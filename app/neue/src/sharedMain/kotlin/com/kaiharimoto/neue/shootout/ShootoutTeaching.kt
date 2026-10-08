package com.kaiharimoto.neue.shootout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrust
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.bench.TrustReport
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrialNote
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import com.kaiharimoto.mastertool.core.shootout.teach.Apprentice
import com.kaiharimoto.mastertool.core.shootout.teach.CalibrationSet
import com.kaiharimoto.mastertool.core.shootout.teach.ExampleBank
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeBrief
import com.kaiharimoto.mastertool.core.shootout.teach.JudgedPair
import com.kaiharimoto.mastertool.core.shootout.teach.Prediction
import com.kaiharimoto.mastertool.core.shootout.teach.Route
import com.kaiharimoto.mastertool.core.shootout.teach.Similarity
import com.kaiharimoto.mastertool.core.shootout.teach.Situation
import com.kaiharimoto.mastertool.core.shootout.teach.SoloHands
import com.kaiharimoto.mastertool.core.shootout.teach.TeachAction
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import com.kaiharimoto.mastertool.core.shootout.teach.TeachProgress
import com.kaiharimoto.mastertool.core.shootout.teach.TeachSteps
import com.kaiharimoto.mastertool.core.shootout.teach.Trust
import com.kaiharimoto.mastertool.core.shootout.teach.TrustRefresh
import com.kaiharimoto.mastertool.core.shootout.teach.TrustState
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.ai.Judged
import com.kaiharimoto.neue.ai.judgeHand
import com.kaiharimoto.neue.ai.judgeProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Teaching Ai on Shootout (Phase S stage 3, S.md §6½) — an owned part of [Shootouts]: the four ways to teach and the gate.
 *
 * - **Calibration set**: the person judges a fixed set chosen to cover the kinds of hand, blind; then Ai answers the same
 *   set blind, each hand from only what was answered before it — its first exam.
 * - **Apprentice**: the person judges as normal; Ai predicts each hand silently as it is shown, and after the person has
 *   answered may ask one question where it disagreed or was unsure, at most one every few trials.
 * - **Supervised**: Ai judges first with its reason; the person takes it with one key or corrects it, and the answer is
 *   marked seen.
 * - **Interview** is a conversation in the Ai panel (`startRubricInterview`), writing the rubric.
 *
 * And, when the person allows it, **Ai alone** on the kinds it has earned, a share of them back to the person blind.
 * Every answer of Ai's is kept as a trial of its own, with what it was shown.
 */
class ShootoutTeach internal constructor(private val s: Shootouts, private val h: NeueHolders, private val scope: CoroutineScope) {

    /**
     * The ways a session teaches. [help] is said with Ai's own name. The first is "Just me" (design review, 1.1.6): "Judge"
     * read as "Ai judges", the opposite of what it meant.
     */
    enum class Mode(val word: String?, val label: String, private val helpWith: (String) -> String) {
        JUDGE(null, "Just me", { ai -> "You judge; $ai is not asked." }),
        CALIBRATION(TeachModes.CALIBRATION, "Calibration set", { ai -> "A fixed set of hands covering every kind, judged by you blind; then $ai answers the same set blind as its first exam." }),
        APPRENTICE(TeachModes.APPRENTICE, "Apprentice", { ai -> "You judge as normal. $ai predicts each hand silently and, where it disagreed or was unsure, may ask one question after you answer." }),
        SUPERVISED(TeachModes.SUPERVISED, "Supervised", { ai -> "$ai judges first with its reason; take it with one key or correct it with another. You see its answer first, so your answers count a little less." }),
        ;

        fun help(ai: String): String = helpWith(ai)
    }

    /** How the next session teaches Ai. */
    var mode by mutableStateOf(Mode.JUDGE)

    // ---- the hand on screen ------------------------------------------------------------------------------------------

    /** Ai's answer to the hand on screen, shown in supervised mode. */
    var verdict by mutableStateOf<Judged.Verdict?>(null)
        private set

    /** Ai's answer marked on its own box of the scale, supervised (design review, 1.1.6); null elsewhere. */
    fun shownVerdict(enabled: Boolean): Answer? = verdict?.answer?.takeIf { enabled && mode == Mode.SUPERVISED }

