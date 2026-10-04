package com.kaiharimoto.mastertool.core.shootout.model

import kotlin.random.Random

/**
 * A hand as copies per card, over one deck's cards numbered 0 until [universe] (Phase S §2).
 *
 * The model reads copies, not an order, and two hands with the same copies are the same hand: a repeat shown
 * again is recognised by equality.
 */
class Hand(counts: IntArray) {

    private val counts: IntArray = counts.copyOf()

    init {
        require(this.counts.all { it >= 0 }) { "a hand cannot hold a negative number of copies" }
    }

    /** How many different cards the deck's numbering has, held or not. */
    val universe: Int get() = counts.size

    /** How many cards are in the hand. */
    val size: Int = this.counts.sum()

    /** The cards held, each once, in ascending order: what the model's value walks. */
    val cards: IntArray = this.counts.indices.filter { this.counts[it] > 0 }.toIntArray()

    /** Copies of [card] held. */
    operator fun get(card: Int): Int = counts[card]

    fun has(card: Int): Boolean = counts[card] > 0

    /** This hand with one copy of [out] given up for one of [into]: a comparison's one-card variant. */
    fun swap(out: Int, into: Int): Hand {
        require(counts[out] > 0) { "the hand holds no copy of card $out to give up" }
        val c = counts.copyOf()
        c[out]--
        c[into]++
        return Hand(c)
    }

    /** The copies, as a fresh array the caller may change. */
    fun toCounts(): IntArray = counts.copyOf()

    override fun equals(other: Any?): Boolean = other is Hand && other.counts.contentEquals(counts)

    override fun hashCode(): Int = counts.contentHashCode()

    override fun toString(): String =
        cards.joinToString(prefix = "Hand(", postfix = ")") { c -> if (counts[c] == 1) "$c" else "$c×${counts[c]}" }

    companion object {
        /** A hand over [universe] cards holding one copy per mention in [cards]. */
        fun of(universe: Int, vararg cards: Int): Hand =
            Hand(IntArray(universe).also { c -> cards.forEach { c[it]++ } })
    }
}

/**
 * A deck as copies per card, over the same numbering as its hands. Siding changes the copies, never the numbering,
 * so a sided deck and its game-one deck share the model's cards (Phase S §1½).
 */
class DeckList(copies: IntArray) {

    private val copies: IntArray = copies.copyOf()

    /** How many different cards the numbering has. */
    val universe: Int get() = copies.size

    /** How many cards are in the deck. */
    val size: Int = this.copies.sum()

    /** Copies of [card] in the deck. */
    operator fun get(card: Int): Int = copies[card]

    /**
     * An opening hand of [n] dealt as a real shuffle would: every set of [n] cards from the deck equally likely, so
     * a three-of turns up as often as a three-of does.
     */
    fun draw(n: Int, random: Random): Hand {
        require(n in 0..size) { "cannot deal $n from a deck of $size" }
        val pile = IntArray(size)
        var at = 0
        for (c in copies.indices) repeat(copies[c]) { pile[at++] = c }
        val hand = IntArray(universe)
        for (i in 0 until n) {
            val j = i + random.nextInt(size - i)
            val t = pile[i]; pile[i] = pile[j]; pile[j] = t
            hand[pile[i]]++
        }
        return Hand(hand)
    }

    /** The cards still in the deck once [hand] is dealt: what could have been drawn in a card's place. */
    fun rest(hand: Hand): IntArray = IntArray(universe) { (copies[it] - hand[it]).coerceAtLeast(0) }

    /**
     * The exact chance each card is in an opening hand of [n] (hypergeometric), the weight the picker gives a card
     * so rare cards do not soak up trials (Phase S §3).
     */
    fun drawShare(n: Int): DoubleArray = DoubleArray(universe) { c -> 1.0 - noneOf(copies[c], n) }

    /** The chance an [n]-card hand holds both [a] and [b]: a pair's weight. */
    fun bothShare(a: Int, b: Int, n: Int): Double =
        1.0 - noneOf(copies[a], n) - noneOf(copies[b], n) + noneOf(copies[a] + copies[b], n)

    /** P(none of [k] copies in [n] cards dealt from [size]). */
    private fun noneOf(k: Int, n: Int): Double {
        if (k == 0) return 1.0
        if (n > size - k) return 0.0
        var p = 1.0
        for (i in 0 until n) p *= (size - k - i).toDouble() / (size - i)
        return p
    }
}
