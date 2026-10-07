package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.net.Line
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.neue.duel.Duels.NetRole

/**
 * A table played over a network, as [Duels] sees it: hosting one, or sitting at someone else's. The local network's is
 * Neue's (`DuelNet`, sockets: 1.0.77); a Lounge room's goes over a WebSocket to kai's computer, where everyone —
 * kai's own window too — is a guest of the room (`docs/LOUNGE.md`). [Duels] forwards each member under its own name.
 */
interface TableNet {
    var role: NetRole?
    var netStatus: String?
    var netCode: String?
    var peer: String?
    /** The guest's table: the host's duel as its seat sees it, rebuilt from every update. */
    var remote: DuelGame?
    var remoteLines: List<Line>
    /** Who a response window waits on, as the guest was told. */
    var remoteWaiting: Int?
    /** A seat asking to take back its last move, for the other player to answer. */
    var takeBackAsked: Int?
    /** The next move goes ahead though a response window waits ("Go on anyway"). */
    var forceNext: Boolean
    /** This player's own response windows, set by the page from its settings. */
    var myWindows: String

    /** This player's seat at the table. */
    val mySeat: Int

    /** Whose answer the table waits on now, if anyone's. */
    val waitingFor: Int?

    /** Watching a room's duel, not playing in it: every move refused, the table drawn whole (the Lounge). */
    val watching: Boolean get() = false

    /**
     * What a watcher has chosen to see of what they are sent (kai: "everything, but they can choose what they want to see
     * or hide"): the seats whose hidden cards are shown — both, one, or [com.kaiharimoto.mastertool.core.duel.lounge.Viewer.PUBLIC]
     * for neither, only what is face-up.
     */
    val watchSight: Set<Int> get() = setOf(0, 1)

    /** Where the person's drag acts as another seat than the card's: a guest. */
    fun dragActor(): Int?

    fun host(mine: SeatSetup)
    fun join(code: String, mine: SeatSetup)
    fun answerTakeBack(yes: Boolean)
    fun leave()

    /** A guest's moves, sent to the host; false when they could not be. */
    fun ask(actions: List<DuelAction>): Boolean

    /** The host's own moves at a networked table. */
    fun hostAct(actions: List<DuelAction>, seat: Int): Boolean

    /** Asks the other player to let this one take back its last move. */
    fun askTakeBack()
}

/** No network: a table on this screen alone (and the browser's, until it sits at a Lounge room). */
class OfflineNet(private val problem: (String) -> Unit = {}) : TableNet {
    override var role by mutableStateOf<NetRole?>(null)
    override var netStatus by mutableStateOf<String?>(null)
    override var netCode by mutableStateOf<String?>(null)
    override var peer by mutableStateOf<String?>(null)
    override var remote by mutableStateOf<DuelGame?>(null)
    override var remoteLines by mutableStateOf<List<Line>>(emptyList())
    override var remoteWaiting by mutableStateOf<Int?>(null)
    override var takeBackAsked by mutableStateOf<Int?>(null)
    override var forceNext = false
    override var myWindows: String = Windows.ACTIVATIONS
    override val mySeat: Int get() = 0
    override val waitingFor: Int? get() = null
    override fun dragActor(): Int? = null
    override fun host(mine: SeatSetup) = problem("There is no network here to open a table on")
    override fun join(code: String, mine: SeatSetup) = problem("There is no network here to join a table on")
    override fun answerTakeBack(yes: Boolean) = Unit
    override fun leave() = Unit
    override fun ask(actions: List<DuelAction>): Boolean = false
    override fun hostAct(actions: List<DuelAction>, seat: Int): Boolean = false
    override fun askTakeBack() = Unit
}