    /** Ai is judging the hand on screen (supervised), or judging hands it has earned alone. */
    var judging by mutableStateOf(false)
        private set
    var routing by mutableStateOf(false)
        private set

    /** Why Ai could not judge the last hand, in words. */
    var trouble by mutableStateOf<String?>(null)

    /**
     * Ai's one question about the hand just answered (apprentice), or the person's own note being written. [shown] is that
     * hand as it was kept, drawn small in the card (kai's choice, 1.1.8), since the next hand is on screen by then.
     */
    class Ask(val trial: String, val question: String?, val aiSaid: String?, val youSaid: String?, val shown: StoredTrial? = null)

    var ask by mutableStateOf<Ask?>(null)
    var draft by mutableStateOf("")

    /** This session's: Ai's solo hands and the audits sent back. */
    var solo by mutableStateOf(0)
        private set
    var audits by mutableStateOf(0)
        private set

    /** The trial last answered by the person, for a note. */
    var lastAnswered by mutableStateOf<String?>(null)
        private set

    private class Pending(val id: String, val proposal: Proposal, val job: Deferred<Judged>, val route: Route = Route.PERSON)

    private var pending: Pending? = null
    private var sinceQuestion: Int? = null

    /** Ai's answers kept beside the person's since the trust state was last read ([TrustRefresh]). */
    private var sinceState = 0
    private var state: TrustState? = null
    private var random = Random(1)

    // ---- the calibration set and the exam -----------------------------------------------------------------------------

    var set by mutableStateOf<List<Proposal.Rate>?>(null)
        private set
    var setAt by mutableStateOf(0)
        private set
    private var setId: String? = null

    /** Ai's exam on a calibration set: its progress, then what it agreed. */
    class Exam(
        val done: Int, val of: Int, val agreed: Int, val failed: Int, val finished: Boolean, val byKind: List<Triple<String, Int, Int>>, val problem: String?,
        /** About how long the rest will take, from the pace so far; null before the first hand is answered. */
        val leftMs: Long? = null,
    )

    /** Ai is sitting its exam: nothing else begins meanwhile (design review, 1.1.6). */
    val examRunning: Boolean get() = exam?.finished == false

    var exam by mutableStateOf<Exam?>(null)
        private set

    // ---- the trust panel and the rubric -------------------------------------------------------------------------------

    var trust by mutableStateOf<TrustReport?>(null)
        private set
    var trustOpen by mutableStateOf(false)
    var reading by mutableStateOf(false)
        private set
    var rubricOpen by mutableStateOf(false)

    val settings: TrustSettings get() = s.log?.trusted ?: TrustSettings()

    /** Why Ai cannot judge hands now; null when it can. */
    val problem: String? get() = h.ai.judgeProblem()

    // ---- a session ---------------------------------------------------------------------------------------------------

    /** A session begins: the set chosen in calibration mode, the gate's state read. */
    internal suspend fun begin(r: ShootoutRun, now: Long) {
        // Ai off: no trace of it, and nothing asked of it.
        if (!h.ai.enabled) mode = Mode.JUDGE
        random = Random(now)
        solo = 0
        audits = 0
        sinceState = 0
        ask = null
        verdict = null
        trouble = null
        pending = null
        sinceQuestion = null
        lastAnswered = null
        state = readState(r)
        if (mode == Mode.CALIBRATION) {
            val stale = state?.kinds?.any { it.stale } == true
            val chosen = withContext(Dispatchers.Default) {
                val rnd = Random(now)
                if (stale) CalibrationSet.short(r.bench, rnd) { r.predict(it).sd } else CalibrationSet.pick(r.bench, CalibrationSet.SIZE, rnd) { r.predict(it).sd }
            }
            set = chosen
            setAt = 0
            setId = "cal" + now.toString(36)
        } else {
            set = null
            setId = null
        }
    }

    /** The calibration set's next hand, or null outside one; [finished] when the set is done. */
    internal fun fromSet(): Proposal.Rate? = set?.getOrNull(setAt)
    internal val setFinished: Boolean get() = set != null && setAt >= set!!.size

