package com.kaiharimoto.mastertool.core.duel.effects

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The engine's speed (D.md §5.7, §10 `FxBenchTest`): engine moves a second on one core, the plan's assumption being at
 * least 20,000 a second on the desk and a fifth of that on a mid-range phone. A "move" here is one `FxEngine.play` made
 * from the moves `FxEngine.moves` lists — the unit the goldfish's search spends — on the walker's full reference table,
 * both seats, random answers.
 *
 * **The budget is a floor, not the target**, so the test never flakes on a slow or busy runner: it fails only below
 * [FLOOR] moves a second, a tenth of the desk's assumption and well under what any machine CI runs on makes. The measured
 * rate is printed (the test's standard output in its report) for the release notes; a warm-up runs first so the JIT's
 * first pass is not counted.
 */
class FxBenchTest {
    private class Count(var plays: Int = 0, var lists: Int = 0, var actions: Int = 0)

    private fun run(seeds: LongRange, c: Count) {
        for (seed in seeds) {
            val r = Random(seed)
            var t = FxWalker.start(seed).second
            val choose = FxWalker.chooser(r, cancelOneIn = 0)
            repeat(60) {
                val seat = FxEngine.next(t)
                val moves = FxEngine.moves(t, seat)
                c.lists++
                if (moves.isEmpty()) return@repeat
                val p = FxEngine.play(t, seat, moves[r.nextInt(moves.size)], choose)
                c.plays++
                if (p is FxPlay.Done) {
                    c.actions += p.actions.size
                    t = FxTable(p.state, p.fx, t.book, t.facts, t.seed)
                }
            }
        }
    }

    @Test
    fun engineMovesASecond() {
        run(1L..40L, Count()) // warm-up
        val c = Count()
        val start = TimeSource.Monotonic.markNow()
        run(100L..399L, c)
        val ms = start.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        val rate = c.plays * 1000L / ms
        println("FxBenchTest: ${c.plays} engine moves (${c.lists} move lists, ${c.actions} actions) in $ms ms: $rate moves a second on one core")
        // The steady state a long search runs at (the red team, D.md §5.7): the same pass again, three times, warm; the
        // median printed beside the first pass (which still pays the JIT's later tiers, and is what the floor judges).
        val warm = (1..3).map {
            val w = Count()
            val m = TimeSource.Monotonic.markNow()
            run(100L..399L, w)
            w.plays * 1000L / m.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        }.sorted()
        println("FxBenchTest: warm, ${warm[1]} moves a second (median of $warm)")
        assertTrue(rate >= FLOOR, "$rate moves a second is below the floor of $FLOOR")
    }

    companion object {
        /** A tenth of the desk's assumption (D.md §5.7: 20,000 a second): the line below which something is badly wrong. */
        const val FLOOR = 2_000L
    }
}
