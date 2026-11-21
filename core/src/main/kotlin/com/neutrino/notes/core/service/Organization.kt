package com.neutrino.notes.core.service

import com.neutrino.notes.core.api.NotesApi
import com.neutrino.notes.core.markdown.WikiLink
import com.neutrino.notes.core.model.FileLink
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NoteTag
import com.neutrino.shared.core.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.logging.Logger

class DuplicateTagName(name: String) : Exception("A tag named “$name” already exists.")

/**
 * Note tags through Drive's tag APIs — the app stores none of its own. A tag belongs to the user
 * and is attached to any number of files; there is no bulk "tags of these files" endpoint, so a
 * file's tags are fetched where one request is natural (the editor), never per listing row.
 * A tag's *name* is readable by the server, like a file name.
 */
class TagsService(private val api: NotesApi, private val scope: CoroutineScope) {
    /** Every tag the user owns, by name. */
    val tags = MutableStateFlow<List<NoteTag>>(emptyList())
    /** Per-file tags, for the files that have been looked at. */
    val tagsByFile = MutableStateFlow<Map<String, List<NoteTag>>>(emptyMap())
    val error = MutableStateFlow<String?>(null)

    fun tags(fileId: String): List<NoteTag> = tagsByFile.value[fileId].orEmpty()

    suspend fun load() {
        try {
            tags.value = api.tags().sortedWith(NoteTag.byName)
        } catch (e: Exception) {
            error.value = e.message
        }
    }

    suspend fun createTag(name: String): NoteTag {
        val trimmed = name.trim()
        val tag = try {
            api.createTag(trimmed)
        } catch (e: ApiException.Http) {
            if (e.status == 409) throw DuplicateTagName(trimmed) else throw e
        }
        tags.update { (it + tag).sortedWith(NoteTag.byName) }
        return tag
    }

    /** Optimistic, every cache rolled back together if the server refuses. */
    fun renameTag(tag: NoteTag, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == tag.name) return
        applyRename(tag.id, trimmed)
        scope.launch {
            try {
                api.renameTag(tag.id, trimmed)
            } catch (e: Exception) {
                applyRename(tag.id, tag.name)
                error.value = if (e is ApiException.Http && e.status == 409) DuplicateTagName(trimmed).message else e.message
            }
        }
    }

    /** The server detaches it from every file too. */
    fun deleteTag(tag: NoteTag) {
        val previousTags = tags.value
        val previousByFile = tagsByFile.value
        tags.update { list -> list.filter { it.id != tag.id } }
        tagsByFile.update { map -> map.mapValues { (_, list) -> list.filter { it.id != tag.id } } }
        scope.launch {
            try {
                api.deleteTag(tag.id)
            } catch (e: Exception) {
                tags.value = previousTags
                tagsByFile.value = previousByFile
                error.value = e.message
            }
        }
    }

    suspend fun loadTags(fileId: String): List<NoteTag> {
        val list = api.fileTags(fileId).sortedWith(NoteTag.byName)
        tagsByFile.update { it + (fileId to list) }
        return list
    }

    /**
     * Writes a picker's selection back as the difference from what the file carries — per-tag writes,
     * not the server's replace-all, so a tag attached from another device meanwhile survives. Every
     * change is attempted; the first failure is rethrown after, so the caches match what was accepted.
     */
    suspend fun applyTags(selected: Set<String>, fileId: String) {
        val current = tags(fileId).map { it.id }.toSet()
        val (added, removed) = diff(current, selected)
        var first: Exception? = null
        for (id in added) {
            val tag = tags.value.firstOrNull { it.id == id } ?: continue
            try {
                api.attachTag(fileId, id)
                tagsByFile.update { it + (fileId to (tags(fileId) + tag).distinctBy { t -> t.id }.sortedWith(NoteTag.byName)) }
                adjustCount(id, +1)
            } catch (e: Exception) { first = first ?: e }
        }
        for (id in removed) {
            try {
                api.detachTag(fileId, id)
                tagsByFile.update { it + (fileId to tags(fileId).filter { t -> t.id != id }) }
                adjustCount(id, -1)
            } catch (e: Exception) { first = first ?: e }
        }
        first?.let { throw it }
    }

    /** Every note carrying a tag, paged until `total`; an empty page also ends it, in case the two disagree. */
    suspend fun notes(tagId: String): List<NoteItem> {
        val out = ArrayList<NoteItem>()
        while (true) {
            val page = api.taggedNotes(tagId, PAGE_SIZE, out.size)
            out += page.files.filter { NoteItem.isMarkdown(it.mimeType) || it.mimeType == null }.map { NoteContentService.item(it) }
            if (page.files.isEmpty() || out.size >= page.total) break
        }
        return out
    }

    fun reset() {
        tags.value = emptyList()
        tagsByFile.value = emptyMap()
        error.value = null
    }

    private fun adjustCount(tagId: String, delta: Int) {
        tags.update { list -> list.map { if (it.id == tagId) it.copy(fileCount = maxOf(0, it.fileCount + delta)) else it } }
    }

    private fun applyRename(tagId: String, name: String) {
        tags.update { list -> list.map { if (it.id == tagId) it.copy(name = name) else it }.sortedWith(NoteTag.byName) }
        tagsByFile.update { map -> map.mapValues { (_, list) -> list.map { if (it.id == tagId) it.copy(name = name) else it }.sortedWith(NoteTag.byName) } }
    }

    companion object {
        /** The server clamps `limit` to 200. */
        const val PAGE_SIZE = 200

        /** The add/remove work a selection implies, sorted so it is deterministic. */
        fun diff(current: Set<String>, selected: Set<String>): Pair<List<String>, List<String>> =
            (selected - current).sorted() to (current - selected).sorted()
    }
}

/**
 * Drive's cross-file link graph (`src/links/`): backlinks read, outgoing titles written.
 *
 * The titles travel in the clear — text out of an end-to-end-encrypted body. A deliberate, narrow
 * disclosure: for a title that resolves, the edge and both names are already server-side; for one
 * that doesn't, the server sees a phrase and stores nothing. A device-local graph would be invisible
 * to the web, where most of these links are written.
 */
class LinksService(private val api: NotesApi) {
    private val log = Logger.getLogger("LinksService")
    val backlinksByFile = MutableStateFlow<Map<String, List<FileLink>>>(emptyMap())
    /** The last title set sent per file, so an unchanged set is not re-sent on every autosave. */
    private val lastSent = HashMap<String, List<String>>()

    fun backlinks(fileId: String): List<FileLink> = backlinksByFile.value[fileId].orEmpty()

    suspend fun loadBacklinks(fileId: String): List<FileLink> {
        val links = api.backlinks(fileId)
        backlinksByFile.update { it + (fileId to links) }
        return links
    }

    /**
     * The editor's update: never throws. The content is saved by the time this runs; a failed link
     * update is a stale edge, not a lost note, and the next save re-sends the whole set. Skipped when
     * the titles haven't changed, unless `force` — something other than the text changed what they
     * resolve to (the note one names was just created).
     */
    suspend fun updateLinksIgnoringFailure(fileId: String, text: String, force: Boolean = false) {
        val titles = WikiLink.requestTitles(text)
        if (!force && lastSent[fileId] == titles) return
        try {
            val links = api.updateLinks(fileId, titles)
            backlinksByFile.update { it + (fileId to links) }
            lastSent[fileId] = titles
        } catch (e: Exception) {
            log.fine("updateLinks failed for $fileId: ${e.javaClass.simpleName}")
        }
    }

    fun forgetAll() {
        backlinksByFile.value = emptyMap()
        lastSent.clear()
    }
}
