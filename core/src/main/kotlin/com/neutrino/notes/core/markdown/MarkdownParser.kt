package com.neutrino.notes.core.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** A parsed note: what the preview draws. The iOS `MarkdownDocumentModel`. */
data class MarkdownDocument(val blocks: List<MarkdownBlock>, val footnotes: List<MarkdownFootnote>)

sealed class MarkdownBlock {
    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock()
    data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock()
    data class ListBlock(val isOrdered: Boolean, val startIndex: Int, val items: List<ListItemBlock>) : MarkdownBlock()
    data class Table(val alignments: List<Alignment>, val header: List<List<MarkdownInline>>, val rows: List<List<List<MarkdownInline>>>) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String?) : MarkdownBlock()
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock()
    object Rule : MarkdownBlock()

    enum class Alignment { NONE, LEADING, CENTER, TRAILING }
}

/**
 * One list item. `checkbox` is null for a plain item, false/true for a task. `sourceLine` is the
 * 0-based line of the note the item starts on, so a tapped checkbox flips the right `[ ]`.
 */
data class ListItemBlock(val checkbox: Boolean?, val blocks: List<MarkdownBlock>, val sourceLine: Int? = null)

sealed class MarkdownInline {
    data class Text(val text: String) : MarkdownInline()
    data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline()
    data class Strong(val children: List<MarkdownInline>) : MarkdownInline()
    data class Strikethrough(val children: List<MarkdownInline>) : MarkdownInline()
    data class Code(val code: String) : MarkdownInline()
    data class Link(val children: List<MarkdownInline>, val destination: String) : MarkdownInline()
    /** A `[[Another Note]]`: resolved at render time, because the answer changes as notes are created and renamed. */
    data class WikiLinkRef(val title: String) : MarkdownInline()
    data class Image(val alt: String, val source: String, val title: String?) : MarkdownInline()
    data class FootnoteRef(val label: String, val index: Int) : MarkdownInline()
    object SoftBreak : MarkdownInline()
    object LineBreak : MarkdownInline()
}

data class MarkdownFootnote(val label: String, val index: Int, val content: List<MarkdownInline>)

/**
 * Markdown source → [MarkdownDocument]: CommonMark with GFM tables, strikethrough and task lists
 * (what iOS gets from cmark-gfm), plus the iOS pre/post pass for footnotes and `[[wiki links]]`,
 * neither of which is GFM. Wiki links are split out of text runs only, so `` `[[x]]` `` inside code
 * stays literal for free.
 */
object MarkdownParser {

    private const val FOOTNOTE_SCHEME = "nn-footnote://"
    private val definitionRegex = Regex("""^\[\^([^\]]+)\]:\s?(.*)$""")
    private val referenceRegex = Regex("""\[\^([^\]]+)\]""")

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

    fun parse(source: String): MarkdownDocument {
        // Footnote definitions come out of the body; the lines kept remember where they came from,
        // so a checkbox's line still names the note's own line.
        val definitions = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        val kept = ArrayList<String>()
        val originalLine = ArrayList<Int>()
        source.split("\n").forEachIndexed { i, line ->
            val m = definitionRegex.find(line)
            if (m != null) {
                if (seen.add(m.groupValues[1])) definitions += m.groupValues[1] to m.groupValues[2]
            } else {
                kept += line
                originalLine += i
            }
        }
        val known = definitions.toMap()
        val indexByLabel = LinkedHashMap<String, Int>()
        val body = referenceRegex.replace(kept.joinToString("\n")) { m ->
            val label = m.groupValues[1]
            if (label !in known) m.value else {
                indexByLabel.getOrPut(label) { indexByLabel.size + 1 }
                "[^$label]($FOOTNOTE_SCHEME${label.replace(" ", "%20")})"
            }
        }
        val ctx = Context(indexByLabel, originalLine)
        val blocks = ctx.blocks(parser.parse(body))
        var next = indexByLabel.size + 1
        val footnotes = definitions.map { (label, text) ->
            val index = indexByLabel[label] ?: next++
            MarkdownFootnote(label, index, inlineOnly(text))
        }.sortedBy { it.index }
        return MarkdownDocument(blocks, footnotes)
    }

    private fun inlineOnly(text: String): List<MarkdownInline> {
        if (text.isEmpty()) return emptyList()
        val paragraph = parser.parse(text).firstChild as? Paragraph ?: return listOf(MarkdownInline.Text(text))
        return Context(emptyMap(), emptyList()).inlines(paragraph)
    }

    private class Context(val indexByLabel: Map<String, Int>, val originalLine: List<Int>) {

        fun children(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()

        fun blocks(parent: Node): List<MarkdownBlock> = children(parent).mapNotNull(::block)

        fun block(node: Node): MarkdownBlock? = when (node) {
            is Heading -> MarkdownBlock.Heading(node.level, inlines(node))
            is Paragraph -> MarkdownBlock.Paragraph(inlines(node))
            is BulletList -> MarkdownBlock.ListBlock(false, 1, children(node).filterIsInstance<ListItem>().map(::item))
            is OrderedList -> MarkdownBlock.ListBlock(true, node.markerStartNumber ?: 1, children(node).filterIsInstance<ListItem>().map(::item))
            is FencedCodeBlock -> MarkdownBlock.CodeBlock(node.literal.removeSuffix("\n"), node.info?.trim()?.substringBefore(' ')?.ifEmpty { null })
            is IndentedCodeBlock -> MarkdownBlock.CodeBlock(node.literal.removeSuffix("\n"), null)
            is BlockQuote -> MarkdownBlock.Quote(blocks(node))
            is ThematicBreak -> MarkdownBlock.Rule
            is TableBlock -> table(node)
            // Raw HTML is shown as the text it is: a note is not a web page.
            is HtmlBlock -> MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(node.literal.trimEnd())))
            else -> null
        }

