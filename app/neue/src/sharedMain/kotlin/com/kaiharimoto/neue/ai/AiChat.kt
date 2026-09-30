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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.text.Block
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.ai.text.Inline
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/** One thing drawn in the transcript. */
private sealed interface Entry {
    data class Person(val text: String) : Entry
    data class Reply(val text: String) : Entry
    data class Line(val summary: String, val isError: Boolean) : Entry
}

/** The conversation as rows: the person's words, Ai's words, and a line for each thing Ai did. */
private fun rows(turns: List<ChatTurn>): List<Entry> = buildList {
    turns.forEach { turn ->
        when {
            turn.role == Role.USER && turn.isToolResults -> turn.toolResults.forEach { add(Entry.Line(it.summary.ifBlank { it.name }, it.isError)) }
            turn.role == Role.USER -> turn.text.takeIf { it.isNotBlank() }?.let { add(Entry.Person(it)) }
            else -> {
                turn.parts.filterIsInstance<Part.Activity>().forEach { add(Entry.Line(if (it.summary.isNotBlank()) it.summary else it.name, it.isError)) }
                turn.text.takeIf { it.isNotBlank() }?.let { add(Entry.Reply(it)) }
            }
        }
    }
}

/**
 * The conversation (Ai, 1.0.43): the person's words set in grey on the right, Ai's in
 * ink on the left in its own markdown, and a mono line for each thing it did —
 * "Added 3 Ash Blossom", "✕ Could not add Maxx "C"". Below the last reply, whatever
 * Ai is waiting on: a confirm card, a question with its answers, or what went wrong.
 */
@Composable
fun Transcript(ai: AiState, modifier: Modifier = Modifier) {
    val session = ai.session
    val rows = remember(session?.turns) { rows(session?.turns.orEmpty()) }
    val list = rememberLazyListState()
    val tail = rows.size + (if (ai.streaming.isNotEmpty()) 1 else 0) + (if (ai.working != null || ai.running) 1 else 0) +
        (if (ai.confirm != null) 1 else 0) + (if (ai.question != null) 1 else 0) + (if (ai.problem != null) 1 else 0) + ai.activity.size
    LaunchedEffect(tail, ai.streaming.length / 80) {
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0) list.scrollToItem(last)
    }
    if (rows.isEmpty() && !ai.running) {
        Greeting(ai, modifier)
        return
    }
    run {
        LazyColumn(
            modifier.fillMaxSize(),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows) { row ->
                when (row) {
                    is Entry.Person -> PersonSays(row.text)
                    is Entry.Reply -> ReplyView(ai, row.text)
                    is Entry.Line -> ActivityLine(row.summary, row.isError)
                }
            }
            items(ai.activity) { ActivityLine(it.summary.ifBlank { it.name }, it.isError) }
            if (ai.streaming.isNotEmpty()) item { ReplyView(ai, ai.streaming, live = true) }
            if (ai.running) item { Working(ai.working ?: ai.status ?: if (ai.streaming.isEmpty()) "Thinking" else "Writing") }
            ai.confirm?.let { c -> item { ConfirmCard(c) } }
            ai.question?.let { q -> item { QuestionCard(q) } }
            ai.problem?.let { (message, connection) -> item { ProblemCard(message, connection) { ai.openWizard() } } }
        }
    }
}

@Composable
private fun Greeting(ai: AiState, modifier: Modifier) {
    val c = Mu.colors
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MuText(ai.name, style = MuType.h1(LocalMuFonts.current), color = c.ink)
        Small(
            "Ask me anything about the game, or have me do it: build a deck, tune the one that is open, sort it into groups, " +
                "write a siding plan, read the latest tournament results, change a setting. I remember what you tell me.",
            color = c.ink70,
        )
        Suggestions(ai)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(ai: AiState) {
    val deck = ai.h.builder.deck
    val ideas = buildList {
        if (deck.totalCards > 0) {
            add("Assess this deck")
            add("Sort this deck into groups")
        }
        add("Build me a deck")
        if (deck.totalCards > 0) add("Which cards here are limited?") else add("Explain going first versus second")
        if (ai.h.webs.library.webs.isNotEmpty()) add("Write siding plans for my web")
        if (AiState.PHASE >= 2) add("What is topping in the TCG right now?")
        if (AiState.PHASE >= 2 && ai.h.webs.library.webs.isEmpty()) add("Build a web of the current field")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ideas.forEach { idea -> Tag(idea, selected = false, onClick = { ai.draft = idea; ai.focusTick++ }, caption = "Ask") }
    }
}

@Composable
private fun PersonSays(text: String) {
    val c = Mu.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.fillMaxWidth(0.88f).background(c.ink06).padding(horizontal = 12.dp, vertical = 8.dp)) {
            SelectionContainer { MuText(text, style = MuType.row(LocalMuFonts.current), color = c.ink) }
        }
    }
}

