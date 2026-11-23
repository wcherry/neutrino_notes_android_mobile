package com.neutrino.notes.core

import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.model.DirectoryUser
import com.neutrino.notes.core.model.NotesSection
import com.neutrino.notes.core.model.PendingMutation
import com.neutrino.notes.core.model.ShareRole
import com.neutrino.notes.core.service.LinksService
import com.neutrino.notes.core.service.NoteContentService
import com.neutrino.notes.core.service.NotesDriveService
import com.neutrino.notes.core.service.OfflineStore
import com.neutrino.notes.core.service.SharingService
import com.neutrino.notes.core.service.SyncEngine
import com.neutrino.notes.core.service.TagsService
import com.neutrino.notes.core.service.VersionHistoryService
import com.neutrino.shared.core.FakeServer
import com.neutrino.shared.core.api.NeutrinoApps
import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.crypto.Sodium
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The services against an in-memory server holding real ciphertext: real HTTP, real crypto. The
 * crypto is never mocked — a fake that always "decrypts" asserts nothing.
 */
class ServicesTest {

    private lateinit var fake: FakeServer
    private lateinit var tmp: File
    private lateinit var api: NotesApi
    private lateinit var keys: InMemoryKeyringStore
    private lateinit var content: NoteContentService
    private lateinit var offline: OfflineStore
    private lateinit var drive: NotesDriveService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var online = true

    /** Notes' own routes on top of the shared fake. */
    private val versions = HashMap<String, Long>()
    private val savedVersions = HashMap<String, MutableList<Pair<String, ByteArray>>>()
    private val trashed = HashSet<String>()
    private val tags = LinkedHashMap<String, String>()
    private val fileTags = HashMap<String, MutableSet<String>>()
    private var linkedTitles: List<String>? = null

    @Before
    fun setUp() = runBlocking {
        fake = FakeServer()
        tmp = Files.createTempDirectory("notes-test").toFile()
        fake.extraRoutes = { r, _ -> route(r) }
        api = fake.client(config = NeutrinoApps.notes) { http, auth -> NotesApi(http, auth) }
        api.auth.login(fake.email, fake.password)
        keys = InMemoryKeyringStore()
        val check = DeviceKeyCheck(api, keys)
        KeyManager(api, keys, check).provision()
        content = NoteContentService(api, { keys.load(fake.userId) }, check)
        offline = OfflineStore(File(tmp, "offline"), content)
        drive = NotesDriveService(api, content, offline, scope) { online }
    }

    @After
    fun tearDown() {
        fake.shutdown()
        tmp.deleteRecursively()
    }

    private fun fileJson(id: String) = fake.files[id]!!.let {
        """{"id":"$id","name":"${it.name}","folderId":${it.folderId?.let { f -> "\"$f\"" } ?: "null"},"sizeBytes":${it.blob.size},"mimeType":"${it.mimeType}",""" +
            """"isStarred":false,"updatedAt":"2026-01-0${minOf(9, versions[id]?.toInt() ?: 1)}T00:00:00","contentVersion":${versions[id] ?: 1}}"""
    }

