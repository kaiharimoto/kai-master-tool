package com.kaiharimoto.neue.cards

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.kaiharimoto.mastertool.core.layout.NameInk
import kotlin.math.max
import kotlin.math.roundToInt

/** A card's printed name as two alpha masks the size of its name bar: the letters, and the letters grown by a hair. */
class NameMask(val letters: ImageBitmap, val outline: ImageBitmap)

/** The name style every card in the window draws with; the studio, with no provider, gets the default. */
val LocalNameStyle = staticCompositionLocalOf { NameStyles.FOIL }

/** Whether Limited and Semi-Limited cards show their 1 or 2 (1.0.73, `NeuePreferences.limitMarks`, off by default). */
val LocalLimitMarks = staticCompositionLocalOf { false }

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

    /** The masks for [image], a whole card render as Coil decoded it, or null when its name bar has no letters to find. */
    fun read(key: String, image: coil3.Image, frameType: String): NameMask? {
        cached(key)?.let { return it }
        val mask = compute(image.width, image.height, frameType) { x, y, w, h -> image.argb(x, y, w, h) } ?: return null
        synchronized(cache) { cache[key] = mask }
        return mask
    }

    /**
     * The masks for a render [width] × [height], whose pixels [argb] reads a
     * rectangle of (opaque ARGB, row by row). Each platform reads its own
     * bitmaps; the finding of the letters is the same arithmetic on both.
     */
    fun compute(width: Int, height: Int, frameType: String, argb: (x: Int, y: Int, w: Int, h: Int) -> IntArray?): NameMask? {
        if (frameType.equals("skill", ignoreCase = true)) return null
        val x0 = (NameInk.LEFT * width).roundToInt()
        val x1 = (NameInk.RIGHT * width).roundToInt()
        val y0 = (NameInk.TOP * height).roundToInt()
        val y1 = (NameInk.BOTTOM * height).roundToInt()
        val w = x1 - x0
        val h = y1 - y0
        if (w < 8 || h < 3) return null
        val pixels = argb(x0, y0, w, h) ?: return null
        val mask = NameInk.mask(pixels, w, h, NameInk.lightText(frameType))
        if (mask.all { it == 0f }) return null
        // The outline: about two pixels of an 813-wide render, and never less than one.
        val r = max(1, (width / 400f).roundToInt())
        val grown = NameInk.erode(FloatArray(mask.size) { 1f - mask[it] }, w, h, r).let { e -> FloatArray(e.size) { 1f - e[it] } }
        return NameMask(alphaBitmap(mask, w, h), alphaBitmap(grown, w, h))
    }
}

/** The opaque ARGB pixels of the rectangle [x], [y], [w] × [h] of a Coil image, or null when it cannot be read. */
internal expect fun coil3.Image.argb(x: Int, y: Int, w: Int, h: Int): IntArray?

/** White at the alpha of each of [mask] (0..1), [w] × [h]: how a mask is drawn through a blend. */
internal expect fun alphaBitmap(mask: FloatArray, w: Int, h: Int): ImageBitmap
