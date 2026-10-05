package com.kaiharimoto.neue.duel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.replay.Replays
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.ai.avatar.AiName
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.delay

private fun kbd(a: DeskAction) = DeskShortcuts.chordFor(a)?.let(DeskShortcuts::kbd)

/**
 * A replay's controls, in place of the duel's: back and forward a step, a phase or a turn; play either
 * way at a speed; take the step just played out, write a note here, or play on from here as a duel of
 * its own. Below them, the timeline: a tick for every step, a taller one at each phase, the turns
 * numbered, notes marked, struck steps hatched — and a click or a drag anywhere on it goes there.
 */
@Composable
internal fun ReplayBar(duels: Duels, replay: Replay) {
    val c = Mu.colors
    val e = replay.record.entries
    var noting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    // Playback: a step every 700 ms at 1×, either way, stopping at the end it is playing toward.
    LaunchedEffect(replay.playing, replay.speed) {
        while (duels.replay?.playing?.let { it != 0 } == true) {
            delay((700f / replay.speed).toLong())
            if (!duels.tick()) break
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Micro("Replay", color = c.ink45)
            RowText(replay.record.name.ifBlank { "Untitled duel" }, Modifier.width(180.dp), color = c.ink)
            Tip("A turn back", kbd = kbd(DeskAction.REPLAY_BACK_TURN)) { MuButton("«", { duels.step(ReplayUnit.TURN, -1) }, size = BtnSize.SM, variant = BtnVariant.GHOST) }
            Tip("A step back", kbd = kbd(DeskAction.REPLAY_BACK)) { MuButton("‹", { duels.step(ReplayUnit.GROUP, -1) }, size = BtnSize.SM, variant = BtnVariant.GHOST) }
            Tip("Play backwards") { MuButton("◂", { duels.play(-1) }, size = BtnSize.SM, variant = BtnVariant.GHOST, toggled = replay.playing < 0) }
            Tip("Play", kbd = kbd(DeskAction.REPLAY_PLAY)) { MuButton(if (replay.playing > 0) "Pause" else "Play", { duels.play(1) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY) }
            Tip("A step on", kbd = kbd(DeskAction.REPLAY_FORWARD)) { MuButton("›", { duels.step(ReplayUnit.GROUP, 1) }, size = BtnSize.SM, variant = BtnVariant.GHOST) }
            Tip("A turn on", kbd = kbd(DeskAction.REPLAY_FORWARD_TURN)) { MuButton("»", { duels.step(ReplayUnit.TURN, 1) }, size = BtnSize.SM, variant = BtnVariant.GHOST) }
            Segmented(replay.speed, listOf(0.5f, 1f, 2f, 4f), { if (it < 1f) "½×" else "${it.toInt()}×" }, { duels.speed(it) }, small = true, compact = true)
            Mono("${replay.at} / ${e.size}", color = c.ink70)
            Box(Modifier.weight(1f))
            if (noting) {
                MuInput(note, { note = it }, Modifier.width(220.dp), placeholder = "A note at this moment", dense = true, onSubmit = {
                    duels.note(note); note = ""; noting = false
                })
            } else {
                MuButton("Note", { noting = true }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
            }
            Tip("Take out the step just played", kbd = kbd(DeskAction.REPLAY_DELETE)) {
                MuButton("Cut step", { duels.deleteStep() }, size = BtnSize.SM, variant = BtnVariant.SUBTLE, enabled = replay.at > 0)
            }
            Tip("What if: play on from here as a duel of its own", kbd = kbd(DeskAction.REPLAY_BRANCH)) {
                MuButton("Play from here", { duels.branch() }, size = BtnSize.SM)
            }
            MuButton("Close", { duels.closeReplay() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
        Timeline(duels, replay, Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp))
    }
}

@Composable
private fun Timeline(duels: Duels, replay: Replay, modifier: Modifier) {
    val c = Mu.colors
    val e = replay.record.entries
    val marks = remember(replay.record) { Replays.marks(e) }
    val refused = duels.refused()
    fun at(x: Float, w: Float): Int = if (e.isEmpty()) 0 else ((x / w) * e.size).toInt().coerceIn(0, e.size)
    Canvas(
        modifier
            .cursor(CursorMode.POINTER, caption = "Go here")
            .pointerInput(e.size) { detectTapGestures { p -> duels.seek(at(p.x, size.width.toFloat())) } }
            .pointerInput(e.size) { detectDragGestures { change, _ -> duels.seek(at(change.position.x, size.width.toFloat())) } },
    ) {
        val n = maxOf(e.size, 1)
        val step = size.width / n
        val base = size.height
        drawLine(c.ink12, Offset(0f, base - 0.5f), Offset(size.width, base - 0.5f), 1.dp.toPx())
        // Played steps in ink, the rest in grey; a struck step a short tick.
        e.forEachIndexed { i, entry ->
            if (entry.seat == null) return@forEachIndexed
            val x = i * step + step / 2f
            val h = if (i in refused) base * 0.2f else base * 0.35f
            drawLine(if (i < replay.at) c.ink else c.ink45, Offset(x, base), Offset(x, base - h), 1.dp.toPx())
        }
        marks.forEach { m ->
            val x = m.at * step
            when (m.kind) {
                Replays.Mark.TURN -> drawLine(c.ink, Offset(x, 0f), Offset(x, base), 2.dp.toPx())
                Replays.Mark.PHASE -> drawLine(c.ink45, Offset(x, base * 0.3f), Offset(x, base), 1.dp.toPx())
                Replays.Mark.NOTE -> drawRect(c.ink, Offset(x - 3.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(6.dp.toPx(), 6.dp.toPx()))
            }
        }
        // Where the replay stands.
        val x = replay.at * step
        drawLine(c.ink, Offset(x, 0f), Offset(x, base), 3.dp.toPx())
    }
}

/** The tally's columns: who Ai played, how it went, what Ai saw, what they saw, who went first. */
private val TALLY_WEIGHTS = listOf(0.9f, 1.1f, 1.1f, 1.1f, 1.2f)

/** A micro-caps header strip over a ruled table (the kit's §12): [cells] side by side, or one [label] across. */
@Composable
private fun TallyHead(cells: List<String>) {
    val c = Mu.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (cells.size == 1) Micro(cells.single(), Modifier.weight(1f), color = c.ink70)
        else cells.forEachIndexed { i, t -> Micro(t, Modifier.weight(TALLY_WEIGHTS[i]), color = c.ink70) }
    }
}

/**
 * Ai's games against people, counted (Phase C; the design review, finding 11): a row per person and setting, read down by
 * column — on a phone, where five columns do not fit, a sentence a row.
 */
@Composable
private fun ResultsTable(scores: List<DuelResults.Score>, aiName: String) {
    val c = Mu.colors
    val phone = LocalPhone.current
    Column {
        TallyHead(if (phone) listOf("Against people") else listOf("Against", "Result", "$aiName saw", "They saw", "First"))
        scores.forEach { s ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (phone) {
                    Small(DuelResults.words(s, aiName), color = c.ink70)
                } else {
                    val cells = DuelResults.cells(s, aiName)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(cells.against, cells.result, cells.aiSaw, cells.theySaw, cells.first).forEachIndexed { i, t ->
                            Small(t, Modifier.weight(TALLY_WEIGHTS[i]), color = if (i == 0) c.ink else c.ink70)
                        }
                    }
                    cells.note?.let { Help(it) }
                }
            }
            HRule()
        }
    }
}

/** Ai vs Ai, counted apart by the models and the decks that met: its own strip, a sentence a pairing, at ink-70 like the rest. */
@Composable
private fun MatchesTable(matches: List<DuelResults.MatchScore>) {
    val c = Mu.colors
    Column {
        TallyHead(listOf("Ai vs Ai"))
        matches.forEach { m ->
            Small(DuelResults.matchWords(m), Modifier.fillMaxWidth().padding(vertical = 6.dp), color = c.ink70)
            HRule()
        }
    }
}

/** The library of replays: save the duel in play as one, open one, or let one go. */
@Composable
internal fun ReplayLibrary(duels: Duels, aiName: String = "Ai") {
    val c = Mu.colors
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        duels.loadReplays()
        duels.loadResults()
    }
    // The finished duels against Ai, counted (Phase C): one quiet line per person and setting, read off the records.
    val scores = remember(duels.results) { DuelResults.aiAgainst(duels.results) }
    // Ai vs Ai (two sessions, one a seat): counted apart, a line per pairing of models.
    val matches = remember(duels.results) { DuelResults.aiVsAi(duels.results) }
    MuDialog("Replays", { duels.libraryOpen = false }, width = 560.dp, description = "Every duel can be kept and watched again, a step, a phase or a turn at a time, either way — and edited, noted, or played on from any point.") {
        if (duels.game != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuInput(name, { name = it }, Modifier.weight(1f), placeholder = "Name this duel", dense = true, onSubmit = { duels.saveReplay(name); name = "" })
                MuButton("Keep the duel in play", { duels.saveReplay(name); name = "" }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            }
            HRule()
        }
        if (scores.isNotEmpty() || matches.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiName(aiName, c.ink)
                if (scores.isNotEmpty()) ResultsTable(scores, aiName)
                if (matches.isNotEmpty()) MatchesTable(matches)
            }
            HRule()
        }
        if (duels.replays.isEmpty()) {
            Help("No replays yet. Keep a duel above and it is here to watch.")
        }
        duels.replays.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    RowText(r.name, color = c.ink)
                    Small(
                        listOfNotNull(
                            java.text.SimpleDateFormat("d MMM yyyy, HH:mm").format(java.util.Date(r.saved)),
                            "${r.entries} steps",
                            r.decks.ifBlank { null },
                            if (r.parent != null) "a what-if" else null,
                        ).joinToString(" · "),
                        color = c.ink45,
                    )
                }
                MuButton("Watch", { duels.openReplay(r.id) }, size = BtnSize.SM)
                MuButton("Delete", { duels.deleteReplay(r.id) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            HRule()
        }
    }
}
