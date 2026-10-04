package com.kaiharimoto.mastertool.core.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameStatsTest {
    private val frame = 16_666_667L

    @Test
    fun steadySixtyReadsAsSixty() {
        val s = FrameStats()
        var t = 1_000L
        repeat(121) { s.feed(t); t += frame }
        val r = s.reading()!!
        assertEquals(60f, r.fps, 0.01f)
        assertEquals(16.67f, r.medianMs, 0.01f)
        assertEquals(16.67f, r.worstMs, 0.01f)
        assertEquals(120, r.frames)
    }

    @Test
    fun aSlowFrameShowsInTheTailNotTheMiddle() {
        val s = FrameStats()
        var t = 1_000L
        s.feed(t)
        repeat(99) { t += frame; s.feed(t) }
        repeat(10) { t += frame * 3; s.feed(t) }
        val r = s.reading()!!
        assertEquals(16.67f, r.medianMs, 0.01f)
        assertEquals(50f, r.p95Ms, 0.01f)
        assertEquals(50f, r.worstMs, 0.01f)
    }

    @Test
    fun aLongGapStartsAgainAndTheWindowSlides() {
        val s = FrameStats(size = 10)
        assertNull(s.reading())
        var t = 1_000L
        repeat(5) { s.feed(t); t += frame }
        t += 5_000_000_000L
        s.feed(t)
        assertNull(s.reading(), "a hidden window is not a frame")
        repeat(30) { t += frame * 2; s.feed(t) }
        val r = s.reading()!!
        assertEquals(10, r.frames)
        assertEquals(33.33f, r.medianMs, 0.01f)
    }
}
