package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.record.Yuv420
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A rendered frame's pixels as Android's encoder takes them (I.9): BT.709 limited range, 4:2:0. */
class Yuv420Test {
    private fun planes(argb: IntArray, w: Int, h: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val y = ByteArray(w * h)
        val u = ByteArray(Yuv420.chromaWidth(w) * Yuv420.chromaHeight(h))
        val v = ByteArray(u.size)
        Yuv420.convert(argb, w, h, y, u, v)
        return Triple(y, u, v)
    }

    private fun Byte.unsigned() = toInt() and 0xFF

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun blackWhiteAndGreyAreTheLimitedRangesEnds() {
        val (yb, ub, vb) = planes(IntArray(4) { rgb(0, 0, 0) }, 2, 2)
        assertTrue(yb.all { it.unsigned() == 16 }, "black is 16")
        assertEquals(128, ub[0].unsigned())
        assertEquals(128, vb[0].unsigned())
        val (yw, uw, vw) = planes(IntArray(4) { rgb(255, 255, 255) }, 2, 2)
        assertTrue(yw.all { it.unsigned() == 235 }, "white is 235")
        assertEquals(128, uw[0].unsigned())
        assertEquals(128, vw[0].unsigned())
        val (yg, ug, vg) = planes(IntArray(4) { rgb(128, 128, 128) }, 2, 2)
        assertEquals(16 + (219 * 128 / 255.0).roundToInt(), yg[0].unsigned())
        assertEquals(128, ug[0].unsigned())
        assertEquals(128, vg[0].unsigned())
    }

    @Test
    fun theFullColoursAreBt709s() {
        // BT.709's own figures for 100 % red, green and blue, limited range: (Y, Cb, Cr).
        val expected = mapOf(
            rgb(255, 0, 0) to Triple(63, 102, 240),
            rgb(0, 255, 0) to Triple(173, 42, 26),
            rgb(0, 0, 255) to Triple(32, 240, 118),
        )
        for ((c, want) in expected) {
            val (y, u, v) = planes(IntArray(4) { c }, 2, 2)
            assertTrue(abs(y[0].unsigned() - want.first) <= 1, "Y of ${c.toString(16)}: ${y[0].unsigned()}")
            assertTrue(abs(u[0].unsigned() - want.second) <= 1, "Cb of ${c.toString(16)}: ${u[0].unsigned()}")
            assertTrue(abs(v[0].unsigned() - want.third) <= 1, "Cr of ${c.toString(16)}: ${v[0].unsigned()}")
        }
    }

    @Test
    fun chromaIsTheMeanOfEachTwoByTwoAndLumaIsEachPixels() {
        // Left half black, right half white, 4 × 2: each block is one colour, so U and V stay neutral and Y follows.
        val w = 4
        val h = 2
        val px = IntArray(w * h) { i -> if (i % w < 2) rgb(0, 0, 0) else rgb(255, 255, 255) }
        val (y, u, v) = planes(px, w, h)
        assertEquals(listOf(16, 16, 235, 235, 16, 16, 235, 235), y.map { it.unsigned() })
        assertEquals(listOf(128, 128), u.map { it.unsigned() })
        assertEquals(listOf(128, 128), v.map { it.unsigned() })
        // A block of red and blue averages to purple's chroma.
        val mixed = intArrayOf(rgb(255, 0, 0), rgb(0, 0, 255), rgb(255, 0, 0), rgb(0, 0, 255))
        val (_, um, vm) = planes(mixed, 2, 2)
        val (_, up, vp) = planes(IntArray(4) { rgb(128, 0, 128) }, 2, 2)
        assertTrue(abs(um[0].unsigned() - up[0].unsigned()) <= 1)
        assertTrue(abs(vm[0].unsigned() - vp[0].unsigned()) <= 1)
    }

    @Test
    fun anOddEdgeRepeatsItself() {
        val (y, u, v) = planes(IntArray(9) { rgb(255, 255, 255) }, 3, 3)
        assertTrue(y.all { it.unsigned() == 235 })
        assertEquals(4, u.size)
        assertTrue(u.all { it.unsigned() == 128 } && v.all { it.unsigned() == 128 })
    }
}
