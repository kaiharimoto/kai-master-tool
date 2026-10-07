package com.kaiharimoto.neue.lounge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.net.Line
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.duel.Duels.NetRole
import com.kaiharimoto.neue.duel.TableNet

/**
 * A Lounge room's table, as one member's [Duels] plays it (`docs/LOUNGE.md`): kai's computer holds the duel
 * ([com.kaiharimoto.mastertool.core.duel.lounge.RoomTable]); this side is only ever sent its own view and asks for
 * its moves, as a guest at a networked table always has (1.0.77). The same in a friend's browser and in kai's own
 * window — kai is a guest of the room too. Watching ([seat] null): the table drawn whole, every move refused.
 */
class LoungeTableNet(
    private val d: Duels,
    /** The seat, or null watching. */
    val seat: Int?,
    private val send: (LoungeWire) -> Unit,
) : TableNet {
    override var role by mutableStateOf<NetRole?>(NetRole.GUEST)
    override var netStatus by mutableStateOf<String?>(null)
    override var netCode by mutableStateOf<String?>(null)
    override var peer by mutableStateOf<String?>(null)
    override var remote by mutableStateOf<DuelGame?>(null)
    override var remoteLines by mutableStateOf<List<Line>>(emptyList())
    override var remoteWaiting by mutableStateOf<Int?>(null)
    override var takeBackAsked by mutableStateOf<Int?>(null)
    override var forceNext = false
    override var myWindows: String = Windows.ACTIVATIONS
        set(v) {
            if (v != field && seat != null) send(LoungeWire.Table(Wire.SetWindows(v)))
            field = v
        }
    private var seq = 0

    override val mySeat: Int get() = seat ?: 0
    override val watching: Boolean get() = seat == null
    override val waitingFor: Int? get() = remoteWaiting

    /** A watcher's choice: both hands, one seat's, or neither ([com.kaiharimoto.mastertool.core.duel.lounge.Viewer.PUBLIC]). */
    override var watchSight by mutableStateOf(setOf(0, 1))
    override fun dragActor(): Int? = seat

    /** What the room's table sent: the view, new lines, a refusal. */
    fun hear(w: Wire) {
        when (w) {
            is Wire.Update -> {
                remote = DuelMirror.game(w.view, DuelHeader(id = "lounge"))
                val first = w.lines.firstOrNull()?.i ?: w.cursor
                remoteLines = remoteLines.filter { it.i < first && it.i < w.cursor } + w.lines
                remoteWaiting = w.waitingFor
                takeBackAsked = w.takeBackFrom?.takeIf { it != seat }
            }
            is Wire.Refused -> d.problem = w.reason
            is Wire.Rejected -> d.problem = w.reason
            else -> Unit
        }
    }

    override fun ask(actions: List<DuelAction>): Boolean {
        if (seat == null) { d.problem = "You are watching: sit down to play"; return false }
        send(LoungeWire.Table(Wire.Intent(++seq, actions, forceNext)))
        forceNext = false
        return true
    }

    override fun hostAct(actions: List<DuelAction>, seat: Int): Boolean = ask(actions)

    override fun askTakeBack() {
        if (seat == null) return
        send(LoungeWire.Table(Wire.TakeBack(ask = true)))
        d.problem = "Asked the other player to let you take it back"
    }

    override fun answerTakeBack(yes: Boolean) {
        if (takeBackAsked == null) return
        takeBackAsked = null
        send(LoungeWire.Table(Wire.TakeBack(ask = false, yes = yes)))
    }

    override fun host(mine: SeatSetup) { d.problem = "This table is a Lounge room's: tables are opened in the lobby" }
    override fun join(code: String, mine: SeatSetup) { d.problem = "This table is a Lounge room's: rooms are joined in the lobby" }
    /** Leaving the table is leaving the room, for the lobby; a seat in a duel waits a while for its player. */
    override fun leave() = send(LoungeWire.Enter(null))
}
