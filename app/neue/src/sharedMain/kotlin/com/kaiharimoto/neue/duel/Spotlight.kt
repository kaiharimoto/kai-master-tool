package com.kaiharimoto.neue.duel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.text.DuelAnswer
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.Spotlight
import com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode
import com.kaiharimoto.mastertool.core.duel.text.Spotlight.RowKind
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import com.kaiharimoto.mastertool.core.layout.CardFrame
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.Slot
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.LocalHardwareKeyboard
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuIcon
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.delay

/**
 * Command mode's Spotlight (1.0.87, kai chose it from the design canvas, direction C): a big box over the table where
 * the duel is typed — or spoken, holding M — a line at a time, "like Magnus Carlsen". Its arithmetic is core's
 * ([Spotlight]); this draws it, in paper and ink: a 2 dp ink frame, the line in JetBrains Mono, the results as whole
 * sentences with their consequence and coordinates, the chosen one ink-filled. It opens in the window's own layer —
 * never a `Popup` — so the family cursor stays over it, and the table dims behind it ([SpotlightDim]) but for what
 * the line touches.
 *
 * Opened by `/`, `Ctrl L`, any letter that is no duel key (that letter typed in), the bar's "Type a command", a
 * right-click on the empty table, and holding M (listening). Enter makes the chosen row and closes; Shift Enter makes
 * it and keeps the box; Tab takes a row into the line; ↑ on an empty box walks the lines made before; 1–3 pick a
 * "did you mean"; Ctrl Enter says the words in the chat; Esc closes.
 */
@Composable
internal fun SpotlightLayer(h: NeueHolders, game: DuelGame, phone: Boolean) {
    val duels = h.duel
    val st = duels.spotlight ?: return
    val c = Mu.colors
    val s = game.state
    val view = remember(st.text, st.cursor, game, duels.bottom, duels.lineHistory, duels.catalog) { spotView(duels, st, game) }
    val rows = view.choosable
    val chosen = st.chosen.coerceAtMost(rows.size - 1)
    // What the table keeps lit: the chosen row's cards and where they go (else the line's own).
    val marked = rows.getOrNull(chosen)?.line ?: st.text
    val marks = remember(marked, s, duels.bottom, st.mode) {
        if (marked.isBlank() || st.mode == Mode.LISTENING) null
        else DuelCommand.preview(marked, s, duels.bottom, duels.catalog, game.header.seed).let { SpotMarks(it.touched, it.dest) }
    }
    SideEffect { duels.spotlightMarks = marks }
    val voice = h.duelVoice
    DisposableEffect(Unit) {
        onDispose {
            duels.spotlightMarks = null
            // Esc while speaking (or the box closed any other way): what was being said is dropped, never made (the red team).
            voice.cancel()
        }
    }
    // Listening ended without words (let go too soon, the microphone taken back): the box goes back to typing.
    LaunchedEffect(voice.phase, st.mode) {
        if (st.mode == Mode.LISTENING && (voice.phase == DuelVoice.Phase.IDLE || voice.phase == DuelVoice.Phase.FAILED) && !voice.held && duels.spotlightLevels == null) {
            delay(250)
            val now = duels.spotlight
            if (now != null && now.mode == Mode.LISTENING && !voice.busy) duels.spotlight = now.copy(mode = Mode.TYPING, problem = voice.failure)
        }
    }

    var boxHeight by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(SPOT_Z)) {
        // A press anywhere else closes the box, and is spent on closing (as a menu's layer is).
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Press) {
                            e.changes.forEach { it.consume() }
                            duels.closeSpotlight()
                        }
                    }
                }
            },
        )
        val width = if (phone) maxWidth - 16.dp else minOf(760.dp, maxWidth - 32.dp)
        // Over the table, where it hides none of what the line touches: high by default, at the very top or the foot
        // when the cards lit or the places marked would be under it (an attack's target is in their field).
        val spans = remember(marks, s, duels.tableLayout) { markSpans(marks, s, duels.tableLayout) }
        val top = if (phone) 8.dp else {
            val h = boxHeight.coerceAtLeast(120f)
            val all = maxHeight.value
            listOf(all * 0.12f, 16f, all - h - 16f).filter { it >= 0f }
                .minByOrNull { t -> spans.sumOf { (a, b) -> (minOf(b, t + h) - maxOf(a, t)).coerceAtLeast(0f).toDouble() } }
                ?.dp ?: 16.dp
        }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = top)
                .onSizeChanged { boxHeight = it.height / density }
                .width(width)
                .heightIn(max = maxHeight - top - 16.dp)
                .background(c.paper)
                .border(2.dp, c.ink)
                // Presses inside are the box's own.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { if (it.pressed) it.consume() } } },
        ) {
            st.heard?.takeIf { st.mode != Mode.LISTENING }?.let { Heard(it) }
            Header(h, st, view, phone)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink))
            Body(h, st, view, chosen, game)
            Footer(st, view, phone)
        }
    }
}

