package com.kaiharimoto.neue.ai

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.composed
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
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
import androidx.compose.ui.text.withLink
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
    data class Person(val text: String, val images: List<Part.Image> = emptyList()) : Entry
    data class Reply(val text: String, val check: com.kaiharimoto.mastertool.core.ai.check.FactCheck.Check? = null) : Entry
    data class Line(val summary: String, val isError: Boolean) : Entry
    data class Thought(val text: String) : Entry

    /** Where the summary begins (1.0.56): the turns above it are sent as the summary, not as themselves. */
    data class Summarized(val summary: String, val carried: Boolean) : Entry
}

/** The conversation as rows: the person's words, Ai's words, and a line for each thing Ai did. */
private fun rows(
    turns: List<ChatTurn>,
    summarized: Int = 0,
    summary: String = "",
    carried: Boolean = false,
    checks: List<com.kaiharimoto.mastertool.core.ai.check.FactCheck.Check> = emptyList(),
): List<Entry> = buildList {
    if (carried && summary.isNotBlank()) add(Entry.Summarized(summary, carried = true))
    turns.forEachIndexed { i, turn ->
        if (i == summarized && summarized > 0 && summary.isNotBlank()) add(Entry.Summarized(summary, carried = false))
        when {
            turn.role == Role.USER && turn.isToolResults -> turn.toolResults.forEach { add(Entry.Line(it.summary.ifBlank { it.name }, it.isError)) }
            turn.role == Role.USER -> if (turn.text.isNotBlank() || turn.images.isNotEmpty()) add(Entry.Person(turn.text, turn.images))
            else -> {
                turn.parts.filterIsInstance<Part.Reasoning>().forEach { add(Entry.Thought(it.text)) }
                turn.parts.filterIsInstance<Part.Activity>().forEach { add(Entry.Line(if (it.summary.isNotBlank()) it.summary else it.name, it.isError)) }
                turn.text.takeIf { it.isNotBlank() }?.let { add(Entry.Reply(it, checks.lastOrNull { c -> c.turn == i })) }
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
    val rows = remember(session?.turns, session?.summarized, session?.summary, session?.checks) {
        rows(session?.turns.orEmpty(), session?.summarized ?: 0, session?.summary.orEmpty(), session?.carriedFrom != null, session?.checks.orEmpty())
    }
    val list = rememberLazyListState()
    val tail = rows.size + (if (ai.streaming.isNotEmpty()) 1 else 0) + (if (ai.reasoning.isNotEmpty()) 1 else 0) + ai.todos.size + (if (ai.confirm != null) 1 else 0) + (if (ai.question != null) 1 else 0) + (if (ai.problem != null) 1 else 0) + ai.activity.size
    // It keeps up with Ai only while the reader is at the end (1.0.61, `ChatFollow`): a scroll the
    // reader lets go of elsewhere is left alone, and the end is the end, not the newest item's top.
    val follow = remember(session?.id) { com.kaiharimoto.mastertool.core.ai.text.ChatFollow() }
    // Which thoughts are open, by their start: one watched open as it streamed stays open when it is
    // filed after the step, or the conversation shrinks under whoever is reading it (1.0.61).
    val opened = remember(session?.id) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val ours = remember { booleanArrayOf(false) }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.isScrollInProgress }.collect { moving ->
            if (!moving && !ours[0]) follow.readerScrolled(atEnd = !list.canScrollForward)
        }
    }
    LaunchedEffect(ai.running) { if (ai.running) follow.sent() }
    LaunchedEffect(tail, ai.streaming.length / 80, ai.reasoning.length / 200) {
        val last = list.layoutInfo.totalItemsCount - 1
        if (last < 0 || !follow.shouldFollow(readerScrolling = list.isScrollInProgress)) return@LaunchedEffect
        ours[0] = true
        try {
            // A large offset past the last item's top: the list stops at its true end.
            list.scrollToItem(last, 1_000_000)
        } finally {
            ours[0] = false
        }
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
                    is Entry.Person -> PersonSays(ai, row.text, row.images)
                    is Entry.Reply -> {
                        ReplyView(ai, row.text)
                        row.check?.let { CheckLine(it) }
                    }
                    is Entry.Line -> ActivityLine(row.summary, row.isError)
                    is Entry.Thought -> ReasoningView(ai, row.text, live = false, opened)
                    is Entry.Summarized -> SummaryMark(row.summary, row.carried)
                }
            }
            if (ai.reasoning.isNotBlank()) item { ReasoningView(ai, ai.reasoning, live = true, opened) }
            if (ai.todos.isNotEmpty()) item { TodoView(ai.todos) }
            items(ai.activity) { ActivityLine(it.summary.ifBlank { it.name }, it.isError) }
            if (ai.streaming.isNotEmpty()) item { ReplyView(ai, ai.streaming, live = true) }
            ai.confirm?.let { c -> item { ConfirmCard(c) } }
            ai.question?.let { q -> item { QuestionCard(ai, q) } }
            ai.problem?.let { (message, connection) -> item { ProblemCard(message, connection) { ai.openWizard() } } }
            ai.notice?.takeIf { !ai.running }?.let { n -> item { ActivityLine(n, isError = false) } }
            if (ai.videoKeyAsked && !ai.running) item { VideoKeyCard(ai) }
        }
    }
}

