package com.neutrino.notes.core.markdown

import com.neutrino.notes.core.model.NoteItem
import java.net.URI
import java.net.URLDecoder

/**
 * The `[[Another Note]]` syntax that ties one note to another — a port of iOS `WikiLink`, itself a
 * port of the web's `extractWikiLinks`: `[[`, anything that is not a `]`, `]]`; the inner text
 * trimmed; empty targets dropped. Offsets are UTF-16, which is what a Kotlin string and an
 * `EditText` both count in.
 */
object WikiLink {

    /**
     * The suffix this app (and iOS) puts on every note it creates, and the web puts on none. The
     * server matches a `[[title]]` against `files.name` with only a case fold, so both spellings
     * travel on the wire ([requestTitles]) and both are indexed locally ([indexKey]).
     */
    const val MARKDOWN_EXTENSION = ".md"

    /** `Meeting Notes.md` → `Meeting Notes`. */
    fun displayTitle(name: String): String {
        val trimmed = name.trim()
        if (!trimmed.lowercase().endsWith(MARKDOWN_EXTENSION) || trimmed.length <= MARKDOWN_EXTENSION.length) return trimmed
        return trimmed.dropLast(MARKDOWN_EXTENSION.length)
    }

    /** The form a title is matched on locally: trimmed, extension-less, case-folded. */
    fun indexKey(title: String): String = displayTitle(title).lowercase()

    /** The name a new note is stored under: the title typed, with `.md` on the end. */
    fun fileName(title: String): String {
        val trimmed = title.trim().ifEmpty { "Untitled" }
        return if (trimmed.lowercase().endsWith(MARKDOWN_EXTENSION)) trimmed else trimmed + MARKDOWN_EXTENSION
    }

    /** One `[[…]]` occurrence: the whole link including brackets, and the trimmed, non-empty target. */
    data class Match(val start: Int, val end: Int, val title: String)

    private val regex = Regex("""\[\[([^\]]*)\]\]""")

    fun matches(text: String): List<Match> = regex.findAll(text).mapNotNull { m ->
        val title = m.groupValues[1].trim()
        if (title.isEmpty()) null else Match(m.range.first, m.range.last + 1, title)
    }.toList()

    /** Distinct titles in first-appearance order, deduplicated case-insensitively as the server resolves. */
    fun titles(text: String): List<String> {
        val seen = HashSet<String>()
        return matches(text).map { it.title }.filter { seen.add(it.lowercase()) }
    }

    /** What `PATCH /links/{id}` is sent: every title, plus its `.md` spelling. Unresolved ones cost nothing. */
    fun requestTitles(text: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (title in titles(text)) for (spelling in listOf(title, title + MARKDOWN_EXTENSION)) {
            if (seen.add(spelling.lowercase())) out += spelling
        }
        return out
    }

    /**
     * The note a relative `[text](destination)` link names, or null when the destination is not a
     * reference to another note. Only the file name is kept: Drive resolves titles, not paths, so
     * `./notes/Help.md` and `[[Help]]` find the same note. A destination with a scheme is somebody else's.
     */
    fun titleForRelativeLink(destination: String): String? {
        val uri = runCatching { URI(destination.replace(" ", "%20")) }.getOrNull() ?: return null
        if (uri.scheme != null) return null
        val path = uri.rawPath ?: return null
        val name = path.substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name).trim()
        if (decoded.isEmpty() || decoded == "." || decoded == "..") return null
        return decoded
    }

    /** The `[[` link the caret is inside: from the opening `[[` to the caret, and what was typed since. */
    data class Token(val start: Int, val end: Int, val query: String)

    /** Only between the nearest unclosed `[[` on this line and the caret; a `]` or `[` in between ends it. */
    fun token(text: String, caret: Int): Token? {
        if (caret < 2 || caret > text.length) return null
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val open = text.lastIndexOf("[[", caret - 2).takeIf { it >= lineStart } ?: return null
        val query = text.substring(open + 2, caret)
        if (query.contains(']') || query.contains('[')) return null
        return Token(open, caret, query)
    }
}

/**
 * Resolves a `[[title]]` to a note on the device: whether to draw a link as live, and what to open.
 * Built from listings the app already loads; nil is not "no such note" — a note in a folder this
 * session never listed is invisible here, which is why an unresolved link stays tappable.
 */
class WikiLinkIndex(items: List<NoteItem> = emptyList()) {
    private val byKey: Map<String, NoteItem>
    private val sortedItems: List<NoteItem>

    init {
        val notes = items.filter { it.type == NoteItem.ItemType.FILE && !it.isTrashed }
        val map = HashMap<String, NoteItem>()
        for (note in notes) {
            for (key in setOf(WikiLink.indexKey(note.name), note.name.lowercase())) {
                if (key.isEmpty()) continue
                // Two notes can share a title; the most recently edited wins, which is at least predictable.
                val existing = map[key]
                if (existing != null && existing.modifiedAt >= note.modifiedAt) continue
                map[key] = note
            }
        }
        byKey = map
        sortedItems = notes.sortedByDescending { it.modifiedAt }
    }

    val isEmpty: Boolean get() = byKey.isEmpty()

    fun item(title: String): NoteItem? = byKey[WikiLink.indexKey(title)]

    fun contains(title: String): Boolean = item(title) != null

    /** Prefix matches first, then anything containing the query, each newest first; empty offers the newest. */
    fun suggestions(query: String, limit: Int = 8): List<NoteItem> {
        val needle = WikiLink.indexKey(query)
        if (needle.isEmpty()) return sortedItems.take(limit)
        val prefixed = ArrayList<NoteItem>()
        val contained = ArrayList<NoteItem>()
        for (note in sortedItems) {
            val key = WikiLink.indexKey(note.name)
            if (key.startsWith(needle)) prefixed += note else if (key.contains(needle)) contained += note
        }
        return (prefixed + contained).take(limit)
    }
}
