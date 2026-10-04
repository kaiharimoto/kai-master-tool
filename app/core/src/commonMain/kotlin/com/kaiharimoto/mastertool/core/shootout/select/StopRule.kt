package com.kaiharimoto.mastertool.core.shootout.select

import com.kaiharimoto.mastertool.core.shootout.model.Ratings
import com.kaiharimoto.mastertool.core.shootout.model.Stratum

/**
 * What a session has settled (Phase S §3, step 6): "21 of 24 cards known within ±3 points".
 *
 * A card is known when, in every stratum in play, its 95 % range is no wider than ± [halfWidth] points. The session
 * may stop when [share] of the deck's cards are known; it can end at any moment regardless, every answer kept.
 * The defaults are the simulation's tuning (S.md "Simulation results").
 */
data class StopRule(val halfWidth: Double = 3.0, val share: Double = 0.875) {

    /** How many cards are known, of how many, and whether that is enough to suggest stopping. */
    data class Settled(val known: Int, val of: Int, val halfWidth: Double, val enough: Boolean) {
        override fun toString(): String = "$known of $of cards known within ±${halfWidth.toInt()} points"
    }

    /** Reads [ratings] in [strata]; a card no hand in a stratum can hold (none in that deck) is left out of it. */
    fun read(ratings: Ratings, strata: List<Stratum>): Settled {
        val byCard = ratings.cards.filter { it.stratum in strata && it.drawShare > 0 }.groupBy { it.card }
        val known = byCard.values.count { r -> r.all { it.estimate.halfWidth95 <= halfWidth } }
        val of = byCard.size
        return Settled(known, of, halfWidth, of > 0 && known >= share * of)
    }
}
