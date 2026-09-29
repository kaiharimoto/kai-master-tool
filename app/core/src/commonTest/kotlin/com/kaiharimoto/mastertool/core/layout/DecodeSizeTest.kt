package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecodeSizeTest {

    @Test
    fun aCardFirstDrawnSmallAsksAgainWhenItGrows() {
        // The window opens at its default size, then is maximised: the card grows.
        val first = DecodeSize.width(drawn = 90, previous = 0)
        val second = DecodeSize.width(drawn = 210, previous = first)
        assertEquals(128, first)
        assertEquals(256, second)
        assertTrue(second >= 210, "the decode covers the card as drawn")
    }

    @Test
    fun shrinkingKeepsTheSharperDecode() {
        assertEquals(320, DecodeSize.width(drawn = 150, previous = 320))
        assertEquals(320, DecodeSize.width(drawn = 320, previous = 320))
    }

    @Test
    fun smallStepsDoNotAskEveryPixel() {
        var decode = DecodeSize.width(200, 0)
        var asks = 1
        for (px in 201..256) {
            val next = DecodeSize.width(px, decode)
            if (next != decode) asks++
            decode = next
        }
        assertEquals(1, asks, "200 to 256 is one step")
        assertEquals(256, decode)
        assertEquals(320, DecodeSize.width(257, decode))
    }

    @Test
    fun neverPastTheSource() {
        assertEquals(268, DecodeSize.width(drawn = 300, previous = 0, cap = 268))
        assertEquals(268, DecodeSize.width(drawn = 900, previous = 268, cap = 268))
        assertEquals(813, DecodeSize.width(drawn = 1000, previous = 768, cap = 813))
    }

    @Test
    fun nothingMeasuredYetAsksForNothing() {
        assertEquals(0, DecodeSize.width(drawn = 0, previous = 0))
    }

    @Test
    fun heightFollowsTheCardsRatio() {
        assertEquals(391, DecodeSize.height(268))
        assertEquals(1186, DecodeSize.height(813))
    }
}