/** The box's view of its line on this table, with the battle an attack just declared comes to (offered on an empty box). */
private fun spotView(duels: Duels, st: Spotlight.State, game: DuelGame): Spotlight.View = Spotlight.view(
    st.text, st.cursor, game.state, duels.bottom, duels.catalog, duels.lineHistory, game.header.seed,
    battle = DuelBattle.pending(game, duels.catalog)?.takeIf { it.attack.seat == duels.bottom || game.state.solo },
)

/** The vertical spans (dp, from the table's top) of what [marks] light: each card's place and each place marked. */
private fun markSpans(marks: SpotMarks?, s: DuelState, l: DuelLayout?): List<Pair<Float, Float>> {
    if (marks == null || l == null) return emptyList()
    val places = marks.touched.mapNotNull { s.placeOf(it) } + marks.dest
    return places.mapNotNull { placeBox(l, it) }.map { it.top - 8f to it.bottom + 8f }
}

/** The cards the line touches and where they go, for [SpotlightDim]. */
internal data class SpotMarks(val touched: Set<Int>, val dest: List<Place>)

/** The words as the transcriber wrote them, faint and italic over the line they became. */
@Composable
private fun Heard(heard: String) {
    val c = Mu.colors
    MuText(
        "“$heard”",
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
        style = MuType.small(LocalMuFonts.current).copy(fontStyle = FontStyle.Italic),
        color = c.ink45,
        maxLines = 2,
    )
}

/** DO or ASK, the line in JetBrains Mono, the microphone and Esc. */
@Composable
private fun Header(h: NeueHolders, st: Spotlight.State, view: Spotlight.View, phone: Boolean) {
    val c = Mu.colors
    val duels = h.duel
    val f = LocalMuFonts.current
    val focus = remember { FocusRequester() }
    val asked = remember { duels.spotlightFocus }
    LaunchedEffect(duels.spotlightFocus) { if (duels.spotlightFocus >= asked) runCatching { focus.requestFocus() } }
    // The field lets go of the keyboard as the box closes, so M and the arrows are the table's again (the red team).
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    DisposableEffect(Unit) { onDispose { runCatching { focusManager.clearFocus() } } }
    var field by remember { mutableStateOf(TextFieldValue(st.text, TextRange(st.cursor))) }
    val shown = if (field.text == st.text) field else TextFieldValue(st.text, TextRange(st.cursor))
    val style = MuType.mono(f, if (phone) 20.sp else 26.sp).copy(fontWeight = FontWeight.Bold, color = c.ink)
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(34.dp)) { Micro(view.label.lowercase(), color = c.ink45, size = 11.sp) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (st.text.isEmpty()) {
                MuText(
                    when (st.mode) {
                        Mode.LISTENING -> "Listening…"
                        else -> "Type a move, or ask"
                    },
                    style = style.copy(fontWeight = FontWeight.Normal),
                    color = c.ink25,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = shown,
                onValueChange = { v ->
                    // The keystroke that opened the box may type its own letter a moment later: dropped once.
                    val seed = duels.spotlightSeed
                    duels.spotlightSeed = null
                    val now = duels.spotlight ?: return@BasicTextField
                    if (seed != null && Duels.now() - seed.third < SEED_MS && v.text == seed.first + seed.second) {
                        field = TextFieldValue(seed.first, TextRange(seed.first.length))
                        return@BasicTextField
                    }
                    field = v
                    duels.spotlight = if (v.text != now.text) now.typed(v.text, v.selection.start) else now.copy(cursor = v.selection.start)
                },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onAny = { spotEnter(h, keep = false) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { duels.spotlightTyping = it.isFocused }
                    .reportsTextFocus()
                    .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && spotKey(h, e.key, e.isShiftPressed, e.isCtrlPressed || e.isMetaPressed, e.isAltPressed) },
            )
        }
        DuelMic(h, size = 28.dp)
        if (LocalHardwareKeyboard.current) {
            Box(Modifier.cursorPointer(caption = "Close").muClickable { duels.closeSpotlight() }) { Kbd("Esc") }
        } else {
            Box(Modifier.size(28.dp).border(1.dp, c.ink25).cursorPointer(caption = "Close").muClickable { duels.closeSpotlight() }, contentAlignment = Alignment.Center) {
                MuIcon(Icons.X, c.ink70, Modifier.size(14.dp))
            }
        }
    }
}