@Composable
private fun Greeting(ai: AiState, modifier: Modifier) {
    val c = Mu.colors
    // The first time the conversation opens empty, Ai winks hello (1.0.52).
    LaunchedEffect(Unit) {
        if (!ai.greeted) {
            ai.greeted = true
            ai.express(com.kaiharimoto.mastertool.core.ai.avatar.Expression.WINK, 3)
        }
    }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        com.kaiharimoto.neue.ai.avatar.AiAvatar(
            ai.face,
            com.kaiharimoto.neue.ai.avatar.AvatarSizes.greeting,
            pointer = { ai.h.cursor.position },
            name = ai.name,
        )
        MuText(ai.name, style = MuType.h1(LocalMuFonts.current), color = c.ink)
        Small(
            "Ask me anything about the game, or have me do it: build a deck, tune the one that is open, sort it into groups, " +
                "write a siding plan, read the latest tournament results, change a setting. I remember what you tell me.",
            color = c.ink70,
        )
        Suggestions(ai)
        com.kaiharimoto.neue.kit.MicroLink("What can you do? →", { ai.demoOpen = true })
    }
}

/** What to ask Ai now: the Greeting's suggestions, and the bar's marquee when it is idle. */
internal fun ideas(ai: AiState): List<String> {
    val deck = ai.h.builder.deck
    return buildList {
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(ai: AiState) {
    val ideas = ideas(ai)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ideas.forEach { idea -> Tag(idea, selected = false, onClick = { ai.draft = idea; ai.focusTick++ }, caption = "Ask") }
    }
}

@Composable
private fun PersonSays(ai: AiState, text: String, images: List<Part.Image>) {
    val c = Mu.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // What they showed (1.0.55), above what they said, as they sent it.
        if (images.isNotEmpty()) SentPictures(ai, images)
        if (text.isNotBlank()) {
            Box(Modifier.fillMaxWidth(0.88f).background(c.ink06).padding(horizontal = 12.dp, vertical = 8.dp)) {
                SelectionContainer { MuText(text, style = MuType.row(LocalMuFonts.current), color = c.ink) }
            }
        }
    }
}

/**
 * An answer's check (1.0.58): one line under it — how many claims were checked against the card
 * text, how many could not be confirmed, or that one was wrong and is corrected below — which
 * opens to every claim, its verdict and where it was checked.
 */
