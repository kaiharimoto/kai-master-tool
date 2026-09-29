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
import androidx.compose.ui.layout.layout
import com.kaiharimoto.mastertool.core.layout.PaneBudget
import com.kaiharimoto.neue.kit.reportsTextFocus
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kaiharimoto.neue.zen.LocalZen
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.foundation.clickable
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
    // Immersive: the bar folds out when the pointer reaches the top edge, so the
    // page keeps a strip of paper under it — the deck's first row is not the edge.
    // Deep zen: the pool and the inspector are faded out, not gone, and a hover, a
    // click or a wheel on them must not reach them (kai, 1.0.15). Each is shielded,
    // and the deck is lifted over both, so a card floated over where they were is
    // still a card under the pointer. From the moment zen is deep, not halfway into
    // its fade (1.0.24): the pointer is the garden's from then on.
    val zen = LocalZen.current
    val asleep = zen.asleep
    // The page's width, shared by PaneBudget (touch swarm, rec 1). Widths there are
    // physical — dp at a scale of one — so the interface scale grows what is in the
    // panes, never the panes; the tablet's pool and inspector are 320 and yield to
    // the deck's floor, and with Groups on the Groups panel takes the inspector's place.
    val scale = neue.prefs.scale
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val panes = PaneBudget.solve(
        window = maxWidth.value * scale,
        touch = neue.touchFirst,
        railOut = false,
        groupsOn = groupsOn(state),
        poolVisible = neue.prefs.poolVisible,
        inspectorVisible = neue.prefs.inspectorVisible,
        poolPref = neue.prefs.poolWidth,
        inspectorPref = neue.prefs.inspectorWidth,
    )
    fun physical(w: Float) = (w / scale).dp
    Row(Modifier.fillMaxSize().padding(top = if (neue.immersive) IMMERSIVE_TOP else 0.dp)) {
        if (!neue.prefs.poolVisible) {
            // A hidden pane leaves its handle where it stood (kai, 1.0.19): a narrow strip
            // at its edge with the one button that brings it back — past the rail's gutter,
            // so reaching for it does not bring the rail out instead.
            val gutter = if (neue.railPinned && !neue.immersive) 0.dp else 32.dp
            HiddenPane(
                Modifier.zenQuiet().padding(start = gutter),
                Icons.PanelLeftOpen,
                "Show the pool",
                kbd(DeskAction.TOGGLE_POOL),
            ) { neue.update { it.copy(poolVisible = true) } }
            VRule(Modifier.zenQuiet(), color = Mu.colors.ink12)
        }
        if (neue.prefs.poolVisible) {
            Box(Modifier.width(physical(panes.pool)).fillMaxHeight()) {
                PoolPane(state, neue, drag, onSearchEffects, Modifier.fillMaxSize())
                ZenShield(asleep)
            }
            Box(Modifier.fillMaxHeight().zIndex(1f)) {
                ResizeRule("Pool", panes.pool, scale, neue.touchFirst) { delta -> neue.update(debounce = true) { it.copy(poolWidth = it.poolWidth + delta) } }
                ZenShield(asleep)
            }
        }
        DeckColumn(state, neue, drag, Modifier.weight(1f).fillMaxHeight().zIndex(if (asleep) 1f else 0f))
        if (panes.inspector > 0f) {
            Box(Modifier.fillMaxHeight().zIndex(1f)) {
                ResizeRule("Inspector", panes.inspector, scale, neue.touchFirst) { delta -> neue.update(debounce = true) { it.copy(inspectorWidth = it.inspectorWidth - delta) } }
                ZenShield(asleep)
            }
            Box(Modifier.width(physical(panes.inspector)).fillMaxHeight()) {
                Inspector(state, neue, Modifier.fillMaxSize())
                ZenShield(asleep)
            }
        } else if (panes.inspectorYielded) {
            // On touch the inspector gave its room to the Groups panel or to the
            // deck's floor; reading a card is the hold's job there (the viewer).
        } else {
            VRule(Modifier.zenQuiet(), color = Mu.colors.ink12)
            HiddenPane(Modifier.zenQuiet(), Icons.PanelRightOpen, "Show the inspector", kbd(DeskAction.TOGGLE_INSPECTOR)) {
                neue.update { it.copy(inspectorVisible = true) }
            }
        }
    }
    }
}

