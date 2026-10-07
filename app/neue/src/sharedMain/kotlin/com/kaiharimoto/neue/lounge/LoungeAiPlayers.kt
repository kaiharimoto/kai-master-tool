package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.playbook.Playbook
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import kotlinx.serialization.json.JsonObject

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

    /**
     * A new player for Ai's [seat]: [deckName] is the deck it plays, [against] the person across, or null for Ai.
     * [library] is kai's library deck it came from (its guide and combos are Ai's to play by), null for a friend's;
     * [strength] how hard it thinks (`DuelPrefs.FAST`, `STRONG`, `MAX`).
     */
    fun player(seat: Int, seatName: String, deckName: String, against: String?, library: String?, strength: String): MatchPlayer

    /** What Ai studied about kai's [library] deck — `playbook_*`, `course_*` answered for it alone — or null. */
    suspend fun knowledge(library: String, tool: String, input: JsonObject): String? = null

    /** kai's [library] deck's playbook, for the entries a cue shows for its position; null when it has none. */
    fun playbook(library: String): Playbook? = null

    /** What a cue read and wrote, counted against today's budget. */
    fun spent(tokens: Long)

    /** A player let go: its duel ended, Ai stood up, or the Lounge closed. */
    fun release(player: MatchPlayer)

    /**
     * A room's conversation with Ai (`LoungeTalk`): everyone's when [seatName] is null, else one player's private one,
     * answered with their seat's eyes. A session of its own, with only `duel_state` and `card_info`.
     */
    fun talker(roomName: String, seatName: String?, strength: String): LoungeTalker

    /** A conversation let go: its room closed, or the Lounge did. */
    fun release(talker: LoungeTalker)
}

/** A room's conversation with Ai, as [LoungeHost] asks it things. */
interface LoungeTalker {
    /**
     * [cue] asked, its tools answered by [tools]: Ai's answer (null when it said nothing), and what it cost. [saying] is
     * handed the answer's words so far as they are written, for the room to read live.
     */
    suspend fun ask(cue: String, tools: ToolRunner, saying: (String) -> Unit = {}): Pair<String?, CueResult>
}
