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
    val course = studies.current ?: return
    // A finished course stays only while it has more to study (the replays it links to, 1.1.41).
    val more = course.state == Course.State.DONE && !studies.running && StudyQueue.more(course, studies.canWatch)
    if (course.state == Course.State.DONE && !studies.running && !more) return
    val c = Mu.colors
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink25, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Micro("Studying · ${course.label}", color = c.ink45)
        val of = course.chapters.size
        val replays = course.replays.size
        val progress = (if (of > 0) " · ${course.done} of $of chapters" else "") + if (replays > 0) " · ${course.replaysDone} of $replays replays" else ""
        Small(studies.line.ifBlank { "Ready" } + progress, color = c.ink)
        // A stop sets both the line and the problem: said once (kai, 2026-10: it said it twice in a row).
        studies.problem?.takeIf { it != studies.line }?.let { Small(it, color = c.ink) }
        // The exam (mastery's measure): how the last sitting went, or what it is doing now.
        val exams = ai.exams
        val lastExam = remember(course.deckId, exams.running) { exams.results(course.deckId).lastOrNull() }
        when {
            exams.running || exams.line.isNotBlank() -> Small(exams.line, color = c.ink)
            lastExam != null -> Small("Last exam: " + lastExam.words(), color = c.ink70)
        }
        // Videos waiting for the voice model are said plainly: until it is downloaded, they are not heard.
        val waiting = course.chapters.count { it.hasVideo && !it.watched }
        if (waiting > 0 && !studies.canWatch) Small("$waiting chapter video${if (waiting == 1) "" else "s"} without captions wait for the voice model: download it in Settings › Voice, and they are watched next.", color = c.ink)
        // The panel is narrow: the controls wrap rather than run off its edge.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                studies.awaitingLogin -> MuButton("Begin", { studies.begin() }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, arrow = true)
                studies.running -> {
                    // Waiting out the model's limit or the network (1.1.46): the person may try at once.
                    if (course.retryAt > 0) MuButton("Try now", { studies.tryNow() }, size = BtnSize.SM)
                    MuButton("Pause", { studies.pause() }, size = BtnSize.SM)
                }
                more -> MuButton("Study it in depth", { studies.resume() }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = ai.configured, reason = "Set up ${ai.name} first")
                course.state == Course.State.PAUSED || course.state == Course.State.BLOCKED ->
                    MuButton("Go on", { studies.resume() }, size = BtnSize.SM, enabled = ai.configured, reason = "Set up ${ai.name} first")
            }
            // The exam: the held-out replays' turns, asked and graded (not while it studies — the study is reading them in).
            val heldRead = course.replays.count { it.exam && (it.state == Chapter.State.READ || it.state == Chapter.State.NOTED) }
            if (exams.running) MuButton("Stop the exam", { exams.stop() }, size = BtnSize.SM)
            else if (heldRead > 0 && !studies.running) MuButton("Take the exam", { exams.start(course); studies.monitor.open = true }, size = BtnSize.SM, enabled = ai.configured, reason = "Set up ${ai.name} first")
            // Watch it study (kai, 2026-10): what it reads beside what it writes, live.
            if (studies.running || exams.running || studies.monitor.written.isNotEmpty()) MuButton("Watch", { studies.monitor.open = true }, size = BtnSize.SM)
            if (more) MuButton("Not now", { studies.dismiss() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            else MuButton("Stop", { studies.stop() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
    }
}
