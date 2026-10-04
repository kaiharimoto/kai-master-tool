package com.kaiharimoto.mastertool.core.shootout.model

import com.kaiharimoto.mastertool.core.shootout.math.Normal
import kotlin.math.round
import kotlin.math.sqrt

/**
 * A reported number in points of win chance (one point is one percentage point), with its Laplace standard
 * deviation (Phase S §2). The ranges are normal, by the delta method: the reported numbers are smooth in the
 * parameters, and the simulation holds the ranges to their stated coverage (S.md §4).
 */
class Estimate(val value: Double, val sd: Double) {
    val range80: ClosedFloatingPointRange<Double> get() = (value - Normal.Z80 * sd)..(value + Normal.Z80 * sd)
    val range95: ClosedFloatingPointRange<Double> get() = (value - Normal.Z95 * sd)..(value + Normal.Z95 * sd)

    /** Half the 95 % range: "known within ± this many points". */
    val halfWidth95: Double get() = Normal.Z95 * sd

    /** Whether the 95 % range lies wholly on one side of zero. */
    val excludesZero: Boolean get() = range95.let { it.start > 0 || it.endInclusive < 0 }

    override fun toString(): String = "${fmt(value)} ± ${fmt(halfWidth95)}"

    private fun fmt(x: Double) = (round(x * 10) / 10).toString()
}

/**
 * One card's rating in one stratum: the change in win chance from holding one more copy, against the card the deck
 * would have dealt instead, averaged over the hands it really appears in (Phase S §2). [drawShare] is the chance it
 * is in an opening hand at all.
 */
class CardRating(val card: Int, val stratum: Stratum, val estimate: Estimate, val drawShare: Double)

/**
 * One pair's extra win chance from holding both, beyond the two cards' own, averaged over the hands holding both.
 * [shown] only once its 95 % range excludes zero (Phase S §2): a pair must earn its place. [backedBy] is how many
 * trials showed a hand holding both.
 */
class PairRating(val pair: CardPair, val stratum: Stratum, val estimate: Estimate, val backedBy: Int) {
    val shown: Boolean get() = estimate.excludesZero
}

/** What the trials say, per stratum: the cards, the pairs that earned a place, and the hands' real-world win rate. */
class Ratings(
    val cards: List<CardRating>,
    val pairs: List<PairRating>,
    val winRates: Map<Stratum, Estimate>,
) {
    /** The cards' ratings in [stratum], by card. */
    fun cards(stratum: Stratum): List<CardRating> = cards.filter { it.stratum == stratum }

    /** The pairs worth showing. */
    val shownPairs: List<PairRating> get() = pairs.filter { it.shown }
}

/**
 * A number read out of the model with its gradient over the parameters: the gradient turns the Laplace covariance
 * into the number's range, and tells the picker which trials would narrow it.
 */
class Contrast(val target: Target, val value: Double, val gradient: DoubleArray) {
    /** The number's standard deviation under [fit], by the delta method. */
    fun sd(fit: Fit): Double = sqrt(fit.covariance.quadratic(gradient).coerceAtLeast(0.0))
}

/** What a [Contrast] is of. */
sealed interface Target {
    val stratum: Stratum
    data class Card(val card: Int, override val stratum: Stratum) : Target
    data class Pair(val pair: Int, override val stratum: Stratum) : Target
    data class WinRate(override val stratum: Stratum) : Target
}
