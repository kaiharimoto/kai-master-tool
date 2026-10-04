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
 *
 * Kept as alpha alone (1.0.92): both are drawn only through their alpha (the
 * letters with `DstIn`, the outline tinted `SrcIn`), so a quarter of the memory
 * draws the same. At most [CAPACITY] cards and [BUDGET] bytes, and a name bar
 * with no letters to find is remembered too, so it is not read again.
 */
object NameMasks {
    private const val CAPACITY = 600
    private const val BUDGET = 24L * 1024 * 1024

    private val cache = SizedLru<NameMask?>(CAPACITY, BUDGET) { it?.bytes ?: 0L }

    /** The masks kept for [key]; null when there are none, or when none have been read. */
    fun cached(key: String): NameMask? = cache[key]

    /** The masks for [image], a whole card render as Coil decoded it, or null when its name bar has no letters to find. */
    fun read(key: String, image: coil3.Image, frameType: String): NameMask? {
        if (cache.has(key)) return cache[key]
        val found = find(image.width, image.height, frameType) { x, y, w, h -> image.argb(x, y, w, h) }
        // A picture that could not be read is tried again next time; one with no letters is not.
        if (found !== UNREADABLE) cache.put(key, found as NameMask?)
        return found as? NameMask
    }

    /**
     * The masks for a render [width] × [height], whose pixels [argb] reads a
     * rectangle of (opaque ARGB, row by row). Each platform reads its own
     * bitmaps; the finding of the letters is the same arithmetic on both.
     */
    fun compute(width: Int, height: Int, frameType: String, argb: (x: Int, y: Int, w: Int, h: Int) -> IntArray?): NameMask? =
        find(width, height, frameType, argb) as? NameMask

    /** The masks; null when there are no letters to find; [UNREADABLE] when the pixels could not be read. */
    private fun find(width: Int, height: Int, frameType: String, argb: (x: Int, y: Int, w: Int, h: Int) -> IntArray?): Any? {
        if (frameType.equals("skill", ignoreCase = true)) return null
        val x0 = (NameInk.LEFT * width).roundToInt()
        val x1 = (NameInk.RIGHT * width).roundToInt()
        val y0 = (NameInk.TOP * height).roundToInt()
        val y1 = (NameInk.BOTTOM * height).roundToInt()
        val w = x1 - x0
        val h = y1 - y0
        if (w < 8 || h < 3) return null
        val pixels = argb(x0, y0, w, h) ?: return UNREADABLE
        val mask = NameInk.mask(pixels, w, h, NameInk.lightText(frameType))
        if (mask.all { it == 0f }) return null
        // The outline: about two pixels of an 813-wide render, and never less than one.
        val r = max(1, (width / 400f).roundToInt())
        val grown = NameInk.erode(FloatArray(mask.size) { 1f - mask[it] }, w, h, r).let { e -> FloatArray(e.size) { 1f - e[it] } }
        return NameMask(alphaBitmap(mask, w, h), alphaBitmap(grown, w, h))
    }

    private val UNREADABLE = Any()
}

/** The bytes a mask holds: two alpha-only bitmaps, a byte a pixel. */
private val NameMask.bytes: Long
    get() = letters.width.toLong() * letters.height + outline.width.toLong() * outline.height

/**
 * A least-recently-used map of at most [capacity] entries and [budget] bytes, as
 * [sizeOf] weighs them; the eldest go first. Thread-safe.
 */
internal class SizedLru<V>(private val capacity: Int, private val budget: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<String, V>(16, 0.75f, true)
    private var bytes = 0L

    /** The total weight held now. */
    val weight: Long get() = synchronized(map) { bytes }

    val size: Int get() = synchronized(map) { map.size }

    fun has(key: String): Boolean = synchronized(map) { map.containsKey(key) }

    operator fun get(key: String): V? = synchronized(map) { map[key] }

    fun put(key: String, value: V) = synchronized(map) {
        map.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        val eldest = map.entries.iterator()
        while ((map.size > capacity || bytes > budget) && eldest.hasNext()) {
            val e = eldest.next()
            if (e.key == key && map.size == 1) break
            bytes -= sizeOf(e.value)
            eldest.remove()
        }
    }
}

/** The opaque ARGB pixels of the rectangle [x], [y], [w] × [h] of a Coil image, or null when it cannot be read. */
internal expect fun coil3.Image.argb(x: Int, y: Int, w: Int, h: Int): IntArray?

/** Each of [mask] (0..1) as the alpha of an alpha-only bitmap, [w] × [h]: how a mask is drawn through a blend. */
internal expect fun alphaBitmap(mask: FloatArray, w: Int, h: Int): ImageBitmap
