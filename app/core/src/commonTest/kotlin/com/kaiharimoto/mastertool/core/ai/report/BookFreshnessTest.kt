package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.report.book.Block
import com.kaiharimoto.mastertool.core.ai.report.book.BookFreshness
import com.kaiharimoto.mastertool.core.ai.report.book.BookFreshness.DeckState
import com.kaiharimoto.mastertool.core.ai.report.book.BookWriter
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The reader's guide against the deck as it is (1.0.99): what the red team found it never noticed. */
class BookFreshnessTest {
    private val names = listOf("Arianna the Labrynth Servant", "Welcome Labrynth", "Lady Labrynth of the Silver Castle")
    private val deckA = Deck(main = List(3) { CardId(1) } + List(3) { CardId(2) } + List(34) { CardId(3) })
    private val deckB = Deck(main = List(2) { CardId(1) } + List(3) { CardId(2) } + List(35) { CardId(3) })
    private val printA = Ledger.fingerprint(deckA)
    private val printB = Ledger.fingerprint(deckB)

    private fun ctx(print: String, notes: String = "n1") = BookWriter.Context(
        known = { n -> names.firstOrNull { it.equals(n.trim(), ignoreCase = true) } },
        deck = null,
        now = 1000L,
        deckPrint = print,
        notesHash = notes,
    )

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun chapter(title: String, text: String = "Go.") =
        obj("""{"title":"$title","sections":[{"title":"S","blocks":[{"type":"text","text":"$text"}]}]}""")

    @Test
    fun aBookWrittenOnDeckAReadsStaleOnDeckBAndCurrentOnA() {
        val book = BookWriter.writeChapter(GuideBook("Labrynth"), chapter("Lines"), ctx(printA)).book
        assertEquals(printA, book.chapters.single().deckPrint)

        val onA = BookFreshness.of(book, printA, "n1")
        assertEquals(DeckState.CURRENT, onA.deck)
        assertFalse(onA.stale)
        assertTrue(BookFreshness.words(book, onA).isEmpty())

        val onB = BookFreshness.of(book, printB, "n1")
        assertEquals(DeckState.CHANGED, onB.deck)
        assertTrue(onB.stale)
        assertEquals(listOf(book.chapters.single().id), onB.olderDeck)
        assertTrue(BookFreshness.words(book, onB).single().startsWith("The deck has changed since the guide was written"))
    }

    @Test
    fun aWriteMakesOnlyItsOwnChapterCurrent() {
        var book = BookWriter.writeChapter(GuideBook("Labrynth"), chapter("Lessons"), ctx(printA)).book
        book = BookWriter.writeChapter(book, chapter("Lines"), ctx(printA)).book
        // The deck changes; one chapter is written again on it.
        book = BookWriter.writeChapter(book, chapter("Lines", "Again."), ctx(printB, notes = "n2")).book
        val s = BookFreshness.of(book, printB, "n2")
        assertEquals(DeckState.CHANGED, s.deck)
        assertEquals(listOf("lessons"), s.olderDeck)
        assertEquals(listOf("lessons"), s.olderNotes)
        val words = BookFreshness.words(book, s, "Ai")
        assertEquals(2, words.size)
        assertTrue("since chapter 01 was written" in words[0], words[0])
        assertTrue("Ai's notes on this deck have changed since chapter 01 was written." == words[1], words[1])
        // And Ai's outline says which to write again.
        assertTrue("written on an older deck" in BookWriter.outline(book, ctx(printB)).lines().first { "[lessons]" in it })
        assertFalse("written on an older deck" in BookWriter.outline(book, ctx(printB)).lines().first { "[lines]" in it })
    }

