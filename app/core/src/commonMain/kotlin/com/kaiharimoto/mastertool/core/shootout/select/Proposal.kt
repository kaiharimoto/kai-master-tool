package com.kaiharimoto.mastertool.core.shootout.select

import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.Stratum

/** Why a trial was shown: the picker's choice, a plain shuffle (the honesty check), or a hand shown before. */
enum class Reason { CHOSEN, PLAIN, REPEAT }

/** The next trial to show (Phase S §3). */
sealed interface Proposal {
    val stratum: Stratum
    val opponent: Hand?
    val reason: Reason

    /** Rate [hand] on the five-point scale. */
    data class Rate(
        val hand: Hand,
        override val opponent: Hand?,
        override val stratum: Stratum,
        override val reason: Reason,
    ) : Proposal

    /** Which of [left] and [right] would you rather open. */
    data class Compare(
        val left: Hand,
        val right: Hand,
        override val opponent: Hand?,
        override val stratum: Stratum,
        override val reason: Reason = Reason.CHOSEN,
    ) : Proposal
}

/**
 * The picker's settings (Phase S §3). The defaults are the simulation's tuning (S.md "Simulation results").
 */
data class PickerSettings(
    /** About one trial in six is a plain random opening hand, so the model is tested against reality. */
    val plainShare: Double = 1.0 / 6,
    /** A few hands come back unannounced; their answers measure the judge's own noise. */
    val repeatShare: Double = 1.0 / 20,
    /** A hand comes back only after this many other trials, so it is not remembered. */
    val repeatAge: Int = 8,
    /**
     * At least this share of trials are comparisons, so the judge's noise between two hands is measured and the
     * picker can weigh comparisons honestly; beyond it they compete with ratings on what they teach.
     */
    val compareShare: Double = 1.0 / 20,
    /** Opening hands dealt per step as candidates (shared across the strata in play). */
    val handCandidates: Int = 240,
    /** One-card variants of judged hands offered per step as comparisons; 0 for none. */
    val compareCandidates: Int = 40,
    /** Rounds of one-card improvements to the chosen hand; 0 shows the best dealt candidate as it is. */
    val climb: Int = 6,
    /** How much a pair's range counts beside a card's, after both are weighted by how often they are drawn. */
    val pairWeight: Double = 1.0,
    /** How much the real-world win rate's range counts. */
    val winRateWeight: Double = 0.5,
    /** Hands the picker averages its targets over: fewer than a report's, since only their direction matters. */
    val targetPool: Int = 120,
    /** A hand whose likeliest answer is this sure, and whose value is known this tightly, is not shown. */
    val skipCertainty: Double = 0.9,
    val skipSd: Double = 0.35,
    /** The strata may drift apart by this many trials (plus a tenth of the total) before the lagging one is forced. */
    val balanceSlack: Int = 2,
    /** A session pinned to one stratum. */
    val pinned: Stratum? = null,
)
