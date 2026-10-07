package com.kaiharimoto.neue.lounge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.lounge.DeckInfo
import com.kaiharimoto.mastertool.core.duel.lounge.Lounge
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.lounge.TalkEntry
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.duel.RoomLine
import com.kaiharimoto.neue.duel.TableNet

/**
 * One member's side of the Lounge (`docs/LOUNGE.md`): who they are, the Lounge as kai's computer last sent it, where
 * they sit, their decks, what was said. A friend's browser has one over its WebSocket; kai's own window has one
 * talking to the Lounge in-process. [send] carries what the member asks for; [hear] is given what comes back.
 *
 * Sitting or watching at a room puts a [LoungeTableNet] on the member's [duels], so the table they see is that room's;
 * leaving it puts back what was there ([away]).
 */
class LoungeClient(
    private val duels: Duels,
    private val send: (LoungeWire) -> Unit,
    /** The network the member's table goes back to out of a room: none in the browser, the local network's on the desk. */
    private val away: () -> TableNet,
) {
    var me by mutableStateOf<String?>(null)
    var token by mutableStateOf<String?>(null)
    var lounge by mutableStateOf(Lounge())
    var seated by mutableStateOf<LoungeWire.Seated?>(null)
    var decks by mutableStateOf<List<DeckInfo>>(emptyList())
    var openDeck by mutableStateOf<LoungeWire.Deck?>(null)
    /** kai's rules decks are checked by ("TCG", "Genesys, 100 points"), and the last check of a deck being edited. */
    var rules by mutableStateOf("")
    var checked by mutableStateOf<LoungeWire.Checked?>(null)
    /** Between a match's games: the deck to side from, until the member has sided (or the match is over). */
    var siding by mutableStateOf<LoungeWire.Siding?>(null)
    /** What was said where this member is — their room, or the lobby — oldest first. */
    var said by mutableStateOf<List<LoungeWire.Said>>(emptyList())
    var problem by mutableStateOf<String?>(null)
    /** Turned away for good: the page says why and offers to try again. */
    var rejected by mutableStateOf<String?>(null)
    /** The room's conversation with Ai as this member may read it (L5), and whether Ai is answering in it. */
    var talk by mutableStateOf<List<TalkEntry>>(emptyList())
    var aiThinking by mutableStateOf(false)
    /** Ai's answer as far as it has written it, while it writes. */
    var aiStreaming by mutableStateOf("")
    /** What is typed to Ai goes to this member alone, answered with their seat's eyes. */
    var askPrivately by mutableStateOf(false)
    /** Ai in the log of the room's table: the room's conversation. */
    val tableAi: LoungeTableAi by lazy { LoungeTableAi(this) }

    /** The room's table this member sits or watches at, while they do. */
    var tableNet by mutableStateOf<LoungeTableNet?>(null)
        private set

    val member get() = me?.let(lounge::member)
    val room get() = lounge.room(member?.room)

    fun ask(w: LoungeWire) = send(w)

    /** What is said in the member's room, as the duel's log sets it among the moves (`TableHost.roomChat`). */
    val roomLines: List<RoomLine>
        get() = if (member?.room == null) emptyList() else said.map { RoomLine(it.nick, it.text, it.at) }

    /** Words to the room (a watcher's, who has no seat to chat from). */
    fun roomSay(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || member?.room == null) return false
        send(LoungeWire.Say(t))
        return true
    }

    fun hear(w: LoungeWire) {
        when (w) {
            is LoungeWire.Welcome -> { me = w.you; token = w.token; rejected = null }
            is LoungeWire.State -> {
                lounge = w.lounge
                tableNet?.peer = room?.name
                // Sided, or the match over or given up: nothing left to side.
                val m = room?.match
                val seat = room?.seated(me.orEmpty())
                if (siding != null && (m?.siding != true || seat == null || m.sided[seat] || siding?.room != room?.id)) siding = null
            }
            is LoungeWire.Siding -> siding = w
            is LoungeWire.Checked -> { checked = w; rules = w.rules }
            is LoungeWire.Seated -> sitAt(w)
            is LoungeWire.Table -> tableNet?.hear(w.wire)
            is LoungeWire.Refused -> problem = w.reason
            is LoungeWire.Rejected -> rejected = w.reason
            // What is said where this member is: a room's lines, or the lobby's.
            is LoungeWire.Said -> if (w.room == member?.room) said = (said + w).takeLast(SAID)
            is LoungeWire.Chat -> said = w.lines.takeLast(SAID)
            is LoungeWire.DeckList -> { decks = w.decks; rules = w.rules }
            is LoungeWire.Deck -> openDeck = w
            is LoungeWire.Talk -> if (w.room == member?.room) { talk = w.entries; aiThinking = w.thinking; aiStreaming = w.streaming.orEmpty() }
            else -> Unit
        }
    }

    private fun sitAt(w: LoungeWire.Seated) {
        val was = seated
        seated = w
        // Another room, another conversation.
        if (was?.room != w.room) { talk = emptyList(); aiThinking = false; aiStreaming = "" }
        if (w.room == null) {
            if (tableNet != null) { tableNet = null; duels.network = away() }
            return
        }
        if (was != null && was.room == w.room && was.seat == w.seat && tableNet != null) return
        val net = LoungeTableNet(duels, w.seat, send)
        net.peer = room?.name
        tableNet = net
        duels.network = net
        // Your own seat at the bottom; a watcher starts with the room's first seat there, and can turn the table.
        duels.bottom = w.seat ?: 0
    }

    /** The connection went: the table is let go, and the member is no one until welcomed again. */
    fun lost() {
        if (tableNet != null) { tableNet = null; duels.network = away() }
        seated = null
    }

    companion object {
        /** The lobby's and a room's words kept on screen. */
        const val SAID = 200
    }
}