/** The results, or what the box is doing: listening, an answer, recent lines and lines to try. */
@Composable
private fun Body(h: NeueHolders, st: Spotlight.State, view: Spotlight.View, chosen: Int, game: DuelGame) {
    val c = Mu.colors
    val duels = h.duel
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when {
            st.mode == Mode.LISTENING -> Listening(h, st)
            st.mode == Mode.ANSWER && st.answer != null -> Answer(st.answer.orEmpty())
            view.answer != null -> Answer(view.answer.orEmpty())
            st.text.isBlank() -> {
                if (view.recent.isNotEmpty()) {
                    Section("Recent")
                    view.recent.forEach { line ->
                        Row(
                            Modifier.fillMaxWidth().cursorPointer(caption = "Put it in the line").muClickable { duels.spotlight = st.typed(line, line.length) }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Mono("↑", color = c.ink25, size = 11.sp)
                            Mono(line, color = c.ink70, size = 13.sp)
                        }
                    }
                }
                if (view.tries.isNotEmpty()) {
                    Section("Try")
                    view.tries.forEachIndexed { i, row -> ResultRow(h, row, i == chosen, game) { spotChoose(h, i, make = true) } }
                }
                if (view.recent.isEmpty() && view.tries.isEmpty()) Small("Type a move, a question, or hold M and say it.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = c.ink45)
            }
            else -> {
                val problem = st.problem ?: view.problem
                if (problem != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(2.dp).height(18.dp).background(if (st.problem != null) c.ink else c.ink25))
                        Small(problem, color = if (st.problem != null) c.ink else c.ink70, maxLines = 3)
                    }
                }
                if (view.fixes.isNotEmpty()) Section("Did you mean")
                view.rows.forEachIndexed { i, row ->
                    if (row.kind == RowKind.COMPLETE && i > 0 && view.rows[i - 1].kind != RowKind.COMPLETE) Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(c.ink12))
                    ResultRow(h, row, i == chosen, game) { spotChoose(h, i, make = row.makes) }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Micro(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp), color = Mu.colors.ink45)
}

/** One result: its number, the card's art, the sentence and what it comes to, the coordinates at the right. */
@Composable
private fun ResultRow(h: NeueHolders, row: Spotlight.Row, chosen: Boolean, game: DuelGame, onClick: () -> Unit) {
    Inverted(on = chosen) {
        val c = Mu.colors
        Row(
            Modifier.fillMaxWidth().background(c.paper)
                .cursorPointer(caption = if (row.makes) "Make it" else "Take it")
                .muClickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (row.number != null) Box(Modifier.width(14.dp)) { Mono("${row.number}", color = c.ink45, size = 12.sp) }
            ArtBox(h, row.uid, game.state)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RowText(row.words, color = c.ink, maxLines = 2)
                row.consequence?.let { Small(it, color = c.ink70, maxLines = 2) }
            }
            if (row.coords.isNotEmpty()) Mono(row.coords, color = c.ink70, size = 12.sp)
        }
    }
}

