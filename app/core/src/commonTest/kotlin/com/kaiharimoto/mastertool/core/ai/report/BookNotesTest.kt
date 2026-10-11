package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.report.book.Block
import com.kaiharimoto.mastertool.core.ai.report.book.BookNotes
import com.kaiharimoto.mastertool.core.ai.report.book.BookSample
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.report.book.blockIds
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
        assertEquals(BookNotes(), BookNotes.read("not json"))
        assertEquals(BookNotes(), BookNotes.read(null))
        assertEquals(1, BookNotes.read("""{"marks":[{"block":"a","note":"x","later":true}],"future":1}""").marks.size)
        assertEquals("guides/deck-1.notes.json", BookNotes.path("deck-1"))
        assertTrue(book.chapters.first().sections.first().blocks.first() is Block)
    }
}
