package com.kaiharimoto.neue.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.memory.Persona
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * What Ai knows (Settings → Assistant): every memory file — its voice, what it knows
 * about the person, its own notes, each deck's and web's — listed, read and edited
 * in place. The files are the memory: what is saved here is what Ai reads next.
 */
@Composable
fun MemoryDialog(ai: AiState) {
    val path = ai.memoryOpen ?: return
    val c = Mu.colors
    val files = remember(path) { ai.files.memoryFiles().map { it.relativeTo(ai.files.root).invariantSeparatorsPath } }
    val all = (listOf(Persona.FILE) + files).distinct()
    var text by remember(path) { mutableStateOf(ai.files.read(path) ?: if (path == Persona.FILE) ai.files.soul(ai.name) else "") }
    var saved by remember(path) { mutableStateOf(true) }
    MuDialog(
        title = "What ${ai.name} knows",
        onDismiss = { ai.memoryOpen = null },
        width = 760.dp,
        scrolls = false,
        description = "Markdown files in ${ai.name}'s folder. Edit them freely: entries are the lines that start with a dash.",
        footer = {
            MuButton("Close", { ai.memoryOpen = null }, variant = BtnVariant.GHOST)
            MuButton(if (saved) "Saved" else "Save", {
                ai.files.write(path, text)
                saved = true
            }, variant = BtnVariant.PRIMARY, enabled = !saved, reason = "Nothing changed")
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(200.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                all.forEach { file ->
                    val source = remember { MutableInteractionSource() }
                    val hovered by source.collectIsHoveredAsState()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(animatedColor(if (file == path) c.ink else if (hovered) c.ink06 else Color.Transparent))
                            .hoverable(source)
                            .cursorPointer(caption = "Open")
                            .muClickable(interactionSource = source) { ai.memoryOpen = file }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Mono(label(ai, file), color = if (file == path) c.paper else c.ink)
                    }
                }
            }
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val style = MuType.mono(LocalMuFonts.current).copy(color = c.ink)
            Box(
                Modifier
                    .weight(1f)
                    .height(420.dp)
                    .border(1.dp, animatedColor(if (focused) c.ink else c.ink25))
                    .cursor(CursorMode.TEXT, fontSize = style.fontSize, singleLine = false, focused = focused)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; saved = false },
                    textStyle = style,
                    cursorBrush = SolidColor(c.ink),
                    interactionSource = source,
                    modifier = Modifier.fillMaxWidth().reportsTextFocus(),
                )
            }
        }
        if (files.isEmpty()) Box(Modifier.padding(top = 12.dp)) { Help("Nothing remembered yet: ${ai.name} writes here as it learns.") }
        Box(Modifier.padding(top = 8.dp)) { Small(path, color = c.ink45) }
    }
}

/** A memory file's name as a person reads it. */
private fun label(ai: AiState, path: String): String = when {
    path == Persona.FILE -> "Voice"
    path == "USER.md" -> "About you"
    path == "MEMORY.md" -> "Its own notes"
    path.startsWith("webs/") -> "Web: " + (ai.h.webs.library.byId(path.removePrefix("webs/").removeSuffix(".md"))?.name ?: path.removePrefix("webs/"))
    path.startsWith("decks/") -> "Deck: " + path.removePrefix("decks/").removeSuffix(".md").take(12)
    else -> path
}

/**
 * What Fine Tuning taught Ai (phase 3), before it is kept: each memory file it wrote,
 * the entries added and the ones gone. Keep leaves them; Undo puts every file back as
 * it was when the interview began. Nothing is learned behind the person's back.
 */
@Composable
fun ReviewDialog(ai: AiState) {
    val changes = ai.review ?: return
    val c = Mu.colors
    val n = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.count(changes)
    MuDialog(
        title = "What ${ai.name} learned",
        onDismiss = { ai.keepReview() },
        width = 560.dp,
        description = "$n change${if (n == 1) "" else "s"} to its memory. Keep them, or put its memory back as it was.",
        footer = {
            MuButton("Undo all", { ai.undoReview() }, variant = BtnVariant.GHOST)
            MuButton("Keep", { ai.keepReview() }, variant = BtnVariant.PRIMARY)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            changes.forEach { change ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Mono(label(ai, change.path), color = c.ink45)
                    change.added.forEach { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Mono("+", color = c.ink); Small(it, color = c.ink) } }
                    change.removed.forEach { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Mono("−", color = c.ink45); Small(it, color = c.ink45) } }
                }
            }
        }
    }
}
