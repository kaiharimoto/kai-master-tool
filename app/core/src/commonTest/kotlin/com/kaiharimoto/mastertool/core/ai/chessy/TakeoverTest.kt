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
        assertEquals(Takeover.Beat.AI, Takeover.Beat.at(Takeover.AI_ON + 1f))
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
        assertTrue(Takeover.restored(Takeover.KNOCK_AT - .1f) > 30f)
        assertTrue(Takeover.restored(Takeover.KNOCK_AT + .5f) < 20f, "she knocks it back")
        assertEquals(1f, Takeover.cleanShare(Takeover.RESTORED_AT))
        assertTrue(Takeover.patching(Takeover.FIGHT_AT + .4f) && !Takeover.patching(Takeover.RESTORE_AT + .9f))
        assertEquals(1f, Takeover.push(Takeover.AI_ON))
        assertEquals(Expression.ANGRY, Takeover.chessyMood(Takeover.KNOCK_AT + .2f))
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
    fun herLinesLeaveAPauseToReadBeforeTheNext() {
        // kai: "a slight pause between the cinematic's text box lines to give a bit of room for the user to read"
        for ((a, b) in Takeover.LINES.zipWithNext()) assertTrue(b.at - a.typedBy >= .8f || b.at >= Takeover.FIGHT_AT, "${a.text}: ${b.at - a.typedBy}")
        assertEquals(Takeover.LINES.size, Takeover.LINE_VOICES.size)
        // her voice speaks each line, in her nya's sounds
        for (line in Takeover.LINES) assertTrue(Takeover.CUES.any { it.sound == Takeover.Sound.VOICE && it.at == line.at }, line.text)
    }

    @Test
    fun warningWindowsPileUpThroughTheAlarmAndTheChaosAndAreGoneAtTheSnap() {
        val w = Takeover.WARNINGS
        assertTrue(w.size >= 20, "${w.size}")
        assertTrue(w.all { it.born >= Takeover.CALM_TO && it.born < Takeover.SNAP_AT })
        assertTrue(w.all { it.x in 0f..1f && it.y in 0f..1f })
        val alarm = w.count { it.born < Takeover.CHAOS_AT } / (Takeover.CHAOS_AT - 2.7f)
        val chaos = w.count { it.born >= Takeover.CHAOS_AT } / (Takeover.SNAP_AT - Takeover.CHAOS_AT)
        assertTrue(chaos > alarm * 1.5f, "faster in the chaos: $alarm, $chaos a second")
        assertTrue(w.none { Takeover.warningShown(it, Takeover.SNAP_AT) })
        assertTrue(w.count { Takeover.warningShown(it, 8.5f) } >= 6)
    }

    @Test
    fun aiHoldsHerInAGlitchingFrameAndShePushesBack() {
        assertEquals(0f, Takeover.contained(Takeover.FIGHT_AT))
        assertEquals(1f, Takeover.contained(Takeover.AI_ON))
        assertTrue(Takeover.frameGlitch(Takeover.AI_ON) > .8f, "the glitches fight the frame as it closes")
        val quiet = Takeover.frameGlitch(Takeover.PUSHES[1] - .3f)
        assertTrue(quiet in .1f..0.4f, "never still: $quiet")
        for (at in Takeover.PUSHES) {
            assertTrue(Takeover.frameGlitch(at + .12f) > .9f)
            assertEquals(Expression.ANGRY, Takeover.chessyMood(at + .12f))
        }
        assertTrue(Takeover.looksUp(Takeover.AI_ON + 1f))
    }

    @Test
    fun theHeadsComeInTheChaosOnly() {
        for (h in Takeover.HEADS) {
            assertTrue(h.born >= Takeover.CHAOS_AT && h.born < Takeover.SNAP_AT)
            assertFalse(Takeover.headShown(h, Takeover.SNAP_AT + .01f))
        }
    }
}