@Composable
private fun ActivityLine(summary: String, isError: Boolean) {
    val c = Mu.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Mono(if (isError) "✕" else "→", color = if (isError) c.ink else c.ink45)
        MuText(summary, style = MuType.mono(LocalMuFonts.current), color = if (isError) c.ink else c.ink70)
    }
}

@Composable
private fun Working(line: String) {
    val c = Mu.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Breathe(running = true)
        Mono(line, color = c.ink70)
    }
}

/** Ai's words: its markdown in the app's type, and a chip for each card it named. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyView(ai: AiState, text: String, live: Boolean = false) {
    val c = Mu.colors
    val blocks = remember(text) { ChatMarkdown.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro(ai.name, color = c.ink45)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { blocks.forEach { MarkdownBlock(it) } }
        }
        if (!live) {
            val names = remember(text) { ChatMarkdown.cards(text) }
            val cards = names.mapNotNull { ai.h.builder.index.byName(it) }
            if (cards.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cards.take(12).forEach { CardChip(ai, it) }
                }
            }
        }
    }
}

/** A card Ai named: hover reads it in the inspector, a click opens it large. */
@Composable
private fun CardChip(ai: AiState, card: com.kaiharimoto.mastertool.core.model.Card) {
    val c = Mu.colors
    val neue = ai.h.neue
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) neue.hovered = card else if (neue.hovered == card) neue.hovered = null
    }
    Box(
        Modifier
            .border(1.dp, animatedColor(if (hovered) c.ink else c.ink25))
            .hoverable(source)
            .cursorPointer(caption = "Open")
            .muClickable(interactionSource = source) { neue.viewing = Viewing(card, null, 0) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Small(card.name, color = c.ink, maxLines = 1)
    }
}

@Composable
private fun MarkdownBlock(block: Block) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    when (block) {
        is Block.Heading -> MuText(styled(block.inlines), style = if (block.level <= 2) MuType.row(f).copy(fontWeight = FontWeight.Bold) else MuType.row(f).copy(fontWeight = FontWeight.Medium), color = c.ink)
        is Block.Paragraph -> MuText(styled(block.inlines), style = MuType.row(f), color = c.ink)
        is Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuText("–", style = MuType.row(f), color = c.ink45)
                    MuText(styled(item), style = MuType.row(f), color = c.ink)
                }
            }
        }
        is Block.Numbered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { n, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mono((block.start + n).toString().padStart(2, '0'), color = c.ink45)
                    MuText(styled(item), style = MuType.row(f), color = c.ink)
                }
            }
        }
        is Block.Code -> Box(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(8.dp)) { Mono(block.text, color = c.ink) }
        is Block.Quote -> Row(
            Modifier.drawBehind { drawLine(c.ink25, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }.padding(start = 10.dp),
        ) { MuText(styled(block.inlines), style = MuType.row(f), color = c.ink70) }
        is Block.Table -> Column(Modifier.fillMaxWidth().border(1.dp, c.ink12)) {
            (listOf(block.header) + block.rows).forEachIndexed { r, cells ->
                Row(Modifier.fillMaxWidth().drawBehind { if (r > 0) drawLine(c.ink12, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(6.dp)) {
                    cells.forEach { cell ->
                        MuText(styled(cell), Modifier.weight(1f), style = if (r == 0) MuType.small(f).copy(fontWeight = FontWeight.Medium) else MuType.small(f), color = c.ink)
                    }
                }
            }
        }
        Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).drawBehind { drawLine(c.ink12, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) })
    }
}

@Composable
private fun styled(inlines: List<Inline>): AnnotatedString {
    val f = LocalMuFonts.current
    val mono = MuType.mono(f, 12.sp).fontFamily
    return buildAnnotatedString {
        inlines.forEach { i ->
            when (i) {
                is Inline.Text -> append(i.text)
                is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(i.text) }
                is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(i.text) }
                is Inline.Code -> withStyle(SpanStyle(fontFamily = mono)) { append(i.text) }
                is Inline.Card -> withStyle(SpanStyle(fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)) { append(i.name) }
            }
        }
    }
}

