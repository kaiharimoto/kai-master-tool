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
    /** The pile laid open over the field. */
    var strip by mutableStateOf<Pair<Int, PileKind>?>(null)
    /** The first row of a long open pile in view, when it scrolls. */
    var stripRow by mutableStateOf(0)
    /** A card is being carried across the table: the window's bars stay folded (1.0.78). */
    var carrying by mutableStateOf(false)
    /** A deck looked through and closed: "Shuffle Deck" stands by it until this time (ms), for this seat. */
    var offerShuffle by mutableStateOf<Pair<Int, Long>?>(null)
    /** Where the bar's Table menu opens, in the window. */
    var tableMenuAt = androidx.compose.ui.geometry.Offset(320f, 48f)
    /** A card waiting for the card it goes under (the O key, the inspector's Attach). */
    var attaching by mutableStateOf<Int?>(null)
    /** A monster waiting for what it attacks (1.0.86): the next click on their monster, or their life points. */
    var attacking by mutableStateOf<Int?>(null)
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
    /** The verb strip beside the selected card (1.0.78): shown for [inspected] while true. Shown or put away, its keyboard cursor goes. */
    var verbStrip: Boolean
        get() = verbStripShown
        set(v) { verbStripShown = v; verbCursor = null }
    private var verbStripShown by mutableStateOf(false)

    // ---- Command mode (1.0.87): a focus the arrows walk ------------------------------------------------

    /** What moved last, the keys or the pointer: the keys act on the focus after the one, on the hover after the other. */
    enum class Input { KEYS, POINTER }

    /** Where the arrows stand on the table (`DuelFocus`), or nowhere yet. */
    var focus by mutableStateOf<com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot?>(null)
        private set
    /** The card the focus was put on, so it goes with the card when the card moves; null on a place or a pile. */
    private var focusCard: Int? = null
    /** Set to [Input.KEYS] by the focus's keys, to [Input.POINTER] by the pointer moving over the table. */
    var lastInput by mutableStateOf(Input.POINTER)
    /** A card picked up by the keys (Shift Enter), put down where Enter is pressed next. */
    var picked by mutableStateOf<Int?>(null)
    /** The verb highlighted in the verb strip opened by Enter; null while the strip is the pointer's (or shut). */
    var verbCursor by mutableStateOf<Int?>(null)
    /** The table as last drawn (set by `DuelTable`): the focus's grid is read off its shape. */
    var tableLayout: com.kaiharimoto.mastertool.core.layout.DuelLayout? = null
    /** Whose eyes the table is drawn through, and the veils' secret (set by `DuelTable`): a hidden hand's order. */
    var eyes: com.kaiharimoto.mastertool.core.layout.DuelFocus.Eyes = com.kaiharimoto.mastertool.core.layout.DuelFocus.Eyes.ALL

    /** The keys are in charge: the ring is drawn and the keys act on it. */
    val byKeys: Boolean get() = lastInput == Input.KEYS && focus != null

    /** The grid's shape: the table as drawn, an open pile's cards to a row. */
    fun focusShape(): com.kaiharimoto.mastertool.core.layout.DuelFocus.Shape {
        val l = tableLayout ?: return com.kaiharimoto.mastertool.core.layout.DuelFocus.Shape(twoSided = shown?.state?.solo == false)
        val per = strip?.let { (seat, kind) -> shown?.state?.seats?.get(seat)?.pile(kind)?.size }?.let { com.kaiharimoto.mastertool.core.layout.DuelFrames.stripGrid(it, l).perRow } ?: 1
        return com.kaiharimoto.mastertool.core.layout.DuelFocus.Shape.of(l, per)
    }

    /** The card the focus stands on, or null for an empty place (or no focus). */
    fun focusUid(): Int? {
        val f = focus ?: return null
        val s = shown?.state ?: return null
        return com.kaiharimoto.mastertool.core.layout.DuelFocus.uidAt(s, f, eyes)
    }

    /**
     * The card a key acts on: the focus's once the keys moved last (null on an empty place — a key there does
     * nothing), else the card under the pointer, the one selected, or the one being read, as before 1.0.87.
     */
    fun keyTarget(): Int? = if (byKeys) focusUid() else hovered ?: selection.singleOrNull() ?: inspected

    /** The card the inspector reads: the focus's while the keys lead, else the hovered or the last clicked. */
    fun reading(): Int? = (if (byKeys) focusUid() else null) ?: hovered ?: inspected

    /** Puts the focus on [slot]: the keys lead, the card there is read, a pile's open strip scrolls to it. */
    fun focusOn(slot: com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot?) {
        focus = slot
        lastInput = Input.KEYS
        val s = shown?.state
        focusCard = if (slot == null || s == null || slot is com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Pile) null
        else com.kaiharimoto.mastertool.core.layout.DuelFocus.uidAt(s, slot, eyes)?.takeIf { followable(s, it) }
        if (slot is com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.PileCard) {
            val l = tableLayout ?: return
            val n = s?.seats?.get(slot.seat)?.pile(slot.kind)?.size ?: return
            val grid = com.kaiharimoto.mastertool.core.layout.DuelFrames.stripGrid(n, l)
            val row = slot.index / grid.perRow
            if (row < stripRow) stripRow = row
            if (row >= stripRow + grid.visibleRows) stripRow = row - grid.visibleRows + 1
        }
    }

    /** The arrows: a step on the table ([com.kaiharimoto.mastertool.core.layout.DuelFocus.step]); the first press starts at home. */
    fun walk(dir: com.kaiharimoto.mastertool.core.layout.DuelFocus.Dir) {
        val s = shown?.state ?: return
        verbStrip = false
        val shape = focusShape()
        if (lastInput != Input.KEYS || focus == null) {
            // The keys take over: on the card under the pointer, else where the ring was left, else home.
            val start = hovered?.let { com.kaiharimoto.mastertool.core.layout.DuelFocus.slotOf(s, it, strip, eyes) }
                ?.let { com.kaiharimoto.mastertool.core.layout.DuelFocus.settle(it, s, bottom, shape) }
                ?: focus?.let { com.kaiharimoto.mastertool.core.layout.DuelFocus.settle(it, s, bottom, shape) }
            focusOn(start ?: com.kaiharimoto.mastertool.core.layout.DuelFocus.home(s, bottom))
            return
        }
        focusOn(com.kaiharimoto.mastertool.core.layout.DuelFocus.step(focus, dir, s, bottom, shape))
    }

    /** Shift ← / → : the row's first or last place. */
    fun walkRow(end: Boolean) {
        val s = shown?.state ?: return
        verbStrip = false
        val shape = focusShape()
        focusOn(if (end) com.kaiharimoto.mastertool.core.layout.DuelFocus.rowEnd(focus, s, bottom, shape) else com.kaiharimoto.mastertool.core.layout.DuelFocus.rowStart(focus, s, bottom, shape))
    }

    /** The table changed: the focus goes with the card it was on, or stays where it was, made good. */
    fun refocus() {
        val f = focus ?: return
        val s = shown?.state ?: return
        val open = (f as? com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.PileCard)?.takeIf { strip != it.seat to it.kind }
            ?.let { com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Pile(it.seat, it.kind) }
        val next = if (open != null) open else com.kaiharimoto.mastertool.core.layout.DuelFocus.follow(f, focusCard, s, bottom, focusShape(), strip, eyes)
        if (next != f) focus = next
        focusCard = next?.takeIf { it !is com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Pile }?.let { com.kaiharimoto.mastertool.core.layout.DuelFocus.uidAt(s, it, eyes) }
            ?.takeIf { followable(s, it) }
        if (picked?.let { it !in s.cards } == true) picked = null
    }

    /**
     * Whether the ring may follow this card when it moves: only one the table's eyes can see (1.0.87, the red team). A
     * hidden card is followed by place, never by uid — following it would show which one a re-veiled hand lost to a Set.
     */
    private fun followable(s: com.kaiharimoto.mastertool.core.duel.DuelState, uid: Int): Boolean =
        eyes.viewers.isEmpty() || eyes.viewers.any { com.kaiharimoto.mastertool.core.duel.DuelSight.sees(s, uid, it) }

    /** Esc's last layer: the focus let go, and what was picked with it. */
    fun clearFocus() {
        focus = null
        focusCard = null
        picked = null
        lastInput = Input.POINTER
    }

    // ---- Ai in the log (1.0.80) ----------------------------------------------------------------------

    /** The duel's own conversation with Ai, so the log always talks into it. */
    var aiSession by mutableStateOf<String?>(null)
    /** How far into the log Ai has read: each cue carries what came after, then moves this on. */
    var aiRead: Int? = null
    /** The next cue starts a new conversation with Ai, not the one open (a new duel, or New topic). */
    var aiFresh = false
    /** The person said Respond: Ai waits until they say Done. */
    var aiResponding by mutableStateOf(false)
    /** Log lines picked (their entry numbers), for Insert here or Save as combo. */
    var logPick by mutableStateOf<List<Int>>(emptyList())
    /** The next move goes into the log after this entry, not at its end: acting in a phase gone by. */
    var insertAfter by mutableStateOf<Int?>(null)

    // ---- Ai's response triggers (1.0.85) --------------------------------------------------------------

    /** Ai's watches: its private plan for what it would answer. Never in the log, the record or the network. */
    var watches by mutableStateOf<List<com.kaiharimoto.mastertool.core.duel.ai.Watch>>(emptyList())
    private var nextWatch = 1
    /** Watches that fired and wait for Ai: the page cues it with them as soon as it is free. */
    var fired by mutableStateOf<List<com.kaiharimoto.mastertool.core.duel.ai.Hit>>(emptyList())
    /** A phase change held while Ai decides whether to respond before it: a phase_leave watch fired. */
    var held by mutableStateOf<Held?>(null)
    /** Ai is answering a trigger: the person's moves wait for it, unless they say Don't wait. */
    var aiAnswering by mutableStateOf(false)
    /** The seat Ai watches as, set by the page while Ai sits at the table; null otherwise. */
    var watcher: Int? = null
    /** A cue given while Ai was still answering, kept for when it is free (1.0.85; before, it was dropped). */
    var queuedCue by mutableStateOf<Pair<String, String>?>(null)
    private var releasing = false
    /** Ai is playing its moves out (duel_act, a combo it runs, a move into the past): never the person's moves (1.0.85). */
    var aiActing = false
    /** Stops Ai's answer, set by the page: Don't wait means Ai's late answer never lands (1.0.85). */
    var stopAi: (() -> Unit)? = null

    /** Ai's cues typed or spoken on the Line (1.0.87, set by the page): false when no Ai sits at the table. */
    var cueAi: ((DuelCommand.Parsed.Ui) -> Boolean)? = null

    /** The Line's last answer to a question (1.0.87): `hand`, `their field`, `?m3` — through this seat's eyes. */
    var answer by mutableStateOf<String?>(null)

    /**
     * A phase change held for Ai: the moves, who made them, and the table it was made against — released only onto
     * that same table, unchanged ([cursor], the same [game]) with no chain open; [spent] are the once-watches it used;
     * [auto]: a step of a turn's opening, which goes on once it is made (1.0.86).
     */
    data class Held(
        val actions: List<DuelAction>,
        val seat: Int?,
        val cursor: Int,
        val game: DuelGame,
        val spent: List<com.kaiharimoto.mastertool.core.duel.ai.Watch> = emptyList(),
        val auto: Boolean = false,
    )

    fun watch(w: com.kaiharimoto.mastertool.core.duel.ai.Watch): com.kaiharimoto.mastertool.core.duel.ai.Watch {
        val kept = w.copy(id = nextWatch++)
        watches = watches + kept
        return kept
    }

    fun unwatch(id: Int?): Int {
        val before = watches.size
        watches = if (id == null) emptyList() else watches.filter { it.id != id }
        return before - watches.size
    }

    fun nextWatchId(): Int = nextWatch

    /** Ai's watches as of now: a turn's watch is gone with its turn. */
    fun liveWatches(): List<com.kaiharimoto.mastertool.core.duel.ai.Watch> {
        val turn = game?.state?.turn ?: return emptyList()
        val alive = com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.alive(watches, turn)
        if (alive.size != watches.size) watches = alive
        return alive
    }

    /** The seat Ai's watches were left for: they are forgotten only when Ai changes seats, not each time the page opens. */
    var watchSeat: Int? = null
    /** Moves were taken back under Ai's read mark (an undo that kept the talk after it): its next cue says so. */
    var aiTookBack = false

    /** Everything about Ai's answers let go: a new duel, a what-if, a replay, the network, another seat for Ai. */
    fun forgetTriggers(clearWatches: Boolean = true) {
        if (clearWatches) watches = emptyList()
        fired = emptyList()
        held = null
        aiAnswering = false
    }

    /** Whether the person's table moves wait on Ai now. */
    val waitingOnAi: Boolean get() = aiAnswering || held != null || fired.isNotEmpty()

    private fun summonsThisTurn(): (Int) -> Int {
        val n by lazy { game?.let { com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.summonsThisTurn(it, catalog) } }
        return { seat -> n?.getOrNull(seat) ?: 0 }
    }

    private fun fire(hits: List<com.kaiharimoto.mastertool.core.duel.ai.Hit>): List<com.kaiharimoto.mastertool.core.duel.ai.Watch> {
        if (hits.isEmpty()) return emptyList()
        val gone = hits.filter { it.watch.once }.map { it.watch }
        if (gone.isNotEmpty()) watches = watches.filter { w -> gone.none { it.id == w.id } }
        fired = fired + hits
        return gone
    }

    /**
     * The phase change held for Ai, made now: Ai has answered, or the person will not wait. Only onto the table it was
     * held against — if Ai responded (the log moved, a chain is open), the person moves the phase on again themselves
     * once the chain is done; on a replay, the past or the network it is let go (1.0.85, the red team).
     */
    fun releaseHeld() {
        val h = held ?: return
        held = null
        val g = game ?: return
        if (replay != null || role != null) return
        // Ai's words are not a response; a table move of its is.
        val moved = g.cursor < h.cursor || g.played.drop(h.cursor).any { !it.action.social }
        if (moved || g.state.chain.isNotEmpty()) {
            problem = "Ai responded — move the phase on again once the chain is done."
            // The opening stops here too: made again at once it would wake Ai a second time for the same step.
            autoTurn = null
            return
        }
        releasing = true
        autoActing = h.auto
        val wasAi = aiActing
        aiActing = false
        try { act(h.actions, h.seat) } finally { releasing = false; autoActing = false; aiActing = wasAi }
        resumeTurn()
    }

    /** The held phase change taken back (Undo): the once-watches it spent stand again. */
    private fun dropHeld() {
        val h = held ?: return
        held = null
        if (h.spent.isNotEmpty()) watches = watches + h.spent.filter { w -> watches.none { it.id == w.id } }
    }

    /** The person goes on without Ai's answer: Ai is stopped, so a late answer never lands on a table moved on. */
    fun dontWait() {
        if (aiAnswering) stopAi?.invoke()
        aiAnswering = false
        fired = emptyList()
        releaseHeld()
        resumeTurn()
    }

    // ---- turns that start themselves (1.0.86) ----------------------------------------------------------

    /** `DuelPrefs.autoDraw`, set by the page: after End Turn the next player's draw, Standby and Main 1 are made here. */
    var autoDraw = true
    /** The turn whose opening is being made; null when none is. */
    private var autoTurn by mutableStateOf<Int?>(null)

    /** A turn's opening is still to be made (paused on Ai's answer): Ai is not asked to play until it is. */
    val opening: Boolean get() = autoTurn != null
    /** The opening's group in the log: its steps are one gesture, the incoming seat's, one step of undo. */
    private var autoGroup: Int? = null
    /** A step of the opening is being committed. */
    private var autoActing = false

    /** The turn just begun opens by itself: on the live table of this device only, never a networked one or a replay. */
    private fun beginTurn() {
        autoTurn = null
        if (!autoDraw || role != null || replay != null) return
        autoTurn = game?.state?.turn ?: return
        autoGroup = null
        if (!releasing) resumeTurn()
    }

    /**
     * Makes the opening's next steps ([com.kaiharimoto.mastertool.core.duel.TurnStart]) through [act], so Ai's watches see
     * the draw and each phase entered and left. It pauses while Ai answers a watch that fired, or holds a phase change,
     * and goes on from where it stopped once Ai has answered (or the person did not wait) and the held change is made;
     * a held change taken back (Undo) ends it, as does any table it no longer fits.
     */
    fun resumeTurn() {
        val turn = autoTurn ?: return
        var left = 4
        while (left-- > 0) {
            val g = game
            if (g == null || !autoDraw || role != null || replay != null || g.state.turn != turn) { autoTurn = null; return }
            if (held != null || aiAnswering || fired.isNotEmpty()) return
            val step = com.kaiharimoto.mastertool.core.duel.TurnStart.next(g) ?: run { autoTurn = null; return }
            // The opening is the table's, never a move put back in a phase gone by.
            val pastAt = insertAfter
            insertAfter = null
            autoActing = true
            // Never Ai's move even when Ai's End Turn began it: the person's opening is watched (1.0.86, the red team).
            val wasAi = aiActing
            aiActing = false
            val ok = try { act(listOf(step), g.state.active) } finally { autoActing = false; aiActing = wasAi; insertAfter = pastAt }
            if (!ok) { autoTurn = null; return }
        }
    }

    /** Where the person's drag acts as another seat than the card's: a guest. */
    fun dragActor(): Int? = if (role == NetRole.GUEST) mySeat else null

    /** The duel already logged to Prep as a practice game, so it is logged once. */
    var loggedDuel: String? = null

    /** A log line picked or let go; two at most, for a span. */
    fun pickLine(i: Int) {
        logPick = if (i in logPick) logPick - i else (logPick + i).takeLast(2)
        if (logPick.size != 1) insertAfter = null
    }

    /** A span of the log kept as one of a deck's combos. */
    fun saveSpan(deckId: String, name: String, needs: List<String>, steps: List<String>, done: (String) -> Unit) {
        scope.launch {
            val book = combos(deckId)
            val combo = com.kaiharimoto.mastertool.core.duel.ai.Combo("c${now()}", name, deckId, needs, steps, created = now())
            saveCombos(deckId, book.copy(combos = book.combos + combo))
            done(name)
        }
    }
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

    /** What the table shows: the replay where it stands, the guest's view of the host's duel, or the duel in play. */
    val shown: DuelGame?
        get() {
            if (role == NetRole.GUEST) return remote
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
        loadRulings()
        if (loaded) return
        loaded = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { File(dir, CURRENT).takeIf { it.exists() }?.readText() }
            val record = text?.let(DuelCodec::decode) ?: return@launch
            if (game == null) {
                game = runCatching { DuelGame.of(record) }.getOrNull()
                // A what-if keeps where it branched from across a restart (1.0.85).
                if (game != null) origin = record.parent?.let { it to (record.parentAt ?: 0) }
            }
        }
    }

    /** Reads the duel in play again: a backup restored. */
    fun reload() {
        forgetTriggers()
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
        attacking = null
        placed = null
        problem = null
        inspected = null
        newTopic()
        logPick = emptyList()
        insertAfter = null
        forgetTriggers()
        queuedCue = null
        save()
        // Turn 1 opens by itself too (1.0.86): Standby and Main 1, nothing drawn.
        beginTurn()
    }

    /**
     * Ai's side of the log starts again (1.0.84): its lines leave the log and its next cue opens a new
     * conversation, reading the whole duel from the start. The table's moves and chat stay — they are
     * the duel. The old conversation is kept in Ai's history.
     */
    fun newTopic() {
        aiSession = null
        aiRead = null
        aiResponding = false
        aiFresh = true
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
        // Insert here takes the person's next move only — never a step of Ai's or a combo's play-out (1.0.85).
        insertAfter?.let { at -> if (role == null && !playing && !releasing && actions.any { !it.social }) { insertAfter = null; return insertPast(at, actions, seat) } }
        if (role == NetRole.GUEST) return ask(actions)
        if (role == NetRole.HOST) return hostAct(actions, seat ?: bottom)
        val g = game ?: return false
        if (actions.isEmpty()) return false
        // Ai's response triggers (1.0.85): the person's moves, checked against the watches Ai left.
        // Every table move not Ai's own is the person's (1.0.85: moving Ai's cards, logged as its seat, counted too).
        val w = watcher
        // A turn's opening is the incoming seat's own: Ai's draw and phases never wake Ai (1.0.86).
        val person = w?.let { 1 - it }
        val watched = w != null && !aiActing && !(autoActing && seat == w) && actions.any { !it.social }
        if (watched && !releasing) {
            if (held != null) {
                problem = "Your phase change waits on Ai's answer."
                return false
            }
            if (aiAnswering || fired.isNotEmpty()) {
                problem = "Ai may respond — your move waits for it. Don't wait goes on without it."
                return false
            }
            if (actions.any { it is DuelAction.Phase || it is DuelAction.EndTurn }) {
                val live = liveWatches()
                if (live.any { com.kaiharimoto.mastertool.core.duel.ai.Trigger.PHASE_LEAVE.key in it.on }) {
                    val leaving = com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.happenings(
                        g.state, actions.map { com.kaiharimoto.mastertool.core.duel.DuelEntry(0, 0L, seat, 0, it) }, catalog, w!!, person,
                    ).filter { it.kind == com.kaiharimoto.mastertool.core.duel.ai.Trigger.PHASE_LEAVE }
                    val hits = com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.hits(live, leaving, w)
                    if (hits.isNotEmpty()) {
                        // Checked whole first, so a held change is one the table will take.
                        val check = g.act(actions, seat, now())
                        if (!check.ok) { problem = check.problem; return false }
                        problem = null
                        val spent = fire(hits)
                        held = Held(actions, seat, g.cursor, g, spent, autoActing)
                        return true
                    }
                }
            }
        }
        val r = g.act(actions, seat, now(), join = autoActing && autoGroup != null && g.entries.getOrNull(g.cursor - 1)?.group == autoGroup)
        if (!r.ok) {
            problem = r.problem
            return false
        }
        game = r.game
        problem = null
        if (autoActing && autoGroup == null) autoGroup = r.game.entries.getOrNull(r.game.cursor - 1)?.group
        // Something moved: a pending Attach and the verbs beside the last card are done with (1.0.85).
        // An attack armed and never made ends with its phase (1.0.86, the red team).
        if (actions.any { it is DuelAction.Phase || it is DuelAction.EndTurn }) attacking = null
        if (actions.any { !it.social && it !is DuelAction.Counter }) {
            verbStrip = false
            if (actions.none { it is DuelAction.Move && it.to is Place.Under }) attaching = null
        }
        save()
        if (watched) {
            val live = liveWatches()
            if (live.isNotEmpty()) {
                val fresh = r.game.entries.subList(g.cursor, r.game.cursor)
                val seen = com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.happenings(g.state, fresh, catalog, w!!, person)
                    .filter { !releasing || it.kind != com.kaiharimoto.mastertool.core.duel.ai.Trigger.PHASE_LEAVE }
                fire(com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.hits(live, seen, w, summonsThisTurn()))
            }
        }
        // The turn passed: the next one opens by itself (1.0.86), after what this move fired.
        if (DuelAction.EndTurn in actions) beginTurn()
        return true
    }

    fun act(action: DuelAction, seat: Int? = bottom): Boolean = act(listOf(action), seat)

    /**
     * [verb] on [uid], or on the selection when [uid] is part of it. A verb that puts a card in a zone
     * remembers it for a moment, so the number keys can move it to the zone meant.
     */
    fun verb(uid: Int, verb: DuelVerb, zone: Place.Zone? = null, host: Int? = null, seat: Int? = null, direct: Boolean = false): Boolean {
        val g = shown ?: return false
        // Any verb puts a waiting attack away (1.0.86); an attack verb arms it again below.
        attacking = null
        val actor = seat ?: seatFor(uid)
        val targets = if (uid in selection && selection.size > 1 && verb != DuelVerb.ATTACK) selection.toList() else listOf(uid)
        if (targets.size > 1) {
            // Several at once: each in turn on the table as the one before left it, one group.
            var s = g.state
            val all = mutableListOf<DuelAction>()
            var attackers = 0
            targets.forEach { u ->
                val r = DuelVerbs.actions(s, seatFor(u), u, verb, catalog)
                // An attack wants its target: declared one monster at a time (1.0.86, the red team: they were dropped silently).
                if (r.needsTarget) { attackers++; return@forEach }
                if (r.problem != null || r.needsHost) return@forEach
                val next = com.kaiharimoto.mastertool.core.duel.DuelRules.applyAll(s, r.actions).first ?: return@forEach
                all += r.actions
                s = next
            }
            if (all.isEmpty()) {
                problem = if (attackers > 0) "Attacks are declared one monster at a time: pick one attacker." else problem
                return false
            }
            selection = emptySet()
            if (attackers > 0) problem = "The other moves were made; attacks are declared one monster at a time."
            return act(all, seatFor(uid))
        }
        val r = DuelVerbs.actions(g.state, actor, uid, verb, catalog, zone, host, direct)
        if (r.needsHost) {
            attaching = uid
            problem = null
            return false
        }
        if (r.needsTarget) {
            attacking = uid
            attaching = null
            inspected = uid
            verbStrip = false
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

    /** The waiting attack declared (1.0.86): on [target], or directly when it is null. */
    fun attack(target: Int?): Boolean {
        val a = attacking ?: return false
        attacking = null
        return verb(a, DuelVerb.ATTACK, host = target, direct = target == null).also { if (it) inspected = a }
    }

    /**
     * A number key just after a card was placed: the same placement, in the zone meant instead —
     * undone and done again, so the log keeps one entry.
     */
    fun replace(kind: ZoneKind, index: Int): Boolean {
        // The live table on this device only: never a networked one or a replay, nor under Ai's answer (1.0.85).
        if (role != null || replay != null || waitingOnAi) return false
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
        return when (val p = DuelCommand.parse(text, g.state, bottom, catalog, g.header.seed)) {
            is DuelCommand.Parsed.Problem -> { problem = p.text; false }
            is DuelCommand.Parsed.Actions -> act(p.actions, bottom).also { if (it) command = "" }
            // Moves joined with ";" (1.0.87): each its own step, in order, stopping at the first the table refuses.
            is DuelCommand.Parsed.Many -> {
                var all = true
                for (part in p.parts) if (!act(part.actions, bottom)) { all = false; break }
                if (all) command = ""
                all
            }
            is DuelCommand.Parsed.Ruling -> {
                val r = keepRuling(p.code, p.card, p.text)
                act(DuelAction.Note("House ruling: ${r.card?.let { "$it — " } ?: ""}${r.text}", bottom), bottom)
                command = ""
                true
            }
            is DuelCommand.Parsed.Query -> {
                val said = com.kaiharimoto.mastertool.core.duel.text.DuelAnswer.answer(p, g.state, bottom, catalog, g.header.seed)
                answer = said
                problem = said
                if (p.uid != null) inspected = p.uid
                command = ""
                true
            }
            is DuelCommand.Parsed.Ui -> {
                when (p.kind) {
                    DuelCommand.UiKind.OPEN -> {
                        val seat = p.seat
                        val pile = p.pile
                        if (seat != null && pile != null && strip != seat to pile) openPile(seat, pile)
                    }
                    DuelCommand.UiKind.CLOSE -> closeStrip()
                    DuelCommand.UiKind.READ -> inspected = p.uid
                    DuelCommand.UiKind.CUE -> if (cueAi?.invoke(p) != true) problem = "No Ai sits at this table."
                    DuelCommand.UiKind.SWAP -> swap()
                    DuelCommand.UiKind.UNDO -> undo()
                    DuelCommand.UiKind.REDO -> redo()
                }
                command = ""
                true
            }
        }
    }

    fun say(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (act(DuelAction.Chat(bottom, t), bottom)) chat = ""
    }

    fun undo() {
        if (replay != null) { step(com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit.GROUP, -1); return }
        if (role != null) { askTakeBack(); return }
        // A phase change held for Ai is not on the table yet: undo takes it back first.
        if (held != null) { dropHeld(); autoTurn = null; return }
        // Ai is answering the move just made: taking it back under its answer would leave the answer to nothing.
        if (aiAnswering || fired.isNotEmpty()) { problem = "Ai is answering your move — Don't wait first, then undo."; return }
        val g = game ?: return
        if (!g.canUndo) return
        autoTurn = null
        // Talk stays (1.0.86): a word to Ai or a cue is never what Ctrl Z takes back; the move before it is.
        val next = g.undoMove()
        // Talk moved to before the move taken back: lines picked by number would now be other lines.
        if (next.entries !== g.entries) { logPick = emptyList(); insertAfter = null }
        // Ai's read mark goes back to where the logs part, and its next cue says moves were taken back (1.0.86, the
        // red team: with the talk kept, the cursor came back to the mark and Ai was told "nothing new").
        val was = g.played
        val now = next.played
        var k = 0
        while (k < was.size && k < now.size && was[k] == now[k]) k++
        aiRead?.let { if (k < it) { aiRead = k; aiTookBack = true } }
        game = next
        placed = null
        problem = null
        attacking = null
        save()
    }

    fun redo() {
        if (replay != null) { step(com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit.GROUP, 1); return }
        if (role != null) return
        if (waitingOnAi) { problem = "Ai is answering your move — Don't wait first."; return }
        val g = game ?: return
        if (!g.canRedo) return
        game = g.redoMove()
        placed = null
        attacking = null
        save()
    }

    /** Sit at the other seat (a hot-seat's turn of the table). */
    /** Ai is playing the other seat (set by the page): the person cannot sit there and see its hand (1.0.85). */
    var aiEngaged = false

    fun swap() {
        if (role != null) return
        val g = game ?: return
        if (g.state.solo) return
        if (aiEngaged) { problem = "Ai plays the other seat: sitting there would show you its hand."; return }
        attacking = null
        bottom = 1 - bottom
        strip = null
    }

    fun openPile(seat: Int, kind: PileKind) {
        if (strip == seat to kind) closeStrip() else {
            strip = seat to kind
            stripRow = 0
        }
    }

    /**
     * Closes the open pile (1.0.78: by its ✕, a press outside it, or a card carried out of it). A Deck
     * looked through leaves "Shuffle Deck" standing by it for a few seconds, as a player shuffles after
     * a search.
     */
    fun closeStrip() {
        val open = strip ?: return
        strip = null
        // A card focused in the pile: the focus goes back to the pile, shut (1.0.87).
        (focus as? com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.PileCard)?.let { focus = com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Pile(it.seat, it.kind); focusCard = null }
        if (open.second == PileKind.DECK) offerShuffle = open.first to System.currentTimeMillis() + SHUFFLE_OFFER_MS
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

    /** The duel written now and waited for: the app is closing. */
    fun flushNow() {
        val g = game ?: return
        if (saveJob?.isActive != true) return
        saveJob?.cancel()
        // Off the main thread and never long: the writers lock on the IO pool, so nothing waits on this thread (1.0.85).
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(2000) { write(g) } }
    }

    /** The duel written now: the window closing. */
    fun flush() {
        val g = game ?: return
        saveJob?.cancel()
        scope.launch { write(g) }
    }

    private suspend fun write(g: DuelGame) = withContext(Dispatchers.IO) {
        io.withLock {
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

    // ---- two players over the network (1.0.77) -------------------------------------------------------

    enum class NetRole { HOST, GUEST }

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
    private fun tokenFile(secret: Int) = File(dir, "net/seat-$secret.txt")
    private var sentTo = 0
    private var seq = 0
    /** This player's seat at a networked table: the host sits at 0, the guest at 1. */
    val mySeat: Int get() = if (role == NetRole.GUEST) 1 else 0

    /** Whose answer the table waits on now, if anyone's. */
    val waitingFor: Int?
        get() = when (role) {
            NetRole.GUEST -> remoteWaiting
            NetRole.HOST -> game?.state?.window?.responder
            null -> null
        }

    /** Opens a table on the local network with [mine] at the host's seat; the code to share comes back in [netCode]. */
    fun host(mine: com.kaiharimoto.mastertool.core.duel.SeatSetup) {
        leave()
        role = NetRole.HOST
        hostSeat = mine
        bottom = 0
        netStatus = "Opening the table…"
        val h = DuelHosting(onGuest = ::guestArrived, onProblem = { problem = it })
        hosting = h
        scope.launch {
            val code = runCatching { h.open() }.getOrNull()
            if (code == null) {
                problem = "No local network to open a table on. Join the same Wi-Fi as the other player."
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
                val seated = guestToken != null && game != null && game?.header?.id == guestDuel && role == NetRole.HOST
                val back = seated && w.token != null && w.token == guestToken
                if (seated && !back) {
                    l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Rejected("A duel is in play at this table.")); l.close(); link = null; return
                }
                guestWindows = w.windows
                if (!back) {
                    val mine = hostSeat ?: return
                    guestToken = java.util.UUID.randomUUID().toString()
                    start(
                        DuelHeader(
                            id = "n${now()}",
                            seed = java.security.SecureRandom().nextLong(),
                            seats = listOf(mine, com.kaiharimoto.mastertool.core.duel.SeatSetup(w.name.ifBlank { "Guest" }, w.main, w.extra, null, w.deckName)),
                            created = now(),
                        ),
                    )
                    role = NetRole.HOST
                    guestDuel = game?.header?.id
                }
                peer = w.name.ifBlank { "Guest" }
                netStatus = "Playing ${peer} over the network"
                setupOpen = false
                sentTo = 0
                l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Welcome(1, guestToken!!, hostSeat?.name.orEmpty()))
                sendUpdate()
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Intent -> {
                val g = game ?: return
                val (resolved, why) = com.kaiharimoto.mastertool.core.duel.net.DuelHost.resolve(g.state, 1, g.header.seed, w.actions)
                if (resolved == null) { l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Refused(w.seq, why ?: "No")); return }
                val r = com.kaiharimoto.mastertool.core.duel.net.DuelHost.act(g, 1, resolved, windows(), w.force, now())
                if (!r.ok) { l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Refused(w.seq, r.problem ?: "No")); return }
                game = r.game
                save()
                sendUpdate()
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.TakeBack -> if (w.ask) {
                takeBackAsked = 1
            } else if (w.yes) {
                // Honoured only as the answer to the host's own ask (1.0.85: before, any yes undid the host).
                if (hostAskedTakeBack) { hostAskedTakeBack = false; takeBack(0) }
            } else {
                hostAskedTakeBack = false
                problem = "${peer ?: "They"} would rather you did not take it back"
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.SetWindows -> guestWindows = w.windows
            com.kaiharimoto.mastertool.core.duel.net.Wire.Bye -> { netStatus = "${peer ?: "The other player"} left the table."; link?.close(); link = null }
            else -> Unit
        }
    }

    private fun windows(): Map<Int, String> = mapOf(0 to myWindows, 1 to guestWindows)

    private fun hostAct(actions: List<DuelAction>, seat: Int): Boolean {
        val g = game ?: return false
        val r = com.kaiharimoto.mastertool.core.duel.net.DuelHost.act(g, seat, actions, windows(), forceNext, now())
        forceNext = false
        // A move made after asking to take back the last one: the ask is over.
        if (actions.any { !it.social }) hostAskedTakeBack = false
        if (!r.ok) { problem = r.problem; return false }
        game = r.game
        problem = null
        save()
        sendUpdate()
        return true
    }

    private fun sendUpdate(takeBackFrom: Int? = null) {
        val g = game ?: return
        val l = link ?: return
        sentTo = minOf(sentTo, g.cursor)
        l.send(com.kaiharimoto.mastertool.core.duel.net.DuelHost.update(g, 1, sentTo, g.header.seed, catalog, takeBackFrom, folds = folds(g)))
        sentTo = g.cursor
    }

    /** Joins the table [code] names, with [mine] as this player's deck. */
    fun join(code: String, mine: com.kaiharimoto.mastertool.core.duel.SeatSetup) {
        val table = com.kaiharimoto.mastertool.core.duel.net.PairCode.decode(code) ?: run { problem = "That is not a table's code"; return }
        leave()
        role = NetRole.GUEST
        bottom = 1
        netStatus = "Joining…"
        scope.launch {
            val socket = runCatching { dial(table) }.getOrElse {
                problem = "Could not reach the table at ${table.host}. Both of you need to be on the same network."
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
                    scope.launch(Dispatchers.IO) { runCatching { tokenFile(secret).apply { parentFile?.mkdirs() }.writeText(t) } }
                }
                peer = w.hostName.ifBlank { "Host" }
                netStatus = "Playing $peer over the network"
                setupOpen = false
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Update -> {
                remote = com.kaiharimoto.mastertool.core.duel.net.DuelMirror.game(w.view, DuelHeader(id = "remote"))
                val first = w.lines.firstOrNull()?.i ?: w.cursor
                remoteLines = remoteLines.filter { it.i < first && it.i < w.cursor } + w.lines
                remoteWaiting = w.waitingFor
                takeBackAsked = w.takeBackFrom
            }
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Refused -> problem = w.reason
            is com.kaiharimoto.mastertool.core.duel.net.Wire.Rejected -> { problem = w.reason; leave() }
            else -> Unit
        }
    }

    private fun ask(actions: List<DuelAction>): Boolean {
        val l = link ?: run { problem = "Not connected to the table"; return false }
        l.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Intent(++seq, actions, forceNext))
        forceNext = false
        return true
    }

    private var hostAskedTakeBack = false

    /** Asks the other player to let this one take back its last move. */
    private fun askTakeBack() {
        when (role) {
            NetRole.GUEST -> link?.send(com.kaiharimoto.mastertool.core.duel.net.Wire.TakeBack(ask = true))
            NetRole.HOST -> { hostAskedTakeBack = true; sendUpdate(takeBackFrom = 0) }
            null -> Unit
        }
        problem = "Asked ${peer ?: "the other player"} to let you take it back"
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
        val g = game ?: return
        val last = g.entries.getOrNull(g.cursor - 1) ?: return
        if (last.seat != seat || !g.canUndo) { problem = "The last move is not theirs to take back"; return }
        game = g.undo()
        save()
        sendUpdate()
    }

    /** Leaves the networked table; the duel stays on this device. */
    fun leave() {
        link?.send(com.kaiharimoto.mastertool.core.duel.net.Wire.Bye)
        link?.close()
        link = null
        hosting?.close()
        hosting = null
        if (role == NetRole.GUEST) { remote = null; remoteLines = emptyList(); bottom = 0 }
        role = null
        netCode = null
        netStatus = null
        peer = null
        remoteWaiting = null
        takeBackAsked = null
        // The guest seat and its token belong to that table only (1.0.85: a second hosted game refused every guest).
        guestToken = null
        guestDuel = null
        attacking = null
        hostAskedTakeBack = false
        forgetTriggers(clearWatches = false)
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
        // A replay is not the table Ai's answer was for (1.0.85): what waited on it goes.
        forgetTriggers(clearWatches = false)
        attacking = null
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

    /**
     * [actions] put into the log after entry [at] of the duel in play (1.0.80): a move made in a phase
     * already gone by. Everything after folds on top of it; a later move it makes impossible is struck
     * through in the log, never refused.
     */
    fun insertPast(at: Int, actions: List<DuelAction>, seat: Int?): Boolean {
        if (!aiActing && waitingOnAi) { problem = "Ai is answering your move — Don't wait first."; return false }
        val g = game ?: return false
        val k = at.coerceIn(g.floor, g.cursor)
        val stamped = actions.mapIndexed { n, a -> com.kaiharimoto.mastertool.core.duel.DuelRandom.stamp(a, com.kaiharimoto.mastertool.core.duel.DuelRandom.forEntry(g.header.seed, g.cursor + n + 104729)) }
        val (ok, why) = com.kaiharimoto.mastertool.core.duel.DuelRules.applyAll(folds(g).sync(g.entries).stateAt(k), stamped, seat)
        if (ok == null) { problem = why; return false }
        val record = g.record().copy(entries = g.played, cursor = g.cursor)
        val inserted = com.kaiharimoto.mastertool.core.duel.replay.Replays.insert(record, k, stamped, seat, now())
        game = DuelGame.of(inserted)
        problem = null
        save()
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
        forgetTriggers()
        attacking = null
        game = com.kaiharimoto.mastertool.core.duel.replay.Replays.branch(r.record, r.at)
        origin = r.id to r.at
        closeReplay()
        selection = emptySet()
        save()
    }

    private suspend fun writeReplay(id: String, record: DuelRecord) = withContext(Dispatchers.IO) {
        io.withLock {
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

    // ---- combos and Ai (1.0.76) ----------------------------------------------------------------------

    /** Whether moves are being played out a step at a time (a combo, Ai's turn); Esc stops them. */
    var playing by mutableStateOf(false)
    var stopRequested = false
    var combosOpen by mutableStateOf(false)
    /** The last turn Ai was asked to play, so a turn is asked for once. */
    var aiAskedTurn = -1

    /** The deck a seat is playing, for its combos: the duel's own, else none. */
    fun deckOf(seat: Int): String? = shown?.header?.seats?.getOrNull(seat)?.deckId

    suspend fun combos(deckId: String): com.kaiharimoto.mastertool.core.duel.ai.ComboBook = withContext(Dispatchers.IO) {
        File(dir, com.kaiharimoto.mastertool.core.duel.ai.ComboCodec.path(deckId)).takeIf { it.exists() }
            ?.readText()?.let(com.kaiharimoto.mastertool.core.duel.ai.ComboCodec::decode)
            ?: com.kaiharimoto.mastertool.core.duel.ai.ComboBook()
    }

    suspend fun saveCombos(deckId: String, book: com.kaiharimoto.mastertool.core.duel.ai.ComboBook) = withContext(Dispatchers.IO) {
        io.withLock {
            val target = File(dir, com.kaiharimoto.mastertool.core.duel.ai.ComboCodec.path(deckId))
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(com.kaiharimoto.mastertool.core.duel.ai.ComboCodec.encode(book))
            if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
        }
    }

    /**
     * [steps] played on the table for [seat], checked whole first (nothing moves if a step cannot be
     * done), then one step at a time [paceMs] apart, each its own step of undo. Returns what happened in
     * words: the steps played, and where it stopped if the person stopped it or the table changed under it.
     */
    suspend fun playOut(steps: List<String>, seat: Int, paceMs: Long, viewer: Int? = seat): PlayReport {
        val g = game ?: return PlayReport("There is no duel on the table.", 0, false)
        // Played on the live table only: never into an open replay (1.0.85).
        if (replay != null) return PlayReport("A replay is open on the table; close it first.", 0, false)
        val plan = com.kaiharimoto.mastertool.core.duel.ai.ComboRunner.plan(g.state, seat, steps, catalog)
        if (!plan.ok) return PlayReport("Nothing was played. ${plan.problem}", 0, false)
        playing = true
        stopRequested = false
        // Ai's play-out marks its own moves only, never the person's made between its paced steps (1.0.85).
        val byAi = aiActing
        aiActing = false
        var done = 0
        // What each step really did, in the log's words as [viewer] reads them (1.0.79, Ai: "report each op's
        // real result … 'Played all N steps' showed up while the board hadn't changed").
        val said = mutableListOf<String>()
        fun report(head: String) = PlayReport((listOf(head) + said).joinToString("\n"), done, done > 0)
        try {
            for ((text, actions) in plan.steps) {
                if (stopRequested) return report("Stopped by the person after $done of ${plan.steps.size} steps:")
                val before = game?.cursor ?: 0
                aiActing = byAi
                val acted = try { act(actions, seat) } finally { aiActing = false }
                if (!acted) return report("Played $done of ${plan.steps.size}; “$text” no longer fits the table: ${problem ?: "refused"}. What was played:")
                done++
                val now = game
                val lines = if (role == NetRole.GUEST || now == null) listOf("sent to the host")
                else com.kaiharimoto.mastertool.core.duel.net.DuelHost.lines(now, before, viewer, catalog, folds(now)).map { it.text }
                said += "$done. $text → ${lines.joinToString("; ").ifBlank { "no change on the table" }}"
                if (paceMs > 0 && done < plan.steps.size) delay(paceMs)
            }
        } finally {
            playing = false
            aiActing = byAi
        }
        return report("Played all ${plan.steps.size} steps:")
    }

    /** What playing a batch out came to: each step's real effect, how many were played, whether any was. */
    data class PlayReport(val text: String, val done: Int, val played: Boolean) {
        override fun toString(): String = text
    }

    // ---- house rulings and the turn's tally (1.0.79) -------------------------------------------------

    /** The rulings agreed at this table, kept in `<data>/duel/rulings.json` (synced, backed up). */
    var rulings by mutableStateOf(com.kaiharimoto.mastertool.core.duel.HouseRulingBook())
        private set
    private var rulingsRead = false

    fun loadRulings() {
        if (rulingsRead) return
        rulingsRead = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { File(dir, com.kaiharimoto.mastertool.core.duel.HouseRulingCodec.PATH).takeIf { it.exists() }?.readText() }
            if (text != null) rulings = com.kaiharimoto.mastertool.core.duel.HouseRulingCodec.decode(text)
        }
    }

    /** Reads the rulings again: a sync or a restore brought new ones. */
    fun reloadRulings() {
        rulingsRead = false
        loadRulings()
    }

    fun keepRuling(code: Int?, card: String?, text: String): com.kaiharimoto.mastertool.core.duel.HouseRuling {
        val r = com.kaiharimoto.mastertool.core.duel.HouseRuling("r${now()}", text, code, card, now())
        rulings = rulings.add(r)
        writeRulings()
        return r
    }

    fun forgetRuling(id: String): Boolean {
        if (rulings.rulings.none { it.id == id }) return false
        rulings = rulings.remove(id)
        writeRulings()
        return true
    }

    private fun writeRulings() {
        val book = rulings
        scope.launch {
            withContext(Dispatchers.IO) {
                io.withLock {
                    dir.mkdirs()
                    val target = File(dir, com.kaiharimoto.mastertool.core.duel.HouseRulingCodec.PATH)
                    val temp = File(dir, "${target.name}.tmp")
                    temp.writeText(com.kaiharimoto.mastertool.core.duel.HouseRulingCodec.encode(book))
                    if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
                }
            }
        }
    }

    private var tallyOf: Triple<DuelGame, Int?, com.kaiharimoto.mastertool.core.duel.Tally>? = null

    /**
     * This turn's counts and locks, for the table shown, as [viewer] may read them (an activation of a card
     * they could not see is "a card"); read off the log, kept until it changes.
     */
    fun tally(viewer: Int? = null): com.kaiharimoto.mastertool.core.duel.Tally? {
        val g = shown ?: return null
        tallyOf?.let { (of, v, t) -> if (of === g && v == viewer) return t }
        // A guest has no log of its own: the locks it was sent, nothing counted.
        val t = if (role == NetRole.GUEST) com.kaiharimoto.mastertool.core.duel.Tally(g.state.turn, listOf(0, 0), listOf(0, 0), listOf(emptyMap(), emptyMap()), g.state.locks)
        else com.kaiharimoto.mastertool.core.duel.DuelTally.of(g, catalog, viewer, folds(g))
        tallyOf = Triple(g, viewer, t)
        return t
    }

    private var folds: com.kaiharimoto.mastertool.core.duel.DuelFolds<Unit>? = null

    /**
     * The tables of [g]'s log, folded once and kept (1.0.86): the tally, Insert here and the lines sent to
     * a guest or read to Ai start from it, never from the deal again. One per duel; a new one for a new header.
     */
    internal fun folds(g: DuelGame): com.kaiharimoto.mastertool.core.duel.DuelFolds<Unit> =
        folds?.takeIf { it.header == g.header } ?: com.kaiharimoto.mastertool.core.duel.DuelFolds.states(g.header).also { folds = it }

    /**
     * The phase moved on, or the turn ended. At a hot-seat the turn player does it; at a networked table
     * the player whose turn it is not asks instead, and the turn player answers (1.0.79).
     */
    fun goPhase(phase: com.kaiharimoto.mastertool.core.board.DuelPhase?, end: Boolean = false): Boolean {
        val s = shown?.state ?: return false
        val seat = if (role != null) mySeat else s.active
        return when {
            !s.solo && seat != s.active -> act(DuelAction.Propose(seat, phase, end), seat)
            end -> act(DuelAction.EndTurn, seat)
            phase != null -> act(DuelAction.Phase(phase), seat)
            else -> false
        }
    }

    /** The turn player's answer to an ask: yes moves the phase on, no says not yet. */
    fun answerProposal(yes: Boolean): Boolean {
        val s = shown?.state ?: return false
        val p = s.proposal ?: return false
        val seat = if (role != null) mySeat else s.active
        return if (!yes) act(DuelAction.Decline(seat), seat)
        else if (p.end) act(DuelAction.EndTurn, seat) else act(DuelAction.Phase(p.phase ?: s.phase), seat)
    }

    /** Resolves the newest chain link: a Normal Spell or Trap goes to the GY with it, unless [keep] (1.0.79). */
    fun resolveChain(keep: Boolean = false): Boolean {
        val s = shown?.state ?: return false
        if (s.chain.isEmpty()) return false
        return act(com.kaiharimoto.mastertool.core.duel.DuelVerbs.resolve(s, catalog, keep), bottom)
    }

    fun useIndex(index: com.kaiharimoto.mastertool.core.search.CardIndex) {
        catalog = DuelCatalog { code -> index.byId(com.kaiharimoto.mastertool.core.model.CardId(code))?.let(com.kaiharimoto.mastertool.core.duel.DuelCardInfo::of) }
    }

    /** What the knowledge setting lets the table show: both seats' eyes, or the bottom seat's alone. */
    fun viewers(prefs: DuelPrefs): Set<Int> = when {
        // At a networked table each player sees through their own seat's eyes, whatever the hot-seat setting.
        role != null -> setOf(mySeat)
        shown?.state?.solo == true -> setOf(0)
        prefs.knowledge == DuelPrefs.KNOW_SEAT -> setOf(bottom)
        else -> setOf(0, 1)
    }

    companion object {
        /** How long "Shuffle Deck" stands by a deck after it was looked through. */
        const val SHUFFLE_OFFER_MS = 6000L
        const val CURRENT = "current.json"
        const val PLACED_MS = 2500L
        fun now(): Long = System.currentTimeMillis()
    }
}
