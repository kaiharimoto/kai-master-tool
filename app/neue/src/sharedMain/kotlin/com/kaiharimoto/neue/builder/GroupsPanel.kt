package com.kaiharimoto.neue.builder

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.input.DeskWords
import com.kaiharimoto.mastertool.core.input.TouchMetrics
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.collectIsHotAsState
import androidx.compose.foundation.layout.imePadding
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.neue.kit.onContextMenu
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.hand.LensOdds
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.percent
import com.kaiharimoto.neue.theme.Mu

/** How wide the Groups panel is, beside the deck: declared to the fitter, which takes it off the cards. */
val GROUPS_PANEL: Dp = 288.dp

/**
 * The user's groups, down the right of the deck, each editable where it stands:
 * name, colour, count and opening rate, and Edit cards, reorder and Delete on the
 * row (1.0.15). Opened with the deck's pieces by the boxed **Groups** button (or
 * K): the Roles lens and this panel are one switch.
 *
 * While a group is being drawn up the panel becomes the draft: its name, its
 * colour, how many cards are in it, and Save. Clicks on the main deck add and
 * remove cards from it until then — the one modal gesture in the builder, as on
 * the tablet — and the panel is out for as long as it lasts.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun GroupsPanel(state: DeckBuilderState, neue: NeueState, modifier: Modifier = Modifier, fillWidth: Boolean = false) {
    val c = Mu.colors
    // Centred down the column (kai, 1.0.16): the column is taller than its groups, and
    // their top edge under the window's bar is where a reach for a row brought the bar
    // out instead. A list taller than the column still starts at the top and scrolls.
    // Where the panel is, for the window's watcher: a press anywhere else folds the palettes.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            neue.groupsPanel = null
            neue.groupPalettesOpen = false
        }
    }
    BoxWithConstraints(
        modifier
            // On a phone (v1.3.5) the panel is the dock's second tab, the width of the window.
            .let { if (fillWidth) it.fillMaxWidth() else it.width(GROUPS_PANEL) }
            .fillMaxHeight()
            .onGloballyPositioned { neue.groupsPanel = it.boundsInWindow() }
            .drawBehind { if (!fillWidth) drawLine(c.ink, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx()) }
            // A group's name being typed stays above the soft keyboard (touch swarm, rec 10).
            .imePadding(),
    ) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        val draft = state.groupDraft
        if (draft != null) {
            Micro(if (draft.isNew) "New group" else "Edit group", color = c.ink)
            MuInput(
                value = draft.name,
                onValueChange = state::setDraftName,
                placeholder = "Name it",
                dense = true,
                onFocusChange = state::onTextFieldFocusChanged,
                onSubmit = state::saveGroupDraft,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GroupMarkers.hues.forEachIndexed { i, hue ->
                    Box(
                        Modifier
                            .size(20.dp)
                            .background(hue)
                            .border(if (draft.color == i) 2.dp else 0.dp, if (draft.color == i) c.ink else Color.Transparent)
                            .cursorPointer(caption = "Pick")
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.setDraftColor(i) },
                    )
                }
            }
            Mono("${draft.selection.size} cards", color = c.ink70)
            Small("Click cards in the deck — main, extra or side — to add or remove them", color = c.ink70)
            MuButton("Save group", state::saveGroupDraft, variant = BtnVariant.PRIMARY, size = BtnSize.SM, modifier = Modifier.fillMaxWidth())
            MuButton("Cancel", state::cancelGroupDraft, variant = BtnVariant.GHOST, size = BtnSize.SM, modifier = Modifier.fillMaxWidth())
            if (!draft.isNew) {
                Tip("Delete the group. Its cards stay in the deck") {
                    MuButton("Delete group", state::deleteDraftGroup, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Trash, modifier = Modifier.fillMaxWidth())
                }
            }
            return@Column
        }

        // The groups themselves, each editable where it stands (kai, 1.0.15: "editing the
        // groups should be more accessible because that's what the roles tab is primarily
        // for"): its name is a field, its colour six swatches, and Edit cards, the arrows
        // and Delete are on the row. The drawer that used to hold all this is gone.
        val groups = state.groups.ordered()
        val keying = state.keying(DeckSection.MAIN)
        val odds = LensOdds.atLeastOne(keying, state.deck.main.size)
        // Which set these are, once the deck has more than one (2026-10); on a phone, whose deck
        // row has no room for it, the sets button itself.
        if (neue.phone) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Micro("Groups", color = c.ink70)
                GroupSetButton(state, neue, nameWidth = 160.dp)
            }
        } else {
            Micro(if (state.groupSets.isPlain) "Groups" else "Groups · ${state.groupSets.current.name}", color = c.ink70)
        }
        if (groups.isEmpty()) {
            Small("No groups yet. Press N, or hold a card in the deck and choose New group from this card.", color = c.ink70)
        }
        groups.forEachIndexed { i, group ->
            GroupRow(
                state = state,
                neue = neue,
                group = group,
                // Every section's cards: a group may hold extra- and side-deck cards (1.0.17).
                count = DeckSection.entries.sumOf { state.groups.countIn(state.deck[it], group.id) },
                odds = odds[group.id],
                first = i == 0,
                last = i == groups.lastIndex,
                index = i,
            )
        }
        // A button, not a link (kai, 1.0.16): making a group is what this column is for.
        Tip("Name a group, then click its cards in the main deck", kbd = "N") {
            MuButton("New group", { state.startGroupDraft() }, variant = BtnVariant.SECONDARY, size = BtnSize.MD, icon = Icons.Plus, modifier = Modifier.fillMaxWidth())
        }
        if (groups.isNotEmpty()) {
            Small(
                DeskWords.groupsHelp(LocalTouchFirst.current),
                Modifier.padding(top = 4.dp),
                color = c.ink45,
            )
        }
        GroupSlides(state, neue, Modifier.padding(top = 16.dp))
        PalettePicker(neue, Modifier.padding(top = 8.dp))
    }
}
}

/** One group, editable where it stands. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun GroupRow(
    state: DeckBuilderState,
    neue: NeueState,
    group: DeckGroup,
    count: Int,
    odds: Double?,
    first: Boolean,
    last: Boolean,
    index: Int,
) {
    val c = Mu.colors
    val isolated = state.isolatedKey == group.id
    val source = remember(group.id) { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var at by remember(group.id) { mutableStateOf(Offset.Zero) }
    // The swatches stay out while the pointer is on the square or on them, and a
    // moment after, so crossing the gap between the two does not put them away.
    val squareSource = remember(group.id) { MutableInteractionSource() }
    val swatchSource = remember(group.id) { MutableInteractionSource() }
    val onSquare by squareSource.collectIsHoveredAsState()
    val onSwatches by swatchSource.collectIsHoveredAsState()
    var lingering by remember(group.id) { mutableStateOf(false) }
    LaunchedEffect(onSquare, onSwatches) {
        if (onSquare || onSwatches) lingering = true else { delay(450); lingering = false }
    }
    // A finger cannot hover: on a touch screen a tap on the square brings the colours
    // out (and a second puts them away); isolating is on the row's held-finger menu.
    var tapped by remember(group.id) { mutableStateOf(false) }
    val swatchesOut = onSquare || onSwatches || lingering || tapped
    // A finger's sizes (touch swarm, rec 19): a 32dp square, 32dp swatches on a line of
    // their own, 40dp arrows 8dp apart, and Delete only on the row's hold menu.
    val touch = neue.touchFirst
    val square = if (touch) TouchMetrics.CHIP.dp else 16.dp
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { at = it.positionInWindow() }
            .border(1.dp, if (isolated || hovered) c.ink else c.ink25)
            .hoverable(source)
            // Right-click, or a finger held on the row (1.3.0).
            .onContextMenu { local ->
                run {
                    neue.menu = MenuSpec(
                        at + local,
                        listOf(
                            MenuEntry("Edit cards in “${group.name}”") { state.editGroup(group) },
                            MenuEntry(if (isolated) "Show every group" else "See it alone") { state.toggleIsolation(group.id) },
                            MenuEntry("Delete group", danger = true, separatorBefore = true) { com.kaiharimoto.neue.shell.deleteGroup(state, group.id) },
                        ),
                    )
                }
            }
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The colour square isolates on a click, and on a hover brings out the six
            // colours to choose from (kai, 1.0.18: all six on every row all the time was
            // a distraction). No tip over it: a tip is a window of its own, and moving
            // onto it would count as leaving the square.
            Box(
                Modifier
                    .size(square)
                    .background(GroupMarkers.hue(group.color))
                    .border(if (isolated) 2.dp else 1.dp, c.ink)
                    .hoverable(squareSource)
                    .cursorPointer(caption = if (isolated) "Show all" else "Isolate")
                    .muClickable(interactionSource = squareSource) {
                        if (neue.touchFirst) tapped = !tapped else state.toggleIsolation(group.id)
                    },
            )
            // Renamed where it stands: written once on Enter or on leaving the field,
            // so a name is one step of undo rather than one per letter.
            var text by remember(group.id, group.name) { mutableStateOf(group.name) }
            fun commit() {
                val name = text.trim()
                if (name.isNotEmpty() && name != group.name) state.updateGroups { it.upsert(group.copy(name = name)) }
                if (name.isEmpty()) text = group.name
            }
            MuInput(
                value = text,
                onValueChange = { text = it },
                placeholder = "Name it",
                dense = true,
                onFocusChange = { focused ->
                    state.onTextFieldFocusChanged(focused)
                    if (!focused) commit()
                },
                onSubmit = ::commit,
                modifier = Modifier.weight(1f),
            )
            Mono(count.toString(), color = c.ink70)
            odds?.let { Mono(percent(it), color = c.ink) }
        }
        val swatches: @Composable () -> Unit = {
            Row(Modifier.hoverable(swatchSource), horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 3.dp)) {
                GroupMarkers.hues.forEachIndexed { h, hue ->
                    Box(
                        Modifier
                            .size(if (touch) TouchMetrics.CHIP.dp else 14.dp)
                            .background(hue)
                            .border(if (group.color == h) 2.dp else 0.dp, if (group.color == h) c.ink else Color.Transparent)
                            .cursorPointer(caption = "Colour")
                            .muClickable {
                                state.updateGroups { it.upsert(group.copy(color = h)) }
                                tapped = false
                            },
                    )
                }
            }
        }
        val icon = if (touch) TouchMetrics.ICON.dp else 24.dp
        if (touch && swatchesOut) swatches()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 3.dp)) {
            if (!touch && swatchesOut) swatches()
            Box(Modifier.weight(1f))
            IconButton(Icons.Pencil, { state.editGroup(group) }, size = icon, label = "Edit cards")
            IconButton(Icons.ArrowUp, { state.updateGroups { it.reorder(group.id, index - 1) } }, enabled = !first, size = icon, label = "Move up", reason = "Already first")
            IconButton(Icons.ArrowDown, { state.updateGroups { it.reorder(group.id, index + 1) } }, enabled = !last, size = icon, label = "Move down", reason = "Already last")
            // Beside Move down a thumb could delete a group: on a tablet it is on the hold menu only.
            if (!touch) IconButton(Icons.Trash, { com.kaiharimoto.neue.shell.deleteGroup(state, group.id) }, size = 24.dp, label = "Delete")
        }
    }
}

/**
 * The groups' palette (kai, 1.0.17: "more choices in color palettes with the
 * empty space we have in the groups column"): every palette as a row of its six
 * colours under its name, the one in use ruled in ink. Choosing one recolours
 * every group at once and changes nothing in the deck (`GroupMarkers.palettes`).
 */
