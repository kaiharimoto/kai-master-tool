package com.kaiharimoto.mastertool.core.hand

import com.kaiharimoto.mastertool.core.deck.LensKeying

/**
 * What a lens's reading is worth in the opening hand.
 *
 * The lens says twelve of these forty cards are starters. The question a
 * deckbuilder is actually asking is the next one — *and how often do I see
 * one?* — and until now the two lived apart: the deck drew the partition, and
 * a separate sheet asked you to re-describe that same partition by hand before
 * it would answer. Both halves were already written. This is the joint.
 *
 * Exact, not simulated, for the same reason [HandOdds] is: a deck is at most
 * sixty cards and a hand at most six, so the honest number is cheap.
 */
object LensOdds {

    /** Hands a deck is quoted at: five going first, six going second (the turn's draw made). */
    const val DEFAULT_HAND = 5

    /**
     * The chance of opening at least one card of each key, keyed by key id.
     *
     * One independent question per key rather than one joint question over all
     * of them — "do I see a starter" and "do I see a handtrap" are two things
     * a player wants to know at once, and the *joint* probability of both is a
     * different question, which is what a stated goal is for.
     */
    fun atLeastOne(
        keying: LensKeying,
        deckSize: Int,
        handSize: Int = DEFAULT_HAND,
    ): Map<String, Double> {
        if (deckSize <= 0 || keying.keys.isEmpty()) return emptyMap()

        val sizes = sizesOf(keying)

        return keying.keys.associate { key ->
            key.id to HandOdds.probability(
                groupSizes = sizes,
                deckSize = deckSize,
                handSize = handSize,
                query = HandQuery(listOf(HandConstraint(key.id, min = 1, max = handSize))),
            )
        }
    }

    /**
     * How many cards of the section each key holds.
     *
     * Counted off the keying rather than off the deck, so it is the same count
     * the bar shows and the same count the deck is drawn with — a number that
     * disagrees with the blocks under it is worse than no number.
     */
    fun sizesOf(keying: LensKeying): Map<String, Int> =
        keying.keys.associate { it.id to keying.countOf(it.id) }
}
