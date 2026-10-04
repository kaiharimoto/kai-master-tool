package com.kaiharimoto.mastertool.core.shootout.select

import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.Decks
import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.HandValue
import com.kaiharimoto.mastertool.core.shootout.model.Likelihood
import com.kaiharimoto.mastertool.core.shootout.model.ModelSpec
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Sparse
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Chooses the next trial (Phase S §3): mostly the one expected to shrink the uncertainty that matters most
 * ([Targets]), and, to keep it honest, sometimes a plain shuffled hand, a hand shown before, or a comparison.
 *
 * Candidates are opening hands dealt as a shuffle would (with the opponent's), one-card variants of judged hands
 * as comparisons, and then the best rating improved one card at a time ([PickerSettings.climb]): the hand shown is
 * any hand the decks can deal, chosen for what it teaches, and the reports correct for that by averaging over hands
 * by their real odds, never over the hands shown.
 */
class Picker(
    private val spec: ModelSpec,
    private val decks: Decks,
    val settings: PickerSettings = PickerSettings(),
    seed: Long = 7L,
) {
    private val random = Random(seed)
    private val value = HandValue(spec)
    private val layout = spec.layout
    private val reporter = Reporter(spec, decks, settings.targetPool, seed * 3 + 1)

    /** The strata this session deals in: the pinned one, or all the model holds. */
    val strata: List<Stratum> = settings.pinned?.let { listOf(it) } ?: spec.strata

    /** The next trial, given what has been answered and the fit read from it. */
    fun next(trials: List<Trial>, fit: Fit): Proposal {
        val allowed = lagging(trials)
        var roll = random.nextDouble()
        if (roll < settings.plainShare) return plain(allowed)
        roll -= settings.plainShare
        if (roll < settings.repeatShare) repeat(trials, allowed)?.let { return it }
        roll -= settings.repeatShare
        return chosen(trials, fit, allowed, comparisonsOnly = roll < settings.compareShare) ?: plain(allowed)
    }

    /** A plain opening hand, dealt as a shuffle would, in one of the [allowed] strata. */
    fun plain(allowed: List<Stratum>): Proposal.Rate {
        val s = allowed[random.nextInt(allowed.size)]
        val (hand, opp) = decks.deal(s, random)
        return Proposal.Rate(hand, opp, s, Reason.PLAIN)
    }

    /** The strata that may be dealt now: all, unless one has fallen behind, which then must be. */
    fun lagging(trials: List<Trial>): List<Stratum> {
        if (strata.size == 1) return strata
        val counts = strata.associateWith { s -> trials.count { it.stratum == s } }
        val least = counts.values.min()
        val most = counts.values.max()
        return if (most - least > settings.balanceSlack + trials.size / 10) strata.filter { counts[it] == least } else strata
    }

    /** A hand rated at least [PickerSettings.repeatAge] trials ago, shown again unannounced; null if none. */
    private fun repeat(trials: List<Trial>, allowed: List<Stratum>): Proposal.Rate? {
        val old = trials.subList(0, (trials.size - settings.repeatAge).coerceAtLeast(0))
            .filterIsInstance<Rated>().filter { it.stratum in allowed }
        if (old.isEmpty()) return null
        val t = old[random.nextInt(old.size)]
        return Proposal.Rate(t.hand, t.opponent, t.stratum, Reason.REPEAT)
    }

    /**
     * The candidate that teaches the most, or null when every candidate was already known. With
     * [comparisonsOnly], the best comparison (null before any hand has been rated).
     */
    fun chosen(trials: List<Trial>, fit: Fit, allowed: List<Stratum>, comparisonsOnly: Boolean = false): Proposal? {
        val targets = Targets(fit, reporter, settings, allowed)
        val reading = Reading(fit)
        val candidates = variants(trials, reading, allowed) + if (comparisonsOnly) emptyList() else dealt(reading, allowed)
        var best: Candidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in candidates) {
            val score = targets.score(c.x, c.information) ?: continue
            if (score > bestScore) { bestScore = score; best = c }
        }
        val start = best ?: return null
        val rate = start.proposal as? Proposal.Rate ?: return start.proposal
        return if (settings.climb == 0) rate else climb(rate, bestScore, targets, reading)
    }

    private class Candidate(val proposal: Proposal, val x: Sparse, val information: Double)

    /**
     * The fit's reading of the reference judge: cut-offs and precisions, and how much more a trial is expected to
     * teach than at the precision's best guess. Information grows with the precision squared; averaged over the
     * fit's doubt about the precision (sd σ on its log) that is e^(2σ²) more. A kind of trial rarely tried is
     * doubted, and so worth a try.
     */
    private inner class Reading(fit: Fit) {
        val theta = fit.theta
        val covariance = fit.covariance
        val cuts = layout.cuts(theta, 0)
        val precision = theta[layout.precision(0)]
        val comparePrecision = theta[layout.comparePrecision(0)]
        private val rateDoubt = exp(2 * fit.noiseSd[layout.precision(0)].let { it * it })
        private val compareDoubt = exp(2 * fit.noiseSd[layout.comparePrecision(0)].let { it * it })

        fun rateInformation(x: Sparse): Double = rateDoubt * Likelihood.ordinalInformation(x.dot(theta), cuts, precision)

        fun compareInformation(diff: Double): Double = compareDoubt * Likelihood.compareInformation(diff, comparePrecision)

        fun sd(x: Sparse): Double = sqrt(covariance.sparseQuadratic(x.index, x.value).coerceAtLeast(0.0))
    }

    /** Opening hands dealt as a shuffle would, less those the fit already calls with near certainty. */
    private fun dealt(r: Reading, allowed: List<Stratum>): List<Candidate> {
        val out = ArrayList<Candidate>(settings.handCandidates)
        for (i in 0 until settings.handCandidates) {
            val s = allowed[i % allowed.size]
            val (hand, opp) = decks.deal(s, random)
            val x = value.features(spec.stratumIndex(s), hand, opp)
            val chances = Likelihood.answerChances(x.dot(r.theta), r.cuts, r.precision)
            if (chances.max() > settings.skipCertainty && r.sd(x) < settings.skipSd) continue
            out += Candidate(Proposal.Rate(hand, opp, s, Reason.CHOSEN), x, r.rateInformation(x))
        }
        return out
    }

    /** One-card variants of judged hands, as comparisons against the hand they came from. */
    private fun variants(trials: List<Trial>, r: Reading, allowed: List<Stratum>): List<Candidate> {
        val judged = trials.filterIsInstance<Rated>().filter { it.stratum in allowed }
        if (judged.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>(settings.compareCandidates)
        repeat(settings.compareCandidates) {
            val t = judged[random.nextInt(judged.size)]
            val s = t.stratum
            val si = spec.stratumIndex(s)
            val give = t.hand.cards[random.nextInt(t.hand.cards.size)]
            val take = pickReplacement(decks.own(s).rest(t.hand), give) ?: return@repeat
            val variant = t.hand.swap(give, take)
            val (left, right) = if (random.nextBoolean()) t.hand to variant else variant to t.hand
            val x = value.features(si, left, t.opponent).minus(value.features(si, right, t.opponent))
            if (x.index.isEmpty()) return@repeat
            val diff = x.dot(r.theta)
            val p = Logistic.of(exp(r.comparePrecision) * diff)
            if (abs(p - 0.5) > settings.skipCertainty / 2 && r.sd(x) < settings.skipSd) return@repeat
            out += Candidate(Proposal.Compare(left, right, t.opponent, s), x, r.compareInformation(diff))
        }
        return out
    }

    /**
     * Improves a chosen hand one card at a time: each card of the hand, and of the opponent's, swapped for any card
     * still in its deck, keeping the best swap, for up to [PickerSettings.climb] rounds.
     */
    private fun climb(first: Proposal.Rate, startScore: Double, targets: Targets, r: Reading): Proposal.Rate {
        val s = first.stratum
        val si = spec.stratumIndex(s)
        var hand = first.hand
        var opp = first.opponent
        var score = startScore
        fun scoreOf(h: Hand, o: Hand?): Double {
            val x = value.features(si, h, o)
            return targets.score(x, r.rateInformation(x)) ?: Double.NEGATIVE_INFINITY
        }
        repeat(settings.climb) {
            var bestHand = hand
            var bestOpp = opp
            var best = score
            val rest = decks.own(s).rest(hand)
            for (give in hand.cards) for (take in rest.indices) {
                if (take == give || rest[take] == 0) continue
                val h = hand.swap(give, take)
                val v = scoreOf(h, opp)
                if (v > best) { best = v; bestHand = h; bestOpp = opp }
            }
            val o = opp
            val theirs = decks.theirs(s)
            if (o != null && theirs != null) {
                val restO = theirs.rest(o)
                for (give in o.cards) for (take in restO.indices) {
                    if (take == give || restO[take] == 0) continue
                    val oh = o.swap(give, take)
                    val v = scoreOf(hand, oh)
                    if (v > best) { best = v; bestHand = hand; bestOpp = oh }
                }
            }
            if (best <= score) return Proposal.Rate(hand, opp, s, Reason.CHOSEN)
            hand = bestHand; opp = bestOpp; score = best
        }
        return Proposal.Rate(hand, opp, s, Reason.CHOSEN)
    }

    /** A card from the rest of the deck other than [give], by its copies there; null if there is none. */
    private fun pickReplacement(rest: IntArray, give: Int): Int? {
        val total = rest.indices.sumOf { if (it == give) 0 else rest[it] }
        if (total == 0) return null
        var r = random.nextInt(total)
        for (c in rest.indices) {
            if (c == give) continue
            r -= rest[c]
            if (r < 0) return c
        }
        return null
    }
}
