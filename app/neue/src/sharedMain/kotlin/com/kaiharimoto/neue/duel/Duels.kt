package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.DuelRecord
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A saved replay, as the library lists it. */
data class ReplayInfo(val id: String, val name: String, val saved: Long, val entries: Int, val decks: String, val parent: String?)

/**
 * A replay open on the table: its record, where it stands ([at] entries played), and whether it is
 * playing forwards (1), backwards (-1) or still (0), at [speed].
 */
data class Replay(val id: String, val record: DuelRecord, val at: Int, val playing: Int = 0, val speed: Float = 1f)

/** A card just put in a zone by a key or a click: for a moment a number moves it to another zone. */
data class Placed(val uid: Int, val kind: ZoneKind, val seat: Int, val until: Long)

/**
 * The duel on the Duel page (1.0.74), for the app's lifetime: the game (the log and the table it folds
 * to), who sits at the bottom, what is selected, hovered and open. Every change to the table goes
 * through [act], one group to the log, so undo, the log in words and — later — replays, Ai and the
 * network all see the same thing.
 *
 * The duel in play is kept in `<data>/duel/current.json` after every change, so closing the window
 * mid-duel, or a crash, loses nothing.
 */
class Duels(val dir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()

    var game by mutableStateOf<DuelGame?>(null)
    /** The seat drawn at the bottom of the table: the one acting, in a hot-seat. */
    var bottom by mutableStateOf(0)
    var selection by mutableStateOf<Set<Int>>(emptySet())
    /** The card under the pointer, which the keys act on. */
    var hovered by mutableStateOf<Int?>(null)
    /** The card the inspector reads: the last clicked, or the hovered. */
    var inspected by mutableStateOf<Int?>(null)
    /** The pile laid open above the hand. */
    var strip by mutableStateOf<Pair<Int, PileKind>?>(null)
    /** A card waiting for the card it goes under (the O key, the inspector's Attach). */
    var attaching by mutableStateOf<Int?>(null)
    var placed by mutableStateOf<Placed?>(null)
    /** What the table refused, said once. */
    var problem by mutableStateOf<String?>(null)
    var lpPad by mutableStateOf<Int?>(null)
    var command by mutableStateOf("")
    var chat by mutableStateOf("")
    /** Bumped to take the keyboard to the command line or the chat. */
    var commandFocus by mutableStateOf(0)
    var chatFocus by mutableStateOf(0)
    var setupOpen by mutableStateOf(false)
    /** On a narrow window, the rail shown as a drawer: "card", "log" or null. */
    var drawer by mutableStateOf<String?>(null)
    /** The card whose every verb is shown, held open (a long press). */
    var verbsOpen by mutableStateOf(false)
    var catalog: DuelCatalog = DuelCatalog.NONE

    /** The replay open on the table, if any: the table shows it instead of the duel in play. */
    var replay by mutableStateOf<Replay?>(null)
    var replays by mutableStateOf<List<ReplayInfo>>(emptyList())
    var libraryOpen by mutableStateOf(false)
    /** Where the duel in play came from, when it is a "what if" played on from a replay. */
    private var origin: Pair<String, Int>? = null
    private var timeline: com.kaiharimoto.mastertool.core.duel.DuelTimeline? = null
    private var timelineOf: DuelRecord? = null
    private var refusedOf: Pair<DuelRecord, Set<Int>>? = null

    /** What the table shows: the replay where it stands, or the duel in play. */
    val shown: DuelGame?
        get() {
            val r = replay ?: return game
            val t = timelineFor(r.record)
            val floor = r.record.entries.indexOfFirst { it.seat != null }.let { if (it < 0) r.record.entries.size else it }
            return DuelGame(r.record.header, r.record.entries, r.at, t.at(r.at).first, minOf(floor, r.at))
        }

    /** The entries of the open replay that no longer fit the table after an edit: struck through. */
    fun refused(): Set<Int> {
        val r = replay ?: return emptySet()
        refusedOf?.let { (rec, set) -> if (rec === r.record) return set }
        val set = com.kaiharimoto.mastertool.core.duel.replay.Replays.refused(r.record)
        refusedOf = r.record to set
        return set
    }

    private fun timelineFor(r: DuelRecord): com.kaiharimoto.mastertool.core.duel.DuelTimeline {
        if (timelineOf !== r) {
            timeline = com.kaiharimoto.mastertool.core.duel.replay.Replays.timeline(r)
            timelineOf = r
        }
        return timeline!!
    }

    private var loaded = false

    /** Reads the duel left in play, once. */
    fun load() {
        if (loaded) return
        loaded = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { File(dir, CURRENT).takeIf { it.exists() }?.readText() }
            val record = text?.let(DuelCodec::decode) ?: return@launch
            if (game == null) game = runCatching { DuelGame.of(record) }.getOrNull()
        }
    }

    /** Reads the duel in play again: a backup restored. */
    fun reload() {
        loaded = false
        game = null
        load()
    }

    fun start(header: DuelHeader) {
        game = DuelGame.start(header, now())
        origin = null
        closeReplay()
        bottom = 0
        selection = emptySet()
        strip = null
        attaching = null
        placed = null
        problem = null
        inspected = null
        save()
    }

    /** The seat acting on [uid]: its controller on the field, its owner anywhere else. */
    fun seatFor(uid: Int): Int {
        val g = shown ?: return bottom
        val card = g.state.cards[uid] ?: return bottom
        return if (g.state.placeOf(uid) is Place.Zone) card.controller else card.owner
    }

    /** Commits [actions] as one group by [seat]. False, and the reason said, when the table refuses. */
    fun act(actions: List<DuelAction>, seat: Int? = bottom): Boolean {
        if (replay != null) return insert(actions, seat)
        val g = game ?: return false
        if (actions.isEmpty()) return false
        val r = g.act(actions, seat, now())
        if (!r.ok) {
            problem = r.problem
            return false
        }
        game = r.game
        problem = null
        save()
        return true
    }

    fun act(action: DuelAction, seat: Int? = bottom): Boolean = act(listOf(action), seat)

    /**
     * [verb] on [uid], or on the selection when [uid] is part of it. A verb that puts a card in a zone
     * remembers it for a moment, so the number keys can move it to the zone meant.
     */
    fun verb(uid: Int, verb: DuelVerb, zone: Place.Zone? = null, host: Int? = null, seat: Int? = null): Boolean {
        val g = shown ?: return false
        val actor = seat ?: seatFor(uid)
        val targets = if (uid in selection && selection.size > 1) selection.toList() else listOf(uid)
        if (targets.size > 1) {
            // Several at once: each in turn on the table as the one before left it, one group.
            var s = g.state
            val all = mutableListOf<DuelAction>()
            targets.forEach { u ->
                val r = DuelVerbs.actions(s, seatFor(u), u, verb, catalog)
                if (r.problem != null || r.needsHost) return@forEach
                val next = com.kaiharimoto.mastertool.core.duel.DuelRules.applyAll(s, r.actions).first ?: return@forEach
                all += r.actions
                s = next
            }
            selection = emptySet()
            return act(all, seatFor(uid))
        }
        val r = DuelVerbs.actions(g.state, actor, uid, verb, catalog, zone, host)
        if (r.needsHost) {
            attaching = uid
            problem = null
            return false
        }
        r.problem?.let { problem = it; return false }
        val ok = act(r.actions, actor)
        if (ok) {
            val now = game?.state?.placeOf(uid)
            placed = if (zone == null && now is Place.Zone && g.state.placeOf(uid) !is Place.Zone && now.kind != ZoneKind.FIELD) {
                Placed(uid, if (now.kind == ZoneKind.EMZ) ZoneKind.MONSTER else now.kind, now.seat, now() + PLACED_MS)
            } else null
        }
        return ok
    }

    /**
     * A number key just after a card was placed: the same placement, in the zone meant instead —
     * undone and done again, so the log keeps one entry.
     */
    fun replace(kind: ZoneKind, index: Int): Boolean {
        val p = placed?.takeIf { now() < it.until } ?: return false
        val g = game ?: return false
        val last = g.entries.getOrNull(g.cursor - 1) ?: return false
        val group = g.entries.filter { it.group == last.group }.map { it.action }
        val zone = Place.Zone(p.seat, kind, index)
        val moved = group.map { a -> if (a is DuelAction.Move && a.uid == p.uid && a.to is Place.Zone) a.copy(to = zone) else a }
        val undone = g.undo()
        val r = undone.act(moved, last.seat, now())
        if (!r.ok) { problem = r.problem; return false }
        game = r.game
        placed = p.copy(kind = if (kind == ZoneKind.EMZ) ZoneKind.MONSTER else kind, until = now() + PLACED_MS)
        save()
        return true
    }

    /** The command line's text, run for the seat at the bottom. */
    fun run(text: String): Boolean {
        val g = shown ?: return false
        return when (val p = DuelCommand.parse(text, g.state, bottom, catalog)) {
            is DuelCommand.Parsed.Problem -> { problem = p.text; false }
            is DuelCommand.Parsed.Actions -> act(p.actions, bottom).also { if (it) command = "" }
        }
    }

    fun say(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (act(DuelAction.Chat(bottom, t), bottom)) chat = ""
    }

    fun undo() {
        if (replay != null) { step(com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit.GROUP, -1); return }
        val g = game ?: return
        if (!g.canUndo) return
        game = g.undo()
        placed = null
        problem = null
        save()
    }

    fun redo() {
        if (replay != null) { step(com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit.GROUP, 1); return }
        val g = game ?: return
        if (!g.canRedo) return
        game = g.redo()
        placed = null
        save()
    }

    /** Sit at the other seat (a hot-seat's turn of the table). */
    fun swap() {
        val g = game ?: return
        if (g.state.solo) return
        bottom = 1 - bottom
        strip = null
    }

    fun openPile(seat: Int, kind: PileKind) {
        strip = if (strip == seat to kind) null else seat to kind
    }

    private var saveJob: Job? = null

    private fun save() {
        val g = game ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            write(g)
        }
    }

    /** The duel written now: the window closing. */
    fun flush() {
        val g = game ?: return
        saveJob?.cancel()
        scope.launch { write(g) }
    }

    private suspend fun write(g: DuelGame) = io.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, CURRENT)
            val temp = File(dir, "$CURRENT.tmp")
            temp.writeText(DuelCodec.encode(g.record(parent = origin?.first, parentAt = origin?.second)))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    // ---- replays --------------------------------------------------------------------------------------

    private val replayDir: File get() = File(dir, "replays")

    /** Reads the library of replays: names and sizes, newest first. */
    fun loadReplays() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                replayDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
                    val r = DuelCodec.decode(f.readText()) ?: return@mapNotNull null
                    ReplayInfo(
                        f.name.removeSuffix(".json"),
                        r.name.ifBlank { "Untitled duel" },
                        r.saved.takeIf { it > 0 } ?: f.lastModified(),
                        r.entries.count { it.seat != null },
                        r.header.seats.mapNotNull { it.deckName.ifBlank { null } }.joinToString(" v ").ifBlank { r.header.seats.joinToString(" v ") { it.name } },
                        r.parent,
                    )
                }.sortedByDescending { it.saved }
            }
            replays = list
        }
    }

    /** The duel in play kept as a replay, under [name]. */
    fun saveReplay(name: String) {
        val g = game ?: return
        val id = "r${now()}"
        val record = g.record(name.ifBlank { "Duel of ${java.text.SimpleDateFormat("d MMM, HH:mm").format(java.util.Date())}" }, origin?.first, origin?.second, now())
        scope.launch {
            writeReplay(id, record)
            loadReplays()
        }
    }

    fun openReplay(id: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { File(replayDir, "$id.json").takeIf { it.exists() }?.readText()?.let(DuelCodec::decode) }
            if (r == null) { problem = "That replay could not be read"; return@launch }
            val floor = r.entries.indexOfFirst { it.seat != null }.let { if (it < 0) r.entries.size else it }
            replay = Replay(id, r, floor)
            libraryOpen = false
            selection = emptySet()
            strip = null
            placed = null
        }
    }

    fun deleteReplay(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { File(replayDir, "$id.json").delete() }
            if (replay?.id == id) replay = null
            loadReplays()
        }
    }

    fun closeReplay() {
        replay = null
        timeline = null
        timelineOf = null
    }

    /** The replay moved to [at], within its log. */
    fun seek(at: Int) {
        val r = replay ?: return
        replay = r.copy(at = at.coerceIn(0, r.record.entries.size))
    }

    fun step(unit: com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit, dir: Int) {
        val r = replay ?: return
        val e = r.record.entries
        val to = if (dir > 0) com.kaiharimoto.mastertool.core.duel.replay.Replays.next(e, r.at, unit)
        else com.kaiharimoto.mastertool.core.duel.replay.Replays.previous(e, r.at, unit)
        replay = r.copy(at = to, playing = 0)
    }

    /** Plays the replay a step at a time in [direction] (1 forwards, -1 backwards), or stops it. */
    fun play(direction: Int) {
        val r = replay ?: return
        replay = r.copy(playing = if (r.playing == direction) 0 else direction)
    }

    fun speed(s: Float) {
        replay = replay?.copy(speed = s)
    }

    /** One step of playback; false when it has reached the end it was playing toward. */
    fun tick(): Boolean {
        val r = replay ?: return false
        val e = r.record.entries
        val unit = com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit.GROUP
        val to = if (r.playing > 0) com.kaiharimoto.mastertool.core.duel.replay.Replays.next(e, r.at, unit)
        else com.kaiharimoto.mastertool.core.duel.replay.Replays.previous(e, r.at, unit)
        if (to == r.at) { replay = r.copy(playing = 0); return false }
        replay = r.copy(at = to)
        return true
    }

    /** Edits are written at once: the replay on disk is the replay on screen. */
    private fun edit(record: DuelRecord, at: Int) {
        val r = replay ?: return
        replay = r.copy(record = record, at = at.coerceIn(0, record.entries.size), playing = 0)
        scope.launch { writeReplay(r.id, record) }
    }

    /** Actions done on the table while a replay is open go into it where it stands. */
    private fun insert(actions: List<DuelAction>, seat: Int?): Boolean {
        val r = replay ?: return false
        val g = shown ?: return false
        val stamped = actions.mapIndexed { k, a -> com.kaiharimoto.mastertool.core.duel.DuelRandom.stamp(a, com.kaiharimoto.mastertool.core.duel.DuelRandom.forEntry(r.record.header.seed, r.at + k + 7919)) }
        val (ok, why) = com.kaiharimoto.mastertool.core.duel.DuelRules.applyAll(g.state, stamped, seat)
        if (ok == null) { problem = why; return false }
        edit(com.kaiharimoto.mastertool.core.duel.replay.Replays.insert(r.record, r.at, stamped, seat, now()), r.at + stamped.size)
        return true
    }

    /** The step just before where the replay stands, taken out. */
    fun deleteStep() {
        val r = replay ?: return
        if (r.at <= 0) return
        val start = com.kaiharimoto.mastertool.core.duel.replay.Replays.previous(r.record.entries, r.at, com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit.GROUP)
        edit(com.kaiharimoto.mastertool.core.duel.replay.Replays.deleteGroup(r.record, r.at - 1), start)
    }

    fun note(text: String) {
        val r = replay ?: return
        if (text.isBlank()) return
        edit(com.kaiharimoto.mastertool.core.duel.replay.Replays.annotate(r.record, r.at, text.trim(), bottom), r.at + 1)
    }

    /** "What if": the duel as it stood here, as the duel in play, to play on from. */
    fun branch() {
        val r = replay ?: return
        game = com.kaiharimoto.mastertool.core.duel.replay.Replays.branch(r.record, r.at)
        origin = r.id to r.at
        closeReplay()
        selection = emptySet()
        save()
    }

    private suspend fun writeReplay(id: String, record: DuelRecord) = io.withLock {
        withContext(Dispatchers.IO) {
            replayDir.mkdirs()
            val target = File(replayDir, "$id.json")
            val temp = File(replayDir, "$id.json.tmp")
            temp.writeText(DuelCodec.encode(record))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    /** What the knowledge setting lets the table show: both seats' eyes, or the bottom seat's alone. */
    fun viewers(prefs: DuelPrefs): Set<Int> = when {
        shown?.state?.solo == true -> setOf(0)
        prefs.knowledge == DuelPrefs.KNOW_SEAT -> setOf(bottom)
        else -> setOf(0, 1)
    }

    companion object {
        const val CURRENT = "current.json"
        const val PLACED_MS = 2500L
        fun now(): Long = System.currentTimeMillis()
    }
}
