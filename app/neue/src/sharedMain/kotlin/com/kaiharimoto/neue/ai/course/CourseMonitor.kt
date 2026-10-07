package com.kaiharimoto.neue.ai.course

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * The study, watched (kai, 2026-10: "the user sees what the Ai is writing live in comparison to what it's reading"):
 * what it is reading on the left — the section, the replay, the card, the video's picture and words — and what it is
 * writing on the right as it writes it — notes, playbook entries, the guide, and what was refused — with its thinking
 * under them. Ink on paper; side by side on a desk, one above the other on a phone.
 */
@Composable
fun CourseMonitor(ai: AiState) {
    val studies = ai.courses
    val m = studies.monitor
    if (!m.open) return
    val c = Mu.colors
    // The exam watched is said as the exam (1.1.47: the dialog said "Studying" and the study's line through a sitting).
    val exam = ai.exams.running
    MuDialog(
        title = (if (exam) "Exam · " else "Studying · ") + (studies.current?.label ?: "a course"),
        onDismiss = { m.open = false },
        width = 1180.dp,
        description = (if (exam) ai.exams.line else studies.line).ifBlank { "Ready" },
        scrolls = false,
        footer = { MuButton("Close", { m.open = false }, variant = BtnVariant.GHOST) },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                Row(Modifier.fillMaxWidth().heightIn(max = 620.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) { Reading(m) }
                    Column(Modifier.weight(1f)) { Writing(m) }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.heightIn(max = 300.dp)) { Reading(m) }
                    Column(Modifier.heightIn(max = 340.dp)) { Writing(m) }
                }
            }
        }
        if (m.thinking.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp)
                    .drawBehind { drawLine(c.ink25, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(top = 8.dp),
            ) {
                Micro("Thinking", color = c.ink45)
                Help(m.thinking.takeLast(420).trim(), maxLines = 4)
            }
        }
    }
}

@Composable
private fun ColumnScope.Reading(m: StudyMonitor) {
    val c = Mu.colors
    val r = m.reading
    Micro("Reading" + (r?.ref?.let { " · $it" } ?: ""), color = c.ink45)
    if (r == null) {
        Help("Nothing read yet. What the study opens appears here as it reads it.")
        return
    }
    Small(r.title, color = c.ink, maxLines = 2)
    m.picture?.let { bitmap ->
        Image(bitmap, contentDescription = m.pictureCaption, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(vertical = 6.dp), contentScale = ContentScale.Fit)
        if (m.pictureCaption.isNotBlank()) Help(m.pictureCaption)
    }
    val scroll = rememberScrollState()
    // A new reading starts at its top.
    LaunchedEffect(r.at) { scroll.scrollTo(0) }
    SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(top = 6.dp)) {
        Body(r.text, color = c.ink70)
    }
}

@Composable
private fun ColumnScope.Writing(m: StudyMonitor) {
    val c = Mu.colors
    Micro("Writing", color = c.ink45)
    val list = m.written
    if (list.isEmpty()) {
        Help("Nothing written yet. Notes, playbook entries and the guide appear here as they are written.")
        return
    }
    val state = rememberLazyListState()
    // Follow the newest, as it is written.
    // Keyed on the newest, never the count: past its cap the count stops changing (1.1.47).
    LaunchedEffect(list.lastOrNull()?.at, list.size) { state.animateScrollToItem(list.size - 1) }
    LazyColumn(Modifier.weight(1f, fill = false), state = state, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list) { w ->
            Column(
                Modifier.fillMaxWidth()
                    .drawBehind { drawLine(c.ink25, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                    .padding(start = 10.dp),
            ) {
                Micro(
                    when (w.kind) {
                        "notes" -> "Notes · ${w.ref}"
                        "guide" -> "Guide"
                        "refused" -> "Not kept · ${w.ref}"
                        "exam" -> "Exam · ${w.ref}"
                        else -> "Playbook · ${w.ref}"
                    },
                    color = if (w.kind == "refused") c.ink else c.ink45,
                )
                if (w.title.isNotBlank()) Small(w.title, color = c.ink, maxLines = 2)
                SelectionContainer { Body(w.text.take(6_000), color = c.ink70) }
            }
        }
    }
}