@Composable
private fun ConfirmCard(c: Confirm) {
    val colors = Mu.colors
    Column(
        Modifier.fillMaxWidth().border(1.dp, colors.ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MuText(c.title, style = MuType.row(LocalMuFonts.current).copy(fontWeight = FontWeight.Medium), color = colors.ink)
        Help(c.detail, color = colors.ink70)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton("Do it", { c.reply(true) }, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
            MuButton("Don't", { c.reply(false) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(q: Question) {
    val c = Mu.colors
    var picked by remember(q) { mutableStateOf(setOf<String>()) }
    var own by remember(q) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MuText(q.question, style = MuType.row(LocalMuFonts.current).copy(fontWeight = FontWeight.Medium), color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            q.options.forEach { option ->
                Tag(option, selected = option in picked, onClick = {
                    if (q.multiple) picked = if (option in picked) picked - option else picked + option else q.reply(option)
                }, caption = if (q.multiple) "Choose" else "Answer")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.kaiharimoto.neue.kit.MuInput(own, { own = it }, Modifier.weight(1f), placeholder = "Or say it your way", dense = true, onSubmit = {
                val answer = (picked + listOfNotNull(own.trim().takeIf { it.isNotEmpty() })).joinToString("; ")
                if (answer.isNotBlank()) q.reply(answer)
            })
            if (q.multiple || own.isNotBlank()) {
                MuButton("Answer", {
                    val answer = (picked + listOfNotNull(own.trim().takeIf { it.isNotEmpty() })).joinToString("; ")
                    if (answer.isNotBlank()) q.reply(answer)
                }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = picked.isNotEmpty() || own.isNotBlank(), reason = "Choose or type an answer")
            }
        }
    }
}

@Composable
private fun ProblemCard(message: String, connection: Boolean, onFix: () -> Unit) {
    val c = Mu.colors
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mono("✕", color = c.ink)
            Small(message, color = c.ink)
        }
        if (connection) MuButton("Fix the connection", onFix, variant = BtnVariant.SECONDARY, size = BtnSize.SM, arrow = true)
    }
}

/**
 * Where the person writes: Enter sends, Shift Enter breaks the line; Stop while Ai is
 * answering. The draft is [AiState.draft], so it survives the panel being closed.
 */
@Composable
fun Composer(ai: AiState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val focus = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val style = MuType.row(f).copy(color = c.ink)
    LaunchedEffect(ai.focusTick) { if (ai.focusTick > 0) runCatching { focus.requestFocus() } }
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp, max = 160.dp)
                .border(1.dp, animatedColor(if (focused) c.ink else c.ink25))
                .cursor(CursorMode.TEXT, fontSize = style.fontSize, singleLine = false, focused = focused)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (ai.draft.isEmpty()) MuText("Ask ${ai.name}…", style = style, color = c.ink45)
            BasicTextField(
                value = ai.draft,
                onValueChange = { ai.draft = it },
                textStyle = style,
                cursorBrush = SolidColor(c.ink),
                interactionSource = source,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .reportsTextFocus()
                    .onPreviewKeyEvent { e ->
                        val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                        if (enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) {
                            ai.send(ai.draft)
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Help(if (ai.running) "${ai.name} is answering" else "Enter sends · Shift Enter for a new line", maxLines = 1)
            }
            if (ai.running) {
                MuButton("Stop", ai::stop, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
            } else {
                MuButton("Send", { ai.send(ai.draft) }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = ai.draft.isNotBlank(), reason = "Write something first")
            }
        }
    }
}

/** The past conversations, newest first: open one, or delete it. */
@Composable
fun SessionList(ai: AiState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val sessions = remember(ai.historyOpen) { ai.files.sessions() }
    if (sessions.isEmpty()) {
        Box(modifier.padding(16.dp)) { Small("No conversations yet.", color = c.ink70) }
        return
    }
    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        items(sessions, key = { it.id }) { s ->
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(animatedColor(if (hovered || s.id == ai.session?.id) c.ink06 else androidx.compose.ui.graphics.Color.Transparent))
                    .hoverable(source)
                    .cursorPointer(caption = "Open")
                    .muClickable(interactionSource = source) { ai.open(s.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Small(s.title.ifBlank { "Untitled" }, color = c.ink, maxLines = 1)
                    Mono(
                        java.time.Instant.ofEpochMilli(s.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() +
                            (if (s.mode == com.kaiharimoto.mastertool.core.ai.AiSession.MODE_TUNE) " · Fine Tuning" else "") +
                            " · ${s.turns.count { it.role == Role.USER && !it.isToolResults }} messages",
                        color = c.ink45,
                    )
                }
                com.kaiharimoto.neue.kit.IconButton(com.kaiharimoto.neue.kit.Icons.Trash, { ai.delete(s.id) }, size = 28.dp, label = "Delete")
            }
        }
    }
}
