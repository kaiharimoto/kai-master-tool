package com.kaiharimoto.mastertool.core.pdf

import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfDocumentTest {

    private val inter = TrueType(File("../neue/src/commonMain/composeResources/font/inter_regular.ttf").readBytes())
    private val mono = TrueType(File("../neue/src/commonMain/composeResources/font/jetbrainsmono_regular.ttf").readBytes())

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
}
