package com.kaiharimoto.neue.shootout

import kotlin.random.Random
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.kaiharimoto.neue.builder.showInDeck
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResultsWords
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.CardIdentity
import androidx.compose.runtime.snapshotFlow
import com.kaiharimoto.mastertool.core.shootout.bench.SeenDraws
import com.kaiharimoto.mastertool.core.shootout.bench.TrialDraws
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.memory.MemoryWrite
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.shootout.bench.Behind
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.Opponent
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutPin
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrust
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrustWords
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.Erasure
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrialNote
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import com.kaiharimoto.mastertool.core.shootout.teach.Rubric
import com.kaiharimoto.mastertool.core.shootout.teach.TeachGate
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shootout's holder (1.1.2, Phase S stage 2): which deck and which target — the deck alone, or an opponent of its web —
 * the trials kept for them in `<data>/shootout/<deck>/`, the session under way, its next trial and its progress, and
 * the results.
 *
 * Every fit and every choice of trial runs off the frame thread (milliseconds, but a frame never waits for one); every
 * answer is written to disk as it is given, so stopping at any moment, or the app closing, loses nothing.
 */
class Shootouts(internal val dataDir: File, private val h: NeueHolders) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writing = Mutex()

    /** One answer fitted at a time: the person's and Ai's land from different coroutines (stage 3). */
    internal val fitting = Mutex()

    /** Teaching Ai, and the gate that lets it judge alone (Phase S stage 3): an owned part. */
    val teach = ShootoutTeach(this, h, scope)

    /** Card against card (2026-10): two cards compared in the deck, its own sessions and results. */
    val versus = ShootoutVersus(this, h)

    enum class View { SETUP, TRIAL, RESULTS, EXAM, VERSUS }

    /** The deck and target a rubric belongs to (stage 3). */
    data class RubricTarget(val deck: String, val deckName: String, val opponent: String?, val opponentName: String?)

    /** The matchup an interview in the Ai panel is writing the rubric of; null when none has begun this run. */
    var interviewing: RubricTarget? = null

    /** The rubric of the deck and target chosen, as the file holds it (stage 3). */
    var rubricText by mutableStateOf<String?>(null)
        private set

    /** A deck that can be run. */
    data class DeckChoice(val id: String, val name: String)

    /** An opponent: a deck of the web the deck is in, or one its siding links to. */
    data class OpponentChoice(val id: String, val name: String)

    var view by mutableStateOf(View.SETUP)

    /** The deck chosen; null follows the builder's. */
    var deckId by mutableStateOf<String?>(null)
        private set

    /** The opponent chosen; null is the deck alone. */
    var opponentId by mutableStateOf<String?>(null)
        private set

    /** The session pinned to one stratum; null lets the picker choose. */
    var pinned by mutableStateOf<Stratum?>(null)

    var decks by mutableStateOf<List<DeckChoice>>(emptyList())
        private set
    var opponents by mutableStateOf<List<OpponentChoice>>(emptyList())
        private set
    var deckName by mutableStateOf("")
        private set

    /** The model of the deck and target chosen, and their kept trials. */
    var bench by mutableStateOf<Bench?>(null)
        private set

    /** What [bench] was built from (the deck, its groups, the opponent): card against card builds its own from it. */
    internal var input by mutableStateOf<BenchInput?>(null)
        private set
    var log by mutableStateOf<ShootoutLog?>(null)
        private set

    /** Why nothing can be run, in words. */
    var problem by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    /** The trial on screen. */
    var proposal by mutableStateOf<Proposal?>(null)
        private set

    /** An answer being fitted, or a session or the results being read: further answers wait. */
    var thinking by mutableStateOf(false)
        private set
    var settled by mutableStateOf<StopRule.Settled?>(null)
        private set
    var results by mutableStateOf<ShootoutResults?>(null)
        private set

    /** The trials behind a number, listed. */
    var behind by mutableStateOf<Behind?>(null)

    /** The card being read below the hands. */
    var reading by mutableStateOf<Card?>(null)

    /** This session's answers, and its length when last answered (no clock ticks: nothing redraws while nothing moves). */
    var sessionAnswers by mutableStateOf(0)
        private set
    var sessionMs by mutableStateOf(0L)
        private set

    private var run: ShootoutRun? = null
    private var sessionId: String? = null
    private var sessionStart = 0L
    private var shownAt = 0L

    /** The id the hand on screen's answer will be kept under, and how many have been given this session. */
    private var shownId = ""
    private var given = 0

    /** The hands the person has judged for the deck and target chosen: what the page counts, never Ai's answers. */
    val handsJudged: Int get() = log?.trials?.count { it.judge == StoredTrial.PERSON } ?: 0

    /** What the deck's other matchups' files say about teaching (read with the deck), and whether any of them has a rubric. */
    private var elsewhere by mutableStateOf(TeachGate.Tally())
    private var rubricElsewhere by mutableStateOf(false)

    /** The deck's hands judged and whether Ai was taught, over every matchup: the gate for teaching (kai's choice, 1.1.8). */
    val deckTally: TeachGate.Tally get() = elsewhere + (log?.let(TeachGate::tally) ?: TeachGate.Tally())

    /**
     * Whether Setup offers teaching Ai: from [TeachGate.HANDS] hands judged for the deck, or always once Ai has been taught
     * on any of its matchups (an answer of Ai's, a teaching mode, the gate's settings, a rubric).
     */
    val teachShown: Boolean get() = TeachGate.shown(deckTally, rubric = rubricElsewhere || !rubricText.isNullOrBlank())

    /**
     * Whether a session is under way. The view (state) is read first, so whoever asks — the window's bar too — hears a
     * session begin and end; `run` is not state, and read first it hid the view from the bar's first look.
     */
    val running: Boolean get() = view == View.TRIAL && run != null

    /** The card behind a passcode, from the pool. */
    fun card(id: Int): Card? = h.builder.index.byId(CardId(id))

    /** The deck and target, read afresh: the builder's deck unless another was chosen, and its kept trials. */
    fun prepare(deck: String? = deckId, opponent: String? = opponentId) {
        val chosen = deck ?: h.builder.deckId
        if (run != null) end()
        deckId = deck
        opponentId = opponent
        if (chosen == null) {
            bench = null
            input = null
            log = null
            problem = "Save this deck to run a Shootout: its trials are kept with it."
            scope.launch { decks = withContext(Dispatchers.IO) { h.deps.deckRepository.all() }.map { DeckChoice(it.entry.id, it.entry.name) } }
            return
        }
        loading = true
        scope.launch {
            try {
                load(chosen, opponent)
            } catch (e: Exception) {
                problem = "The deck could not be read: ${e.message ?: e::class.simpleName}"
                bench = null
            } finally {
                loading = false
            }
        }
    }

    /** Another deck: its own trials, the deck alone first. */
    fun chooseDeck(id: String?) = prepare(id, null)

    /** Another target of the same deck: null for the deck alone. A pin keeps its turn ([ShootoutPin]). */
    fun chooseOpponent(id: String?) = prepare(deckId, id)

    private suspend fun load(deck: String, opponent: String?) {
        val library = withContext(Dispatchers.IO) { h.deps.deckRepository.all() }
        decks = library.map { DeckChoice(it.entry.id, it.entry.name) }
        val me = library.firstOrNull { it.entry.id == deck }
        if (me == null) {
            problem = "That deck is no longer in the library."
            bench = null
            return
        }
        deckName = me.entry.name
        val state = h.builder
        val siding = h.webs.sidingOf(me, state)
        // The field: the web's other decks, or the decks the deck's own siding links to.
        val web = h.webs.webOf(deck)
        val others: List<StoredDeck> = if (web != null) {
            web.deckIds.filter { it != deck }.mapNotNull { id -> library.firstOrNull { it.entry.id == id } }
        } else {
            siding.matchups.mapNotNull { m -> m.deckId?.let { id -> library.firstOrNull { it.entry.id == id } } }.distinctBy { it.entry.id }
        }
        opponents = others.map { OpponentChoice(it.entry.id, it.entry.name) }
        val them = opponent?.let { id -> others.firstOrNull { it.entry.id == id } }
        if (opponent != null && them == null) opponentId = null

        val myDeck = h.webs.deckOf(me, state)
        val groups = if (state.deckId == deck) state.groups else DeckGroupsCodec.read(me.extended).groups
        val mine = HashMap<Turn, SidePlan>()
        val theirs = HashMap<Turn, SidePlan>()
        // Where Siding finds this matchup (G.4's "Side it out"): the web's deck, else the deck's own matchup by its id.
        sidingTarget = them?.let { t -> if (web != null) t.entry.id else siding.against(t.entry.id, t.entry.name)?.let { "m:${it.id}" } ?: t.entry.id }
        if (them != null) {
            siding.against(them.entry.id, them.entry.name)?.let { m -> Turn.entries.forEach { mine[it] = m.plan(it) } }
            h.webs.sidingOf(them, state).against(me.entry.id, me.entry.name)?.let { m -> Turn.entries.forEach { theirs[it] = m.plan(it) } }
        }
        val path = ShootoutPaths.file(deck, them?.entry?.id)
        val kept = withContext(Dispatchers.IO) { File(dataDir, path).takeIf { it.isFile }?.readText() }?.let(ShootoutCodec::decode)
        val log = (kept ?: ShootoutLog(deck = deck, opponent = them?.entry?.id)).copy(opponentName = them?.entry?.name)
        val index = state.index
        val input = BenchInput(
            deck = myDeck,
            cards = { index.byId(it) },
            groups = groups,
            opponent = them?.let { Opponent(it.entry.id, it.entry.name, h.webs.deckOf(it, state)) },
            mine = mine,
            theirs = theirs,
            trials = log.trials,
        )
        val why = Bench.problem(input)
        problem = why
        this.input = input
        this.log = log
        rubricText = withContext(Dispatchers.IO) { File(dataDir, ShootoutPaths.rubric(deck, them?.entry?.id)).takeIf { it.isFile }?.readText() }
        // The deck's other matchups, for the teaching gate: counted per deck, so a new opponent keeps what the page has learned.
        val (tally, rubrics) = withContext(Dispatchers.IO) { deckElsewhere(deck, path) }
        elsewhere = tally
        rubricElsewhere = rubrics
        val made = if (why == null) withContext(Dispatchers.Default) { Bench.of(input) } else null
        // A pin the new bench cannot deal keeps its turn where it can, and says so: never a mixed session in its place.
        if (made != null) {
            val carried = ShootoutPin.carry(pinned, made)
            pinned = carried.pin
            carried.said?.let { h.neue.note = Note(it) }
        }
        bench = made
        results = null
        versus.forget()
        teach.forget()
        teach.readProgress()
    }

    /** The deck's other trial files, tallied, and whether any of its matchups keeps a rubric; [path] is the file read now. */
    private fun deckElsewhere(deck: String, path: String): Pair<TeachGate.Tally, Boolean> {
        val files = File(dataDir, ShootoutPaths.folder(deck)).listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }
        val mine = File(path).name
        val tally = files.filter { it.name.endsWith(".json") && it.name != mine }
            .mapNotNull { f -> try { ShootoutCodec.decode(f.readText()) } catch (e: Exception) { null } }
            .fold(TeachGate.Tally()) { acc, l -> acc + TeachGate.tally(l) }
        return tally to files.any { it.name.endsWith(ShootoutPaths.RUBRIC) && it.length() > 0 }
    }

    /** A session begun on the deck and target chosen, or the one under way carried on. */
    fun start() {
        if (view == View.TRIAL && run != null) return
        // Nothing begins while Ai sits its exam (design review, 1.1.6).
        if (teach.examRunning) return
        val b = bench ?: return
        val l = log ?: return
        if (thinking) return
        thinking = true
        val pin = pinned
        scope.launch {
            try {
                val now = h.deps.now()
                val r = withContext(Dispatchers.Default) { ShootoutRun(b, l, pin, seed = now) }
                run = r
                sessionId = "s" + now.toString(36)
                sessionStart = now
                sessionAnswers = 0
                given = 0
                sessionMs = 0
                reading = null
                teach.begin(r, now)
                view = View.TRIAL
                val (first, s) = nextFor(r)
                if (run !== r) return@launch
                show(r, first)
                settled = s
            } catch (e: Exception) {
                h.neue.note = Note("The session could not begin: ${e.message ?: e::class.simpleName}")
            } finally {
                thinking = false
            }
        }
    }

    /**
     * The next hand for the person (stage 3): the calibration set's next, or the picker's — and while Ai may judge a kind
     * alone, the hands it takes are kept as its own and another is chosen.
     */
    private suspend fun nextFor(r: ShootoutRun): Pair<Proposal, StopRule.Settled> {
        var p: Proposal = teach.fromSet() ?: fitting.withLock { withContext(Dispatchers.Default) { r.next() } }
        var taken = 0
        while (taken < ALONE_AT_ONCE && teach.fromSet() == null && teach.considers(r, p)) {
            // Ai is asked first: the last hand leaves the screen while it judges.
            proposal = null
            if (!teach.takesAlone(r, p, nextId())) break
            taken++
            p = fitting.withLock { withContext(Dispatchers.Default) { r.next() } }
        }
        val settled = fitting.withLock { withContext(Dispatchers.Default) { r.settled() } }
        return p to settled
    }

    private fun nextId(): String = "${teach.sessionId(sessionId) ?: "s"}-$given"

    /** Cards turned up for draws by effects on the trial on screen (1.1.5): how many off each deck. */
    var myDraws by mutableStateOf(0)
        private set
    var theirDraws by mutableStateOf(0)
        private set

    /** One more card off your deck for an effect that draws; a comparison has two hands of yours, so none. */
    fun drawMine() {
        val p = proposal as? Proposal.Rate ?: return
        if (myDraws < TrialDraws.deckSize(myHand(p), myRest(p))) myDraws++
    }

    /** One more card off their deck. */
    fun drawTheirs() {
        val p = proposal ?: return
        val hand = theirHand(p) ?: return
        if (theirDraws < TrialDraws.deckSize(hand, theirRest(p))) theirDraws++
    }

    private fun myRest(p: Proposal.Rate): List<Int> = bench?.restIds(p.stratum, p.hand).orEmpty()

    private fun theirRest(p: Proposal): List<Int> = bench?.theirRestIds(p.stratum, p.opponent).orEmpty()

    /** Your hand on screen, the turn's draw (the hand's own, dealt sixth) last and marked when you went second. */
    fun myHand(p: Proposal.Rate): TrialDraws.Ordered = bench?.shown(p.hand) ?: TrialDraws.Ordered(emptyList(), null)

    /** A comparison's two hands on screen, each with its turn's draw last when you went second. */
    fun myPair(p: Proposal.Compare): Pair<TrialDraws.Ordered, TrialDraws.Ordered> {
        val none = TrialDraws.Ordered(emptyList(), null)
        return (bench?.shown(p.left) ?: none) to (bench?.shown(p.right) ?: none)
    }

    /** Their hand on screen, likewise. */
    fun theirHand(p: Proposal): TrialDraws.Ordered? =
        p.opponent?.let { o -> bench?.theirShown(o, TrialDraws.seed(p, TrialDraws.THEIRS)) }

    /**
     * Your hand as it stands after your draws by effects: off the top, so when you went second the first takes your marked
     * sixth and the turn's draw is the next card down (1.1.7, kai).
     */
    fun myShown(p: Proposal.Rate): TrialDraws.Shown =
        TrialDraws.shown(myHand(p), myRest(p), myDraws, TrialDraws.seed(p, TrialDraws.MY_DRAWS))

    /** Their hand after their draws by effects, likewise. */
    fun theirShown(p: Proposal): TrialDraws.Shown? =
        theirHand(p)?.let { TrialDraws.shown(it, theirRest(p), theirDraws, TrialDraws.seed(p, TrialDraws.THEIR_DRAWS)) }

    /** What the trial on screen showed beyond its hands, kept with the answer. */
    private fun seen(p: Proposal): SeenDraws {
        val mine = (p as? Proposal.Rate)?.let(::myShown)
        val theirs = theirShown(p)
        return SeenDraws(turnDraw = mine?.draw, theirTurnDraw = theirs?.draw, drew = mine?.drawn.orEmpty(), theyDrew = theirs?.drawn.orEmpty())
    }

    private fun show(r: ShootoutRun, p: Proposal) {
        shownId = nextId()
        myDraws = 0
        theirDraws = 0
        proposal = p
        shownAt = h.deps.now()
        teach.shown(r, p, shownId)
    }

    /** The hand on screen answered on the five-point scale. */
    fun answer(answer: Answer) {
        val p = proposal as? Proposal.Rate ?: return
        val draws = seen(p)
        commit(p) { r, id, at, ms, saw, mode -> r.answer(p, answer, id, at, ms, teach.sessionId(sessionId), saw, mode, draws) }
    }

    /** One of the two hands on screen chosen. */
    fun prefer(left: Boolean) {
        val p = proposal as? Proposal.Compare ?: return
        val draws = seen(p)
        commit(p) { r, id, at, ms, saw, mode -> r.prefer(p, left, id, at, ms, teach.sessionId(sessionId), saw, mode, draws) }
    }

    private fun commit(p: Proposal, record: (ShootoutRun, String, Long, Long, Boolean, String?) -> StoredTrial) {
        val r = run ?: return
        if (thinking) return
        thinking = true
        val now = h.deps.now()
        val ms = now - shownAt
        val id = shownId
        val saw = teach.sawAi(p)
        val mode = teach.modeFor(p)
        scope.launch {
            try {
                val kept = fitting.withLock { withContext(Dispatchers.Default) { record(r, id, now, ms, saw, mode) } }
                given++
                kept(r)
                teach.answered(r, p, kept)
                // Stopped while this answer was fitted, or a calibration set done: it is kept, and nothing more is shown.
                if (run !== r || teach.setFinished) return@launch
                val (next, s) = nextFor(r)
                if (run !== r) return@launch
                show(r, next)
                settled = s
                sessionAnswers++
                sessionMs = h.deps.now() - sessionStart
                reading = null
            } catch (e: Exception) {
                h.neue.note = Note("The answer could not be fitted: ${e.message ?: e::class.simpleName}")
            } finally {
                thinking = false
            }
        }
    }

    /** [r]'s log, as it stands after an answer — the person's or Ai's — on screen and on disk. */
    internal suspend fun kept(r: ShootoutRun) {
        if (run === r || run == null) log = r.log
        try {
            save(r.log)
        } catch (e: Exception) {
            h.neue.note = Note("The answer could not be written: ${e.message ?: e::class.simpleName}")
        }
    }

    /** The session under way, or one read afresh from the trials kept (the trust panel, the exam). */
    internal fun runOrNew(): ShootoutRun? = run ?: bench?.let { b -> log?.let { ShootoutRun(b, it, pinned) } }

    /** The session's id, for Ai's answers kept during it. */
    internal fun sessionFor(): String? = teach.sessionId(sessionId)

    /** A calibration set done: Ai's exam on it, shown. */
    internal fun showExam() {
        run = null
        proposal = null
        reading = null
        view = View.EXAM
    }

    /** The person's note on a trial, kept beside the trials. */
    internal fun addNote(note: TrialNote) = amend { it.copy(notes = it.notes + note) }

    /** The gate's settings for this matchup. */
    internal fun setTrust(settings: TrustSettings) = amend { it.copy(trust = settings) }

    private fun amend(change: (ShootoutLog) -> ShootoutLog) {
        val r = run
        scope.launch {
            val next = if (r != null) {
                fitting.withLock { r.amend(change) }
                r.log
            } else {
                log?.let(change) ?: return@launch
            }
            log = next
            try {
                save(next)
            } catch (e: Exception) {
                h.neue.note = Note("The note could not be written: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    // ---- the rubric (stage 3) ---------------------------------------------------------------------------------------

    /** The matchup a rubric is written for: an interview's while one runs, else the page's. */
    fun rubricTarget(): RubricTarget? {
        if (h.ai.session?.mode == AiSession.MODE_RUBRIC) interviewing?.let { return it }
        val deck = deckId ?: h.builder.deckId ?: return null
        val b = bench ?: return null
        return RubricTarget(deck, deckName, opponentId.takeIf { !b.alone }, b.opponentName)
    }

    /** The rubric changed on disk (the interview, a review undone): read again. */
    fun rubricChanged() {
        val t = rubricTarget() ?: return
        scope.launch {
            rubricText = withContext(Dispatchers.IO) { File(dataDir, ShootoutPaths.rubric(t.deck, t.opponent)).takeIf { it.isFile }?.readText() }
        }
    }

    /** One of the rubric's entries taken out by the person. */
    fun removeRubricEntry(entry: String) {
        val t = rubricTarget() ?: return
        val doc = Rubric.read(rubricText, t.deckName, t.opponentName)
        val next = doc.copy(entries = doc.entries.filter { it != entry })
        writeRubric(t, next.render())
    }

    /** A recurring note offered for the rubric, taken: the person's own words, an entry. */
    fun addRubricEntry(entry: String) {
        val t = rubricTarget() ?: return
        when (val w = Rubric.add(Rubric.read(rubricText, t.deckName, t.opponentName), entry)) {
            is MemoryWrite.Done -> writeRubric(t, w.doc.render())
            is MemoryWrite.Refused -> h.neue.note = Note(w.message)
        }
    }

    private fun writeRubric(t: RubricTarget, text: String) {
        rubricText = text
        scope.launch {
            writing.withLock {
                withContext(Dispatchers.IO) {
                    // Written whole, then put in place, as the trials are: sync never reads half a rubric.
                    val f = File(dataDir, ShootoutPaths.rubric(t.deck, t.opponent))
                    f.parentFile?.mkdirs()
                    val temp = File(f.parentFile, ".${f.name}.tmp")
                    temp.writeText(text)
                    if (!temp.renameTo(f)) {
                        f.delete()
                        temp.renameTo(f)
                    }
                }
            }
        }
    }

    /** [passcode]'s group, as the bench numbers the deck's roles; null for a card it does not hold. */
    fun roleOf(passcode: Int): String? {
        val b = bench ?: return null
        val i = b.own.indexOf(b.canonical(passcode)).takeIf { it >= 0 } ?: return null
        return b.roleNames.getOrNull(b.spec.roles.getOrNull(i) ?: return null)
    }

    /** An opening hand of the deck dealt as a shuffle would (the setup's preview, G.4): passcodes, seeded so it holds still. */
    fun sampleHand(): List<Int> {
        val b = bench ?: return emptyList()
        val stratum = b.strata.firstOrNull() ?: return emptyList()
        val hand = b.decks.own(stratum).draw(Hand.OPENING, Random(PREVIEW_SEED))
        return hand.cards.flatMap { i -> List(hand[i]) { b.own[i] } }
    }

    /** Where Siding finds the matchup on the page: its opponent's id, or `m:` and the matchup's id. */
    private var sidingTarget: String? = null

    /** [passcode]'s copy as the deck holds it (any printing), or null. */
    private fun heldAs(passcode: Int, ids: List<CardId>): CardId? =
        ids.firstOrNull { CardIdentity.canonical(it) { x -> h.builder.index.byId(x) }.value == passcode }

    /**
     * "Try −1" and "Try +1" (Phase G, G.4): the builder on this deck with [passcode]'s copy picked out, so the inspector shows
     * each of the deck's questions at −1, now and +1 beside the copy stepper.
     */
    fun tryInBuilder(passcode: Int) {
        val deck = deckId ?: return
        h.openDeck(deck)
        scope.launch {
            withTimeoutOrNull(5_000) { snapshotFlow { h.builder.deckId }.first { it == deck } }
            val state = h.builder
            if (state.deckId != deck) return@launch
            heldAs(passcode, state.deck.main)?.let { showInDeck(state, h.neue, it, DeckSection.MAIN) }
        }
    }

    /** Whether "Side it out" can open: a matchup on the page. */
    val canSide: Boolean get() = deckId != null && opponentId != null && sidingTarget != null

    /**
     * "Side it out" (Phase G, G.4): Siding on this matchup, going [first] or second as the card was called, one copy of it
     * marked out there.
     */
    fun sideOut(passcode: Int, first: Boolean) {
        val deck = deckId ?: return
        val target = sidingTarget ?: return
        val held = heldAs(passcode, input?.deck?.main.orEmpty()) ?: CardId(passcode)
        h.webs.sidingOut = (if (first) Turn.FIRST else Turn.SECOND) to held
        h.webs.side(deck, target)
    }

    /** What `shootout_results` tells Ai (Phase G, D1): the results for the deck and target on the page, in words. */
    internal suspend fun resultsForAi(): String? {
        val b = bench ?: return null
        val name = deckName
        val r = withContext(Dispatchers.Default) { runOrNew()?.results() } ?: return null
        if (r.kept == 0) return "No hands judged yet for “$name”" + (b.opponentName?.let { " against “$it”" } ?: "") + ": nothing is rated."
        return ShootoutResultsWords.describe(r, name, b.opponentName) { card(it)?.name ?: "#$it" }
    }

    /**
     * `shootout_whatif` (Phase G, D2): [from] made [to], each a name or passcode of a card the hands have numbered; the answer in
     * words, or why it cannot be read (`first` false).
     */
    internal suspend fun whatIfForAi(from: String?, to: String?): Pair<Boolean, String> {
        val b = bench ?: return false to "Shootout has no deck chosen: open the page (navigate shootout) on a saved deck."
        fun find(word: String): Int? {
            val w = word.trim()
            w.toIntOrNull()?.let { p -> return b.canonical(p).takeIf { it in b.own } }
            b.own.firstOrNull { card(it)?.name.equals(w, ignoreCase = true) }?.let { return it }
            return b.own.filter { card(it)?.name?.contains(w, ignoreCase = true) == true }.singleOrNull()
        }
        val a = from?.let { find(it) ?: return false to "“$it” is not a card Shootout has numbered for this deck." }
        val c = to?.let { find(it) ?: return false to "“$it” is not a card Shootout has numbered for this deck." }
        if (a == null && c == null) return false to "Name from, to, or both."
        if (a == c) return false to "from and to are the same card."
        val by = withContext(Dispatchers.Default) { runOrNew()?.whatIf(a, c) } ?: return false to "Nothing is judged yet to read a change from."
        fun n(p: Int) = card(p)?.name ?: "#$p"
        val change = when {
            a != null && c != null -> "One copy of ${n(a)} made ${n(c)}"
            c != null -> "One more ${n(c)}, in the place of a copy of any other card alike"
            else -> "One fewer ${n(a!!)}, its place any other card of the deck alike"
        }
        return true to ShootoutResultsWords.whatIf(change, by)
    }

    /** What `shootout_state` tells Ai: the matchup, the trials, the rubric and the trust panel in words. */
    internal suspend fun describeForAi(): String? {
        val b = bench ?: return null
        val l = log ?: return null
        val report = withContext(Dispatchers.Default) { ShootoutTrust.read(runOrNew() ?: return@withContext null) } ?: return null
        return ShootoutTrustWords.describe(deckName, b, l, rubricText, report) { card(it)?.name ?: "#$it" }
    }

    /** The session ended — every answer is already kept — and its results shown. */
    fun stop() {
        if (run == null || thinking) return
        showResults()
    }

    /** The results of the deck and target chosen, read from every trial kept. */
    fun showResults() {
        val b = bench ?: run?.bench ?: return
        val l = run?.log ?: log ?: return
        if (thinking) return
        val r = run
        end()
        view = View.RESULTS
        thinking = true
        scope.launch {
            try {
                results = withContext(Dispatchers.Default) { (r ?: ShootoutRun(b, l, pinned)).results() }
            } catch (e: Exception) {
                h.neue.note = Note("The results could not be read: ${e.message ?: e::class.simpleName}")
            } finally {
                thinking = false
            }
        }
    }

    /** The results, or back from them: to the session under way, else to the start. */
    fun toggleResults() {
        if (view == View.RESULTS) view = View.SETUP else showResults()
    }

    // ---- adjusting and erasing kept trials (2026-10, kai) -------------------------------------------------------------

    /**
     * The person's answer to [t] changed to [to] — a rating's answer name, or a comparison's [StoredTrial.LEFT]/
     * [StoredTrial.RIGHT] — kept, and the ratings read again from every trial.
     */
    fun adjust(t: StoredTrial, to: String) {
        val at = h.deps.now()
        rewrite("The answer could not be changed") { it.adjusted(t.id, to, at) ?: it }
    }

    /** [t] erased, with Ai's answers to it and the notes on it, and the ratings read again; the note that says so puts it back. */
    fun erase(t: StoredTrial) {
        var gone: Erasure? = null
        rewrite("The hand could not be erased", after = {
            val e = gone ?: return@rewrite
            h.neue.note = Note("Hand erased.", action = "Undo", lastsMs = 10_000) {
                rewrite("The hand could not be put back") { it.restored(e) }
            }
        }) { l -> l.erased(setOf(t.id)).also { gone = it }.log }
    }

    /**
     * The log with [change] made to its trials, on screen and on disk, the session's fit (or the results shown) read again
     * from all of them. Under way, the change waits for the answer being fitted.
     */
    private fun rewrite(failed: String, after: () -> Unit = {}, change: (ShootoutLog) -> ShootoutLog) {
        val r = run
        scope.launch {
            try {
                val next = if (r != null) {
                    fitting.withLock { withContext(Dispatchers.Default) { r.rewrite(change) } }
                    r.log
                } else {
                    log?.let(change) ?: return@launch
                }
                if (next === log) return@launch
                log = next
                save(next)
                if (r != null && run === r) settled = fitting.withLock { withContext(Dispatchers.Default) { r.settled() } }
                val b = bench
                if (view == View.RESULTS && r == null && b != null) {
                    results = withContext(Dispatchers.Default) { ShootoutRun(b, next).results() }
                }
                teach.readProgress()
                after()
            } catch (e: Exception) {
                h.neue.note = Note("$failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** The trials behind a number, newest first. */
    fun trialsBehind(b: Behind): List<StoredTrial> {
        val bench = bench
        return ShootoutResults.trialsBehind(log?.trials.orEmpty(), b, { t -> bench?.kindOf(t)?.key }, { bench?.canonical(it) ?: it })
    }

    private fun end() {
        run = null
        proposal = null
        reading = null
        teach.end()
        if (view == View.TRIAL) view = View.SETUP
    }

    /** The log written whole, then put in place: a crash mid-write leaves the last one. */
    internal suspend fun save(log: ShootoutLog) = write(ShootoutPaths.file(log.deck, log.opponent), log)

    /** [log] written whole to [path] (under the data folder), then put in place. */
    internal suspend fun write(path: String, log: ShootoutLog) = writing.withLock {
        withContext(Dispatchers.IO) {
            val target = File(dataDir, path)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, ".${target.name}.tmp")
            temp.writeText(ShootoutCodec.encode(log))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    /** What came in by a sync or a restore, read again — never under a session's feet. */
    fun reload() {
        if (run != null || versus.running) return
        if (bench != null || problem != null) prepare()
    }

    /** A deck deleted: its trials go with it. */
    fun forgetDeck(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { File(dataDir, ShootoutPaths.folder(id)).deleteRecursively() }
            if (deckId == id || h.builder.deckId == id) {
                run = null
                deckId = null
                bench = null
                log = null
                elsewhere = TeachGate.Tally()
                rubricElsewhere = false
                results = null
                versus.forget()
                view = View.SETUP
            }
        }
    }

    /**
     * The studio's picture (`--shootout=demo`): [answers] trials answered by a judge that likes the deck's most-played
     * card, synchronously, then the view asked for. Never in the app.
     */
    fun demo(deck: String, opponent: String?, answers: Int, show: View) {
        runBlocking { load(deck, opponent) }
        deckId = deck
        opponentId = opponent
        val b = bench ?: return
        val r0 = ShootoutRun(b, log ?: return, seed = 5)
        val favourite = b.own.first()
        repeat(answers) { i ->
            when (val p = r0.next()) {
                is Proposal.Rate -> {
                    val ids = b.ids(p.hand)
                    val score = ids.count { it == favourite } * 2 + ids.toSet().size - (p.opponent?.size ?: 0) / 3 + (i % 3) - 3
                    val a = when {
                        score >= 4 -> Answer.CLEAR_WIN
                        score >= 2 -> Answer.LEAN_WIN
                        score >= 1 -> Answer.COIN_FLIP
                        score >= 0 -> Answer.LEAN_LOSS
                        else -> Answer.CLEAR_LOSS
                    }
                    r0.answer(p, a, "demo-$i", at = 1_760_000_000_000L + i * 7_000L, ms = 3_000, session = "demo")
                }
                is Proposal.Compare -> r0.prefer(p, b.ids(p.left).count { it == favourite } >= b.ids(p.right).count { it == favourite }, "demo-$i", at = 1_760_000_000_000L + i * 7_000L, session = "demo")
            }
        }
        val r = when (teaching) {
            null -> r0
            "early" -> demoEarly(r0)
            else -> demoTeaching(r0)
        }
        log = r.log
        when (show) {
            View.RESULTS -> {
                results = r.results()
                view = View.RESULTS
            }
            View.TRIAL -> {
                run = r
                proposal = r.next()
                settled = r.settled()
                sessionId = "demo"
                sessionAnswers = answers.coerceAtMost(14)
                sessionMs = 6 * 60_000L
                view = View.TRIAL
            }
            View.SETUP -> view = View.SETUP
            View.EXAM -> view = View.EXAM
            View.VERSUS -> view = View.SETUP
        }
        teaching?.let { what ->
            teach.demo(r, proposal, what)
            if (what == "exam" || what == "exam-running") view = View.EXAM
            if (what == "calibration") teach.mode = ShootoutTeach.Mode.CALIBRATION
        }
    }

    /**
     * The studio's teaching data (`--shootout-teach=`): the demo's answers read as a calibration set (its first 32) and
     * apprentice sessions, Ai beside every one of them — agreeing within a step most of the time, a little optimistic —
     * some hands Ai judged alone and a few audits, notes that keep coming back, and a rubric. Never in the app.
     */
    private fun demoTeaching(r0: ShootoutRun): ShootoutRun {
        val b = r0.bench
        val random = kotlin.random.Random(11)
        val person = r0.log.trials.filter { it.judge == StoredTrial.PERSON && it.kind == StoredTrial.RATE }
        val retagged = r0.log.trials.map { t ->
            val i = person.indexOf(t)
            when {
                i in 0 until 32 -> t.copy(mode = TeachModes.CALIBRATION, session = "cal-demo")
                i >= 0 && i % 9 == 4 -> t.copy(mode = TeachModes.AUDIT)
                i >= 0 && i % 11 == 7 -> t.copy(mode = TeachModes.SUPERVISED, sawAi = true)
                i >= 0 -> t.copy(mode = TeachModes.APPRENTICE)
                else -> t
            }
        }
        val r = ShootoutRun(b, r0.log.copy(trials = retagged), seed = 5)
        val fresh = r
        val ai = ArrayList<StoredTrial>()
        retagged.filter { it.judge == StoredTrial.PERSON && it.kind == StoredTrial.RATE }.forEachIndexed { i, t ->
            val p = b.proposal(t) as? Proposal.Rate ?: return@forEachIndexed
            val mine = Answer.entries.first { it.name == t.answer }
            val shift = when (random.nextInt(10)) { 0 -> -2; 1, 2 -> 1; 3 -> -1; else -> 0 }
            val said = if (t.sawAi) mine else Answer.entries[(mine.ordinal + shift).coerceIn(0, 4)]
            val sure = if (shift == 0 || shift == 1) 0.8 + 0.18 * random.nextDouble() else 0.55 + 0.3 * random.nextDouble()
            val verdict = AiVerdict(
                answer = said.name, sure = sure, why = "Reads as the examples nearest it.", model = "demo",
                kind = b.kindOf(p).key, print = b.print, asked = t.at - 1_000,
            )
            val mode = if (t.mode == TeachModes.CALIBRATION) TeachModes.EXAM else t.mode ?: "apprentice"
            ai += b.aiAnswer(p, verdict, "${t.id}~ai", t.at + 500, of = t.id, mode = mode, session = t.session)
            if (i % 5 == 0) {
                val q = fresh.next() as? Proposal.Rate ?: return@forEachIndexed
                ai += b.aiAnswer(q, verdict.copy(answer = fresh.predict(q).likeliest?.name ?: said.name, sure = 0.9), "${t.id}~solo", t.at + 700, of = null, mode = TeachModes.SOLO, session = t.session)
            }
        }
        r.recordAll(ai)
        val ids = person.map { it.id }
        val notes = listOf(
            "Only wins if they have no Imperm for the starter",
            "Imperm on the starter and it's over",
            "They had Imperm and Ash: nothing gets through",
            "Bricked: no starter, no searcher",
            "Bricked hard, two garnets",
            "Imperm again, even with the extender",
        ).mapIndexed { k, text -> TrialNote(ids.getOrElse(k * 5) { ids.first() }, text, 1_760_000_000_000L + k * 40_000L) }
        r.amend { it.copy(notes = notes, trust = TrustSettings(bar = 0.85, solo = true)) }
        rubricText = "# Rubric: demo\n\n" +
            "- A starter and a hand trap beats their turn one unless their trap is Imperm on the starter.\n" +
            "- Going second, a hand without a board breaker is a lean loss at best, whatever else it holds.\n" +
            "- Two garnets in five cards is a clear loss going first; one garnet with a starter is a coin flip.\n"
        return r
    }

    /**
     * The studio's store early in teaching (`--shootout-teach=early`): the demo's first 12 answers kept as a calibration set
     * Ai has not sat its exam on, nothing else taught. Never in the app.
     */
    private fun demoEarly(r0: ShootoutRun): ShootoutRun {
        val person = r0.log.trials.filter { it.judge == StoredTrial.PERSON }.take(12).map { it.id }.toSet()
        val retagged = r0.log.trials.map { t -> if (t.id in person) t.copy(mode = TeachModes.CALIBRATION, session = "cal-demo") else t }
        return ShootoutRun(r0.bench, r0.log.copy(trials = retagged), seed = 5)
    }

    /** The studio's teaching screen (`--shootout-teach=`): set before [demo]. */
    var teaching: String? = null

    companion object {
        /** The setup preview's hand: one deal, the same each visit. */
        private const val PREVIEW_SEED = 41L

        /** The most hands Ai takes alone between two of the person's: the person is never left waiting long. */
        const val ALONE_AT_ONCE = 12

        /** A rubric's path as Ai's memory review reads it: from `<data>/ai`, one folder up. */
        fun reviewPath(t: RubricTarget): String = "../" + ShootoutPaths.rubric(t.deck, t.opponent)
    }
}