@Composable
private fun CheckLine(check: com.kaiharimoto.mastertool.core.ai.check.FactCheck.Check) {
    val c = Mu.colors
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val wrong = check.wrong.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .hoverable(source)
                .cursorPointer(caption = if (open) "Hide" else "Show")
                .muClickable(interactionSource = source) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Mono(if (wrong) "✕" else if (check.unsure.isNotEmpty()) "?" else "✓", color = if (wrong) c.ink else c.ink70)
            Mono(com.kaiharimoto.mastertool.core.ai.check.FactCheck.summary(check), color = animatedColor(if (hovered || wrong) c.ink else c.ink45))
        }
        if (open) {
            Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                check.claims.forEach { claim ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mono(
                            when (claim.verdict) {
                                com.kaiharimoto.mastertool.core.ai.check.FactCheck.Verdict.OK -> "✓"
                                com.kaiharimoto.mastertool.core.ai.check.FactCheck.Verdict.WRONG -> "✕"
                                com.kaiharimoto.mastertool.core.ai.check.FactCheck.Verdict.UNSURE -> "?"
                            },
                            color = c.ink,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Small(claim.claim, color = c.ink)
                            if (claim.correction.isNotBlank()) Small("In fact: ${claim.correction}", color = c.ink70)
                            if (claim.source.isNotBlank()) Mono(claim.source, color = c.ink45)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where the summary begins (1.0.56): a rule and a line saying what is above it is sent as a
 * summary now; a click shows the summary itself.
 */
@Composable
private fun SummaryMark(summary: String, carried: Boolean) {
    val c = Mu.colors
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .hoverable(source)
                .cursorPointer(caption = if (open) "Hide" else "Show")
                .muClickable(interactionSource = source) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(c.ink25))
            Mono(
                (if (carried) "Carried on from an earlier conversation" else "Above: summarised to fit") + if (open) " · Hide the summary" else " · Show the summary",
                color = animatedColor(if (hovered) c.ink else c.ink45),
            )
            Box(Modifier.weight(1f).height(1.dp).background(c.ink25))
        }
        if (open) {
            Box(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(10.dp)) {
                SelectionContainer { MuText(summary, style = MuType.row(LocalMuFonts.current), color = c.ink70) }
            }
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

/** Ai's words: its markdown in the app's type, and a chip for each card it named. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyView(ai: AiState, text: String, live: Boolean = false) {
    val c = Mu.colors
    val blocks = remember(text, live) { ChatMarkdown.parse(text, streaming = live) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.kaiharimoto.neue.ai.avatar.AiName(ai.name, c.ink45)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { blocks.forEach { MarkdownBlock(ai, it) } }
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

/**
 * What a card named in a reply's words does when it is clicked (1.0.55): opens it large. Given by
 * [MarkdownBlock], so every block's words — paragraphs, lists, tables, a combo's steps — link alike.
 */
internal val LocalCardLink = androidx.compose.runtime.compositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun MarkdownBlock(ai: AiState, block: Block) {
    val open: (String) -> Unit = remember(ai) { { name -> cardNamed(ai, name)?.let { ai.h.neue.viewing = Viewing(it, null, 0) } } }
    androidx.compose.runtime.CompositionLocalProvider(LocalCardLink provides open) { BlockBody(ai, block) }
}

@Composable
private fun BlockBody(ai: AiState, block: Block) {
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
        is Block.Chart -> ChartBlock(block.chart)
        is Block.Cards -> CardsBlock(ai, block)
        is Block.Deck -> DeckBlock(ai, block)
        is Block.Compare -> CompareBlock(ai, block)
        is Block.Line -> LineBlock(ai, block)
        is Block.Board -> BoardBlock(ai, block)
        is Block.Pending -> PendingBlock(block)
        is Block.Quote -> Row(
            Modifier.drawBehind { drawLine(c.ink25, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }.padding(start = 10.dp),
        ) { MuText(styled(block.inlines), style = MuType.row(f), color = c.ink70) }
        is Block.Table -> TableBlock(block) { styled(it) }
        Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).drawBehind { drawLine(c.ink12, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) })
    }
}

@Composable
internal fun styled(inlines: List<Inline>): AnnotatedString {
    val f = LocalMuFonts.current
    val mono = MuType.mono(f, 12.sp).fontFamily
    val link = LocalCardLink.current
    val card = SpanStyle(fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
    return buildAnnotatedString {
        inlines.forEach { i ->
            when (i) {
                is Inline.Text -> append(i.text)
                is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(i.text) }
                is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(i.text) }
                is Inline.Code -> withStyle(SpanStyle(fontFamily = mono)) { append(i.text) }
                is Inline.Card -> if (link != null) {
                    // A card in the words opens large on a click (1.0.55); it was only underlined.
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Clickable(
                            "card:${i.name}",
                            androidx.compose.ui.text.TextLinkStyles(style = card, hoveredStyle = card.copy(fontWeight = FontWeight.Bold)),
                        ) { link(i.name) },
                    ) { append(i.name) }
                } else {
                    withStyle(card) { append(i.name) }
                }
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
private fun QuestionCard(ai: AiState, q: Question) {
    val c = Mu.colors
    // Kept on the question itself (1.0.63), so the row can be rebuilt without losing a word.
    var picked by q::picked
    var own by q::typed
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // What it heard, before it asks whether it heard right (1.0.65).
        if (q.heard.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(c.ink06).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Micro("What I heard", color = c.ink45)
                q.heard.forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mono("–", color = c.ink45)
                        MuText(styled(ChatMarkdown.inline(point)), style = MuType.small(LocalMuFonts.current), color = c.ink)
                    }
                }
            }
        }
        // The cards it asks about, as their art (1.0.48): a question about a card shows the card.
        if (q.cards.isNotEmpty()) {
            CardsBlock(ai, com.kaiharimoto.mastertool.core.ai.text.Block.Cards(q.cards.map { com.kaiharimoto.mastertool.core.ai.text.CardLine(1, it.name) }))
        }
        MuText(styled(ChatMarkdown.inline(q.question)), style = MuType.row(LocalMuFonts.current).copy(fontWeight = FontWeight.Medium), color = c.ink)
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
fun Composer(ai: AiState, modifier: Modifier = Modifier, phone: Boolean = false) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val focus = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val style = MuType.row(f).copy(color = c.ink)
    LaunchedEffect(ai.focusTick) { if (ai.focusTick > 0) runCatching { focus.requestFocus() } }
    val pasteScope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FaceStrip(ai, phone)
        AttachedRow(ai)
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
                        val paste = e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed) && e.type == KeyEventType.KeyDown
                        when {
                            enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed -> {
                                ai.send(ai.draft)
                                true
                            }
                            // A copied picture goes with the message (1.0.55); copied words paste as ever.
                            paste && com.kaiharimoto.neue.platform.clipboardHasPicture() -> {
                                pasteScope.launch { com.kaiharimoto.neue.platform.pastedPicture()?.let(ai::attach) }
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A picture: chosen here, pasted, or dropped anywhere on the panel (1.0.55).
            com.kaiharimoto.neue.kit.Tip("Attach a picture — or paste one, or drop it on the panel", above = true) {
                com.kaiharimoto.neue.kit.IconButton(
                    com.kaiharimoto.neue.kit.Icons.Image,
                    { pasteScope.launch { choosePicture(ai) } },
                    label = "Attach",
                )
            }
            // Speak instead of typing (1.0.57): the words go into the box to read over; talk mode sends them and answers aloud.
            com.kaiharimoto.neue.kit.Tip(
                if (ai.hearing) "Stop listening" else "Speak to ${ai.name}",
                kbd = com.kaiharimoto.mastertool.core.input.DeskShortcuts.chordFor(com.kaiharimoto.mastertool.core.input.DeskAction.AI_VOICE)?.let(com.kaiharimoto.mastertool.core.input.DeskShortcuts::kbd),
                above = true,
            ) {
                com.kaiharimoto.neue.kit.IconButton(
                    com.kaiharimoto.neue.kit.Icons.Mic,
                    { ai.toggleVoice() },
                    toggled = ai.hearing || ai.transcribing,
                    label = if (ai.hearing) "Stop" else "Speak",
                    enabled = !ai.talkMode && ai.voiceDownload == null,
                    reason = if (ai.talkMode) "Talk mode is listening" else "The speech model is downloading",
                )
            }
            com.kaiharimoto.neue.kit.Tip(if (ai.talkMode) "End talk mode" else "Talk mode: a conversation out loud — speak, hear the answer, speak again", above = true) {
                com.kaiharimoto.neue.kit.IconButton(
                    com.kaiharimoto.neue.kit.Icons.AudioLines,
                    { ai.toggleTalk() },
                    toggled = ai.talkMode,
                    label = if (ai.talkMode) "End" else "Talk",
                )
            }
            // A phone's or a tablet's camera: a photo of a paper decklist or a board, straight to Ai.
            if (com.kaiharimoto.neue.platform.Platform.canTakePhoto) {
                com.kaiharimoto.neue.kit.Tip("Take a photo for ${ai.name} to see", above = true) {
                    com.kaiharimoto.neue.kit.IconButton(
                        com.kaiharimoto.neue.kit.Icons.Camera,
                        { pasteScope.launch { com.kaiharimoto.neue.platform.Platform.takePhoto()?.let(ai::attach) } },
                        label = "Photo",
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    ai.hearing -> VoiceMeter(ai)
                    ai.voiceDownload != null -> Help("Downloading the speech model · ${((ai.voiceDownload ?: 0f) * 100).toInt()}%", maxLines = 1)
                    ai.talkMode -> Help(if (ai.aloud) "Talk mode · speaking · the talk button ends it" else "Talk mode · the talk button ends it", maxLines = 1)
                    else -> Help(if (ai.running) "${ai.name} is answering" else if (phone) "Enter sends" else "Enter sends · Shift Enter for a new line", maxLines = 1)
                }
            }
            if (ai.running) {
                MuButton("Stop", ai::stop, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
            } else {
                MuButton("Send", { ai.send(ai.draft) }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = ai.draft.isNotBlank() || ai.attached.isNotEmpty(), reason = "Write something first")
            }
        }
    }
}

/** How loud the microphone is, as it listens (1.0.57): a word and a bar that follows the voice. */
@Composable
private fun VoiceMeter(ai: AiState) {
    val c = Mu.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mono(if (ai.talkMode) "Talk mode · listening" else "Listening · pause to finish", color = c.ink)
        Box(Modifier.weight(1f).height(4.dp).background(c.ink12)) {
            Box(Modifier.fillMaxWidth((ai.voiceLevel * 6f).coerceIn(0.02f, 1f)).height(4.dp).background(c.ink))
        }
    }
}

