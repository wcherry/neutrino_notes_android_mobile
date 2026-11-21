package com.neutrino.notes.core.service

import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.model.DeviceSession
import com.neutrino.notes.core.model.NoteFileInfo
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NoteVersion
import com.neutrino.notes.core.model.OfflineNote
import com.neutrino.notes.core.model.PendingMutation
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.sync.SyncActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.logging.Logger

/** The parts of the content service the sync engine needs, so its conflict rules can be tested without HTTP. */
interface NoteSyncing {
    suspend fun fileInfo(fileId: String): NoteFileInfo?
    suspend fun saveContent(text: String, item: NoteItem, dek: ByteArray, expectedContentVersion: Long?): NoteContentService.SaveResult
}

/** The parts of the drive service it needs to replay queued metadata changes. */
interface NoteMutating {
    suspend fun sendTrash(itemId: String, isFolder: Boolean)
    suspend fun sendMove(itemId: String, isFolder: Boolean, target: String?)
    suspend fun sendRename(itemId: String, name: String, isFolder: Boolean): Instant
}

/**
 * Drains the offline queue whenever the device can transfer.
 *
 * The rule the feature rests on: **a queued edit is uploaded only if the server is still on the
 * version it was written against.** Otherwise the note is flagged as a conflict and left for the
 * user — a queued edit never silently clobbers a remote one. The server owns the verdict: the edit's
 * base `contentVersion` rides along as `expectedContentVersion`; the timestamp check first is only a
 * cheap pre-flight. After an upload the note's links go to the graph, as an online save sends them.
 *
 * Edits drain before metadata, so a note about to be trashed still gets its last edit uploaded.
 * One drain at a time. Main-thread confined.
 */
class SyncEngine(
    private val store: OfflineStore,
    private val content: NoteSyncing,
    private val drive: NoteMutating,
    private val scope: CoroutineScope,
    /** Sends the uploaded text's `[[links]]`; never throws. */
    private val publishLinks: suspend (fileId: String, text: String) -> Unit = { _, _ -> },
    /** Whether the current network may be used, honouring the cellular preference. */
    private val canTransfer: () -> Boolean,
) {
    sealed class State {
        object Idle : State()
        object Offline : State()
        data class Syncing(val remaining: Int) : State()
        data class Failed(val reason: String) : State()

        val message: String
            get() = when (this) {
                Idle -> "Up to date"
                Offline -> "Offline"
                is Syncing -> if (remaining == 1) "Syncing 1 item…" else "Syncing $remaining items…"
                is Failed -> "Sync failed: $reason"
            }
    }

    private val log = Logger.getLogger("SyncEngine")
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    val lastSyncedAt = MutableStateFlow<Instant?>(null)

    private var inFlight: Job? = null

    /** Fire-and-forget; a no-op while a drain is running. */
    fun requestSync() {
        if (inFlight?.isActive == true) return
        inFlight = scope.launch { drain() }
    }

    /** Awaitable — pull to refresh, Sync Now, the background job. Joins a drain already running. */
    suspend fun syncNow() {
        inFlight?.takeIf { it.isActive }?.let { it.join(); return }
        val job = scope.launch { drain() }
        inFlight = job
        job.join()
    }

    fun connectivityChanged(online: Boolean) {
        if (online) requestSync() else {
            inFlight?.cancel()
            _state.value = State.Offline
        }
    }

    private suspend fun drain() {
        if (!canTransfer()) {
            _state.value = State.Offline
            return
        }
        val edits = drainableEdits()
        val mutations = store.drainableMutations()
        if (edits.isEmpty() && mutations.isEmpty()) {
            _state.value = State.Idle
            lastSyncedAt.value = Instant.now()
            return
        }
        var remaining = edits.size + mutations.size
        _state.value = State.Syncing(remaining)
        var lastFailure: String? = null
        for (note in edits) {
            if (!currentCoroutineContext().isActive) break
            uploadEdit(note)?.let { lastFailure = it }
            _state.value = State.Syncing(--remaining)
        }
        for (mutation in mutations) {
            if (!currentCoroutineContext().isActive) break
            replay(mutation)?.let { lastFailure = it }
            _state.value = State.Syncing(--remaining)
        }
        _state.value = when {
            !canTransfer() -> State.Offline
            lastFailure != null -> State.Failed(lastFailure!!)
            else -> State.Idle.also { lastSyncedAt.value = Instant.now() }
        }
    }

    /** Edits out of their backoff window and not conflicted, oldest first. */
    private fun drainableEdits(now: Long = System.currentTimeMillis()): List<OfflineNote> =
        store.notes.value.filter { n -> n.conflict == null && n.pendingEdit?.let { now >= it.nextAttemptAt } == true }
            .sortedBy { it.pendingEdit?.editedAt }

    /** Uploads one queued edit; a failure message, or null on success or a conflict recorded. */
    private suspend fun uploadEdit(note: OfflineNote): String? {
        // Re-read: an earlier iteration, or the editor, may have changed it.
        val current = store.note(note.id) ?: return null
        val edit = current.pendingEdit ?: return null
        if (current.conflict != null) return null
        return try {
            val item = current.asNoteItem
            val info = content.fileInfo(item.id)
            if (info != null && info.isLive && info.updatedAt.toEpochMilli() > edit.baseServerModifiedAt) {
                store.markConflict(note.id, info.updatedAt.toEpochMilli(), info.contentVersion)
                return null
            }
            val (text, dek) = store.readPlaintext(note.id)
            val saved = content.saveContent(text, item, dek, edit.baseContentVersion)
            store.clearPendingEdit(note.id, saved.updatedAt.toEpochMilli(), saved.contentVersion)
            publishLinks(note.id, text)
            null
        } catch (e: NotesApi.ContentVersionConflict) {
            // Something landed between the check and the upload — what the pre-flight cannot catch.
            val serverModifiedAt = runCatching { content.fileInfo(note.id)?.updatedAt?.toEpochMilli() }.getOrNull() ?: System.currentTimeMillis()
            store.markConflict(note.id, serverModifiedAt, e.current)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning("drain: ${note.id} failed: ${e.javaClass.simpleName}")
            store.recordFailure(note.id, e.message ?: "Upload failed")
            e.message ?: "Upload failed"
        }
    }

    private suspend fun replay(mutation: PendingMutation): String? = try {
        when (mutation.kind) {
            PendingMutation.Kind.DELETE -> drive.sendTrash(mutation.itemId, mutation.isFolder)
            PendingMutation.Kind.MOVE -> drive.sendMove(mutation.itemId, mutation.isFolder, mutation.targetFolderId)
            PendingMutation.Kind.RENAME -> { drive.sendRename(mutation.itemId, mutation.newName ?: "", mutation.isFolder) }
        }
        store.removeMutation(mutation.id)
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        store.recordMutationFailure(mutation.id, e.message ?: "Failed")
        e.message ?: "Failed"
    }

    /**
     * Background download: re-fetches a cached note whose copy is behind the server's, so an offline
     * reader opens current text. Anything with unsynced work is skipped — replacing its base would
     * silently resolve a conflict in the server's favour.
     */
    suspend fun refreshCachedNotes() {
        for (note in store.notes.value) {
            if (note.pendingEdit != null || note.conflict != null) continue
            runCatching {
                val info = content.fileInfo(note.id) ?: return@runCatching
                if (info.isLive && info.updatedAt.toEpochMilli() > note.serverModifiedAt) store.download(note.asNoteItem, note.cacheReason)
            }
        }
    }
}

