package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrustWords
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial

/**
 * When Shootout's teaching controls appear (design review 1.1.6, finding 2; kai's choice, 1.1.8): a first visit is for
 * rating hands, so Setup shows only that, with one line saying Ai can be taught later. From [HANDS] hands judged by the
 * person **for the deck** the controls appear; someone who has already taught Ai always sees them.
 *
 * **Per deck, not per matchup**: the threshold is about the person knowing the page and its scale, which carries from the
 * deck alone to every opponent; and a calibration set is how a new matchup is best begun, so hiding the steps again on
 * each new opponent would hide the first one where it is most use. Teaching itself stays per matchup ([TeachSteps]).
 */
object TeachGate {
    /** The person's hands judged for a deck before teaching Ai is offered: about one ten-minute session. */
    const val HANDS = 30

    /** What a deck's store says about teaching: the person's hands judged, and whether Ai has been taught at all. */
    data class Tally(val hands: Int = 0, val taught: Boolean = false) {
        operator fun plus(other: Tally): Tally = Tally(hands + other.hands, taught || other.taught)
    }

    /** One matchup's file, read. */
    fun tally(log: ShootoutLog): Tally = Tally(log.trials.count { it.judge == StoredTrial.PERSON }, taught(log))

    /**
     * Whether Ai has been taught on this matchup: any answer of Ai's, any hand judged in a teaching mode (a calibration
     * set, apprentice, supervised, an audit), a verdict an older build kept on the person's trial, or the gate's settings
     * chosen. A note alone is the person's annotation, not teaching.
     */
    fun taught(log: ShootoutLog): Boolean =
        log.trust != null || log.trials.any { it.judge == StoredTrial.AI || it.mode != null || it.ai != null }

    /** Whether the teaching controls show: [HANDS] judged for the deck, Ai taught on any of its matchups, or a rubric kept. */
    fun shown(tally: Tally, rubric: Boolean = false): Boolean = tally.taught || rubric || tally.hands >= HANDS

    /** Hands still to judge before the controls appear. */
    fun toGo(tally: Tally): Int = (HANDS - tally.hands).coerceAtLeast(0)

    /**
     * The one line under Setup while the controls are hidden: kai's words on a first visit, the hands left once some are
     * judged (a second session would make "after your first session" untrue).
     */
    fun line(tally: Tally, ai: String): String {
        val left = toGo(tally)
        return if (tally.hands == 0) "After your first session you can teach $ai to judge with you."
        else "${ShootoutWords.hands(left).replaceFirstChar { it.uppercase() }} more and you can teach $ai to judge with you."
    }
}

/** Where one step of teaching stands. */
enum class StepState { NOT_STARTED, IN_PROGRESS, DONE }

/** What moving a step on does: a session in a mode, Ai's exam, or the trust panel (where judging alone is switched on). */
enum class TeachAction { CALIBRATION, EXAM, APPRENTICE, SUPERVISED, TRUST }

/**
 * One step of teaching: its number (`01`–`04`), name, state — [status] is the state's word ("Done", "In progress", "Not
 * started"; for judging alone "On", "Off", "Allowed", "Not yet") and [words] the counts behind it; [action] and [label]
 * are what moves it on: for the next step, the page's one primary button.
 */
class TeachStep(
    val number: Int,
    val name: String,
    val state: StepState,
    val status: String,
    val words: String,
    val action: TeachAction,
    val label: String,
)

/** The four steps, in order; [next] is the first not done, and its action the page's one primary button. */
class TeachProgress(val steps: List<TeachStep>) {
    val next: TeachStep? get() = steps.firstOrNull { it.state != StepState.DONE }
    fun step(n: Int): TeachStep = steps.first { it.number == n }
}

