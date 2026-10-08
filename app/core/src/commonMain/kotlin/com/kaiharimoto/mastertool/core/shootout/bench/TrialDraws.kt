package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import kotlin.random.Random

/**
 * What a trial shows beyond its hands (1.1.5, kai: "The sixth card should be marked as their top deck … allow the user to
 * simulate drawing from a deck for either the user or the opponent").
 *
 * - **The turn's draw.** A hand of six is the player going second's: five dealt, one drawn for their turn. The sixth is
 *   marked, because it is not in hand on the other player's first turn — a hand trap drawn for the turn stops nothing
 *   before it. Which of the six it is comes from a shuffle, so it is a uniform choice, seeded by the trial.
 * - **Draws by card effects.** Mulcharmies and other draw and consistency cards make the next cards matter: the person may
 *   turn them up, for either side, off the top of that side's deck in the trial's stratum (sided decks in sided games).
 *   Each further draw is the next card of one seeded shuffle, so asking again shows the same cards.
 * - **The top card is the draw's until an effect takes it** (1.1.7, kai: "If a card draws for effect, it would draw the
 *   6th card, and the next card would be the next top card"). For the player going second the marked sixth is the top of
 *   their deck, so their first draw by an effect is that card, and the turn's draw moves down to the card under the last
 *   one taken ([shown]). The cards in hand once the turn is drawn are the same six and more either way; what changes is
 *   which arrived when, and a hand trap drawn by an effect on the other player's turn is there to use.
 *
 * The model reads the hand of six as before: every one of the six is in hand by the player's turn, whenever it came.
 */
object TrialDraws {
    /** Cards in an opening hand before the turn's draw. */
    const val OPENING = 5

    /** A hand with its turn's draw put last: [opening] the five, [draw] the sixth (null for the player going first). */
    data class Ordered(val opening: List<Int>, val draw: Int?)

    /** [ids] as dealt, the turn's draw chosen from [seed] and put last; a hand of five has none. */
    fun ordered(ids: List<Int>, seed: Long): Ordered {
        if (ids.size != OPENING + 1) return Ordered(ids, null)
        val at = Random(seed).nextInt(ids.size)
        return Ordered(ids.filterIndexed { i, _ -> i != at }, ids[at])
    }

    /**
     * One side's cards on screen after [n] draws by effects: the [opening] five, the cards [drawn] off the top in order,
     * then the turn's [draw] — the next top card — for the player going second (null for the player going first, and once
     * the deck is spent). [shifted] when an effect took the card the turn would have drawn.
     */
    data class Shown(val opening: List<Int>, val drawn: List<Int>, val draw: Int?, val shifted: Boolean)

    /**
     * [hand] after [n] draws by effects off its deck: the turn's draw is the top card, then [rest] in one seeded shuffle,
     * so a draw by an effect takes the marked card first and the turn's draw becomes the next card down.
     */
    fun shown(hand: Ordered, rest: List<Int>, n: Int, seed: Long): Shown {
        val deck = listOfNotNull(hand.draw) + drawn(rest, rest.size, seed)
        val k = n.coerceIn(0, deck.size)
        val draw = if (hand.draw == null) null else deck.getOrNull(k)
        return Shown(hand.opening, deck.take(k), draw, hand.draw != null && k > 0)
    }

    /** How many cards an effect can draw off [hand]'s deck: the rest, and the turn's draw while it is still on top. */
    fun deckSize(hand: Ordered, rest: List<Int>): Int = rest.size + (if (hand.draw != null) 1 else 0)

    /** The first [n] cards off [rest] (the side's deck after its hand), in one seeded shuffle: a longer ask extends a shorter. */
    fun drawn(rest: List<Int>, n: Int, seed: Long): List<Int> =
        if (n <= 0 || rest.isEmpty()) emptyList() else rest.shuffled(Random(seed)).take(n)

    /**
     * One seed per situation and purpose, from what is on screen rather than the trial's id (the red team, 2026-10): a
     * repeat is the same hand shown again to measure how steadily it is judged, so it must be the same situation — the
     * same marked sixth of theirs and the same cards off the top. Their side reads only the stratum and their hand; your
     * draws, the stratum and your hand.
     */
    fun seed(p: Proposal, what: String): Long = seed(
        when (what) {
            THEIRS, THEIR_DRAWS -> "${p.stratum}|${p.opponent}"
            else -> when (p) {
                is Proposal.Rate -> "${p.stratum}|${p.hand}"
                is Proposal.Compare -> "${p.stratum}|${p.left}|${p.right}"
            }
        },
        what,
    )

    /** One seed per trial and purpose ([what]: the turn's draw or the draws, yours or theirs), from the trial's id. */
    fun seed(trialId: String, what: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in "$trialId/$what") {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    const val MINE = "mine"
    const val THEIRS = "theirs"
    const val MY_DRAWS = "my-draws"
    const val THEIR_DRAWS = "their-draws"
}

/** What a trial showed beyond its hands, kept with the answer (1.1.5). */
data class SeenDraws(
    /** Your turn's draw as shown, when you went second: the top card left after [drew] (1.1.7). */
    val turnDraw: Int? = null,
    /** Their turn's draw as shown, when they went second. */
    val theirTurnDraw: Int? = null,
    /** Cards you turned up for your draws by effects, in order: off the top, so the first is your marked sixth when you went second. */
    val drew: List<Int> = emptyList(),
    /** Cards you turned up for theirs, likewise. */
    val theyDrew: List<Int> = emptyList(),
) {
    companion object {
        val NONE = SeenDraws()
    }
}
