package com.kaiharimoto.neue.ai.course

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.course.Chapter
import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.course.CoursePaths
import com.kaiharimoto.mastertool.core.ai.course.DbReplay
import com.kaiharimoto.mastertool.core.ai.course.DbReplays
import com.kaiharimoto.mastertool.core.ai.course.ReplayRef
import com.kaiharimoto.mastertool.core.ai.course.ReplayStats
import com.kaiharimoto.mastertool.core.ai.course.StudyRetry
import com.kaiharimoto.mastertool.core.ai.exam.AuthorExam
import com.kaiharimoto.mastertool.core.ai.exam.ExamAnswer
import com.kaiharimoto.mastertool.core.ai.exam.ExamBrief
import com.kaiharimoto.mastertool.core.ai.exam.ExamLog
import com.kaiharimoto.mastertool.core.ai.exam.ExamRun
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.guideForPrompt
import com.kaiharimoto.neue.ai.playbook
import com.kaiharimoto.neue.duel.tableGuide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The exam, sat ([AuthorExam]): every held-out replay of a course turned into the author's turns, a few dozen of them asked —
 * the same ones each time — each in a conversation of its own with what Ai learned and nothing of the duel's future, its
 * answer graded by the app against the author's plays. Kept per deck in `ai/exams/<deck>.json`, each sitting with the
 * model, the effort and how much Ai knew, so the next one says what changed. The person watches it on the monitor.
 */
class CourseExams(private val ai: AiState) {
    var running by mutableStateOf(false)
        private set

    /** What the exam is doing, or how the last sitting went. */
    var line by mutableStateOf("")
        private set

    private var job: Job? = null
    private val files get() = ai.files
    private val monitor get() = ai.courses.monitor

    fun results(deckId: String): List<ExamRun> = ExamLog.read(files.read(ExamLog.path(deckId)))

    /** The replays of [course] read so far, parsed. */
    private fun read(course: Course): List<Pair<ReplayRef, DbReplay>> = course.replays
        .filter { it.state == Chapter.State.READ || it.state == Chapter.State.NOTED }
        .mapNotNull { r -> files.read(CoursePaths.replayRaw(course.id, r.n))?.let(DbReplays::parse)?.let { r to it } }

    /** The positions [course]'s exam asks and the author they are asked of; none until a held-out replay has been read. */
    fun positions(course: Course): Pair<String?, List<AuthorExam.Point>> {
        val parsed = read(course)
        val studied = parsed.filter { !it.first.exam }.map { (r, d) -> ReplayStats.Entry(r.n, r.chapter, d) }
        val author = ReplayStats.focus(studied) ?: ReplayStats.focus(parsed.map { (r, d) -> ReplayStats.Entry(r.n, r.chapter, d) })
            ?: return null to emptyList()
        val points = parsed.filter { it.first.exam }.flatMap { (r, d) -> AuthorExam.points(r.n, d, author) }
        return author to AuthorExam.pick(points)
    }

