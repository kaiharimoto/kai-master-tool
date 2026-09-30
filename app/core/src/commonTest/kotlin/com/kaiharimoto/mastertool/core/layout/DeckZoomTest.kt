package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckZoomTest {

    @Test
    fun downShrinksAndUpGrows() {
        val down = DeckZoom.wheel(0.8f, 1f)
        assertTrue(down < 0.8f)
        assertEquals(0.8f * exp(-DeckZoom.PER_NOTCH), down, 1e-5f)
        assertTrue(DeckZoom.wheel(0.6f, -1f) > 0.6f)
    }

    @Test
    fun aNotchIsAFineStep() {
        // 1.0.24–1.0.40 took about 11 % a notch; kai asked for finer (1.0.41): about 5 %.
        for (z in listOf(1f, 0.8f, 0.6f, 0.45f)) {
            val next = DeckZoom.wheel(z, 1f)
            if (next == DeckZoom.MIN) continue
            val ratio = next / z
            assertTrue(ratio in 0.93f..0.97f, "at $z a notch scaled by $ratio")
        }
    }

    @Test
    fun theWholeRangeIsAboutNineteenNotches() {
        var z = 1f
        var notches = 0
        while (z > DeckZoom.MIN) {
            z = DeckZoom.wheel(z, 1f)
            notches++
        }
        assertTrue(notches in 17..21, "down took $notches")
        notches = 0
        while (z < 1f) {
            z = DeckZoom.wheel(z, -1f)
            notches++
        }
        assertTrue(notches in 16..21, "up took $notches")
    }

    @Test
    fun aTouchpadsFractionsAddUpToTheNotch() {
        var z = 0.8f
        repeat(10) { z = DeckZoom.wheel(z, 0.1f) }
        assertEquals(DeckZoom.wheel(0.8f, 1f), z, 1e-4f)
    }

    @Test
    fun aFlungWheelIsCapped() {
        assertEquals(DeckZoom.wheel(0.9f, DeckZoom.MAX_NOTCHES), DeckZoom.wheel(0.9f, 40f))
        assertTrue(DeckZoom.wheel(1f, 40f) > DeckZoom.MIN, "one event does not throw the deck end to end")
    }

    @Test
    fun itStaysInItsRange() {
        assertEquals(DeckZoom.MIN, DeckZoom.wheel(DeckZoom.MIN, 1f))
        assertEquals(1f, DeckZoom.wheel(1f, -1f))
        assertEquals(1f, DeckZoom.wheel(Float.NaN, 0f))
        assertEquals(0.7f, DeckZoom.wheel(0.7f, Float.NaN))
        assertEquals(0.7f, DeckZoom.wheel(0.7f, 0f))
    }

    @Test
    fun theLastNotchUpLandsOnTheFullSize() {
        assertEquals(1f, DeckZoom.wheel(0.95f, -1f))
        // Down never snaps: a touchpad may rest a hair under the full size.
        assertTrue(DeckZoom.wheel(1f, 0.1f) < 1f)
    }

    @Test
    fun theGlideClosesWithoutPassingTheTarget() {
        var shown = 1f
        val target = 0.6f
        var frames = 0
        var last = shown
        while (shown != target) {
            shown = DeckZoom.approach(shown, target, 16.6f)
            assertTrue(shown <= last && shown >= target, "monotone, never past: $shown")
            last = shown
            frames++
            assertTrue(frames < 60, "it settles")
        }
        // About the family's 180 ms, at sixty frames a second.
        assertTrue(frames in 8..20, "settled in $frames frames")
    }

    @Test
    fun aNotchMidGlideCarriesOnFromWhereTheDeckIs() {
        var shown = 1f
        repeat(3) { shown = DeckZoom.approach(shown, 0.8f, 16.6f) }
        val before = shown
        val next = DeckZoom.approach(shown, 0.7f, 16.6f)
        assertTrue(next < before && abs(next - before) < 0.1f, "no jump: $before → $next")
    }

    @Test
    fun noTimeIsNoMotion() {
        assertEquals(0.9f, DeckZoom.approach(0.9f, 0.5f, 0f))
        assertEquals(0.5f, DeckZoom.approach(Float.NaN, 0.5f, 16f))
        assertEquals(0.5f, DeckZoom.approach(0.9f, 0.5f, 10_000f), "a stalled frame arrives, it does not overshoot")
    }

    @Test
    fun theDeckGoesToTheMiddleSmoothly() {
        assertEquals(0f, DeckZoom.centring(1f))
        assertEquals(0.5f, DeckZoom.centring(DeckZoom.SNAP_FULL))
        assertEquals(0.5f, DeckZoom.centring(0.5f))
        val mid = DeckZoom.centring((1f + DeckZoom.SNAP_FULL) / 2)
        assertEquals(0.25f, mid, 1e-4f)
    }
}
