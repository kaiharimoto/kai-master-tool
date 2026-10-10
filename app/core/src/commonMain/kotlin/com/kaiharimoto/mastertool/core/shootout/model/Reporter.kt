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
 *
 * Going second a card has two numbers: in the opening five, and as the turn's draw — each read only off the hands it
 * stands in that way, so the draw never muddies the five (2026-10, kai).
 */
class Reporter(val spec: ModelSpec, val decks: Decks, poolSize: Int = 400, seed: Long = 1L) {

    private val value = HandValue(spec)
    private val layout = spec.layout

    /** The hands each stratum's numbers are averaged over. */
    val pools: Map<Stratum, Pool> =
        spec.strata.associateWith { Pool.deal(decks, it, poolSize, seed * 31 + it.ordinal) }

    /**
     * The chance each card is in the opening five, per stratum: the weight a card's rating carries. Going second the
     * turn's draw is rated apart ([drawnShare]), so this is the five's chance too.
     */
    fun drawShare(stratum: Stratum): DoubleArray = decks.own(stratum).drawShare(Hand.OPENING)

    /** The chance each card is the turn's draw, going second; none going first. */
    fun drawnShare(stratum: Stratum): DoubleArray =
        if (stratum.goingFirst) DoubleArray(spec.cards) else decks.own(stratum).drawnShare()

    /** The chance a hand holds both cards of pair number [p] by your turn. */
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
        val drawnValue = DoubleArray(spec.cards)
        val drawnGrad = Array(spec.cards) { DoubleArray(n) }
        val drawnHeld = IntArray(spec.cards)
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
            /** E over the card the deck would have dealt instead of the one [other] gives up. */
            fun contrast(grad: DoubleArray, other: (Int) -> Hand): Double {
                var replaced = 0.0
                var slopeSum = 0.0
                for (x in rest.indices) {
                    if (rest[x] == 0) continue
                    val q = rest[x] / restSize
                    val h = other(x)
                    val etaX = value.swapped(theta, s, hand, eta, h)
                    replaced += q * Logistic.of(etaX)
                    val sx = q * Logistic.slope(etaX)
                    slopeSum += sx
                    value.addSwapFeatures(grad, -sx, s, hand, h)
                }
                value.addFeatures(grad, slope0 - slopeSum, s, hand, opp)
                return w0 - replaced
            }
            // A card in the opening five, and the turn's draw, are rated apart (2026-10, kai).
            for (c in hand.cards) {
                if (hand.opened(c) == 0) continue
                cardValue[c] += contrast(cardGrad[c]) { x -> hand.swap(c, x) }
                cardHeld[c]++
            }
            val d = hand.draw
            if (d != Hand.NONE && !stratum.goingFirst) {
                drawnValue[d] += contrast(drawnGrad[d]) { x -> hand.swapDraw(x) }
                drawnHeld[d]++
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
        if (!stratum.goingFirst) for (c in 0 until spec.cards) {
            val k = drawnHeld[c]
            out += Contrast(Target.Drawn(c, stratum), points(drawnValue[c], k), scaled(drawnGrad[c], k))
        }
        for (p in spec.pairs.indices) {
            val k = pairHeld[p]
            out += Contrast(Target.Pair(p, stratum), points(pairValue[p], k), scaled(pairGrad[p], k))
        }
        out += Contrast(Target.WinRate(stratum), points(winValue, pool.size), scaled(winGrad, pool.size))
        return out
    }

    /**
     * The next copy's worth (Phase G, D2), per card of the numbering: the stratum's win rate with one more copy of the card
     * in the deck, in the place of a copy of any other card alike, less the win rate as it is — in points of win chance.
     * A card at its limit still has a number; whether the copy is allowed is the builder's to say.
     */
    fun nextCopy(theta: DoubleArray, stratum: Stratum): List<Contrast> =
        variants(theta, stratum, (0 until spec.cards).map { Change(null, it) })

