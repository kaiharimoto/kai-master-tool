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
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.DeckSection
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
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import java.awt.Cursor

private fun kbd(action: DeskAction) = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd)

/**
 * `02 Builder`: the page header with the deck's name in it, then pool, deck
 * and inspector side by side, then the one strong rule in the page and the
 * one primary action, Save.
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
) {
    Column(Modifier.fillMaxSize()) {
        // In immersive mode the shell draws the header and footer itself, folded
        // over the page, so the deck is fitted to the whole window.
        if (bars) BuilderHeader(state, neue, onFormat, onScreenshot)
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
        if (bars) BuilderFooter(state, neue)
    }
}

@Composable
fun BuilderHeader(state: DeckBuilderState, neue: NeueState, onFormat: (Format) -> Unit, onScreenshot: () -> Unit) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(start = 32.dp, end = 24.dp, top = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Numeral(2, Modifier.padding(bottom = 26.dp))
        Column(Modifier.weight(1f)) {
            // The deck's name is the page's title, and editable where it stands.
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val hovered by source.collectIsHoveredAsState()
            val line = animatedColor(if (focused) c.ink else if (hovered) c.ink25 else c.paper)
            BasicTextField(
                value = state.deckName,
                onValueChange = state::rename,
                singleLine = true,
                textStyle = MuType.h1(f).copy(color = c.ink),
                cursorBrush = SolidColor(c.ink),
                interactionSource = source,
                modifier = Modifier
                    .widthIn(min = 240.dp, max = 720.dp)
                    .hoverable(source)
                    .onFocusChanged { state.onTextFieldFocusChanged(it.isFocused) }
                    .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
            )
            val main = state.deck.main.size
            val extra = state.deck.extra.size
            val side = state.deck.side.size
            Micro(
                "$main main · $extra extra · $side side · ${state.groups.groups.size} groups",
                Modifier.padding(top = 8.dp),
                color = c.ink45,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Tip("Undo", kbd = kbd(DeskAction.UNDO)) { IconButton(Icons.Undo, state::undo, enabled = state.canUndo, size = 32.dp) }
            Tip("Redo", kbd = kbd(DeskAction.REDO)) { IconButton(Icons.Redo, state::redo, enabled = state.canRedo, size = 32.dp) }
            Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
            Segmented(state.format, Format.entries, { it.name }, onFormat, small = true)
            Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
            Tip("Import a .ydk or .ydkx", kbd = kbd(DeskAction.IMPORT)) {
                MuButton("Import", state::importFromFile, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Import)
            }
            Tip("Export the deck", kbd = kbd(DeskAction.EXPORT)) {
                MuButton("Export", state::exportToFile, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Export)
            }
            Tip("A picture of the deck, without the window around it", kbd = kbd(DeskAction.SCREENSHOT)) {
                MuButton("Screenshot", onScreenshot, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Camera)
            }
            Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
            Tip("Show or hide the pool", kbd = kbd(DeskAction.TOGGLE_POOL)) {
                IconButton(Icons.PanelLeft, { neue.update { it.copy(poolVisible = !it.poolVisible) } }, toggled = neue.prefs.poolVisible, size = 32.dp)
            }
            Tip("Show or hide the inspector", kbd = kbd(DeskAction.TOGGLE_INSPECTOR)) {
                IconButton(Icons.PanelRight, { neue.update { it.copy(inspectorVisible = !it.inspectorVisible) } }, toggled = neue.prefs.inspectorVisible, size = 32.dp)
            }
        }
    }
}

/** The page's one strong rule, its status in mono, and its one primary action. */
@Composable
fun BuilderFooter(state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val validation = state.validation
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(c.paper)
            .drawBehind { drawRect(c.ink, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        DeckSection.entries.forEach { section ->
            val n = state.deck[section].size
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro(section.displayName, color = c.ink45)
                Mono(n.toString(), color = c.ink, size = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp))
            }
        }
        Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
        when {
            validation.errors.isNotEmpty() -> MicroLink(
                "✕ ${validation.errors.size} ${if (validation.errors.size == 1) "issue" else "issues"} →",
                { neue.drawer = Drawer.ISSUES },
                color = c.ink,
            )
            validation.warnings.isNotEmpty() -> MicroLink(
                "Legal · ${validation.warnings.size} ${if (validation.warnings.size == 1) "note" else "notes"} →",
                { neue.drawer = Drawer.ISSUES },
            )
            else -> Micro("Legal in ${state.format.name}", color = c.ink45)
        }
        Box(Modifier.weight(1f))
        Mono("Ctrl S", color = c.ink45)
        MuButton("Save", { state.save() }, variant = BtnVariant.PRIMARY, size = BtnSize.LG, icon = Icons.Save)
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
