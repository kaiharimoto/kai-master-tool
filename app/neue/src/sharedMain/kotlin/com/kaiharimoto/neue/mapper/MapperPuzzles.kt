package com.kaiharimoto.neue.mapper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.BoardGoal
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.MapPuzzle
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.MapPuzzles
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.PuzzleSolve
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.Volatile

/**
 * Puzzles of the person's own (Phase M, M4; `docs/phases/M.md` §5): saved from the table on screen — the duel in play, a replay
 * at its step, a DuelingBook replay opened on the Duel page — kept in `<data>/duel/puzzles/` (synced and backed up with the
 * replays), solved off the frame thread with the person's Stop ([PuzzleSolve]), and opened on the Duel page as unsaved
 * replays: the position, or the line that solves it. Never at a table with another player, never in a match being watched.
 */
class MapperPuzzles internal constructor(private val h: NeueHolders) {
    private val scope = Background.scope("Puzzles", Dispatchers.Main)
    private val dir get() = File(File(Platform.dataDir, "duel"), MapPuzzles.FOLDER)
    private var started = false

    /** Every puzzle kept, newest first. */
    var puzzles by mutableStateOf<List<MapPuzzle>>(emptyList())
        private set

    /** Files a newer build wrote, left as they are. */
    var unreadable by mutableStateOf(0)
        private set

    var loaded by mutableStateOf(false)
        private set

    /** The puzzle chosen on the tab. */
    var selected by mutableStateOf<String?>(null)

    /** The puzzle being solved, and what went wrong last. */
    var solving by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<String?>(null)

    /** The save dialog is open (the Duel page's Table ▾). */
    var saving by mutableStateOf(false)

    /** This run's solves, by puzzle: what "Play the line" opens. */
    private val solved = mutableStateMapOf<String, PuzzleSolve.Solved>()

    /** One a search: a solve started again stops the one before it, whose thread the job's cancel cannot reach. */
    private class Flag {
        @Volatile
        var on = false
    }

    private var stopped = Flag()
    private var job: Job? = null

    fun load() {
        if (started) return
        started = true
        reload()
    }

    /** Read again (a puzzle synced from another device). */
    fun reload() {
        scope.launch {
            val (read, bad) = withContext(Dispatchers.IO) { read() }
            puzzles = read
            unreadable = bad
            loaded = true
        }
    }

    fun byId(id: String): MapPuzzle? = puzzles.firstOrNull { it.id == id }

    /** Read now when nothing was read yet (Ai's `mapper_solve`, which may come before the page). */
    suspend fun ensureLoaded() {
        if (loaded) return
        started = true
        val (read, bad) = withContext(Dispatchers.IO) { read() }
        puzzles = read
        unreadable = bad
        loaded = true
    }

    /** [id] solved as the tab's Solve solves it, waited for: the puzzle with its answer kept, or null when it could not be. */
    suspend fun solveAndWait(id: String): MapPuzzle? {
        solve(id)
        job?.join()
        return byId(id)?.takeIf { it.answer != null && solving == null && solved[id] != null }
    }

    /** The last solve of [id] this run, if any. */
    fun solvedNow(id: String): PuzzleSolve.Solved? = solved[id]

    // ---- saving from the table ------------------------------------------------------------------------------------------

    /** What the person sees of the table on screen: both seats, or the solving seat's own eyes. */
    private fun knows(): String {
        val seen = h.duel.viewers(h.neue.prefs.duel)
        return if (seen.containsAll(listOf(0, 1))) DuelBrief.FULL else DuelBrief.SELF
    }

    /** Why the table on screen cannot be saved as a puzzle now, or null. */
    fun saveRefusal(): String? {
        val d = h.duel
        val g = d.shown ?: return "No duel is on the table."
        if (d.role != null) return "A table with another player is theirs and yours: nothing is saved from it."
        if (d.spectating) return "An Ai vs Ai match is on the table."
        val seat = g.state.active
        if (!g.state.solo && d.aiEngaged && h.neue.prefs.duel.aiSeat == seat && d.replay == null) {
            return "It is ${h.ai.name}'s turn, and its hand is its own."
        }
        if (knows() == DuelBrief.SELF && seat !in d.viewers(h.neue.prefs.duel)) return "It is the turn of a seat whose hand you cannot see."
        return MapPuzzles.refusal(g)
    }

