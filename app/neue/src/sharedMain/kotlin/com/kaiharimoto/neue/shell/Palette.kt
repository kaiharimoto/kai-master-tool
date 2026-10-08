package com.kaiharimoto.neue.shell

import androidx.compose.foundation.layout.imePadding
import com.kaiharimoto.mastertool.core.duel.text.CommandHelp
import com.kaiharimoto.mastertool.core.input.DeskMenuBar
import com.kaiharimoto.mastertool.core.input.MapperMouse
import com.kaiharimoto.mastertool.core.input.MapperTarget
import com.kaiharimoto.mastertool.core.input.MapperTouch
import com.kaiharimoto.mastertool.core.text.Words
import com.kaiharimoto.mastertool.core.ai.text.MicroCaps
import com.kaiharimoto.neue.kit.Hint
import com.kaiharimoto.neue.kit.LocalKeepCase
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import com.kaiharimoto.mastertool.core.input.DuelMouse
import com.kaiharimoto.mastertool.core.input.DuelTarget
import com.kaiharimoto.mastertool.core.input.DuelTouch
import com.kaiharimoto.mastertool.core.input.PresentMouse
import com.kaiharimoto.mastertool.core.input.PresentTarget
import com.kaiharimoto.mastertool.core.input.PresentTouch
import com.kaiharimoto.mastertool.core.input.ShootoutMouse
import com.kaiharimoto.mastertool.core.input.ShootoutTarget
import com.kaiharimoto.mastertool.core.input.ShootoutTouch
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.input.DeskTouch
import com.kaiharimoto.mastertool.core.input.DeskWords
import com.kaiharimoto.neue.kit.LocalHardwareKeyboard
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.reportsTextFocus
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import com.kaiharimoto.mastertool.core.layout.FollowScroll
import com.kaiharimoto.neue.kit.onPointer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.DeskMouse
import com.kaiharimoto.mastertool.core.input.DeskScope
import com.kaiharimoto.mastertool.core.input.MouseTarget
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.SectionTitle
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/** One row of the palette: its group, its label, a hint (usually a shortcut), and what it does. */
data class Command(
    val group: String,
    val label: String,
    val hint: String? = null,
    /** Shift + Enter, where it means something (add a card to the side deck). */
    val alt: (() -> Unit)? = null,
    /** What else the row answers to, beyond its label and group ("banlist" finds Legality). */
    val words: List<String> = emptyList(),
    val run: () -> Unit,
)

