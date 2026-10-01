package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.report.book.Block
import com.kaiharimoto.mastertool.core.ai.report.book.BookReview
import com.kaiharimoto.mastertool.core.ai.report.book.BookSample
import com.kaiharimoto.mastertool.core.ai.report.book.BookWriter
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookWriterTest {
    private val names = listOf("Arianna the Labrynth Servant", "Welcome Labrynth", "Lady Labrynth of the Silver Castle", "Ash Blossom & Joyous Spring")
    private val ctx = BookWriter.Context(
        known = { n -> names.firstOrNull { it.equals(n.trim(), ignoreCase = true) } },
        deck = List(3) { "Arianna the Labrynth Servant" } + List(3) { "Welcome Labrynth" } + List(34) { "Lady Labrynth of the Silver Castle" },
        now = 1000L,
    )

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun theOutlineComesFirstAndKeepsWrittenChapters() {
        val r = BookWriter.setOutline(GuideBook("Labrynth"), listOf(obj("""{"title":"Lessons","summary":"What decides games"}"""), obj("""{"title":"Lines"}""")))
        assertTrue(r.ok)
        assertEquals(listOf("lessons", "lines"), r.book.chapters.map { it.id })
        assertTrue(r.book.chapters.none { it.written })

        val written = BookWriter.writeChapter(r.book, obj("""{"title":"Lines","sections":[{"title":"One card","blocks":[{"type":"text","text":"Summon [[arianna the labrynth servant]]."}]}]}"""), ctx)
        assertTrue(written.ok, written.message)
        // A new outline without the written chapter keeps it at the end, never drops it.
        val again = BookWriter.setOutline(written.book, listOf(obj("""{"title":"Matchups"}""")))
        assertEquals(listOf("Matchups", "Lines"), again.book.chapters.map { it.title })
        assertTrue(again.book.chapters.last().written)
    }

    @Test
    fun aChapterIsCheckedBeforeItIsKept() {
        val book = GuideBook("Labrynth")
        val bad = BookWriter.writeChapter(
            book,
            obj("""{"title":"Lines","sections":[{"title":"Broken","blocks":[{"type":"line","line":{"name":"L","steps":[{"card":"","action":"x"}]}},{"type":"lesson","maxim":"m","card":"Not A Card"}]}]}"""),
            ctx,
        )
        assertFalse(bad.ok)
        assertTrue("names no card" in bad.message, bad.message)
        assertTrue("Not A Card" in bad.message, bad.message)
        assertEquals(book, bad.book)

        val shape = BookWriter.writeChapter(book, obj("""{"title":"X","sections":[{"title":"Y","blocks":[{"type":"poem","text":"?"}]}]}"""), ctx)
        assertFalse(shape.ok)
        assertTrue("\"type\"" in shape.message, shape.message)
    }

    @Test
    fun namesAreWrittenAsPrintedAndRewritesReplace() {
        val first = BookWriter.writeChapter(
            GuideBook("Labrynth"),
            obj("""{"title":"Lessons","sections":[{"title":"Open Welcome","blocks":[{"type":"lesson","maxim":"Open Welcome","card":"welcome labrynth","number":"74%"}]}]}"""),
            ctx,
        )
        assertTrue(first.ok, first.message)
        val lesson = first.book.chapters.single().sections.single().blocks.single() as Block.Lesson
        assertEquals("Welcome Labrynth", lesson.card)
        assertTrue(lesson.id.isNotBlank())

        val second = BookWriter.writeChapter(
            first.book,
            obj("""{"title":"lessons","sections":[{"title":"Two","blocks":[{"type":"text","text":"a"}]},{"title":"Three","blocks":[{"type":"text","text":"b"}]}]}"""),
            ctx,
        )
        assertEquals(1, second.book.chapters.size)
        assertEquals(2, second.book.chapters.single().sections.size)
        assertEquals(first.book.chapters.single().id, second.book.chapters.single().id)
    }

    @Test
    fun theFrontSetsRolesAndTheFactsAreWorkedOut() {
        val front = BookWriter.setFront(
            GuideBook("Labrynth"),
            obj("""{"title":"Labrynth","big_idea":"Every trap is a card.","roles":[{"name":"Starters","cards":[{"card":"arianna the labrynth servant","copies":3},{"card":"Welcome Labrynth","copies":3}]},{"name":"Boss","cards":[{"card":"Lady Labrynth of the Silver Castle","copies":34}]}]}"""),
            ctx,
        )
        assertTrue(front.ok, front.message)
        assertEquals("Arianna the Labrynth Servant", front.book.roles.first().cards.first().card)
        val facts = BookWriter.facts(front.book, ctx)
        assertTrue("Deck: 40 cards" in facts, facts)
        // Six starters in forty: 1 − C(34,5)/C(40,5) ≈ 58%.
        assertTrue("58%" in facts, facts)

        val unknown = BookWriter.setFront(GuideBook("x"), obj("""{"roles":[{"name":"A","cards":[{"card":"Nope","copies":1}]}]}"""), ctx)
        assertFalse(unknown.ok)
    }

    @Test
    fun aReviewNamesWhatChanged() {
        val before = GuideBook.write(BookSample.labrynth)
        val book = BookSample.labrynth
        val changed = book.copy(chapters = book.chapters.dropLast(1) + book.chapters.last().copy(sections = book.chapters.last().sections.map { it.copy(title = it.title + " now") }))
        val (added, removed) = BookReview.diff(before, GuideBook.write(changed))
        assertTrue(added.isNotEmpty() && added.all { it.startsWith("New:") }, added.toString())
        assertTrue(removed.isNotEmpty())
        assertEquals(Pair(emptyList(), emptyList()), BookReview.diff(before, before))
    }

    @Test
    fun theSampleRoundTripsAndOldFilesStillRead() {
        val text = GuideBook.write(BookSample.labrynth)
        assertEquals(BookSample.labrynth, GuideBook.read(text))
        // A file from an older build, with keys this one does not know, still reads.
        val old = """{"title":"Old","chapters":[{"title":"A","sections":[{"title":"B","blocks":[{"type":"text","text":"t","colour":"red"}]}]}],"future":1}"""
        val read = assertNotNull(GuideBook.read(old))
        assertEquals("t", (read.chapters.single().sections.single().blocks.single() as Block.Text).text)
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertTrue(parsed["chapters"]!!.jsonArray.isNotEmpty())
        assertTrue((parsed["chapters"]!!.jsonArray.first() as JsonObject)["sections"] != null)
    }
}
