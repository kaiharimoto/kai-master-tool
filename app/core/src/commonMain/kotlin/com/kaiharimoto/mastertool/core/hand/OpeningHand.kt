package com.kaiharimoto.mastertool.core.hand

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.random.Random

/**
 * One goldfish session: the main deck shuffled, hands dealt, mulligans taken.
 *
 * Pure and seeded so an open can be replayed exactly — the same seed deals the
 * same cards, which is what would make "show me that hand again" possible and
 * is what makes the tests possible. The state machine is deliberately tiny:
 * shuffle, draw, mulligan (sweep the hand back, reshuffle what remains unseen
 * plus the swept cards, draw again), and draw-one for turns beyond the opener.
 *
 * **Nothing in the app calls this yet.** There is no goldfish surface in the
 * builder: the consistency question is answered exactly by [HandOdds] rather
 * than sampled, so a dealt hand would be an illustration of a number the app
 * already knows. It is kept, tested, because a scrubbable practice hand is a
 * queued piece of work (`docs/TABLE.md` §5) and this is the half of it that is
 * hard to get right. Until then, do not describe it in the README as a feature.
 */
data class OpeningHand(
    val seed: Long,
    /** The shuffled order; index 0 is the top of the deck. */
    val library: List<CardId>,
    /** Cards drawn, in draw order. */
    val hand: List<CardId>,
    /** How many cards the library has given out. */
    val drawn: Int,
    val mulligans: Int,
) {
    val remaining: List<CardId> get() = library.drop(drawn)

    companion object {
        /** Fisher–Yates over [deck] with [seed]; deals [handSize] immediately. */
        fun deal(deck: List<CardId>, seed: Long, handSize: Int): OpeningHand {
            val shuffled = deck.toMutableList()
            val random = Random(seed)
            for (i in shuffled.indices.reversed()) {
                val j = random.nextInt(i + 1)
                val tmp = shuffled[i]
                shuffled[i] = shuffled[j]
                shuffled[j] = tmp
            }
            val take = handSize.coerceIn(0, shuffled.size)
            return OpeningHand(
                seed = seed,
                library = shuffled,
                hand = shuffled.take(take),
                drawn = take,
                mulligans = 0,
            )
        }
    }

    /** One more card off the top, for playing past the opener. */
    fun drawOne(): OpeningHand {
        if (drawn >= library.size) return this
        return copy(hand = hand + library[drawn], drawn = drawn + 1)
    }

    /**
     * Sweep the hand back and deal a fresh one from a reshuffle of everything
     * not yet seen plus the swept cards. Derives its randomness from the seed
     * and the mulligan count, so the whole session stays replayable from
     * `(deck, seed)` alone.
     */
    fun mulligan(handSize: Int): OpeningHand {
        val pool = hand + remaining
        val next = deal(pool, seed = seed + mulligans + 1, handSize = handSize)
        return next.copy(mulligans = mulligans + 1)
    }

    /**
     * Copies of each group in the current hand.
     *
     * For a readout beside a dealt hand, when there is one. There is no such
     * chip today — see the note on the class.
     */
    fun tally(groups: DeckGroups): Map<String?, Int> =
        hand.groupingBy { groups.assignments[it] }.eachCount()
}
