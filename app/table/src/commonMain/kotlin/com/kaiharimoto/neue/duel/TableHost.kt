package com.kaiharimoto.neue.duel

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.neue.kit.MenuSpec

/**
 * What the duel table is given by the app it stands in: the duel, the cards, its settings, and the app's own parts
 * the table reaches for when there are any. Neue gives the whole of it (`NeueHolders`); the Lounge's browser table
 * gives the duel and the cards, and no Ai, voice or written effects of its own (`docs/LOUNGE.md`). The table's
 * drawing is the same in both.
 */
interface TableHost {
    val duel: Duels

    /** The pool, by passcode: faces, names and text. */
    val cards: CardIndex

    val duelPrefs: DuelPrefs

    /** The foil's style on card faces (`Foils`). */
    val foil: String

    fun updateDuelPrefs(change: (DuelPrefs) -> DuelPrefs)

    /** A line at the bottom of the window. */
    fun note(text: String)

    /** A menu opened in the window's own layer. */
    fun menu(spec: MenuSpec)

    /** The duel's keys reach the table: it is the page on show, with nothing over it. */
    val keysHere: Boolean

    /** Ai at the table, where there is one (Neue's, on kai's connection). */
    val ai: TableAi?

    /** The voice (hold M), where there is one. */
    val voice: TableVoice?

    /** More for the inspector under a card's text: Neue's written effects. */
    val cardExtra: (@Composable (Card) -> Unit)?

    /** What is said in a Lounge room around its table, set among the log's lines by time; none elsewhere. */
    val roomChat: List<RoomLine> get() = emptyList()

    /** Words to a Lounge room from someone with no seat to chat from (a watcher); false where there is no room. */
    fun roomSay(text: String): Boolean = false
}

/** A line said in a Lounge room (`docs/LOUNGE.md`): who, what, and when by the host's clock. */
data class RoomLine(val who: String, val text: String, val at: Long)

/**
 * Ai at the table, as the log, the keys and the Spotlight reach it (1.0.76–1.0.87): its state, its cues, its
 * question, and its own views in the log — Neue's `AiState` behind them.
 */
interface TableAi {
    val name: String

    /**
     * The conversation carries the people's own words (a Lounge room's, `docs/LOUNGE.md`): the log's box sends them to
     * Ai alone, never across the table — a private ask stays private, and a watcher has no seat to say it from — and the
     * log shows them from the conversation. Neue's own Ai at kai's table: false.
     */
    val ownWords: Boolean get() = false

    /** Ai sits at this table: its seat, its conversation. */
    fun atTable(): Boolean

    /** Ai's conversation at this table, when it is the one open. */
    fun talk(): AiSession?

    /** Ai is answering now. */
    val running: Boolean

    /** Ai's answer as it streams, its thinking as it streams, and what it is doing. */
    val streaming: String
    val reasoning: String
    val activity: List<Pair<String, Boolean>>

    /** Ai's question stands in the log's foot. */
    val asking: Boolean

    /** The first cue the log's foot and the Y key offer now. */
    fun cueNow(game: DuelGame): AiCue

    fun give(cue: AiCue)
    fun catchUp()

    /** Words to Ai at its table. */
    fun say(text: String)

    /** Ai's cue typed or spoken on the Line; false when no Ai sits at the table. */
    fun lineCue(u: DuelCommand.Parsed.Ui): Boolean

    /** A digit answering Ai's question; false when none of the duel's stands. */
    fun answerDigit(n: Int): Boolean

    /** Enter sending the answers picked; false when there are none. */
    fun answerPicked(): Boolean

    /** Ai stopped where it is; true when it was answering at this table. */
    fun stopAtTable(): Boolean

    @Composable fun Reply(text: String, live: Boolean)
    @Composable fun Reasoning(text: String, live: Boolean, opened: MutableMap<String, Boolean>)
    @Composable fun Activity(text: String, isError: Boolean)

    /** The log's foot: Ai's question, or the buttons that cue it. */
    @Composable fun Cues(game: DuelGame, talking: Boolean)
}

/** Where the microphone is (1.0.87): listening, writing out, what was heard, or why nothing was. */
enum class VoicePhase { IDLE, LISTENING, TRANSCRIBING, HEARD, FAILED }

/** The duel's voice (hold M), as the Spotlight reaches it: Neue's `DuelVoice`, Whisper on the desk. */
interface TableVoice {
    val phase: VoicePhase
    val held: Boolean
    val busy: Boolean
    val failure: String?
    val level: Float
    val partial: String
    var onListen: () -> Unit
    var onHeard: (String) -> Unit
    var hints: () -> String

    /** What was being said dropped, never made. */
    fun cancel()

    /** A line read back aloud, when that is set. */
    fun say(text: String)

    @Composable fun Mic(size: Dp)
}
