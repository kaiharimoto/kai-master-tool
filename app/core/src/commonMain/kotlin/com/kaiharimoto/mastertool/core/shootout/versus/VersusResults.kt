package com.kaiharimoto.mastertool.core.shootout.versus

import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.HandValue
import com.kaiharimoto.mastertool.core.shootout.model.Pool
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One card held with the card being compared ([partner], a passcode), and what it changes (2026-10, kai: "which card is
 * better paired with certain cards"). [both] is the substitute against the card in hands that also hold the partner, every
 * effect counted; [synergy] is the part of it the partner makes — [both] less the two cards' worth on their own in the
 * same hands. Positive favours the substitute. [hands] is how many hands of the report's shuffles held both.
 */
class VersusPartner(val partner: Int, val both: Estimate, val synergy: Estimate, val hands: Int) {
    /** Whether the partner tells the two cards apart: the synergy's 95 % range excludes zero. */
    val shown: Boolean get() = synergy.excludesZero
}

/**
 * Card against card in one stratum, every number the substitute less the card in points of win chance, with its range:
 *
 * - [alone]: one copy in the opening five given up for the substitute, the same hand otherwise, with what either card
 *   makes beside another held aside — the two cards on their own.
 * - [drawn]: the same as the turn's draw, going second, rated apart from the five (2026-10, kai's decision); null going first.
 * - [partners]: beside each other card ([VersusPartner]), the most telling first.
 * - [overall]: the deck with the substitute against the deck as built, over every hand a shuffle deals, held or not
 *   ([withCard], [withSubstitute] are each deck's win chance).
 */
class VersusSide(
    val stratum: Stratum,
    val alone: Estimate,
    val aloneHands: Int,
    val drawn: Estimate?,
    val drawnHands: Int,
    val partners: List<VersusPartner>,
    val overall: Estimate,
    val withCard: Estimate,
    val withSubstitute: Estimate,
    /** The chance the card is in a hand by your turn: how often the choice matters at all. */
    val heldShare: Double,
)

/**
 * What card against card says (2026-10): the card ([card]) and its substitute ([substitute]) as passcodes, a [VersusSide]
 * per stratum dealt, and the headline numbers over those strata alike ([alone], [overall]). Read from the fit, averaged
 * over hands dealt as a real shuffle of the deck as built — each one's twin with the substitute is a real shuffle of the
 * other deck — so the hands shown never weigh the answer.
 */
class VersusResults(
    val card: Int,
    val substitute: Int,
    val sides: List<VersusSide>,
    val alone: Estimate,
    val overall: Estimate,
    /** Hands answered holding the card, and holding the substitute. */
    val withCard: Int,
    val withSubstitute: Int,
    /** Every answer the fit read. */
    val answered: Int,
) {
    /** The partners that tell the two apart in any stratum, by partner, the most telling first. */
    val telling: List<Pair<Stratum, VersusPartner>>
        get() = sides.flatMap { s -> s.partners.filter { it.shown }.map { s.stratum to it } }.sortedByDescending { abs(it.second.synergy.value) }

    companion object {
        /** Hands each stratum's numbers are averaged over: enough that a partner held one hand in fifty has sixty. */
        const val POOL = 3000

        /** The progress line's hands: only the cards on their own are read. */
        const val QUICK_POOL = 400

        /** A partner held with the card in fewer of the report's hands than this is left out: too rare to say. */
        const val MIN_PARTNER_HANDS = 20

        private const val SEED = 1L

        fun read(bench: Bench, fit: Fit, trials: List<Trial>, strata: List<Stratum>, pool: Int = POOL): VersusResults {
            val swap = requireNotNull(bench.swap)
            val spec = bench.spec
            val l = spec.layout
            val value = HandValue(spec)
            val n = l.size
            val x = swap.card
            val y = swap.substitute
            val theta = fit.theta
            // What either card makes beside another is held aside for the cards on their own: every pair here names one of them.
            val pairIndex = spec.pairs.indices.filter { p -> spec.pairs[p].let { it.a == x || it.b == x || it.a == y || it.b == y } }.map(l::pair)
            val apart = theta.copyOf().also { t -> pairIndex.forEach { t[it] = 0.0 } }
            fun estimate(sum: Double, g: DoubleArray, k: Int, dropPairs: Boolean = false): Estimate {
                if (k == 0) return Estimate(0.0, 0.0)
                if (dropPairs) pairIndex.forEach { g[it] = 0.0 }
                val f = 100.0 / k
                for (i in g.indices) g[i] *= f
                return Estimate(100.0 * sum / k, sqrt(fit.covariance.quadratic(g).coerceAtLeast(0.0)))
            }

            val sides = ArrayList<VersusSide>()
            val aloneGrads = ArrayList<Pair<Double, DoubleArray>>()
            val overallGrads = ArrayList<Pair<Double, DoubleArray>>()
            for (stratum in strata) {
                val s = spec.stratumIndex(stratum)
                if (s < 0) continue
                val hands = Pool.deal(bench.decks, stratum, pool, SEED * 31 + stratum.ordinal)
                /** σ(η(b)) − σ(η(a)) under [t], its gradient added into [g]. */
                fun diff(t: DoubleArray, a: Hand, b: Hand, opp: Hand?, g: DoubleArray): Double {
                    val ea = value.of(t, s, a, opp)
                    val eb = value.of(t, s, b, opp)
                    value.addFeatures(g, Logistic.slope(eb), s, b, opp)
                    value.addFeatures(g, -Logistic.slope(ea), s, a, opp)
                    return Logistic.of(eb) - Logistic.of(ea)
                }
                var aloneSum = 0.0; val aloneG = DoubleArray(n); var aloneK = 0
                var drawnSum = 0.0; val drawnG = DoubleArray(n); var drawnK = 0
                var winA = 0.0; val winAG = DoubleArray(n)
                var winB = 0.0; val winBG = DoubleArray(n)
                var overSum = 0.0; val overG = DoubleArray(n)
                var held = 0
                val bothSum = HashMap<Int, Double>(); val bothG = HashMap<Int, DoubleArray>()
                val apartSum = HashMap<Int, Double>(); val apartG = HashMap<Int, DoubleArray>()
                val partnerK = HashMap<Int, Int>()
                for (i in 0 until hands.size) {
                    val hand = hands.hands[i]
                    val opp = hands.opponents[i]
                    val twin = swap.twin(hand)
                    val ea = value.of(theta, s, hand, opp)
                    val eb = value.of(theta, s, twin, opp)
                    winA += Logistic.of(ea); value.addFeatures(winAG, Logistic.slope(ea), s, hand, opp)
                    winB += Logistic.of(eb); value.addFeatures(winBG, Logistic.slope(eb), s, twin, opp)
                    overSum += diff(theta, hand, twin, opp, overG)
                    if (!hand.has(x)) continue
                    held++
                    if (hand.opened(x) > 0) {
                        aloneSum += diff(apart, hand, hand.swap(x, y), opp, aloneG)
                        aloneK++
                    }
                    if (!stratum.goingFirst && hand.draw == x) {
                        drawnSum += diff(apart, hand, hand.swapDraw(y), opp, drawnG)
                        drawnK++
                    }
                    // Beside each card held with it: everything counted, and the cards on their own, so the partner's part is the gap.
                    val one = hand.swap(x, y)
                    for (c in hand.cards) {
                        if (c == x || c == y) continue
                        bothSum[c] = (bothSum[c] ?: 0.0) + diff(theta, hand, one, opp, bothG.getOrPut(c) { DoubleArray(n) })
                        apartSum[c] = (apartSum[c] ?: 0.0) + diff(apart, hand, one, opp, apartG.getOrPut(c) { DoubleArray(n) })
                        partnerK[c] = (partnerK[c] ?: 0) + 1
                    }
                }
                val partners = partnerK.filter { it.value >= MIN_PARTNER_HANDS }.map { (c, k) ->
                    val gBoth = bothG.getValue(c)
                    val gApart = apartG.getValue(c).also { g -> pairIndex.forEach { g[it] = 0.0 } }
                    val gSyn = DoubleArray(n) { gBoth[it] - gApart[it] }
                    val synergy = estimate(bothSum.getValue(c) - apartSum.getValue(c), gSyn, k)
                    VersusPartner(bench.own[c], estimate(bothSum.getValue(c), gBoth, k), synergy, k)
                }.sortedByDescending { abs(it.synergy.value) / (it.synergy.sd + 1e-9) }
                aloneGrads += (if (aloneK == 0) 0.0 else aloneSum / aloneK) to aloneG.copyOf().also { g -> pairIndex.forEach { g[it] = 0.0 }; if (aloneK > 0) for (j in g.indices) g[j] /= aloneK }
                overallGrads += overSum / hands.size to overG.copyOf().also { g -> for (j in g.indices) g[j] /= hands.size }
                sides += VersusSide(
                    stratum = stratum,
                    alone = estimate(aloneSum, aloneG, aloneK, dropPairs = true),
                    aloneHands = aloneK,
                    drawn = if (stratum.goingFirst) null else estimate(drawnSum, drawnG, drawnK, dropPairs = true),
                    drawnHands = drawnK,
                    partners = partners,
                    overall = estimate(overSum, overG, hands.size),
                    withCard = estimate(winA, winAG, hands.size),
                    withSubstitute = estimate(winB, winBG, hands.size),
                    heldShare = held.toDouble() / hands.size,
                )
            }
            fun mean(parts: List<Pair<Double, DoubleArray>>): Estimate {
                if (parts.isEmpty()) return Estimate(0.0, 0.0)
                val g = DoubleArray(n)
                parts.forEach { (_, pg) -> for (j in g.indices) g[j] += 100.0 * pg[j] / parts.size }
                return Estimate(100.0 * parts.sumOf { it.first } / parts.size, sqrt(fit.covariance.quadratic(g).coerceAtLeast(0.0)))
            }
            val rated = trials.filterIsInstance<Rated>().filter { it.stratum in strata }
            return VersusResults(
                card = bench.own[x],
                substitute = bench.own[y],
                sides = sides,
                alone = mean(aloneGrads),
                overall = mean(overallGrads),
                withCard = rated.count { it.hand.has(x) },
                withSubstitute = rated.count { it.hand.has(y) },
                answered = trials.size,
            )
        }
    }
}
