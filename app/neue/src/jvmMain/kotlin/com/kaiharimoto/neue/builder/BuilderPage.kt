package com.kaiharimoto.neue.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.mastertool.core.input.CursorMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.zen.zenQuiet

private fun kbd(action: DeskAction) = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd)

/**
 * `02 Builder`: pool, deck and inspector side by side, under the window's one
 * bar — into which the builder puts [BuilderBar]: the deck's name, whether it
 * may be played, every action on it, and Save.
 *
 * It was a page header 92px tall and a footer of 64 around the deck, then (1.0.9)
 * a bar of its own under the app's; kai's brief both times was that any pixel is
 * a win for the cards, and from 1.0.10 the page has no bar of its own at all.
 */
@Composable
fun BuilderPage(
    state: DeckBuilderState,
    neue: NeueState,
    drag: NeueDrag,
    onSearchEffects: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        if (neue.prefs.poolVisible) {
            PoolPane(state, neue, drag, onSearchEffects, Modifier.width(neue.prefs.poolWidth.dp).fillMaxHeight())
            ResizeRule("Pool", neue.prefs.poolWidth) { delta -> neue.update(debounce = true) { it.copy(poolWidth = it.poolWidth + delta) } }
        }
        DeckColumn(state, neue, drag, Modifier.weight(1f).fillMaxHeight())
        if (neue.prefs.inspectorVisible) {
            ResizeRule("Inspector", neue.prefs.inspectorWidth) { delta -> neue.update(debounce = true) { it.copy(inspectorWidth = it.inspectorWidth - delta) } }
            Inspector(state, neue, Modifier.width(neue.prefs.inspectorWidth.dp).fillMaxHeight())
        }
    }
}

/** The builder's part of the window's bar. [narrow] drops the words from the tools and keeps their icons and tooltips. */
@Composable
fun RowScope.BuilderBar(
    state: DeckBuilderState,
    neue: NeueState,
    onFormat: (Format) -> Unit,
    onScreenshot: () -> Unit,
    onSave: () -> Unit,
    narrow: Boolean,
) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    // The deck's name is the page's title, and editable where it stands.
    val source = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val line = animatedColor(if (focused) c.ink else if (hovered) c.ink25 else c.paper)
    BasicTextField(
        value = state.deckName,
        onValueChange = state::rename,
        singleLine = true,
        // A field clips to its line, and the type scale's display leading is
        // tighter than a descender: the tail of a y was cut off at 1.05.
        textStyle = MuType.h2(f).copy(color = c.ink, lineHeight = 28.sp),
        cursorBrush = SolidColor(c.ink),
        interactionSource = source,
        // Enter is done: the name is kept, and the field lets go.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    focusManager.clearFocus()
                    true
                } else {
                    false
                }
            }
            .weight(1f, fill = false)
            .widthIn(min = 140.dp, max = 560.dp)
            .cursor(CursorMode.TEXT, caption = "Rename", fontSize = 20.sp, focused = focused)
            .hoverable(source)
            .onFocusChanged { state.onTextFieldFocusChanged(it.isFocused) }
            .drawBehind { drawLine(line, Offset(0f, size.height + 2.dp.toPx()), Offset(size.width, size.height + 2.dp.toPx()), 1.dp.toPx()) },
    )
    Standing(state, neue)
    Box(Modifier.weight(1f))
    Tip("Undo", kbd = kbd(DeskAction.UNDO)) { IconButton(Icons.Undo, state::undo, enabled = state.canUndo, size = 32.dp, label = "Undo", reason = "Nothing to undo") }
    Tip("Redo", kbd = kbd(DeskAction.REDO)) { IconButton(Icons.Redo, state::redo, enabled = state.canRedo, size = 32.dp, label = "Redo", reason = "Nothing to redo") }
    Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
    Segmented(state.format, Format.entries, { it.name }, onFormat, small = true)
    Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
    Tool("Import", "Import a .ydk or .ydkx", Icons.Import, kbd(DeskAction.IMPORT), !narrow, state::importFromFile)
    Tool("Export", "Export the deck", Icons.Export, kbd(DeskAction.EXPORT), !narrow, state::exportToFile)
    Tool("Screenshot", "A picture of the deck, without the window around it", Icons.Camera, kbd(DeskAction.SCREENSHOT), !narrow, onScreenshot)
    Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
    Tip("Show or hide the pool", kbd = kbd(DeskAction.TOGGLE_POOL)) {
        IconButton(Icons.PanelLeft, { neue.update { it.copy(poolVisible = !it.poolVisible) } }, toggled = neue.prefs.poolVisible, size = 32.dp, label = if (neue.prefs.poolVisible) "Hide pool" else "Show pool")
    }
    Tip("Show or hide the inspector", kbd = kbd(DeskAction.TOGGLE_INSPECTOR)) {
        IconButton(Icons.PanelRight, { neue.update { it.copy(inspectorVisible = !it.inspectorVisible) } }, toggled = neue.prefs.inspectorVisible, size = 32.dp, label = if (neue.prefs.inspectorVisible) "Hide inspector" else "Show inspector")
    }
    Tip("Save the deck", kbd = kbd(DeskAction.SAVE)) {
        MuButton("Save", onSave, variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Icons.Save)
    }
    Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
}

/** A tool on the bar: a word and an icon while there is room, the icon alone when there is not. */
@Composable
private fun Tool(label: String, tip: String, icon: androidx.compose.ui.graphics.vector.ImageVector, chord: String?, words: Boolean, onClick: () -> Unit) {
    Tip(tip, kbd = chord) {
        if (words) {
            MuButton(label, onClick, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = icon)
        } else {
            IconButton(icon, onClick, size = 32.dp, label = label)
        }
    }
}

/** Whether the deck may be played, in a word, and the way to the reasons when it may not. */
@Composable
private fun Standing(state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val validation = state.validation
    when {
        validation.errors.isNotEmpty() -> MicroLink(
            "✕ ${validation.errors.size} ${if (validation.errors.size == 1) "issue" else "issues"} →",
            { neue.drawer = Drawer.ISSUES },
            color = c.ink,
        )
        validation.warnings.isNotEmpty() -> MicroLink(
            "Legal · ${validation.warnings.size} ${if (validation.warnings.size == 1) "note" else "notes"} →",
            { neue.drawer = Drawer.ISSUES },
            color = c.ink70,
        )
        else -> Micro("Legal in ${state.format.name}", color = c.ink70)
    }
}

/**
 * A pane edge you can drag: a 1px ink rule inside a 6px grip with a resize
 * cursor. Widths are stored at a scale of one, so a pane keeps its size when
 * the interface is zoomed.
 */
@Composable
private fun ResizeRule(name: String, width: Float, onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val c = Mu.colors
    Box(
        Modifier
            .zenQuiet()
            .width(7.dp)
            .fillMaxHeight()
            .hoverable(source)
            // A drag: the family cursor names the pane and reads its width as it moves.
            .cursor(CursorMode.DRAG, caption = name, value = "${kotlin.math.round(width).toInt()} px")
            .draggable(
                rememberDraggableState { px -> onDrag(with(density) { px.toDp().value }) },
                Orientation.Horizontal,
            ),
        contentAlignment = Alignment.Center,
    ) {
        VRule(Modifier.width(if (hovered) 2.dp else 1.dp), color = c.ink)
    }
}