@Composable
private fun PalettePicker(neue: NeueState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    // Folded to the one in use (kai, 1.0.18): the others come out on a click, and stay
    // out while one is tried after another (1.0.24) — kai: "the user is most likely going
    // to choose between the palettes to their liking". A press off the Groups panel, Esc,
    // or the header again folds them (`NeueState.groupPalettesOpen`).
    val open = neue.groupPalettesOpen
    // A draft takes the picker's place: it comes back folded.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { neue.groupPalettesOpen = false } }
    val current = GroupMarkers.byId(neue.prefs.groupPalette)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val headSource = remember { MutableInteractionSource() }
        val headHovered by headSource.collectIsHoveredAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, if (open || headHovered) c.ink else c.ink25)
                .hoverable(headSource)
                .cursorPointer(caption = if (open) "Fold" else "Palettes")
                .clickable(interactionSource = headSource, indication = null) { neue.groupPalettesOpen = !open }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Micro("Palette", color = c.ink70)
            Small(current.name, Modifier.weight(1f), color = c.ink, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { current.colors.forEach { Box(Modifier.size(10.dp).background(it)) } }
            Mono(if (open) "−" else "▾", color = c.ink70)
        }
        if (open) GroupMarkers.palettes.forEach { palette ->
            val chosen = neue.prefs.groupPalette == palette.id
            val source = remember(palette.id) { MutableInteractionSource() }
            val hovered by source.collectIsHotAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (chosen) c.ink else if (hovered) c.ink45 else c.ink12)
                    .hoverable(source)
                    .cursorPointer(caption = if (chosen) "In use" else "Use")
                    .clickable(interactionSource = source, indication = null) {
                        neue.update { it.copy(groupPalette = palette.id) }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Small(palette.name, Modifier.weight(1f), color = if (chosen) c.ink else c.ink70, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    palette.colors.forEach { Box(Modifier.size(14.dp).background(it)) }
                }
            }
        }
    }
}
