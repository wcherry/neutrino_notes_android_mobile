package com.neutrino.notes.core

import com.neutrino.notes.core.markdown.ListItemBlock
import com.neutrino.notes.core.markdown.MarkdownBlock
import com.neutrino.notes.core.markdown.MarkdownEditing
import com.neutrino.notes.core.markdown.MarkdownFormat
import com.neutrino.notes.core.markdown.MarkdownInline
import com.neutrino.notes.core.markdown.MarkdownParser
import com.neutrino.notes.core.markdown.TextDiff
import com.neutrino.notes.core.markdown.WikiLink
import com.neutrino.notes.core.markdown.WikiLinkIndex
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NoteTextStats
import com.neutrino.notes.core.service.FileEventsMessage
import com.neutrino.notes.core.service.TagsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Ports of the iOS (and, through them, the web's) tests for the same rules: the clients must agree. */
class WikiLinkTest {

    @Test
    fun linksAreBracketedTrimmedAndNeverEmpty() {
        val matches = WikiLink.matches("See [[ Meeting Notes ]] and [[]] and [[   ]] then [[Plan]].")
        assertEquals(listOf("Meeting Notes", "Plan"), matches.map { it.title })
        assertEquals(4, matches[0].start)
    }

    @Test
    fun titlesDedupeCaseInsensitivelyAndTravelWithTheirMdSpelling() {
        assertEquals(listOf("Plan", "Other"), WikiLink.titles("[[Plan]] [[plan]] [[Other]]"))
        assertEquals(listOf("Plan", "Plan.md", "Other.md", "Other.md.md"), WikiLink.requestTitles("[[Plan]] [[Other.md]]"))
    }

    @Test
    fun namesAndTitles() {
        assertEquals("Meeting Notes", WikiLink.displayTitle("Meeting Notes.md"))
        assertEquals(".md", WikiLink.displayTitle(".md"))
        assertEquals("Q3.md", WikiLink.fileName(" Q3 "))
        assertEquals("Q3.md", WikiLink.fileName("Q3.md"))
        assertEquals("Untitled.md", WikiLink.fileName(""))
    }

    @Test
    fun relativeLinksNameANoteByFileName() {
        assertEquals("Help.md", WikiLink.titleForRelativeLink("./notes/Help.md"))
        assertEquals("My Note.md", WikiLink.titleForRelativeLink("My%20Note.md"))
        assertNull(WikiLink.titleForRelativeLink("https://example.com/a.md"))
        assertNull(WikiLink.titleForRelativeLink("#section"))
        assertNull(WikiLink.titleForRelativeLink("mailto:a@b.c"))
    }

    @Test
    fun theAutocompleteTokenIsTheUnclosedLinkBeforeTheCaret() {
        val text = "a [[Mee"
        assertEquals(WikiLink.Token(2, 7, "Mee"), WikiLink.token(text, text.length))
        assertNull(WikiLink.token("a [[Done]]", 10))
        assertNull(WikiLink.token("[[x\ny", 5))
        assertNull(WikiLink.token("[[a]b", 5))
    }

    @Test
    fun theIndexMatchesEitherSpellingAndPrefersTheNewestNote() {
        val old = note("1", "Plan.md", 1)
        val new = note("2", "plan", 2)
        val index = WikiLinkIndex(listOf(old, new, note("3", "Planet.md", 3), note("4", "Ideas about plans.md", 0)))
        assertEquals("2", index.item("PLAN")?.id)
        assertEquals("2", index.item("Plan.md")?.id)
        assertEquals(listOf("3", "2", "1", "4"), index.suggestions("pla").map { it.id })
        assertEquals("3", index.suggestions("").first().id)
    }

    private fun note(id: String, name: String, t: Long) =
        NoteItem(id, name, NoteItem.ItemType.FILE, null, modifiedAt = Instant.ofEpochSecond(t), mimeType = NoteItem.MARKDOWN_MIME)
}

class EditingTest {

    @Test
    fun returnContinuesEveryKindOfList() {
        fun ret(text: String) = MarkdownEditing.returnPressed(text, text.length)!!.applyTo(text)
        assertEquals("- [ ] a\n- [ ] ", ret("- [ ] a"))
        assertEquals("  * b\n  * ", ret("  * b"))
        assertEquals("9) c\n10) ", ret("9) c"))
        assertEquals("1. [x] d\n2. [ ] ", ret("1. [x] d"))
        assertNull(MarkdownEditing.returnPressed("plain", 5))
    }

    @Test
    fun returnOnAnEmptyItemEndsTheList() {
        val text = "- a\n- [ ] "
        val edit = MarkdownEditing.returnPressed(text, text.length)!!
        // A blank line is left, so what is typed next is not read as more of the last item.
        assertEquals("- a\n\n", edit.applyTo(text))
        assertEquals(5, edit.caret)
        val parsed = com.neutrino.notes.core.markdown.MarkdownParser.parse(edit.applyTo(text) + "After")
        assertEquals(2, parsed.blocks.size)
    }

    @Test
    fun aCaretInsideTheMarkerIsLeftAlone() {
        assertNull(MarkdownEditing.returnPressed("- [ ] a", 2))
    }

    @Test
    fun slashCommandsAreTheStartOfALine() {
        assertEquals(MarkdownEditing.SlashToken(3, 7, "hea"), MarkdownEditing.slashToken("ab\n/hea", 7))
        assertNull(MarkdownEditing.slashToken("x /hea", 6))
        assertNull(MarkdownEditing.slashToken("/a b", 4))
        assertEquals(listOf("heading1", "heading2", "heading3"), MarkdownFormat.matching("h").map { it.id }.filter { it.startsWith("heading") })
        assertEquals(listOf("checklist"), MarkdownFormat.matching("todo").map { it.id })
    }

    @Test
    fun formatsWrapPrefixOrInsertASnippet() {
        val bold = MarkdownFormat.byId("bold")
        assertEquals("a **word** b", MarkdownEditing.apply(bold, "a word b", 2, 6).applyTo("a word b"))
        val empty = MarkdownEditing.apply(bold, "ab", 1, 1)
        assertEquals("a****b", empty.applyTo("ab"))
        assertEquals(3, empty.caret)
        val quote = MarkdownFormat.byId("quote")
        assertEquals("> one\n> two", MarkdownEditing.apply(quote, "one\ntwo", 0, 7).applyTo("one\ntwo"))
        val numbered = MarkdownFormat.byId("numberedList")
        assertEquals("1. one\n2. two", MarkdownEditing.apply(numbered, "one\ntwo", 1, 5).applyTo("one\ntwo"))
        val slash = MarkdownEditing.slashToken("/che", 4)!!
        assertEquals("- [ ] ", MarkdownEditing.apply(MarkdownFormat.byId("checklist"), "/che", 4, 4, slash).applyTo("/che"))
    }

    @Test
    fun aTappedCheckboxFlipsTheLineItStartsOn() {
        val text = "```\n- [ ] not a task\n```\n- [ ] real\n- [x] done"
        assertEquals("```\n- [ ] not a task\n```\n- [x] real\n- [x] done", MarkdownEditing.toggleTask(text, 3))
        assertEquals("```\n- [ ] not a task\n```\n- [ ] real\n- [ ] done", MarkdownEditing.toggleTask(text, 4))
        assertNull(MarkdownEditing.toggleTask(text, 0))
    }

    @Test
    fun diffIsLineLevelWithDeletionsFirst() {
        val diff = TextDiff.compare("a\nb\nc\n", "a\nB\nc")
        assertEquals(listOf(TextDiff.Kind.UNCHANGED, TextDiff.Kind.DELETED, TextDiff.Kind.INSERTED, TextDiff.Kind.UNCHANGED), diff.map { it.kind })
        assertTrue(TextDiff.isIdentical("x\n", "x"))
    }

    @Test
    fun statsAndTagDiffs() {
        assertEquals(NoteTextStats(3, 13, 1), NoteTextStats.compute("one two\nthree"))
        assertEquals(listOf("c") to listOf("a"), TagsService.diff(setOf("a", "b"), setOf("b", "c")))
    }

    @Test
    fun fileEventFramesRoundTripAndOthersAreIgnored() {
        val frame = FileEventsMessage.encodeFileUpdated("me")
        assertEquals(2, frame[0].toInt())
        assertEquals("me", FileEventsMessage.decodeFileUpdatedSender(frame))
        assertNull(FileEventsMessage.decodeFileUpdatedSender(byteArrayOf(1, '{'.code.toByte())))
        assertEquals(300 to 2, FileEventsMessage.readVarint(byteArrayOf(0xAC.toByte(), 0x02)))
        assertEquals("wss://www.getneutrino.app/api/v1/files/f/ws?token=a.b-c_d", FileEventsMessage.socketUrl("https://www.getneutrino.app", "f", "a.b-c_d"))
        assertEquals("ws://10.0.2.2:8080/api/v1/files/f/ws?token=t", FileEventsMessage.socketUrl("http://10.0.2.2:8080", "f", "t"))
    }
}

class ParserTest {

    @Test
    fun blocksInlinesAndWikiLinks() {
        val doc = MarkdownParser.parse("# Title\n\nSome **bold** and *it* ~~gone~~ `code` [[Other Note]] [web](https://x.y)\n\n> quoted\n\n---\n\n```kotlin\nval x = \"[[no]]\"\n```")
        assertEquals(MarkdownBlock.Heading(1, listOf(MarkdownInline.Text("Title"))), doc.blocks[0])
        val para = (doc.blocks[1] as MarkdownBlock.Paragraph).inlines
        assertTrue(MarkdownInline.Strong(listOf(MarkdownInline.Text("bold"))) in para)
        assertTrue(MarkdownInline.Emphasis(listOf(MarkdownInline.Text("it"))) in para)
        assertTrue(MarkdownInline.Strikethrough(listOf(MarkdownInline.Text("gone"))) in para)
        assertTrue(MarkdownInline.Code("code") in para)
        assertTrue(MarkdownInline.WikiLinkRef("Other Note") in para)
        assertTrue(MarkdownInline.Link(listOf(MarkdownInline.Text("web")), "https://x.y") in para)
        assertTrue(doc.blocks[2] is MarkdownBlock.Quote)
        assertEquals(MarkdownBlock.Rule, doc.blocks[3])
        assertEquals(MarkdownBlock.CodeBlock("val x = \"[[no]]\"", "kotlin"), doc.blocks[4])
    }

    @Test
    fun taskListsKnowTheLineTheyStartOn() {
        val list = MarkdownParser.parse("[^a]: gone\nIntro\n\n- [ ] one\n- [x] two\n- three").blocks[1] as MarkdownBlock.ListBlock
        assertEquals(listOf(false, true, null), list.items.map(ListItemBlock::checkbox))
        // The footnote definition line is taken out before parsing; the lines still count from the note's top.
        assertEquals(listOf(3, 4, 5), list.items.map { it.sourceLine })
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("one"))), list.items[0].blocks.single())
    }

    @Test
    fun orderedListsTablesAndFootnotes() {
        val doc = MarkdownParser.parse("3. a\n4. b\n\n| L | C | R |\n|:--|:-:|--:|\n| 1 | 2 | 3 |\n\nSee[^n] and[^m].\n\n[^m]: Em\n[^n]: En *x*\n[^z]: unused")
        val list = doc.blocks[0] as MarkdownBlock.ListBlock
        assertTrue(list.isOrdered)
        assertEquals(3, list.startIndex)
        val table = doc.blocks[1] as MarkdownBlock.Table
        assertEquals(listOf(MarkdownBlock.Alignment.LEADING, MarkdownBlock.Alignment.CENTER, MarkdownBlock.Alignment.TRAILING), table.alignments)
        assertEquals(1, table.rows.size)
        val refs = (doc.blocks[2] as MarkdownBlock.Paragraph).inlines.filterIsInstance<MarkdownInline.FootnoteRef>()
        assertEquals(listOf("n" to 1, "m" to 2), refs.map { it.label to it.index })
        assertEquals(listOf("n", "m", "z"), doc.footnotes.map { it.label })
        assertEquals(3, doc.footnotes.last().index)
        assertFalse(doc.footnotes[0].content.isEmpty())
    }

    @Test
    fun plainTextReadsThroughMarkup() {
        val p = MarkdownParser.parse("a **b** [[C]] `d`").blocks.single() as MarkdownBlock.Paragraph
        assertEquals("a b C d", MarkdownParser.plainText(p.inlines))
    }
}
