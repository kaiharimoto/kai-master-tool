package com.kaiharimoto.neue.ai

import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Micro
import androidx.compose.ui.Alignment
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
 * Ai's brain (1.0.54, kai: "I also want to be able to read Ai's brain (the MDs) and edit them in
 * app in the top bar … it'll be like looking into Ai"): every markdown file it thinks with — its
 * voice, what it knows about you, its own notes, each deck's guide and notes, each web's, the
 * skills it wrote — grouped down the left with how full each bounded one is, and on the right the
 * file read as a document or edited as text. The files are the memory: what is saved here is what
 * Ai reads next. Opened from the bar's Ai button, Settings, the palette, and each document.
 */
@Composable
fun MemoryDialog(ai: AiState) {
    val path = ai.memoryOpen ?: return
    val c = Mu.colors
    val stamp = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val groups = remember(stamp.intValue) { brainGroups(ai) }
    androidx.compose.runtime.LaunchedEffect(Unit) { ai.deckNames = ai.h.webs.libraryDecks().associate { it.entry.id to it.entry.name } }
    var text by remember(path, stamp.intValue) { mutableStateOf(ai.files.read(path) ?: if (path == Persona.FILE) ai.files.soul(ai.name) else "") }
    var saved by remember(path, stamp.intValue) { mutableStateOf(true) }
    var editing by remember(path) { mutableStateOf(false) }
    fun save() {
        ai.files.write(path, text)
        saved = true
        stamp.intValue++
    }
    // Moving to another file keeps what was typed: nothing is lost to a click.
    fun open(next: String) {
        if (!saved) save()
        ai.memoryOpen = next
    }
    MuDialog(
        title = "${ai.name}'s brain",
        onDismiss = {
            if (!saved) save()
            ai.memoryOpen = null
        },
        width = 1080.dp,
        scrolls = false,
        description = "The markdown ${ai.name} thinks with. Read it, or edit it: entries are the lines that start with a dash, and what you save is what it reads next.",
        footer = {
            if (path.startsWith("guides/")) {
                MuButton("Open as the guide", {
                    if (!saved) save()
                    val id = path.removePrefix("guides/").removeSuffix(".md")
                    ai.memoryOpen = null
                    ai.docOpen = LivingDoc.Guide(id, deckName(ai, id))
                }, variant = BtnVariant.GHOST)
            }
            if (path == "USER.md") {
                MuButton("Open as your profile", {
                    if (!saved) save()
                    ai.memoryOpen = null
                    ai.docOpen = LivingDoc.Profile
                }, variant = BtnVariant.GHOST)
            }
            MuButton("Close", {
                if (!saved) save()
                ai.memoryOpen = null
            }, variant = BtnVariant.GHOST)
            MuButton(if (saved) "Saved" else "Save", { save() }, variant = BtnVariant.PRIMARY, enabled = !saved, reason = "Nothing changed")
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // The files, by what they are.
            Column(Modifier.width(260.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                groups.forEach { (group, files) ->
                    Micro(group, Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp), color = c.ink45)
                    files.forEach { file -> BrainRow(ai, file, file == path) { open(file) } }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        MuText(label(ai, path), style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 1)
                        Mono(path + (fullness(path, text)?.let { " · $it" } ?: ""), color = c.ink45)
                    }
                    com.kaiharimoto.neue.kit.Segmented(editing, listOf(false, true), { if (it) "Edit" else "Read" }, { editing = it }, small = true)
                }
                if (editing) {
                    val source = remember { MutableInteractionSource() }
                    val focused by source.collectIsFocusedAsState()
                    val style = MuType.mono(LocalMuFonts.current).copy(color = c.ink)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(460.dp)
                            .border(1.dp, animatedColor(if (focused) c.ink else c.ink25))
                            .cursor(CursorMode.TEXT, fontSize = style.fontSize, singleLine = false, focused = focused)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
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
                } else {
                    // Read: the file as a document, the way the chat draws Ai's answers.
                    val blocks = remember(text) { com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown.parse(text) }
                    Column(
                        Modifier.fillMaxWidth().height(460.dp).border(1.dp, c.ink12).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (text.isBlank()) Help("Empty: ${ai.name} writes here as it learns, or switch to Edit and write it yourself.")
                        blocks.forEach { MarkdownBlock(ai, it) }
                    }
                }
            }
        }
    }
}

/** The brain's files, grouped: who it is, you, its notes, then each deck's, each web's, its skills. */
private fun brainGroups(ai: AiState): List<Pair<String, List<String>>> {
    val root = ai.files.root
    fun list(dir: String, pattern: (java.io.File) -> Boolean = { it.extension == "md" }) =
        java.io.File(root, dir).listFiles()?.filter(pattern)?.sortedBy { it.name }?.map { it.relativeTo(root).invariantSeparatorsPath }.orEmpty()
    val skills = java.io.File(root, "skills").listFiles { f -> f.isDirectory }?.map { java.io.File(it, "SKILL.md") }?.filter { it.isFile }
        ?.sortedBy { it.path }?.map { it.relativeTo(root).invariantSeparatorsPath }.orEmpty()
    return listOf(
        "Who it is" to listOf(Persona.FILE),
        "You" to listOf("USER.md"),
        "Its own notes" to listOf("MEMORY.md"),
        "Deck guides" to byDeck(ai, list("guides")),
        "Deck notes" to byDeck(ai, list("decks")),
        "Webs" to list("webs"),
        "Skills it wrote" to skills,
    ).filter { it.second.isNotEmpty() }
}

