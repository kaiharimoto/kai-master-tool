package com.kaiharimoto.neue.shootout

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
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrust
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrustWords
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrialNote
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import com.kaiharimoto.mastertool.core.shootout.teach.Rubric
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
class Shootouts(private val dataDir: File, private val h: NeueHolders) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writing = Mutex()

    /** One answer fitted at a time: the person's and Ai's land from different coroutines (stage 3). */
    internal val fitting = Mutex()

    /** Teaching Ai, and the gate that lets it judge alone (Phase S stage 3): an owned part. */
    val teach = ShootoutTeach(this, h, scope)

    enum class View { SETUP, TRIAL, RESULTS, EXAM }

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

    /** Whether a session is under way. */
    val running: Boolean get() = run != null && view == View.TRIAL

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

    /** Another target of the same deck: null for the deck alone. */
    fun chooseOpponent(id: String?) {
        pinned = null
        prepare(deckId, id)
    }

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
        this.log = log
        rubricText = withContext(Dispatchers.IO) { File(dataDir, ShootoutPaths.rubric(deck, them?.entry?.id)).takeIf { it.isFile }?.readText() }
        bench = if (why == null) withContext(Dispatchers.Default) { Bench.of(input) } else null
        results = null
        teach.forget()
    }

    /** A session begun on the deck and target chosen, or the one under way carried on. */
    fun start() {
        if (view == View.TRIAL && run != null) return
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
        if (myDraws < myRest(p).size) myDraws++
    }

    /** One more card off their deck. */
    fun drawTheirs() {
        val p = proposal ?: return
        if (theirDraws < theirRest(p).size) theirDraws++
    }

    private fun myRest(p: Proposal.Rate): List<Int> = bench?.restIds(p.stratum, p.hand).orEmpty()

    private fun theirRest(p: Proposal): List<Int> = bench?.theirRestIds(p.stratum, p.opponent).orEmpty()

    /** Your hand on screen, the turn's draw last and marked when you went second. */
    fun myHand(p: Proposal.Rate): TrialDraws.Ordered =
        TrialDraws.ordered(bench?.ids(p.hand).orEmpty(), TrialDraws.seed(shownId, TrialDraws.MINE))

    /** Their hand on screen, likewise. */
    fun theirHand(p: Proposal): TrialDraws.Ordered? =
        p.opponent?.let { o -> bench?.opponentIds(o) }?.let { TrialDraws.ordered(it, TrialDraws.seed(shownId, TrialDraws.THEIRS)) }

    /** The cards turned up so far for your draws, in order. */
    fun myDrawn(p: Proposal.Rate): List<Int> = TrialDraws.drawn(myRest(p), myDraws, TrialDraws.seed(shownId, TrialDraws.MY_DRAWS))

    /** The cards turned up so far for theirs. */
    fun theirDrawn(p: Proposal): List<Int> = TrialDraws.drawn(theirRest(p), theirDraws, TrialDraws.seed(shownId, TrialDraws.THEIR_DRAWS))

    /** What the trial on screen showed beyond its hands, kept with the answer. */
    private fun seen(p: Proposal): SeenDraws = SeenDraws(
        turnDraw = (p as? Proposal.Rate)?.let { myHand(it).draw },
        theirTurnDraw = theirHand(p)?.draw,
        drew = (p as? Proposal.Rate)?.let(::myDrawn).orEmpty(),
        theyDrew = theirDrawn(p),
    )

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
    internal fun runOrNew(): ShootoutRun? = run ?: bench?.let { b -> log?.let { ShootoutRun(b, it) } }

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
                results = withContext(Dispatchers.Default) { (r ?: ShootoutRun(b, l)).results() }
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

    /** The trials behind a number, newest first. */
    fun trialsBehind(b: Behind): List<StoredTrial> = ShootoutResults.trialsBehind(log?.trials.orEmpty(), b) { t -> bench?.kindOf(t)?.key }

    private fun end() {
        run = null
        proposal = null
        reading = null
        teach.end()
        if (view == View.TRIAL) view = View.SETUP
    }

    /** The log written whole, then put in place: a crash mid-write leaves the last one. */
    internal suspend fun save(log: ShootoutLog) = writing.withLock {
        withContext(Dispatchers.IO) {
            val target = File(dataDir, ShootoutPaths.file(log.deck, log.opponent))
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
        if (run != null) return
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
                results = null
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
        val r = if (teaching != null) demoTeaching(r0) else r0
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

    /** The studio's teaching screen (`--shootout-teach=`): set before [demo]. */
    var teaching: String? = null

    companion object {
        /** The most hands Ai takes alone between two of the person's: the person is never left waiting long. */
        const val ALONE_AT_ONCE = 12

        /** A rubric's path as Ai's memory review reads it: from `<data>/ai`, one folder up. */
        fun reviewPath(t: RubricTarget): String = "../" + ShootoutPaths.rubric(t.deck, t.opponent)
    }
}
