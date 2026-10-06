package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelRandom
import com.kaiharimoto.mastertool.core.duel.DuelRecord
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelTimeline
import com.kaiharimoto.mastertool.core.duel.replay.Past
import com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit
import com.kaiharimoto.mastertool.core.duel.replay.Replays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Replays (1.0.75), a part of [Duels]: the library in `<data>/duel/replays/`, the replay open on the table and its
 * edits, a what-if branched from one, and a move put into the past of the duel in play (1.0.80). [Duels] forwards every
 * member under its own name.
 */
internal class DuelReplays(private val d: Duels) {
    /** The replay open on the table, if any: the table shows it instead of the duel in play. */
    var replay by mutableStateOf<Replay?>(null)
    var replays by mutableStateOf<List<ReplayInfo>>(emptyList())
    var libraryOpen by mutableStateOf(false)
    /** Where the duel in play came from, when it is a "what if" played on from a replay. */
    var origin: Pair<String, Int>? = null
    private var timeline: DuelTimeline? = null
    private var timelineOf: DuelRecord? = null
    private var refusedOf: Pair<DuelRecord, Set<Int>>? = null

    /** The entries of the open replay that no longer fit the table after an edit: struck through. */
    fun refused(): Set<Int> {
        val r = replay ?: return emptySet()
        refusedOf?.let { (rec, set) -> if (rec === r.record) return set }
        val set = Replays.refused(r.record)
        refusedOf = r.record to set
        return set
    }

    /** The open replay's table where it stands, with the record and the place it was made for (1.0.92). */
    private var shownAt: Triple<DuelRecord, Int, DuelGame>? = null

    /**
     * [r]'s table at [Replay.at], made once a place (1.0.92): the table asks after it dozens of times a frame (whose seat a
     * card is, what it shows), and each of those folded the timeline again and made a new game of it.
     */
    fun shown(r: Replay): DuelGame {
        shownAt?.let { (rec, at, g) -> if (rec === r.record && at == r.at) return g }
        val t = timelineFor(r.record)
        val floor = r.record.entries.indexOfFirst { it.seat != null }.let { if (it < 0) r.record.entries.size else it }
        val g = DuelGame(r.record.header, r.record.entries, r.at, t.at(r.at).first, minOf(floor, r.at))
        shownAt = Triple(r.record, r.at, g)
        return g
    }

    fun timelineFor(r: DuelRecord): DuelTimeline {
        if (timelineOf !== r) {
            timeline = Replays.timeline(r)
            timelineOf = r
        }
        return timeline!!
    }

    private val replayDir: File get() = File(d.dir, "replays")

