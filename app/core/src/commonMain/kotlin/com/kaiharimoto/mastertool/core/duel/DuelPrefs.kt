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
    /** Whether the log and chat and the card inspector stand beside the table — put away together (1.0.93), they are drawers. */
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
    /**
     * The table draws for each turn's player (1.0.86): after End Turn it draws one card for the next player. From 1.0.93 the
     * draw alone (kai: "let the player manually do that") — the turn waits in the Draw Phase; 1.0.86–1.0.92 also went on
     * through the Standby Phase to Main Phase 1.
     */
    val autoDraw: Boolean = true,
    /** Every place's coordinate (`m3`, `os2`, `h4`) written faintly at its corner, as a chessboard's edge (1.0.87, `I`). */
    val coordinates: Boolean = false,
    /**
     * Command mode speaks back (1.0.87, off unless asked for): the move understood as it is shown, the other seat's
     * moves as they land, the answers to questions — in this seat's own words, never naming a card it cannot see.
     */
    val speak: Boolean = false,
    /**
     * A spoken move is shown in the Spotlight and waits for Enter or "yes" (1.0.87, kai: "show, then confirm"); off, a
     * move heard is made at once. Questions, cues to Ai and undo never wait.
     */
    val voiceConfirm: Boolean = true,
    /**
     * A new two-seat duel opens with the dice (1.0.87, kai): each seat throws two onto its field, the higher chooses to go
     * first or second. Off, the first seat goes first, as before.
     */
    val openingRoll: Boolean = true,
    /**
     * How hard Ai thinks at the table (mastery, 1.1.43, kai: strong by default): [FAST] answers quickly, [STRONG] thinks
     * hard and takes more rounds a cue, [MAX] thinks as hard as the model can. Ai vs Ai seats play at it too.
     */
    val aiStrength: String = STRONG,
) {
    companion object {
        const val KNOW_ALL = "all"
        const val KNOW_SEAT = "seat"

        const val FAST = "fast"
        const val STRONG = "strong"
        const val MAX = "max"

        /**
         * The effort to ask for at [strength], among what the provider offers ([offered], lowest first as the provider
         * lists them; empty when it takes none): the wanted level, else the nearest below it, else [fallback].
         */
        fun effort(strength: String, offered: List<String>, fallback: String): String {
            if (offered.isEmpty()) return fallback
            val ladder = listOf("low", "medium", "high", "xhigh", "max")
            val want = when (strength) {
                FAST -> "low"
                MAX -> "xhigh"
                else -> "high"
            }
            return ladder.take(ladder.indexOf(want) + 1).reversed().firstOrNull { it in offered } ?: offered.first()
        }

        /** The rounds of tools one cue may take at [strength]: thinking hard is reading more before moving. */
        fun steps(strength: String, base: Int): Int = when (strength) {
            FAST -> base
            MAX -> base * 3
            else -> base * 2
        }
    }
}
