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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import java.awt.Cursor

private fun kbd(action: DeskAction) = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd)

/**
 * `02 Builder`: one bar with the deck's name, its standing and every action in
 * it, then pool, deck and inspector side by side.
 *
 * It was a page header 92px tall and a footer of 64 around the deck, and kai's
 * brief for 1.0.9 was that any pixel is a win for the cards: the header's
 * second line and the footer's counts said what the deck's own strips say, and
 * what was left of both — the name, the legality, Save — fits one row.
 */
@Composable
fun BuilderPage(
    state: DeckBuilderState,
    neue: NeueState,
    drag: NeueDrag,
    onFormat: (Format) -> Unit,
    onSearchEffects: (Boolean) -> Unit,
    bars: Boolean = true,
    onScreenshot: () -> Unit = {},
    onSave: () -> Unit = { state.save() },
) {
    Column(Modifier.fillMaxSize()) {
        // In immersive mode the shell draws the bar itself, folded over the page,
        // so the deck is fitted to the whole window.
        if (bars) BuilderHeader(state, neue, onFormat, onScreenshot, onSave)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (neue.prefs.poolVisible) {
                PoolPane(state, neue, drag, onSearchEffects, Modifier.width(neue.prefs.poolWidth.dp).fillMaxHeight())
                ResizeRule { delta -> neue.update(debounce = true) { it.copy(poolWidth = it.poolWidth + delta) } }
            }
            DeckColumn(state, neue, drag, Modifier.weight(1f).fillMaxHeight())
            if (neue.prefs.inspectorVisible) {
                ResizeRule { delta -> neue.update(debounce = true) { it.copy(inspectorWidth = it.inspectorWidth - delta) } }
                Inspector(state, neue, Modifier.width(neue.prefs.inspectorWidth.dp).fillMaxHeight())
            }
        }
    }
}

/** How tall the builder's bar is: two rows of the old header and the footer, in one. */
val BUILDER_BAR = 48.dp

@Composable
fun BuilderHeader(state: DeckBuilderState, neue: NeueState, onFormat: (Format) -> Unit, onScreenshot: () -> Unit, onSave: () -> Unit = { state.save() }) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Words on the buttons while there is room for them; below that, the icons and their tooltips.
        val words = maxWidth >= 1500.dp
        Row(
            Modifier
                .fillMaxWidth()
                .height(BUILDER_BAR)
                .background(c.paper)
                .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(start = 24.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                    .widthIn(min = 160.dp, max = 640.dp)
                    .hoverable(source)
                    .onFocusChanged { state.onTextFieldFocusChanged(it.isFocused) }
                    .drawBehind { drawLine(line, Offset(0f, size.height + 2.dp.toPx()), Offset(size.width, size.height + 2.dp.toPx()), 1.dp.toPx()) },
            )
            Standing(state, neue)
            Box(Modifier.weight(1f))
            Tip("Undo", kbd = kbd(DeskAction.UNDO)) { IconButton(Icons.Undo, state::undo, enabled = state.canUndo, size = 32.dp) }
            Tip("Redo", kbd = kbd(DeskAction.REDO)) { IconButton(Icons.Redo, state::redo, enabled = state.canRedo, size = 32.dp) }
            Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
            Segmented(state.format, Format.entries, { it.name }, onFormat, small = true)
            Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
            Tool("Import", "Import a .ydk or .ydkx", Icons.Import, kbd(DeskAction.IMPORT), words, state::importFromFile)
            Tool("Export", "Export the deck", Icons.Export, kbd(DeskAction.EXPORT), words, state::exportToFile)
            Tool("Screenshot", "A picture of the deck, without the window around it", Icons.Camera, kbd(DeskAction.SCREENSHOT), words, onScreenshot)
            Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
            Tip("Show or hide the pool", kbd = kbd(DeskAction.TOGGLE_POOL)) {
                IconButton(Icons.PanelLeft, { neue.update { it.copy(poolVisible = !it.poolVisible) } }, toggled = neue.prefs.poolVisible, size = 32.dp)
            }
            Tip("Show or hide the inspector", kbd = kbd(DeskAction.TOGGLE_INSPECTOR)) {
                IconButton(Icons.PanelRight, { neue.update { it.copy(inspectorVisible = !it.inspectorVisible) } }, toggled = neue.prefs.inspectorVisible, size = 32.dp)
            }
            Tip("Save the deck", kbd = kbd(DeskAction.SAVE)) {
                MuButton("Save", onSave, variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Icons.Save)
            }
        }
    }
}

/** A tool on the bar: a word and an icon while there is room, the icon alone when there is not. */
@Composable
private fun Tool(label: String, tip: String, icon: androidx.compose.ui.graphics.vector.ImageVector, chord: String?, words: Boolean, onClick: () -> Unit) {
    Tip(tip, kbd = chord) {
        if (words) {
            MuButton(label, onClick, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = icon)
        } else {
            IconButton(icon, onClick, size = 32.dp)
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
private fun ResizeRule(onDrag: (Float) -> Unit) {
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
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                rememberDraggableState { px -> onDrag(with(density) { px.toDp().value }) },
                Orientation.Horizontal,
            ),
        contentAlignment = Alignment.Center,
    ) {
        VRule(Modifier.width(if (hovered) 2.dp else 1.dp), color = c.ink)
    }
}
