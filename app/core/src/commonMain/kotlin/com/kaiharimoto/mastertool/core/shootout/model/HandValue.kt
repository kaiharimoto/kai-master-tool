package com.kaiharimoto.mastertool.core.shootout.model

/**
 * The parameters one hand's value reads and how much of each, sorted by parameter: what the fit multiplies by
 * and what the picker measures a trial by.
 */
class Sparse(val index: IntArray, val value: DoubleArray) {

    /** The value these features give under [theta]. */
    fun dot(theta: DoubleArray): Double {
        var s = 0.0
        for (i in index.indices) s += value[i] * theta[index[i]]
        return s
    }

    /** These features less [other]'s: a comparison reads only where its two hands differ. */
    fun minus(other: Sparse): Sparse {
        val idx = IntArray(index.size + other.index.size)
        val v = DoubleArray(idx.size)
        var i = 0; var j = 0; var n = 0
        while (i < index.size || j < other.index.size) {
            val a = if (i < index.size) index[i] else Int.MAX_VALUE
            val b = if (j < other.index.size) other.index[j] else Int.MAX_VALUE
            val (at, x) = when {
                a < b -> a to value[i++]
                b < a -> b to -other.value[j++]
                else -> a to (value[i++] - other.value[j++])
            }
            if (x != 0.0) { idx[n] = at; v[n] = x; n++ }
        }
        return Sparse(idx.copyOf(n), v.copyOf(n))
    }
}

/**
 * A hand's value (Phase S §2), as log-odds of a win:
 * - the stratum's starting point;
 * - each card held, its worth across the matchup plus its stratum's deviation, times its copies' worth (each
 *   copy past the first a declining fraction, [ModelSpec.copies]);
 * - each named pair held, its extra;
 * - less each of the opponent's cards held, by the same copy rule.
 *
 * The value is linear in the parameters, which is what lets the fit stay a few small matrices.
 */
class HandValue(private val spec: ModelSpec) {

    private val layout = spec.layout
    private val hasDeviations = layout.deviationsPerCard > 0

    /** The value of [hand] against [opponent] in stratum number [s] under [theta]. */
    fun of(theta: DoubleArray, s: Int, hand: Hand, opponent: Hand?): Double {
        var v = theta[layout.intercept(s)]
        for (c in hand.cards) {
            var w = theta[layout.card(c)]
            if (hasDeviations) w += theta[layout.deviation(c, s)]
            v += spec.copies(hand[c]) * w
        }
        for (p in spec.pairs.indices) {
            val pair = spec.pairs[p]
            if (hand.has(pair.a) && hand.has(pair.b)) v += theta[layout.pair(p)]
        }
        if (opponent != null) for (o in opponent.cards) v -= spec.copies(opponent[o]) * theta[layout.opponent(o)]
        return v
    }

    /** Adds [scale] times the value's gradient (its features) into [target], without building them. */
    fun addFeatures(target: DoubleArray, scale: Double, s: Int, hand: Hand, opponent: Hand?) {
        target[layout.intercept(s)] += scale
        for (c in hand.cards) {
            val w = scale * spec.copies(hand[c])
            target[layout.card(c)] += w
            if (hasDeviations) target[layout.deviation(c, s)] += w
        }
        for (p in spec.pairs.indices) {
            val pair = spec.pairs[p]
            if (hand.has(pair.a) && hand.has(pair.b)) target[layout.pair(p)] += scale
        }
        if (opponent != null) for (o in opponent.cards) target[layout.opponent(o)] -= scale * spec.copies(opponent[o])
    }

    /**
     * The value of [hand] with one copy of [out] given up for one of [into], from the hand's own value [base]:
     * what the reports ask hundreds of times per card, so it touches only what the swap changes.
     */
    fun swapped(theta: DoubleArray, s: Int, hand: Hand, base: Double, out: Int, into: Int): Double {
        if (out == into) return base
        var v = base
        v += (spec.copies(hand[out] - 1) - spec.copies(hand[out])) * cardWorth(theta, out, s)
        v += (spec.copies(hand[into] + 1) - spec.copies(hand[into])) * cardWorth(theta, into, s)
        for (p in spec.pairs.indices) {
            val change = pairChange(hand, spec.pairs[p], out, into)
            if (change != 0) v += change * theta[layout.pair(p)]
        }
        return v
    }

    /** Adds [scale] times what [swapped] changes in the features into [target]. */
    fun addSwapFeatures(target: DoubleArray, scale: Double, s: Int, hand: Hand, out: Int, into: Int) {
        if (out == into) return
        val wOut = scale * (spec.copies(hand[out] - 1) - spec.copies(hand[out]))
        val wIn = scale * (spec.copies(hand[into] + 1) - spec.copies(hand[into]))
        target[layout.card(out)] += wOut
        target[layout.card(into)] += wIn
        if (hasDeviations) {
            target[layout.deviation(out, s)] += wOut
            target[layout.deviation(into, s)] += wIn
        }
        for (p in spec.pairs.indices) {
            val change = pairChange(hand, spec.pairs[p], out, into)
            if (change != 0) target[layout.pair(p)] += scale * change
        }
    }

    private fun cardWorth(theta: DoubleArray, c: Int, s: Int): Double =
        theta[layout.card(c)] + if (hasDeviations) theta[layout.deviation(c, s)] else 0.0

    /** −1, 0 or +1: whether the swap breaks the pair, leaves it, or completes it. */
    private fun pairChange(hand: Hand, pair: CardPair, out: Int, into: Int): Int {
        fun after(c: Int) = hand[c] - (if (c == out) 1 else 0) + (if (c == into) 1 else 0)
        val before = hand.has(pair.a) && hand.has(pair.b)
        val now = after(pair.a) > 0 && after(pair.b) > 0
        return (if (now) 1 else 0) - (if (before) 1 else 0)
    }

    /**
     * The value's features, sorted by parameter, as [judge] reads the hand: a judge other than the reference adds its own
     * reading of each card held ([Layout.judgeCard]), so its blind spots are its own (stage 3).
     */
    fun features(s: Int, hand: Hand, opponent: Hand?, judge: Int = 0): Sparse {
        val idx = ArrayList<Int>(2 * hand.cards.size + 8)
        val v = ArrayList<Double>(idx.size)
        for (c in hand.cards) { idx += layout.card(c); v += spec.copies(hand[c]) }
        if (hasDeviations) for (c in hand.cards) { idx += layout.deviation(c, s); v += spec.copies(hand[c]) }
        for (p in spec.pairs.indices) {
            val pair = spec.pairs[p]
            if (hand.has(pair.a) && hand.has(pair.b)) { idx += layout.pair(p); v += 1.0 }
        }
        if (opponent != null) for (o in opponent.cards) { idx += layout.opponent(o); v += -spec.copies(opponent[o]) }
        idx += layout.intercept(s); v += 1.0
        if (judge > 0) for (c in hand.cards) { idx += layout.judgeCard(judge, c); v += spec.copies(hand[c]) }
        return Sparse(idx.toIntArray(), v.toDoubleArray())
    }
}
