package com.kaiharimoto.neue.shell

import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.input.DeskTouch
import com.kaiharimoto.neue.kit.LocalTouchFirst
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
import androidx.compose.ui.input.pointer.PointerEventType
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
    val rows = remember(query) { commands(query) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(highlighted) { if (rows.isNotEmpty()) list.animateScrollToItem((highlighted - 3).coerceAtLeast(0)) }

    fun run(row: Command, shift: Boolean) {
        onDismiss()
        if (shift && row.alt != null) row.alt.invoke() else row.run()
    }

    Box(
        Modifier.fillMaxSize().background(c.overlay)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .cursor(CursorMode.TEXT, fontSize = 15.sp, focused = true)
                                .focusRequester(focus)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionDown -> { if (rows.isNotEmpty()) highlighted = (highlighted + 1) % rows.size; true }
                                        Key.DirectionUp -> { if (rows.isNotEmpty()) highlighted = (highlighted - 1 + rows.size) % rows.size; true }
                                        Key.Enter, Key.NumPadEnter -> { rows.getOrNull(highlighted)?.let { run(it, e.isShiftPressed) }; true }
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
                    Box(Modifier.heightIn(max = 480.dp)) {
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
                                        Micro(row.group, Modifier.width(48.dp), color = if (on) inner.ink.copy(alpha = 0.6f) else c.ink45)
                                        RowText(row.label, Modifier.weight(1f), color = inner.ink)
                                        if (row.hint != null) Mono(row.hint, color = if (on) inner.ink.copy(alpha = 0.6f) else c.ink45)
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
    MuDialog("Keyboard and mouse", onDismiss, width = 896.dp, description = "Every shortcut in Neue Master Tool, and what the mouse does to a card. The palette, ${DeskShortcuts.chordFor(DeskAction.PALETTE)?.let(DeskShortcuts::kbd)}, reaches every shortcut by name.") {
        val scroll = androidx.compose.foundation.rememberScrollState()
        Box(Modifier.heightIn(max = 420.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                DeskScope.entries.chunked(2).forEach { pair ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        pair.forEach { scope ->
                            Column {
                                SectionTitle(null, scope.heading)
                                DeskShortcuts.all.filter { it.scope == scope }.distinctBy { it.action to it.description }.forEach { row ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        RowText(row.description, Modifier.weight(1f))
                                        Kbd(DeskShortcuts.kbd(row.chord))
                                    }
                                    HRule()
                                }
                            }
                        }
                    }
                }
            }
            ScrollbarFor(scroll)
        }
        // The mouse, from its own table: the two places a card can be, side by
        // side. On a touch screen the finger's table stands in its place — a
        // mouse plugged into the tablet still does everything the desk's does.
        val touch = LocalTouchFirst.current
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
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
                            RowText(description, Modifier.weight(1f))
                            Kbd(gesture)
                        }
                        HRule()
                    }
                }
            }
        }
        MuText(
            if (touch) {
                "Two fingers pinch the deck larger or smaller. A mouse and a keyboard work as they do on the desktop."
            } else {
                "The index folds away: move to the left edge of the window to bring it out. F11 is immersive mode, where the top and bottom bars fold away too."
            },
            Modifier.padding(top = 20.dp),
            MuType.help(LocalMuFonts.current),
            color = c.ink45,
        )
    }
}
