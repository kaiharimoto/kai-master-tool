package com.kaiharimoto.mastertool.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** `Hsb` is `java.awt.Color`'s arithmetic without AWT: swept against the original, bit for bit. */
class HsbTest {

    @Test
    fun rgbToHsbMatchesAwt() {
        val awt = FloatArray(3)
        for (r in 0..255 step 5) for (g in 0..255 step 7) for (b in 0..255 step 11) {
            java.awt.Color.RGBtoHSB(r, g, b, awt)
            val ours = Hsb.fromRgb(r, g, b)
            for (i in 0..2) assertEquals(awt[i].toRawBits(), ours[i].toRawBits(), "rgb($r,$g,$b)[$i]")
        }
    }

    @Test
    fun hsbToRgbMatchesAwt() {
        var h = -1.3f
        while (h < 2.4f) {
            var s = 0f
            while (s <= 1f) {
                var v = 0f
                while (v <= 1f) {
                    assertEquals(java.awt.Color.HSBtoRGB(h, s, v), Hsb.toRgb(h, s, v), "hsb($h,$s,$v)")
                    v += 0.0625f
                }
                s += 0.125f
            }
            h += 0.0137f
        }
    }
}
