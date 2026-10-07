package com.kaiharimoto.neue.ai.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.course.Course
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
@Composable
fun CourseStrip(ai: AiState) {
    val studies = ai.courses
    val course = studies.current ?: return
    if (course.state == Course.State.DONE && !studies.running) return
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
        val progress = if (of > 0) " · ${course.done} of $of chapters" else ""
        Small(studies.line.ifBlank { "Ready" } + progress, color = c.ink)
        // A stop sets both the line and the problem: said once (kai, 2026-10: it said it twice in a row).
        studies.problem?.takeIf { it != studies.line }?.let { Small(it, color = c.ink) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                studies.awaitingLogin -> MuButton("Begin", { studies.begin() }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, arrow = true)
                studies.running -> MuButton("Pause", { studies.pause() }, size = BtnSize.SM)
                course.state == Course.State.PAUSED || course.state == Course.State.BLOCKED ->
                    MuButton("Go on", { studies.resume() }, size = BtnSize.SM, enabled = ai.configured, reason = "Set up ${ai.name} first")
            }
            MuButton("Stop", { studies.stop() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
    }
}