    /** The session id the person's answers carry: the set's, in calibration mode. */
    internal fun sessionId(fallback: String?): String? = setId ?: fallback

    /** The mode word the person's answer to [p] is kept with. */
    internal fun modeFor(p: Proposal): String? = when {
        pending?.proposal == p && pending?.route == Route.AUDIT -> TeachModes.AUDIT
        else -> mode.word ?: if (pending?.proposal == p) TeachModes.APPRENTICE else null
    }

    /** Whether the person saw Ai's answer to [p] before giving theirs. */
    internal fun sawAi(p: Proposal): Boolean = mode == Mode.SUPERVISED && verdict != null && pending?.proposal == p

    /**
     * A hand about to be shown: in apprentice and supervised mode Ai starts judging it at once, from what was known before
     * the person answers. [id] is the id the person's trial will have.
     */
    internal fun shown(r: ShootoutRun, p: Proposal, id: String) {
        verdict = null
        trouble = null
        if (pending?.proposal == p) {
            // Already asked while routing: in supervised mode its answer is shown now.
            if (mode == Mode.SUPERVISED) watch(pending!!)
            return
        }
        pending = null
        if (mode != Mode.APPRENTICE && mode != Mode.SUPERVISED) return
        if (problem != null) {
            trouble = problem
            return
        }
        val brief = briefFor(r, p, asOf = Long.MAX_VALUE, exclude = emptySet(), prediction = r.predict(p))
        val next = Pending(id, p, scope.async { h.ai.judgeHand(brief) })
        pending = next
        if (mode == Mode.SUPERVISED) watch(next)
    }

    private fun watch(p: Pending) {
        judging = true
        scope.launch {
            val got = p.job.await()
            if (pending !== p) return@launch
            judging = false
            when (got) {
                is Judged.Verdict -> verdict = got
                is Judged.Failed -> trouble = got.why
            }
        }
    }

    /** Whether Ai is asked about [p] before the person: it may judge alone, and [p] is of a kind it has earned. */
    internal fun considers(r: ShootoutRun, p: Proposal): Boolean {
        if (!settings.solo || mode == Mode.CALIBRATION || mode == Mode.SUPERVISED || problem != null) return false
        if (!SoloHands.offered(p)) return false
        return state?.kind(r.bench.kindOf(p).key)?.open == true
    }

    /**
     * Ai may take [p] alone (S.md §6½ "The gate"): when the person has let it, the kind is open, and — once asked — it is
     * sure; a share of those go back to the person blind (an audit). True when Ai took it, so another hand is chosen.
     */
    internal suspend fun takesAlone(r: ShootoutRun, p: Proposal, id: String): Boolean {
        if (!considers(r, p)) return false
        val st = state ?: return false
        val kind = r.bench.kindOf(p).key
        routing = true
        val brief = briefFor(r, p, asOf = Long.MAX_VALUE, exclude = emptySet(), prediction = r.predict(p))
        val job = scope.async { h.ai.judgeHand(brief) }
        val got = job.await()
        routing = false
        if (got !is Judged.Verdict) {
            trouble = (got as? Judged.Failed)?.why
            return false
        }
        return when (Trust.route(st, kind, got.verdict.sure, random)) {
            Route.SOLO -> {
                keepAi(r, p, got.verdict, of = null, mode = TeachModes.SOLO, id = "$id~solo${solo}")
                solo++
                true
            }
            Route.AUDIT -> {
                pending = Pending(id, p, job, Route.AUDIT)
                audits++
                false
            }
            Route.PERSON -> {
                pending = Pending(id, p, job)
                false
            }
        }
    }