/**
 * What a hidden side pane leaves behind: a 36 px strip with the button that brings it
 * back — and the whole strip brings it back too (kai, 1.0.24: "reopen them by clicking
 * anywhere on the drawer rather than just the button"). Only while hidden: the panes
 * still hide from their own buttons. The strip is framed by the family cursor with
 * `Show`, and faintly shaded under the pointer, so it reads as the one target it is;
 * a tap on it is the same on the tablet. [modifier] carries the pool's gutter as
 * padding outside the strip, so the gutter itself stays paper that clicks nothing.
 */
@Composable
private fun HiddenPane(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, tip: String, chord: String?, onShow: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val name = tip.removePrefix("Show the ").replaceFirstChar { it.uppercase() }
    Box(
        modifier
            .width(36.dp)
            .fillMaxHeight()
            .background(animatedColor(if (hovered) Mu.colors.ink06 else androidx.compose.ui.graphics.Color.Transparent))
            .hoverable(source)
            .cursorPointer(caption = "Show", label = name)
            .clickable(interactionSource = source, indication = null, onClick = onShow)
            .padding(top = 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Tip(tip, kbd = chord) { IconButton(icon, onShow, size = 28.dp, label = name) }
    }
}

/** Over a pane faded out by deep zen: takes every pointer event, so nothing under it hears one. */
@Composable
private fun BoxScope.ZenShield(on: Boolean) {
    if (!on) return
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    )
}

/**
 * The paper at the top of the page in immersive mode (kai, 1.0.12): the bar
 * comes out at 8 px from the edge, and the lens row's buttons sat inside that
 * reach. This strip is the baseline; the deck's spare height is then shared
 * above and below it, so the deck is centred and a click on its own row never
 * calls the bar.
 */
