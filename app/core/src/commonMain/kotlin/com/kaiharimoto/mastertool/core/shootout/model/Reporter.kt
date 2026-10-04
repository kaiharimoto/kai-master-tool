package com.kaiharimoto.mastertool.core.shootout.model

import com.kaiharimoto.mastertool.core.shootout.math.Logistic

/**
 * Reads the numbers a person is shown out of a fit (Phase S §2): each card's worth, each pair's, and the real-world
 * win rate, per stratum, all in points of win chance and averaged over hands dealt as a real shuffle would.
 *
 * A card's worth is a contrast: the hand as dealt against the same hand with that copy given up for whatever the
 * rest of the deck would have dealt instead. That is the question a deck builder asks ("is this slot better than
 * the card it replaces?"), and it is the one number the trials pin down: a shift of every card's value at once is
 * indistinguishable from the stratum's starting point, and cancels in a contrast.
 */
class Reporter(val spec: ModelSpec, val decks: Decks, poolSize: Int = 400, seed: Long = 1L) {

    private val value = HandValue(spec)
    private val layout = spec.layout

    /** The hands each stratum's numbers are averaged over. */
    val pools: Map<Stratum, Pool> =
        spec.strata.associateWith { Pool.deal(decks, it, poolSize, seed * 31 + it.ordinal) }

    /** The chance each card is in an opening hand, per stratum: the weight a card's rating carries. */
    fun drawShare(stratum: Stratum): DoubleArray = decks.own(stratum).drawShare(stratum.handSize)

    /** The chance an opening hand holds both cards of pair number [p]. */
    fun bothShare(stratum: Stratum, p: Int): Double =
        decks.own(stratum).bothShare(spec.pairs[p].a, spec.pairs[p].b, stratum.handSize)

    /** Every reported number at [theta], each with its gradient. */
    fun contrasts(theta: DoubleArray): List<Contrast> = spec.strata.flatMap { contrasts(theta, it) }

    /** The reported numbers of one stratum at [theta]. */
    fun contrasts(theta: DoubleArray, stratum: Stratum): List<Contrast> {
        val s = spec.stratumIndex(stratum)
        val pool = pools.getValue(stratum)
        val deck = decks.own(stratum)
        val n = layout.size
        val cardValue = DoubleArray(spec.cards)
        val cardGrad = Array(spec.cards) { DoubleArray(n) }
        val cardHeld = IntArray(spec.cards)
        val pairValue = DoubleArray(spec.pairs.size)
        val pairGrad = Array(spec.pairs.size) { DoubleArray(n) }
        val pairHeld = IntArray(spec.pairs.size)
        var winValue = 0.0
        val winGrad = DoubleArray(n)

        for (i in 0 until pool.size) {
            val hand = pool.hands[i]
            val opp = pool.opponents[i]
            val eta = value.of(theta, s, hand, opp)
            val w0 = Logistic.of(eta)
            val slope0 = Logistic.slope(eta)
            winValue += w0
            value.addFeatures(winGrad, slope0, s, hand, opp)

            val rest = deck.rest(hand)
            val restSize = rest.sum().toDouble()
            for (c in hand.cards) {
                // E over the card the deck would have dealt instead.
                var replaced = 0.0
                var slopeSum = 0.0
                for (x in rest.indices) {
                    if (rest[x] == 0) continue
                    val q = rest[x] / restSize
                    val etaX = value.swapped(theta, s, hand, eta, c, x)
                    replaced += q * Logistic.of(etaX)
                    val sx = q * Logistic.slope(etaX)
                    slopeSum += sx
                    value.addSwapFeatures(cardGrad[c], -sx, s, hand, c, x)
                }
                cardValue[c] += w0 - replaced
                value.addFeatures(cardGrad[c], slope0 - slopeSum, s, hand, opp)
                cardHeld[c]++
            }
            for (p in spec.pairs.indices) {
                val pair = spec.pairs[p]
                if (!(hand.has(pair.a) && hand.has(pair.b))) continue
                val bonus = theta[layout.pair(p)]
                val slopeWithout = Logistic.slope(eta - bonus)
                pairValue[p] += w0 - Logistic.of(eta - bonus)
                value.addFeatures(pairGrad[p], slope0 - slopeWithout, s, hand, opp)
                pairGrad[p][layout.pair(p)] += slopeWithout
                pairHeld[p]++
            }
        }

        val out = ArrayList<Contrast>(spec.cards + spec.pairs.size + 1)
        for (c in 0 until spec.cards) {
            val k = cardHeld[c]
            out += Contrast(Target.Card(c, stratum), points(cardValue[c], k), scaled(cardGrad[c], k))
        }
        for (p in spec.pairs.indices) {
            val k = pairHeld[p]
            out += Contrast(Target.Pair(p, stratum), points(pairValue[p], k), scaled(pairGrad[p], k))
        }
        out += Contrast(Target.WinRate(stratum), points(winValue, pool.size), scaled(winGrad, pool.size))
        return out
    }

    /** Every number with its range under [fit]; [trials] count the trials behind each pair. */
    fun ratings(fit: Fit, trials: List<Trial>): Ratings {
        val all = contrasts(fit.theta)
        val cards = ArrayList<CardRating>()
        val pairs = ArrayList<PairRating>()
        val rates = LinkedHashMap<Stratum, Estimate>()
        val shares = spec.strata.associateWith { drawShare(it) }
        for (c in all) {
            val estimate = Estimate(c.value, c.sd(fit))
            when (val t = c.target) {
                is Target.Card -> cards += CardRating(t.card, t.stratum, estimate, shares.getValue(t.stratum)[t.card])
                is Target.Pair -> pairs += PairRating(spec.pairs[t.pair], t.stratum, estimate, backing(trials, t))
                is Target.WinRate -> rates[t.stratum] = estimate
            }
        }
        return Ratings(cards, pairs, rates)
    }

    private fun backing(trials: List<Trial>, t: Target.Pair): Int {
        val pair = spec.pairs[t.pair]
        fun holds(h: Hand) = h.has(pair.a) && h.has(pair.b)
        return trials.count { tr ->
            tr.stratum == t.stratum && when (tr) {
                is Rated -> holds(tr.hand)
                is Compared -> holds(tr.left) || holds(tr.right)
            }
        }
    }

    /** A mean over [k] hands, in points; nothing held is nothing known, reported as 0. */
    private fun points(sum: Double, k: Int): Double = if (k == 0) 0.0 else 100.0 * sum / k

    private fun scaled(g: DoubleArray, k: Int): DoubleArray {
        if (k == 0) return DoubleArray(g.size)
        val f = 100.0 / k
        for (i in g.indices) g[i] *= f
        return g
    }
}