    private fun route(r: RecordedRequest): MockResponse? {
        val path = r.requestUrl!!.encodedPath
        Regex("/api/v1/drive/files/([^/]+)/info").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            if (id !in fake.files) return fake.status(404)
            return fake.ok(fileJson(id).dropLast(1) + ""","yourRole":"owner"${if (id in trashed) ",\"deletedAt\":\"2026-02-01T00:00:00\"" else ""}}""")
        }
        Regex("/api/v1/drive/files/([^/]+)/autosave").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            val current = versions[id] ?: 1
            val expected = r.requestUrl!!.queryParameter("expectedContentVersion")?.toLong()
            if (expected != null && expected != current) {
                return fake.status(409).setBody("""{"error":{"code":"CONTENT_VERSION_CONFLICT","message":"expected $expected, found $current"}}""")
            }
            fake.files[id] = fake.files[id]!!.copy(blob = filePart(r))
            versions[id] = current + 1
            return fake.ok(fileJson(id))
        }
        Regex("/api/v1/drive/files/([^/]+)/versions/([^/]+)/download").matchEntire(path)?.let { m ->
            val blob = savedVersions[m.groupValues[1]]?.firstOrNull { it.first == m.groupValues[2] }?.second ?: return fake.status(404)
            return MockResponse().setResponseCode(200).setBody(okio.Buffer().write(blob))
        }
        Regex("/api/v1/drive/files/([^/]+)/versions").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            if (r.method == "POST") {
                val blob = filePart(r)
                val list = savedVersions.getOrPut(id) { mutableListOf() }
                val vid = "v${list.size + 1}"
                list += vid to blob
                fake.files[id] = fake.files[id]!!.copy(blob = blob)
                return fake.ok("""{"id":"$vid","fileId":"$id","versionNumber":${list.size},"sizeBytes":${blob.size},"label":"named","createdAt":"2026-03-01T00:00:00Z","isNamed":true}""")
            }
            val list = savedVersions[id].orEmpty()
            return fake.ok("""{"versions":[${list.mapIndexed { i, (vid, b) -> """{"id":"$vid","fileId":"$id","versionNumber":${i + 1},"sizeBytes":${b.size},"createdAt":"2026-03-01T00:00:00Z","isNamed":true}""" }.joinToString(",")}],"total":${list.size}}""")
        }
        Regex("/api/v1/drive/files/([^/]+)/tags/([^/]+)").matchEntire(path)?.let { m ->
            val set = fileTags.getOrPut(m.groupValues[1]) { mutableSetOf() }
            if (r.method == "POST") set += m.groupValues[2] else set -= m.groupValues[2]
            return MockResponse().setResponseCode(204)
        }
        Regex("/api/v1/drive/files/([^/]+)/tags").matchEntire(path)?.let { m ->
            return fake.ok(fileTags[m.groupValues[1]].orEmpty().joinToString(",", "[", "]") { """{"id":"$it","name":"${tags[it]}","createdAt":"2026-01-01T00:00:00"}""" })
        }
        if (path == "/api/v1/drive/tags") {
            if (r.method == "POST") {
                val name = Regex("\"name\":\"([^\"]+)\"").find(r.body.readUtf8())!!.groupValues[1]
                if (name in tags.values) return fake.status(409)
                val id = "t${tags.size + 1}"
                tags[id] = name
                return fake.ok("""{"id":"$id","name":"$name","createdAt":"2026-01-01T00:00:00","fileCount":0}""")
            }
            return fake.ok("""{"tags":[${tags.entries.joinToString(",") { """{"id":"${it.key}","name":"${it.value}","createdAt":"2026-01-01T00:00:00"}""" }}],"total":${tags.size}}""")
        }
        Regex("/api/v1/drive/tags/([^/]+)/files").matchEntire(path)?.let { m ->
            val ids = fileTags.filterValues { m.groupValues[1] in it }.keys
            val offset = r.requestUrl!!.queryParameter("offset")!!.toInt()
            val page = ids.drop(offset).take(1)
            return fake.ok("""{"files":[${page.joinToString(",") { fileJson(it) }}],"total":${ids.size}}""")
        }
        Regex("/api/v1/links/([^/]+)").matchEntire(path)?.let {
            val body = r.body.readUtf8()
            linkedTitles = Regex("\"([^\"]+)\"").findAll(body.substringAfter("[")).map { it.groupValues[1] }.toList()
            return fake.ok("""{"backlinks":[{"id":"x","title":"Linker.md","fileType":"note"}]}""")
        }
        if (path == "/api/v1/drive/bulk/trash") {
            Regex("\"fileIds\":\\[\"([^\"]+)\"").find(r.body.readUtf8())?.let { trashed += it.groupValues[1] }
            return fake.ok("""{"affected":1}""")
        }
        Regex("/api/v1/drive/files/([^/]+)").matchEntire(path)?.let { m ->
            if (r.method == "PATCH") {
                val name = Regex("\"name\":\"([^\"]+)\"").find(r.body.readUtf8())?.groupValues?.get(1)
                name?.let { fake.files[m.groupValues[1]] = fake.files[m.groupValues[1]]!!.copy(name = it) }
                return fake.ok(fileJson(m.groupValues[1]))
            }
        }
        return null
    }

    private fun filePart(r: RecordedRequest): ByteArray {
        val boundary = r.getHeader("Content-Type")!!.substringAfter("boundary=")
        MultipartReader(r.body, boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                if ("name=\"file\"" in part.headers["Content-Disposition"]!!) return part.body.readByteArray()
                part.body.readByteArray()
            }
        }
        error("no file part")
    }

    private val secret = "# Plan\n\nSecret quarterly numbers [[Budget]]"

    @Test
    fun aNoteIsCreatedEncryptedSavedAndReadBack() = runBlocking {
        val item = drive.createNote("Plan", null)
        assertEquals("Plan.md", item.name)
        assertEquals("text/markdown", fake.files[item.id]!!.mimeType)
        assertTrue("encrypted_file_key" in fake.lastUploadParts)
        val (empty, dek) = content.loadContent(item)
        assertEquals("", empty)
        content.saveContent(secret, item, dek, item.contentVersion)
        assertFalse(String(fake.files[item.id]!!.blob, Charsets.ISO_8859_1).contains("quarterly"))
        assertEquals(secret, content.loadContent(item).first)
        // What the web writes — plain secretstream over UTF-8 — reads here too.
        val web = FileCrypto.encryptFile("from the web ✓".toByteArray(), dek)
        assertEquals("from the web ✓", content.decryptText(web, dek))
    }

    @Test
    fun aStaleSaveIsRefusedByTheServer() = runBlocking {
        val item = drive.createNote("Plan", null)
        val (_, dek) = content.loadContent(item)
        content.saveContent(secret, item, dek, 1)
        try {
            content.saveContent(secret, item, dek, 1)
            fail()
        } catch (e: NotesApi.ContentVersionConflict) {
            assertEquals(2L, e.current)
        }
    }

    @Test
    fun listingsKeepOnlyMarkdownAndTheRootIsNull() = runBlocking {
        val note = drive.createNote("Plan", null)
        drive.createFolder("Work", null)
        drive.load(NotesSection.MY_NOTES)
        assertEquals(setOf("Plan", "Work"), drive.items(NotesSection.MY_NOTES).map { it.displayName }.toSet())
        assertEquals(note.id, drive.fetchItem(note.id).id)
    }

    @Test
    fun duplicateIsANewFileUnderAFreshKey() = runBlocking {
        val item = drive.createNote("Plan", null)
        content.saveContent(secret, item, content.loadContent(item).second, null)
        val copy = drive.duplicate(item.id)
        assertEquals("Plan copy.md", copy.name)
        assertEquals(secret, content.loadContent(copy).first)
        assertFalse(fake.keyRefs[item.id]!!.values.first().first == fake.keyRefs[copy.id]!!.values.first().first)
    }

    /** A server that is down while the phone is online is a state to show, not an error dialog. */
    @Test
    fun anUnreachableServerIsReportedInlineNotAsAnError() = runBlocking {
        drive.load(com.neutrino.notes.core.model.NotesSection.MY_NOTES)
        assertFalse(drive.unreachable.value)
        fake.shutdown()
        online = true
        drive.load(com.neutrino.notes.core.model.NotesSection.MY_NOTES)
        assertTrue(drive.unreachable.value)
        assertNull(drive.error.value)
    }

    @Test
    fun anOfflineEditQueuesAsCiphertextAndDrainsWithItsLinks() = runBlocking {
        val item = drive.createNote("Plan", null)
        offline.download(item)
        val (_, dek) = offline.readPlaintext(item.id)
        offline.writePendingEdit(secret, item.id, dek)
        assertEquals(1, offline.pendingEditCount)
        tmp.walk().filter { it.isFile }.forEach { assertFalse(it.name, String(it.readBytes(), Charsets.ISO_8859_1).contains("quarterly")) }
        assertEquals(secret, offline.readPlaintext(item.id).first)

        val links = LinksService(api)
        val engine = SyncEngine(offline, content, drive, scope, { id, text -> links.updateLinksIgnoringFailure(id, text) }) { true }
        engine.syncNow()
        assertEquals(0, offline.pendingEditCount)
        assertEquals(SyncEngine.State.Idle, engine.state.value)
        assertEquals(secret, content.loadContent(item).first)
        assertEquals(listOf("Budget", "Budget.md"), linkedTitles)
        assertEquals("Linker", links.backlinks(item.id).single().displayTitle)
    }

    @Test
    fun aQueuedEditNeverOverwritesARemoteOne() = runBlocking {
        val item = drive.createNote("Plan", null)
        offline.download(item)
        val (_, dek) = offline.readPlaintext(item.id)
        offline.writePendingEdit(secret, item.id, dek)
        content.saveContent("theirs", item, dek, null)
        SyncEngine(offline, content, drive, scope) { true }.syncNow()
        assertNotNull(offline.note(item.id)!!.conflict)
        assertEquals("theirs", content.loadContent(item).first)
        offline.resolveKeepingLocal(item.id)
        SyncEngine(offline, content, drive, scope) { true }.syncNow()
        assertEquals(secret, content.loadContent(item).first)
        assertNull(offline.note(item.id)!!.conflict)
    }

    @Test
    fun deletesAndRenamesMadeOfflineReplayAndSupersede() = runBlocking {
        val item = drive.createNote("Plan", null)
        online = false
        drive.rename(item.id, "Renamed")
        assertEquals("Renamed.md", drive.item(item.id)!!.name)
        drive.rename(item.id, "Again")
        assertEquals(1, offline.pendingMutations.value.size)
        drive.delete(item.id)
        assertEquals(listOf(PendingMutation.Kind.DELETE), offline.pendingMutations.value.map { it.kind })
        online = true
        SyncEngine(offline, content, drive, scope) { true }.syncNow()
        assertTrue(item.id in trashed)
        assertTrue(offline.pendingMutations.value.isEmpty())
    }

    @Test
    fun aNamedVersionIsSavedListedAndRead() = runBlocking {
        val item = drive.createNote("Plan", null)
        val dek = content.loadContent(item).second
        val history = VersionHistoryService(api, content)
        assertEquals("named (v1)", history.saveVersion(secret, item, dek, "named").displayTitle)
        assertEquals(listOf("v1"), history.listVersions(item.id).map { it.id })
        assertEquals(secret, history.versionText(item.id, "v1", dek))
    }

    @Test
    fun tagsAreCreatedAppliedAsADiffAndListed() = runBlocking {
        val item = drive.createNote("Plan", null)
        val service = TagsService(api, scope)
        val work = service.createTag("Work")
        val home = service.createTag("Home")
        try {
            service.createTag("Work")
            fail()
        } catch (e: com.neutrino.notes.core.service.DuplicateTagName) { }
        service.loadTags(item.id)
        service.applyTags(setOf(work.id, home.id), item.id)
        assertEquals(listOf("Home", "Work"), service.tags(item.id).map { it.name })
        service.applyTags(setOf(home.id), item.id)
        assertEquals(setOf(home.id), fileTags[item.id])
        assertEquals(listOf(item.id), service.notes(home.id).map { it.id })
    }

    @Test
    fun sharingGrantsAccessAndDeliversAKeyTheRecipientCanOpen() = runBlocking {
        val item = drive.createNote("Plan", null)
        content.saveContent(secret, item, content.loadContent(item).second, null)
        val recipient = Sodium.boxKeyPair()
        fake.users["user-2"] = "b@example.com"
        fake.publishedKeys["user-2"] = mutableListOf(FakeServer.Published(B64.encode(recipient.publicKey), 3))
        val sharing = SharingService(api, content, scope) { fake.userId }
        val user = sharing.lookupUser("b@example.com")
        val result = sharing.share(item, user, ShareRole.EDITOR)
        assertEquals(1, result.keysDelivered)
        assertNull(result.summary)
        val (sealed, version) = fake.keyRefs[item.id]!!["user-2"]!!
        assertEquals(3, version)
        val dek = FileCrypto.openSealedDek(sealed, recipient.secretKey)!!
        assertEquals(secret, content.decryptText(fake.files[item.id]!!.blob, dek))

        // Someone with no key yet: shared, and told so.
        fake.users["user-3"] = "c@example.com"
        val noKey = sharing.share(item, DirectoryUser("user-3", "c@example.com", ""), ShareRole.VIEWER)
        assertTrue(noKey.recipientHasNoKey)
        try {
            sharing.share(item, DirectoryUser(fake.userId, fake.email, ""), ShareRole.VIEWER)
            fail()
        } catch (e: com.neutrino.notes.core.service.SharingException.CannotShareWithYourself) { }
    }
}
