package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.neue.duel.Duels.NetRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two players over the network (1.0.77), a part of [Duels]: hosting or joining a table, what each side hears, take-backs
 * and leaving. The socket and the code are `DuelNet.kt`'s ([DuelHosting], [DuelLink]). [Duels] forwards every member
 * under its own name.
 */
internal class DuelNet(private val d: Duels) {
    var role by mutableStateOf<NetRole?>(null)
    var netStatus by mutableStateOf<String?>(null)
    var netCode by mutableStateOf<String?>(null)
    var peer by mutableStateOf<String?>(null)
    /** The guest's table: the host's duel as its seat sees it, rebuilt from every update. */
    var remote by mutableStateOf<DuelGame?>(null)
    var remoteLines by mutableStateOf<List<com.kaiharimoto.mastertool.core.duel.net.Line>>(emptyList())
    /** Who a response window waits on, as the guest was told. */
    var remoteWaiting by mutableStateOf<Int?>(null)
    /** A seat asking to take back its last move, for the other player to answer. */
    var takeBackAsked by mutableStateOf<Int?>(null)
    /** The next move goes ahead though a response window waits ("Go on anyway"). */
    var forceNext = false
    /** This player's own response windows, set by the page from its settings. */
    var myWindows: String = com.kaiharimoto.mastertool.core.duel.net.Windows.ACTIVATIONS
    private var guestWindows: String = com.kaiharimoto.mastertool.core.duel.net.Windows.ACTIVATIONS
    private var hosting: DuelHosting? = null
    private var link: DuelLink? = null
    private var hostSeat: com.kaiharimoto.mastertool.core.duel.SeatSetup? = null
    private var guestToken: String? = null
    /** The duel [guestToken] sits in: a token is never a seat at another duel (1.0.85). */
    private var guestDuel: String? = null
    private var token: String? = null
    private var joinedSecret: Int? = null

    /**
     * The guest's seat token, kept on disk per table (1.0.85): an app that restarts mid-duel comes back by it, so the
     * host never has to let anyone back in by name alone.
     */
    private fun tokenFile(secret: Int) = File(d.dir, "net/seat-$secret.txt")
    private var sentTo = 0
    private var seq = 0
    /** This player's seat at a networked table: the host sits at 0, the guest at 1. */
    val mySeat: Int get() = if (role == NetRole.GUEST) 1 else 0

    /** Whose answer the table waits on now, if anyone's. */
    val waitingFor: Int?
        get() = when (role) {
            NetRole.GUEST -> remoteWaiting
            NetRole.HOST -> d.game?.state?.window?.responder
            null -> null
        }

    /** Where the person's drag acts as another seat than the card's: a guest. */
    fun dragActor(): Int? = if (role == NetRole.GUEST) mySeat else null

    /** Opens a table on the local network with [mine] at the host's seat; the code to share comes back in [netCode]. */
    fun host(mine: com.kaiharimoto.mastertool.core.duel.SeatSetup) {
        leave()
        role = NetRole.HOST
        hostSeat = mine
        d.bottom = 0
        netStatus = "Opening the table…"
        val h = DuelHosting(onGuest = ::guestArrived, onProblem = { d.problem = it })
        hosting = h
        d.scope.launch {
            val code = runCatching { h.open() }.getOrNull()
            if (code == null) {
                d.problem = "No local network to open a table on. Join the same Wi-Fi as the other player."
                leave()
            } else {
                netCode = code
                netStatus = "Waiting for someone to join"
            }
        }
    }

    private fun guestArrived(socket: java.net.Socket) {
        if (link != null) { runCatching { socket.close() }; return }
        val l = DuelLink(socket, ::hostHears) { _ ->
            link = null
            netStatus = "${peer ?: "The other player"} left. They can join again with the same code."
        }
        link = l
        l.start()
    }

