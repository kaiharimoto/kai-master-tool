package com.kaiharimoto.mastertool.core.shootout.bench

import kotlin.random.Random

/**
 * What a trial shows beyond its hands (1.1.5, kai: "The sixth card should be marked as their top deck … allow the user to
 * simulate drawing from a deck for either the user or the opponent").
 *
 * - **The turn's draw.** A hand of six is the player going second's: five dealt, one drawn for their turn. The sixth is
 *   marked, because it is not in hand on the other player's first turn — a hand trap drawn for the turn stops nothing
 *   before it. Which of the six it is comes from a shuffle, so it is a uniform choice, seeded by the trial.
 * - **Draws by card effects.** Mulcharmies and other draw and consistency cards make the next cards matter: the person may
 *   turn them up, for either side, off the rest of that side's deck in the trial's stratum (sided decks in sided games).
 *   Each further draw is the next card of one seeded shuffle, so asking again shows the same cards. They are a look ahead
 *   only: the opening hand is what is rated, and the model reads it as before.
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

    /** The first [n] cards off [rest] (the side's deck after its hand), in one seeded shuffle: a longer ask extends a shorter. */
    fun drawn(rest: List<Int>, n: Int, seed: Long): List<Int> =
        if (n <= 0 || rest.isEmpty()) emptyList() else rest.shuffled(Random(seed)).take(n)

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
    /** Your turn's draw, when you went second. */
    val turnDraw: Int? = null,
    /** Their turn's draw, when they went second. */
    val theirTurnDraw: Int? = null,
    /** Cards you turned up for your draws by effects. */
    val drew: List<Int> = emptyList(),
    /** Cards you turned up for theirs. */
    val theyDrew: List<Int> = emptyList(),
) {
    companion object {
        val NONE = SeenDraws()
    }
}