    fun start(course: Course) {
        if (running) return
        running = true
        line = "Starting the exam…"
        job = ai.scope.launch {
            try {
                withContext(Dispatchers.IO) { sit(course) }
            } catch (c: CancellationException) {
                line = "The exam was stopped."
                throw c
            } catch (t: Throwable) {
                line = "The exam stopped: ${t.message ?: t::class.simpleName}"
            } finally {
                running = false
            }
        }.also { j ->
            ai.backgroundJobs.removeAll { it.isCompleted }
            ai.backgroundJobs += j
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun sit(course: Course) {
        val (author, points) = positions(course)
        if (author == null || points.isEmpty()) {
            line = "No held-out replay has been read yet: the exam is ready once the study has read some."
            return
        }
        val connection = ai.prefs.connection ?: error("${ai.name} has no connection set up.")
        val provider = Providers.byId(connection.provider)
        val strength = ai.h.neue.prefs.duel.aiStrength
        val effort = DuelPrefs.effort(strength, provider?.efforts.orEmpty(), ai.prefs.effort.ifBlank { provider?.defaultEffort.orEmpty() })
        val guide = tableGuide(ai.guideForPrompt(course.deckId))
        val system = ExamBrief.system(ai.name, files.soul(ai.name), course.deckName, guide)
        val offered = ai.tools.filter { it.name in ExamBrief.tools } + ExamBrief.answer
        val started = System.currentTimeMillis()
        val sitting = ExamLog.sitting(course.deckId)
        // A sitting stopped before goes on from the next position (1.1.46), with the same model and thought.
        val answers = ArrayList(ExamLog.resume(ExamLog.readSitting(files.read(sitting)), course.deckId, connection.model, effort, points.map { it.id }))
        fun keep() = files.write(
            sitting,
            ExamLog.writeSitting(ExamRun(at = started, deckId = course.deckId, model = connection.model, effort = effort, answers = answers.toList())),
        )
        var k = 0
        var tries = 0
        while (k < points.size) {
            val p = points[k]
            if (answers.any { it.id == p.id }) {
                k++
                continue
            }
            line = "Exam: position ${k + 1} of ${points.size} — replay ${p.replay}, game ${p.game}, turn ${p.turn}"
            monitor.reading("Exam · replay ${p.replay}, game ${p.game}, turn ${p.turn} (as $author saw it)", p.context, "exam")
            var given: Pair<List<String>, String>? = null
            try {
                ai.studyStep(
                    course.id, course.deckId, course.deckName, system, ExamBrief.ask(p), offered, effort, DuelPrefs.steps(strength, STEPS),
                    monitor = monitor,
                    local = { call ->
                        if (call.name.removePrefix("mcp__neue__") != ExamBrief.answer.name) null
                        else {
                            val plays = ToolArgs.objects(call.input, "plays").mapNotNull { ToolArgs.string(it, "card")?.trim()?.takeIf { c -> c.isNotEmpty() } }
                            given = plays to ToolArgs.string(call.input, "why").orEmpty()
                            Part.ToolResult(call.id, call.name, "Answer kept. That is all for this position.")
                        }
                    },
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Stopped by the model or the network: the same position again after a wait, never counted as missed.
                if (given == null) {
                    val why = t.message ?: t::class.simpleName.orEmpty()
                    val kind = StudyRetry.kind(why, (t as? StepFailed)?.auth == true)
                    tries++
                    if (StudyRetry.givesUp(kind, tries)) {
                        line = "The exam stopped at position ${k + 1} of ${points.size}: $why. Take the exam again to go on from there."
                        return
                    }
                    val wait = StudyRetry.waitMs(tries)
                    line = "Exam, position ${k + 1} of ${points.size}: " + StudyRetry.note(why, wait / 60_000)
                    delay(wait)
                    continue
                }
            }
            tries = 0
            val target = p.cards
            val g = given
            val a = if (g == null) {
                ExamAnswer(p.id, p.replay, p.game, p.turn, target, missed = true)
            } else {
                val graded = AuthorExam.grade(target, g.first)
                ExamAnswer(p.id, p.replay, p.game, p.turn, target, g.first, graded.first, graded.recall, graded.precision, g.second.take(800))
            }
            answers += a
            keep()
            k++
            monitor.note(
                "exam", "replay ${p.replay}, game ${p.game}, turn ${p.turn}",
                if (a.missed) "No answer" else if (a.first) "Same first play as the author" else "A different first play",
                "${ai.name}: ${a.answer.joinToString(" → ").ifBlank { "—" }}\n$author: ${target.joinToString(" → ")}" + (if (a.why.isNotBlank()) "\nWhy: ${a.why}" else ""),
            )
        }
        // In the positions' order, whichever sitting answered them.
        val order = points.map { it.id }
        val run = ExamRun(
            at = System.currentTimeMillis(), deckId = course.deckId, model = connection.model, effort = effort,
            playbook = ai.playbook(course.deckId)?.size ?: 0, guide = ai.guideForPrompt(course.deckId).length,
            answers = answers.sortedBy { order.indexOf(it.id) },
        )
        val before = results(course.deckId)
        files.write(ExamLog.path(course.deckId), ExamLog.write(before + run))
        files.file(sitting).delete()
        line = ExamLog.compare(run, before.lastOrNull())
    }

    companion object {
        /** Rounds a position may take at Fast; Strong and Max take more (`DuelPrefs.steps`). */
        const val STEPS = 12
    }
}
