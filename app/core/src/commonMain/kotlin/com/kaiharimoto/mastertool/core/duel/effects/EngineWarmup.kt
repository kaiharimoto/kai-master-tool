package com.kaiharimoto.mastertool.core.duel.effects

import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * The engine's first pass, run before anyone waits on it (I.1, D.md §5.7). Measured, the engine makes about 7,000 moves a
 * second on its first pass and 25,000 once the JIT has settled: a search that runs for minutes (the Mapper, a series) runs at
 * the steady rate, but a short one — a turn solved, a Search seat's answer at the table — paid the cold one. So the app plays
 * the reference table ([FxRef.full], what the bench plays) for a moment, off the frame thread, the first time a page that
 * searches opens.
 *
 * Pure: it plays on tables of its own and keeps nothing, so whether it ran never changes an answer the engine gives.
 */
object EngineWarmup {
    /**
     * How long it plays. On the bench's machine the JIT settles pass by pass — 7,000, 9,200, 9,800, 12,200, 16,000, 17,900,
     * 19,300, 23,600, then 27,000 a second, about six seconds of play — so five seconds leaves a search near the steady rate.
     */
    const val BUDGET_MS = 5_000L

    /** Moves a walk makes from its table before the next seed's. */
    private const val STEPS = 60

    /**
     * Plays seeded walks over the reference table for [budgetMs], or until [stop] says so between moves: the number of engine
     * moves made.
     */
    fun run(budgetMs: Long = BUDGET_MS, stop: () -> Boolean = { false }): Int {
        val clock = TimeSource.Monotonic.markNow()
        var made = 0
        var seed = 1L
        while (clock.elapsedNow().inWholeMilliseconds < budgetMs && !stop()) {
            val r = Random(seed)
            var t = FxRef.full(seed).second
            val choose = Chooser { d -> answer(d, r) }
            for (step in 0 until STEPS) {
                if (stop() || clock.elapsedNow().inWholeMilliseconds >= budgetMs) return made
                val seat = FxEngine.next(t)
                val moves = FxEngine.moves(t, seat)
                if (moves.isEmpty()) break
                val p = FxEngine.play(t, seat, moves[r.nextInt(moves.size)], choose)
                made++
                if (p is FxPlay.Done) t = FxTable(p.state, p.fx, t.book, t.facts, t.seed)
            }
            seed++
        }
        return made
    }

    /** A legal answer at random, as the walks answer (never out of bounds: nothing here is meant to cancel). */
    private fun answer(d: Decision, r: Random): List<Int> = when (d) {
        is Decision.Cards -> d.among.indices.shuffled(r).take(r.nextInt(d.min, d.max.coerceAtMost(d.among.size) + 1))
        is Decision.Zone -> listOf(r.nextInt(d.among.size))
        is Decision.Position -> listOf(r.nextInt(d.among.size))
        is Decision.Order -> d.triggers.indices.shuffled(r)
        is Decision.YesNo -> listOf(r.nextInt(2))
        is Decision.Option -> listOf(r.nextInt(d.among.size))
        is Decision.Declare -> listOf(r.nextInt(d.among.size))
    }
}
