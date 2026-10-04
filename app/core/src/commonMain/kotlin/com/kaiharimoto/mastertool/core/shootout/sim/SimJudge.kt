package com.kaiharimoto.mastertool.core.shootout.sim

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Compared
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.HandValue
import com.kaiharimoto.mastertool.core.shootout.model.ModelSpec
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.Reason
import kotlin.math.ln
import kotlin.random.Random

/**
 * A simulated person answering trials (Phase S §4), built to be harder than the model assumes:
 * - **noise in two parts**: misreading the situation (the matchup, the opponent's hand, the turn), drawn once per
 *   trial with scale [contextNoise], and misreading the hand itself, scale [handNoise] per hand. A rating carries
 *   both. A comparison shows both hands in one situation, so the situation's misreading is shared and cancels: that
 *   is S.md §1's premise that a comparison is judged "more consistently than a hand alone", made explicit. With
 *   [contextNoise] 0 a comparison is exactly as noisy, hand for hand, as a rating;
 * - **the five-point scale**: a rating is the band the reading falls in;
 * - **fatigue**: over [fatigueOver] trials the noise grows by [fatigue] (a fraction) and the cut-offs drift by
 *   [lean] toward calling hands worse, which the model, fitting one steady judge, does not know about;
 * - **slips**: a share [slips] of answers are keys pressed at random.
 */
class SimJudge(
    private val world: SyntheticDeck,
    seed: Long,
    val contextNoise: Double = 0.45,
    val handNoise: Double = 0.4,
    val slips: Double = 0.03,
    val fatigue: Double = 0.3,
    val lean: Double = 0.1,
    val fatigueOver: Int = 300,
    /** A steady lean, in log-odds, added to every reading: a judge always a little optimistic (stage 3, a biased Ai). */
    val offset: Double = 0.0,
    /** Cards this judge rates wrongly, by how many log-odds per copy: a judge with a blind spot. */
    val favour: Map<Int, Double> = emptyMap(),
    /** The judge's number in the model: 0 the person blind, 1 Ai, 2 the person after seeing Ai. */
    val judge: Int = 0,
) {
    private val random = Random(seed)
    private val value = HandValue(world.spec)

    /** The answer to [proposal], shown as the [index]th trial of the session. */
    fun answer(proposal: Proposal, index: Int): Trial {
        val tired = (index.toDouble() / fatigueOver).coerceAtMost(1.5)
        val grow = 1 + fatigue * tired
        val drift = lean * tired
        val s = world.spec.stratumIndex(proposal.stratum)
        val context = grow * contextNoise * logistic(random)
        val slipped = random.nextDouble() < slips
        return when (proposal) {
            is Proposal.Rate -> {
                val eta = value.of(world.truth, s, proposal.hand, proposal.opponent) + offset + favoured(proposal.hand)
                val read = eta + context + grow * handNoise * logistic(random)
                lastRead = read
                val band = ModelSpec.NOMINAL_CUTS.count { read > it + drift }
                val answer = if (slipped) random.nextInt(5) else band
                Rated(proposal.hand, proposal.opponent, proposal.stratum, Answer.entries[answer], judge = judge, plain = proposal.reason == Reason.PLAIN)
            }
            is Proposal.Compare -> {
                val left = value.of(world.truth, s, proposal.left, proposal.opponent) + favoured(proposal.left) + grow * handNoise * logistic(random)
                val right = value.of(world.truth, s, proposal.right, proposal.opponent) + favoured(proposal.right) + grow * handNoise * logistic(random)
                lastRead = left - right
                val prefersLeft = if (slipped) random.nextBoolean() else left > right
                Compared(proposal.left, proposal.right, proposal.opponent, proposal.stratum, prefersLeft, judge = judge)
            }
        }
    }

    /** The reading behind the last answer, in log-odds (a comparison's: left less right): how near a cut-off it fell. */
    var lastRead: Double = 0.0
        private set

    private fun favoured(hand: Hand): Double =
        if (favour.isEmpty()) 0.0 else hand.cards.sumOf { c -> (favour[c] ?: 0.0) * hand[c] }

    /** A standard logistic draw. */
    private fun logistic(random: Random): Double {
        val u = random.nextDouble().coerceIn(1e-12, 1 - 1e-12)
        return ln(u / (1 - u))
    }
}
