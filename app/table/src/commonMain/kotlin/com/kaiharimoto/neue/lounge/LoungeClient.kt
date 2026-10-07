package com.kaiharimoto.neue.lounge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.lounge.DeckInfo
import com.kaiharimoto.mastertool.core.duel.lounge.Lounge
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.neue.duel.Duels
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
    var said by mutableStateOf<List<LoungeWire.Said>>(emptyList())
    var problem by mutableStateOf<String?>(null)
    /** Turned away for good: the page says why and offers to try again. */
    var rejected by mutableStateOf<String?>(null)
    private var table: LoungeTableNet? = null

    val member get() = me?.let(lounge::member)
    val room get() = lounge.room(member?.room)

    fun ask(w: LoungeWire) = send(w)

    fun hear(w: LoungeWire) {
        when (w) {
            is LoungeWire.Welcome -> { me = w.you; token = w.token; rejected = null }
            is LoungeWire.State -> lounge = w.lounge
            is LoungeWire.Seated -> sitAt(w)
            is LoungeWire.Table -> table?.hear(w.wire)
            is LoungeWire.Refused -> problem = w.reason
            is LoungeWire.Rejected -> rejected = w.reason
            is LoungeWire.Said -> said = (said + w).takeLast(SAID)
            is LoungeWire.DeckList -> decks = w.decks
            is LoungeWire.Deck -> openDeck = w
            else -> Unit
        }
    }

    private fun sitAt(w: LoungeWire.Seated) {
        val was = seated
        seated = w
        if (w.room == null) {
            if (table != null) { table = null; duels.network = away() }
            return
        }
        if (was != null && was.room == w.room && was.seat == w.seat && table != null) return
        val net = LoungeTableNet(duels, w.seat, send)
        table = net
        duels.network = net
        // Your own seat at the bottom; a watcher starts with the room's first seat there, and can turn the table.
        duels.bottom = w.seat ?: 0
    }

    /** The connection went: the table is let go, and the member is no one until welcomed again. */
    fun lost() {
        if (table != null) { table = null; duels.network = away() }
        seated = null
    }

    companion object {
        /** The lobby's and a room's words kept on screen. */
        const val SAID = 200
    }
}