    /**
     * The person answered [p] as [kept]: Ai's answer to it is kept beside it, now or when it lands; in apprentice mode its
     * one question is offered where it disagreed or was unsure; a calibration set moves on, and when it is done, the exam.
     */
    internal fun answered(r: ShootoutRun, p: Proposal, kept: StoredTrial) {
        lastAnswered = kept.id
        verdict = null
        judging = false
        if (set != null && fromSet() == p) setAt++
        sinceQuestion = sinceQuestion?.plus(1)
        val mine = pending?.takeIf { it.proposal == p }
        pending = null
        if (mine != null) {
            scope.launch {
                val got = mine.job.await() as? Judged.Verdict ?: return@launch
                val m = when (mine.route) {
                    Route.AUDIT -> TeachModes.AUDIT
                    else -> mode.word?.takeIf { it != TeachModes.CALIBRATION } ?: TeachModes.APPRENTICE
                }
                keepAi(r, p, got.verdict, of = kept.id, mode = m, id = "${kept.id}~ai")
                sinceState++
                if (TrustRefresh.due(m == TeachModes.AUDIT, sinceState)) {
                    sinceState = 0
                    state = readState(r)
                }
                if (mode == Mode.APPRENTICE && Apprentice.asks(got.verdict, kept, sinceQuestion) && ask == null) {
                    sinceQuestion = 0
                    ask = Ask(kept.id, got.verdict.question, words(got.answer, got.prefersLeft, r.bench), words(Answer.entries.firstOrNull { it.name == kept.answer }, kept.prefer?.let { it == StoredTrial.LEFT }, r.bench), shown = kept)
                    draft = ""
                }
            }
        }
        if (setFinished) startExam(r)
    }

    /** Takes Ai's answer as the person's (supervised: one key). */
    fun accept() {
        val v = verdict ?: return
        when {
            v.answer != null -> s.answer(v.answer)
            v.prefersLeft != null -> s.prefer(v.prefersLeft)
        }
    }

    /** The person's note on [Ask.trial]: an answer to Ai's question, or their own. */
    fun sendNote() {
        val a = ask ?: return
        val text = draft.trim()
        ask = null
        draft = ""
        if (text.isEmpty()) return
        s.addNote(TrialNote(a.trial, text, h.deps.now(), a.question))
    }

    /** A note of the person's own on the hand just answered. */
    fun noteOnLast() {
        val id = lastAnswered ?: return
        ask = Ask(id, null, null, null, shown = s.log?.trials?.lastOrNull { it.id == id })
        draft = ""
    }

    fun skipAsk() {
        ask = null
        draft = ""
    }

    /** Another deck or target: what was read of the last one goes. */
    internal fun forget() {
        end()
        trust = null
        trustOpen = false
        set = null
        setId = null
        state = null
        ask = null
        progress = null
        if (exam?.finished != false) exam = null
    }

    /** The session is over: what Ai was still judging is let go. */
    internal fun end() {
        pending = null
        verdict = null
        judging = false
        routing = false
    }

    // ---- the exam -----------------------------------------------------------------------------------------------------

    /**
     * Ai's exam on the calibration set (S.md §6½): each hand of the set judged blind, from only what was answered before
     * the person answered it — the examples and the rubric — and the model fitted on the trials kept before the set began.
     */
    fun startExam(r: ShootoutRun? = s.runOrNew()) {
        val run = r ?: return
        val id = setId ?: lastSet(run)
        set = null
        setId = null
        if (id == null) return
        val trials = run.log.trials.filter { it.judge == StoredTrial.PERSON && it.mode == TeachModes.CALIBRATION && it.session == id }
        val answered = run.log.trials.filter { it.judge == StoredTrial.AI && it.mode == TeachModes.EXAM }.mapNotNull { it.of }.toSet()
        val todo = trials.filter { it.id !in answered }
        problem?.let { why ->
            exam = Exam(0, trials.size, 0, 0, true, emptyList(), why)
            s.showExam()
            return
        }
        exam = Exam(0, todo.size, 0, 0, todo.isEmpty(), emptyList(), null)
        s.showExam()
        scope.launch {
            val start = trials.minOfOrNull { it.at } ?: 0L
            val before = withContext(Dispatchers.Default) { ShootoutRun(run.bench, run.log.copy(trials = run.log.trials.filter { it.at < start }), seed = 3) }
            var failed = 0
            val began = h.deps.now()
            todo.forEachIndexed { i, t ->
                val p = run.bench.proposal(t) ?: return@forEachIndexed
                val brief = briefFor(run, p, asOf = t.at, exclude = setOf(t.id), prediction = before.predict(p))
                when (val got = h.ai.judgeHand(brief)) {
                    is Judged.Verdict -> keepAi(run, p, got.verdict, of = t.id, mode = TeachModes.EXAM, id = "${t.id}~exam")
                    is Judged.Failed -> {
                        failed++
                        trouble = got.why
                    }
                }
                // The pace so far, for "about a minute left": read at each answer, no clock ticking.
                val each = (h.deps.now() - began) / (i + 1)
                exam = Exam(i + 1, todo.size, 0, failed, false, emptyList(), null, leftMs = each * (todo.size - i - 1))
            }
            exam = examResult(run, id, failed)
            state = readState(run)
        }
    }

