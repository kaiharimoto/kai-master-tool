package com.kaiharimoto.neue.cards

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.ui.gpu.BrushMemo
import com.kaiharimoto.mastertool.ui.gpu.brush
import com.kaiharimoto.mastertool.ui.gpu.compileStageShader
import com.kaiharimoto.neue.art.CustomArt
import com.kaiharimoto.neue.platform.PickedFile
import com.kaiharimoto.neue.zen.BlurPaints
import com.kaiharimoto.neue.zen.blurRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The 1.0.92 caches in a card's drawing hold to the pixels drawn without them:
 * the foil's kept path and brushes, the name masks kept as alpha alone, and the
 * zen blur's kept paints — each drawn both ways and compared pixel for pixel.
 */
class CardDrawingCacheTest {

    private fun picture(w: Int, h: Int, draw: DrawScope.() -> Unit): IntArray {
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            // A card's print under the foil: something other than flat, so a blend shows.
            for (y in 0 until h step 7) drawRect(Color(0.2f + 0.6f * (y % 3) / 2f, 0.35f, 0.55f), Offset(0f, y.toFloat()), Size(w.toFloat(), 7f))
            draw()
        }
        val map = bitmap.toPixelMap()
        return IntArray(w * h) { map[it % w, it / w].toArgb() }
    }

    @Test
    fun theFoilDrawnThroughItsCacheIsTheFoilDrawnWithout() {
        assertTrue(Holo.available, "the shader compiles on the desk")
        val frame = ArtFrame.of("effect")
        val cache = HoloCache()
        for ((w, h) in listOf(118 to 172, 200 to 292, 60 to 87)) {
            for (feel in listOf(Offset.Zero, Offset(0.3f, -0.6f), Offset.Zero, Offset(-1f, 1f))) {
                val plain = picture(w, h) { drawFoil(Foils.HOLO, feel, frame) }
                // Twice through the cache: once made, once kept.
                assertContentEquals(plain, picture(w, h) { drawFoil(Foils.HOLO, feel, frame, cache) }, "made, $w×$h at $feel")
                assertContentEquals(plain, picture(w, h) { drawFoil(Foils.HOLO, feel, frame, cache) }, "kept, $w×$h at $feel")
            }
        }
        // Another card's frame through the same cache: the path follows the frame.
        val link = ArtFrame.of("link")
        assertContentEquals(picture(118, 172) { drawFoil(Foils.HOLO, Offset.Zero, link) }, picture(118, 172) { drawFoil(Foils.HOLO, Offset.Zero, link, cache) })
    }

    @Test
    fun aBrushIsKeptOnlyWhileItsUniformsAreTheSame() {
        val shader = compileStageShader("uniform float u; uniform float2 v; half4 main(float2 p) { return half4(half(u), half(v.x), half(v.y), 1.0); }")!!
        val memo = BrushMemo()
        val first = shader.brush(memo) { float("u", 0.5f); float2("v", 0.1f, 0.2f) }
        assertSame(first, shader.brush(memo) { float("u", 0.5f); float2("v", 0.1f, 0.2f) })
        val second = shader.brush(memo) { float("u", 0.5f); float2("v", 0.1f, 0.3f) }
        assertNotSame(first, second)
        assertSame(second, shader.brush(memo) { float("u", 0.5f); float2("v", 0.1f, 0.3f) })
        // -0 and 0 are different floats to a shader's sign(): kept apart.
        val zero = shader.brush(memo) { float("u", 0f); float2("v", 0f, 0f) }
        assertNotSame(zero, shader.brush(memo) { float("u", -0f); float2("v", 0f, 0f) })
        // The memo answers on its own too: the record, then what was made from it.
        val bare = BrushMemo()
        assertNull(bare.reuse { float("u", 1f) })
    }

    /** A name bar's letters, and their outline: soft edges and solid strokes, as `NameInk` finds them. */
    private fun mask(w: Int, h: Int, grow: Int): FloatArray = FloatArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val stroke = (sin(x * 0.7f) * 0.5f + 0.5f) * (if (y in (h / 4 - grow)..(3 * h / 4 + grow)) 1f else 0f)
        if (x % 11 < 2 + grow) 1f else stroke * ((x * 7 + y * 3) % 5) / 4f
    }

    @Test
    fun nameMasksKeptAsAlphaAloneDrawTheSamePixels() {
        val w = 63
        val h = 9
        val alpha = NameMask(alphaBitmap(mask(w, h, 0), w, h), alphaBitmap(mask(w, h, 1), w, h))
        val white = NameMask(whiteBitmap(mask(w, h, 0), w, h), whiteBitmap(mask(w, h, 1), w, h))
        assertEquals(org.jetbrains.skia.ColorType.ALPHA_8, alpha.letters.asSkiaBitmap().colorType, "config ${alpha.letters.config}")
        // Drawn smaller, the same and larger than the masks were read at, so the filtering is compared too.
        for ((cw, ch) in listOf(70 to 102, 81 to 118, 160 to 233, 300 to 437)) {
            for (outlined in listOf(false, true)) {
                for (light in listOf(Offset.Zero, Offset(0.5f, -0.25f))) {
                    assertContentEquals(
                        picture(cw, ch) { drawFoilName(white, light, outlined) },
                        picture(cw, ch) { drawFoilName(alpha, light, outlined) },
                        "$cw×$ch outlined=$outlined at $light",
                    )
                }
            }
        }
    }

    @Test
    fun theMaskCacheIsBoundedByCountAndBytesAndRemembersNoLetters() {
        val lru = SizedLru<String?>(capacity = 3, budget = 10) { it?.length?.toLong() ?: 0L }
        lru.put("a", "1234")
        lru.put("b", "1234")
        assertEquals(8, lru.weight)
        lru.put("c", "123")
        // Eleven bytes: the eldest goes.
        assertEquals(false, lru.has("a"))
        assertEquals(7, lru.weight)
        lru["b"]
        lru.put("d", null)
        lru.put("e", null)
        // Four entries: the least recently used goes, and that is c, since b was read.
        assertEquals(listOf(false, true, false, true, true), listOf("a", "b", "c", "d", "e").map(lru::has))
        assertNull(lru["d"])
        assertEquals(3, lru.size)
        // One too large for the budget is still kept, alone.
        lru.put("f", "12345678901")
        assertEquals(listOf("f"), listOf("b", "d", "e", "f").filter(lru::has))
        // Replacing a value re-weighs it.
        lru.put("f", "12")
        assertEquals(2, lru.weight)
    }

    @Test
    fun zensKeptBlurPaintsDrawWhatAFreshOneDrew() {
        fun fresh(scope: DrawScope, l: Float, t: Float, r: Float, b: Float, color: Color, sigma: Float) {
            val paint = Paint().apply {
                isAntiAlias = true
                this.color = color.toArgb()
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
            }
            scope.drawIntoCanvas { it.nativeCanvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(l, t, r, b), paint) }
        }
        val rects = listOf(
            floatArrayOf(10f, 12f, 60f, 80f, 4.5f),
            floatArrayOf(30f, 5f, 90f, 40f, 4.5f),
            floatArrayOf(5f, 50f, 70f, 95f, 2.25f),
        )
        val colours = listOf(Color.Black.copy(alpha = 0.3f), Color.White.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.05f))
        val kept = picture(100, 100) { rects.forEachIndexed { i, r -> blurRect(r[0], r[1], r[2], r[3], colours[i], r[4]) } }
        val made = picture(100, 100) { rects.forEachIndexed { i, r -> fresh(this, r[0], r[1], r[2], r[3], colours[i], r[4]) } }
        assertContentEquals(made, kept)
        // A sigma per frame never grows the kept set past its bound.
        repeat(40) { BlurPaints.paint(1f + it * 0.37f) }
        assertTrue(BlurPaints.size <= 16)
    }

    @Test
    fun customArtListsItsFolderOnceAndSeesWhatItKeeps() {
        val dir = File.createTempFile("custom-art", "").apply { delete(); mkdirs() }
        try {
            File(dir, "111").mkdirs()
            File(dir, "111/1.png").writeBytes(byteArrayOf(1))
            val art = CustomArt(dir)
            assertEquals(1, art.files(111).size)
            assertEquals(0, art.files(222).size)
            // Kept through the app: seen at once.
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)
            assertEquals(-1, art.keep(222, PickedFile("a.png", png)))
            assertEquals(1, art.files(222).size)
            // Written by another device: seen once the folders are read again, as sync does.
            File(dir, "333").mkdirs()
            File(dir, "333/1.png").writeBytes(byteArrayOf(1))
            art.reload()
            assertEquals(1, art.files(333).size)
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** The masks as they were stored before 1.0.92, white at each alpha: what the alpha-only ones are held to. */
private fun whiteBitmap(mask: FloatArray, w: Int, h: Int): ImageBitmap {
    val bytes = ByteArray(w * h * 4)
    mask.forEachIndexed { i, a ->
        val v = (a * 255f).roundToInt().coerceIn(0, 255).toByte()
        bytes[i * 4] = v
        bytes[i * 4 + 1] = v
        bytes[i * 4 + 2] = v
        bytes[i * 4 + 3] = v
    }
    return Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL), bytes, w * 4).toComposeImageBitmap()
}
