package com.kaiharimoto.neue.builder

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
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
fun GroupsPanel(state: DeckBuilderState, neue: NeueState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    // Centred down the column (kai, 1.0.16): the column is taller than its groups, and
    // their top edge under the window's bar is where a reach for a row brought the bar
    // out instead. A list taller than the column still starts at the top and scrolls.
    BoxWithConstraints(
        modifier
            .width(GROUPS_PANEL)
            .fillMaxHeight()
            .drawBehind { drawLine(c.ink, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx()) },
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
            Small("Click cards in the main deck to add or remove them", color = c.ink70)
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
        Micro("Groups", color = c.ink70)
        if (groups.isEmpty()) {
            Small("No groups yet. Press N, or hold a card in the deck and choose New group from this card.", color = c.ink70)
        }
        groups.forEachIndexed { i, group ->
            GroupRow(
                state = state,
                neue = neue,
                group = group,
                count = keying.countOf(group.id),
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
            Small("Click a colour square to see that group alone. Right-click a group for the rest.", Modifier.padding(top = 4.dp), color = c.ink45)
        }
    }
}
}

/** One group, editable where it stands. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun GroupRow(
    state: DeckBuilderState,
    neue: NeueState,
    group: com.kaiharimoto.mastertool.core.deck.DeckGroup,
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
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { at = it.positionInWindow() }
            .border(1.dp, if (isolated || hovered) c.ink else c.ink25)
            .hoverable(source)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    neue.menu = MenuSpec(
                        at + event.changes.first().position,
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
            // The colour square isolates: the one place on the row that is not an edit.
            Tip(if (isolated) "Show every group" else "See this group alone. Chance of opening at least one in five cards is on the right") {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(GroupMarkers.hue(group.color))
                        .border(if (isolated) 2.dp else 1.dp, c.ink)
                        .cursorPointer(caption = if (isolated) "Show all" else "Isolate")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.toggleIsolation(group.id) },
                )
            }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            GroupMarkers.hues.forEachIndexed { h, hue ->
                Box(
                    Modifier
                        .size(14.dp)
                        .background(hue)
                        .border(if (group.color == h) 2.dp else 0.dp, if (group.color == h) c.ink else Color.Transparent)
                        .cursorPointer(caption = "Colour")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            state.updateGroups { it.upsert(group.copy(color = h)) }
                        },
                )
            }
            Box(Modifier.weight(1f))
            IconButton(Icons.Pencil, { state.editGroup(group) }, size = 24.dp, label = "Edit cards")
            IconButton(Icons.ArrowUp, { state.updateGroups { it.reorder(group.id, index - 1) } }, enabled = !first, size = 24.dp, label = "Move up", reason = "Already first")
            IconButton(Icons.ArrowDown, { state.updateGroups { it.reorder(group.id, index + 1) } }, enabled = !last, size = 24.dp, label = "Move down", reason = "Already last")
            IconButton(Icons.Trash, { com.kaiharimoto.neue.shell.deleteGroup(state, group.id) }, size = 24.dp, label = "Delete")
        }
    }
}
