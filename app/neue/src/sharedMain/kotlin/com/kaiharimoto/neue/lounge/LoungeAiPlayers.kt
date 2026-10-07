package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules

/**
 * Ai's players at the Lounge's tables (`docs/LOUNGE.md`, L5), on kai's connection: what [LoungeHost] asks for when Ai
 * sits down in a room. [LoungeCenter] gives Neue's (`AiState`); a test gives scripted ones. Each player is a session of
 * its own that sees only its seat — nothing of kai's other conversations, memory or tools reaches a room.
 */
interface LoungeAiPlayers {
    /** Ai's name at the table. */
    val name: String

    /** The rules Ai plays a room's table by: its moves a cue, its cues a turn, its time a cue. */
    val rules: MatchRules

    /** Milliseconds between Ai's moves, so the people at the table can follow them. */
    val paceMs: Long get() = rules.paceMs

    /** A card's printed text, by its name: public text, for `card_info`. */
    fun cardText(name: String): String?

    /** Why Ai cannot play now — off, no connection it can use, today's budget spent — or null when it can. */
    fun unavailable(): String?

    /** A new player for Ai's [seat]: [deckName] is the deck it plays, [against] the person across, or null for Ai. */
    fun player(seat: Int, seatName: String, deckName: String, against: String?): MatchPlayer

    /** What a cue read and wrote, counted against today's budget. */
    fun spent(tokens: Long)

    /** A player let go: its duel ended, Ai stood up, or the Lounge closed. */
    fun release(player: MatchPlayer)
}