    /** The table on screen kept as a puzzle with [goal], named [title]; opens the Mapper's Puzzles tab on it. */
    fun save(goal: BoardGoal, title: String) {
        if (saveRefusal() != null) return
        val d = h.duel
        val g = d.shown ?: return
        val now = System.currentTimeMillis()
        val from = d.replay?.record?.name?.takeIf { it.isNotBlank() }?.let { "Replay: $it" } ?: if (d.replay != null) "A replay" else "The duel in play"
        val p = MapPuzzles.of(g, knows(), goal, "p$now", title, from, now) ?: return
        saving = false
        put(p)
        selected = p.id
        h.mapper.tab = MapperTab.PUZZLES
        h.neue.go(Page.MAPPER)
    }

    fun rename(id: String, title: String) {
        val p = byId(id) ?: return
        val t = title.trim()
        if (t.isEmpty() || t == p.title) return
        put(p.copy(title = t, record = p.record.copy(name = t)))
    }

    fun delete(id: String) {
        if (solving == id) stop()
        puzzles = puzzles.filterNot { it.id == id }
        solved.remove(id)
        if (selected == id) selected = null
        scope.launch(Dispatchers.IO + NonCancellable) { File(dir, "${MapPuzzles.safe(id)}.json").delete() }
    }

    // ---- solving ------------------------------------------------------------------------------------------------------------

    /** Solves [id] off the frame thread; its answer is kept on the puzzle as it ends. */
    fun solve(id: String) {
        val p = byId(id) ?: return
        PuzzleSolve.refusal(p)?.let { problem = it; return }
        stopped.on = true
        job?.cancel()
        problem = null
        solving = id
        val kit = h.goldfishKit()
        val flag = Flag().also { stopped = it }
        job = scope.launch {
            val got = withContext(Dispatchers.Default) {
                runCatching { PuzzleSolve.solve(p, kit, by = Provenance(Provenance.TABLE)) { flag.on } }
            }
            solving = null
            got.onSuccess { s ->
                if (s == null) {
                    problem = PuzzleSolve.refusal(p) ?: "It could not be solved."
                    return@onSuccess
                }
                solved[id] = s
                val answer = PuzzleSolve.answer(s, kit, ::name, System.currentTimeMillis())
                byId(id)?.let { put(it.copy(answer = answer)) }
                if (s.turn.problem != null) problem = "The line could not be played out to the end: ${s.turn.problem}"
            }.onFailure { problem = "The search failed: ${it.message ?: it::class.simpleName}" }
        }
    }

    /** Stops the solve: what it found so far stands, and is kept. */
    fun stop() {
        stopped.on = true
    }

    /** Whether [p]'s kept answer was found with other written effects than the library holds now. */
    fun stale(p: MapPuzzle): Boolean = p.answer?.let { PuzzleSolve.stale(p, it, h.goldfishKit()) } == true

    /** The position on the Duel page, as the solver sees it: an unsaved replay of a table of its own. */
    fun openPosition(id: String) {
        val p = byId(id) ?: return
        val g = PuzzleSolve.position(p) ?: run { problem = "The position could not be read."; return }
        h.duel.openGame("Puzzle: ${p.title}", g)
        h.neue.go(Page.DUEL)
    }

    /** The line that solves [id], on the Duel page as an unsaved replay. */
    fun playLine(id: String) {
        val s = solved[id]?.takeIf { it.met } ?: return
        h.duel.openGame("Solved: ${s.puzzle.title}", s.turn.game)
        h.neue.go(Page.DUEL)
    }

    fun name(code: Int): String = h.builder.index.byId(CardId(code))?.name ?: "#$code"

    /** A puzzle and its solve put beside the rest without a search (the studio's pictures). */
    fun keep(p: MapPuzzle, s: PuzzleSolve.Solved?) {
        started = true
        loaded = true
        put(p)
        if (s != null) solved[p.id] = s
    }

    // ---- the folder ---------------------------------------------------------------------------------------------------------

    private fun put(p: MapPuzzle) {
        puzzles = (puzzles.filterNot { it.id == p.id } + p).sortedByDescending { it.created }
        scope.launch(Dispatchers.IO + NonCancellable) { write(p) }
    }

    private fun read(): Pair<List<MapPuzzle>, Int> {
        var bad = 0
        val read = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") && !it.name.startsWith(".") }.mapNotNull { f ->
            MapPuzzles.decode(f.readText()) ?: run { bad++; null }
        }
        return read.sortedByDescending { it.created } to bad
    }

    private fun write(p: MapPuzzle) {
        dir.mkdirs()
        val target = File(dir, "${MapPuzzles.safe(p.id)}.json")
        val temp = File(dir, ".${target.name}.tmp")
        temp.writeText(MapPuzzles.encode(p))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }
}
