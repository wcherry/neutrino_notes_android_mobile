package com.neutrino.notes.core.service

import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.markdown.WikiLink
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NotesSection
import com.neutrino.notes.core.model.PendingMutation
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FolderDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

class NoteNotFound : Exception("That note is no longer available.")

/**
 * Browses and organises the notes in Neutrino Drive through Drive's own folder, file and trash APIs.
 * Changes are optimistic: the local model moves first and is rolled back if the server refuses.
 * Offline, deletes, moves and renames are queued in [OfflineStore]. Main-thread confined.
 */
class NotesDriveService(
    private val api: NotesApi,
    private val content: NoteContentService,
    private val offline: OfflineStore,
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean,
) : NoteMutating {
    private val log = Logger.getLogger("NotesDriveService")

    /** The user's own folders and notes, every folder visited so far. Also backs the move picker. */
    val allItems = MutableStateFlow<List<NoteItem>>(emptyList())
    val recentItems = MutableStateFlow<List<NoteItem>>(emptyList())
    val starredItems = MutableStateFlow<List<NoteItem>>(emptyList())
    val sharedItems = MutableStateFlow<List<NoteItem>>(emptyList())
    val trashItems = MutableStateFlow<List<NoteItem>>(emptyList())

    private val _loading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _loading
    val error = MutableStateFlow<String?>(null)

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────

    fun items(section: NotesSection, parentId: String? = null): List<NoteItem> = when (section) {
        NotesSection.MY_NOTES -> allItems.value.filter { it.parentId == parentId }
        NotesSection.RECENT -> recentItems.value
        NotesSection.FAVORITES -> starredItems.value
        NotesSection.SHARED -> sharedItems.value
        NotesSection.TRASH -> trashItems.value
    }

    /** An item wherever it currently lives: one reached from Recent or Shared was never in a folder listing. */
    fun item(id: String): NoteItem? =
        (allItems.value + recentItems.value + starredItems.value + sharedItems.value + trashItems.value).firstOrNull { it.id == id }

    /** Whether `child` is `folderId` itself or beneath it — moving a folder into its own subtree would detach it. */
    fun isDescendant(child: String?, folderId: String): Boolean {
        var current = child
        var guard = allItems.value.size + 1
        while (current != null && guard-- > 0) {
            if (current == folderId) return true
            current = allItems.value.firstOrNull { it.id == current }?.parentId
        }
        return false
    }

    /** One note's metadata straight from the server, for a link naming a note no listing has loaded. */
    suspend fun fetchItem(id: String): NoteItem {
        api.fileInfo(id)?.let { info ->
            if (!NoteItem.isMarkdown(info.mimeType) || !info.isLive) throw NoteNotFound()
            val owned = info.yourRole == com.neutrino.notes.core.model.ShareRole.OWNER
            return NoteItem(info.id, info.name, NoteItem.ItemType.FILE, if (owned) info.folderId else null, info.sizeBytes, info.updatedAt,
                mimeType = info.mimeType, contentVersion = info.contentVersion, isShared = !owned)
        }
        throw NoteNotFound()
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────────

    suspend fun load(section: NotesSection, parentId: String? = null) {
        _loading.value = true
        error.value = null
        try {
            when (section) {
                NotesSection.MY_NOTES -> {
                    val listing = api.noteFolder(parentId)
                    val folders = listing.folders.map(::folder)
                    val files = listing.files.filter { NoteItem.isMarkdown(it.mimeType) }.map { NoteContentService.item(it) }
                    // A listing's folder ids name the parent; null is the root even though the server spells it as the user's id.
                    allItems.update { items -> items.filter { it.parentId != parentId } + (folders + files).map { if (parentId == null) it.copy(parentId = null) else it } }
                }
                NotesSection.RECENT -> recentItems.value = api.recent(RECENT_LIMIT).files.filter { NoteItem.isMarkdown(it.mimeType) }.map { NoteContentService.item(it) }
                NotesSection.FAVORITES -> {
                    val listing = api.starred()
                    starredItems.value = listing.folders.map(::folder) + listing.files.filter { NoteItem.isMarkdown(it.mimeType) }.map { NoteContentService.item(it) }
                }
                NotesSection.SHARED -> {
                    val listing = api.sharedWithMe()
                    // Somebody else's folder: its parent is a folder this account cannot list, and its star is the owner's.
                    sharedItems.value = listing.folders.map { folder(it).copy(parentId = null, isStarred = false, isShared = true) } +
                        listing.files.filter { NoteItem.isMarkdown(it.mimeType) }.map { NoteContentService.item(it, isShared = true) }
                }
                NotesSection.TRASH -> {
                    val listing = api.trash()
                    trashItems.value = listing.folders.map {
                        NoteItem(it.id, it.name, NoteItem.ItemType.FOLDER, null, modifiedAt = DriveDate.parse(it.deletedAt) ?: Instant.EPOCH, isTrashed = true)
                    } + listing.files.filter { NoteItem.isMarkdown(it.mimeType) }.map {
                        NoteItem(it.id, it.name, NoteItem.ItemType.FILE, null, it.sizeBytes, DriveDate.parse(it.deletedAt) ?: Instant.EPOCH, isTrashed = true, mimeType = it.mimeType)
                    }
                }
            }
        } catch (e: Exception) {
            // Offline, a failed refresh is expected: the listing already on screen stays, and the banner says why.
            if (isOnline()) error.value = e.message
        } finally {
            _loading.value = false
        }
    }

    // ── Wiki-link index ─────────────────────────────────────────────────────────────────────────

    /** Every note this account owns, wherever it lives: what a `[[title]]` resolves against. */
    val indexedNotes = MutableStateFlow<List<NoteItem>>(emptyList())
    private var noteIndexLoadedAt: Long? = null

    /** Everything a `[[title]]` could name: the walk plus what the other listings brought in (shared notes among them). */
    val linkableNotes: List<NoteItem>
        get() = (indexedNotes.value + allItems.value + recentItems.value + starredItems.value + sharedItems.value)
            .filter { it.type == NoteItem.ItemType.FILE && !it.isTrashed }.associateBy { it.id }.values.toList()

    /**
     * Rebuilds [indexedNotes] by walking the folder tree — one `?type=note` listing per folder, as
     * there is no whole-drive listing any more. Bounded, and silent on failure: this feeds link
     * highlighting and a picker, and a folder that doesn't answer costs some links their highlight.
     */
    suspend fun loadNoteIndex(force: Boolean = false) {
        val loadedAt = noteIndexLoadedAt
        if (!force && loadedAt != null && System.currentTimeMillis() - loadedAt < NOTE_INDEX_TTL_MS) return
        val root = runCatching { api.rootFolderId() }.getOrNull() ?: return
        val notes = ArrayList<NoteItem>()
        val pending = ArrayDeque(listOf(root))
        val visited = hashSetOf(root)
        var requests = 0
        while (pending.isNotEmpty() && requests < NOTE_INDEX_FOLDER_BUDGET) {
            val id = pending.removeFirst()
            requests++
            val listing = runCatching { api.noteFolder(id) }.getOrNull() ?: continue
            notes += listing.files.filter { NoteItem.isMarkdown(it.mimeType) }.map { NoteContentService.item(it) }
            listing.folders.forEach { if (visited.add(it.id)) pending += it.id }
        }
        if (pending.isNotEmpty()) log.warning("loadNoteIndex: stopped at $NOTE_INDEX_FOLDER_BUDGET folders — the index is partial")
        indexedNotes.value = notes
        noteIndexLoadedAt = System.currentTimeMillis()
    }

    /** A create, rename or trash changes what a `[[title]]` resolves to. */
    fun invalidateNoteIndex() {
        noteIndexLoadedAt = null
    }

    // ── Create ──────────────────────────────────────────────────────────────────────────────────

    fun createFolder(name: String, parentId: String?) {
        val placeholder = NoteItem(UUID.randomUUID().toString(), name, NoteItem.ItemType.FOLDER, parentId, modifiedAt = Instant.now())
        allItems.update { it + placeholder }
        scope.launch {
            try {
                val created = folder(api.createFolder(name, parentId)).copy(parentId = parentId)
                allItems.update { items -> items.map { if (it.id == placeholder.id) created else it } }
            } catch (e: Exception) {
                allItems.update { items -> items.filter { it.id != placeholder.id } }
                error.value = e.message
            }
        }
    }

    /** Needs the network: a new file has no id to queue work against. The name gets `.md`, as iOS's do. */
    suspend fun createNote(title: String, parentId: String?, text: String = ""): NoteItem {
        val item = content.createNote(WikiLink.fileName(title), parentId, text)
        noteWasCreated(item.copy(parentId = parentId))
        return item.copy(parentId = parentId)
    }

    fun noteWasCreated(item: NoteItem) {
        allItems.update { it + item }
        // Immediately linkable by name, without waiting out the index's TTL.
        indexedNotes.update { it + item }
    }

    /** Drive has no copy endpoint: a duplicate is decrypted and uploaded as a new file under a fresh DEK. */
    suspend fun duplicate(itemId: String): NoteItem {
        val source = item(itemId)?.takeIf { it.isNote } ?: throw NoteNotFound()
        val (text, _) = content.loadContent(source)
        return createNote("${source.displayName} copy", if (source.isShared) null else source.parentId, text)
    }

    // ── Rename ──────────────────────────────────────────────────────────────────────────────────

    /** A note keeps its `.md` whatever was typed: the dialog shows the title, and "Q3" means `Q3.md`. */
    fun rename(itemId: String, newName: String) {
        val existing = item(itemId) ?: return
        val previousName = existing.name
        val previousModifiedAt = existing.modifiedAt
        val finalName = if (existing.isFolder) newName.trim() else WikiLink.fileName(newName)
        applyEverywhere(itemId) { it.copy(name = finalName) }
        offline.updateName(itemId, finalName)
        // A note's name *is* its wiki-link title.
        invalidateNoteIndex()

        if (!isOnline()) {
            offline.enqueue(PendingMutation(itemId = itemId, kind = PendingMutation.Kind.RENAME, isFolder = existing.isFolder, newName = finalName, queuedAt = System.currentTimeMillis()))
            return
        }
        scope.launch {
            try {
                val updatedAt = sendRename(itemId, finalName, existing.isFolder)
                // A rename bumps `updated_at` without touching content: keep the cache's idea of the server in step.
                offline.rebasePendingEdit(itemId, previousModifiedAt.toEpochMilli(), updatedAt.toEpochMilli())
            } catch (e: Exception) {
                applyEverywhere(itemId) { it.copy(name = previousName) }
                offline.updateName(itemId, previousName)
                error.value = e.message
            }
        }
    }

    override suspend fun sendRename(itemId: String, name: String, isFolder: Boolean): Instant =
        DriveDate.parse(if (isFolder) api.updateFolder(itemId, name = name).updatedAt else api.updateFile(itemId, name = name).updatedAt) ?: Instant.now()

    // ── Favorites ───────────────────────────────────────────────────────────────────────────────

    /** Offline, starring is reverted with a message: it has no safe offline semantics. */
    fun setStarred(itemId: String, starred: Boolean) {
        val item = item(itemId) ?: return
        val previousModifiedAt = item.modifiedAt
        applyStarred(item.copy(isStarred = starred))
        if (!isOnline()) {
            applyStarred(item.copy(isStarred = !starred))
            error.value = "Favorites need a connection."
            return
        }
        scope.launch {
            try {
                val updatedAt = if (item.isFolder) api.updateFolder(itemId, isStarred = starred).updatedAt else api.updateFile(itemId, isStarred = starred).updatedAt
                DriveDate.parse(updatedAt)?.let { offline.rebasePendingEdit(itemId, previousModifiedAt.toEpochMilli(), it.toEpochMilli()) }
            } catch (e: Exception) {
                applyStarred(item.copy(isStarred = !starred))
                error.value = e.message
            }
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────────────────────────────

    /** Moves an item to Trash, or deletes it for good when it is already there. */
    fun delete(itemId: String) {
        invalidateNoteIndex()
        if (trashItems.value.any { it.id == itemId }) permanentlyDelete(itemId) else trash(itemId)
    }

    private fun trash(itemId: String) {
        val item = allItems.value.firstOrNull { it.id == itemId } ?: recentItems.value.firstOrNull { it.id == itemId }
            ?: starredItems.value.firstOrNull { it.id == itemId } ?: return
        allItems.update { items -> items.filter { it.id != itemId } }
        trashItems.update { it + item.copy(isTrashed = true, modifiedAt = Instant.now()) }
        // Trashed items leave both server-side views; a Favorites row must not open a deleted note.
        starredItems.update { items -> items.filter { it.id != itemId } }
        recentItems.update { items -> items.filter { it.id != itemId } }
        indexedNotes.update { items -> items.filter { it.id != itemId } }

        if (!isOnline()) {
            offline.enqueue(PendingMutation(itemId = itemId, kind = PendingMutation.Kind.DELETE, isFolder = item.isFolder, queuedAt = System.currentTimeMillis()))
            return
        }
        scope.launch {
            try {
                sendTrash(itemId, item.isFolder)
            } catch (e: Exception) {
                trashItems.update { items -> items.filter { it.id != itemId } }
                allItems.update { it + item }
                if (item.isStarred) applyStarred(item)
                error.value = e.message
            }
        }
    }

    override suspend fun sendTrash(itemId: String, isFolder: Boolean) {
        if (isFolder) api.bulkTrash(emptyList(), listOf(itemId)) else api.bulkTrash(listOf(itemId), emptyList())
    }

    private fun permanentlyDelete(itemId: String) {
        val item = trashItems.value.firstOrNull { it.id == itemId } ?: return
        trashItems.update { items -> items.filter { it.id != itemId } }
        scope.launch {
            try {
                api.deleteForever(itemId, item.isFolder)
            } catch (e: Exception) {
                trashItems.update { it + item }
                error.value = e.message
            }
        }
    }

    // ── Move ────────────────────────────────────────────────────────────────────────────────────

    fun move(itemId: String, newParentId: String?) {
        val item = allItems.value.firstOrNull { it.id == itemId } ?: return
        if (newParentId != null && isDescendant(newParentId, itemId)) return
        val previousParent = item.parentId
        allItems.update { items -> items.map { if (it.id == itemId) it.copy(parentId = newParentId) else it } }
        offline.updateParent(itemId, newParentId)
        if (!isOnline()) {
            offline.enqueue(PendingMutation(itemId = itemId, kind = PendingMutation.Kind.MOVE, isFolder = item.isFolder, targetFolderId = newParentId, queuedAt = System.currentTimeMillis()))
            return
        }
        scope.launch {
            try {
                sendMove(itemId, item.isFolder, newParentId)
            } catch (e: Exception) {
                allItems.update { items -> items.map { if (it.id == itemId) it.copy(parentId = previousParent) else it } }
                offline.updateParent(itemId, previousParent)
                error.value = e.message
            }
        }
    }

    override suspend fun sendMove(itemId: String, isFolder: Boolean, target: String?) {
        if (isFolder) api.bulkMove(emptyList(), listOf(itemId), target) else api.bulkMove(listOf(itemId), emptyList(), target)
    }

    // ── Trash ───────────────────────────────────────────────────────────────────────────────────

    fun restore(itemId: String) {
        val item = trashItems.value.firstOrNull { it.id == itemId } ?: return
        trashItems.update { items -> items.filter { it.id != itemId } }
        // Trash does not say where an item came from; it reappears wherever the next listing finds it.
        invalidateNoteIndex()
        scope.launch {
            try {
                api.restoreFromTrash(itemId, item.isFolder)
            } catch (e: Exception) {
                trashItems.update { it + item }
                error.value = e.message
            }
        }
    }

    fun emptyTrash() {
        val snapshot = trashItems.value
        trashItems.value = emptyList()
        scope.launch {
            try {
                api.emptyTrash()
            } catch (e: Exception) {
                trashItems.value = snapshot
                error.value = e.message
            }
        }
    }

    // ── Editor callbacks ────────────────────────────────────────────────────────────────────────

    /** After a save, so the listing's size and date stay in step without a refetch. */
    fun noteWasSaved(itemId: String, size: Long, modifiedAt: Instant, contentVersion: Long?) {
        applyEverywhere(itemId) { it.copy(size = size, modifiedAt = modifiedAt, contentVersion = contentVersion ?: it.contentVersion) }
    }

    fun reset() {
        listOf(allItems, recentItems, starredItems, sharedItems, trashItems, indexedNotes).forEach { it.value = emptyList() }
        noteIndexLoadedAt = null
        error.value = null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun applyEverywhere(itemId: String, transform: (NoteItem) -> NoteItem) {
        for (flow in listOf(allItems, recentItems, starredItems, sharedItems, trashItems, indexedNotes)) {
            if (flow.value.any { it.id == itemId }) flow.update { items -> items.map { if (it.id == itemId) transform(it) else it } }
        }
    }

    /** Writes a star through every list, and adds the item to (or drops it from) Favorites, newest first. */
    private fun applyStarred(item: NoteItem) {
        allItems.update { items -> items.map { if (it.id == item.id) it.copy(isStarred = item.isStarred) else it } }
        recentItems.update { items -> items.map { if (it.id == item.id) it.copy(isStarred = item.isStarred) else it } }
        starredItems.update { items ->
            if (item.isStarred) {
                if (items.any { it.id == item.id }) items.map { if (it.id == item.id) item else it } else listOf(item) + items
            } else items.filter { it.id != item.id }
        }
    }

    private fun folder(dto: FolderDto) = NoteItem(
        id = dto.id, name = dto.name, type = NoteItem.ItemType.FOLDER, parentId = dto.parentId,
        modifiedAt = DriveDate.parse(dto.updatedAt) ?: Instant.EPOCH, isStarred = dto.isStarred ?: false,
    )

    companion object {
        const val RECENT_LIMIT = 50
        private const val NOTE_INDEX_TTL_MS = 60_000L
        private const val NOTE_INDEX_FOLDER_BUDGET = 200
    }
}
