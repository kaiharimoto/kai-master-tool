package com.kaiharimoto.mastertool.core.ai.proposals

import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Ai's suggestions scored against your results (Phase G part two, G2.3; `docs/phases/G2.md`): every applied proposal that said
 * what a win rate would do, with a range, set beside the games you played since at the version it made against those before
 * (`Proposals.outcome`). Two scores over them all: how often the gain you saw lay inside the range it gave, and the Brier score
 * of the chance it gave that the change helps, against whether it did — a coin's is 0.25. A proposal with fewer games than
 * [MIN_GAMES] on either side waits, and says so.
 */
object ProposalScore {
    /** Games on each side of a change before it is scored: fewer, and what was seen is mostly noise. */
    const val MIN_GAMES = 8

    /** Proposals scored before the scores are read as anything but a start. */
    const val ENOUGH = 5

    /**
     * One applied proposal scored: what it predicted (points of win rate, with its range), what was seen since, whether the seen
     * gain lay inside the range ([held]), the chance it gave that the change helps ([helps]) and whether it did ([helped]).
     */
    data class Scored(
        val proposal: Proposal,
        val expect: Expect,
        val predicted: Double,
        val low: Double,
        val high: Double,
        val seen: Double,
        val seenLow: Double,
        val seenHigh: Double,
        val before: TestStats.Rate,
        val after: TestStats.Rate,
    ) {
        val held: Boolean get() = seen in low..high

        /** The chance the change helps, as its range said: a normal through the range's middle, the range 95 % of it. */
        val helps: Double get() {
            val sd = (high - low) / (2 * 1.96)
            return if (sd <= 0) (if (predicted > 0) 1.0 else if (predicted < 0) 0.0 else 0.5) else normalCdf(predicted / sd)
        }

        val helped: Boolean get() = seen > 0

        val brier: Double get() = (helps - (if (helped) 1.0 else 0.0)).let { it * it }
    }

    /** Every applied proposal read: the [scored], those [waiting] on games, and the two scores over the scored. */
    data class Score(val scored: List<Scored>, val waiting: List<Proposal>) {
        val held: Int get() = scored.count { it.held }
        val brier: Double? get() = if (scored.isEmpty()) null else scored.sumOf { it.brier } / scored.size
    }

    /** Whether [e] says what a win rate does, with a range a score can hold it to. */
    fun scorable(e: Expect): Boolean =
        e.before != null && e.after != null && e.low != null && e.high != null && e.unit == "%" &&
            (e.kind == Expect.SERIES || e.kind == Expect.GAMES || "win" in e.metric.lowercase())

    fun score(proposals: List<Proposal>, games: List<TestGame>): Score {
        val scored = ArrayList<Scored>()
        val waiting = ArrayList<Proposal>()
        proposals.filter { it.state == Proposal.APPLIED && it.expect.any(::scorable) }.forEach { p ->
            val o = Proposals.outcome(p, games)
            val d = o?.difference
            if (o == null || d == null || o.before.games < MIN_GAMES || o.after.games < MIN_GAMES) {
                waiting += p
                return@forEach
            }
            val e = p.expect.first(::scorable)
            val before = e.before!!
            scored += Scored(p, e, e.after!! - before, e.low!! - before, e.high!! - before, d.points, d.low, d.high, o.before, o.after)
        }
        return Score(scored, waiting)
    }

    /** The score in words (Test scores, Ai's `proposals_scored`). */
    fun words(s: Score): String = buildString {
        if (s.scored.isEmpty()) {
            append(
                if (s.waiting.isEmpty()) "No applied proposal has said what a win rate would do yet: nothing to score."
                else "${s.waiting.size} applied ${if (s.waiting.size == 1) "proposal waits" else "proposals wait"} for $MIN_GAMES games on each side of the change before it is scored.",
            )
            return@buildString
        }
        val n = s.scored.size
        append("$n ${if (n == 1) "suggestion" else "suggestions"} scored against your games: the gain you saw lay inside its range ${s.held} of $n times")
        s.brier?.let { append("; Brier ${dec(it)} for \"it helps\" against a coin's 0.25") }
        append(if (n < ENOUGH) " — too few yet to judge Ai by." else ".")
        s.scored.forEach { x ->
            append("\n- ${x.proposal.title}: said ${pts(x.predicted)} (${pts(x.low)} to ${pts(x.high)}), saw ${pts(x.seen)} (${pts(x.seenLow)} to ${pts(x.seenHigh)}) over ${x.before.games} games before and ${x.after.games} after — ${if (x.held) "held" else "missed"}")
        }
        if (s.waiting.isNotEmpty()) append("\n${s.waiting.size} more ${if (s.waiting.size == 1) "waits" else "wait"} for games.")
    }

    private fun pts(x: Double): String = x.roundToInt().let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }

    private fun dec(x: Double): String = ((x * 1000).roundToInt() / 1000.0).toString()

    /** The standard normal's distribution (Abramowitz and Stegun 7.1.26 through erf). */
    private fun normalCdf(z: Double): Double {
        val x = abs(z) / sqrt(2.0)
        val t = 1 / (1 + 0.3275911 * x)
        val erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (z >= 0) 0.5 * (1 + erf) else 0.5 * (1 - erf)
    }
}
