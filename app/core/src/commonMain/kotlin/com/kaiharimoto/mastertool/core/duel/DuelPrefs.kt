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
    val aiPace: Int = 450,
    /** Ai plays [aiSeat]'s turns by itself when the turn passes to it. */
    val aiPlays: Boolean = false,
) {
    companion object {
        const val KNOW_ALL = "all"
        const val KNOW_SEAT = "seat"
    }
}