val IMMERSIVE_TOP = 32.dp

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
            .reportsTextFocus()
            .onFocusChanged { state.onTextFieldFocusChanged(it.isFocused) }
            .drawBehind { drawLine(line, Offset(0f, size.height + 2.dp.toPx()), Offset(size.width, size.height + 2.dp.toPx()), 1.dp.toPx()) },
    )
    Standing(state, neue)
    Box(Modifier.weight(1f))
    Tip("Undo", kbd = kbd(DeskAction.UNDO)) { IconButton(Icons.Undo, state::undo, enabled = state.canUndo, size = 32.dp, label = "Undo", reason = "Nothing to undo") }
    Tip("Redo", kbd = kbd(DeskAction.REDO)) { IconButton(Icons.Redo, state::redo, enabled = state.canRedo, size = 32.dp, label = "Redo", reason = "Nothing to redo") }
    // The history (kai, 1.0.17): every step undo can take back and redo put back, in words.
    var historyAt by remember { mutableStateOf(Offset.Zero) }
    Tip("History: every change, and a click goes back to it") {
        Box(Modifier.onGloballyPositioned { historyAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
            IconButton(
                Icons.History,
                { neue.menu = com.kaiharimoto.neue.kit.MenuSpec(historyAt, historyMenu(state)) },
                enabled = state.canUndo || state.canRedo,
                size = 32.dp,
                label = "History",
                reason = "Nothing changed yet",
            )
        }
    }
    Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
    Segmented(state.format, Format.entries, { it.name }, onFormat, small = true)
    Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
    Tool("Import", "Import a .ydk or .ydkx", Icons.Import, kbd(DeskAction.IMPORT), !narrow, state::importFromFile)
    Box(Modifier.onGloballyPositioned { neue.exportAnchor = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
        Tool("Export", "Export: a .ydk or .ydkx file, or a YDKe code or a text list to paste", Icons.Export, kbd(DeskAction.EXPORT), !narrow) {
            neue.menu = com.kaiharimoto.neue.kit.MenuSpec(neue.exportAnchor, CardActions.exportMenu(state, neue))
        }
    }
    Tool("Screenshot", "A picture of the deck, without the window around it", Icons.Camera, kbd(DeskAction.SCREENSHOT), !narrow, onScreenshot)
    // The pool's and the inspector's switches moved into the panes themselves (1.0.19).
    Box(Modifier.width(1.dp).height(20.dp).background(c.ink25))
    // Auto save (kai, 1.0.18): beside Save, and while it is on the deck is written a moment after each change.
    Tip(if (neue.prefs.autoSave) "Auto save: on. Every change is saved a moment after it is made. Click to turn off" else "Auto save: off. Click to save every change by itself") {
        com.kaiharimoto.neue.kit.WordToggle(if (narrow) "Auto" else "Auto save", neue.prefs.autoSave) { neue.update { it.copy(autoSave = !it.autoSave) } }
    }
    Tip(if (state.dirty) "Save the deck" else "Saved", kbd = kbd(DeskAction.SAVE)) {
        MuButton(if (neue.prefs.autoSave && !state.dirty) "Saved" else "Save", onSave, variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Icons.Save)
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
private fun ResizeRule(name: String, width: Float, scale: Float, touch: Boolean, onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val c = Mu.colors
    // A finger needs more than 7dp to find the edge (rec 1): on touch the grip is
    // 32dp wide, centred on the rule, with no layout width of its own — it lies
    // over the paper at the pane's and the deck's edges, never over a card.
    val grip = if (touch) 32.dp else 7.dp
    Box(Modifier.width(7.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        VRule(Modifier.width(if (hovered) 2.dp else 1.dp), color = c.ink)
        Box(
            Modifier
                .zenQuiet()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = grip.roundToPx(), maxWidth = grip.roundToPx()))
                    layout(7.dp.roundToPx(), p.height) { p.place((7.dp.roundToPx() - p.width) / 2, 0) }
                }
                .fillMaxHeight()
                .hoverable(source)
                // A drag: the family cursor names the pane and reads its width as it moves.
                .cursor(CursorMode.DRAG, caption = name, value = "${kotlin.math.round(width).toInt()} px")
                .draggable(
                    // Widths are physical (at a scale of one), so a drag's dp is multiplied back by the scale.
                    rememberDraggableState { px -> onDrag(with(density) { px.toDp().value } * scale) },
                    Orientation.Horizontal,
                ),
        )
    }
}

/**
 * The history menu: the steps redo would put back, then the steps undo would take
 * back, newest first, each in words (`DeckHistory`). A click on a step goes to the
 * deck as it was just after it — undoing or redoing everything in between.
 */
fun historyMenu(state: DeckBuilderState): List<com.kaiharimoto.neue.kit.MenuEntry> {
    val view = state.history()
    return buildList {
        if (view.undone.isNotEmpty()) {
            add(com.kaiharimoto.neue.kit.MenuEntry("Undone"))
            view.undone.take(HISTORY_SHOWN).forEachIndexed { i, text ->
                add(com.kaiharimoto.neue.kit.MenuEntry(text, hint = "Redo ${i + 1}") { state.travel(-(i + 1)) })
            }
        }
        add(com.kaiharimoto.neue.kit.MenuEntry("Done", separatorBefore = view.undone.isNotEmpty()))
        if (view.done.isEmpty()) add(com.kaiharimoto.neue.kit.MenuEntry("Nothing yet", enabled = false))
        view.done.take(HISTORY_SHOWN).forEachIndexed { i, text ->
            // The newest is the deck as it is: going back to it is going nowhere.
            add(com.kaiharimoto.neue.kit.MenuEntry(text, hint = if (i == 0) "Now" else "Undo $i", enabled = i > 0) { state.travel(i) })
        }
        if (view.done.size > HISTORY_SHOWN) add(com.kaiharimoto.neue.kit.MenuEntry("${view.done.size - HISTORY_SHOWN} earlier", enabled = false))
    }
}

private const val HISTORY_SHOWN = 14
