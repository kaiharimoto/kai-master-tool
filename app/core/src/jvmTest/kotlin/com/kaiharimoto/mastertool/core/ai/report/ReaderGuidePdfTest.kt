package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reader's guide (1.0.66 mockups): each layout a real PDF with art, and the data round-trips. */
class ReaderGuidePdfTest {
    private fun font(name: String) = TrueType(File("../neue/src/commonMain/composeResources/font/$name.ttf").readBytes())
    private val fonts = GuideFonts(font("inter_regular"), font("inter_bold"), font("jetbrainsmono_regular"))
    private val sample = ReaderGuideSample.labrynth
    private val pictures = sample.cards().associateWith { name -> PdfImage(4, 6, ByteArray(4 * 6 * 3) { (name.length * 7).toByte() }) }

    private fun pages(bytes: ByteArray) = Regex("/Type\\s*/Page[^s]").findAll(String(bytes, Charsets.ISO_8859_1)).count()

    @Test
    fun everyLayoutIsAPdfWithTheCardsDrawn() {
        ReaderGuidePdf.Style.entries.forEach { style ->
            val bytes = ReaderGuidePdf.render(sample, style, fonts, { pictures[it] }, null, "1 October 2026")
            File("build/report-samples").apply { mkdirs() }.resolve("reader-${style.name.lowercase()}.pdf").writeBytes(bytes)
            val pdf = String(bytes, Charsets.ISO_8859_1)
            assertTrue(pdf.startsWith("%PDF-"), style.name)
            assertTrue(pdf.contains("/Subtype /Image") || pdf.contains("/Subtype/Image"), "$style draws the art")
            assertTrue(pages(bytes) >= 2, "$style: ${pages(bytes)} pages")
        }
    }

    @Test
    fun aGuideWithNoArtAndLittleInItStillRenders() {
        val thin = ReaderGuide("Empty", pitch = "Just a pitch.")
        ReaderGuidePdf.Style.entries.forEach { style ->
            assertTrue(String(ReaderGuidePdf.render(thin, style, fonts, { null }, null), Charsets.ISO_8859_1).startsWith("%PDF-"), style.name)
        }
    }

    @Test
    fun theGuideRoundTripsAndReadsTolerantly() {
        assertEquals(sample, ReaderGuide.read(ReaderGuide.write(sample)))
        assertNull(ReaderGuide.read("not json"))
        assertNotNull(ReaderGuide.read("""{"deckName":"X","pitch":"Y","somethingNew":1}"""))
        assertEquals(sample.cards().size, sample.cards().distinct().size)
        assertTrue("Arianna the Labrynth Servant" == sample.cards().first())
        assertEquals(ReaderGuide.hashOf(" notes "), ReaderGuide.hashOf("notes"))
    }
}
