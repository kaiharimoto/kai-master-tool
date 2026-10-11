package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.report.book.Block
import com.kaiharimoto.mastertool.core.ai.report.book.BookArt
import com.kaiharimoto.mastertool.core.ai.report.book.BookHtml
import com.kaiharimoto.mastertool.core.ai.report.book.BookNotes
import com.kaiharimoto.mastertool.core.ai.report.book.BookSample
import com.kaiharimoto.mastertool.core.ai.report.book.Drawing
import com.kaiharimoto.mastertool.core.ai.report.book.Faces
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.report.book.SvgInk
import com.kaiharimoto.mastertool.core.ai.report.book.Weight
import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The book as one HTML page (Phase H, H.6): contents that link, words as text, and the same drawings as SVG. */
class BookHtmlTest {
    private fun bytes(name: String) = File("../table/src/commonMain/composeResources/font/$name.ttf").readBytes()
    private val raw = mapOf(Weight.REGULAR to bytes("inter_regular"), Weight.MEDIUM to bytes("inter_medium"), Weight.BOLD to bytes("inter_bold"), Weight.MONO to bytes("jetbrainsmono_regular"))
    private val fonts = GuideFonts(TrueType(raw.getValue(Weight.REGULAR)), TrueType(raw.getValue(Weight.BOLD)), TrueType(raw.getValue(Weight.MONO)), TrueType(raw.getValue(Weight.MEDIUM)))
    private val faces = Faces.of(fonts)
    private val book = BookSample.labrynth
    private val art: (String) -> String? = { name -> if (name.length % 3 == 0) null else "data:image/jpeg;base64,AAAA" }

    @Test
    fun theBookIsOnePageWithContentsThatLinkAndTheSameDrawings() {
        val html = BookHtml.render(book, raw, faces, art, "1 October 2026")
        File("build/report-samples").apply { mkdirs() }.resolve("book.html").writeText(html)
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue("@font-face{font-family:BookSans;font-weight:700;src:url(data:font/ttf;base64," in html, "the fonts inside")
        // Every chapter and section is a place, and the contents link to each.
        book.chapters.forEach { c ->
            assertTrue("id=\"${c.id}\"" in html && "href=\"#${c.id}\"" in html, c.title)
            if (c.written) c.sections.forEach { s -> assertTrue("id=\"${s.id}\"" in html && "href=\"#${s.id}\"" in html, s.title) }
        }
        // Every picture is the drawing the app and the PDF paint: as many SVGs as the blocks have drawings.
        val art = BookArt.of(faces, book)
        val expected = book.chapters.filter { it.written }.flatMap { it.sections }.flatMap { it.blocks }.sumOf { b ->
            when (b) {
                is Block.Text -> 0
                is Block.Line -> art.drawings(b, BookHtml.COLUMN).size + when {
                    b.frames && b.line.steps.isNotEmpty() -> art.frames(b.line, (BookHtml.COLUMN - 18f) / 2).size
                    b.line.endBoard.isNotEmpty() || b.line.endSet.isNotEmpty() -> 1
                    else -> 0
                }
                else -> art.drawings(b, BookHtml.COLUMN).size
            }
        }
        val svgs = Regex("<svg .*?</svg>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.value }.toList()
        assertTrue(expected > 0)
        assertEquals(expected, svgs.size)
        // Each is well-formed XML, with no number a browser cannot read, and clip paths never collide.
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        svgs.forEach { parser.parse(it.byteInputStream()) }
        val numbers = Regex("\\s(?:x|y|x1|y1|x2|y2|width|height|font-size|stroke-width|opacity|letter-spacing)=\"([^\"]*)\"").findAll(svgs.joinToString("")).map { it.groupValues[1] }.toList()
        assertTrue(numbers.isNotEmpty())
        numbers.forEach { assertTrue(Regex("-?\\d+(\\.\\d+)?").matches(it), "a number a browser cannot read: $it") }
        val clips = Regex("clipPath id=\"(c\\d+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(clips.size, clips.toSet().size)
        // A card with no picture is a quiet box with its name, as in the PDF; one with a picture is an image.
        assertTrue("<image href=\"data:image/jpeg;base64,AAAA\"" in html)
        assertTrue("fill=\"#f2f2f2\"" in html)
    }

    @Test
    fun thePersonsMarksAreInIt() {
        val block = book.chapters.first().sections.first().blocks.first().id
        val notes = BookNotes().highlight(block, true, 1).note(block, "Mine & <yours>", 2)
        val html = BookHtml.render(book, raw, faces, art, notes = notes)
        assertTrue("<div class=\"hl\">" in html)
        assertTrue("<aside class=\"note\"><p class=\"micro\">Your note</p><p>Mine &amp; &lt;yours&gt;</p></aside>" in html)
        assertTrue("<div class=\"hl\">" !in BookHtml.render(book, raw, faces, art), "no marks, no bars")
    }

    @Test
    fun wordsAreTextAndEscaped() {
        val odd = GuideBook(
            "Fish & <Chips>",
            chapters = listOf(GuideBook.Chapter(title = "A \"first\" look", sections = listOf(GuideBook.Section(title = "One", blocks = listOf(Block.Text("Go <now> & win.", label = "Why")))))),
        ).withIds()
        val html = BookHtml.render(odd, raw, faces, { null })
        assertTrue("<h1>Fish &amp; &lt;Chips&gt;</h1>" in html)
        assertTrue("<p>Go &lt;now&gt; &amp; win.</p>" in html)
        assertTrue("<p class=\"label\">Why</p>" in html)
        assertTrue("&quot;first&quot;" in html || "\"first\"" in html)
    }

    @Test
    fun aPlannedChapterSaysSoAndANumberIsShort() {
        val planned = GuideBook("Empty", chapters = listOf(GuideBook.Chapter(title = "Lines"))).withIds()
        assertTrue("Not written yet" in BookHtml.render(planned, raw, faces, { null }))
        assertEquals("12.5", SvgInk.n(12.5f))
        assertEquals("-3", SvgInk.n(-3.001f))
        assertEquals("0.05", SvgInk.n(0.05f))
        assertEquals("#000000", SvgInk.gray(0f))
        assertEquals("#ffffff", SvgInk.gray(1f))
    }

    @Test
    fun aPuzzleHandKeepsItsAnswerBackUntilAskedFor() {
        // The interactive book (H.6): the reader draws a puzzle hand's question first, its verdict and answer once asked.
        val hand = Block.Hands.Hand(listOf("Ash Blossom & Joyous Spring", "Welcome Labrynth"), "keep", "Set Welcome and hold Ash.")
        val art = BookArt.of(faces, book)
        fun words(answer: Boolean) = art.puzzle(hand, 360f, answer).ops.filterIsInstance<Drawing.Text>().joinToString(" ") { it.text }
        val asked = words(false)
        assertTrue("What do you do with it?" in asked, asked)
        assertTrue("Set Welcome" !in asked && "keep" !in asked.lowercase().replace("what do you do with it?", ""), asked)
        val answered = words(true)
        assertTrue("Set Welcome" in answered, answered)
        // The PDF and the HTML print it whole, as before.
        assertEquals(art.puzzle(hand, 360f, true), art.drawings(Block.Hands(listOf(hand)), 360f).single())
    }
}
