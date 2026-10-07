package com.kaiharimoto.neue.ai.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.core.ai.course.Chapter
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.course.StudyQueue
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * A course study in the panel, under its head while there is one: what it studies, what it is doing, and the person's
 * few controls — Begin once they are logged in, then Pause, Go on and Stop. Ink only, one rule under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseStrip(ai: AiState) {
    val studies = ai.courses
    val exams = ai.exams
    val shown = studies.current
    // A finished course stays only while it has more to study (the replays it links to, 1.1.41).
    val more = shown != null && shown.state == Course.State.DONE && !studies.running && StudyQueue.more(shown, studies.canWatch)
    val studying = shown != null && !(shown.state == Course.State.DONE && !studies.running && !more)
    // The exam is the measure once the study is done (1.1.47: a finished course hid it): the deck in view's latest course
    // with held-out replays read, while no study is on screen.
    val deckId = ai.h.builder.deckId
    val examCourse = if (studying) shown else remember(deckId, studies.running, exams.running, shown?.id) {
        studies.courses().firstOrNull { it.deckId == deckId && heldRead(it) > 0 }
    }
    val course = (if (studying) shown else examCourse) ?: return
    val c = Mu.colors
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink25, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Micro((if (studying) "Studying · " else "Exam · ") + course.label, color = c.ink45)
        if (studying) {
            val of = course.chapters.size
            val replays = course.replays.size
            val progress = (if (of > 0) " · ${course.done} of $of chapters" else "") + if (replays > 0) " · ${course.replaysDone} of $replays replays" else ""
            Small(studies.line.ifBlank { "Ready" } + progress, color = c.ink)
            // A stop sets both the line and the problem: said once (kai, 2026-10: it said it twice in a row).
            studies.problem?.takeIf { it != studies.line }?.let { Small(it, color = c.ink) }
        }
        // The exam (mastery's measure): how the last sitting went, or what it is doing now — this course's alone.
        val lastExam = remember(course.deckId, course.id, exams.running) { exams.results(course.deckId).lastOrNull { it.course == course.id || it.course.isBlank() } }
        when {
            exams.lineCourse == course.id && (exams.running || exams.line.isNotBlank()) -> Small(exams.line, color = c.ink)
            lastExam != null -> Small("Last exam: " + lastExam.words(), color = c.ink70)
        }
        studies.copySaid?.let { Small(it, color = c.ink70) }
        // What is kept on this computer, said once: the course never needs its pages opened again but to watch a video.
        if (studying) {
            val kept = course.chapters.count { it.saved }
            val pictures = course.chapters.sumOf { it.pictures }
            if (kept > 0) Help("Kept on this computer: $kept of ${course.chapters.size} pages" + (if (pictures > 0) ", $pictures pictures" else "") +
                (course.replays.count { it.state == Chapter.State.READ || it.state == Chapter.State.NOTED }.takeIf { it > 0 }?.let { ", $it replays" } ?: "") + ".", color = c.ink45)
        }
        // Videos waiting for the voice model are said plainly: until it is downloaded, they are not heard.
        val waiting = course.chapters.count { it.hasVideo && !it.watched }
        if (studying && waiting > 0 && !studies.canWatch) Small("$waiting chapter video${if (waiting == 1) "" else "s"} without captions wait for the voice model: download it in Settings › Voice, and they are watched next.", color = c.ink)
        // The panel is narrow: the controls wrap rather than run off its edge.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // A study and an exam never run together: the exam asks one state of knowledge (1.1.47).
            val sitting = exams.running
            if (studying) when {
                studies.awaitingLogin -> MuButton("Begin", { studies.begin() }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, arrow = true, enabled = !sitting, reason = EXAM_GOING)
                studies.running -> {
                    // Waiting out the model's limit or the network (1.1.46): the person may try at once.
                    if (course.retryAt > 0) MuButton("Try now", { studies.tryNow() }, size = BtnSize.SM)
                    MuButton("Pause", { studies.pause() }, size = BtnSize.SM)
                }
                more -> MuButton("Study it in depth", { studies.resume() }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = ai.configured && !sitting, reason = if (sitting) EXAM_GOING else "Set up ${ai.name} first")
                // Studying but not running (the app opened without a connection or a browser, or the study ended
                // unexpectedly): Go on, never a strip with only Stop (1.1.47).
                course.state == Course.State.PAUSED || course.state == Course.State.BLOCKED || course.state == Course.State.STUDYING ->
                    MuButton("Go on", { studies.resume() }, size = BtnSize.SM, enabled = ai.configured && !sitting, reason = if (sitting) EXAM_GOING else "Set up ${ai.name} first")
            }
            // The exam: the held-out replays' turns, asked and graded (not while it studies — the study is reading them in).
            if (sitting) MuButton("Stop the exam", { exams.stop() }, size = BtnSize.SM)
            else if (heldRead(course) > 0) {
                val why = exams.refusal(course)
                MuButton("Take the exam", { exams.start(course); studies.monitor.open = true }, size = BtnSize.SM, enabled = ai.configured && why == null, reason = why ?: "Set up ${ai.name} first")
            }
            // What is kept on this computer (1.1.48): the replays, read in the library; the whole course, as one file.
            if (course.replays.any { it.state == Chapter.State.READ || it.state == Chapter.State.NOTED }) MuButton("Replays", { ai.replays.open = true }, size = BtnSize.SM)
            if (course.chapters.any { it.state == Chapter.State.READ || it.state == Chapter.State.NOTED }) MuButton("Save a copy", { studies.saveCopy(course) }, size = BtnSize.SM)
            // Watch it study (kai, 2026-10): what it reads beside what it writes, live.
            if (studies.running || sitting || studies.monitor.written.isNotEmpty()) MuButton("Watch", { studies.monitor.open = true }, size = BtnSize.SM)
            if (studying) {
                if (more) MuButton("Not now", { studies.dismiss() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                else MuButton("Stop", { studies.stop() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
        }
    }
}

/** Held-out replays of [course] read: what the exam can ask. */
private fun heldRead(course: Course): Int = course.replays.count { it.exam && (it.state == Chapter.State.READ || it.state == Chapter.State.NOTED) }

private const val EXAM_GOING = "The exam is being sat: stop it first, or let it finish."