        fun item(node: ListItem): ListItemBlock {
            var checkbox: Boolean? = null
            val marker = children(node).firstOrNull { it is TaskListItemMarker } as? TaskListItemMarker
                ?: (node.firstChild as? Paragraph)?.firstChild as? TaskListItemMarker
            if (marker != null) checkbox = marker.isChecked
            val line = node.sourceSpans.firstOrNull()?.lineIndex?.let { originalLine.getOrNull(it) }
            return ListItemBlock(checkbox, blocks(node), line)
        }

        fun table(node: TableBlock): MarkdownBlock.Table {
            val head = children(node).filterIsInstance<TableHead>().firstOrNull()
            val headerRow = head?.let { children(it).filterIsInstance<TableRow>().firstOrNull() }
            val headerCells = headerRow?.let { children(it).filterIsInstance<TableCell>() }.orEmpty()
            val alignments = headerCells.map {
                when (it.alignment) {
                    TableCell.Alignment.LEFT -> MarkdownBlock.Alignment.LEADING
                    TableCell.Alignment.CENTER -> MarkdownBlock.Alignment.CENTER
                    TableCell.Alignment.RIGHT -> MarkdownBlock.Alignment.TRAILING
                    else -> MarkdownBlock.Alignment.NONE
                }
            }
            val rows = children(node).filterIsInstance<TableBody>().flatMap { body ->
                children(body).filterIsInstance<TableRow>().map { row -> children(row).filterIsInstance<TableCell>().map(::inlines) }
            }
            return MarkdownBlock.Table(alignments, headerCells.map(::inlines), rows)
        }

        /** Adjacent text nodes are joined before splitting: `[[` may land in a different node from its title. */
        fun inlines(parent: Node): List<MarkdownInline> {
            val out = ArrayList<MarkdownInline>()
            val run = StringBuilder()
            fun flush() {
                if (run.isEmpty()) return
                out += splitWikiLinks(run.toString())
                run.clear()
            }
            for (child in children(parent)) {
                if (child is Text) {
                    run.append(child.literal)
                    continue
                }
                if (child is TaskListItemMarker) continue
                flush()
                inline(child)?.let { out += it }
            }
            flush()
            return out
        }

        fun splitWikiLinks(text: String): List<MarkdownInline> {
            val matches = WikiLink.matches(text)
            if (matches.isEmpty()) return listOf(MarkdownInline.Text(text))
            val out = ArrayList<MarkdownInline>()
            var cursor = 0
            for (m in matches) {
                if (m.start > cursor) out += MarkdownInline.Text(text.substring(cursor, m.start))
                out += MarkdownInline.WikiLinkRef(m.title)
                cursor = m.end
            }
            if (cursor < text.length) out += MarkdownInline.Text(text.substring(cursor))
            return out
        }

        fun inline(node: Node): MarkdownInline? = when (node) {
            is Emphasis -> MarkdownInline.Emphasis(inlines(node))
            is StrongEmphasis -> MarkdownInline.Strong(inlines(node))
            is Strikethrough -> MarkdownInline.Strikethrough(inlines(node))
            is Code -> MarkdownInline.Code(node.literal)
            is Link -> {
                val destination = node.destination.orEmpty()
                if (destination.startsWith(FOOTNOTE_SCHEME)) {
                    val label = destination.removePrefix(FOOTNOTE_SCHEME).replace("%20", " ")
                    MarkdownInline.FootnoteRef(label, indexByLabel[label] ?: 0)
                } else {
                    MarkdownInline.Link(inlines(node), destination)
                }
            }
            is Image -> MarkdownInline.Image(plainText(node), node.destination.orEmpty(), node.title)
            is SoftLineBreak -> MarkdownInline.SoftBreak
            is HardLineBreak -> MarkdownInline.LineBreak
            is HtmlInline -> MarkdownInline.Text(node.literal)
            else -> null
        }

        fun plainText(node: Node): String = buildString {
            for (c in children(node)) when (c) {
                is Text -> append(c.literal)
                is Code -> append(c.literal)
                else -> append(plainText(c))
            }
        }
    }

    /** The plain text a list of inlines reads as — for search, previews and accessibility. */
    fun plainText(inlines: List<MarkdownInline>): String = buildString {
        for (i in inlines) when (i) {
            is MarkdownInline.Text -> append(i.text)
            is MarkdownInline.Emphasis -> append(plainText(i.children))
            is MarkdownInline.Strong -> append(plainText(i.children))
            is MarkdownInline.Strikethrough -> append(plainText(i.children))
            is MarkdownInline.Code -> append(i.code)
            is MarkdownInline.Link -> append(plainText(i.children))
            is MarkdownInline.WikiLinkRef -> append(i.title)
            is MarkdownInline.Image -> append(i.alt)
            is MarkdownInline.FootnoteRef -> append("[${i.index}]")
            MarkdownInline.SoftBreak -> append(' ')
            MarkdownInline.LineBreak -> append('\n')
        }
    }
}