    /** Reads the library of replays: names and sizes, newest first. */
    fun loadReplays() {
        d.scope.launch {
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
        val g = d.game ?: return
        val id = "r${Duels.now()}"
        val record = g.record(name.ifBlank { "Duel of ${java.text.SimpleDateFormat("d MMM, HH:mm").format(java.util.Date())}" }, origin?.first, origin?.second, Duels.now())
        d.scope.launch {
            writeReplay(id, record)
            loadReplays()
        }
    }

    /** A game played elsewhere — an Ai vs Ai match (`docs/phases/C.md` §6) — kept as a replay under [name]. */
    fun keepReplay(name: String, g: DuelGame) {
        val id = "r${Duels.now()}"
        val record = g.record(name, saved = Duels.now())
        d.scope.launch {
            writeReplay(id, record)
            loadReplays()
        }
    }

    fun openReplay(id: String) {
        // A replay is not the table Ai's answer was for (1.0.85): what waited on it goes, and the Spotlight with it.
        d.aiWatch.forgetTriggers(clearWatches = false)
        d.spot.closeSpotlight()
        d.attacking = null
        d.scope.launch {
            val r = withContext(Dispatchers.IO) { File(replayDir, "$id.json").takeIf { it.exists() }?.readText()?.let(DuelCodec::decode) }
            if (r == null) { d.problem = "That replay could not be read"; return@launch }
            val floor = r.entries.indexOfFirst { it.seat != null }.let { if (it < 0) r.entries.size else it }
            replay = Replay(id, r, floor)
            libraryOpen = false
            d.picking.clearSelection()
            d.strip = null
            d.placed = null
        }
    }

    /**
     * [game] opened as a replay to watch, never written (Phase D step 4: a goldfish hand, `GoldfishReplay`): it stands at the
     * deal, its line a step at a time ahead; edits stay on screen, and [keepOpen] puts it in the library when the person asks.
     */
    fun openGame(name: String, game: DuelGame) {
        d.aiWatch.forgetTriggers(clearWatches = false)
        d.spot.closeSpotlight()
        d.attacking = null
        val record = game.record(name, saved = Duels.now())
        val floor = record.entries.indexOfFirst { it.seat != null }.let { if (it < 0) record.entries.size else it }
        closeReplay()
        replay = Replay("g${Duels.now()}", record, floor, kept = false)
        libraryOpen = false
        d.picking.clearSelection()
        d.strip = null
        d.placed = null
    }

    /** The replay on screen, opened from a game ([openGame]), kept in the library as it stands now. */
    fun keepOpen() {
        val r = replay ?: return
        if (r.kept) return
        replay = r.copy(kept = true)
        d.scope.launch {
            writeReplay(r.id, r.record.copy(saved = Duels.now()))
            loadReplays()
        }
    }

    fun deleteReplay(id: String) {
        d.scope.launch {
            withContext(Dispatchers.IO) { File(replayDir, "$id.json").delete() }
            if (replay?.id == id) replay = null
            loadReplays()
        }
    }

    fun closeReplay() {
        replay = null
        timeline = null
        timelineOf = null
        shownAt = null
    }

    /** The replay moved to [at], within its log. */
    fun seek(at: Int) {
        val r = replay ?: return
        replay = r.copy(at = at.coerceIn(0, r.record.entries.size))
    }

    fun step(unit: ReplayUnit, dir: Int) {
        val r = replay ?: return
        val e = r.record.entries
        val to = if (dir > 0) Replays.next(e, r.at, unit)
        else Replays.previous(e, r.at, unit)
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
        val unit = ReplayUnit.GROUP
        val to = if (r.playing > 0) Replays.next(e, r.at, unit)
        else Replays.previous(e, r.at, unit)
        if (to == r.at) { replay = r.copy(playing = 0); return false }
        replay = r.copy(at = to)
        return true
    }

    /** Edits are written at once: the replay on disk is the replay on screen. */
    private fun edit(record: DuelRecord, at: Int) {
        val r = replay ?: return
        replay = r.copy(record = record, at = at.coerceIn(0, record.entries.size), playing = 0)
        // A game opened to watch is written only once the person keeps it.
        if (r.kept) d.scope.launch { writeReplay(r.id, record) }
    }

    /** Actions done on the table while a replay is open go into it where it stands. */
    fun insert(actions: List<DuelAction>, seat: Int?): Boolean {
        val r = replay ?: return false
        val g = d.shown ?: return false
        val stamped = actions.mapIndexed { k, a -> DuelRandom.stamp(a, DuelRandom.forEntry(r.record.header.seed, r.at + k + 7919)) }
        val (ok, why) = DuelRules.applyAll(g.state, stamped, seat)
        if (ok == null) { d.problem = why; return false }
        edit(Replays.insert(r.record, r.at, stamped, seat, Duels.now(), by = d.provenance()), r.at + stamped.size)
        return true
    }

    /**
     * [actions] put into the log after entry [at] of the duel in play (1.0.80): a move made in a phase
     * already gone by. Everything after folds on top of it; a later move it makes impossible is struck
     * through in the log, never refused. What it leaves to chance comes from that place's own dice, and a move
     * that would change what was drawn since is refused (Phase C, [Past.stamp], [Past.redeals]): never a way to
     * fish for a better hand.
     */
    fun insertPast(at: Int, actions: List<DuelAction>, seat: Int?): Boolean {
        if (!d.aiWatch.aiActing && d.aiWatch.waitingOnAi) { d.problem = "Ai is answering your move — Don't wait first."; return false }
        val g = d.game ?: return false
        val k = at.coerceIn(g.floor, g.cursor)
        val stamped = Past.stamp(g.header, g.played, k, actions)
        val (ok, why) = DuelRules.applyAll(d.folds(g).sync(g.entries).stateAt(k), stamped, seat)
        if (ok == null) { d.problem = why; return false }
        Past.redeals(g.header, g.played, k, stamped, seat)?.let { d.problem = it; return false }
        val record = g.record().copy(entries = g.played, cursor = g.cursor)
        val inserted = Replays.insert(record, k, stamped, seat, Duels.now(), by = d.provenance())
        d.game = DuelGame.of(inserted)
        d.problem = null
        d.save()
        return true
    }

    /** The step just before where the replay stands, taken out. */
    fun deleteStep() {
        val r = replay ?: return
        if (r.at <= 0) return
        val start = Replays.previous(r.record.entries, r.at, ReplayUnit.GROUP)
        edit(Replays.deleteGroup(r.record, r.at - 1), start)
    }

    fun note(text: String) {
        val r = replay ?: return
        if (text.isBlank()) return
        edit(Replays.annotate(r.record, r.at, text.trim(), d.bottom), r.at + 1)
    }

    /** "What if": the duel as it stood here, as the duel in play, to play on from. */
    fun branch() {
        val r = replay ?: return
        d.aiWatch.forgetTriggers(clearWatches = true)
        d.attacking = null
        d.game = Replays.branch(r.record, r.at)
        origin = r.id to r.at
        closeReplay()
        d.picking.clearSelection()
        d.save()
    }

    private suspend fun writeReplay(id: String, record: DuelRecord) = withContext(Dispatchers.IO) {
        d.io.withLock {
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
}
