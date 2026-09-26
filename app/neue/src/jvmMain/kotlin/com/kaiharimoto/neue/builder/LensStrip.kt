package com.kaiharimoto.neue.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.hand.LensOdds
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
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

/**
 * The lens over the main deck: which partition is drawn, and each key with its
 * count and its opening rate — "3 handtraps · 34%". Click a key to isolate it.
 *
 * While a group is being drawn up the strip becomes the draft: its name, its
 * colour, how many cards are in it, and Save. Clicks on the main deck add and
 * remove cards from it until then — the one modal gesture in the builder, as on
 * the tablet.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LensStrip(state: DeckBuilderState, neue: NeueState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                modifier = Modifier.width(160.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GroupMarkers.hues.forEachIndexed { i, hue ->
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(hue)
                            .border(if (draft.color == i) 2.dp else 0.dp, if (draft.color == i) c.ink else Color.Transparent)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.setDraftColor(i) },
                    )
                }
            }
            Mono("${draft.selection.size} cards", color = c.ink45)
            Small("Click cards in the main deck to add or remove them", Modifier.weight(1f), color = c.ink45, maxLines = 1)
            if (!draft.isNew) {
                Tip("Delete the group. Its cards stay in the deck") {
                    MuButton("Delete group", state::deleteDraftGroup, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Trash)
                }
            }
            MuButton("Cancel", state::cancelGroupDraft, variant = BtnVariant.GHOST, size = BtnSize.SM)
            MuButton("Save group", state::saveGroupDraft, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
            return@Row
        }

        val keying = state.keying(DeckSection.MAIN)
        val odds = LensOdds.atLeastOne(keying, state.deck.main.size)
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.lens != Lens.DECK && keying.keys.isEmpty()) {
                Small(
                    if (state.lens == Lens.ROLES) "No groups yet. Press N, or Shift right-click a card in the deck." else "Nothing to show.",
                    color = c.ink45,
                    maxLines = 1,
                )
            }
            keying.keys.forEach { key ->
                val isolated = state.isolatedKey == key.id
                val source = remember(key.id) { MutableInteractionSource() }
                val hovered by source.collectIsHoveredAsState()
                // A key on the Roles lens is a group the user drew, and can be changed from here.
                val group = if (state.lens == Lens.ROLES) state.groups.byId(key.id) else null
                var at by remember(key.id) { mutableStateOf(Offset.Zero) }
                Tip(
                    "Isolate ${key.label.lowercase()}. Chance of opening at least one in five cards" +
                        if (group != null) ". Right-click to edit or delete it" else "",
                ) {
                    Row(
                        Modifier
                            .height(28.dp)
                            .onGloballyPositioned { at = it.positionInWindow() }
                            .background(animatedColor(if (isolated) c.ink else Color.Transparent))
                            .border(1.dp, if (isolated || hovered) c.ink else c.ink25)
                            .hoverable(source)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .onPointerEvent(PointerEventType.Press) { event ->
                                if (group != null && event.buttons.isSecondaryPressed) {
                                    val p = event.changes.first().position
                                    neue.menu = MenuSpec(
                                        at + p,
                                        listOf(
                                            MenuEntry("Edit cards in “${group.name}”") { state.editGroup(group) },
                                            MenuEntry("Rename or recolour", hint = "G") { neue.drawer = Drawer.GROUPS },
                                            MenuEntry("Delete group", danger = true, separatorBefore = true) {
                                                com.kaiharimoto.neue.shell.deleteGroup(state, group.id)
                                            },
                                        ),
                                    )
                                }
                            }
                            .clickable(interactionSource = source, indication = null) { state.toggleIsolation(key.id) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(GroupMarkers.paint(key.paint, c.ink)).border(1.dp, if (isolated) c.paper else c.ink))
                        Small(key.label, color = if (isolated) c.paper else c.ink, maxLines = 1)
                        Mono(keying.countOf(key.id).toString(), color = if (isolated) c.paper.copy(alpha = 0.7f) else c.ink45)
                        odds[key.id]?.let { Mono(percent(it), color = if (isolated) c.paper else c.ink) }
                    }
                }
            }
        }
        if (state.lens == Lens.ROLES && state.groups.groups.isNotEmpty()) {
            MicroLink("Edit groups", { neue.drawer = Drawer.GROUPS })
        }
        if (state.lens == Lens.ROLES || state.lens == Lens.DECK) {
            MicroLink("+ New group", { state.startGroupDraft() })
        }
    }
}
