package com.kaiharimoto.neue.cards

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kaiharimoto.mastertool.core.layout.NameInk
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.max
import kotlin.math.roundToInt

/** A card's printed name as two alpha masks the size of its name bar: the letters, and the letters grown by a hair. */
class NameMask(val letters: ImageBitmap, val outline: ImageBitmap)

/** The name style every card in the window draws with; the studio, with no provider, gets the default. */
val LocalNameStyle = staticCompositionLocalOf { NameStyles.FOIL }

/** How a card's name is drawn: stamped in the foil (kai's pick), foil over an ink outline, or as printed. */
object NameStyles {
    const val FOIL = "foil"
    const val OUTLINE = "outline"
    const val PRINTED = "printed"

    val all = listOf(FOIL, OUTLINE, PRINTED)

    fun label(id: String) = when (id) {
        FOIL -> "Foil"
        OUTLINE -> "Foil, outlined"
        else -> "Printed"
    }
}

/**
 * The name masks, read off the picture a card has already decoded and kept by
 * card and size, so a card scrolled away and back is not read twice. Reading
 * one is a few thousand pixels of one bar; it happens off the UI thread.
 */
object NameMasks {
    private const val CAPACITY = 600
    private val cache = object : LinkedHashMap<String, NameMask>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NameMask>?) = size > CAPACITY
    }

    fun cached(key: String): NameMask? = synchronized(cache) { cache[key] }

    /** The masks for [bitmap], a whole card render, or null when its name bar has no letters to find. */
    fun read(key: String, bitmap: Bitmap, frameType: String): NameMask? {
        cached(key)?.let { return it }
        val mask = compute(bitmap, frameType) ?: return null
        synchronized(cache) { cache[key] = mask }
        return mask
    }

    fun read(image: Image, frameType: String): NameMask? = compute(Bitmap.makeFromImage(image), frameType)

    private fun compute(bitmap: Bitmap, frameType: String): NameMask? {
        if (frameType.equals("skill", ignoreCase = true)) return null
        val x0 = (NameInk.LEFT * bitmap.width).roundToInt()
        val x1 = (NameInk.RIGHT * bitmap.width).roundToInt()
        val y0 = (NameInk.TOP * bitmap.height).roundToInt()
        val y1 = (NameInk.BOTTOM * bitmap.height).roundToInt()
        val w = x1 - x0
        val h = y1 - y0
        if (w < 8 || h < 3) return null
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val bytes = bitmap.readPixels(info, w * 4, x0, y0) ?: return null
        val pixels = IntArray(w * h) { i ->
            val b = bytes[i * 4].toInt() and 0xFF
            val g = bytes[i * 4 + 1].toInt() and 0xFF
            val r = bytes[i * 4 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val mask = NameInk.mask(pixels, w, h, NameInk.lightText(frameType))
        if (mask.all { it == 0f }) return null
        // The outline: about two pixels of an 813-wide render, and never less than one.
        val r = max(1, (bitmap.width / 400f).roundToInt())
        val grown = NameInk.erode(FloatArray(mask.size) { 1f - mask[it] }, w, h, r).let { e -> FloatArray(e.size) { 1f - e[it] } }
        return NameMask(alpha(mask, w, h), alpha(grown, w, h))
    }

    private fun alpha(mask: FloatArray, w: Int, h: Int): ImageBitmap {
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
}
