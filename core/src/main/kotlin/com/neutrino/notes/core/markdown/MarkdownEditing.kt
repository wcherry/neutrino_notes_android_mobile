package com.neutrino.notes.core.markdown

/**
 * The editor's typing rules and formatting commands, pure and in UTF-16 offsets so the text field
 * only applies what these return. The text itself is the note: there is no model beside it.
 */
object MarkdownEditing {

    /** A change to apply: replace `[start, end)` with `text`, then put the caret at `caret`. */
    data class Edit(val start: Int, val end: Int, val text: String, val caret: Int) {
        fun applyTo(source: String): String = source.substring(0, start) + text + source.substring(end)
    }

    private val listMarker = Regex("""^([ \t]*)(?:([-*+])|(\d{1,9})([.)]))[ \t]+(\[[ xX]\][ \t]+)?""")

    /**
     * Return pressed at `caret` (replacing the selection `[caret, selectionEnd)`). On a list line,
     * continues the list — the same bullet, the next number, an unchecked box — and on an empty item
     * ends it, clearing the marker. Null leaves the newline to the text field.
     *
     * iOS continues task lists only; bullets and numbers are continued here too, as the web's
     * editor does.
     */
    fun returnPressed(text: String, caret: Int, selectionEnd: Int = caret): Edit? {
        if (caret < 0 || selectionEnd > text.length || caret > selectionEnd) return null
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val m = listMarker.find(line) ?: return null
        val markerLength = m.value.length
        if (caret - lineStart < markerLength) return null
        val content = line.substring(markerLength).trim()
        if (content.isEmpty() && caret == selectionEnd) {
            // An empty item ends the list: the marker goes, and a blank line is left after the list —
            // without one, the next line would be read as more of the last item (a lazy continuation).
            val endsList = lineStart >= 2 && text[lineStart - 2] != '\n'
            val insert = if (endsList) "\n" else ""
            return Edit(lineStart, lineEnd, insert, lineStart + insert.length)
        }
        val indent = m.groupValues[1]
        val bullet = m.groupValues[2]
        val number = m.groupValues[3].toIntOrNull()
        val delimiter = m.groupValues[4].ifEmpty { "." }
        val box = if (m.groupValues[5].isNotEmpty()) "[ ] " else ""
        val prefix = if (bullet.isNotEmpty()) "$indent$bullet $box" else "$indent${(number ?: 0) + 1}$delimiter $box"
        val insert = "\n" + prefix
        return Edit(caret, selectionEnd, insert, caret + insert.length)
    }

    /** The `/command` typed at the start of the caret's line, with no whitespace yet. */
    data class SlashToken(val start: Int, val end: Int, val query: String)

    fun slashToken(text: String, caret: Int): SlashToken? {
        if (caret < 0 || caret > text.length) return null
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val typed = text.substring(lineStart, caret)
        if (!typed.startsWith("/")) return null
        val query = typed.drop(1)
        if (query.any { it.isWhitespace() }) return null
        return SlashToken(lineStart, caret, query)
    }

    /**
     * Toggles `[ ]`/`[x]` on the task item that starts on `line` (0-based) — a tap on a preview
     * checkbox, located by the parser's source position rather than by counting, so a `- [ ]` inside
     * a code block cannot shift which box flips. Null when that line is not a task item.
     */
    fun toggleTask(text: String, line: Int): String? {
        var start = 0
        repeat(line) { start = text.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: return null }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val m = Regex("""^([ \t>]*(?:[-*+]|\d{1,9}[.)])[ \t]+)\[([ xX])\]""").find(text.substring(start, end)) ?: return null
        val box = start + m.groups[2]!!.range.first
        val replacement = if (text[box] == ' ') 'x' else ' '
        return text.substring(0, box) + replacement + text.substring(box + 1)
    }

    /**
     * Applies a format at the selection. A selection is wrapped (`**…**`) or prefixed line by line
     * (`> `, `- `); with none, the snippet goes in at the caret with the caret inside it — the iOS
     * slash-menu behaviour. `replacing` drops a typed `/command` first.
     */
    fun apply(format: MarkdownFormat, text: String, start: Int, end: Int, replacing: SlashToken? = null): Edit {
        if (replacing != null) {
            return Edit(replacing.start, replacing.end, format.snippet, replacing.start + format.caretOffset)
        }
        val selected = text.substring(start, end)
        if (selected.isEmpty()) {
            val atLineStart = start == 0 || text[start - 1] == '\n'
            if (format.isLinePrefix && !atLineStart) {
                val lineStart = text.lastIndexOf('\n', start - 1) + 1
                return Edit(lineStart, lineStart, format.snippet, start + format.snippet.length)
            }
            return Edit(start, end, format.snippet, start + format.caretOffset)
        }
        return when {
            format.id == "link" -> Edit(start, end, "[$selected]()", start + selected.length + 3)
            format.id == "codeBlock" -> {
                val wrapped = "```\n$selected\n```"
                Edit(start, end, wrapped, start + wrapped.length)
            }
            format.isLinePrefix -> {
                val lineStart = text.lastIndexOf('\n', start - 1) + 1
                val block = text.substring(lineStart, end)
                val prefixed = block.split("\n").mapIndexed { i, line ->
                    when {
                        format.id == "numberedList" -> "${i + 1}. $line"
                        else -> format.snippet + line
                    }
                }.joinToString("\n")
                Edit(lineStart, end, prefixed, lineStart + prefixed.length)
            }
            else -> {
                val wrapped = format.marker + selected + format.marker
                Edit(start, end, wrapped, start + wrapped.length)
            }
        }
    }
}

