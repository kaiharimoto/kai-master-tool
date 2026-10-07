package com.kaiharimoto.mastertool.core.pdf

import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfDocumentTest {

    private val inter = TrueType(File("../table/src/commonMain/composeResources/font/inter_regular.ttf").readBytes())
    private val mono = TrueType(File("../table/src/commonMain/composeResources/font/jetbrainsmono_regular.ttf").readBytes())

    @Test
    fun theFontIsReadAsTheAppDrawsIt() {
        assertTrue(inter.unitsPerEm > 0)
        assertTrue(inter.glyph('A'.code) > 0, "A has a glyph")
        assertTrue(inter.glyph('é'.code) > 0, "é has a glyph")
        assertTrue(inter.glyph('“'.code) > 0, "a curly quote has a glyph")
        assertEquals(0, inter.glyph(0x4E00), "a Chinese character is not in Inter")
        // Proportional: an i is narrower than an M.
        assertTrue(inter.width("i", 10f) < inter.width("M", 10f))
        // Monospaced: every letter the same.
        assertEquals(mono.width("i", 10f), mono.width("M", 10f))
        assertTrue(mono.fixedPitch)
        assertEquals(inter.width("ab", 12f), inter.width("a", 12f) + inter.width("b", 12f), 0.001f)
    }

    @Test
    fun aDocumentIsWellFormed() {
        for (zlib in listOf(null, JvmZlib)) {
            val doc = PdfDocument(zlib, title = "Siding guide · Snake-Eye")
            val font = doc.font(inter)
            val image = PdfImage(2, 2, byteArrayOf(0, 0, 0, -1, -1, -1, -1, 0, 0, 0, -1, 0))
            repeat(2) { i ->
                val page = doc.page()
                page.text(font, 12f, 40f, 60f, "vs Yubel — page ${i + 1}")
                page.fillRect(40f, 80f, 100f, 2f, 0f)
                page.strokeRect(40f, 100f, 100f, 50f, 0.5f, 1f, dash = 3f)
                page.image(image, 40f, 160f, 40f, 58f)
            }
            val bytes = doc.write()
            val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue(text.startsWith("%PDF-1.7"))
            assertTrue(text.trimEnd().endsWith("%%EOF"))
            // Every xref entry points at the object it names.
            val xref = text.substringAfterLast("startxref\n").trim().lines().first().toInt()
            val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
            entries.forEachIndexed { i, line ->
                val offset = line.substring(0, 10).toInt()
                assertTrue(text.startsWith("${i + 1} 0 obj", offset), "object ${i + 1} at $offset")
            }
            // One picture, shared by both pages.
            assertEquals(1, Regex("/Subtype /Image").findAll(text).count())
            assertEquals(2, Regex("/Type /Page /Parent").findAll(text).count())
        }
    }

    @Test
    fun writesAGuideToLookAt() {
        // Not an assertion: a file to open by eye (build/pdf-sample.pdf), made every run.
        val doc = PdfDocument(JvmZlib, title = "Sample")
        val font = doc.font(inter)
        val page = doc.page()
        page.text(font, 30f, 44f, 70f, "Snake-Eye Fiendsmith")
        page.text(font, 12f, 44f, 100f, "They go second into our board with Dimension Shifter — “keep Called by the Grave”.")
        File("build/pdf-sample.pdf").writeBytes(doc.write())
    }

    @Test
    fun shapesClipsTrackingAndJpegsAreWritten() {
        val doc = PdfDocument(null)
        val font = doc.font(inter)
        val page = doc.page(400f, 866f)
        page.fill(page.path { circle(50f, 50f, 10f) }, 0f)
        page.stroke(page.path { moveTo(10f, 10f); curveTo(10f, 40f, 60f, 40f, 60f, 70f) }, 0.5f, 0.8f, cap = LineCap.ROUND, round = true)
        page.fillStroke(page.path { polygon(listOf(0f to 0f, 10f to 0f, 5f to 8f)) }, 0f, 0f)
        page.clip(0f, 0f, 20f, 20f) { page.fillRect(0f, 0f, 40f, 40f, 0f) }
        page.text(font, 7f, 10f, 100f, "MICRO CAPS", 0.5f, tracking = 0.56f)
        // A one-pixel JPEG, as a JPEG file begins and ends: passed through, never deflated.
        val jpeg = byteArrayOf(-1, -40, -1, -39)
        page.image(PdfImage.jpeg(1, 1, jpeg), 0f, 0f, 10f, 10f)
        val text = String(doc.write(), Charsets.ISO_8859_1)
        assertTrue(" c " in text, "a curve")
        assertTrue("re W n" in text, "a clip")
        assertTrue("0.56 Tc" in text, "letter-spacing")
        assertTrue("1 J 1 j" in text, "a round cap and join")
        assertTrue("/DCTDecode" in text, "the JPEG kept as it is")
        assertTrue(String(jpeg, Charsets.ISO_8859_1) in text)
        // Tracking is measured the way it is set: a tracked word is wider by the tracking a character.
        val f = doc.font(inter)
        assertEquals(f.width("ABC", 10f) + 3f, f.width("ABC", 10f, 1f), 0.001f)
    }
}
