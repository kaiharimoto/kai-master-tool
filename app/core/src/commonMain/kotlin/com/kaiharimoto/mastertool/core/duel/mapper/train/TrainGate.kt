package com.kaiharimoto.mastertool.core.duel.mapper.train

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The gate a new network passes before it replaces the one in use (M.md §4.5): it is judged on hands neither was trained on,
 * by what the engine measures — never by its own loss, and never by Ai's word. A version that fails stays a file; the one in
 * use does not change.
 *
 * What it measures is **front recall**: each held-out hand is mapped once exhaustively (no prior, no budget) to find its
 * Pareto front on the traits where more is plainly better; then each network maps it under the gate set's one fixed budget,
 * and the score is how much of that front it found, and how early ([FrontRecall]). A network that finds more kinds of board
 * but misses the boards nothing beats does not pass — "more cells" was a measure a prior could game.
 */

/** Front recall at a budget. */
object FrontRecall {
    /**
     * The area under the recall curve of [front] over [budget] engine moves: a front board first found after `at` moves
     * ([found]: key to moves spent, `MapSearch.End.at`) adds (budget − at) ÷ budget, and the sum is divided by the front's
     * size. 1 is every front board found at once; 0 is none found within the budget. An empty front is 1 (nothing to find).
     */
    fun auc(front: Set<String>, found: Map<String, Int>, budget: Int): Double {
        if (front.isEmpty()) return 1.0
        val b = budget.coerceAtLeast(1).toDouble()
        return front.sumOf { k -> found[k]?.let { at -> ((b - at) / b).coerceIn(0.0, 1.0) } ?: 0.0 } / front.size
    }
}

/**
 * One held-out hand mapped by both networks under the gate set's [budget]: each one's front recall ([candidate], [current],
 * [FrontRecall.auc]). Higher is a win; equal (within [TIE]) is a tie, which the sign test leaves out.
 */
@Serializable
data class GateHand(val hand: String, val candidate: Double, val current: Double, val budget: Int = 0) {
    /** 1 a win for the candidate, ½ a tie, 0 a loss. */
    val score: Double get() = when {
        candidate > current + TIE -> 1.0
        candidate < current - TIE -> 0.0
        else -> 0.5
    }

    companion object {
        const val TIE = 1e-9
    }
}

/**
 * A version's search rating (M.md §4.5, the roadmap's "Elo per Ai version"): how much sooner its maps find the front, never
 * how well it plays. Version 0 is the hand-written order, at [Elo.BASE].
 */
@Serializable
data class Rated(val version: Int, val elo: Double, val low: Double, val high: Double, val parent: Int? = null, val hands: Int = 0, val at: Long = 0L)

object Elo {
    /** The rating the hand-written order (no network) stands at. */
    const val BASE = 1000.0

    /** The rating difference a score share [s] means: −400·log10(1/s − 1), held inside ±800 at a clean sweep. */
    fun diff(s: Double): Double {
        val c = s.coerceIn(0.01, 0.99)
        return -400.0 * log10(1.0 / c - 1.0)
    }

    /** The expected share for a rating difference [d]. */
    fun expected(d: Double): Double = 1.0 / (1.0 + 10.0.pow(-d / 400.0))
}

/** What the gate decided, with every number it read. */
data class GateVerdict(
    val promote: Boolean,
    /** Hands the candidate found more of the front on, fewer, and as much. */
    val wins: Int,
    val losses: Int,
    val ties: Int,
    /** The sign test's one-sided p: the chance of this many wins or more out of the decisive hands if neither were better. */
    val p: Double,
    /** The bar [p] had to clear: [TrainGate.ALPHA] shared among the candidates tried since the last promotion. */
    val bar: Double,
    /** The score share (ties half) and its 95 % range, and the search rating they mean. */
    val score: Double,
    val low: Double,
    val high: Double,
    val elo: Double,
    val eloLow: Double,
    val eloHigh: Double,
    /** Boards the person confirmed (kept lines, combos) the candidate's maps no longer found: any one blocks it. */
    val regressed: List<String>,
    /** The value heads it predicts worse than the network in use (beyond [TrainGate.ERROR_SLACK]), or not at all. */
    val worseHeads: List<String>,
    val why: String,
)

object TrainGate {
    /** A value head's error may grow by at most this share and still pass. */
    const val ERROR_SLACK = 0.05

    /** The fewest held-out hands a verdict is made on. */
    const val MIN_HANDS = 30

