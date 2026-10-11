package com.kaiharimoto.neue.shootout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeAblation
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeAblation.Variant
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.Judged
import com.kaiharimoto.neue.ai.judgeHand
import com.kaiharimoto.neue.ai.judgeProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Is it better? (Phase S, S4.4; `docs/phases/S.md` §6¾) — a part of [Shootouts]: Ai judges the person's newest held-out blind
 * hands with everything it is handed and without each piece ([JudgeAblation.Variant]), every answer held to the person's,
 * on the deck and target on the page. Run only when the person presses Run, shown in Test scores, kept apart from the trials
 * (`<deck>/<target>.ablation.json`): an answer here moves no rating and no trust.
 */
class ShootoutAblation internal constructor(private val s: Shootouts, private val h: NeueHolders) {

    /** Every run for the deck and target on the page; null before it is read. */
    var log by mutableStateOf<JudgeAblation.Log?>(null)
        private set

    /** A run under way: answers given of the run's whole, and the trouble of the last that failed. */
    var done by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var trouble by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    val running: Boolean get() = done != null

    private fun target(): Pair<String, String?>? {
        val deck = s.deckId ?: h.builder.deckId ?: return null
        val b = s.bench ?: return null
        return deck to s.opponentId.takeIf { !b.alone }
    }

    /** The newest run's scores, everything first; empty with none. */
    fun latest(): List<JudgeAblation.Score> = log?.runs?.lastOrNull()?.let(JudgeAblation::score).orEmpty()

    /** Why a run cannot begin now, in words; null when it can. */
    fun problem(): String? {
        h.ai.judgeProblem()?.let { return it }
        if (target() == null) return "Open Shootout on a saved deck."
        val trials = s.log?.trials.orEmpty()
        val short = JudgeAblation.short(trials)
        if (short > 0) return "It needs ${ShootoutWords.hands(short)} more of your blind answers on this deck and target first."
        return null
    }

    /** About what a run costs, in tokens: every held-out hand, judged once per variant. */
    fun tokens(): Int = JudgeAblation.HANDS * Variant.entries.size * JudgeAblation.TOKENS_EACH

    /** The runs kept for the deck and target on the page, read from disk. */
    fun read() {
        val (deck, opponent) = target() ?: run { log = null; return }
        s.scope.launch {
            log = withContext(Dispatchers.IO) { File(s.dataDir, ShootoutPaths.ablation(deck, opponent)).takeIf { it.isFile }?.readText() }
                .let(JudgeAblation::decode) ?: JudgeAblation.Log(deck = deck, opponent = opponent)
        }
    }

    /**
     * A run: the person's [JudgeAblation.HANDS] newest blind answers held out, the model fitted on what came before them,
     * each hand judged by Ai once per variant from only what was answered before it. Kept as it goes, so a stopped run
     * keeps what it has.
     */
    fun run() {
        if (running || problem() != null) return
        val r = s.runOrNew() ?: return
        val (deck, opponent) = target() ?: return
        val held = JudgeAblation.pick(r.log.trials) ?: return
        val connection = h.ai.prefs.connection
        val id = "a" + h.deps.now().toString(36)
        trouble = null
        done = 0 to held.size * Variant.entries.size
        job = s.scope.launch {
            try {
                val start = held.minOf { it.at }
                val before = withContext(Dispatchers.Default) { ShootoutRun(r.bench, r.log.copy(trials = r.log.trials.filter { it.at < start }), seed = 3) }
                var current = JudgeAblation.Run(id, h.deps.now(), connection?.model, connection?.label?.ifBlank { connection.provider }, held.map { it.id }, deckPrint = r.bench.deckPrint)
                var count = 0
                held.forEach { t ->
                    val p = r.bench.proposal(t) ?: return@forEach
                    Variant.entries.forEach { v ->
                        val brief = s.teach.briefFor(
                            r, p, asOf = t.at, exclude = setOf(t.id),
                            prediction = if (v == Variant.NO_PREDICTION) null else before.predict(p),
                            rubric = v != Variant.NO_RUBRIC, examples = v != Variant.NO_EXAMPLES,
                        )
                        current = when (val got = h.ai.judgeHand(brief)) {
                            is Judged.Verdict -> current.copy(said = current.said + JudgeAblation.said(t, v, got.verdict.answer, got.verdict.prefer, got.verdict.sure))
                            is Judged.Failed -> {
                                trouble = got.why
                                current.copy(failed = current.failed + 1)
                            }
                        }
                        count++
                        done = count to (done?.second ?: count)
                    }
                    save(deck, opponent, current)
                }
            } finally {
                done = null
                job = null
            }
        }
    }

    /**
     * The studio's run (`--shootout-ablation=demo`): the demo's held-out hands answered as a model might — agreeing a little
     * less without each piece — and kept as a run would be. Never in the app.
     */
    fun studioRun() {
        val (deck, opponent) = target() ?: return
        val held = JudgeAblation.pick(s.log?.trials.orEmpty()) ?: return
        val misses = mapOf(Variant.FULL to 4, Variant.NO_RUBRIC to 7, Variant.NO_EXAMPLES to 9, Variant.NO_PREDICTION to 5)
        val said = held.flatMapIndexed { i, t ->
            Variant.entries.map { v ->
                val wrong = (i * 7 + v.ordinal * 3) % held.size < misses.getValue(v)
                val far = when (t.answer) { "CLEAR_WIN", "LEAN_WIN" -> "LEAN_LOSS"; else -> "LEAN_WIN" }
                JudgeAblation.said(t, v, if (wrong) far else t.answer, t.prefer?.let { if (wrong) (if (it == "left") "right" else "left") else it }, if (wrong) 0.7 else 0.8)
            }
        }
        val run = JudgeAblation.Run("studio", 1_760_000_000_000L, "claude-opus", "Anthropic", held.map { it.id }, said, deckPrint = s.bench?.deckPrint)
        val next = JudgeAblation.Log(deck = deck, opponent = opponent, runs = listOf(run))
        File(s.dataDir, ShootoutPaths.ablation(deck, opponent)).also { it.parentFile?.mkdirs() }.writeText(JudgeAblation.encode(next))
        log = next
    }

    /** The run under way stopped: what it has is kept. */
    fun stop() {
        job?.cancel()
    }

    private suspend fun save(deck: String, opponent: String?, run: JudgeAblation.Run) {
        val base = log ?: JudgeAblation.Log(deck = deck, opponent = opponent)
        val next = base.copy(runs = base.runs.filterNot { it.id == run.id } + run)
        withContext(Dispatchers.IO) {
            val f = File(s.dataDir, ShootoutPaths.ablation(deck, opponent))
            f.parentFile?.mkdirs()
            f.writeText(JudgeAblation.encode(next))
        }
        log = next
    }
}
