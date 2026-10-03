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
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val io = Mutex()

    // The parts (each its own file beside this one). Every member they hold stays reachable here under its own name.
    internal val network = DuelNet(this)
    internal val replayer = DuelReplays(this)
    internal val houseRulings = DuelRulings(this)
    internal val spot = DuelSpotlightState(this)
    internal val aiWatch = DuelAiWatch(this)
    internal val opener = DuelOpening(this)
    internal val picking = DuelPicking(this)

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
    var chat by mutableStateOf("")
    /** Bumped to take the keyboard to the chat (the Spotlight has its own, [spotlightFocus]). */
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
    var tableLayout by mutableStateOf<com.kaiharimoto.mastertool.core.layout.DuelLayout?>(null)
    /** Whose eyes the table is drawn through, and the veils' secret (set by `DuelTable`): a hidden hand's order. */
    var eyes by mutableStateOf(com.kaiharimoto.mastertool.core.layout.DuelFocus.Eyes.ALL)

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
        focusCard = if (slot == null || s == null || slot is com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Pile || slot is com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Link) null
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
        chainMenu = null
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
        focusCard = next?.takeIf { it !is com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Pile && it !is com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Link }?.let { com.kaiharimoto.mastertool.core.layout.DuelFocus.uidAt(s, it, eyes) }
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

    // ---- Ai's response triggers (1.0.85): DuelAiWatch -------------------------------------------------

    var watches by aiWatch::watches
    var fired by aiWatch::fired
    var held by aiWatch::held
    var aiAnswering by aiWatch::aiAnswering
    var watcher by aiWatch::watcher
    var queuedCue by aiWatch::queuedCue
    var aiActing by aiWatch::aiActing
    var stopAi by aiWatch::stopAi
    var watchSeat by aiWatch::watchSeat
    var aiTookBack by aiWatch::aiTookBack
    val waitingOnAi: Boolean get() = aiWatch.waitingOnAi
    fun watch(w: com.kaiharimoto.mastertool.core.duel.ai.Watch): com.kaiharimoto.mastertool.core.duel.ai.Watch = aiWatch.watch(w)
    fun unwatch(id: Int?): Int = aiWatch.unwatch(id)
    fun nextWatchId(): Int = aiWatch.nextWatchId()
    fun liveWatches(): List<com.kaiharimoto.mastertool.core.duel.ai.Watch> = aiWatch.liveWatches()
    fun forgetTriggers(clearWatches: Boolean = true) = aiWatch.forgetTriggers(clearWatches)
    fun releaseHeld() = aiWatch.releaseHeld()
    fun dontWait() = aiWatch.dontWait()

    // ---- Command mode (1.0.87): the Spotlight, DuelSpotlightState --------------------------------------

    var spotlight by spot::spotlight
    val lineHistory: List<String> get() = spot.lineHistory
    var spotlightTyping by spot::spotlightTyping
    var spotlightFocus by spot::spotlightFocus
    internal var spotlightMarks by spot::spotlightMarks
    var spotlightLevels by spot::spotlightLevels
    var spotlightSeed by spot::spotlightSeed
    fun openSpotlight(
        text: String = "",
        mode: com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode = com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode.TYPING,
        swallow: Char? = text.lastOrNull(),
    ) = spot.openSpotlight(text, mode, swallow)
    fun typeIntoSpotlight(text: String) = spot.typeIntoSpotlight(text)
    fun closeSpotlight() = spot.closeSpotlight()
    fun rememberLine(line: String) = spot.rememberLine(line)

    /** Ai's cues typed or spoken on the Line (1.0.87, set by the page): false when no Ai sits at the table. */
    var cueAi: ((DuelCommand.Parsed.Ui) -> Boolean)? = null

    /** The Line's last answer to a question (1.0.87): `hand`, `their field`, `?m3` — through this seat's eyes. */
    var answer by mutableStateOf<String?>(null)

    /** What a line run from the Spotlight came to: a move made, a question answered, the chrome's words done, or refused. */
    sealed interface Ran {
        /** Everything the line asked was done. */
        val ok: Boolean get() = this !is Refused && this !is Partial

        data object Moved : Ran
        data class Answered(val text: String) : Ran
        data object Chrome : Ran
        data class Refused(val why: String) : Ran

        /**
         * A `;` line stopped after [made] of [total] steps: [rest] is the line still to make — waiting on Ai when
         * [waiting], else refused for [why].
         */
        data class Partial(val made: Int, val total: Int, val rest: String, val waiting: Boolean, val why: String?) : Ran {
            val words: String get() = "$made of $total made — " + if (waiting) "the rest waits on Ai" else "move ${made + 1}: ${why ?: "refused"}"
        }
    }

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

    // ---- how a turn opens (1.0.86) and the opening roll (1.0.87): DuelOpening ---------------------------

    var autoDraw by opener::autoDraw
    val opening: Boolean get() = opener.opening
    fun resumeTurn() = opener.resumeTurn()
    var openingRoll by opener::openingRoll
    var diceCarry by opener::diceCarry
    var diceRolling by opener::diceRolling
    var aiOpeningSeat by opener::aiOpeningSeat
    fun mayRoll(seat: Int, playsBoth: Boolean): Boolean = opener.mayRoll(seat, playsBoth)
    fun throwDice(seat: Int, toss: com.kaiharimoto.mastertool.core.duel.dice.DiceThrow? = null): Boolean = opener.throwDice(seat, toss)
    fun goFirst(seat: Int, first: Boolean): Boolean = opener.goFirst(seat, first)
    fun aiOpening(): Boolean = opener.aiOpening()

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

    /** What the table shows: the replay where it stands, the guest's view of the host's duel, or the duel in play. */
    val shown: DuelGame?
        get() {
            if (network.role == NetRole.GUEST) return network.remote
            val r = replayer.replay ?: return game
            val t = replayer.timelineFor(r.record)
            val floor = r.record.entries.indexOfFirst { it.seat != null }.let { if (it < 0) r.record.entries.size else it }
            return DuelGame(r.record.header, r.record.entries, r.at, t.at(r.at).first, minOf(floor, r.at))
        }

    // ---- replays (1.0.75): DuelReplays -------------------------------------------------------------------

    var replay by replayer::replay
    var replays by replayer::replays
    var libraryOpen by replayer::libraryOpen
    fun refused(): Set<Int> = replayer.refused()
    fun loadReplays() = replayer.loadReplays()
    fun saveReplay(name: String) = replayer.saveReplay(name)
    fun openReplay(id: String) = replayer.openReplay(id)
    fun deleteReplay(id: String) = replayer.deleteReplay(id)
    fun closeReplay() = replayer.closeReplay()
    fun seek(at: Int) = replayer.seek(at)
    fun step(unit: com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit, dir: Int) = replayer.step(unit, dir)
    fun play(direction: Int) = replayer.play(direction)
    fun speed(s: Float) = replayer.speed(s)
    fun tick(): Boolean = replayer.tick()
    fun insertPast(at: Int, actions: List<DuelAction>, seat: Int?): Boolean = replayer.insertPast(at, actions, seat)
    fun deleteStep() = replayer.deleteStep()
    fun note(text: String) = replayer.note(text)
    fun branch() = replayer.branch()

    private var loaded = false

    /** Reads the duel left in play, once. */
    fun load() {
        houseRulings.loadRulings()
        if (loaded) return
        loaded = true
        scope.launch {
            val lines = withContext(Dispatchers.IO) { runCatching { File(dir, LINES).takeIf { it.exists() }?.readLines() }.getOrNull() }
            if (lines != null) {
                // The lines read go before any made while they were read (the red team: those were dropped).
                val read = lines.mapNotNull { l ->
                    val tab = l.indexOf('\t')
                    val seat = if (tab > 0) l.substring(0, tab).toIntOrNull() else 0
                    val line = (if (tab > 0) l.substring(tab + 1) else l).trim()
                    if (seat == null || line.isEmpty()) null else seat to line
                }.groupBy({ it.first }, { it.second })
                val made = spot.lineHistories
                spot.lineHistories = (read.keys + made.keys).associateWith { seat ->
                    made[seat].orEmpty().fold(read[seat].orEmpty().takeLast(com.kaiharimoto.mastertool.core.duel.text.Spotlight.HISTORY)) { acc, l -> com.kaiharimoto.mastertool.core.duel.text.Spotlight.remember(acc, l) }
                }
                if (made.isNotEmpty()) spot.writeLines()
            }
        }
        scope.launch {
            val text = withContext(Dispatchers.IO) { File(dir, CURRENT).takeIf { it.exists() }?.readText() }
            val record = text?.let(DuelCodec::decode) ?: return@launch
            if (game == null) {
                game = runCatching { DuelGame.of(record) }.getOrNull()
                // A what-if keeps where it branched from across a restart (1.0.85).
                if (game != null) replayer.origin = record.parent?.let { it to (record.parentAt ?: 0) }
            }
        }
    }

    /** Reads the duel in play again: a backup restored. */
    fun reload() {
        aiWatch.forgetTriggers(clearWatches = true)
        loaded = false
        game = null
        load()
    }

    fun start(header: DuelHeader) {
        game = DuelGame.start(header, now())
        replayer.origin = null
        spot.closeSpotlight()
        replayer.closeReplay()
        bottom = 0
        picking.clearSelection()
        chainMenu = null
        linkTarget = null
        strip = null
        attaching = null
        attacking = null
        placed = null
        problem = null
        inspected = null
        newTopic()
        logPick = emptyList()
        insertAfter = null
        aiWatch.forgetTriggers(clearWatches = true)
        aiWatch.queuedCue = null
        save()
        // Turn 1 opens by itself too (1.0.86): Standby and Main 1, nothing drawn.
        opener.beginTurn()
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
        if (replayer.replay != null) return replayer.insert(actions, seat)
        // Insert here takes the person's next move only — never a step of Ai's or a combo's play-out (1.0.85).
        insertAfter?.let { at -> if (network.role == null && !playing && !aiWatch.releasing && actions.any { !it.social }) { insertAfter = null; return replayer.insertPast(at, actions, seat) } }
        if (network.role == NetRole.GUEST) return network.ask(actions)
        if (network.role == NetRole.HOST) return network.hostAct(actions, seat ?: bottom)
        val g = game ?: return false
        if (actions.isEmpty()) return false
        // Ai's response triggers (1.0.85): the person's moves, checked against the watches Ai left.
        // Every table move not Ai's own is the person's (1.0.85: moving Ai's cards, logged as its seat, counted too).
        val w = aiWatch.watcher
        // A turn's opening is the incoming seat's own: Ai's draw and phases never wake Ai (1.0.86).
        val person = w?.let { 1 - it }
        val watched = w != null && !aiWatch.aiActing && !(opener.autoActing && seat == w) && actions.any { !it.social }
        if (watched && !aiWatch.releasing) {
            if (aiWatch.held != null) {
                problem = "Your phase change waits on Ai's answer."
                return false
            }
            if (aiWatch.aiAnswering || aiWatch.fired.isNotEmpty()) {
                problem = "Ai may respond — your move waits for it. Don't wait goes on without it."
                return false
            }
            if (actions.any { it is DuelAction.Phase || it is DuelAction.EndTurn }) {
                val live = aiWatch.liveWatches()
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
                        val spent = aiWatch.fire(hits)
                        aiWatch.held = Held(actions, seat, g.cursor, g, spent, opener.autoActing)
                        return true
                    }
                }
            }
        }
        val r = g.act(actions, seat, now(), join = opener.autoActing && opener.autoGroup != null && g.entries.getOrNull(g.cursor - 1)?.group == opener.autoGroup)
        if (!r.ok) {
            problem = r.problem
            return false
        }
        game = r.game
        problem = null
        if (opener.autoActing && opener.autoGroup == null) opener.autoGroup = r.game.entries.getOrNull(r.game.cursor - 1)?.group
        // Something moved: a pending Attach and the verbs beside the last card are done with (1.0.85).
        // An attack armed and never made ends with its phase (1.0.86, the red team).
        if (actions.any { it is DuelAction.Phase || it is DuelAction.EndTurn }) attacking = null
        if (actions.any { !it.social && it !is DuelAction.Counter }) {
            verbStrip = false
            if (actions.none { it is DuelAction.Move && it.to is Place.Under }) attaching = null
        }
        save()
        if (watched) {
            val live = aiWatch.liveWatches()
            if (live.isNotEmpty()) {
                val fresh = r.game.entries.subList(g.cursor, r.game.cursor)
                val seen = com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.happenings(g.state, fresh, catalog, w!!, person)
                    .filter { !aiWatch.releasing || it.kind != com.kaiharimoto.mastertool.core.duel.ai.Trigger.PHASE_LEAVE }
                aiWatch.fire(com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.hits(live, seen, w, aiWatch.summonsThisTurn()))
            }
        }
        // The turn passed: the next one opens by itself (1.0.86), after what this move fired. So does turn 1, once the
        // opening roll's winner has chosen (1.0.87).
        if (DuelAction.EndTurn in actions || actions.any { it is DuelAction.GoFirst }) opener.beginTurn()
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
        // Several at once (1.0.90, DuelSelection): one group, one undo; onto a Deck, in an order the person chooses first.
        if (uid in selection && selection.size > 1 && verb != DuelVerb.ATTACK) return picking.verbAll(verb, host)
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

    data class Ordering(val order: List<Int>, val bottom: Boolean, val cursor: Int = 0)

    var selecting by picking::selecting
    var ordering by picking::ordering
    var selCursor by picking::selCursor
    fun clearSelection() = picking.clearSelection()
    fun toggleSelect(uid: Int) = picking.toggleSelect(uid)
    fun rangeSelect(uid: Int) = picking.rangeSelect(uid)
    fun selectFocused(): Boolean = picking.selectFocused()
    fun verbAll(verb: DuelVerb, host: Int? = null): Boolean = picking.verbAll(verb, host)
    fun orderCursor(delta: Int) = picking.orderCursor(delta)
    fun orderMove(delta: Int, from: Int? = null) = picking.orderMove(delta, from)
    fun orderTo(bottom: Boolean) = picking.orderTo(bottom)
    fun commitOrdering(): Boolean = picking.commitOrdering()
    fun orderRandom(): Boolean = picking.orderRandom()
    fun orderShuffle(): Boolean = picking.orderShuffle()

    // ---- The chain by keys (1.0.90, kai: "consider the chain system and how we can use it better with a keyboard") ------

    /** The link (0-based) whose menu Enter opened in the chain well, and the item ↑/↓ have chosen in it. */
    var chainMenu by mutableStateOf<Int?>(null)
    var chainCursor by mutableStateOf(0)
    /** A link's card waiting for what it targets: the next card clicked, or Enter on the focus, gets an arrow from it. */
    var linkTarget by mutableStateOf<Int?>(null)

    /** Shift Q: the whole chain, newest link first, as one group. */
    fun resolveAll(): Boolean {
        val s = shown?.state ?: return false
        if (s.chain.isEmpty()) { problem = "There is no chain to resolve"; return false }
        chainMenu = null
        return act(DuelVerbs.resolveAll(s, catalog), bottom)
    }

    /** Chain Link [link] (1-based) negated: it stays and resolves doing nothing; an activated Spell or Trap goes to the GY. */
    fun negate(link: Int): Boolean {
        val s = shown?.state ?: return false
        val r = DuelVerbs.negate(s, bottom, link, catalog)
        r.problem?.let { problem = it; return false }
        chainMenu = null
        return act(r.actions, bottom)
    }

    /** The link's card's arrow to [uid] (Target with it, from the chain well's menu). */
    fun targetFromLink(uid: Int): Boolean {
        val from = linkTarget ?: return false
        linkTarget = null
        if (from == uid) return false
        return act(DuelAction.Target(bottom, from, listOf(uid)), bottom)
    }

    /** Y with no Ai at the table (1.0.90): No response — priority passed while a chain stands or a window waits. */
    fun pass(): Boolean {
        val s = shown?.state ?: return false
        if (s.chain.isEmpty() && s.window == null) { problem = "Nothing to pass on: no chain stands"; return false }
        return act(DuelAction.Answer(bottom, respond = false), bottom)
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
        if (network.role != null || replayer.replay != null || aiWatch.waitingOnAi) return false
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
    fun run(text: String): Boolean = runLine(text).ok

    /**
     * A line run for the seat at the bottom (1.0.87: the Spotlight's, the log's `/` lines, the LP pad's), saying what it
     * came to. [quiet]: the Spotlight shows the refusal or the answer itself, so nothing is said at the window's foot,
     * and the line goes into [lineHistory] when it does something.
     */
    fun runLine(text: String, quiet: Boolean = false): Ran {
        val g = shown ?: return Ran.Refused("No duel")
        /** The table's own refusal ([act] says it once), taken back into the box when it is the box's. */
        fun refused(): Ran {
            val why = problem ?: "The table refused that"
            if (quiet) problem = null
            return Ran.Refused(why)
        }
        val ran: Ran = when (val p = DuelCommand.parse(text, g.state, bottom, catalog, g.header.seed)) {
            is DuelCommand.Parsed.Problem -> { if (!quiet) problem = p.text; Ran.Refused(p.text) }
            is DuelCommand.Parsed.Actions -> if (act(p.actions, bottom)) Ran.Moved else refused()
            // Moves joined with ";" (1.0.87): each its own step, in order, stopping at the first the table refuses.
            is DuelCommand.Parsed.Many -> many(p, quiet, ::refused)
            is DuelCommand.Parsed.Ruling -> {
                val r = houseRulings.keepRuling(p.code, p.card, p.text)
                act(DuelAction.Note("House ruling: ${r.card?.let { "$it — " } ?: ""}${r.text}", bottom), bottom)
                Ran.Chrome
            }
            // A question is answered to this seat alone: never a Chat or a Note, which the log keeps for both seats.
            is DuelCommand.Parsed.Query -> {
                val said = com.kaiharimoto.mastertool.core.duel.text.DuelAnswer.answer(p, g.state, bottom, catalog, g.header.seed)
                answer = said
                if (!quiet) problem = said
                if (p.uid != null) inspected = p.uid
                Ran.Answered(said)
            }
            is DuelCommand.Parsed.Ui -> {
                var why: String? = null
                when (p.kind) {
                    DuelCommand.UiKind.OPEN -> {
                        val seat = p.seat
                        val pile = p.pile
                        if (seat != null && pile != null && strip != seat to pile) openPile(seat, pile)
                    }
                    DuelCommand.UiKind.CLOSE -> closeStrip()
                    DuelCommand.UiKind.READ -> inspected = p.uid
                    DuelCommand.UiKind.CUE -> if (cueAi?.invoke(p) != true) {
                        // No Ai at the table (1.0.87, the red team): "pass" and "no response" with a chain open pass
                        // priority across a hot-seat, as the response window's own No response does.
                        val passes = p.cue == com.kaiharimoto.mastertool.core.duel.ai.AiCue.PASS || p.cue == com.kaiharimoto.mastertool.core.duel.ai.AiCue.NO_RESPONSE
                        if (passes && !g.state.solo && g.state.chain.isNotEmpty()) {
                            if (!act(DuelAction.Answer(bottom, respond = false), bottom)) why = problem ?: "The table refused that"
                        } else why = "No Ai sits at this table."
                    }
                    DuelCommand.UiKind.SWAP -> swap()
                    DuelCommand.UiKind.UNDO -> undo()
                    DuelCommand.UiKind.REDO -> redo()
                }
                if (why != null) {
                    if (quiet) problem = null else problem = why
                    Ran.Refused(why)
                } else Ran.Chrome
            }
        }
        if (quiet && ran.ok) spot.rememberLine(text)
        return ran
    }

    /**
     * A `;` line, made a step at a time (1.0.87). Into the past with Insert here, the steps go in together, once (the red
     * team: only the first went back). Stopped partway — Ai's watch fired on a step, a phase change held for it, or the
     * table refused one — the steps made stay made and [Ran.Partial] carries the rest, never the whole line, so Enter
     * again does not make the first step twice.
     */
    private fun many(p: DuelCommand.Parsed.Many, quiet: Boolean, refused: () -> Ran): Ran {
        val flat = p.parts.flatMap { it.actions }
        insertAfter?.let { at ->
            if (network.role == null && !playing && flat.any { !it.social }) {
                insertAfter = null
                return if (replayer.insertPast(at, flat, bottom)) Ran.Moved else refused()
            }
        }
        p.parts.forEachIndexed { k, part ->
            val rest = p.lines.drop(k).joinToString("; ")
            if (k > 0 && aiWatch.waitingOnAi) return Ran.Partial(k, p.parts.size, rest, waiting = true, why = null)
            if (!act(part.actions, bottom)) {
                if (k == 0) return refused()
                val why = problem
                if (quiet) problem = null
                return Ran.Partial(k, p.parts.size, rest, waiting = aiWatch.waitingOnAi, why = why)
            }
            // A phase change held for Ai holds the steps after it too.
            if (aiWatch.held != null && k < p.parts.size - 1) return Ran.Partial(k + 1, p.parts.size, p.lines.drop(k + 1).joinToString("; "), waiting = true, why = null)
        }
        return Ran.Moved
    }

    fun say(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (act(DuelAction.Chat(bottom, t), bottom)) chat = ""
    }

    fun undo() {
        if (replayer.replay != null) { replayer.step(com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit.GROUP, -1); return }
        if (network.role != null) { network.askTakeBack(); return }
        // A phase change held for Ai is not on the table yet: undo takes it back first.
        if (aiWatch.held != null) { aiWatch.dropHeld(); opener.autoTurn = null; return }
        // Ai is answering the move just made: taking it back under its answer would leave the answer to nothing.
        if (aiWatch.aiAnswering || aiWatch.fired.isNotEmpty()) { problem = "Ai is answering your move — Don't wait first, then undo."; return }
        val g = game ?: return
        if (!g.canUndo) return
        opener.autoTurn = null
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
        aiRead?.let { if (k < it) { aiRead = k; aiWatch.aiTookBack = true } }
        game = next
        placed = null
        problem = null
        attacking = null
        save()
    }

    fun redo() {
        if (replayer.replay != null) { replayer.step(com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit.GROUP, 1); return }
        if (network.role != null) return
        if (aiWatch.waitingOnAi) { problem = "Ai is answering your move — Don't wait first."; return }
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
        if (network.role != null) return
        val g = game ?: return
        if (g.state.solo) return
        if (aiEngaged) { problem = "Ai plays the other seat: sitting there would show you its hand."; return }
        attacking = null
        // What the box shows — an answer about a hand, a line typed — was the other seat's (the red team).
        spot.closeSpotlight()
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

    internal fun save() {
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
            temp.writeText(DuelCodec.encode(g.record(parent = replayer.origin?.first, parentAt = replayer.origin?.second)))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    // ---- two players over the network (1.0.77): DuelNet ------------------------------------------------

    enum class NetRole { HOST, GUEST }

    var role by network::role
    var netStatus by network::netStatus
    var netCode by network::netCode
    var peer by network::peer
    var remote by network::remote
    var remoteLines by network::remoteLines
    var remoteWaiting by network::remoteWaiting
    var takeBackAsked by network::takeBackAsked
    var forceNext by network::forceNext
    var myWindows by network::myWindows
    val mySeat: Int get() = network.mySeat
    val waitingFor: Int? get() = network.waitingFor
    fun dragActor(): Int? = network.dragActor()
    fun host(mine: com.kaiharimoto.mastertool.core.duel.SeatSetup) = network.host(mine)
    fun join(code: String, mine: com.kaiharimoto.mastertool.core.duel.SeatSetup) = network.join(code, mine)
    fun answerTakeBack(yes: Boolean) = network.answerTakeBack(yes)
    fun leave() = network.leave()

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
        if (replayer.replay != null) return PlayReport("A replay is open on the table; close it first.", 0, false)
        val plan = com.kaiharimoto.mastertool.core.duel.ai.ComboRunner.plan(g.state, seat, steps, catalog)
        if (!plan.ok) return PlayReport("Nothing was played. ${plan.problem}", 0, false)
        playing = true
        stopRequested = false
        // Ai's play-out marks its own moves only, never the person's made between its paced steps (1.0.85).
        val byAi = aiWatch.aiActing
        aiWatch.aiActing = false
        var done = 0
        // What each step really did, in the log's words as [viewer] reads them (1.0.79, Ai: "report each op's
        // real result … 'Played all N steps' showed up while the board hadn't changed").
        val said = mutableListOf<String>()
        fun report(head: String) = PlayReport((listOf(head) + said).joinToString("\n"), done, done > 0)
        try {
            for ((text, actions) in plan.steps) {
                if (stopRequested) return report("Stopped by the person after $done of ${plan.steps.size} steps:")
                val before = game?.cursor ?: 0
                aiWatch.aiActing = byAi
                val acted = try { act(actions, seat) } finally { aiWatch.aiActing = false }
                if (!acted) return report("Played $done of ${plan.steps.size}; “$text” no longer fits the table: ${problem ?: "refused"}. What was played:")
                done++
                val now = game
                val lines = if (network.role == NetRole.GUEST || now == null) listOf("sent to the host")
                else com.kaiharimoto.mastertool.core.duel.net.DuelHost.lines(now, before, viewer, catalog, folds(now)).map { it.text }
                said += "$done. $text → ${lines.joinToString("; ").ifBlank { "no change on the table" }}"
                if (paceMs > 0 && done < plan.steps.size) delay(paceMs)
            }
        } finally {
            playing = false
            aiWatch.aiActing = byAi
        }
        return report("Played all ${plan.steps.size} steps:")
    }

    /** What playing a batch out came to: each step's real effect, how many were played, whether any was. */
    data class PlayReport(val text: String, val done: Int, val played: Boolean) {
        override fun toString(): String = text
    }

    // ---- house rulings (1.0.79): DuelRulings, and the turn's tally ---------------------------------------

    val rulings: com.kaiharimoto.mastertool.core.duel.HouseRulingBook get() = houseRulings.rulings
    fun loadRulings() = houseRulings.loadRulings()
    fun reloadRulings() = houseRulings.reloadRulings()
    fun keepRuling(code: Int?, card: String?, text: String): com.kaiharimoto.mastertool.core.duel.HouseRuling = houseRulings.keepRuling(code, card, text)
    fun forgetRuling(id: String): Boolean = houseRulings.forgetRuling(id)

    private var tallyOf: Triple<DuelGame, Int?, com.kaiharimoto.mastertool.core.duel.Tally>? = null

    /**
     * This turn's counts and locks, for the table shown, as [viewer] may read them (an activation of a card
     * they could not see is "a card"); read off the log, kept until it changes.
     */
    fun tally(viewer: Int? = null): com.kaiharimoto.mastertool.core.duel.Tally? {
        val g = shown ?: return null
        tallyOf?.let { (of, v, t) -> if (of === g && v == viewer) return t }
        // A guest has no log of its own: the locks it was sent, nothing counted.
        val t = if (network.role == NetRole.GUEST) com.kaiharimoto.mastertool.core.duel.Tally(g.state.turn, listOf(0, 0), listOf(0, 0), listOf(emptyMap(), emptyMap()), g.state.locks)
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
        val seat = if (network.role != null) network.mySeat else s.active
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
        val seat = if (network.role != null) network.mySeat else s.active
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
        network.role != null -> setOf(network.mySeat)
        shown?.state?.solo == true -> setOf(0)
        prefs.knowledge == DuelPrefs.KNOW_SEAT -> setOf(bottom)
        else -> setOf(0, 1)
    }

    companion object {
        /** How long "Shuffle Deck" stands by a deck after it was looked through. */
        const val SHUFFLE_OFFER_MS = 6000L
        const val CURRENT = "current.json"
        /** The Spotlight's history (1.0.87): one line made per line of text. */
        const val LINES = "lines.txt"
        const val PLACED_MS = 2500L
        fun now(): Long = System.currentTimeMillis()
    }
}