/**
 * A note's version history, through Drive's own versioning. Every snapshot is the ciphertext
 * uploaded at the time and a file's DEK never rotates, so the session's DEK opens every version.
 * Autosave does not snapshot — only an explicit save does, which is why the editor offers one.
 */
class VersionHistoryService(private val api: NotesApi, private val content: NoteContentService) {

    /** Newest first, as the server orders them. */
    suspend fun listVersions(fileId: String): List<NoteVersion> = api.versions(fileId)

    suspend fun versionText(fileId: String, versionId: String, dek: ByteArray): String {
        val ciphertext = api.download(fileId, versionId)
        return withContext(Dispatchers.Default) { content.decryptText(ciphertext, dek) }
    }

    /** A named snapshot. The server also makes it the current content, so this doubles as a save. */
    suspend fun saveVersion(text: String, item: NoteItem, dek: ByteArray, label: String?): NoteVersion {
        val ciphertext = withContext(Dispatchers.Default) { content.encryptText(text, dek) }
        return api.saveVersion(item.id, item.name, item.mimeType ?: NoteItem.MARKDOWN_MIME, ciphertext, label)
    }

    /** Returns the note's new size and modification time. */
    suspend fun restore(versionId: String, fileId: String): Pair<Long, Instant> {
        val file = api.restoreVersion(fileId, versionId)
        return (file.sizeBytes ?: 0) to (DriveDate.parse(file.updatedAt) ?: Instant.now())
    }
}

/** The visible half of device registration: the account's devices, and revoking them. */
class DeviceSessionService(private val api: NotesApi, private val scope: CoroutineScope, private val currentDeviceName: () -> String) {
    val sessions = MutableStateFlow<List<DeviceSession>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** This device first — somebody checking is looking for the ones they don't recognise — then most recent. */
    suspend fun load() {
        isLoading.value = true
        error.value = null
        try {
            sessions.value = api.sessions(currentDeviceName()).sortedWith(order)
        } catch (e: Exception) {
            error.value = e.message
        } finally {
            isLoading.value = false
        }
    }

    fun revoke(id: String) {
        val before = sessions.value
        sessions.value = before.filter { it.id != id }
        scope.launch {
            try {
                api.revokeSession(id)
            } catch (e: Exception) {
                sessions.value = before
                error.value = e.message
            }
        }
    }

    private val order = Comparator<DeviceSession> { a, b ->
        if (a.isCurrentDevice != b.isCurrentDevice) return@Comparator if (a.isCurrentDevice) -1 else 1
        (b.lastUsedAt ?: b.createdAt).compareTo(a.lastUsedAt ?: a.createdAt)
    }
}

/** The engine's state in the shared connectivity banner's terms. */
val SyncEngine.State.activity: SyncActivity
    get() = when (this) {
        is SyncEngine.State.Syncing -> SyncActivity.Syncing(remaining)
        is SyncEngine.State.Failed -> SyncActivity.Failed(reason)
        SyncEngine.State.Idle, SyncEngine.State.Offline -> SyncActivity.Idle
    }
