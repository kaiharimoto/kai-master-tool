package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TakeoverTest {
    private fun times(step: Float = .01f) = generateSequence(0f) { it + step }.takeWhile { it <= Takeover.END }

    @Test
    fun theBeatsFollowOneAnotherToTheEnd() {
        val beats = Takeover.Beat.entries
        assertEquals(0f, beats.first().from)
        assertEquals(Takeover.END, beats.last().to)
        beats.zipWithNext { a, b -> assertEquals(a.to, b.from, "$a then $b") }
        assertEquals(Takeover.Beat.CHAOS, Takeover.Beat.at(7f))
        assertEquals(Takeover.Beat.AI, Takeover.Beat.at(35f))
    }

    @Test
    fun noMoreThanTwoFlashesNeverThreeASecondAndTheGlowNeverFlashes() {
        // a flash: the window going from lit to fully dark
        var flashes = 0
        var dark = false
        val at = ArrayList<Float>()
        for (t in times(.005f)) {
            val now = Takeover.dark(t) >= .99f
            if (now && !dark) { flashes++; at += t }
            dark = now
        }
        assertEquals(2, flashes)
        assertTrue(at.zipWithNext { a, b -> b - a }.all { it >= 1f / 3f }, "$at")
        // the glow breathes at 0.8 Hz: under the three-a-second line, and never full
        assertTrue(Takeover.GLOW_HZ <= 1f)
        assertTrue(times().all { Takeover.glow(it) <= .9f })
    }

    @Test
    fun herBoxesTypeInOrderAndAreGoneWhenAiIsBack() {
        for (line in Takeover.LINES) {
            assertTrue(line.typedBy < line.at + line.life, "${line.text} is typed before its box goes")
            assertEquals(0, Takeover.typed(line, line.at))
            assertEquals(line.units, Takeover.typed(line, line.at + line.life))
        }
        assertTrue(Takeover.shownLines(Takeover.AI_ON + .5f).isEmpty())
        // at most two boxes at once, and on a phone two at once never stand in the same band (above her, or below)
        for (t in times()) {
            val shown = Takeover.shownLines(t)
            assertTrue(shown.size <= 2, "$t: ${shown.size}")
            if (shown.size == 2) assertTrue((shown[0].tall.top < 40f) != (shown[1].tall.top < 40f), "$t: two boxes on one side of her")
        }
    }

    @Test
    fun aiWinsBackTheWindowOnceKnockedBack() {
        assertEquals(0f, Takeover.cleanShare(25f))
        assertTrue(Takeover.restored(26.9f) > 30f)
        assertTrue(Takeover.restored(27.5f) < 20f, "she knocks it back")
        assertEquals(1f, Takeover.cleanShare(31f))
        assertTrue(Takeover.patching(25f) && !Takeover.patching(26.5f))
        assertEquals(1f, Takeover.push(31f))
        assertEquals(Expression.ANGRY, Takeover.chessyMood(27.2f))
        assertEquals(Expression.SURPRISED, Takeover.aiFace(Takeover.AI_ON + .1f))
    }

    @Test
    fun theSoundtrackIsInOrderAndTheHornIsKais() {
        val cues = Takeover.CUES
        assertEquals(cues.sortedBy { it.at }, cues)
        val horns = cues.filter { it.sound == Takeover.Sound.HORN }
        assertTrue(horns.size >= 6)
        assertTrue(horns.all { it.at >= Takeover.CALM_TO && it.at < Takeover.SNAP_AT })
        assertEquals(TakeoverHorn.BLAST_S, horns.first().len)
        assertEquals(10, cues.count { it.sound == Takeover.Sound.RESTORE })
        assertTrue(cues.all { it.at in 0f..Takeover.END })
    }

    @Test
    fun theHeadsComeInTheChaosOnly() {
        for (h in Takeover.HEADS) {
            assertTrue(h.born >= Takeover.CHAOS_AT && h.born < Takeover.SNAP_AT)
            assertFalse(Takeover.headShown(h, Takeover.SNAP_AT + .01f))
        }
    }
}
