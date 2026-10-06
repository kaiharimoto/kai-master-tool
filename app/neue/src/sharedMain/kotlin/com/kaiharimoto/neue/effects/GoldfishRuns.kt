package com.kaiharimoto.neue.effects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardPlace
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Goldfish
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishBrowse
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishProgress
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishReplay
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishResult
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishSetup
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandPick
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.NotComputable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TargetDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** The Effects app's two tabs: the library of written effects, and the goldfish on the open deck. */
enum class EffectsTab(val words: String) { LIBRARY("Library"), GOLDFISH("Goldfish") }

/**
 * The goldfish in the Effects app (Phase D step 4, agent (c); `docs/phases/D.md` §5): what the pane is set to — the end
 * board chosen, going first or second, how many hands from which seed — the run in progress (off the frame thread, its
 * progress posted here, cancelled by [stop]), the result on screen and which of its hands are listed. A part of [Effects]
 * for the app's lifetime, so a run carries on while its window is closed.
 *
 * Nothing here writes a target or a result by itself: the person keeps a target from the editor and a result with Keep
 * (`Effects.putTarget`, `Effects.keepResult`); a hand opens as a replay on the Duel page that is not saved unless kept.
 */
class GoldfishRuns(private val effects: Effects) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The tab the Effects app shows. */
    var tab by mutableStateOf(EffectsTab.LIBRARY)

    /** The end board chosen to run, by id (of the open deck's file). */
    var targetId by mutableStateOf<String?>(null)

    /** Going first (5 cards, no draw) or second (6). */
    var first by mutableStateOf(true)

    /** How many hands, as typed; null for the device's default (2,000 on the desk, 500 on a phone). */
    var handsText by mutableStateOf<String?>(null)

    /** The seed, as typed. */
    var seedText by mutableStateOf("1")

    /** The end board being made or edited, or null. */
    var editing by mutableStateOf<Editing?>(null)

    /** The run in progress, or null. */
    var running by mutableStateOf<GoldfishSetup?>(null)
        private set

    /** How far the run has got. */
    var progress by mutableStateOf<GoldfishProgress?>(null)
        private set

    /** The result on screen: the run just finished, or a kept one opened. */
    var shown by mutableStateOf<Shown?>(null)

    /** Why the last run could not start: its cards, for Write these. */
    var refusal by mutableStateOf<NotComputable?>(null)

    /** A line to the person about the last run: stopped, or failed. */
    var said by mutableStateOf<String?>(null)

    /** Which hands of [shown] are listed, or null. */
    var picked by mutableStateOf<HandPick?>(null)

    /** How many of them are listed: more come a page at a time. */
    var listed by mutableStateOf(PAGE)

    /** Moves on whenever a new result is put on screen: the pane brings it into view. */
    var reveal by mutableStateOf(0)
        private set

    /** The hand being made into a replay (its index), or null. */
    var opening by mutableStateOf<Int?>(null)

    private var job: Job? = null

    /** An end board in the editor: [id] when it edits a kept one, [by] whose it was. */
    data class Editing(val id: String?, val draft: TargetDraft, val by: String, val picking: BoardPlace? = null)

    /**
     * A result on screen: [setup] makes its hands again (null when they cannot be, with [why]: a kept result whose deck
     * changed); [kept] once it is in the deck's file; [deckId] the deck it is for.
     */
    data class Shown(val result: GoldfishResult, val setup: GoldfishSetup?, val why: String?, val kept: Boolean, val deckId: String?)

    /** The seed typed, or 1. */
    val seed: Long get() = GoldfishBrowse.seedOf(seedText) ?: 1L

    /** The hands typed, or [default]. */
    fun hands(default: Int): Int = handsText?.let(GoldfishBrowse::handsOf) ?: default

    /** A new seed: the next run deals other hands. */
    fun reroll(now: Long = System.nanoTime()) {
        seedText = ((now xor (now ushr 29)) and 0x7fffffff).toString()
    }

    /**
     * Runs [setup] with [kit] (read on the main thread) on [workers] workers, off the frame thread; the progress comes here a
     * few times a second, and the result is [shown] when it is done. A run that is not computable starts nothing: its
     * [refusal] names the cards, for Write these. Returns the run's job, or null when it did not start.
     */
    fun start(setup: GoldfishSetup, kit: GoldfishKit, workers: Int = Goldfish.workers(), now: Long = System.currentTimeMillis()): Job? {
        if (running != null) return null
        said = null
        picked = null
        refusal = Goldfish.refusal(setup, kit)
        if (refusal != null) return null
        running = setup
        progress = GoldfishProgress(0, setup.hands.coerceIn(1, Goldfish.MOST_HANDS), 0, 0)
        val posted = AtomicLong(0L)
        val j = scope.launch {
            try {
                val r = withContext(Dispatchers.Default) {
                    Goldfish.run(setup, kit, workers, progress = { p ->
                        // A few posts a second from the workers' threads: the last always.
                        val t = System.nanoTime() / 1_000_000
                        val last = posted.get()
                        if (p.done == p.total || (t - last >= POST_MS && posted.compareAndSet(last, t))) {
                            scope.launch { if (running === setup && p.done >= (progress?.done ?: 0)) progress = p }
                        }
                    }, now = now)
                }
                if (running === setup) {
                    shown = Shown(r, setup, null, kept = false, deckId = setup.deck.id)
                    listed = PAGE
                    reveal++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                said = "The run failed: ${e.message ?: e::class.simpleName}"
            } finally {
                if (running === setup) {
                    running = null
                    job = null
                }
            }
        }
        job = j
        return j
    }

    /** Stops the run: what it found so far is let go, and said. */
    fun stop() {
        val j = job ?: return
        val p = progress
        job = null
        running = null
        j.cancel()
        said = "Stopped after ${GoldfishWords.count(p?.done ?: 0)} of ${GoldfishWords.count(p?.total ?: 0)} hands: nothing was kept. Run it again to see it through."
    }

    /** Whether a run is going. */
    val busy: Boolean get() = running != null

    /** A kept result opened: its hands open on [deck] (the deck as it is now) only while it is the deck they were dealt from. */
    fun show(r: GoldfishResult, deck: GoldfishDeck?) {
        val (setup, why) = if (deck == null) null to "Open its deck to open its hands." else GoldfishBrowse.setup(r, deck)
        shown = Shown(r, setup, why, kept = true, deckId = r.deckId ?: deck?.id)
        picked = null
        listed = PAGE
        reveal++
    }

    /** The result on screen kept in its deck's file. */
    suspend fun keep(): Boolean {
        val s = shown ?: return false
        val deck = s.deckId ?: return false
        if (s.kept) return true
        effects.keepResult(deck, s.result)
        if (shown === s) shown = s.copy(kept = true)
        return true
    }

    /** [pick]'s hands listed under the result (again: closed). */
    fun pick(pick: HandPick?) {
        picked = if (picked == pick) null else pick
        listed = PAGE
    }

    /** Hand [k] of the result on screen made again as a replay, off the frame thread; null when it cannot be. */
    suspend fun replay(k: Int, kit: GoldfishKit): GoldfishReplay.Replay? {
        val setup = shown?.setup ?: return null
        opening = k
        try {
            return withContext(Dispatchers.Default) { GoldfishReplay.of(setup, kit, k) }
        } finally {
            if (opening == k) opening = null
        }
    }

    /**
     * Hand [k] of the result on screen made again as a replay (off the frame thread), then handed to [then] on the main
     * thread with the result it is from; one hand at a time. Null when it cannot be opened.
     */
    fun open(k: Int, kit: GoldfishKit, then: (GoldfishResult, GoldfishReplay.Replay) -> Unit): Job? {
        val s = shown ?: return null
        if (s.setup == null || opening != null) return null
        return scope.launch {
            val replay = try {
                replay(k, kit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                said = "Hand ${k + 1} could not be made again: ${e.message ?: e::class.simpleName}"
                null
            }
            if (replay != null) then(s.result, replay)
        }
    }

    /** A target let go of: the pane forgets it was chosen. */
    fun forgetTarget(id: String) {
        if (targetId == id) targetId = null
        if (editing?.id == id) editing = null
    }

    companion object {
        /** Hands listed at a time. */
        const val PAGE = 24

        /** At most this often the progress is posted, in ms. */
        const val POST_MS = 120L
    }
}
