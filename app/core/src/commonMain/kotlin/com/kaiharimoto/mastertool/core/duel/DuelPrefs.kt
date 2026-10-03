package com.kaiharimoto.mastertool.core.duel

import kotlinx.serialization.Serializable

/**
 * How the duel table is set (1.0.74): one player's table or two, how much the hot-seat shows, and
 * the decks chosen last, so a new duel starts where the last one did. Fields with defaults, as every
 * setting is: an older build reads a newer document and a newer one an older.
 */
@Serializable
data class DuelPrefs(
    /** Both sides of the table, or the near side alone for testing a deck. */
    val twoSided: Boolean = true,
    /** [KNOW_ALL]: both hands face-up, as a hot-seat tester wants; [KNOW_SEAT]: only what the seat at the bottom may see. */
    val knowledge: String = KNOW_ALL,
    /** The library decks chosen last, for each seat. */
    val deckId: String? = null,
    val opponentDeckId: String? = null,
    /** The names each seat goes by in the log. */
    val names: List<String> = listOf("You", "Opponent"),
    /** Whether the log and chat stand beside the table. */
    val logShown: Boolean = true,
    /** Ai at the table (1.0.76): what it may know (`DuelBrief`: self, opponent, full, auto), the seat it plays, its pace. */
    val aiKnowledge: String = "self",
    val aiSeat: Int = 1,
    val aiPace: Int = 250,
    /** Ai plays [aiSeat]'s turns by itself when the turn passes to it. */
    val aiPlays: Boolean = false,
    /** Two players over the network (1.0.77): which of the other player's moves wait for this one (`Windows`), and auto-pass after so many seconds (0: never). */
    val windows: String = "activations",
    val autoPass: Int = 0,
    /** The far seat's cards turned round to face them, as across a real table (1.0.78). */
    val facing: Boolean = false,
    /** The keys pinned at the inspector's foot, or folded away (1.0.78). */
    val keysShown: Boolean = true,
    /** Ai may move the other seat's cards too, not only its own (1.0.79; off: it asks the person). */
    val aiBothSeats: Boolean = false,
    /** Ai's thinking and what it did shown in the duel's log, or only what it says (1.0.80). */
    val aiThinking: Boolean = false,
    /** A duel that ends against a known deck is logged to Prep as a practice game (1.0.80). */
    val logGames: Boolean = true,
    /** Ai's response triggers (1.0.85): its watches wake it on the moves it could answer, and the person's moves wait for it. */
    val aiTriggers: Boolean = true,
    /** Each turn starts in Main Phase 1 (1.0.86): after End Turn the table draws for the next player and moves on by itself. */
    val autoDraw: Boolean = true,
) {
    companion object {
        const val KNOW_ALL = "all"
        const val KNOW_SEAT = "seat"
    }
}