    /** What Ai's exam on set [id] came to: held-out pairs agreeing, in all and per kind. */
    private fun examResult(r: ShootoutRun, id: String, failed: Int): Exam {
        val ids = r.log.trials.filter { it.session == id && it.judge == StoredTrial.PERSON }.map { it.id }.toSet()
        val pairs = JudgedPair.all(r.log.trials) { r.bench.kindOf(it)?.key }.filter { it.person.id in ids && it.aiTrial?.mode == TeachModes.EXAM }
        val byKind = pairs.groupBy { it.kind ?: "?" }.map { (k, ps) -> Triple(k, ps.count { it.agrees }, ps.size) }.sortedByDescending { it.third }
        return Exam(pairs.size, pairs.size, pairs.count { it.agrees }, failed, true, byKind, null)
    }

    /** The newest calibration set kept that Ai has not answered whole; null when none. */
    fun lastSet(r: ShootoutRun?): String? {
        val trials = (r?.log ?: s.log)?.trials ?: return null
        val answered = trials.filter { it.judge == StoredTrial.AI && it.mode == TeachModes.EXAM }.mapNotNull { it.of }.toSet()
        return trials.filter { it.mode == TeachModes.CALIBRATION && it.judge == StoredTrial.PERSON && it.id !in answered }
            .maxByOrNull { it.at }?.session
    }

    // ---- the steps (kai's choice, 1.1.8) -----------------------------------------------------------------------------

    /** The four steps of teaching on the matchup chosen, read off the frame thread as its trials change; null until read. */
    var progress by mutableStateOf<TeachProgress?>(null)
        private set

    /** Reads [progress] afresh from the trials kept. */
    fun readProgress() {
        val b = s.bench ?: return
        val l = s.log ?: return
        val name = h.ai.name
        scope.launch {
            val read = withContext(Dispatchers.Default) { TeachSteps.of(b, l, name) }
            if (s.log === l) progress = read
        }
    }

    /** A step moved on: a session begun in its mode, Ai's exam, or the trust panel. */
    fun act(action: TeachAction) {
        when (action) {
            TeachAction.CALIBRATION -> begin(Mode.CALIBRATION)
            TeachAction.APPRENTICE -> begin(Mode.APPRENTICE)
            TeachAction.SUPERVISED -> begin(Mode.SUPERVISED)
            TeachAction.EXAM -> startExam()
            TeachAction.TRUST -> openTrust()
        }
    }

    /** A session begun in [m]: the mode is chosen for the session it begins, and Just me is a session without one. */
    fun begin(m: Mode) {
        if (s.running || examRunning) return
        mode = if (h.ai.enabled) m else Mode.JUDGE
        if (s.view == Shootouts.View.EXAM) s.view = Shootouts.View.SETUP
        s.start()
    }

    // ---- the trust panel ----------------------------------------------------------------------------------------------

    fun openTrust() {
        trustOpen = true
        refreshTrust()
    }

    fun refreshTrust() {
        val run = s.runOrNew() ?: return
        reading = true
        scope.launch {
            try {
                trust = withContext(Dispatchers.Default) { ShootoutTrust.read(run) }
            } catch (e: Exception) {
                h.neue.note = Note("The trust panel could not be read: ${e.message ?: e::class.simpleName}")
            } finally {
                reading = false
            }
        }
    }

    /** The gate's settings changed: kept with the matchup's trials. */
    fun setSettings(next: TrustSettings) {
        s.setTrust(next)
        state = state?.let { st -> s.runOrNew()?.let { readState(it) } ?: st }
        if (trustOpen) refreshTrust()
    }

