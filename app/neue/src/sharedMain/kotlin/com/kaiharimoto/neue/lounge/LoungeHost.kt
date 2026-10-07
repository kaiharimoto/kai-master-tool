package com.kaiharimoto.neue.lounge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.lounge.Lounge
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeAsk
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeDecks
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeResult
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeRules
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.lounge.RoomAiMemo
import com.kaiharimoto.mastertool.core.duel.lounge.RoomAiTurn
import com.kaiharimoto.mastertool.core.duel.lounge.RoomTable
import com.kaiharimoto.mastertool.core.duel.lounge.Viewer
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchPrompt
import com.kaiharimoto.mastertool.core.duel.match.MatchTable
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.sync.Sha256
import com.kaiharimoto.neue.duel.Duels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/**
 * The Lounge on kai's computer (`docs/LOUNGE.md`): who is here, the rooms, every room's duel, and what each member is
 * sent. Pure rules come from `:core` ([LoungeRules], [RoomTable]); this keeps their state, the members' tokens and
 * decks on disk under [dir] (`<data>/lounge/`), and the connections. Everything runs on the main thread — a handful of
 * friends, a message at a time — so the window's own Lounge screen reads the same state the server changes.
 *
 * A connection is a [Session]: whatever carries it (the server's WebSocket, kai's own window in-process) hands it what
 * arrives and closes it when it goes.
 */
