package com.kaiharimoto.neue.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.ContextBreakdown
import com.kaiharimoto.mastertool.core.ai.ContextWindows
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/*
 * Context you can see and steer (1.0.56, kai: "implement context management tools and
 * indicators"): how full the model's window is, in the panel's head; what fills it, and what to do
 * about it, in a dialog. Ai sees the same numbers through `context_status`.
 */

/** A share 0–1 as ten cells, filled in ink, hatched past four fifths. */
@Composable
private fun Cells(share: Float, modifier: Modifier = Modifier, tall: androidx.compose.ui.unit.Dp = 4.dp) {
    val c = Mu.colors
    val filled = (share * 10).let { if (it > 0f && it < 1f) 1 else kotlin.math.round(it).toInt() }.coerceIn(0, 10)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(10) { k ->
            Box(
                Modifier
                    .weight(1f)
                    .height(tall)
                    .let { if (k < filled) it.background(if (share >= 0.8f) c.ink else c.ink70) else it.border(1.dp, c.ink12) },
            )
        }
    }
}

/**
 * How full the window is, in the head (1.0.56): the tokens used of the model's window and ten
 * cells; a click opens the Context panel.
 */
@Composable
internal fun ContextGauge(ai: AiState) {
    val c = Mu.colors
    val s = ai.session ?: return
    if (s.turns.isEmpty()) return
    val used = ai.contextUsed
    val window = ai.window.coerceAtLeast(1)
    val share = (used.toFloat() / window).coerceIn(0f, 1f)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val tip = buildString {
        append(if (ai.contextMeasured) "" else "About ")
        append("${ContextWindows.words(used)} of ${ContextWindows.words(window.toLong())} tokens")
        append(" — ${(share * 100).toInt()}% of what the model can read at once. ")
        append(if (share >= 0.6f) "Nearly full: the start will be summarised soon. " else "")
        append("Click to see what fills it.")
    }
    Tip(tip) {
        Column(
            Modifier
                .width(64.dp)
                .hoverable(source)
                .cursorPointer(caption = "Context")
                .muClickable(interactionSource = source) { ai.contextOpen = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Mono(
                (if (ai.contextMeasured) "" else "≈") + ContextWindows.words(used),
                color = animatedColor(if (hovered || share >= 0.6f) c.ink else c.ink45),
            )
            Cells(share, Modifier.fillMaxWidth())
        }
    }
}

/** What fills the window, part by part, and what to do about it (1.0.56). */
@Composable
fun ContextPanel(ai: AiState) {
    if (!ai.contextOpen) return
    val c = Mu.colors
    val s = ai.session
    var focus by remember { mutableStateOf("") }
    MuDialog(
        title = "${ai.name}'s context",
        onDismiss = { ai.contextOpen = false },
        width = 620.dp,
        description = "What ${ai.name} reads each time it answers: its instructions, the rules, what it remembers, its tools and this conversation. " +
            "The model reads only so much at once; past most of it, the start of the conversation is summarised.",
        footer = {
            MuButton("Done", { ai.contextOpen = false }, variant = BtnVariant.PRIMARY)
        },
    ) {
        if (s == null) {
            Help("No conversation yet.", color = c.ink70)
            return@MuDialog
        }
        val used = ai.contextUsed
        val window = ai.window.coerceAtLeast(1)
        val slices = ContextBreakdown.of(s, ai.tools, s.context)
        val total = slices.sumOf { it.tokens }.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mono((if (ai.contextMeasured) "" else "≈ ") + ContextWindows.words(used), color = c.ink)
                    Mono("of ${ContextWindows.words(window.toLong())} tokens · ${used * 100 / window}%", color = c.ink45)
                }
                Cells((used.toFloat() / window).coerceIn(0f, 1f), Modifier.fillMaxWidth(), tall = 8.dp)
                Help(
                    when {
                        ai.ownsContext -> "This connection's app keeps its own history and compacts it itself; these are the app's estimate."
                        ai.contextMeasured -> "As the provider counted it on the last answer."
                        else -> "An estimate, until the next answer is counted."
                    },
                    color = c.ink45,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("What fills it", color = c.ink70)
                slices.forEach { slice ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Small(slice.label, Modifier.width(170.dp), color = c.ink)
                        Box(Modifier.weight(1f).height(8.dp)) {
                            Box(Modifier.fillMaxWidth(slice.tokens.toFloat() / total).height(8.dp).background(c.ink70))
                        }
                        Mono(ContextWindows.words(slice.tokens), Modifier.width(48.dp), color = c.ink45)
                    }
                }
            }
            if (s.summarized > 0 || s.carriedFrom != null || s.clearedBefore > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Micro("Already done", color = c.ink70)
                    if (s.summarized > 0) Small("The first ${s.summarized} messages are summarised. ${ai.name} can still recall their words.", color = c.ink)
                    if (s.carriedFrom != null) Small("This conversation carries on from an earlier one, with its summary.", color = c.ink)
                    if (s.clearedBefore > 0) Small("Old tool results are sent cut short.", color = c.ink)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("What it remembers, read every time", color = c.ink70)
                val deckId = ai.h.builder.deckId
                // Kept whole, read within a budget (1.1.11): each file's size, and how much of it goes in front of the model.
                // Measured off the frame thread: a guide may be a megabyte.
                val memory by androidx.compose.runtime.produceState(emptyList<Triple<String, String, Pair<Int, Int>>>(), deckId, s.id) {
                    val files = listOf(
                        Triple("About you", MemoryKind.USER, null),
                        Triple("Its own notes", MemoryKind.AGENT, null),
                    ) + listOfNotNull(deckId?.let { Triple("This deck's guide", MemoryKind.GUIDE, it) })
                    value = files.map { (label, kind, id) ->
                        val path = AiMemory.path(kind, id)
                        val size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.files.read(path)?.length ?: 0 }
                        Triple(label, path, size to minOf(size, ai.memoryRoom(kind)))
                    }
                }
                memory.forEach { (label, path, sizes) ->
                    val (size, read) = sizes
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Small(label, Modifier.weight(1f), color = c.ink)
                        Mono(
                            when {
                                size == 0 -> "empty"
                                read >= size -> "${ContextWindows.words(size / 4L)} tokens"
                                else -> "${ContextWindows.words(read / 4L)} of ${ContextWindows.words(size / 4L)} tokens"
                            },
                            color = c.ink45,
                        )
                        MuButton("Open", { ai.contextOpen = false; ai.memoryOpen = path }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                    }
                }
            }
            if (!ai.ownsContext) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Micro("Make room", color = c.ink70)
                    MuInput(focus, { focus = it }, placeholder = "What a summary must keep (optional)", modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MuButton(
                            "Compact now",
                            { ai.contextOpen = false; ai.compactNow(focus.ifBlank { null }) },
                            variant = BtnVariant.SECONDARY,
                            size = BtnSize.SM,
                            enabled = s.turns.size > 4 && !ai.running,
                            reason = if (ai.running) "Answering" else "Too short to summarise yet",
                        )
                        MuButton(
                            "Clear old tool results",
                            { ai.clearToolResults() },
                            variant = BtnVariant.GHOST,
                            size = BtnSize.SM,
                            enabled = s.turns.any { it.isToolResults } && s.clearedBefore < s.turns.size,
                            reason = "No tool results to clear",
                        )
                        MuButton(
                            "Start fresh with a summary",
                            { ai.contextOpen = false; ai.startFresh() },
                            variant = BtnVariant.GHOST,
                            size = BtnSize.SM,
                            enabled = s.turns.isNotEmpty() && !ai.running,
                            reason = "Answering",
                        )
                    }
                    Help("Compact summarises all but the last few exchanges. Clearing cuts old tool results short, which costs nothing. A fresh start keeps only a summary.", color = c.ink45)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Micro("Spent in this conversation", color = c.ink70)
                Mono(
                    "${ContextWindows.words(s.usage.read)} read · ${ContextWindows.words(s.usage.output)} written" +
                        (s.usage.cacheRead.takeIf { it > 0 }?.let { " · ${ContextWindows.words(it)} from cache" }.orEmpty()) +
                        (s.usage.costUsd?.let { " · about $" + "%.2f".format(it) }.orEmpty()),
                    color = c.ink,
                )
            }
        }
    }
}