/**
 * Ai at the chat box (1.0.52, kai: "closer to the chat box like Claude and Grok Bot when
 * it thinks and displays its status"): its face on the composer's top edge, and beside it
 * its name and what it is doing — the tool's line while it works, Thinking, Writing,
 * Waiting on you — or, at rest, the kaomoji of the face it is wearing.
 */
@Composable
private fun FaceStrip(ai: AiState, phone: Boolean) {
    val c = Mu.colors
    val face = ai.face
    val status = when {
        ai.checking && !ai.running -> "Checking its answer against the card text"
        ai.hearing -> "Listening"
        ai.transcribing -> "Writing down what you said"
        ai.aloud -> "Speaking"
        ai.confirm != null || ai.question != null -> "Waiting on you"
        ai.running -> ai.working ?: ai.status ?: when {
            ai.streaming.isNotEmpty() -> "Writing"
            ai.studying -> "Studying the deck"
            ai.refactoring -> "Refactoring the guide"
            else -> "Thinking"
        }
        ai.handLine != null -> ai.handLine
        ai.problem != null -> "Could not finish"
        face == com.kaiharimoto.mastertool.core.ai.avatar.Expression.DONE -> "Done"
        face == com.kaiharimoto.mastertool.core.ai.avatar.Expression.SAD -> "Stopped"
        face == com.kaiharimoto.mastertool.core.ai.avatar.Expression.SLEEPING -> "Asleep"
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        com.kaiharimoto.neue.ai.avatar.AiAvatar(
            face,
            if (phone) com.kaiharimoto.neue.ai.avatar.AvatarSizes.composerPhone else com.kaiharimoto.neue.ai.avatar.AvatarSizes.composer,
            modifier = Modifier.avatarHand(ai),
            pointer = { ai.h.cursor.position },
            name = ai.name,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Micro(ai.name, color = c.ink)
                androidx.compose.animation.Crossfade(face.kaomoji, animationSpec = androidx.compose.animation.core.tween(com.kaiharimoto.neue.theme.MuMotion.FAST), label = "kaomoji") {
                    Mono(it, color = c.ink45)
                }
            }
            androidx.compose.animation.Crossfade(status, animationSpec = androidx.compose.animation.core.tween(com.kaiharimoto.neue.theme.MuMotion.FAST), label = "status") { line ->
                if (line != null) MuText(line, style = MuType.small(LocalMuFonts.current), color = c.ink70, maxLines = 1)
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

/**
 * What Ai thought on its way to an answer (1.0.47, kai: "the AI should think out loud at
 * least partially so the user can also learn with it"): a faint block with a rule down its
 * side, folded to its first lines unless the person opens it (Settings → Assistant →
 * Reasoning), open while it streams so it can be followed as it goes.
 */
@Composable
private fun ReasoningView(ai: AiState, text: String, live: Boolean, opened: MutableMap<String, Boolean>) {
    val c = Mu.colors
    val how = ai.prefs.showReasoning
    if (how == com.kaiharimoto.mastertool.core.prefs.AiPrefs.REASONING_HIDDEN) return
    val startsOpen = live || how == com.kaiharimoto.mastertool.core.prefs.AiPrefs.REASONING_OPEN || ai.tuning
    val key = text.trim().take(200)
    if (live) androidx.compose.runtime.SideEffect { if (key !in opened) opened[key] = true }
    val open = opened[key] ?: startsOpen
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink12, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }
            .padding(start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier
                .hoverable(source)
                .cursorPointer(caption = if (open) "Fold" else "Read")
                .muClickable(interactionSource = source) { opened[key] = !open },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Micro(if (live) "Thinking" else "How it thought", color = c.ink45)
            Mono(if (open) "−" else "+", color = c.ink45)
        }
        val shown = if (open) text.trim() else text.trim().lineSequence().filter { it.isNotBlank() }.take(2).joinToString("\n") { it.take(160) }
        MuText(shown, style = MuType.small(LocalMuFonts.current), color = c.ink45, maxLines = if (open) Int.MAX_VALUE else 3)
    }
}

/** Ai's plan for the job in hand, as it works through it (`todo_write`, 1.0.47). */
@Composable
private fun TodoView(items: List<String>) {
    val c = Mu.colors
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro("Plan", color = c.ink45)
        items.forEach { raw ->
            val t = raw.trim()
            val (mark, words) = when {
                t.startsWith("[x]", ignoreCase = true) -> "✓" to t.drop(3).trim()
                t.startsWith("[>]") -> "→" to t.drop(3).trim()
                t.startsWith("[ ]") -> "·" to t.drop(3).trim()
                else -> "·" to t
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono(mark, color = if (mark == "→") c.ink else c.ink45)
                Small(words, color = when (mark) { "✓" -> c.ink45; "→" -> c.ink; else -> c.ink70 })
            }
        }
    }
}

