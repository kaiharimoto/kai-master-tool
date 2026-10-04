package com.kaiharimoto.mastertool.core.duel.dice

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** 1.0.92: a throw played out once and kept is the run a fresh play-out gives, frame for frame. */
class DiceRunsTest {
    @Test
    fun aKeptRunIsTheRunAndIsMadeOnce() {
        DiceRuns.clear()
        val a = DiceThrow.random(Random(3))
        val b = DiceThrow.random(Random(4))
        val before = DiceRuns.simulated
        val ra = DiceRuns.of(a)
        assertEquals(DiceSim.run(a), ra)
        DiceRuns.warm(b)
        // Both seats' throws asked again, as the table asks when the second seat throws: nothing played out again.
        assertSame(ra, DiceRuns.of(a))
        assertEquals(DiceSim.run(b), DiceRuns.of(b))
        assertEquals(before + 2, DiceRuns.simulated)
        // An equal throw read back from the log is the same key.
        assertSame(ra, DiceRuns.of(a.copy(dice = a.dice.toList())))
    }

    @Test
    fun onlyTheLastFewThrowsAreKept() {
        DiceRuns.clear()
        val throws = (0 until DiceRuns.KEEP + 2).map { DiceThrow.random(Random(100 + it)) }
        throws.forEach { DiceRuns.of(it) }
        val before = DiceRuns.simulated
        DiceRuns.of(throws.last())
        assertEquals(before, DiceRuns.simulated)
        DiceRuns.of(throws.first())
        assertEquals(before + 1, DiceRuns.simulated)
    }
}
