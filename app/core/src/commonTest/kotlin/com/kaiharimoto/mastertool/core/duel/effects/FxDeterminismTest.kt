package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.DuelCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The engine is deterministic (D.md §2.5, §10 `FxDeterminismTest`): the same table, scripts and answers always give the same
 * actions, tags and state — walk for walk, entry for entry, written to a file byte for byte — and a different seed walks
 * elsewhere.
 */
class FxDeterminismTest {
    @Test
    fun theSameTableAndAnswersGiveTheSameLog() {
        for (seed in 1L..30L) {
            val a = FxWalker.walk(seed)
            val b = FxWalker.walk(seed)
            assertEquals(a.entries, b.entries, "seed $seed")
            assertEquals(a.state, b.state, "seed $seed")
            assertEquals(DuelCodec.encode(a.record()), DuelCodec.encode(b.record()), "seed $seed: the files agree")
        }
        assertNotEquals(FxWalker.walk(1).entries, FxWalker.walk(2).entries, "two seeds, two walks")
    }

    @Test
    fun aMoveMadeTwiceOnOneTableMakesTheSameThing() {
        val (_, t) = FxWalker.start()
        val moves = FxEngine.moves(t, 0)
        moves.forEach { m ->
            val p1 = FxEngine.play(t, 0, m, Chooser.FIRST)
            val p2 = FxEngine.play(t, 0, m, Chooser.FIRST)
            assertEquals(p1, p2, "$m")
        }
        assertEquals(moves, FxEngine.moves(t, 0))
    }
}