    /**
     * What-if (Phase G, D2): the stratum's win rate with one copy of [Change.from] made a copy of [Change.to], less as it is,
     * in points of win chance. `from` null is a copy of any other card alike given up (one more `to`); `to` null is the copy
     * made any other card alike, as the rest of the deck stands (one fewer `from`). The deck keeps its size: Shootout's
     * hands are dealt from it.
     *
     * Read on the pool's own hands, which is what dealing both decks from the same keys gives: a hand differs only where it
     * held the copy that changed — an opened copy or the turn's draw, each as often as it is that copy — so the difference
     * is exact over the pool, with nothing dealt twice.
     */
    fun variants(theta: DoubleArray, stratum: Stratum, changes: List<Change>): List<Contrast> {
        val s = spec.stratumIndex(stratum)
        val pool = pools.getValue(stratum)
        val deck = decks.own(stratum)
        val n = layout.size
        // Each change as the copies it moves: (card given up, card it becomes, weight per held copy).
        val moves = changes.map { ch ->
            val from = ch.from
            val to = ch.to
            when {
                from != null && to != null -> if (from == to || deck[from] == 0) emptyList() else listOf(Move(from, to, 1.0 / deck[from]))
                to != null -> {
                    val others = deck.size - deck[to]
                    (0 until spec.cards).filter { it != to && deck[it] > 0 }.map { Move(it, to, 1.0 / others) }
                }
                from != null -> {
                    val others = deck.size - deck[from]
                    if (deck[from] == 0 || others == 0) emptyList()
                    else (0 until spec.cards).filter { it != from && deck[it] > 0 }.map { y -> Move(from, y, deck[y].toDouble() / others / deck[from]) }
                }
                else -> emptyList()
            }
        }
        val sums = DoubleArray(changes.size)
        val grads = Array(changes.size) { DoubleArray(n) }
        for (i in 0 until pool.size) {
            val hand = pool.hands[i]
            val opp = pool.opponents[i]
            val eta = value.of(theta, s, hand, opp)
            val w0 = Logistic.of(eta)
            val slope0 = Logistic.slope(eta)
            fun add(j: Int, w: Double, h: Hand) {
                val etaX = value.swapped(theta, s, hand, eta, h)
                val slopeX = Logistic.slope(etaX)
                sums[j] += w * (Logistic.of(etaX) - w0)
                // d(σ(ηx) − σ(η)) = (σ'(ηx) − σ'(η))·∇η + σ'(ηx)·(∇ηx − ∇η)
                value.addFeatures(grads[j], w * (slopeX - slope0), s, hand, opp)
                value.addSwapFeatures(grads[j], w * slopeX, s, hand, h)
            }
            for (j in moves.indices) for (m in moves[j]) {
                if (!hand.has(m.from)) continue
                val opened = hand.opened(m.from)
                if (opened > 0) add(j, opened * m.weight, hand.swap(m.from, m.to))
                if (hand.draw == m.from) add(j, m.weight, hand.swapDraw(m.to))
            }
        }
        return changes.indices.map { j ->
            val ch = changes[j]
            val target = if (ch.from == null && ch.to != null) Target.Next(ch.to, stratum) else Target.Variant(ch.from, ch.to, stratum)
            Contrast(target, points(sums[j], pool.size), scaled(grads[j], pool.size))
        }
    }

    /** One copy of [from] made a copy of [to]; null on either side is "any other card alike" ([variants]). */
    data class Change(val from: Int?, val to: Int?)

    private class Move(val from: Int, val to: Int, val weight: Double)

    /** Every number with its range under [fit]; [trials] count the trials behind each pair. */
    fun ratings(fit: Fit, trials: List<Trial>): Ratings {
        val all = contrasts(fit.theta)
        val cards = ArrayList<CardRating>()
        val pairs = ArrayList<PairRating>()
        val drawn = ArrayList<CardRating>()
        val rates = LinkedHashMap<Stratum, Estimate>()
        val shares = spec.strata.associateWith { drawShare(it) }
        val drawnShares = spec.strata.associateWith { drawnShare(it) }
        for (c in all) {
            val estimate = Estimate(c.value, c.sd(fit))
            when (val t = c.target) {
                is Target.Card -> cards += CardRating(t.card, t.stratum, estimate, shares.getValue(t.stratum)[t.card])
                is Target.Drawn -> drawn += CardRating(t.card, t.stratum, estimate, drawnShares.getValue(t.stratum)[t.card])
                is Target.Pair -> pairs += PairRating(spec.pairs[t.pair], t.stratum, estimate, backing(trials, t))
                is Target.WinRate -> rates[t.stratum] = estimate
                is Target.Next, is Target.Variant -> Unit
            }
        }
        return Ratings(cards, pairs, rates, drawn)
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
