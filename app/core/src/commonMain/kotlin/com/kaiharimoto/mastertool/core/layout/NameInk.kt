package com.kaiharimoto.mastertool.core.layout

import kotlin.math.max
import kotlin.math.min

/**
 * The printed name on a card render, found in its pixels — so it can be
 * stamped in foil like the border.
 *
 * **An exploration**, at kai's request: nothing in the app draws with it yet.
 *
 * Every YGOPRODeck render (813 × 1185) sets the name in one bar across the top,
 * left of the attribute icon, and measured over eighteen cards of every frame
 * the letters sit on rows 69–116 and run from x ≈ 60 to at most 670; the icon
 * begins at 680. The bar is [LEFT]..[RIGHT] × [TOP]..[BOTTOM], inside the
 * bar's own bevel so its outline is never mistaken for a letter.
 *
 * Which way round the ink is follows the frame, not a guess from the pixels:
 * spells, traps, Xyz and Link print their names in white, everything else in
 * black. (A guess from the pixels fails on exactly the frames where it
 * matters — a white Synchro frame, a black Xyz one.)
 */
object NameInk {
    const val LEFT = 56f / 813f
    const val RIGHT = 676f / 813f
    const val TOP = 62f / 1185f
    const val BOTTOM = 124f / 1185f

    /** Whether [frameType] prints its name in white. */
    fun lightText(frameType: String): Boolean {
        val type = frameType.lowercase()
        return type.contains("xyz") || type.contains("link") || type.contains("spell") || type.contains("trap") || type == "skill"
    }

    /**
     * How much of each pixel of a crop of the name bar is letter, 0..1.
     *
     * [pixels] is ARGB, row-major, [width] × [height]. The background is the
     * crop's median luminance (letters are well under half the bar), the ink is
     * its 3rd or 97th percentile, and a pixel is letter by how far it has gone
     * from one toward the other — so the letters keep their antialiased edges
     * rather than turning into a stencil.
     */
    fun mask(pixels: IntArray, width: Int, height: Int, light: Boolean): FloatArray {
        require(pixels.size == width * height) { "pixels ${pixels.size} for $width × $height" }
        val lum = IntArray(pixels.size) { luminance(pixels[it]) }
        val histogram = IntArray(256)
        lum.forEach { histogram[it]++ }
        val background = percentile(histogram, lum.size, 0.5f)
        val ink = percentile(histogram, lum.size, if (light) 0.97f else 0.03f)
        val span = if (light) ink - background else background - ink
        if (span < MIN_CONTRAST) return FloatArray(pixels.size)
        return FloatArray(pixels.size) { i ->
            val t = (if (light) lum[i] - background else background - lum[i]).toFloat() / span
            smooth(((t - LOW) / (HIGH - LOW)).coerceIn(0f, 1f))
        }
    }

    /**
     * The mask pulled in by [radius] pixels (a minimum filter), so foil can
     * fill a letter and leave its printed edge standing round it.
     */
    fun erode(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask.copyOf()
        // Separable: rows, then columns.
        val rows = FloatArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var m = 1f
            for (dx in -radius..radius) m = min(m, mask[y * width + (x + dx).coerceIn(0, width - 1)])
            rows[y * width + x] = m
        }
        val out = FloatArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var m = 1f
            for (dy in -radius..radius) m = min(m, rows[(y + dy).coerceIn(0, height - 1) * width + x])
            out[y * width + x] = m
        }
        return out
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return ((2126 * r + 7152 * g + 722 * b) / 10000).coerceIn(0, 255)
    }

    private fun percentile(histogram: IntArray, total: Int, p: Float): Int {
        val target = max(1, (total * p).toInt())
        var seen = 0
        for (v in 0..255) {
            seen += histogram[v]
            if (seen >= target) return v
        }
        return 255
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /** Below this, the bar has no letters to speak of (or the crop missed them). */
    private const val MIN_CONTRAST = 40
    private const val LOW = 0.35f
    private const val HIGH = 0.8f
}