    // ---- the studio's pictures ----------------------------------------------------------------------------------------

    /**
     * The studio's picture of teaching (`--shootout-teach=`): the state each screen shows, set by hand from a run already
     * answered — never in the app. [r] holds the demo's answers; [p] is the hand on screen.
     */
    internal fun demo(r: ShootoutRun, p: Proposal?, what: String) {
        state = readState(r)
        when (what) {
            "supervised" -> {
                mode = Mode.SUPERVISED
                val rate = p as? Proposal.Rate
                val a = rate?.let { r.predict(it).likeliest } ?: Answer.LEAN_WIN
                verdict = Judged.Verdict(
                    AiVerdict(answer = a.name, sure = 0.82, why = "A starter with a searcher behind it, and only one of their hand traps to play through."),
                    a, null,
                )
            }
            "judging" -> {
                mode = Mode.SUPERVISED
                judging = true
            }
            "question" -> {
                mode = Mode.APPRENTICE
                lastAnswered = r.log.trials.lastOrNull { it.judge == StoredTrial.PERSON }?.id
                val shown = r.log.trials.lastOrNull { it.id == lastAnswered }
                ask = Ask(lastAnswered ?: "demo", "What made it a win — would you still call it one if they held a second hand trap?", "Lean loss", "Lean win", shown)
                draft = "Their Ash hits the searcher, but the extender still"
                solo = 0
            }
            "calibration" -> {
                mode = Mode.CALIBRATION
                set = List(CalibrationSet.SIZE) { p as? Proposal.Rate ?: return }
                setAt = 11
                setId = "cal-demo"
            }
            "solo" -> {
                mode = Mode.APPRENTICE
                solo = 14
                audits = 3
                lastAnswered = r.log.trials.lastOrNull()?.id
            }
            "exam" -> exam = examResult(r, r.log.trials.firstOrNull { it.mode == TeachModes.CALIBRATION }?.session ?: "", failed = 0)
            "exam-running" -> exam = Exam(13, 32, 0, 0, false, emptyList(), null, leftMs = 95_000)
            "trust" -> {
                trust = ShootoutTrust.read(r)
                trustOpen = true
            }
            "rubric" -> rubricOpen = true
            "early" -> Unit
            else -> mode = Mode.APPRENTICE
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private fun readState(r: ShootoutRun): TrustState =
        Trust.read(r.log.trials, r.log.trusted, r.bench.alone, r.bench.print) { r.bench.kindOf(it)?.key }

    /** What Ai is handed for [p]: the person's examples answered before [asOf], never [exclude]; the rubric; the prediction. */
    private fun briefFor(r: ShootoutRun, p: Proposal, asOf: Long, exclude: Set<String>, prediction: Prediction?): JudgeBrief {
        val bench = r.bench
        val similarity = Similarity(bench::roleOf, bench.kinds.interaction)
        val hand = when (p) {
            is Proposal.Rate -> bench.ids(p.hand)
            is Proposal.Compare -> bench.ids(p.left)
        }
        val examples = ExampleBank.nearest(Situation(p.stratum, hand, p.opponent?.let(bench::opponentIds)), r.log.trials, similarity, r.log.notes, asOf = asOf, exclude = exclude)
        return JudgeBrief.of(
            bench, p, prediction, s.rubricText, examples, s.deckName,
            name = { s.card(it)?.name ?: "#$it" },
            asked = h.deps.now(),
            fitted = r.fitted,
            cardText = { s.card(it)?.description },
        )
    }

    private suspend fun keepAi(r: ShootoutRun, p: Proposal, verdict: AiVerdict, of: String?, mode: String, id: String) {
        val trial = r.bench.aiAnswer(p, verdict, id, h.deps.now(), of, mode, s.sessionFor())
        s.fitting.withLock { withContext(Dispatchers.Default) { r.record(trial) } }
        s.kept(r)
    }

    private fun words(a: Answer?, left: Boolean?, bench: Bench): String? = when {
        a != null -> ShootoutWords.label(a, bench.alone)
        left != null -> if (left) "the left hand" else "the right hand"
        else -> null
    }
}