/** How full a bounded memory file is, in characters against its limit. */
private fun fullness(path: String, text: String): String? {
    val kind = com.kaiharimoto.mastertool.core.ai.memory.MemoryKind.entries.firstOrNull { k ->
        if (k.file.contains("%s")) path.startsWith(k.file.substringBefore("%s")) else path == k.file
    } ?: return null
    val used = com.kaiharimoto.mastertool.core.ai.memory.AiMemory.parse(text).used
    // A guide has no cap (1.0.65): its size in words' worth of characters, not a share of one.
    if (!kind.bounded) return "${used / 1000}k characters".takeIf { used >= 1000 } ?: "$used characters"
    return "${used * 100 / kind.limit}% full"
}

@Composable
private fun BrainRow(ai: AiState, file: String, on: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
            .hoverable(source)
            .cursorPointer(caption = "Open")
            .muClickable(interactionSource = source, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MuText(label(ai, file), Modifier.weight(1f), style = MuType.small(LocalMuFonts.current), color = if (on) c.paper else c.ink, maxLines = 1)
    }
}

/** A deck's name for a guide or notes file, from the library as the app last read it. */
private fun deckName(ai: AiState, id: String): String =
    if (id == ai.h.builder.deckId) ai.h.builder.deckName else ai.deckNames[id] ?: "Deleted deck · ${id.take(4)}"

private fun known(ai: AiState, id: String) = id == ai.h.builder.deckId || id in ai.deckNames

/** A deck's files by its name, the decks no longer in the library last. */
private fun byDeck(ai: AiState, paths: List<String>): List<String> {
    fun id(path: String) = path.substringAfter('/').removeSuffix(".md")
    return paths.sortedWith(compareBy({ !known(ai, id(it)) }, { deckName(ai, id(it)).lowercase() }))
}

/** A memory file's name as a person reads it. */
private fun label(ai: AiState, path: String): String = when {
    path == Persona.FILE -> "Voice"
    path == "USER.md" -> "About you (your profile)"
    path == "MEMORY.md" -> "Its own notes"
    path.startsWith("webs/") -> "Web: " + (ai.h.webs.library.byId(path.removePrefix("webs/").removeSuffix(".md"))?.name ?: path.removePrefix("webs/"))
    path.startsWith("decks/") -> deckName(ai, path.removePrefix("decks/").removeSuffix(".md"))
    path.startsWith("guides/") -> deckName(ai, path.removePrefix("guides/").removeSuffix(".md"))
    path.startsWith("skills/") -> path.removePrefix("skills/").substringBefore('/')
    else -> path
}

/**
 * What Fine Tuning taught Ai (phase 3), before it is kept: each memory file it wrote,
 * the entries added and the ones gone. Keep leaves them; Undo puts every file back as
 * it was when the interview began. Nothing is learned behind the person's back.
 */
@Composable
fun ReviewDialog(ai: AiState) {
    val report = ai.endReport
    val changes = ai.review
    if (report == null && changes == null) return
    val c = Mu.colors
    val n = changes?.let { com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.count(it) } ?: 0
    MuDialog(
        title = if (report != null) "Fine Tuning · ${report.deckName}" else "What ${ai.name} learned",
        onDismiss = { ai.keepReview() },
        width = if (report != null) 720.dp else 560.dp,
        description = when {
            report != null && changes != null -> "${com.kaiharimoto.mastertool.core.ai.report.SessionReport.modeWords(report.mode)}. " +
                "The report, then $n change${if (n == 1) "" else "s"} to ${ai.name}'s memory to keep or undo."
            report != null -> "${com.kaiharimoto.mastertool.core.ai.report.SessionReport.modeWords(report.mode)}: the session's report."
            else -> "$n change${if (n == 1) "" else "s"} to its memory. Keep them, or put its memory back as it was."
        },
        footer = {
            if (changes != null) MuButton("Undo all", { ai.undoReview() }, variant = BtnVariant.GHOST)
            MuButton(if (changes != null) "Keep" else "Done", { ai.keepReview() }, variant = BtnVariant.PRIMARY)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (report != null) EndReport(ai, report)
            if (report != null && changes != null) Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
            changes?.forEach { change ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Mono(label(ai, change.path), color = c.ink45)
                    change.added.forEach { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Mono("+", color = c.ink); Small(it, color = c.ink) } }
                    change.removed.forEach { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Mono("−", color = c.ink45); Small(it, color = c.ink45) } }
                }
            }
        }
    }
}
