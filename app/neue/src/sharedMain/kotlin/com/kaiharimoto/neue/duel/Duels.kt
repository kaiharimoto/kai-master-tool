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
        val g = game ?: return bottom
        val card = g.state.cards[uid] ?: return bottom
        return if (g.state.placeOf(uid) is Place.Zone) card.controller else card.owner
    }

    /** Commits [actions] as one group by [seat]. False, and the reason said, when the table refuses. */
    fun act(actions: List<DuelAction>, seat: Int? = bottom): Boolean {
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
        val g = game ?: return false
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
        val g = game ?: return false
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
        val g = game ?: return
        if (!g.canUndo) return
        game = g.undo()
        placed = null
        problem = null
        save()
    }

    fun redo() {
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
            temp.writeText(DuelCodec.encode(g.record()))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    /** What the knowledge setting lets the table show: both seats' eyes, or the bottom seat's alone. */
    fun viewers(prefs: DuelPrefs): Set<Int> = when {
        game?.state?.solo == true -> setOf(0)
        prefs.knowledge == DuelPrefs.KNOW_SEAT -> setOf(bottom)
        else -> setOf(0, 1)
    }

    companion object {
        const val CURRENT = "current.json"
        const val PLACED_MS = 2500L
        fun now(): Long = System.currentTimeMillis()
    }
}
