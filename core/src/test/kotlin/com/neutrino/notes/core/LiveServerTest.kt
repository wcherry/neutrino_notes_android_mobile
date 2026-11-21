package com.neutrino.notes.core

import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.model.NotesSection
import com.neutrino.notes.core.model.ShareRole
import com.neutrino.notes.core.service.LinksService
import com.neutrino.notes.core.service.NoteContentService
import com.neutrino.notes.core.service.NotesDriveService
import com.neutrino.notes.core.service.OfflineStore
import com.neutrino.notes.core.service.SharingService
import com.neutrino.notes.core.service.SyncEngine
import com.neutrino.notes.core.service.TagsService
import com.neutrino.notes.core.service.VersionHistoryService
import com.neutrino.shared.core.MemorySessionStore
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.NeutrinoApps
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

/**
 * Against a real server, when `NEUTRINO_LIVE_URL` names one (a throwaway database with the
 * `neutrino-notes-android` migration, 00160). Registers two fresh accounts each run.
 */
class LiveServerTest {

    private class Account(url: String, name: String) {
        val http = OkHttpClient()
        val auth = AuthClient(NeutrinoApps.notes, http, MemorySessionStore(), url) { "JVM test - Neutrino Notes" }
        val api = NotesApi(http, auth)
        val keys = InMemoryKeyringStore()
        val email = "$name-${UUID.randomUUID().toString().take(8)}@example.com"
        lateinit var content: NoteContentService

        suspend fun setUp() {
            auth.register(email.substringBefore('-'), email, "correct horse battery")
            val check = DeviceKeyCheck(api, keys)
            KeyManager(api, keys, check).provision()
            content = NoteContentService(api, { keys.load(auth.userId!!) }, check)
        }
    }

    @Test
    fun theWholeAppAgainstARealServer() = runBlocking<Unit> {
        val url = System.getenv("NEUTRINO_LIVE_URL")
        assumeTrue("set NEUTRINO_LIVE_URL to run", url != null)
        val me = Account(url!!, "notes").apply { setUp() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tmp = Files.createTempDirectory("live").toFile()
        val offline = OfflineStore(tmp, me.content)
        val drive = NotesDriveService(me.api, me.content, offline, scope) { true }

        // Create, list, save under the version guard, read back; a stale save is refused.
        val budget = drive.createNote("Budget", null)
        val plan = drive.createNote("Live Plan", null)
        assertEquals("Live Plan.md", plan.name)
        drive.load(NotesSection.MY_NOTES)
        assertTrue(drive.items(NotesSection.MY_NOTES).any { it.id == plan.id })
        val info = me.api.fileInfo(plan.id)!!
        assertEquals(ShareRole.OWNER, info.yourRole)
        val (_, dek) = me.content.loadContent(plan)
        val text = "# Plan\n\n- [ ] see [[Budget]]\n"
        val saved = me.content.saveContent(text, plan, dek, info.contentVersion)
        assertEquals(text, me.content.loadContent(plan).first)
        try {
            me.content.saveContent("stale", plan, dek, info.contentVersion)
            fail("a stale save should be refused")
        } catch (e: NotesApi.ContentVersionConflict) { }

        // The link graph: Plan links to Budget, so Budget's backlinks name Plan.
        val links = LinksService(me.api)
        links.updateLinksIgnoringFailure(plan.id, text)
        assertEquals(listOf(plan.id), links.loadBacklinks(budget.id).map { it.id })

        // Tags.
        val tags = TagsService(me.api, scope)
        val tag = tags.createTag("live-${UUID.randomUUID().toString().take(4)}")
        tags.loadTags(plan.id)
        tags.applyTags(setOf(tag.id), plan.id)
        assertEquals(listOf(tag.id), tags.loadTags(plan.id).map { it.id })
        assertEquals(listOf(plan.id), tags.notes(tag.id).map { it.id })

        // Versions.
        val history = VersionHistoryService(me.api, me.content)
        val version = history.saveVersion("# Plan v2\n", plan.copy(contentVersion = saved.contentVersion), dek, "named")
        assertTrue(history.listVersions(plan.id).any { it.id == version.id })
        assertEquals("# Plan v2\n", history.versionText(plan.id, version.id, dek))
        history.restore(version.id, plan.id)

        // An offline edit drains through the sync engine.
        offline.download(plan.copy(contentVersion = me.api.fileInfo(plan.id)!!.contentVersion))
        offline.writePendingEdit("offline edit", plan.id, dek)
        SyncEngine(offline, me.content, drive, scope, { id, t -> links.updateLinksIgnoringFailure(id, t) }) { true }.syncNow()
        assertEquals(0, offline.pendingEditCount)
        assertEquals("offline edit", me.content.loadContent(plan).first)

        // Sharing: another account gets access and a key it can open.
        val them = Account(url, "friend").apply { setUp() }
        val sharing = SharingService(me.api, me.content, scope) { me.auth.userId }
        val friend = sharing.lookupUser(them.email)
        val result = sharing.share(plan, friend, ShareRole.EDITOR)
        assertEquals(1, result.keysDelivered)
        sharing.loadPermissions(plan)
        assertTrue(sharing.permissions(plan).any { it.userId == friend.id && it.role == ShareRole.EDITOR })
        val theirDrive = NotesDriveService(them.api, them.content, OfflineStore(Files.createTempDirectory("live2").toFile(), them.content), scope) { true }
        theirDrive.load(NotesSection.SHARED)
        val shared = theirDrive.items(NotesSection.SHARED).first { it.id == plan.id }
        assertEquals(ShareRole.EDITOR, them.api.fileInfo(plan.id)!!.yourRole)
        assertEquals("offline edit", them.content.loadContent(shared).first)

        // Organising: rename keeps .md, star, trash.
        drive.rename(budget.id, "Budget 2026")
        delay(500)
        assertEquals("Budget 2026.md", me.api.fileInfo(budget.id)!!.name)
        drive.delete(budget.id)
        delay(500)
        assertNotNull(me.api.fileInfo(budget.id)?.deletedAt)
        tmp.deleteRecursively()
    }
}
