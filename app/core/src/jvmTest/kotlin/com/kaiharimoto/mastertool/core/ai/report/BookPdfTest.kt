package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.report.book.Block
import com.kaiharimoto.mastertool.core.ai.report.book.BookArt
import com.kaiharimoto.mastertool.core.ai.report.book.BookPdf
import com.kaiharimoto.mastertool.core.ai.report.book.BookSample
import com.kaiharimoto.mastertool.core.ai.report.book.Faces
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.report.book.GuideFacts
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The guide as a book (1.0.67): a PDF with its contents, links and bookmarks; drawings that fit; a file that round-trips. */
class BookPdfTest {
    private fun font(name: String) = TrueType(File("../neue/src/commonMain/composeResources/font/$name.ttf").readBytes())
    private val fonts = GuideFonts(font("inter_regular"), font("inter_bold"), font("jetbrainsmono_regular"), font("inter_medium"))
    private val book = BookSample.labrynth
    private val pictures = book.cards().associateWith { name -> PdfImage(4, 6, ByteArray(4 * 6 * 3) { (name.length * 7).toByte() }) }

    private fun pages(pdf: String) = Regex("/Type\\s*/Page[^s]").findAll(pdf).count()

    @Test
    fun theBookIsAPhonePdfWithContentsLinksAndBookmarks() {
        val bytes = BookPdf.render(book, fonts, { pictures[it] }, null, "1 October 2026")
        File("build/report-samples").apply { mkdirs() }.resolve("book.pdf").writeBytes(bytes)
        val pdf = String(bytes, Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-"))
        assertTrue("/MediaBox [0 0 400.0 866.0]" in pdf, "phone-shaped")
        val sections = book.chapters.sumOf { it.sections.size }
        assertTrue(pages(pdf) > sections, "a page at least a section: ${pages(pdf)} for $sections sections")
        assertTrue("/Subtype /Link" in pdf, "the contents link to their pages")
        val links = Regex("/Subtype /Link").findAll(pdf).count()
        assertTrue(links >= book.chapters.size + sections, "every chapter and section linked at least once: $links")
        assertTrue("/Outlines" in pdf, "bookmarks for a viewer's sidebar")
        assertEquals(book.chapters.size + sections, Regex("/Title <FEFF").findAll(pdf).count() - 1, "a bookmark a chapter and a section, and the document's title")
    }

    @Test
    fun aBookWithNothingWrittenStillRenders() {
        val planned = GuideBook("Empty", chapters = listOf(GuideBook.Chapter(title = "Lines"))).withIds()
        assertTrue(String(BookPdf.render(planned, fonts, { null }, null), Charsets.ISO_8859_1).startsWith("%PDF-"))
    }

    @Test
    fun theBookRoundTripsAndReadsTolerantly() {
        val again = GuideBook.read(GuideBook.write(book))
        assertEquals(book, again)
        assertNull(GuideBook.read("not json"))
        val written = GuideBook.read("""{"title":"X","chapters":[{"title":"Lines","sections":[{"title":"One card","blocks":[{"type":"text","text":"Go."},{"type":"callout","text":"Careful.","kind":"misplay","later":1}]}]}]}""")
        assertNotNull(written)
        assertTrue(written.chapters.single().sections.single().blocks[1] is Block.Callout)
        // Ids are given where they are missing, and are unique.
        val ids = book.chapters.flatMap { c -> listOf(c.id) + c.sections.flatMap { s -> listOf(s.id) + s.blocks.map { it.id } } }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun everyDrawingStaysInsideItsWidth() {
        val faces = Faces.of(fonts)
        val art = BookArt(faces, book, GuideFacts.of(book.roles, null), { if (it.contains("Welcome") || it.contains("Trap") || it.contains("Cannon")) 'T' else 'M' }, { 3 })
        val width = 360f
        book.chapters.flatMap { it.sections }.flatMap { it.blocks }.forEach { b ->
            art.drawings(b, width).forEach { d ->
                d.ops.filterIsInstance<com.kaiharimoto.mastertool.core.ai.report.book.Drawing.Card>().forEach { c ->
                    assertTrue(c.x >= -0.5f && c.x + c.w <= width + 0.5f, "${b::class.simpleName}: ${c.name} at ${c.x}+${c.w}")
                }
                assertTrue(d.height > 0f, "${b::class.simpleName} has a height")
            }
        }
        // A line's frames: one a step, the last its end board.
        val line = book.chapters.flatMap { it.sections }.flatMap { it.blocks }.filterIsInstance<Block.Line>().first()
        val frames = art.frames(line.line, 170f)
        assertEquals(line.line.steps.size, frames.size)
        assertTrue(line.line.endBoard.all { n -> frames.last().drawing.cards().any { it.name == n } })
    }
}