/**
 * Teaching as numbered steps (design review 1.1.6, finding 3; kai's choice, 1.1.8): **1 Calibration set → 2 Apprentice →
 * 3 Supervised → 4 Ai judges alone**, each with its state, read from one matchup's trials and its [TrustState] alone so
 * nothing can drift from them.
 *
 * - **Calibration set** is done once Ai has sat the exam on a set whole, on today's decks; a set judged but not examined
 *   is in progress (its action, the exam); after a deck change a short set earns the kinds back.
 * - **Apprentice** is done at [APPRENTICE_HANDS] blind hands: about a session beside the set, enough held-out pairs for
 *   the trust panel to read the common kinds.
 * - **Supervised** is done at [SUPERVISED_HANDS] hands: enough seen answers to measure how far seeing Ai's moves the
 *   person ([SeenDrift]).
 * - **Ai judges alone** is done when a kind is open and the person has let it judge alone; with kinds earned and the
 *   switch off, its action is the trust panel; with none earned, more apprentice hands earn them.
 */
object TeachSteps {
    const val APPRENTICE_HANDS = 40
    const val SUPERVISED_HANDS = 20

    /** The steps for [log] on [bench], Ai named [ai]. */
    fun of(bench: Bench, log: ShootoutLog, ai: String): TeachProgress {
        val state = Trust.read(log.trials, log.trusted, bench.alone, bench.print) { bench.kindOf(it)?.key }
        return read(log, state, bench.print, ai)
    }

    /** The steps from the trials, notes and gate's [state] of one matchup; [print] the decks as they are today. */
    fun read(log: ShootoutLog, state: TrustState, print: String?, ai: String): TeachProgress =
        TeachProgress(listOf(calibration(log.trials, print, ai), apprentice(log, ai), supervised(log.trials, ai), alone(state, ai)))

    /** One calibration set: the person's hands in it, Ai's exam answers to them, and the agreements. */
    class CalibrationRun(val session: String, val judged: Int, val examined: Int, val agreed: Int, val at: Long, val print: String?) {
        val examinedWhole: Boolean get() = judged > 0 && examined >= judged
    }

    /** Every calibration set kept, oldest first. */
    fun calibrationRuns(trials: List<StoredTrial>): List<CalibrationRun> {
        val exam = trials.filter { it.judge == StoredTrial.AI && it.mode == TeachModes.EXAM && it.of != null }.associateBy { it.of!! }
        return trials.filter { it.judge == StoredTrial.PERSON && it.mode == TeachModes.CALIBRATION }
            .groupBy { it.session ?: "" }
            .map { (session, set) ->
                val answers = set.mapNotNull { p -> exam[p.id]?.let { p to it } }
                CalibrationRun(
                    session = session,
                    judged = set.size,
                    examined = answers.size,
                    agreed = answers.count { (p, a) -> Agreement.agrees(p, a.answer ?: a.ai?.answer, a.prefer ?: a.ai?.prefer) == true },
                    at = set.maxOf { it.at },
                    print = answers.firstNotNullOfOrNull { it.second.ai?.print },
                )
            }
            .sortedBy { it.at }
    }

    private fun calibration(trials: List<StoredTrial>, print: String?, ai: String): TeachStep {
        val runs = calibrationRuns(trials)
        val done = runs.lastOrNull { it.examinedWhole }
        val waiting = runs.lastOrNull { !it.examinedWhole }?.takeIf { w -> done == null || w.at > done.at }
        val name = "Calibration set"
        fun step(state: StepState, words: String, action: TeachAction) = TeachStep(1, name, state, status(state), words, action, label(action, ai, waiting))
        return when {
            runs.isEmpty() -> step(StepState.NOT_STARTED, "${CalibrationSet.SIZE} hands you judge blind, then $ai sits an exam on them", TeachAction.CALIBRATION)
            waiting != null -> step(
                StepState.IN_PROGRESS,
                "${ShootoutWords.hands(waiting.judged).replaceFirstChar { it.uppercase() }} judged · " +
                    if (waiting.examined > 0) "$ai's exam: ${waiting.examined} of ${waiting.judged}" else "$ai has not sat its exam",
                TeachAction.EXAM,
            )
            done != null && print != null && done.print != null && done.print != print -> step(
                StepState.IN_PROGRESS, "The decks changed since the exam · a short set earns the kinds back", TeachAction.CALIBRATION,
            )
            else -> step(StepState.DONE, "$ai agreed ${done!!.agreed} of ${done.examined} in its exam",TeachAction.CALIBRATION)
        }
    }