    private fun hostHears(w: com.kaiharimoto.mastertool.core.duel.net.Wire) {
        val l = link ?: return
        val h = hosting ?: return
        when (w) {
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Hello -> {
                com.kaiharimoto.mastertool.core.duel.net.DuelHost.knows(w, h.secret)?.let { why ->
                    l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Rejected(why)); l.close(); link = null; return
                }
                // A guest who sat down before comes back to the duel in play — by its token, or, when its app
                // restarted and lost it, by its name. Never a new deal over a duel a guest is in (1.0.85).
                val seated = guestToken != null && d.game != null && d.game?.header?.id == guestDuel && role == NetRole.HOST
                val back = seated && w.token != null && w.token == guestToken
                if (seated && !back) {
                    l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Rejected("A duel is in play at this table.")); l.close(); link = null; return
                }
                guestWindows = w.windows
                if (!back) {
                    val mine = hostSeat ?: return
                    guestToken = java.util.UUID.randomUUID().toString()
                    d.start(
                        DuelHeader(
                            id = "n${Duels.now()}",
                            seed = java.security.SecureRandom().nextLong(),
                            seats = listOf(mine, com.kaiharimoto.mastertool.core.duel.SeatSetup(w.name.ifBlank { "Guest" }, w.main, w.extra, null, w.deckName)),
                            created = Duels.now(),
                            // The dice decide who goes first over the network too (1.0.87): each player throws their own.
                            openingRoll = d.opener.openingRoll,
                        ),
                    )
                    role = NetRole.HOST
                    guestDuel = d.game?.header?.id
                }
                peer = w.name.ifBlank { "Guest" }
                netStatus = "Playing ${peer} over the network"
                d.setupOpen = false
                sentTo = 0
                l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Welcome(1, guestToken!!, hostSeat?.name.orEmpty()))
                sendUpdate()
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Intent -> {
                val g = d.game ?: return
                val (resolved, why) = com.kaiharimoto.mastertool.core.duel.net.DuelHost.resolve(g.state, 1, g.header.seed, w.actions)
                if (resolved == null) { l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Refused(w.seq, why ?: "No")); return }
                val r = com.kaiharimoto.mastertool.core.duel.net.DuelHost.act(g, 1, resolved, windows(), w.force, Duels.now())
                if (!r.ok) { l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Refused(w.seq, r.problem ?: "No")); return }
                d.game = r.game
                d.save()
                sendUpdate()
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.TakeBack -> if (w.ask) {
                takeBackAsked = 1
            } else if (w.yes) {
                // Honoured only as the answer to the host's own ask (1.0.85: before, any yes undid the host).
                if (hostAskedTakeBack) { hostAskedTakeBack = false; takeBack(0) }
            } else {
                hostAskedTakeBack = false
                d.problem = "${peer ?: "They"} would rather you did not take it back"
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.SetWindows -> guestWindows = w.windows
            com.kaiharimoto.mastertool.core.duel.net.Wire.Bye -> { netStatus = "${peer ?: "The other player"} left the table."; link?.close(); link = null }
            else -> Unit
        }
    }

    private fun windows(): Map<Int, String> = mapOf(0 to myWindows, 1 to guestWindows)

    fun hostAct(actions: List<DuelAction>, seat: Int): Boolean {
        val g = d.game ?: return false
        val r = com.kaiharimoto.mastertool.core.duel.net.DuelHost.act(g, seat, actions, windows(), forceNext, Duels.now())
        forceNext = false
        // A move made after asking to take back the last one: the ask is over.
        if (actions.any { !it.social }) hostAskedTakeBack = false
        if (!r.ok) { d.problem = r.problem; return false }
        d.game = r.game
        d.problem = null
        d.save()
        sendUpdate()
        return true
    }

    private fun sendUpdate(takeBackFrom: Int? = null) {
        val g = d.game ?: return
        val l = link ?: return
        sentTo = minOf(sentTo, g.cursor)
        l.send(com.kaiharimoto.mastertool.core.duel.net.DuelHost.update(g, 1, sentTo, g.header.seed, d.catalog, takeBackFrom, folds = d.folds(g)))
        sentTo = g.cursor
    }

    /** Joins the table [code] names, with [mine] as this player's deck. */
    fun join(code: String, mine: com.kaiharimoto.mastertool.core.duel.SeatSetup) {
        val table = com.kaiharimoto.mastertool.core.duel.net.PairCode.decode(code) ?: run { d.problem = "That is not a table's code"; return }
        leave()
        role = NetRole.GUEST
        d.bottom = 1
        netStatus = "Joining…"
        d.scope.launch {
            val socket = runCatching { dial(table) }.getOrElse {
                d.problem = "Could not reach the table at ${table.host}. Both of you need to be on the same network."
                leave()
                return@launch
            }
            val l = DuelLink(socket, ::guestHears) { _ ->
                link = null
                netStatus = "The table closed. Join again with the same code to sit back down."
            }
            link = l
            l.start()
            joinedSecret = table.secret
            if (token == null) token = withContext(Dispatchers.IO) { runCatching { tokenFile(table.secret).takeIf { it.exists() }?.readText()?.trim() }.getOrNull() }
            l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Hello(name = mine.name, main = mine.main, extra = mine.extra, deckName = mine.deckName, secret = table.secret, token = token, windows = myWindows))
        }
    }

    private fun guestHears(w: com.kaiharimoto.mastertool.core.duel.net.Wire) {
        when (w) {
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Welcome -> {
                token = w.token
                joinedSecret?.let { secret ->
                    val t = w.token
                    d.scope.launch(Dispatchers.IO) { runCatching { tokenFile(secret).apply { parentFile?.mkdirs() }.writeText(t) } }
                }
                peer = w.hostName.ifBlank { "Host" }
                netStatus = "Playing $peer over the network"
                d.setupOpen = false
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Update -> {
                remote = com.kaiharimoto.mastertool.core.duel.net.DuelMirror.game(w.view, DuelHeader(id = "remote"))
                val first = w.lines.firstOrNull()?.i ?: w.cursor
                remoteLines = remoteLines.filter { it.i < first && it.i < w.cursor } + w.lines
                remoteWaiting = w.waitingFor
                takeBackAsked = w.takeBackFrom
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Refused -> d.problem = w.reason
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Rejected -> { d.problem = w.reason; leave() }
            else -> Unit
        }
    }

    fun ask(actions: List<DuelAction>): Boolean {
        val l = link ?: run { d.problem = "Not connected to the table"; return false }
        l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Intent(++seq, actions, forceNext))
        forceNext = false
        return true
    }

    private var hostAskedTakeBack = false

    /** Asks the other player to let this one take back its last move. */
    fun askTakeBack() {
        when (role) {
            NetRole.GUEST -> link?.send(com.kaiharimoto.mastertool.core.duel.net.Wire.TakeBack(ask = true))
            NetRole.HOST -> { hostAskedTakeBack = true; sendUpdate(takeBackFrom = 0) }
            null -> Unit
        }
        d.problem = "Asked ${peer ?: "the other player"} to let you take it back"
    }

    /** The answer to a take-back request shown to this player. */
    fun answerTakeBack(yes: Boolean) {
        val asker = takeBackAsked ?: return
        takeBackAsked = null
        when (role) {
            NetRole.HOST -> if (yes) takeBack(asker) else link?.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Refused(0, "${hostSeat?.name ?: "The host"} would rather you did not take it back"))
            NetRole.GUEST -> link?.send(com.kaiharimoto.mastertool.core.duel.net.Wire.TakeBack(ask = false, yes = yes))
            null -> Unit
        }
    }

    /** [seat]'s last move undone on the host's table, when it is that seat's. */
    private fun takeBack(seat: Int) {
        val g = d.game ?: return
        val last = g.entries.getOrNull(g.cursor - 1) ?: return
        if (last.seat != seat || !g.canUndo) { d.problem = "The last move is not theirs to take back"; return }
        d.game = g.undo()
        d.save()
        sendUpdate()
    }

    /** Leaves the networked table; the duel stays on this device. */
    fun leave() {
        link?.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Bye)
        link?.close()
        link = null
        hosting?.close()
        hosting = null
        if (role == NetRole.GUEST) { remote = null; remoteLines = emptyList(); d.bottom = 0 }
        role = null
        netCode = null
        netStatus = null
        peer = null
        remoteWaiting = null
        takeBackAsked = null
        // The guest seat and its token belong to that table only (1.0.85: a second hosted game refused every guest).
        guestToken = null
        guestDuel = null
        d.attacking = null
        hostAskedTakeBack = false
        d.aiWatch.forgetTriggers(clearWatches = false)
    }
}
