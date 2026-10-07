package com.kaiharimoto.neue.duel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.ai.ActivityLine
import com.kaiharimoto.neue.ai.ReasoningView
import com.kaiharimoto.neue.ai.ReplyView
import com.kaiharimoto.neue.effects.DuelCardEffects
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.lounge.LoungeClient
import com.kaiharimoto.neue.lounge.LoungeTableNet

/** The table as Neue gives it: the whole app behind it — Ai, the voice, the written effects. */
internal class NeueTableHost(private val h: NeueHolders) : TableHost {
    override val duel: Duels get() = h.duel
    override val cards: CardIndex get() = h.builder.index
    override val duelPrefs: DuelPrefs get() = h.neue.prefs.duel
    override val foil: String get() = h.neue.prefs.foil
    override fun updateDuelPrefs(change: (DuelPrefs) -> DuelPrefs) = h.neue.update { it.copy(duel = change(it.duel)) }
    override fun note(text: String) { h.neue.note = Note(text) }
    override fun menu(spec: MenuSpec) { h.neue.menu = spec }
    override val keysHere: Boolean get() = h.neue.page == Page.DUEL && !h.neue.hasTop && !h.overlays.isOpen
    private val neueAi = NeueTableAi(h)
    /** None while Ai is off (Settings › Ai): the table shows no trace of it then. */
    // At a Lounge room's table (docs/LOUNGE.md) the log's Ai is the room's conversation, the same one friends read;
    // kai's own Ai never reads or moves a networked table.
    override val ai: TableAi? get() = loungeAi() ?: neueAi.takeIf { h.neue.prefs.ai.enabled }

    private fun loungeAi(): TableAi? = room()?.tableAi?.takeIf { it.atTable() }

    // What is said in the Lounge room kai sits or watches at.
    override val roomChat: List<RoomLine> get() = room()?.roomLines.orEmpty()
    override fun roomSay(text: String): Boolean = room()?.roomSay(text) == true

    /** kai's side of the Lounge, while the table on the Duel page is one of its rooms'. */
    private fun room(): LoungeClient? = if (h.duel.network is LoungeTableNet) h.lounge.client else null
    override val voice: TableVoice get() = h.duelVoice
    override val cardExtra: @Composable (Card) -> Unit = { card -> DuelCardEffects(card) }
}

/** Ai at the table: Neue's `AiState`, and the log's cues (`DuelAiCues.kt`). */
internal class NeueTableAi(private val h: NeueHolders) : TableAi {
    override val name: String get() = h.ai.name
    override fun atTable(): Boolean = aiAtTable(h)
    override fun talk(): AiSession? = h.ai.session?.takeIf { it.mode == AiSession.MODE_DUEL && it.id == h.duel.aiSession }
    override val running: Boolean get() = h.ai.running
    override val streaming: String get() = h.ai.streaming
    override val reasoning: String get() = h.ai.reasoning
    override val activity: List<Pair<String, Boolean>> get() = h.ai.activity.map { it.summary.ifBlank { it.name } to it.isError }
    override val asking: Boolean get() = h.ai.question != null && duelTalking(h)
    override fun cueNow(game: DuelGame): AiCue = aiCueNow(h, game)
    override fun give(cue: AiCue) = giveCue(h, cue)
    override fun catchUp() = catchUp(h)
    override fun say(text: String) { cueAi(h, Cue.SAY, text) }
    override fun lineCue(u: DuelCommand.Parsed.Ui): Boolean = lineCue(h, u)
    override fun answerDigit(n: Int): Boolean = answerByDigit(h, n)
    override fun answerPicked(): Boolean = answerPicked(h)

    override fun stopAtTable(): Boolean {
        if (!(aiAtTable(h) && h.ai.running && h.ai.session?.mode == AiSession.MODE_DUEL)) return false
        h.ai.stop()
        return true
    }

    @Composable
    override fun Reply(text: String, live: Boolean) = ReplyView(h.ai, text, live)

    @Composable
    override fun Reasoning(text: String, live: Boolean, opened: MutableMap<String, Boolean>) = ReasoningView(h.ai, text, live, opened)

    @Composable
    override fun Activity(text: String, isError: Boolean) = ActivityLine(text, isError)

    @Composable
    override fun Cues(game: DuelGame, talking: Boolean) = AiCues(h, h.duel, game, talking)
}