/**
 * The command palette (§6): `Ctrl K`, a blinking `→`, and every command and
 * card in one list. Groups in the family's order — Go, then this object's
 * actions, then App, then search results.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CommandPalette(commands: (String) -> List<Command>, onDismiss: () -> Unit) {
    val c = Mu.colors
    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    // A card search over the whole pool is too much for the frame thread on every key (1.0.92): from two letters on,
    // the list settles 130 ms after the last key, worked out off it, as the pool's does; shorter queries (commands
    // only) answer at once, and Enter on a list not yet settled works out the current one first.
    var settled by remember { mutableStateOf("" to emptyList<Command>()) }
    val quick = query.trim().length < 2
    val instant = remember(query) { if (quick) commands(query) else null }
    LaunchedEffect(query) {
        if (!quick) {
            delay(130)
            val made = withContext(Dispatchers.Default) { commands(query) }
            settled = query to made
        }
    }
    val rows = instant ?: settled.second
    fun current(): List<Command> = instant ?: if (settled.first == query) settled.second else commands(query).also { settled = query to it }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The list follows the keys' highlight only (kai, 1.0.24): the pointer's is under it
    // already, and once a wheel, a touchpad, a finger or the scrollbar has moved the
    // list, it is the hand's until the palette closes (`FollowScroll`).
    val follow = remember { FollowScroll() }
    var keyed by remember { mutableStateOf(0) }
    LaunchedEffect(keyed) {
        if (keyed > 0 && rows.isNotEmpty() && follow.follows(byKeys = true)) list.animateScrollToItem((highlighted - 3).coerceAtLeast(0))
    }
    // A new query is a new list: it starts at its top, where its first row is highlighted.
    LaunchedEffect(query) { if (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) list.scrollToItem(0) }
    fun step(by: Int) {
        if (rows.isEmpty()) return
        highlighted = (highlighted + by + rows.size) % rows.size
        keyed++
    }

    fun run(row: Command, shift: Boolean) {
        onDismiss()
        if (shift && row.alt != null) row.alt.invoke() else row.run()
    }

    Box(
        Modifier.fillMaxSize().background(c.overlay)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = maxHeight * 0.15f)
                    .width(minOf(640.dp, maxWidth * 0.9f))
                    .background(c.paper)
                    .border(1.dp, c.ink)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val blink = rememberInfiniteTransition(label = "blink").animateFloat(
                        1f, 0f,
                        infiniteRepeatable(keyframes { durationMillis = 1000; 1f at 0; 1f at 499; 0f at 500; 0f at 999 }, RepeatMode.Restart),
                        label = "caret",
                    ).value
                    Mono("→", Modifier.alpha(blink), size = 15.sp)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) RowText("Type a command or a card name", color = c.ink45)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it; highlighted = 0 },
                            singleLine = true,
                            textStyle = MuType.body(LocalMuFonts.current).copy(fontSize = 15.sp, color = c.ink),
                            cursorBrush = SolidColor(c.ink),
                            // The palette is a command line: the soft keyboard's key reads Go and runs
                            // the highlighted row, as a hardware Enter does (touch swarm, rec 9).
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { current().getOrNull(highlighted)?.let { run(it, false) } }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .cursor(CursorMode.TEXT, fontSize = 15.sp, focused = true)
                                .focusRequester(focus)
                                .reportsTextFocus()
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionDown -> { step(1); true }
                                        Key.DirectionUp -> { step(-1); true }
                                        Key.Enter, Key.NumPadEnter -> { current().getOrNull(highlighted)?.let { run(it, e.isShiftPressed) }; true }
                                        Key.Escape -> { onDismiss(); true }
                                        else -> false
                                    }
                                },
                        )
                    }
                    Kbd("Esc")
                }
                HRule(color = c.ink)
                if (rows.isEmpty()) {
                    RowText("No matches.", Modifier.padding(horizontal = 16.dp, vertical = 24.dp), color = c.ink70)
                } else {
                    Box(
                        Modifier
                            .heightIn(max = 480.dp)
                            // Watched on the way down, consuming nothing: a wheel or a touchpad over
                            // the list, or a press dragged across it (a finger, the scrollbar's thumb).
                            .pointerInput(follow) {
                                awaitPointerEventScope {
                                    var pressedAt: Offset? = null
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull() ?: continue
                                        when (event.type) {
                                            PointerEventType.Scroll -> follow.scrolledByHand()
                                            PointerEventType.Press -> pressedAt = change.position
                                            PointerEventType.Release -> pressedAt = null
                                            PointerEventType.Move -> {
                                                val from = pressedAt
                                                if (from != null && change.pressed && (change.position - from).getDistance() > viewConfiguration.touchSlop) {
                                                    follow.scrolledByHand()
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                    ) {
                        // The group column is as wide as the longest group showing, so none is cut ("SHOO…", finding 11).
                        val measurer = rememberTextMeasurer()
                        val fonts = LocalMuFonts.current
                        val keep = LocalKeepCase.current
                        val density = LocalDensity.current
                        val groupWidth = remember(rows, fonts, keep, density) {
                            val widest = rows.map { it.group }.distinct().maxOfOrNull { group ->
                                measurer.measure(MicroCaps.of(group, keep), MuType.micro(fonts), maxLines = 1).size.width
                            } ?: 0
                            with(density) { widest.toDp() + 2.dp }.coerceIn(48.dp, 120.dp)
                        }
                        LazyColumn(state = list) {
                            itemsIndexed(rows) { i, row ->
                                val on = i == highlighted
                                Inverted(on) {
                                    val inner = Mu.colors
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(if (on) inner.paper else androidx.compose.ui.graphics.Color.Transparent)
                                            .onPointer(PointerEventType.Enter) { highlighted = i }
                                            .cursorPointer(caption = if (row.group == "Cards") "Add" else "Run")
                                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { run(row, false) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        Micro(row.group, Modifier.width(groupWidth), color = if (on) inner.ink.copy(alpha = 0.6f) else c.ink45)
                                        RowText(row.label, Modifier.weight(1f), color = inner.ink)
                                        if (row.hint != null) Hint(row.hint, color = if (on) inner.ink.copy(alpha = 0.6f) else c.ink45)
                                    }
                                }
                                if (i < rows.lastIndex) HRule()
                            }
                        }
                        ScrollbarFor(list)
                    }
                }
            }
        }
    }
}

/** The keyboard, rendered from the table — so it cannot list a key that does nothing. */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val c = Mu.colors
    val touch = LocalTouchFirst.current
    val keyboard = LocalHardwareKeyboard.current
    // On a tablet (touch swarm, rec 20) the finger's table leads, and the keyboard's is
    // there only when a keyboard is: the dialog is "Fingers", and tells what it is.
    val title = if (touch) "Fingers" else "Keyboard and mouse"
    val description = if (touch) {
        "What a finger does to a card, and the rest of the tablet's gestures." + if (keyboard) " A keyboard's shortcuts are below." else ""
    } else {
        "Every shortcut in Neue Master Tool, and what the mouse does to a card. The palette, ${DeskShortcuts.chordFor(DeskAction.PALETTE)?.let(DeskShortcuts::kbd)}, reaches every shortcut by name."
    }
    MuDialog(title, onDismiss, width = 896.dp, description = description, scrolls = false) {
        val scroll = androidx.compose.foundation.rememberScrollState()
        Box(Modifier.heightIn(max = 520.dp)) {
        Column(Modifier.padding(end = 12.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (touch) {
                GestureTable(touch = true)
                MuText(DeskWords.TOUCH_FOOTER, style = MuType.help(LocalMuFonts.current), color = c.ink45)
            }
            if (!touch || keyboard) KeyTable()
            PresentGestureTable(touch)
            DuelGestureTable(touch)
            ShootoutGestureTable(touch)
            MapperGestureTable(touch)
            CommandModeTable()
            if (!touch) {
                GestureTable(touch = false)
                MuText(
                    "The index folds away: move to the left edge of the window to bring it out. F11 is immersive mode, where the top and bottom bars fold away too.",
                    style = MuType.help(LocalMuFonts.current),
                    color = c.ink45,
                )
            }
        }
        ScrollbarFor(scroll)
        }
    }
}

