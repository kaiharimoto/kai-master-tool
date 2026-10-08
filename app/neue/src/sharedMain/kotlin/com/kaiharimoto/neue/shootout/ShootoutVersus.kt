package com.kaiharimoto.neue.shootout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.CardSwap
import com.kaiharimoto.mastertool.core.shootout.bench.SeenDraws
import com.kaiharimoto.mastertool.core.shootout.bench.TrialDraws
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.VersusPick
import com.kaiharimoto.mastertool.core.shootout.versus.VersusDeal
import com.kaiharimoto.mastertool.core.shootout.versus.VersusResults
import com.kaiharimoto.mastertool.core.shootout.versus.VersusRun
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Card against card (2026-10, kai: "compare two cards … the user picks one card in the current deck and chooses a
 * substitute"): Shootout's goal-oriented session, held beside [Shootouts] for the deck and target it has chosen. The
 * person picks a card of the main deck and a substitute; each hand is dealt from the deck as built or with the substitute
 * in every copy's place, about half each, and rated on the same five answers; the results say which card is better on its
 * own, beside which cards, and which deck does better overall ([VersusResults]).
 *
 * Its trials are a log of their own, `<deck>/versus/<target>.<card>.<substitute>.json` ([ShootoutPaths.versus]): synced,
 * backed up and deleted with the deck, and never mixed into the deck's own ratings. Every answer is written as it is given.
 */
class ShootoutVersus(private val s: Shootouts, private val h: NeueHolders) {

    enum class Phase { SETUP, TRIAL, RESULTS }

    /** An earlier comparison of this deck and target: the two cards and the hands judged. */
    data class Earlier(val pick: VersusPick, val hands: Int)

    var phase by mutableStateOf(Phase.SETUP)
        private set

    /** The card of the main deck being compared, a canonical passcode; null until one is chosen. */
    var card by mutableStateOf<Int?>(null)

    /** The card put in its place, a canonical passcode. */
    var substitute by mutableStateOf<Int?>(null)

    /** The turn the session deals, or null for both. */
    var pinned by mutableStateOf<Stratum?>(null)

    /** The substitute's chooser is open. */
    var choosing by mutableStateOf(false)

    var earlier by mutableStateOf<List<Earlier>>(emptyList())
        private set

    var bench by mutableStateOf<Bench?>(null)
        private set

    /** The hand on screen. */
    var deal by mutableStateOf<VersusDeal?>(null)
        private set

    /** The comparison as it stands: the progress line's reading, and the results'. */
    var progress by mutableStateOf<VersusResults?>(null)
        private set
    var results by mutableStateOf<VersusResults?>(null)
        private set

    var thinking by mutableStateOf(false)
        private set
    var sessionAnswers by mutableStateOf(0)
        private set
    var sessionMs by mutableStateOf(0L)
        private set

    /** Cards turned up for draws by effects on the hand on screen. */
    var myDraws by mutableStateOf(0)
        private set
    var theirDraws by mutableStateOf(0)
        private set

    private var run: VersusRun? = null
    private var path: String? = null
    private var sessionId = ""
    private var sessionStart = 0L
    private var shownAt = 0L
    private var given = 0

    /** A session is dealing hands. */
    val running: Boolean get() = s.view == Shootouts.View.VERSUS && phase == Phase.TRIAL && run != null

    /** Card against card opened on its setup, the earlier comparisons read. */
    fun open() {
        if (s.running) return
        s.view = Shootouts.View.VERSUS
        if (phase == Phase.TRIAL && run == null) phase = Phase.SETUP
        readEarlier()
    }

    /** Back to the Shootout's own setup; every answer is already kept. */
    fun leave() {
        end()
        phase = Phase.SETUP
        s.view = Shootouts.View.SETUP
    }

    /** The deck or target changed under it: the session ends, the cards stay chosen while the new deck holds them. */
    fun forget() {
        end()
        results = null
        progress = null
        bench = null
        if (phase != Phase.SETUP) phase = Phase.SETUP
        val input = s.input
        if (input != null && card != null && input.deck.main.none { canon(it.value) == card }) card = null
        if (s.view == Shootouts.View.VERSUS) readEarlier()
    }

    private fun canon(passcode: Int): Int = CardIdentity.canonical(CardId(passcode)) { h.builder.index.byId(it) }.value

    /** The deck's main-deck cards, each once, in deck order: what can be compared. */
    fun deckCards(): List<Int> = s.input?.deck?.main?.map { canon(it.value) }?.distinct().orEmpty()

    /** Copies of [passcode] in the main deck. */
    fun copies(passcode: Int): Int = s.input?.deck?.main?.count { canon(it.value) == passcode } ?: 0

    /** The side deck's Main Deck cards: the first substitutes a player would try. */
    fun sideCards(): List<Int> = s.input?.deck?.side.orEmpty()
        .filter { h.builder.index.byId(it)?.isExtraDeck != true }
        .map { canon(it.value) }.distinct().filter { it != card }

    /** [passcode] chosen as the substitute (an alternate artwork is its card), and the chooser closed. */
    fun chooseSubstitute(passcode: Int) {
        substitute = canon(passcode)
        choosing = false
    }

    /** A card of the main deck chosen to compare; a substitute that is the same card is let go. */
    fun chooseCard(passcode: Int) {
        card = passcode
        if (substitute == passcode) substitute = null
    }

    /** The strata a comparison can deal for the target chosen: both turns, game 1 in a matchup. */
    fun strata(): List<Stratum> = if (s.opponentId == null || s.bench?.alone != false) Bench.ALONE else Bench.MATCHUP.filter { !it.sided }

    /** Why the two cards cannot be compared yet, or null. */
    fun problem(): String? {
        val input = s.input ?: return s.problem ?: "Choose a deck first."
        val c = card ?: return "Choose the card to compare."
        val sub = substitute ?: return "Choose its substitute."
        return Bench.problem(swapped(input, c, sub, emptyList()))
    }

    private fun swapped(input: BenchInput, c: Int, sub: Int, trials: List<StoredTrial>) = BenchInput(
        deck = input.deck,
        cards = input.cards,
        groups = input.groups,
        opponent = input.opponent,
        trials = trials,
        swap = CardSwap(CardId(c), CardId(sub)),
    )

    /** The earlier comparisons of this deck and target, newest first. */
    private fun readEarlier() {
        val deck = s.deckId ?: h.builder.deckId ?: return
        val prefix = (s.opponentId?.let(ShootoutPaths::safe) ?: "alone") + "."
        s.scope.launch {
            earlier = withContext(Dispatchers.IO) {
                File(s.dataDir, ShootoutPaths.versusFolder(deck)).listFiles().orEmpty()
                    .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".json") }
                    .sortedByDescending { it.lastModified() }
                    .mapNotNull { f -> ShootoutCodec.decode(f.readText())?.let { l -> l.versus?.let { Earlier(it, l.trials.size) } } }
            }
        }
    }

    /** An earlier comparison chosen again: its cards set, and its results shown or a session begun. */
    fun resume(e: Earlier, showResults: Boolean) {
        card = e.pick.card
        substitute = e.pick.substitute
        begin(showResults)
    }

    /**
     * A session begun on the two cards chosen — the log kept for them read first, so the answers carry over — or, with
     * [showResults], only their results.
     */
    fun begin(showResults: Boolean = false) {
        if (thinking || running) return
        val input = s.input ?: return
        val deck = s.deckId ?: h.builder.deckId ?: return
        val c = card ?: return
        val sub = substitute ?: return
        problem()?.let { h.neue.note = Note(it); return }
        thinking = true
        val pin = pinned
        s.scope.launch {
            try {
                val now = h.deps.now()
                val file = ShootoutPaths.versus(deck, input.opponent?.id, c, sub)
                val kept = withContext(Dispatchers.IO) { File(s.dataDir, file).takeIf { it.isFile }?.readText() }?.let(ShootoutCodec::decode)
                val log = (kept ?: ShootoutLog(deck = deck, opponent = input.opponent?.id, versus = VersusPick(c, sub)))
                    .copy(opponentName = input.opponent?.name, versus = VersusPick(c, sub))
                val (b, r) = withContext(Dispatchers.Default) {
                    val b = Bench.of(swapped(input, c, sub, log.trials))
                    b to VersusRun(b, log, pin, seed = now)
                }
                bench = b
                path = file
                if (showResults) {
                    results = withContext(Dispatchers.Default) { r.results() }
                    phase = Phase.RESULTS
                    return@launch
                }
                run = r
                sessionId = "v" + now.toString(36)
                sessionStart = now
                sessionAnswers = 0
                sessionMs = 0
                given = 0
                s.reading = null
                val (first, p) = withContext(Dispatchers.Default) { r.next() to r.progress() }
                show(first)
                progress = p
                phase = Phase.TRIAL
            } catch (e: Exception) {
                h.neue.note = Note("The comparison could not begin: ${e.message ?: e::class.simpleName}")
            } finally {
                thinking = false
            }
        }
    }

    private fun show(d: VersusDeal) {
        myDraws = 0
        theirDraws = 0
        deal = d
        shownAt = h.deps.now()
    }

    /** The hand on screen answered. */
    fun answer(answer: Answer) {
        val r = run ?: return
        val d = deal ?: return
        if (thinking) return
        thinking = true
        val now = h.deps.now()
        val ms = now - shownAt
        val id = "$sessionId-$given"
        val draws = seen(d)
        s.scope.launch {
            try {
                s.fitting.withLock { withContext(Dispatchers.Default) { r.answer(d, answer, id, now, ms, sessionId, draws) } }
                given++
                path?.let { s.write(it, r.log) }
                if (run !== r) return@launch
                val (next, p) = s.fitting.withLock { withContext(Dispatchers.Default) { r.next() to r.progress() } }
                if (run !== r) return@launch
                show(next)
                progress = p
                sessionAnswers++
                sessionMs = h.deps.now() - sessionStart
                s.reading = null
            } catch (e: Exception) {
                h.neue.note = Note("The answer could not be kept: ${e.message ?: e::class.simpleName}")
            } finally {
                thinking = false
            }
        }
    }

    /** The session stopped and its results shown; every answer is already kept. */
    fun stop() {
        val r = run ?: return
        if (thinking) return
        end()
        phase = Phase.RESULTS
        thinking = true
        s.scope.launch {
            try {
                results = withContext(Dispatchers.Default) { r.results() }
            } catch (e: Exception) {
                h.neue.note = Note("The results could not be read: ${e.message ?: e::class.simpleName}")
            } finally {
                thinking = false
            }
        }
    }

    /** Back to choosing the cards. */
    fun toSetup() {
        end()
        phase = Phase.SETUP
        readEarlier()
    }

    private fun end() {
        run = null
        deal = null
        s.reading = null
    }

    // ---- draws by effects, as a Shootout's (1.1.5) -------------------------------------------------------------------

    fun drawMine() {
        val d = deal ?: return
        if (myDraws < TrialDraws.deckSize(myHand(d), myRest(d))) myDraws++
    }

    fun drawTheirs() {
        val d = deal ?: return
        val hand = theirHand(d) ?: return
        if (theirDraws < TrialDraws.deckSize(hand, theirRest(d))) theirDraws++
    }

    private fun myHand(d: VersusDeal): TrialDraws.Ordered = bench?.shown(d.proposal.hand) ?: TrialDraws.Ordered(emptyList(), null)

    private fun myRest(d: VersusDeal): List<Int> = bench?.restIds(d.proposal.stratum, d.proposal.hand, d.substituted).orEmpty()

    private fun theirHand(d: VersusDeal): TrialDraws.Ordered? =
        d.proposal.opponent?.let { o -> bench?.theirShown(o, TrialDraws.seed(d.proposal, TrialDraws.THEIRS)) }

    private fun theirRest(d: VersusDeal): List<Int> = bench?.theirRestIds(d.proposal.stratum, d.proposal.opponent).orEmpty()

    /** Your hand on screen after your draws by effects, off the deck it was dealt from. */
    fun myShown(d: VersusDeal): TrialDraws.Shown =
        TrialDraws.shown(myHand(d), myRest(d), myDraws, TrialDraws.seed(d.proposal, TrialDraws.MY_DRAWS))

    /** Their hand on screen after their draws by effects. */
    fun theirShown(d: VersusDeal): TrialDraws.Shown? =
        theirHand(d)?.let { TrialDraws.shown(it, theirRest(d), theirDraws, TrialDraws.seed(d.proposal, TrialDraws.THEIR_DRAWS)) }

    private fun seen(d: VersusDeal): SeenDraws {
        val mine = myShown(d)
        val theirs = theirShown(d)
        return SeenDraws(turnDraw = mine.draw, theirTurnDraw = theirs?.draw, drew = mine.drawn, theyDrew = theirs?.drawn.orEmpty())
    }
}
