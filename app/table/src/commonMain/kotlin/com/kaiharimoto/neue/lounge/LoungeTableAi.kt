package com.kaiharimoto.neue.lounge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.neue.duel.TableAi
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * A Lounge room's conversation with Ai, as the duel's log shows it (`docs/LOUNGE.md`, L5): what everyone asked and Ai
 * answered, and this member's own private asks, sent from kai's computer ([LoungeClient.talk]); what is typed in the log
 * goes to Ai alone ([ownWords]), to the room or, with [LoungeClient.askPrivately], just to them. The same in a friend's
 * browser and in kai's window at a room. It never moves the table: Ai at a seat plays by itself, on kai's computer.
 */
class LoungeTableAi(private val client: LoungeClient) : TableAi {
    override val name: String get() = "Ai"
    override val ownWords: Boolean get() = true

    /** The room allows Ai: its conversation is in the log, and the box talks to it. */
    override fun atTable(): Boolean = client.room?.ai == true

    override fun talk(): AiSession? {
        val room = client.room ?: return null
        if (!room.ai) return null
        val me = client.me
        val turns = client.talk.map { e ->
            val mine = e.to != null
            val text = when {
                e.ai && mine -> "(to you) ${e.text}"
                e.ai -> e.text
                mine -> "${e.who} (just Ai): ${e.text}"
                else -> "${e.who}: ${e.text}"
            }
            ChatTurn(if (e.ai) Role.ASSISTANT else Role.USER, listOf(Part.Text(text)), e.at)
        }
        // While it answers, a line in its place under the question: the log has no stream from kai's computer.
        val waiting = if (client.aiThinking) listOf(ChatTurn(Role.ASSISTANT, listOf(Part.Text("…")), (client.talk.lastOrNull()?.at ?: 0L) + 1)) else emptyList()
        return AiSession(id = "lounge-${room.id}-$me", title = room.name, turns = turns + waiting)
    }

    override val running: Boolean get() = client.aiThinking
    override val streaming: String get() = ""
    override val reasoning: String get() = ""
    override val activity: List<Pair<String, Boolean>> get() = emptyList()
    override val asking: Boolean get() = false

    override fun cueNow(game: DuelGame): AiCue = AiCue.YOUR_MOVE
    override fun give(cue: AiCue) = Unit
    override fun catchUp() = Unit

    override fun say(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) client.ask(LoungeWire.AskAi(t, private = client.askPrivately && client.seated?.seat != null))
    }

    override fun lineCue(u: DuelCommand.Parsed.Ui): Boolean = false
    override fun answerDigit(n: Int): Boolean = false
    override fun answerPicked(): Boolean = false
    override fun stopAtTable(): Boolean = false

    /** Ai's answer: its words beside a rule of ink, so they read apart from the table's lines. */
    @Composable
    override fun Reply(text: String, live: Boolean) {
        val c = Mu.colors
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(2.dp).height(16.dp).background(c.ink))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Micro(name, color = c.ink45)
                Small(text, color = c.ink)
            }
        }
    }

    @Composable
    override fun Reasoning(text: String, live: Boolean, opened: MutableMap<String, Boolean>) = Unit

    @Composable
    override fun Activity(text: String, isError: Boolean) = Unit

    /** The log's foot: while Ai answers, a line that says so. */
    @Composable
    override fun Cues(game: DuelGame, talking: Boolean) {
        if (client.aiThinking) Small("$name is answering…", color = Mu.colors.ink45)
    }
}
