package com.kaiharimoto.neue.shootout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
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

    enum class View { SETUP, TRIAL, RESULTS }

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
        bench = if (why == null) withContext(Dispatchers.Default) { Bench.of(input) } else null
        results = null
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
                val (r, first, s) = withContext(Dispatchers.Default) {
                    val r = ShootoutRun(b, l, pin, seed = now)
                    Triple(r, r.next(), r.settled())
                }
                run = r
                proposal = first
                settled = s
                sessionId = "s" + now.toString(36)
                sessionStart = now
                sessionAnswers = 0
                sessionMs = 0
                shownAt = now
                reading = null
                view = View.TRIAL
            } catch (e: Exception) {
                h.neue.note = Note("The session could not begin: ${e.message ?: e::class.simpleName}")
            } finally {
                thinking = false
            }
        }
    }

    /** The hand on screen answered on the five-point scale. */
    fun answer(answer: Answer) {
        val p = proposal as? Proposal.Rate ?: return
        commit { r, id, at, ms -> r.answer(p, answer, id, at, ms, sessionId) }
    }

    /** One of the two hands on screen chosen. */
    fun prefer(left: Boolean) {
        val p = proposal as? Proposal.Compare ?: return
        commit { r, id, at, ms -> r.prefer(p, left, id, at, ms, sessionId) }
    }

    private fun commit(record: (ShootoutRun, String, Long, Long) -> StoredTrial) {
        val r = run ?: return
        if (thinking) return
        thinking = true
        val now = h.deps.now()
        val ms = now - shownAt
        val id = "${sessionId ?: "s"}-$sessionAnswers"
        scope.launch {
            try {
                val (next, s) = withContext(Dispatchers.Default) {
                    record(r, id, now, ms)
                    r.next() to r.settled()
                }
                log = r.log
                try {
                    save(r.log)
                } catch (e: Exception) {
                    h.neue.note = Note("The answer could not be written: ${e.message ?: e::class.simpleName}")
                }
                // Stopped while this answer was fitted: it is kept, and nothing more is shown.
                if (run !== r) return@launch
                proposal = next
                settled = s
                sessionAnswers++
                sessionMs = h.deps.now() - sessionStart
                shownAt = h.deps.now()
                reading = null
            } catch (e: Exception) {
                h.neue.note = Note("The answer could not be fitted: ${e.message ?: e::class.simpleName}")
            } finally {
                thinking = false
            }
        }
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
    fun trialsBehind(b: Behind): List<StoredTrial> = ShootoutResults.trialsBehind(log?.trials.orEmpty(), b)

    private fun end() {
        run = null
        proposal = null
        reading = null
        if (view == View.TRIAL) view = View.SETUP
    }

    /** The log written whole, then put in place: a crash mid-write leaves the last one. */
    private suspend fun save(log: ShootoutLog) = writing.withLock {
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
        val r = ShootoutRun(b, log ?: return, seed = 5)
        val favourite = b.own.first()
        repeat(answers) { i ->
            when (val p = r.next()) {
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
                    r.answer(p, a, "demo-$i", at = 1_760_000_000_000L + i * 7_000L, ms = 3_000, session = "demo")
                }
                is Proposal.Compare -> r.prefer(p, b.ids(p.left).count { it == favourite } >= b.ids(p.right).count { it == favourite }, "demo-$i", at = 1_760_000_000_000L + i * 7_000L, session = "demo")
            }
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
        }
    }
}
