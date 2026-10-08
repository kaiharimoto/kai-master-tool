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
 * - each card in the opening five, its worth across the matchup plus its stratum's deviation, times its copies' worth
 *   (each copy past the first a declining fraction, [ModelSpec.copies]);
 * - going second, the turn's draw, by **its own worth as the draw** ([Layout.drawn]), times what one more copy adds:
 *   a drawn card's answers teach its worth as the draw and never its worth in the five (2026-10, kai);
 * - each named pair held (the draw too: a combo's second piece drawn for the turn still makes it), its extra;
 * - less each of the opponent's cards held, by the same copy rule.
 *
 * A hand of six that does not name its draw (kept before the draw was) is read as each of its cards the draw by its
 * share of the hand — what the person was shown, a uniform sixth, averaged.
 *
 * The value is linear in the parameters, which is what lets the fit stay a few small matrices.
 */
class HandValue(private val spec: ModelSpec) {

    private val layout = spec.layout
    private val hasDeviations = layout.deviationsPerCard > 0
    private val second = BooleanArray(spec.strata.size) { !spec.strata[it].goingFirst }

    /** The value of [hand] against [opponent] in stratum number [s] under [theta]. */
    fun of(theta: DoubleArray, s: Int, hand: Hand, opponent: Hand?): Double {
        var v = theta[layout.intercept(s)] + own(theta, s, hand)
        if (opponent != null) for (o in opponent.cards) v -= spec.copies(opponent[o]) * theta[layout.opponent(o)]
        return v
    }

    /** Adds [scale] times the value's gradient (its features) into [target], without building them. */
    fun addFeatures(target: DoubleArray, scale: Double, s: Int, hand: Hand, opponent: Hand?) {
        target[layout.intercept(s)] += scale
        walk(s, hand) { i, x -> target[i] += scale * x }
        if (opponent != null) for (o in opponent.cards) target[layout.opponent(o)] -= scale * spec.copies(opponent[o])
    }

    /**
     * The value of [other] — [hand] with one card changed, against the same opponent — from the hand's own value [base]:
     * what the reports ask hundreds of times per card, so it reads only your own cards.
     */
    fun swapped(theta: DoubleArray, s: Int, hand: Hand, base: Double, other: Hand): Double =
        if (other == hand) base else base + own(theta, s, other) - own(theta, s, hand)

    /** Adds [scale] times what [swapped] changes in the features into [target]. */
    fun addSwapFeatures(target: DoubleArray, scale: Double, s: Int, hand: Hand, other: Hand) {
        if (other == hand) return
        walk(s, other) { i, x -> target[i] += scale * x }
        walk(s, hand) { i, x -> target[i] -= scale * x }
    }

    /** Your own cards' part of the value: the five, the draw and the pairs. */
    private fun own(theta: DoubleArray, s: Int, hand: Hand): Double {
        var v = 0.0
        walk(s, hand) { i, x -> v += x * theta[i] }
        return v
    }

    /**
     * Each parameter your cards read and how much, perhaps more than once: the draw named, none (going first, or five
     * cards), or — a hand of six going second that does not name it — each card the draw by its share of the hand.
     */
    private inline fun walk(s: Int, hand: Hand, emit: (Int, Double) -> Unit) {
        if (!second[s] || hand.size <= Hand.OPENING) {
            reading(s, hand, Hand.NONE, 1.0, emit)
        } else if (hand.draw != Hand.NONE) {
            reading(s, hand, hand.draw, 1.0, emit)
        } else {
            for (d in hand.cards) reading(s, hand, d, hand[d].toDouble() / hand.size, emit)
        }
    }

    /** The parameters read when [draw] is the turn's draw, weighted [w]. */
    private inline fun reading(s: Int, hand: Hand, draw: Int, w: Double, emit: (Int, Double) -> Unit) {
        for (c in hand.cards) {
            val opened = hand[c] - (if (c == draw) 1 else 0)
            if (opened == 0) continue
            val x = w * spec.copies(opened)
            emit(layout.card(c), x)
            if (hasDeviations) emit(layout.deviation(c, s), x)
        }
        if (draw != Hand.NONE) {
            val before = hand[draw] - 1
            emit(layout.drawn(draw), w * (spec.copies(before + 1) - spec.copies(before)))
        }
        // A pair is both cards in hand by your turn, the draw too: drawing the second piece still makes the combo.
        for (p in spec.pairs.indices) {
            val pair = spec.pairs[p]
            if (hand.has(pair.a) && hand.has(pair.b)) emit(layout.pair(p), w)
        }
    }

    /**
     * The value's features, sorted by parameter, as [judge] reads the hand: a judge other than the reference adds its own
     * reading of each card held ([Layout.judgeCard]), so its blind spots are its own (stage 3).
     */
    fun features(s: Int, hand: Hand, opponent: Hand?, judge: Int = 0): Sparse {
        val at = HashMap<Int, Double>(4 * hand.cards.size + 8)
        fun add(i: Int, x: Double) { at[i] = (at[i] ?: 0.0) + x }
        walk(s, hand) { i, x -> add(i, x) }
        if (opponent != null) for (o in opponent.cards) add(layout.opponent(o), -spec.copies(opponent[o]))
        add(layout.intercept(s), 1.0)
        if (judge > 0) for (c in hand.cards) add(layout.judgeCard(judge, c), spec.copies(hand[c]))
        val idx = at.keys.filter { at.getValue(it) != 0.0 }.sorted()
        return Sparse(idx.toIntArray(), DoubleArray(idx.size) { at.getValue(idx[it]) })
    }
}
