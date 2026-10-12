package com.kaiharimoto.mastertool.core.duel.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Phase I, I.1: the engine's first pass played before anyone waits on it — on its own tables, within its time, stopped when asked. */
class EngineWarmupTest {

    @Test
    fun itPlaysTheReferenceTableForItsTimeAndNoLonger() {
        val clock = TimeSource.Monotonic.markNow()
        val made = EngineWarmup.run(budgetMs = 300)
        assertTrue(made > 0, "it made engine moves")
        assertTrue(clock.elapsedNow().inWholeMilliseconds < 300 + 2_000, "it stops near its budget (one move past it at most)")
    }

    @Test
    fun itStopsWhenAsked() {
        assertEquals(0, EngineWarmup.run(budgetMs = 10_000, stop = { true }))
        var asked = 0
        val made = EngineWarmup.run(budgetMs = 10_000, stop = { ++asked > 20 })
        assertTrue(made in 1..20, "it stopped between moves: $made")
    }

    @Test
    fun theTableItPlaysIsTheWalksTable() {
        // One list of the reference table: the walks, the bench and the warm-up play the same.
        assertEquals(FxRef.full(7).second, FxWalker.start(7).second)
    }
}
