package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.eval.DecklistPicture.Look
import com.kaiharimoto.mastertool.core.ai.eval.DecklistPictures
import com.kaiharimoto.neue.ai.EvalPictures
import com.kaiharimoto.neue.platform.encodePng
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The decklist pictures (Phase I, I.6) are drawn, legibly sized, the same every time, and inside what a model is sent
 * without shrinking. Each is written to `build/eval-pictures/` to be looked at.
 */
class EvalPicturesTest {
    @Test
    fun everyPictureIsDrawnTheSameEachTimeAndFitsAModelsEye() {
        val out = File("build/eval-pictures").apply { mkdirs() }
        DecklistPictures.all.forEachIndexed { i, p ->
            val bitmap = EvalPictures.draw(p)
            val long = maxOf(bitmap.width, bitmap.height)
            assertTrue(long <= EvalPictures.LONG_SIDE, "${p.title}: ${bitmap.width}×${bitmap.height}, larger than a model is sent")
            assertTrue(minOf(bitmap.width, bitmap.height) >= 300, "${p.title}: ${bitmap.width}×${bitmap.height}")
            // Words were drawn: a share of the pixels differ from the paper (a picture with no fonts would be blank).
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            val paper = pixels[0]
            val inked = pixels.count { it != paper }.toDouble() / pixels.size
            assertTrue(inked > 0.02, "${p.title} (${p.look}): only ${"%.3f".format(inked)} of it drawn on")
            val png = encodePng(bitmap)!!
            assertContentEquals(png, encodePng(EvalPictures.draw(p)), "${p.title}: drawn the same twice")
            File(out, "%02d-%s-%s.png".format(i + 1, p.look.name.lowercase(), p.title.replace(Regex("[^A-Za-z]+"), "-"))).writeBytes(png)
        }
        // The part sent: a PNG, its bytes inside, its size said.
        val part = EvalPictures.image(DecklistPictures.all.first { it.look == Look.SHEET })
        val bytes = Base64.getDecoder().decode(part.data!!)
        assertContentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), bytes.copyOf(4))
        assertTrue(part.width > 0 && part.height > 0 && part.mime == "image/png")
    }
}