    private fun apprentice(log: ShootoutLog, ai: String): TeachStep {
        val hands = log.trials.count { it.judge == StoredTrial.PERSON && it.mode == TeachModes.APPRENTICE && !it.sawAi }
        val asked = log.notes.count { it.question != null }
        val questions = when (asked) {
            0 -> "no questions yet"
            1 -> "1 question asked"
            else -> "$asked questions asked"
        }
        val (state, words) = when {
            hands == 0 -> StepState.NOT_STARTED to "You judge; $ai predicts silently and asks now and then"
            hands < APPRENTICE_HANDS -> StepState.IN_PROGRESS to "$hands of $APPRENTICE_HANDS hands · $questions"
            else -> StepState.DONE to "${ShootoutWords.hands(hands)} · $questions"
        }
        return TeachStep(2, "Apprentice", state, status(state), words, TeachAction.APPRENTICE, label(TeachAction.APPRENTICE, ai, null))
    }

    private fun supervised(trials: List<StoredTrial>, ai: String): TeachStep {
        val mine = trials.filter { it.judge == StoredTrial.PERSON && it.mode == TeachModes.SUPERVISED }
        val answers = trials.filter { it.judge == StoredTrial.AI && it.of != null }.associateBy { it.of!! }
        val took = mine.count { p ->
            val a = answers[p.id] ?: return@count false
            if (p.kind == StoredTrial.COMPARE) p.prefer != null && p.prefer == (a.prefer ?: a.ai?.prefer)
            else p.answer != null && p.answer == (a.answer ?: a.ai?.answer)
        }
        val n = mine.size
        val (state, words) = when {
            n == 0 -> StepState.NOT_STARTED to "$ai judges first; you take its answer or correct it"
            n < SUPERVISED_HANDS -> StepState.IN_PROGRESS to "$n of $SUPERVISED_HANDS hands · you took its answer on $took"
            else -> StepState.DONE to "${ShootoutWords.hands(n)} · you took its answer on $took"
        }
        return TeachStep(3, "Supervised", state, status(state), words, TeachAction.SUPERVISED, label(TeachAction.SUPERVISED, ai, null))
    }

    private fun alone(state: TrustState, ai: String): TeachStep {
        val total = state.kinds.size
        val open = state.kinds.count { it.open }
        val name = "$ai judges alone"
        fun step(s: StepState, status: String, words: String, action: TeachAction) = TeachStep(4, name, s, status, words, action, label(action, ai, null))
        return when {
            open > 0 && state.settings.solo -> step(
                StepState.DONE, "On",
                "$open of $total kinds of hand" + if (state.solo > 0) " · ${ShootoutWords.hands(state.solo)} judged alone" else "",
                TeachAction.TRUST,
            )
            open > 0 -> step(StepState.IN_PROGRESS, "Off", "$open of $total kinds of hand earned · judging alone is off", TeachAction.TRUST)
            else -> {
                val need = state.kinds.sumOf { ShootoutTrustWords.sureNeeded(it) }
                val why = if (need > 0) "needs at least $need more hands it is sure of" else "its agreement is not yet over your bar"
                if (state.settings.solo) step(StepState.IN_PROGRESS, "Allowed", "No kind of hand is earned now · $why", TeachAction.APPRENTICE)
                else step(StepState.NOT_STARTED, "Not yet", "No kind of hand earned yet · $why", TeachAction.APPRENTICE)
            }
        }
    }

    /** A step's state in a word. */
    fun status(state: StepState): String = when (state) {
        StepState.NOT_STARTED -> "Not started"
        StepState.IN_PROGRESS -> "In progress"
        StepState.DONE -> "Done"
    }

    /** A step's action in words, for its button; [waiting] is a calibration set not yet examined whole. */
    fun label(action: TeachAction, ai: String, waiting: CalibrationRun? = null): String = when (action) {
        TeachAction.CALIBRATION -> "Begin the calibration set"
        TeachAction.EXAM -> if ((waiting?.examined ?: 0) > 0) "Finish the exam" else "Start the exam"
        TeachAction.APPRENTICE -> "Begin an apprentice session"
        TeachAction.SUPERVISED -> "Begin a supervised session"
        TeachAction.TRUST -> "Let $ai judge alone"
    }
}
