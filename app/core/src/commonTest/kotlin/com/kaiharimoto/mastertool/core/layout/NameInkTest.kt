package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameInkTest {

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** A 40 × 10 bar of [bg], with a "letter" of [ink] in columns 10..17. */
    private fun bar(bg: Int, ink: Int): IntArray = IntArray(400) { i -> if (i % 40 in 10..17) grey(ink) else grey(bg) }

    @Test
    fun darkLettersOnAColouredBar() {
        val mask = NameInk.mask(bar(bg = 150, ink = 15), 40, 10, light = false)
        assertEquals(1f, mask[5 * 40 + 12], 1e-4f)
        assertEquals(0f, mask[5 * 40 + 30], 1e-4f)
    }

    @Test
    fun whiteLettersOnASpell() {
        val mask = NameInk.mask(bar(bg = 110, ink = 245), 40, 10, light = true)
        assertEquals(1f, mask[5 * 40 + 12], 1e-4f)
        assertEquals(0f, mask[5 * 40 + 30], 1e-4f)
        // Read the wrong way round, white letters are not letters at all.
        assertTrue(NameInk.mask(bar(bg = 110, ink = 245), 40, 10, light = false).all { it == 0f })
    }

    @Test
    fun antialiasedEdgesStayPartial() {
        val pixels = bar(bg = 160, ink = 10).also { it[5 * 40 + 18] = grey(85) }
        val edge = NameInk.mask(pixels, 40, 10, light = false)[5 * 40 + 18]
        assertTrue(edge > 0f && edge < 1f, "edge $edge")
    }

    @Test
    fun aBarWithNoLettersIsEmpty() {
        assertTrue(NameInk.mask(IntArray(400) { grey(120) }, 40, 10, light = false).all { it == 0f })
    }

    @Test
    fun theFrameDecidesTheInk() {
        listOf("spell", "trap", "xyz", "link", "xyz_pendulum", "skill").forEach { assertTrue(NameInk.lightText(it), it) }
        listOf("normal", "effect", "fusion", "ritual", "synchro", "effect_pendulum", "token").forEach { assertFalse(NameInk.lightText(it), it) }
    }

    @Test
    fun theBarSitsLeftOfTheAttributeAndUnderTheBevel() {
        assertTrue(NameInk.RIGHT * 813f < 680f, "clear of the attribute icon")
        assertTrue(NameInk.TOP * 1185f < 69f && NameInk.BOTTOM * 1185f > 116f, "round every measured letter")
        assertTrue(NameInk.BOTTOM < ArtFrame.STANDARD.top, "above the picture")
    }

    @Test
    fun erosionPullsTheLetterIn() {
        val mask = NameInk.mask(bar(bg = 150, ink = 15), 40, 10, light = false)
        val inner = NameInk.erode(mask, 40, 10, 1)
        assertEquals(0f, inner[5 * 40 + 10], 1e-4f)
        assertEquals(1f, inner[5 * 40 + 13], 1e-4f)
    }
}