/** The card's own art when the seat may see it, its back when not, a plain box for a move with no card. */
@Composable
private fun ArtBox(h: NeueHolders, uid: Int?, s: DuelState) {
    val c = Mu.colors
    val m = Modifier.size(26.dp, 38.dp)
    val inst = uid?.let { s.cards[it] }
    val card = inst?.takeIf { Spotlight.seen(s, uid, h.duel.bottom) && !(it.token && it.code == 0) }?.let { h.builder.index.byId(CardId(it.code)) }
    when {
        card != null -> NeueCard(card, m, foil = "off")
        inst != null -> CardBack(m)
        else -> Box(m.border(1.dp, c.ink25))
    }
}

/** Listening (hold M): the microphone square in ink, the level as square bars, the words so far in italics. */
@Composable
private fun Listening(h: NeueHolders, st: Spotlight.State) {
    val c = Mu.colors
    val voice = h.duelVoice
    val duels = h.duel
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        while (true) {
            levels.add(voice.level)
            if (levels.size > BARS) levels.removeAt(0)
            delay(70)
        }
    }
    val shown = duels.spotlightLevels ?: levels
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(44.dp).background(c.ink), contentAlignment = Alignment.Center) { MuIcon(Icons.Mic, c.paper, Modifier.size(20.dp)) }
        Canvas(Modifier.width((BARS * 7).dp).height(28.dp)) {
            val w = 4.dp.toPx()
            val pitch = 7.dp.toPx()
            for (i in 0 until BARS) {
                val v = shown.getOrNull(i) ?: 0f
                val hgt = (size.height * (0.12f + (v * 6f).coerceIn(0f, 1f) * 0.88f))
                drawRect(c.ink, Offset(i * pitch, size.height - hgt), Size(w, hgt))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val words = voice.partial.ifBlank { st.heard ?: "" }
            if (words.isNotBlank()) MuText(words, style = MuType.body(LocalMuFonts.current).copy(fontStyle = FontStyle.Italic), color = c.ink, maxLines = 2)
            Small(if (voice.phase == DuelVoice.Phase.TRANSCRIBING) "Writing out what you said…" else "Let go to send", color = c.ink45)
        }
    }
}

/** A question answered, in words, through this seat's eyes. */
@Composable
private fun Answer(text: String) {
    val c = Mu.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(2.dp).height(20.dp).background(c.ink))
        // "Their field: om2: …; om3: …" reads as its head and a line a place.
        val head = text.substringBefore(": ", "")
        val items = if (head.isNotEmpty() && "; " in text) text.substringAfter(": ").removeSuffix(".").split("; ") else emptyList()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (items.isEmpty()) MuText(text, style = MuType.body(LocalMuFonts.current), color = c.ink, maxLines = 12)
            else {
                MuText("$head:", style = MuType.body(LocalMuFonts.current), color = c.ink70, maxLines = 1)
                items.forEach { item -> MuText(item, style = MuType.body(LocalMuFonts.current), color = c.ink, maxLines = 2) }
            }
        }
    }
}

/** The keys, on one line. */
@Composable
private fun Footer(st: Spotlight.State, view: Spotlight.View, phone: Boolean) {
    val c = Mu.colors
    val keys = LocalHardwareKeyboard.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!keys) {
            Small(if (st.mode == Mode.LISTENING) "Let go to send" else "Tap a row to make it · hold the microphone to speak", color = c.ink45)
        } else {
            val hints = when (st.mode) {
                Mode.LISTENING -> listOf("M" to "held, listening", "Esc" to "close")
                Mode.HEARD -> listOf("⏎" to "or say “yes” to make it", "Esc" to "or “no”")
                Mode.ANSWER -> listOf("⏎" to "close", "↑" to "lines made before")
                Mode.TYPING -> buildList {
                    add("↑↓" to "choose")
                    add("⏎" to "make it")
                    add("Tab" to "take")
                    if (view.fixes.isNotEmpty()) add("1–${view.fixes.size}" to "pick")
                }
            }
            hints.forEachIndexed { i, (k, words) ->
                if (i > 0) Small("·", color = c.ink25)
                Kbd(k)
                Small(words, color = c.ink70)
            }
            Box(Modifier.weight(1f))
            if (st.mode == Mode.TYPING && !phone) {
                Kbd("⇧⏎")
                Small("make it, keep typing", color = c.ink45)
            }
        }
    }
}

