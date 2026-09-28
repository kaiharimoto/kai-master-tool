package com.kaiharimoto.mastertool.core.model

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Hue, saturation and brightness, exactly as `java.awt.Color.RGBtoHSB` and
 * `HSBtoRGB` compute them — the same float arithmetic in the same order, so a
 * colour turned here comes out to the bit what the desktop drew before Neue
 * left AWT behind (1.0.20: Android has no AWT). `HsbTest` sweeps it against
 * `java.awt.Color` itself.
 */
object Hsb {
    /** [r], [g], [b] in 0..255 → hue (0..1, a turn), saturation and brightness (0..1). */
    fun fromRgb(r: Int, g: Int, b: Int): FloatArray {
        val cmax = max(max(r, g), b)
        val cmin = min(min(r, g), b)
        val brightness = cmax / 255f
        val saturation = if (cmax != 0) (cmax - cmin).toFloat() / cmax.toFloat() else 0f
        var hue = 0f
        if (saturation != 0f) {
            val span = (cmax - cmin).toFloat()
            val redc = (cmax - r).toFloat() / span
            val greenc = (cmax - g).toFloat() / span
            val bluec = (cmax - b).toFloat() / span
            hue = when {
                r == cmax -> bluec - greenc
                g == cmax -> 2.0f + redc - bluec
                else -> 4.0f + greenc - redc
            }
            hue /= 6.0f
            if (hue < 0) hue += 1.0f
        }
        return floatArrayOf(hue, saturation, brightness)
    }

    /** Hue (a turn; any real number), saturation, brightness → opaque ARGB. */
    fun toRgb(hue: Float, saturation: Float, brightness: Float): Int {
        var r = 0
        var g = 0
        var b = 0
        if (saturation == 0f) {
            r = (brightness * 255.0f + 0.5f).toInt()
            g = r
            b = r
        } else {
            val h = (hue - floor(hue.toDouble()).toFloat()) * 6.0f
            val f = h - floor(h.toDouble()).toFloat()
            val p = brightness * (1.0f - saturation)
            val q = brightness * (1.0f - saturation * f)
            val t = brightness * (1.0f - saturation * (1.0f - f))
            fun c(v: Float) = (v * 255.0f + 0.5f).toInt()
            when (h.toInt()) {
                0 -> { r = c(brightness); g = c(t); b = c(p) }
                1 -> { r = c(q); g = c(brightness); b = c(p) }
                2 -> { r = c(p); g = c(brightness); b = c(t) }
                3 -> { r = c(p); g = c(q); b = c(brightness) }
                4 -> { r = c(t); g = c(p); b = c(brightness) }
                5 -> { r = c(brightness); g = c(p); b = c(q) }
            }
        }
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
}