class LoungeHost(
    private val dir: File,
    /** The pool's names and kinds, for the log's words. */
    private val catalog: () -> DuelCatalog,
    /** A duel finished in a room, kept with kai's replays. */
    private val keep: (name: String, game: com.kaiharimoto.mastertool.core.duel.DuelGame) -> Unit = { _, _ -> },
    private val now: () -> Long = { Duels.now() },
    /** Ai's players, on kai's connection (L5); null while Ai is off on kai's computer. */
    private val ai: () -> LoungeAiPlayers? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val random = SecureRandom()

    var lounge by mutableStateOf(Lounge())
        private set
    private val tables = HashMap<String, RoomTable>()
    private val sessions = LinkedHashMap<String, Session>()
    private val seen = HashMap<String, LoungeWire.Seated>()
    private val sentTo = HashMap<String, Int>()
    private val dirty = HashSet<String>()
    private var members: List<KeptMember> = emptyList()
    private var ticker: Job? = null
    private var roomCount = 0

    /** One connection: a member once it has said hi. */
    inner class Session(private val out: (LoungeWire) -> Unit, private val close: () -> Unit) {
        var member: String? = null
            internal set

        fun send(w: LoungeWire) = runCatching { out(w) }.let { }

        /** What the connection carried in. */
        fun hear(w: LoungeWire) = handle(this, w)

        /** The connection went. */
        fun gone() = dropped(this)

        fun shut() = runCatching { close() }.let { }
    }

    fun open(out: (LoungeWire) -> Unit, close: () -> Unit = {}): Session {
        if (ticker == null) {
            members = readMembers()
            ticker = scope.launch {
                while (isActive) {
                    delay(15_000)
                    change(LoungeAsk.Tick(now()))
                    push()
                }
            }
        }
        return Session(out, close)
    }

    /** The Lounge closed: everyone told, every room's duel kept. */
    fun shutdown() {
        tables.keys.toList().forEach(::stopAi)
        tables.forEach { (room, t) -> keepGame(room, t) }
        tables.clear()
        sessions.values.toList().forEach { it.send(LoungeWire.Rejected("kai closed the Lounge")); it.shut() }
        sessions.clear()
        lounge = Lounge()
        seen.clear()
        ticker?.cancel()
        ticker = null
    }

    // ---- what arrives -------------------------------------------------------------------------------------

    private fun handle(s: Session, w: LoungeWire) {
        val me = s.member
        if (w is LoungeWire.Hi) { hi(s, w); push(); return }
        if (me == null) { s.send(LoungeWire.Refused("Say hi first")); return }
        when (w) {
            is LoungeWire.Create -> act(s, LoungeAsk.Create(me, "r${++roomCount}-${(random.nextInt() ushr 8).toString(36)}", w.name))
            is LoungeWire.Enter -> act(s, LoungeAsk.Enter(me, w.room))
            is LoungeWire.Sit -> act(s, LoungeAsk.Sit(me, w.seat, now()))
            LoungeWire.Stand -> act(s, LoungeAsk.Stand(me))
            is LoungeWire.Ready -> ready(s, me, w.deck)
            is LoungeWire.Swap -> act(s, w.yes?.let { LoungeAsk.AnswerSwap(me, it) } ?: LoungeAsk.AskSwap(me))
            is LoungeWire.AiSeat -> seatAi(s, me, w)
            is LoungeWire.RoomSet -> act(s, LoungeAsk.SetRoom(me, w.room, w.ai, w.publicOnly))
            is LoungeWire.Close -> if (act(s, LoungeAsk.Close(me, w.room))) { stopAi(w.room); tables.remove(w.room)?.let { keepGame(w.room, it) } }
            is LoungeWire.Kick -> kick(s, me, w.who)
            is LoungeWire.Say -> say(me, w.text)
            LoungeWire.Decks -> s.send(LoungeWire.DeckList(deckList(me)))
            is LoungeWire.DeckGet -> readDeck(me, w.id)?.let { s.send(LoungeWire.Deck(w.id, it.name, it.text)) } ?: s.send(LoungeWire.Refused("That deck is gone"))
            is LoungeWire.DeckSave -> saveDeck(s, me, w)
            is LoungeWire.DeckDelete -> { deckFile(me, w.id)?.delete(); s.send(LoungeWire.DeckList(deckList(me))) }
            LoungeWire.End -> end(s, me)
            is LoungeWire.Table -> table(s, me, w.wire)
            LoungeWire.Bye -> dropped(s)
            else -> Unit
        }
        push()
    }

    private fun hi(s: Session, w: LoungeWire.Hi) {
        if (w.proto != LoungeWire.PROTO) {
            s.send(LoungeWire.Rejected("This page is another version of the Lounge (${w.proto}, kai's ${LoungeWire.PROTO}). Reload it."))
            s.shut()
            return
        }
        val known = w.token?.let { t -> members.firstOrNull { it.token == Sha256.hex(t) } }
        val id = known?.id ?: "m" + (random.nextLong() ushr 1).toString(36)
        val token = w.token?.takeIf { known != null } ?: ((random.nextLong() ushr 1).toString(36) + (random.nextLong() ushr 1).toString(36))
        val nick = w.nick.ifBlank { known?.nick.orEmpty() }
        when (val r = LoungeRules.apply(lounge, LoungeAsk.Join(id, nick))) {
            is LoungeResult.No -> { s.send(LoungeWire.Refused(r.why)); return }
            is LoungeResult.Ok -> lounge = r.lounge
        }
        // One connection a member: an older one (another tab) is let go.
        sessions.remove(id)?.takeIf { it !== s }?.let { it.send(LoungeWire.Rejected("You joined again elsewhere")); it.shut() }
        s.member = id
        sessions[id] = s
        seen.remove(id)
        val kept = KeptMember(id, lounge.member(id)?.nick ?: nick, Sha256.hex(token))
        members = members.filter { it.id != id } + kept
        writeMembers()
        s.send(LoungeWire.Welcome(id, token))
    }

    /** kai's own window joins as the host, in-process, with no token or passcode: it is kai's computer. */
    fun hostJoin(s: Session, nick: String) {
        when (val r = LoungeRules.apply(lounge, LoungeAsk.Join(HOST, nick.ifBlank { "kai" }, host = true))) {
            is LoungeResult.No -> { s.send(LoungeWire.Refused(r.why)); return }
            is LoungeResult.Ok -> lounge = r.lounge
        }
        s.member = HOST
        sessions[HOST] = s
        seen.remove(HOST)
        s.send(LoungeWire.Welcome(HOST, ""))
        push()
    }

    private fun dropped(s: Session) {
        val id = s.member ?: return
        if (sessions[id] !== s) return
        sessions.remove(id)
        seen.remove(id)
        change(LoungeAsk.Drop(id, now()))
        push()
    }

    /** [ask] made if the rules allow it; else the asker is told why. */
    private fun act(s: Session, ask: LoungeAsk): Boolean = when (val r = LoungeRules.apply(lounge, ask)) {
        is LoungeResult.Ok -> { lounge = r.lounge; true }
        is LoungeResult.No -> { s.send(LoungeWire.Refused(r.why)); false }
    }

    private fun change(ask: LoungeAsk) {
        (LoungeRules.apply(lounge, ask) as? LoungeResult.Ok)?.let { lounge = it.lounge }
    }

    private fun kick(s: Session, me: String, who: String) {
        if (!act(s, LoungeAsk.Kick(me, who))) return
        sessions.remove(who)?.let { it.send(LoungeWire.Rejected("kai asked you to leave")); it.shut() }
        seen.remove(who)
        members = members.filter { it.id != who }
        writeMembers()
    }

    private fun say(me: String, text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        val m = lounge.member(me) ?: return
        val said = LoungeWire.Said(me, m.nick, t, m.room)
        lounge.members.filter { it.room == m.room }.forEach { sessions[it.id]?.send(said) }
    }

    // ---- the rooms' duels -----------------------------------------------------------------------------------

    private fun ready(s: Session, me: String, deckId: String) {
        val kept = readDeck(me, deckId) ?: run { s.send(LoungeWire.Refused("That deck is gone")); return }
        val deck = LoungeDecks.read(kept.text) ?: run { s.send(LoungeWire.Refused("That deck could not be read")); return }
        if (deck.main.isEmpty()) { s.send(LoungeWire.Refused("Bring a deck with a Main Deck")); return }
        if (!act(s, LoungeAsk.Ready(me, deckId, kept.name))) return
        val (room, _) = lounge.seatOf(me) ?: return
        if (room.canStart) start(room.id)
    }

    private fun start(roomId: String) {
        val room = lounge.room(roomId) ?: return
        val players = ai()
        val seats = room.seats.map { seat ->
            // Ai plays the deck of whoever sat it down.
            val m = seat.member ?: seat.aiDeckOf?.takeIf { seat.ai } ?: return
            val kept = seat.deck?.let { readDeck(m, it) } ?: return
            val deck = LoungeDecks.read(kept.text) ?: return
            val name = if (seat.ai) players?.name ?: "Ai" else lounge.member(m)?.nick ?: "Player"
            SeatSetup(name, deck.main.map { it.value }, deck.extra.map { it.value }, null, kept.name)
        }
        if (room.seats.any { it.ai }) {
            val why = players?.unavailable() ?: if (players == null) "Ai is off on kai's computer" else null
            if (why != null) { roomSay(roomId, "Ai cannot sit down to play: $why"); return }
        }
        val at = now()
        tables[roomId] = RoomTable.start(DuelHeader(id = "lounge-$at", seed = random.nextLong(), seats = seats, created = at, openingRoll = true), at)
        change(LoungeAsk.Playing(roomId, true))
        lounge.members.filter { it.room == roomId }.forEach { sentTo.remove(it.id) }
        dirty += roomId
    }

    private fun end(s: Session, me: String) {
        val m = lounge.member(me) ?: return
        val room = lounge.room(m.room) ?: run { s.send(LoungeWire.Refused("Go into a room first")); return }
        // Ai against Ai has no player to end it: anyone watching may.
        val people = room.seats.any { it.member != null }
        if (room.seated(me) == null && !m.host && people) { s.send(LoungeWire.Refused("Only a player at the table, or kai, ends the duel")); return }
        stopAi(room.id)
        tables.remove(room.id)?.let { keepGame(room.id, it) }
        change(LoungeAsk.Playing(room.id, false))
        lounge.members.filter { it.room == room.id }.forEach { sessions[it.id]?.send(LoungeWire.Said(me, m.nick, "ended the duel", room.id)) }
    }

    private fun keepGame(roomId: String, t: RoomTable) {
        if (t.game.cursor <= t.game.floor) return
        val names = t.game.header.seats.joinToString(" v ") { it.name }
        runCatching { keep("Lounge · ${lounge.room(roomId)?.name ?: "room"} · $names", t.game) }
    }

    private fun table(s: Session, me: String, w: Wire) {
        val (room, seat) = lounge.seatOf(me) ?: run { s.send(LoungeWire.Table(Wire.Refused(0, "Sit down to play"))); return }
        val t = tables[room.id] ?: run { s.send(LoungeWire.Table(Wire.Refused(0, "No duel is on at this table yet: both players get ready"))); return }
        // Ai's move is being made on this table this moment: a person's waits for it, so neither is lost.
        if (room.id in aiMoving && w !is Wire.SetWindows) { s.send(LoungeWire.Table(Wire.Refused((w as? Wire.Intent)?.seq ?: 0, "A moment: ${ai()?.name ?: "Ai"} is moving"))); return }
        when (w) {
            is Wire.Intent -> {
                val made = t.intent(seat, w, now(), Provenance(by = Provenance.GUEST))
                made.refused?.let { why -> s.send(LoungeWire.Table(Wire.Refused(w.seq, why))) }
                tables[room.id] = made.table
            }
            is Wire.TakeBack -> if (w.ask) tables[room.id] = t.askTakeBack(seat) else {
                val asker = t.takeBackFrom
                val made = t.answerTakeBack(seat, w.yes)
                tables[room.id] = made.table
                // The one who asked hears the no (or why it could not be done); the one answering knows already.
                val askerMember = asker?.let { room.seats.getOrNull(it)?.member }
                made.refused?.let { why -> askerMember?.let { sessions[it] }?.send(LoungeWire.Table(Wire.Refused(0, why))) }
            }
            is Wire.SetWindows -> tables[room.id] = t.setWindows(seat, w.windows)
            else -> return
        }
        dirty += room.id
    }

    // ---- Ai at the tables (L5) ------------------------------------------------------------------------------

    private val aiJobs = HashMap<String, Job>()
    private val aiMemo = HashMap<String, RoomAiMemo>()
    private val aiPlayers = HashMap<Pair<String, Int>, MatchPlayer>()
    /** Rooms where Ai's move is being made on the table this moment (a tool call, not its thinking). */
    private val aiMoving = HashSet<String>()
    /** Why Ai stopped at a room, said once in its log. */
    private val aiStopped = HashMap<String, String>()
    private var aiCues = 0

    private fun seatAi(s: Session, me: String, w: LoungeWire.AiSeat) {
        if (w.on) {
            val players = ai() ?: run { s.send(LoungeWire.Refused("Ai is off on kai's computer")); return }
            players.unavailable()?.let { s.send(LoungeWire.Refused(it)); return }
            if (lounge.room(lounge.member(me)?.room)?.ai != true) { s.send(LoungeWire.Refused("Ai is not on in this room: kai turns it on")); return }
            val id = w.deck ?: run { s.send(LoungeWire.Refused("Choose the deck Ai plays")); return }
            val kept = readDeck(me, id) ?: run { s.send(LoungeWire.Refused("That deck is gone")); return }
            if (LoungeDecks.read(kept.text)?.main.isNullOrEmpty()) { s.send(LoungeWire.Refused("Bring a deck with a Main Deck")); return }
            if (!act(s, LoungeAsk.SeatAi(me, w.seat, true, id, kept.name))) return
        } else if (!act(s, LoungeAsk.SeatAi(me, w.seat, false))) return
        val room = lounge.room(lounge.member(me)?.room) ?: return
        if (room.canStart) start(room.id)
    }

    /** Ai plays its seats at [roomId] while it is owed a move: one loop a room, cue after cue, until a person's turn. */
    private fun driveAi(roomId: String) {
        if (aiJobs[roomId]?.isActive == true) return
        if (tables[roomId] == null) return
        aiJobs[roomId] = scope.launch { runCatching { aiLoop(roomId) }.onFailure { e -> aiStop(roomId, "Ai stopped: ${e.message ?: e::class.simpleName}") } }
    }

    private suspend fun aiLoop(roomId: String) {
        while (true) {
            val room = lounge.room(roomId) ?: return
            val t = tables[roomId] ?: return
            val seats = room.seats.indices.filter { room.seats[it].ai }.toSet()
            val players = ai()
            if (seats.isEmpty() || !room.ai || players == null) return
            val rules = players.rules
            val memo = aiMemo[roomId] ?: RoomAiMemo()
            when (val next = RoomAiTurn.next(t.game, seats, memo, rules)) {
                RoomAiTurn.Next.Wait, is RoomAiTurn.Next.Over -> return
                is RoomAiTurn.Next.Table -> {
                    if (!aiTable(roomId, next)) return
                    delay(players.paceMs)
                }
                is RoomAiTurn.Next.Cue -> {
                    players.unavailable()?.let { why -> aiStop(roomId, "${players.name} stops here: $why"); return }
                    aiCue(roomId, next, players, memo)
                }
            }
        }
    }

    /** The table's own move for Ai's seat (its dice, its draw, a turn ended or a pass made for it), its note first. */
    private fun aiTable(roomId: String, n: RoomAiTurn.Next.Table): Boolean {
        val t = tables[roomId] ?: return false
        var g = t.game
        n.note?.let { note -> g.act(listOf(DuelAction.Note(note)), null, now(), by = Provenance(Provenance.TABLE)).takeIf { it.ok }?.let { g = it.game } }
        if (n.actions.isNotEmpty()) {
            val r = g.act(n.actions, n.seat, now(), by = Provenance(Provenance.TABLE))
            if (!r.ok) { aiStop(roomId, "The table could not move for ${ai()?.name ?: "Ai"}: ${r.problem}"); return false }
            g = r.game
        }
        tables[roomId] = t.copy(game = g)
        dirty += roomId
        push()
        return true
    }

    private suspend fun aiCue(roomId: String, cue: RoomAiTurn.Next.Cue, players: LoungeAiPlayers, memo: RoomAiMemo) {
        val room = lounge.room(roomId) ?: return
        val start = tables[roomId] ?: return
        val seat = cue.seat
        val player = aiPlayers.getOrPut(roomId to seat) {
            val across = room.seats.getOrNull(1 - seat)
            val against = across?.member?.let { lounge.member(it)?.nick }
            players.player(seat, start.game.header.seats.getOrNull(seat)?.name ?: players.name, room.seats[seat].deckName, against)
        }
        val rules = players.rules
        val table = MatchTable(start.game, catalog(), rules, cardText = players::cardText, now = now, seatWindows = { tables[roomId]?.windows?.get(it) ?: Windows.ACTIVATIONS })
        table.onMove = { g ->
            tables[roomId]?.let { tables[roomId] = it.copy(game = g) }
            dirty += roomId
            push()
            delay(players.paceMs)
        }
        // Each tool call starts from the table as it is now — a person may have moved while Ai thought — and while it
        // runs, a person's move waits.
        val runner = table.runner(seat)
        val tools = ToolRunner { call ->
            tables[roomId]?.let { table.adopt(it.game) }
            aiMoving += roomId
            try { runner.run(call) } finally { aiMoving -= roomId }
        }
        table.beginCue(seat, cue.kind)
        val from = start.game.cursor
        val text = MatchPrompt.cue(table, seat, cue.kind, ++aiCues, memo.read[seat])
        val result = withTimeoutOrNull(rules.cueMillis) { player.cue(text, tools) } ?: CueResult(failed = "no answer in ${rules.cueMillis / 1000} s")
        table.endCue()
        players.spent(result.tokens)
        val now = tables[roomId] ?: return
        val after = RoomAiTurn.after(start.game, now.game, RoomAiTurn.Cued(seat, cue.kind, table.movesSince(from, seat), result.failed, result.tokens), memo, rules)
        aiMemo[roomId] = after.memo
        after.table?.let { aiTable(roomId, it) }
    }

    /** Ai stops at [roomId], saying [why] in its log once. */
    private fun aiStop(roomId: String, why: String) {
        if (aiStopped[roomId] == why) return
        aiStopped[roomId] = why
        tables[roomId]?.let { t ->
            t.game.act(listOf(DuelAction.Note(why)), null, now(), by = Provenance(Provenance.TABLE)).takeIf { it.ok }?.let { tables[roomId] = t.copy(game = it.game) }
            dirty += roomId
        }
    }

    /** Ai let go at [roomId]: its loop, its players, what it remembered. */
    private fun stopAi(roomId: String) {
        aiJobs.remove(roomId)?.cancel()
        aiMemo.remove(roomId)
        aiStopped.remove(roomId)
        aiMoving.remove(roomId)
        val players = ai()
        aiPlayers.keys.filter { it.first == roomId }.forEach { k -> aiPlayers.remove(k)?.let { p -> players?.release(p) } }
    }

    /** A line from the table to everyone in [roomId]. */
    private fun roomSay(roomId: String, text: String) {
        val said = LoungeWire.Said(TABLE, "The table", text, roomId)
        lounge.members.filter { it.room == roomId }.forEach { sessions[it.id]?.send(said) }
    }

    // ---- what goes out -----------------------------------------------------------------------------------

    /**
     * Everyone sent what changed: the Lounge, each member's place at the table, and each room's duel as each member there
     * may see it — the whole log to someone who just sat down or came back, only the new lines to everyone else.
     */
    private fun push() {
        val state = LoungeWire.State(lounge)
        sessions.values.forEach { it.send(state) }
        lounge.members.filter { it.online }.forEach { m ->
            val s = sessions[m.id] ?: return@forEach
            val room = lounge.room(m.room)
            val seated = LoungeWire.Seated(room?.id, room?.seated(m.id), room?.publicOnly == true)
            if (seen[m.id] != seated) {
                seen[m.id] = seated
                s.send(seated)
                sentTo.remove(m.id)
                room?.id?.let { dirty += it }
            }
        }
        // Ai's seats, wherever one is owed a move.
        lounge.rooms.filter { it.playing && it.seats.any { s -> s.ai } }.forEach { driveAi(it.id) }
        dirty.forEach { roomId ->
            val t = tables[roomId] ?: return@forEach
            val room = lounge.room(roomId) ?: return@forEach
            lounge.members.filter { it.room == roomId && it.online }.forEach { m ->
                val s = sessions[m.id] ?: return@forEach
                val viewer = room.seated(m.id)?.let { Viewer.Seat(it) } ?: Viewer.Watcher(room.publicOnly)
                s.send(LoungeWire.Table(t.update(viewer, sentTo[m.id] ?: 0, catalog())))
                sentTo[m.id] = t.game.cursor
            }
        }
        dirty.clear()
    }

    // ---- the members' decks, on disk ---------------------------------------------------------------------

    private fun deckDir(member: String) = File(dir, "decks/${member.filter { it.isLetterOrDigit() }}")
    private fun deckFile(member: String, id: String): File? = id.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }?.let { File(deckDir(member), "$it.json") }

    private fun readDeck(member: String, id: String): LoungeDecks.Kept? =
        deckFile(member, id)?.takeIf { it.isFile }?.let { f -> runCatching { json.decodeFromString(LoungeDecks.Kept.serializer(), f.readText()) }.getOrNull() }

    private fun deckList(member: String) = deckDir(member).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f ->
            val kept = runCatching { json.decodeFromString(LoungeDecks.Kept.serializer(), f.readText()) }.getOrNull() ?: return@mapNotNull null
            val deck = LoungeDecks.read(kept.text) ?: return@mapNotNull null
            LoungeDecks.info(f.name.removeSuffix(".json"), kept.name, deck)
        }

    private fun saveDeck(s: Session, me: String, w: LoungeWire.DeckSave) {
        val deck = LoungeDecks.read(w.text) ?: run { s.send(LoungeWire.Refused("That is not a deck: a .ydk file, a .ydkx, or a ydke:// code")); return }
        val existing = deckDir(me).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        val id = w.id?.takeIf { deckFile(me, it)?.isFile == true } ?: run {
            if (existing.size >= LoungeDecks.MAX_DECKS) { s.send(LoungeWire.Refused("You keep ${LoungeDecks.MAX_DECKS} decks here: delete one first")); return }
            "d" + (random.nextLong() ushr 1).toString(36)
        }
        val f = deckFile(me, id) ?: return
        f.parentFile.mkdirs()
        f.writeText(json.encodeToString(LoungeDecks.Kept.serializer(), LoungeDecks.Kept(LoungeDecks.name(w.name), LoungeDecks.text(w.text, deck))))
        s.send(LoungeWire.DeckList(deckList(me)))
        s.send(LoungeWire.Deck(id, LoungeDecks.name(w.name), f.readText().let { json.decodeFromString(LoungeDecks.Kept.serializer(), it).text }))
    }

    // ---- members kept across visits (their tokens as hashes) ---------------------------------------------

    @Serializable
    private data class KeptMember(val id: String, val nick: String, val token: String)

    private val membersFile get() = File(dir, "members.json")

    private fun readMembers(): List<KeptMember> =
        runCatching { json.decodeFromString(ListSerializer(KeptMember.serializer()), membersFile.readText()) }.getOrDefault(emptyList())

    private fun writeMembers() {
        runCatching {
            dir.mkdirs()
            val temp = File(dir, "members.json.tmp")
            temp.writeText(json.encodeToString(ListSerializer(KeptMember.serializer()), members))
            if (!temp.renameTo(membersFile)) { membersFile.delete(); temp.renameTo(membersFile) }
        }
    }

    companion object {
        /** kai's own member id: the computer the Lounge runs on. */
        const val HOST = "host"
        /** Who the table's own lines in a room are from. */
        const val TABLE = "table"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
