package com.neutrino.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.neutrino.notes.core.markdown.MarkdownBlock
import com.neutrino.notes.core.markdown.MarkdownDocument
import com.neutrino.notes.core.markdown.MarkdownInline
import com.neutrino.notes.core.markdown.WikiLinkIndex

/** What a tap in the preview asks for. */
sealed class PreviewAction {
    data class WikiLink(val title: String) : PreviewAction()
    data class Url(val destination: String) : PreviewAction()
    /** A task checkbox on this 0-based line of the note. */
    data class ToggleTask(val line: Int) : PreviewAction()
}

/**
 * The rendered note: the iOS `MarkdownView`, block for block. `[[links]]` the index resolves are
 * drawn as links; the rest are drawn dashed and still tappable, since "not in the index" is not "no
 * such note". Checkboxes flip when the note is writable — iOS draws them read-only.
 */
@Composable
fun MarkdownPreview(document: MarkdownDocument, index: WikiLinkIndex, fontSize: Float, canToggle: Boolean, onAction: (PreviewAction) -> Unit) {
    val style = Style(fontSize, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        document.blocks.forEach { Block(it, style, index, canToggle, onAction) }
        if (document.footnotes.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            document.footnotes.forEach { note ->
                Row {
                    Text("${note.index}.", fontSize = (fontSize * 0.85f).sp, color = style.secondary, modifier = Modifier.width(28.dp))
                    InlineText(note.content, style, index, onAction, TextStyle(fontSize = (fontSize * 0.85f).sp))
                }
            }
        }
    }
}

private data class Style(val size: Float, val link: Color, val secondary: Color, val codeBackground: Color)

@Composable
private fun Block(block: MarkdownBlock, style: Style, index: WikiLinkIndex, canToggle: Boolean, onAction: (PreviewAction) -> Unit) {
    when (block) {
        is MarkdownBlock.Paragraph -> InlineText(block.inlines, style, index, onAction, TextStyle(fontSize = style.size.sp, lineHeight = (style.size * 1.4f).sp))
        is MarkdownBlock.Heading -> {
            val scale = when (block.level) { 1 -> 1.75f; 2 -> 1.45f; 3 -> 1.2f; else -> 1.05f }
            InlineText(block.inlines, style, index, onAction, TextStyle(fontSize = (style.size * scale).sp, fontWeight = FontWeight.Bold, lineHeight = (style.size * scale * 1.25f).sp),
                Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp))
        }
        is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { i, item ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.widthIn(min = 26.dp).padding(end = 4.dp)) {
                        val checkbox = item.checkbox
                        when {
                            checkbox != null -> Icon(
                                if (checkbox) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                if (checkbox) "Checked" else "Unchecked",
                                Modifier.size((style.size * 1.3f).dp).then(
                                    if (canToggle && item.sourceLine != null) Modifier.clickable { onAction(PreviewAction.ToggleTask(item.sourceLine!!)) } else Modifier,
                                ),
                                tint = if (checkbox) style.link else style.secondary,
                            )
                            block.isOrdered -> Text("${block.startIndex + i}.", fontSize = style.size.sp, color = style.secondary)
                            else -> Text("•", fontSize = style.size.sp, color = style.secondary)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.blocks.forEach { child ->
                            // A checked task reads as done.
                            if (item.checkbox == true && child is MarkdownBlock.Paragraph) {
                                InlineText(child.inlines, style, index, onAction, TextStyle(fontSize = style.size.sp, color = style.secondary, textDecoration = TextDecoration.LineThrough))
                            } else Block(child, style, index, canToggle, onAction)
                        }
                    }
                }
            }
        }
        is MarkdownBlock.CodeBlock -> Box(
            Modifier.fillMaxWidth().background(style.codeBackground, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text(block.code, fontFamily = FontFamily.Monospace, fontSize = (style.size * 0.88f).sp)
        }
        is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(style.secondary.copy(alpha = 0.5f)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.blocks.forEach { Block(it, style.copy(), index, canToggle, onAction) }
            }
        }
        MarkdownBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
        is MarkdownBlock.Table -> Table(block, style, index, onAction)
    }
}

@Composable
private fun Table(table: MarkdownBlock.Table, style: Style, index: WikiLinkIndex, onAction: (PreviewAction) -> Unit) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, border, RoundedCornerShape(6.dp))) {
        val rows = listOf(table.header) + table.rows
        rows.forEachIndexed { r, row ->
            Row(Modifier.then(if (r == 0) Modifier.background(style.codeBackground) else Modifier)) {
                row.forEachIndexed { c, cell ->
                    val align = when (table.alignments.getOrNull(c)) {
                        MarkdownBlock.Alignment.CENTER -> TextAlign.Center
                        MarkdownBlock.Alignment.TRAILING -> TextAlign.End
                        else -> TextAlign.Start
                    }
                    Box(Modifier.width(140.dp).border(0.5.dp, border).padding(8.dp)) {
                        InlineText(cell, style, index, onAction, TextStyle(fontSize = (style.size * 0.92f).sp, fontWeight = if (r == 0) FontWeight.SemiBold else null, textAlign = align),
                            Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineText(inlines: List<MarkdownInline>, style: Style, index: WikiLinkIndex, onAction: (PreviewAction) -> Unit, textStyle: TextStyle, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString { append(inlines, style, index, onAction, textStyle.fontSize) }
    Text(text, modifier, style = textStyle)
}

private fun AnnotatedString.Builder.append(inlines: List<MarkdownInline>, style: Style, index: WikiLinkIndex, onAction: (PreviewAction) -> Unit, size: TextUnit) {
    for (inline in inlines) when (inline) {
        is MarkdownInline.Text -> append(inline.text)
        is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline.children, style, index, onAction, size) }
        is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.children, style, index, onAction, size) }
        is MarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inline.children, style, index, onAction, size) }
        is MarkdownInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = style.codeBackground, fontSize = 0.9.em)) { append(inline.code) }
        is MarkdownInline.Link -> withLink(LinkAnnotation.Clickable(inline.destination, TextLinkStyles(SpanStyle(color = style.link, textDecoration = TextDecoration.Underline))) {
            onAction(PreviewAction.Url(inline.destination))
        }) { append(inline.children, style, index, onAction, size) }
        is MarkdownInline.WikiLinkRef -> {
            val resolved = index.contains(inline.title)
            val linkStyle = if (resolved) SpanStyle(color = style.link, fontWeight = FontWeight.Medium)
            else SpanStyle(color = style.secondary, textDecoration = TextDecoration.Underline)
            withLink(LinkAnnotation.Clickable("wiki:${inline.title}", TextLinkStyles(linkStyle)) { onAction(PreviewAction.WikiLink(inline.title)) }) {
                append(inline.title)
            }
        }
        // Pictures are not drawn: an image in a note is a URL or a Drive reference this app does not fetch.
        is MarkdownInline.Image -> withStyle(SpanStyle(color = style.secondary, fontStyle = FontStyle.Italic)) { append("[${inline.alt.ifBlank { "image" }}]") }
        is MarkdownInline.FootnoteRef -> withStyle(SpanStyle(color = style.link, baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)) { append(inline.index.toString()) }
        MarkdownInline.SoftBreak -> append(' ')
        MarkdownInline.LineBreak -> append('\n')
    }
}