/** The keyboard, from its own table: every scope, two to a column. */
@Composable
private fun KeyTable() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        DeskScope.entries.chunked(2).forEach { pair ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                pair.forEach { scope ->
                    Column {
                        SectionTitle(null, scope.heading)
                        DeskShortcuts.all.filter { it.scope == scope && (it.action !in DeskAction.AI || DeskMenuBar.aiShown) }.distinctBy { it.action to it.description }.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RowText(if (row.action == DeskAction.AI_PANEL) "${com.kaiharimoto.mastertool.core.input.DeskMenuBar.aiName}: open or close" else Words.named(row.description, DeskMenuBar.aiName), Modifier.weight(1f))
                                Kbd(DeskShortcuts.kbd(row.chord))
                            }
                            HRule()
                        }
                    }
                }
            }
        }
    }
}

/** The duel's gestures (1.0.74), from its own tables: what a press means on the duel table. */
@Composable
private fun DuelGestureTable(touch: Boolean) {
    val rows = if (touch) DuelTouch.all else DuelMouse.all
    Column {
        SectionTitle(null, "Duel: the table")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            DuelTarget.entries.chunked(3).forEach { targets ->
                Column(Modifier.weight(1f)) {
                    targets.forEach { target ->
                        MuText(target.heading, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MuType.help(LocalMuFonts.current), color = Mu.colors.ink70)
                        rows.filter { it.target == target }.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RowText(Words.named(row.description, DeskMenuBar.aiName), Modifier.weight(1f))
                                Kbd(row.gesture, always = true)
                            }
                            HRule()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Command mode (1.0.87): the duel played by typing and by voice — the coordinates, the verb letters, lines to type,
 * phrases to say and the Spotlight's keys, all read from the tables the Line itself reads (`CommandHelp`).
 */
@Composable
private fun CommandModeTable() {
    val help = CommandHelp
    @Composable
    fun Rows(title: String, rows: List<CommandHelp.Row>, mono: Boolean = true) {
        MuText(title, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MuType.help(LocalMuFonts.current), color = Mu.colors.ink70)
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (mono) Mono(row.left, color = Mu.colors.ink, size = 12.sp) else RowText(row.left)
                RowText(row.right, Modifier.weight(1f), color = Mu.colors.ink70, maxLines = 2)
            }
            HRule()
        }
    }
    Column {
        SectionTitle(null, "Duel: command mode")
        MuText(
            "Type a move, or hold M and say it: the Spotlight shows what it will do, and Enter makes it. Places are coordinates from your side of the table, as a chessboard's squares are.",
            style = MuType.help(LocalMuFonts.current),
            color = Mu.colors.ink45,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f)) {
                Rows("The coordinates", help.notation)
                Rows("Verb letters, before a coordinate", help.letters)
                Rows("Opening the box", help.opening.map { CommandHelp.Row(DeskShortcuts.kbd(it.chord), it.description) })
                Rows("In the box", help.keys)
            }
            Column(Modifier.weight(1f)) {
                Rows("Lines to type", help.examples)
                Rows("Hold M and say", help.spoken, mono = false)
            }
        }
    }
}