/** One entry of the slash menu and the formatting bar — the iOS `MarkdownFormat` list, value for value. */
data class MarkdownFormat(
    val id: String,
    val title: String,
    val marker: String,
    val keywords: List<String>,
    val snippet: String,
    val caretOffset: Int,
) {
    /** Applied to whole lines rather than wrapped around a selection. */
    val isLinePrefix: Boolean get() = id in setOf("heading1", "heading2", "heading3", "quote", "bulletList", "numberedList", "checklist")

    private fun matches(query: String): Boolean =
        (title.lowercase().split(" ") + keywords).any { it.startsWith(query) }

    companion object {
        val all = listOf(
            MarkdownFormat("heading1", "Heading 1", "#", listOf("h1", "title"), "# ", 2),
            MarkdownFormat("heading2", "Heading 2", "##", listOf("h2", "subtitle"), "## ", 3),
            MarkdownFormat("heading3", "Heading 3", "###", listOf("h3"), "### ", 4),
            MarkdownFormat("bold", "Bold", "**", listOf("strong"), "****", 2),
            MarkdownFormat("italic", "Italic", "*", listOf("emphasis"), "**", 1),
            MarkdownFormat("strikethrough", "Strikethrough", "~~", listOf("strike"), "~~~~", 2),
            MarkdownFormat("code", "Code", "`", listOf("inline", "monospace"), "``", 1),
            MarkdownFormat("codeBlock", "Code Block", "```", listOf("fence", "snippet"), "```\n\n```", 4),
            MarkdownFormat("link", "Link", "[]()", listOf("url", "href"), "[]()", 1),
            MarkdownFormat("quote", "Quote", ">", listOf("blockquote"), "> ", 2),
            MarkdownFormat("bulletList", "Bulleted List", "-", listOf("unordered", "bullet"), "- ", 2),
            MarkdownFormat("numberedList", "Numbered List", "1.", listOf("ordered", "number"), "1. ", 3),
            MarkdownFormat("checklist", "Checklist", "- [ ]", listOf("todo", "task", "checkbox", "list"), "- [ ] ", 6),
            MarkdownFormat("divider", "Divider", "---", listOf("rule", "separator", "line"), "---\n", 4),
        )

        fun matching(query: String): List<MarkdownFormat> {
            val q = query.lowercase()
            return if (q.isEmpty()) all else all.filter { it.matches(q) }
        }

        fun byId(id: String): MarkdownFormat = all.first { it.id == id }
    }
}

/** A line-level diff between two revisions, for comparing versions. A port of iOS `TextDiff`. */
object TextDiff {
    enum class Kind { UNCHANGED, INSERTED, DELETED }
    data class Line(val kind: Kind, val text: String)

    /** Cap on the LCS table; beyond it the middle reads as replaced wholesale. */
    const val MAX_TABLE_CELLS = 250_000

    fun compare(old: String, new: String): List<Line> {
        val a = lines(old)
        val b = lines(new)
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val oldMiddle = a.subList(prefix, a.size - suffix)
        val newMiddle = b.subList(prefix, b.size - suffix)
        val out = ArrayList<Line>()
        a.subList(0, prefix).forEach { out += Line(Kind.UNCHANGED, it) }
        out += if (oldMiddle.size.toLong() * newMiddle.size <= MAX_TABLE_CELLS) diff(oldMiddle, newMiddle)
        else oldMiddle.map { Line(Kind.DELETED, it) } + newMiddle.map { Line(Kind.INSERTED, it) }
        a.subList(a.size - suffix, a.size).forEach { out += Line(Kind.UNCHANGED, it) }
        return out
    }

    fun isIdentical(old: String, new: String) = lines(old) == lines(new)

    /** "a\n" and "a" compare equal: the editor's trailing newline is not a meaningful change. */
    private fun lines(text: String): List<String> {
        val lines = text.split("\n").toMutableList()
        if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines
    }

    private fun diff(old: List<String>, new: List<String>): List<Line> {
        val m = old.size
        val n = new.size
        if (m == 0) return new.map { Line(Kind.INSERTED, it) }
        if (n == 0) return old.map { Line(Kind.DELETED, it) }
        val table = IntArray((m + 1) * (n + 1))
        for (i in m - 1 downTo 0) for (j in n - 1 downTo 0) {
            table[i * (n + 1) + j] = if (old[i] == new[j]) table[(i + 1) * (n + 1) + j + 1] + 1
            else maxOf(table[(i + 1) * (n + 1) + j], table[i * (n + 1) + j + 1])
        }
        val out = ArrayList<Line>()
        var i = 0
        var j = 0
        while (i < m || j < n) {
            when {
                i < m && j < n && old[i] == new[j] -> { out += Line(Kind.UNCHANGED, old[i]); i++; j++ }
                // Ties break towards the deletion, so a replaced line reads "− old" then "+ new".
                i < m && (j >= n || table[(i + 1) * (n + 1) + j] >= table[i * (n + 1) + j + 1]) -> { out += Line(Kind.DELETED, old[i]); i++ }
                else -> { out += Line(Kind.INSERTED, new[j]); j++ }
            }
        }
        return out
    }
}