/**
 * The face answers a hand (1.0.54, kai: "let the user interact with the Ai avatar in various ways
 * to make it feel like it's really living and there"): a tap, a double tap, poking, a press held,
 * the pointer or a finger stroked back and forth (petting), and the pointer left resting on it —
 * each read by `AvatarPlay` (core) into a face and a line. Nothing moves but the face itself.
 */
private fun Modifier.avatarHand(ai: AiState): Modifier = composed {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var hovering by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var dwellAnswered by remember { mutableStateOf(false) }
    // Resting on it a while: shy of being looked at, once a visit.
    androidx.compose.runtime.LaunchedEffect(hovering) {
        if (!hovering) {
            dwellAnswered = false
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay((com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay.DWELL * 1000).toLong())
        if (hovering && !pressed && !dwellAnswered) {
            dwellAnswered = true
            ai.touched(ai.play.dwell(com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay.DWELL))
        }
    }
    this
        .cursorPointer(caption = "Pet")
        .pointerInput(ai) {
            // Every move over the face, hovering or dragged: back and forth is petting.
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    when (e.type) {
                        androidx.compose.ui.input.pointer.PointerEventType.Enter -> hovering = true
                        androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovering = false
                        androidx.compose.ui.input.pointer.PointerEventType.Move -> e.changes.firstOrNull()?.let { ch ->
                            val dx = ch.position.x - ch.previousPosition.x
                            if (dx != 0f) ai.touched(ai.play.stroke(dx / density, ai.clock(), ai.mood.sleeping))
                        }
                        else -> Unit
                    }
                }
            }
        }
        .pointerInput(ai) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { ai.touched(ai.play.tap(ai.clock(), ai.mood.sleeping)) },
                onDoubleTap = { ai.touched(ai.play.doubleTap(ai.clock())) },
                onLongPress = {
                    ai.touched(ai.play.hold(longer = false))
                    scope.launch {
                        kotlinx.coroutines.delay(((com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay.HOLD_LONGER - com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay.HOLD) * 1000).toLong())
                        if (pressed) ai.touched(ai.play.hold(longer = true))
                    }
                },
            )
        }
}