    @Test
    fun whatAiSendsCannotStampAChapterCurrent() {
        val forged = obj("""{"title":"Lines","deckPrint":"$printB","notesHash":"n9","sections":[{"title":"S","blocks":[{"type":"text","text":"Go."}]}]}""")
        val book = BookWriter.writeChapter(GuideBook("Labrynth"), forged, ctx(printA)).book
        assertEquals(printA, book.chapters.single().deckPrint)
        assertEquals("n1", book.chapters.single().notesHash)
    }

    @Test
    fun theFrontRemembersTheDeckItsRolesWereSetOn() {
        val front = BookWriter.setFront(
            GuideBook("Labrynth"),
            obj("""{"roles":[{"name":"Starters","cards":[{"card":"Welcome Labrynth","copies":3}]}]}"""),
            ctx(printA),
        ).book
        assertEquals(printA, front.deckPrint)
        // A title alone does not re-stamp it.
        assertEquals(printA, BookWriter.setFront(front, obj("""{"title":"New"}"""), ctx(printB)).book.deckPrint)
        val s = BookFreshness.of(front, printB, null)
        assertTrue(s.frontOnOlderDeck)
        assertEquals(DeckState.CHANGED, s.deck)
    }

    @Test
    fun aBookFromBeforeTheDeckWasRecordedReadsDeckUnknown() {
        // 1.0.98's shape: a notes hash, no deck anywhere.
        val old = """{"title":"Labrynth","notesHash":"abc","chapters":[{"id":"lines","title":"Lines","sections":[
            {"id":"lines/s","title":"S","blocks":[{"type":"text","text":"Opens 74% of the time."}]}]}]}"""
        val book = assertNotNull(GuideBook.read(old))
        val s = BookFreshness.of(book, printA, "abc")
        assertEquals(DeckState.UNKNOWN, s.deck)
        assertFalse(s.stale)
        assertTrue(BookFreshness.words(book, s).isEmpty())
        // The notes are still checked as they were: against the book's own hash.
        assertEquals(listOf("lines"), BookFreshness.of(book, printA, "def").olderNotes)
        // And it is not stale for ever: written again, the chapter is current.
        val again = BookWriter.writeChapter(book, chapter("Lines", "Again."), ctx(printA, notes = "abc")).book
        assertEquals(DeckState.CURRENT, BookFreshness.of(again, printA, "abc").deck)
        // A deck the reader cannot find is unknown too, never stale.
        assertEquals(DeckState.UNKNOWN, BookFreshness.of(again, null, "abc").deck)
    }

    @Test
    fun theNumbersAiTypedAreFound() {
        assertEquals(listOf("74%", "1 in 4"), BookFreshness.typedNumbers(Block.Text("Opens 74% of the time; bricks 1 in 4 hands. Run 3 copies.")))
        assertEquals(listOf("58%"), BookFreshness.typedNumbers(Block.Lesson("Open Welcome", card = "Welcome Labrynth", number = "58%")))
        assertEquals(listOf("40 cards"), BookFreshness.typedNumbers(Block.Lesson("Forty", number = "40 cards")))
        // The pictures' numbers are the app's, worked out from the deck: nothing typed.
        assertTrue(BookFreshness.typedNumbers(Block.Cells()).isEmpty())
        assertTrue(BookFreshness.typedNumbers(Block.Odds(listOf(Block.Odds.Row("Starters", role = "Starters")))).isEmpty())
        assertEquals(listOf("30%"), BookFreshness.typedNumbers(Block.Table(listOf("Card", "Share"), listOf(listOf("Ash", "30%")))))
    }

    @Test
    fun chaptersAreNamedByTheirPlace() {
        val book = GuideBook("x", chapters = listOf("a", "b", "c").map { GuideBook.Chapter(id = it, title = it) })
        assertEquals("chapter 02", BookFreshness.chapters(book, listOf("b")))
        assertEquals("chapters 01 and 03", BookFreshness.chapters(book, listOf("a", "c")))
        assertEquals("chapters 01, 02 and 03", BookFreshness.chapters(book, listOf("c", "a", "b")))
    }
}
