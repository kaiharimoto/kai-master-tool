package com.kaiharimoto.mastertool.core.duel.mapper.train

import kotlinx.serialization.Serializable
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The gate a new network passes before it replaces the one in use (M.md §4.5): it is judged on hands neither was trained on,
 * by what the engine measures — never by its own loss, and never by Ai's word. A version that fails stays a file; the one in
 * use does not change.
 */

/**
 * One held-out hand mapped by both networks under the same engine-move budget: the MAP-Elites cells each found
 * ([candidate], [current]), and whether each map was complete. More cells for the same budget is a win; equal is a draw.
 */
@Serializable
data class GateHand(val hand: String, val candidate: Int, val current: Int, val candidateComplete: Boolean = false, val currentComplete: Boolean = false) {
    /** 1 a win for the candidate, ½ a draw, 0 a loss. */
    val score: Double get() = when {
        candidate > current -> 1.0
        candidate < current -> 0.0
        else -> 0.5
    }
}

/** A version's rating (M.md §4.5, the roadmap's "Elo per Ai version"): version 0 is the hand-written order, at [Elo.BASE]. */
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
    val score: Double,
    val low: Double,
    val high: Double,
    val elo: Double,
    val eloLow: Double,
    val eloHigh: Double,
    /** Boards the person confirmed (kept lines, combos) the candidate's maps no longer found: any one blocks it. */
    val regressed: List<String>,
    /** Mean absolute error of the predicted traits against the engine's measure, candidate and current. */
    val valueError: Double,
    val currentError: Double,
    val why: String,
)

object TrainGate {
    /** The value error may grow by at most this share and still pass. */
    const val ERROR_SLACK = 0.05

    /** The fewest held-out hands a verdict is made on. */
    const val MIN_HANDS = 30

    /**
     * Whether the candidate replaces the current network. It must, all at once:
     * - win more than it loses on [hands], with the 95 % Wilson range of its score above ½ (draws count half);
     * - find every confirmed board ([regressed] empty);
     * - predict the traits no worse than [ERROR_SLACK] beyond the current network's error;
     * - be judged on at least [MIN_HANDS] hands.
     */
    fun judge(hands: List<GateHand>, regressed: List<String>, valueError: Double, currentError: Double): GateVerdict {
        val n = hands.size
        val s = if (n == 0) 0.5 else hands.sumOf { it.score } / n
        val (lo, hi) = wilson(s, n)
        val elo = Elo.diff(s)
        val why = when {
            n < MIN_HANDS -> "Judged on $n held-out hands; $MIN_HANDS are needed."
            regressed.isNotEmpty() -> "It no longer finds ${regressed.size} board${if (regressed.size == 1) "" else "s"} you confirmed."
            valueError > currentError * (1 + ERROR_SLACK) + 1e-9 -> "Its trait predictions are worse (${fmt(valueError)} against ${fmt(currentError)})."
            lo <= 0.5 -> "Its edge is not clear yet: ${pct(s)} of the score, ${pct(lo)}–${pct(hi)} at 95 %."
            else -> "It found more on ${pct(s)} of the score (${pct(lo)}–${pct(hi)}), +${elo.toInt()} Elo."
        }
        val promote = n >= MIN_HANDS && regressed.isEmpty() && valueError <= currentError * (1 + ERROR_SLACK) + 1e-9 && lo > 0.5
        return GateVerdict(promote, s, lo, hi, elo, Elo.diff(lo), Elo.diff(hi), regressed, valueError, currentError, why)
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

    private fun pct(x: Double) = "${(x * 1000).toInt() / 10.0} %"
    private fun fmt(x: Double) = ((x * 1000).toInt() / 1000.0).toString()
}
