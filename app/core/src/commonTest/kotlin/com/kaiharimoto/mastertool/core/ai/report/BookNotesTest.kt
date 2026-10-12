package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.report.book.Block
import com.kaiharimoto.mastertool.core.ai.report.book.BookNotes
import com.kaiharimoto.mastertool.core.ai.report.book.BookSample
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.report.book.blockIds
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The person's own marks in a reader's guide (Phase H, H.6): kept by block, never lost to a rewrite, read by Ai. */
class BookNotesTest {
    private val book = BookSample.labrynth
    private val first = book.chapters.first().sections.first().blocks.first().id
    private val second = book.chapters.first().sections.first().blocks[1].id

    @Test
    fun aBlockIsHighlightedAndNotedAndAnEmptyMarkGoes() {
        var n = BookNotes()
        n = n.highlight(first, true, 1).note(first, "  Why the trap first  ", 2).note(second, "Check this", 3)
        assertEquals(BookNotes.Mark(first, highlight = true, note = "Why the trap first", at = 2), n.of(first))
        assertEquals(2, n.marks.size)
        // A mark with nothing left on it is no mark.
        n = n.highlight(first, false, 4).note(first, "", 5)
        assertNull(n.of(first))
        assertEquals(1, n.marks.size)
        n = n.remove(second)
        assertTrue(n.marks.isEmpty())
        assertEquals(BookNotes.NOTE_MAX, BookNotes().note(first, "x".repeat(BookNotes.NOTE_MAX + 10), 1).of(first)!!.note.length)
    }

    @Test
    fun aRewriteNeverLosesANoteItWaitsApart() {
        val n = BookNotes().note(first, "Keep this", 1).highlight(second, true, 2)
        // The first block rewritten away: its note waits apart; the second stays where it was.
        val rewritten = book.copy(chapters = book.chapters.mapIndexed { i, c ->
            if (i != 0) c else c.copy(sections = c.sections.mapIndexed { j, s -> if (j != 0) s else s.copy(blocks = s.blocks.drop(1)) })
        })
        assertTrue(first !in rewritten.blockIds() && second in rewritten.blockIds())
        assertEquals(listOf(first), n.orphans(rewritten).map { it.block })
        assertTrue(n.orphans(book).isEmpty())
    }

    @Test
    fun aiReadsTheMarksWithWhereTheyAreAndTheFileReadsForgivingly() {
        val n = BookNotes().note(first, "Ask about Welcome Labrynth", 1).highlight(second, true, 2)
        val words = BookNotes.words(n, book)
        val chapter = book.chapters.first()
        assertTrue("theirs alone" in words, words)
        assertTrue("${chapter.title} › ${chapter.sections.first().title}" in words, words)
        assertTrue("Their note: Ask about Welcome Labrynth" in words && "highlighted." in words, words)
        assertEquals("", BookNotes.words(BookNotes(), book))
        assertEquals("", BookNotes.words(n, book, book.chapters.last().id), "another chapter's marks are not this one's")
        assertEquals(n, BookNotes.read(BookNotes.write(n)))
        assertTrue(BookNotes.read("not json").marks.isEmpty())
        assertEquals(BookNotes(), BookNotes.read(null))
        assertEquals(1, BookNotes.read("""{"marks":[{"block":"a","note":"x","later":true}],"future":1}""").marks.size)
        assertEquals("guides/deck-1.notes.json", BookNotes.path("deck-1"))
        assertTrue(book.chapters.first().sections.first().blocks.first() is Block)
    }

    @Test
    fun oneBadMarkNeverEmptiesTheNotesAndTheNextWriteKeepsIt() {
        // The red team, 2026-10: a mark that would not read (a null note, a newer build's shape) read the whole file as no
        // marks, and the person's next mark wrote over every other one.
        val file = """{"version":1,"marks":[{"block":"$first","note":"Keep this","at":1},{"block":"broken","note":null},""" +
            """{"block":"$second","highlight":true,"at":2},{"block":"newer","ticks":"all"}]}"""
        val n = BookNotes.read(file)
        assertEquals(listOf(first, second), n.marks.map { it.block })
        assertEquals("Keep this", n.of(first)!!.note)
        assertEquals(2, n.unread.size, "the two that would not read are kept as they were")
        // The person's next mark: written with every mark, the unreadable ones among them, untouched.
        val written = BookNotes.write(n.note(second, "Ask Ai", 3))
        val again = BookNotes.read(written)
        assertEquals(listOf(first, second), again.marks.map { it.block })
        assertEquals("Ask Ai", again.of(second)!!.note)
        assertEquals(n.unread, again.unread)
        assertTrue("\"broken\"" in written && "\"newer\"" in written && "\"all\"" in written, written)
        // A file that will not read at all is kept whole, as text, through the next write.
        val torn = """{"version":1,"marks":[{"block":"$first","note":"half"""
        val kept = BookNotes.read(torn)
        assertTrue(kept.marks.isEmpty())
        val over = BookNotes.read(BookNotes.write(kept.note(first, "new", 4)))
        assertEquals("new", over.of(first)!!.note)
        assertEquals(listOf(torn), over.unread.map { it.jsonPrimitive.content })
    }

    @Test
    fun theInteractiveBooksTicksAndGradesAreKeptAndAiReadsThem() {
        // H.6, the interactive book: a checklist's ticks and a puzzle's hands graded by the person are their marks too.
        val checklist = book.chapters.flatMap { c -> c.sections.flatMap { it.blocks } }.filterIsInstance<Block.Checklist>().first()
        var n = BookNotes().tick(checklist.id, 2, true, 1).tick(checklist.id, 0, true, 2).tick(checklist.id, 2, true, 3)
        assertEquals(listOf(0, 2), n.of(checklist.id)!!.ticks)
        n = n.grade("puzzles", 1, true, 4).grade("puzzles", 2, false, 5).grade("puzzles", 1, false, 6)
        val m = n.of("puzzles")!!
        assertEquals(false, m.graded(1))
        assertEquals(false, m.graded(2))
        assertNull(m.graded(0))
        assertEquals(listOf(1, 2), m.missed)
        assertTrue(m.got.isEmpty())
        // Read back whole, and Ai is told what was ticked and which hands to teach again.
        assertEquals(n, BookNotes.read(BookNotes.write(n)))
        val words = BookNotes.words(n, book)
        assertTrue("ticked 2 of ${checklist.items.size}." in words, words)
        // Unticked and ungraded, nothing is left of the mark.
        n = n.tick(checklist.id, 0, false, 7).tick(checklist.id, 2, false, 8).grade("puzzles", 1, null, 9).grade("puzzles", 2, null, 10)
        assertTrue(n.marks.isEmpty(), n.marks.toString())
    }

    @Test
    fun aPuzzleHandsGradesAreSaidToAiWhereTheBlockIs() {
        val hands = Block.Hands(listOf(Block.Hands.Hand(listOf("Ash Blossom & Joyous Spring"), "keep", "Hold Ash for their starter.")), id = "c9.s1.b1")
        val withPuzzle = book.copy(chapters = book.chapters.mapIndexed { i, c ->
            if (i != 0) c else c.copy(sections = c.sections.mapIndexed { j, s -> if (j != 0) s else s.copy(blocks = s.blocks + hands) })
        })
        val words = BookNotes.words(BookNotes().grade(hands.id, 0, false, 1), withPuzzle)
        assertTrue("puzzle hands: had 0, missed 1 (hand 1 missed: worth teaching again)." in words, words)
    }
}
