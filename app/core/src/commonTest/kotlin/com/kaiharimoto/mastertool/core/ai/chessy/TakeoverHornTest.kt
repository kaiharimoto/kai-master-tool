package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TakeoverHornTest {
    @Test
    fun kaisHornIsKept() {
        // kai's tuning, 2026-10-06: change these only on kai's word
        val h = TakeoverHorn
        assertEquals(listOf(.07f, 55f, .8f, .055f, .88f, .75f, .32f, .82f, .035f, .25f), listOf(h.VOLUME, h.PITCH_HZ, h.SCOOP, h.SCOOP_S, h.SAG, h.BLAST_S, h.GAP_S, h.CHAOS, h.ATTACK_S, h.RELEASE_S))
        assertEquals(listOf(4f, 24f, .65f, 0f, .18f, 8300f), listOf(h.DRIVE, h.SPREAD_CENTS, h.PULSE, h.SUB, h.AIR, h.BRIGHT_HZ))
        assertEquals(listOf(160f, 15f, 1180f, 1.5f, 2500f, 7f), listOf(h.LOW_HZ, h.LOW_DB, h.MID_HZ, h.MID_DB, h.HIGH_HZ, h.HIGH_DB))
        assertEquals(listOf(23f, .14f, .46f), listOf(h.FLUTTER_HZ, h.FLUTTER_DEPTH, h.ECHO))
    }

    @Test
    fun theBlastsAreSteadyThenQuickerAndStopForTheSnap() {
        val b = TakeoverHorn.blasts()
        assertEquals(2f, b.first().first)
        assertTrue(b.all { it.first < 8.8f })
        val calm = b.filter { it.first < 6f }.zipWithNext { a, c -> c.first - a.first }
        val chaos = b.filter { it.first >= 6f }.zipWithNext { a, c -> c.first - a.first }
        assertTrue(calm.all { kotlin.math.abs(it - 1.07f) < .01f }, "$calm")
        assertTrue(chaos.all { it < 1.07f }, "$chaos")
    }
}