    /** The sign test's level for one candidate. */
    const val ALPHA = 0.05

    /**
     * Whether the candidate replaces the current network. It must, all at once:
     * - be judged on at least [MIN_HANDS] hands, every one at the gate set's one budget;
     * - find more of the front on more hands than it finds less, by a one-sided sign test on the decisive hands below
     *   [ALPHA] ÷ [attempts] — every candidate tried since the last promotion spends some of the chance of passing by luck;
     * - find every confirmed board ([regressed] empty);
     * - predict every value head no worse than [ERROR_SLACK] beyond the network in use ([valueError] and [currentError] by
     *   head, mean absolute error on the gate's hands; against the first network, [currentError] is predicting each head's
     *   mean, since the hand-written order predicts nothing).
     */
    fun judge(
        hands: List<GateHand>,
        regressed: List<String>,
        valueError: Map<String, Double>,
        currentError: Map<String, Double>,
        attempts: Int = 1,
    ): GateVerdict {
        val n = hands.size
        val wins = hands.count { it.score == 1.0 }
        val losses = hands.count { it.score == 0.0 }
        val ties = n - wins - losses
        val p = signTest(wins, losses)
        val bar = ALPHA / attempts.coerceAtLeast(1)
        val s = if (n == 0) 0.5 else hands.sumOf { it.score } / n
        val (lo, hi) = wilson(s, n)
        val elo = Elo.diff(s)
        val worse = currentError.keys.sorted().filter { h ->
            val c = valueError[h] ?: return@filter true
            c > currentError.getValue(h) * (1 + ERROR_SLACK) + 1e-9
        }
        val budgets = hands.map { it.budget }.distinct()
        val why = when {
            n < MIN_HANDS -> "Judged on $n held-out hands; $MIN_HANDS are needed."
            budgets.size > 1 -> "The hands were mapped at different budgets (${budgets.sorted().joinToString()}); the gate set has one."
            regressed.isNotEmpty() -> "It no longer finds ${regressed.size} board${if (regressed.size == 1) "" else "s"} you confirmed."
            worse.isNotEmpty() -> "It predicts ${worse.joinToString()} worse than the network in use."
            p >= bar -> "Its edge is not clear yet: more of the front on $wins hands, less on $losses (p ${fmt(p)}, needed below ${fmt(bar)})."
            else -> "It found more of the front on $wins hands and less on $losses (p ${fmt(p)}): search rating +${elo.toInt()}."
        }
        val promote = n >= MIN_HANDS && budgets.size <= 1 && regressed.isEmpty() && worse.isEmpty() && p < bar
        return GateVerdict(promote, wins, losses, ties, p, bar, s, lo, hi, elo, Elo.diff(lo), Elo.diff(hi), regressed, worse, why)
    }

    /** The one-sided sign test: P(X ≥ [wins]) for X ~ Binomial([wins] + [losses], ½). 1 when nothing was decisive. */
    fun signTest(wins: Int, losses: Int): Double {
        val m = wins + losses
        if (m == 0) return 1.0
        // Sum the upper tail in log space so a few hundred hands never overflow.
        var logC = 0.0 // log C(m, 0)
        val logs = DoubleArray(m + 1)
        for (k in 0..m) {
            if (k > 0) logC += ln((m - k + 1).toDouble()) - ln(k.toDouble())
            logs[k] = logC - m * ln(2.0)
        }
        return (wins..m).sumOf { exp(logs[it]) }.coerceIn(0.0, 1.0)
    }

    /** The candidate's place on the ladder after [v], from its parent's rating. */
    fun rate(parent: Rated, version: Int, v: GateVerdict, hands: Int, at: Long = 0L): Rated =
        Rated(version, parent.elo + v.elo, parent.elo + v.eloLow, parent.elo + v.eloHigh, parent.version, hands, at)

    /** The 95 % Wilson score interval of a share [p] over [n] trials. */
    fun wilson(p: Double, n: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n == 0) return 0.0 to 1.0
        val z2 = z * z
        val d = 1 + z2 / n
        val centre = (p + z2 / (2 * n)) / d
        val half = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / d
        return (centre - half).coerceAtLeast(0.0) to (centre + half).coerceAtMost(1.0)
    }

    private fun fmt(x: Double) = if (x < 0.001) "< 0.001" else ((x * 1000).toInt() / 1000.0).toString()
}