/** Shootout's gestures (1.1.2), from its own tables: answering a hand, choosing one of two, reading a card. */
@Composable
private fun ShootoutGestureTable(touch: Boolean) {
    val rows = if (touch) ShootoutTouch.all else ShootoutMouse.all
    Column {
        SectionTitle(null, "Shootout: judging hands")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            ShootoutTarget.entries.chunked(3).forEach { targets ->
                Column(Modifier.weight(1f)) {
                    targets.forEach { target ->
                        val mine = rows.filter { it.target == target }
                        if (mine.isNotEmpty()) {
                            MuText(target.heading, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MuType.help(LocalMuFonts.current), color = Mu.colors.ink70)
                            mine.forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    RowText(Words.named(row.description, DeskMenuBar.aiName), Modifier.weight(1f))
                                    Kbd(row.gesture, always = true)
                                }
                                HRule()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Gameplay Mapper's gestures (Phase M), from its own tables: a board read and replayed, a weight dragged, a card's rule. */
@Composable
private fun MapperGestureTable(touch: Boolean) {
    val rows = if (touch) MapperTouch.all else MapperMouse.all
    Column {
        SectionTitle(null, "Gameplay Mapper: the board library")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            MapperTarget.entries.chunked(3).forEach { targets ->
                Column(Modifier.weight(1f)) {
                    targets.forEach { target ->
                        val mine = rows.filter { it.target == target }
                        if (mine.isNotEmpty()) {
                            MuText(target.heading, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MuType.help(LocalMuFonts.current), color = Mu.colors.ink70)
                            mine.forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    RowText(row.description, Modifier.weight(1f))
                                    Kbd(row.gesture, always = true)
                                }
                                HRule()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Present's gestures (1.0.70), from its own tables: what a press means on a slide and while presenting. */
@Composable
private fun PresentGestureTable(touch: Boolean) {
    val rows = if (touch) PresentTouch.all else PresentMouse.all
    Column {
        SectionTitle(null, "Present: making and showing slides")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            PresentTarget.entries.chunked(3).forEach { targets ->
                Column(Modifier.weight(1f)) {
                    targets.forEach { target ->
                        MuText(target.heading, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MuType.help(LocalMuFonts.current), color = Mu.colors.ink70)
                        rows.filter { it.target == target }.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RowText(Words.named(row.description, DeskMenuBar.aiName), Modifier.weight(1f))
                                Kbd(row.gesture, always = true)
                            }
                            HRule()
                        }
                    }
                }
            }
        }
    }
}

/** A card's two places side by side, from the finger's table or the mouse's. */
@Composable
private fun GestureTable(touch: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        MouseTarget.entries.forEach { target ->
            Column(Modifier.weight(1f)) {
                SectionTitle(null, target.heading)
                val rows = if (touch) {
                    DeskTouch.all.filter { it.target == target }.map { it.description to it.gesture.label }
                } else {
                    DeskMouse.all.filter { it.target == target }.map { it.description to it.gesture.label }
                }
                rows.forEach { (description, gesture) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RowText(Words.named(description, DeskMenuBar.aiName), Modifier.weight(1f))
                        Kbd(gesture, always = true)
                    }
                    HRule()
                }
            }
        }
        // The window's own: two fingers undo, three redo (touch swarm, rec 22).
        if (touch) {
            Column(Modifier.weight(1f)) {
                SectionTitle(null, "Anywhere")
                DeskTouch.window.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RowText(Words.named(row.description, DeskMenuBar.aiName), Modifier.weight(1f))
                        Kbd(row.gesture.label, always = true)
                    }
                    HRule()
                }
            }
        }
    }
}