// ---- the table behind it ----------------------------------------------------------------------------------

/**
 * The table behind the Spotlight: paper over all of it at 82 %, so it reads at about 18 %, but for the cards the line
 * touches — lit, in the focus ring's look (2 dp of paper, then 2 dp of ink) — and the places it goes, marked by a 2 dp
 * dashed ink outline.
 */
@Composable
internal fun SpotlightDim(duels: Duels, s: DuelState, l: DuelLayout, frames: List<CardFrame>) {
    val c = Mu.colors
    val marks = duels.spotlightMarks
    val lit = marks?.touched?.mapNotNull { u -> frames.firstOrNull { it.uid == u && it.shown }?.let(::seenBox) } ?: emptyList()
    val dest = marks?.dest?.mapNotNull { placeBox(l, it) } ?: emptyList()
    Canvas(Modifier.fillMaxSize().zIndex(DIM_Z)) {
        val d = density
        fun Slot.rect(grow: Float) = androidx.compose.ui.geometry.Rect((left - grow) * d, (top - grow) * d, (right + grow) * d, (bottom + grow) * d)
        // The holes as one union (a card lit that is also a destination — an attack's target — is one hole, not two
        // that cancel), and the veil everywhere else.
        val holes = Path().apply { (lit + dest).forEach { addRect(it.rect(4f)) } }
        clipPath(holes, clipOp = ClipOp.Difference) { drawRect(c.paper.copy(alpha = 0.82f)) }
        lit.forEach { b ->
            val outer = b.rect(4f)
            drawRect(c.ink, outer.topLeft, outer.size, style = Stroke(2.dp.toPx()))
            val inner = b.rect(2f)
            drawRect(c.paper, inner.topLeft, inner.size, style = Stroke(2.dp.toPx()))
        }
        dest.forEach { b ->
            val r = b.rect(3f)
            drawRect(c.ink, r.topLeft, r.size, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
        }
    }
}

/** Where a place is drawn: a zone's frame, a pile's, a hand's band. Under a card is that card's place (the preview says so). */
private fun placeBox(l: DuelLayout, p: Place): Slot? = when (p) {
    is Place.Zone -> l.zone(if (p.kind == ZoneKind.EMZ) p.copy(seat = 0) else p)
    is Place.Pile -> l.pile(p.seat, p.kind)
    else -> null
}

// ---- what the keys do -----------------------------------------------------------------------------------

/** The box's own keys; false lets the field have the key (typing). */
private fun spotKey(h: NeueHolders, key: Key, shift: Boolean, ctrl: Boolean, alt: Boolean): Boolean {
    val duels = h.duel
    val st = duels.spotlight ?: return false
    val g = duels.shown ?: return false
    fun view() = spotView(duels, st, g)
    return when (key) {
        Key.Enter, Key.NumPadEnter -> {
            when {
                ctrl -> spotChat(h)
                else -> spotEnter(h, keep = shift)
            }
            true
        }
        Key.DirectionUp -> { duels.spotlight = Spotlight.up(st, view().choosable.size, duels.lineHistory); true }
        Key.DirectionDown -> { duels.spotlight = Spotlight.down(st, view().choosable.size, duels.lineHistory); true }
        Key.Tab -> { spotTake(h, view()); true }
        Key.Escape -> { duels.closeSpotlight(); true }
        Key.One, Key.Two, Key.Three -> {
            val n = when (key) { Key.One -> 1; Key.Two -> 2; else -> 3 }
            val v = view()
            if (!shift && !ctrl && !alt && Spotlight.digitPicks(st.text, st.cursor, n, v.fixes.size)) {
                v.fixes.getOrNull(n - 1)?.let { duels.spotlight = st.typed(it.line, it.line.length) }
                true
            } else false
        }
        else -> false
    }
}

/** A row clicked: made when it makes a move, else taken into the line. */
private fun spotChoose(h: NeueHolders, i: Int, make: Boolean) {
    val duels = h.duel
    val st = duels.spotlight ?: return
    duels.spotlight = st.copy(chosen = i)
    if (make) spotEnter(h, keep = false) else {
        val g = duels.shown ?: return
        spotTake(h, spotView(duels, st, g))
    }
}

/** Tab: the chosen row into the line (the line's own move row takes the first completion instead). */
private fun spotTake(h: NeueHolders, view: Spotlight.View) {
    val duels = h.duel
    val st = duels.spotlight ?: return
    val rows = view.choosable
    val row = rows.getOrNull(st.chosen)?.takeIf { it.kind != RowKind.MOVE && it.kind != RowKind.STEP }
        ?: rows.firstOrNull { it.kind == RowKind.FIX || it.kind == RowKind.COMPLETE || it.kind == RowKind.TRY }
        ?: return
    duels.spotlight = st.typed(row.line, row.cursor).copy(chosen = 0)
}

/** Enter: the chosen row made (or taken, when it is no move yet); [keep] leaves the box open for the next line. */
fun spotEnter(h: NeueHolders, keep: Boolean) {
    val duels = h.duel
    val st = duels.spotlight ?: return
    val g = duels.shown ?: return
    if (st.mode == Mode.LISTENING) return
    if (st.mode == Mode.ANSWER && st.answer != null) { if (keep) duels.spotlight = Spotlight.State() else duels.closeSpotlight(); return }
    // A heard move confirmed after the table moved (Ai played, the other seat answered) is shown again, never made
    // blind: the "yes" was for the table it was read out on (the red team).
    if (st.mode == Mode.HEARD && st.shownAt != null && st.shownAt != g.cursor) {
        duels.spotlight = st.copy(shownAt = g.cursor, problem = "The table changed. Check the move, then say “yes” or press Enter.")
        h.duelVoice.say("The table changed. Check the move again.")
        return
    }
    val view = spotView(duels, st, g)
    val row = view.choosable.getOrNull(st.chosen)
    val line = when {
        row == null || row.kind == RowKind.MOVE || row.kind == RowKind.STEP -> st.text
        !row.makes -> { spotTake(h, view); return }
        else -> row.line
    }
    if (line.isBlank()) { duels.closeSpotlight(); return }
    spotMake(h, line, keep, heard = st.heard)
}

/** [line] made through the duel's one door ([Duels.runLine]); what came of it shown in the box, or the box closed. */
internal fun spotMake(h: NeueHolders, line: String, keep: Boolean, heard: String? = null) {
    val duels = h.duel
    when (val r = duels.runLine(line, quiet = true)) {
        Duels.Ran.Moved, Duels.Ran.Chrome -> if (keep) { duels.spotlight = Spotlight.State(); duels.spotlightFocus++ } else duels.closeSpotlight()
        is Duels.Ran.Answered -> {
            duels.spotlight = Spotlight.State(line).copy(mode = Mode.ANSWER, answer = r.text, heard = heard)
            h.duelVoice.say(r.text)
        }
        is Duels.Ran.Refused -> duels.spotlight = Spotlight.State(line).copy(problem = r.why, heard = heard)
        // A `;` line stopped partway: the steps made stay made, and only the rest waits in the box (the red team).
        is Duels.Ran.Partial -> duels.spotlight = Spotlight.State(r.rest).copy(problem = r.words)
    }
}

/** Ctrl Enter: the words said in the chat — to Ai at its table, else across it. */
private fun spotChat(h: NeueHolders) {
    val duels = h.duel
    val t = duels.spotlight?.text?.trim().orEmpty()
    if (t.isEmpty()) return
    duels.say(t)
    if (aiAtTable(h)) cueAi(h, Cue.SAY, t)
    duels.closeSpotlight()
}

/**
 * What was heard holding M (1.0.87): the transcriber's words made the Line's ([DuelSpeech.normalize]) and sorted
 * ([DuelSpeech.classify]). A move is shown in the box with the words heard above it and waits for Enter or "yes"
 * (kai: "show, then confirm"; `DuelPrefs.voiceConfirm`); a question is answered in the box, never in the log; a cue goes
 * to Ai; undo, "no" and words to Ai need no confirm.
 */
fun spotHeard(h: NeueHolders, heard: String) {
    val duels = h.duel
    // A replay is a record, not a table to play on: words finished while one opened change nothing (the red team).
    if (duels.replay != null) return
    val g = duels.shown ?: return
    val s = g.state
    val line = DuelSpeech.normalize(heard)
    val ai = aiAtTable(h)
    fun show(state: Spotlight.State) {
        duels.spotlight = state
        duels.spotlightFocus++
    }
    when (val said = DuelSpeech.classify(line, s, duels.bottom, duels.catalog, ai, g.header.seed)) {
        is DuelSpeech.Spoken.Command -> if (!h.neue.prefs.duel.voiceConfirm) {
            show(Spotlight.State(said.line).copy(heard = heard))
            spotMake(h, said.line, keep = false, heard = heard)
        } else {
            show(Spotlight.State(said.line).copy(mode = Mode.HEARD, heard = heard, shownAt = g.cursor))
            // Said back as it is shown, when the table speaks.
            val p = DuelCommand.preview(said.line, s, duels.bottom, duels.catalog, g.header.seed)
            h.duelVoice.say(if (p.ok) Spotlight.sentence(p.actions, p.words, s, duels.bottom, duels.catalog) else p.problem ?: "")
        }
        DuelSpeech.Spoken.Confirm -> {
            val pending = duels.spotlight?.takeIf { it.text.isNotBlank() && it.mode != Mode.ANSWER && it.mode != Mode.LISTENING }
                ?: duels.spotlight?.takeIf { it.mode == Mode.LISTENING && it.text.isNotBlank() }?.copy(mode = Mode.HEARD)
            if (pending != null) {
                duels.spotlight = pending
                spotEnter(h, keep = false)
            } else show(Spotlight.State().copy(mode = Mode.ANSWER, heard = heard, answer = "Nothing to confirm. Say the move first."))
        }
        DuelSpeech.Spoken.Cancel -> duels.closeSpotlight()
        DuelSpeech.Spoken.Undo -> { duels.undo(); duels.closeSpotlight() }
        is DuelSpeech.Spoken.Cue -> when {
            ai -> { giveCue(h, said.cue); duels.closeSpotlight() }
            // No Ai: "no response" with a chain open passes priority across the hot-seat.
            !s.solo && s.chain.isNotEmpty() -> { duels.act(DuelAction.Answer(duels.bottom, respond = false), duels.bottom); duels.closeSpotlight() }
            else -> show(Spotlight.State().copy(mode = Mode.ANSWER, heard = heard, answer = "No Ai sits at this table."))
        }
        is DuelSpeech.Spoken.Query -> {
            val answer = DuelAnswer.answer(said.query, s, duels.bottom, duels.catalog, g.header.seed)
            said.query.uid?.let { duels.inspected = it }
            show(Spotlight.State(line).copy(mode = Mode.ANSWER, heard = heard, answer = answer))
            h.duelVoice.say(answer)
        }
        is DuelSpeech.Spoken.ToAi -> if (ai) {
            duels.say(said.text)
            cueAi(h, Cue.SAY, said.text)
            duels.closeSpotlight()
        } else show(Spotlight.State().copy(mode = Mode.ANSWER, heard = heard, answer = "Didn't catch that."))
        is DuelSpeech.Spoken.Unknown -> show(Spotlight.State().copy(mode = Mode.ANSWER, heard = heard, answer = "Didn't catch that."))
    }
}

/** The Spotlight's hooks on the duel's voice: holding M opens it listening; the words come here; hints from the table. */
internal fun wireSpotlightVoice(h: NeueHolders) {
    val voice = h.duelVoice
    voice.onListen = { h.duel.openSpotlight(mode = Mode.LISTENING) }
    voice.onHeard = { text -> spotHeard(h, text) }
    voice.hints = {
        val d = h.duel
        val g = d.shown
        if (g == null) tableHints(h) else DuelSpeech.hints(g.state, d.bottom, d.catalog)
    }
}

/** Over every card and the table's chrome, under nothing of the table's. */
private const val DIM_Z = 200f
/** Over the table and its rails. */
private const val SPOT_Z = 300f
/** The level bars. */
private const val BARS = 14
/** How long after opening the box its opening keystroke may still arrive as typing. */
private const val SEED_MS = 400L
