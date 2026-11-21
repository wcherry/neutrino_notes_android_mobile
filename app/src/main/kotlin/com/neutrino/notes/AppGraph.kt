package com.neutrino.notes

import android.content.Context
import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.model.CacheReason
import com.neutrino.notes.core.service.DeviceSessionService
import com.neutrino.notes.core.service.FileEventsClient
import com.neutrino.notes.core.service.LinksService
import com.neutrino.notes.core.service.NoteContentService
import com.neutrino.notes.core.service.NotesDriveService
import com.neutrino.notes.core.service.OfflineStore
import com.neutrino.notes.core.service.SharingService
import com.neutrino.notes.core.service.SyncEngine
import com.neutrino.notes.core.service.TagsService
import com.neutrino.notes.core.service.VersionHistoryService
import com.neutrino.shared.android.NeutrinoAccount
import com.neutrino.shared.android.net.NetworkMonitor
import com.neutrino.shared.core.api.NeutrinoApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

/**
 * Every long-lived object, built once per process in `Application.onCreate` — the background sync
 * job can start the process with no screen. The iOS app's composition root. Main-thread confined.
 */
class AppGraph(context: Context) {
    val context: Context = context.applicationContext

    /** Sign-in, the session, the keyring and the HTTP client, from neutrino_shared_android. */
    val account = NeutrinoAccount(this.context, NeutrinoApps.notes, "NeutrinoNotes-Android/${BuildConfig.VERSION_NAME}") { http, auth -> NotesApi(http, auth) }
    val api: NotesApi get() = account.api
    val prefs = NotesPrefs(account.settings.prefs)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val network = NetworkMonitor(this.context)
    val isOnline: StateFlow<Boolean> = network.isOnline
    val isMetered: StateFlow<Boolean> = network.isMetered

    val content = NoteContentService(account.api, { account.keys.keyring() }, account.keyCheck)
    val offline = OfflineStore(File(this.context.noBackupFilesDir, "offline-notes"), content)
    val drive = NotesDriveService(account.api, content, offline, scope) { isOnline.value }
    val links = LinksService(account.api)
    val sync = SyncEngine(offline, content, drive, scope, { id, text -> links.updateLinksIgnoringFailure(id, text) }) {
        isOnline.value && (prefs.syncOnCellular.value || !isMetered.value)
    }
    val versions = VersionHistoryService(account.api, content)
    val tags = TagsService(account.api, scope)
    val sharing = SharingService(account.api, content, scope) { account.auth.userId }
    val sessions = DeviceSessionService(account.api, scope) { account.auth.config.headerSafeDeviceName(account.settings.deviceName) }
    val router = AppRouter()

    /** One relay socket for the note on screen. */
    fun fileEvents(): FileEventsClient = FileEventsClient(account.http, account.auth, scope)

    init {
        account.onSignOut {
            // Nothing decrypted for this account may outlive its session; the next account starts clean.
            drive.reset()
            offline.wipe()
            tags.reset()
            links.forgetAll()
            sharing.reset()
            File(this.context.cacheDir, "exports").deleteRecursively()
            router.reset()
        }
        network.start()
        // Drains the queue on reconnecting; going offline stops a drain mid-flight.
        isOnline.drop(1).onEach { online ->
            if (account.auth.isSignedIn && (prefs.autoSync.value || !online)) sync.connectivityChanged(online)
        }.launchIn(scope)
    }

    /** After sign-in: drain anything queued. */
    fun signedIn() {
        if (prefs.autoSync.value) sync.requestSync()
    }

    /** Offline mode's "cache recent / favorites": what the listings brought in, kept automatically. */
    fun cacheAutomatically() {
        if (!isOnline.value) return
        scope.launch {
            val wanted = buildList {
                if (prefs.cacheRecents.value) addAll(drive.recentItems.value.take(10).map { it to CacheReason.RECENT })
                if (prefs.cacheFavorites.value) addAll(drive.starredItems.value.filter { it.isNote }.map { it to CacheReason.FAVORITE })
            }
            for ((item, reason) in wanted) {
                if (offline.isAvailableOffline(item.id)) continue
                runCatching { offline.download(item, reason) }
            }
        }
    }
}
