package com.kaiharimoto.neue.builder

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.neue.NeueState
import androidx.compose.ui.geometry.Offset
import com.kaiharimoto.mastertool.core.input.DeskMouse
import com.kaiharimoto.mastertool.core.input.MouseAction
import com.kaiharimoto.mastertool.core.input.MouseTarget
import com.kaiharimoto.neue.Selection
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.kit.MenuEntry
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * What a card can be asked to do, written once for the card viewer, the
 * palette and the keyboard, so the three cannot drift.
 */
object CardActions {

    /** Where a card from the pool goes when it is simply "added": the extra deck for extra-deck cards, else the main. */
    fun add(state: DeckBuilderState, card: Card, toSide: Boolean = false) {
        state.addCard(card, if (toSide) DeckSection.SIDE else card.requiredSection())
    }

    private fun hint(target: MouseTarget, action: MouseAction) = DeskMouse.gestureFor(target, action)?.label

    /** A mouse gesture on a pool card, as `DeskMouse` resolved it. */
    fun onPool(action: MouseAction, at: Offset, card: Card, row: Int, state: DeckBuilderState, neue: NeueState) {
        when (action) {
            MouseAction.SELECT -> neue.selection = Selection.InPool(card, row)
            MouseAction.ADD -> add(state, card)
            MouseAction.ADD_TO_SIDE -> add(state, card, toSide = true)
            MouseAction.VIEW -> {
                neue.selection = Selection.InPool(card, row)
                neue.viewing = Viewing(card, null, row)
            }
            MouseAction.ADD_COPY, MouseAction.REMOVE, MouseAction.INSPECT, MouseAction.PICK_UP -> Unit
        }
    }

    /** A mouse gesture on a deck card, as `DeskMouse` resolved it. */
    fun onDeck(action: MouseAction, at: Offset, card: Card, section: DeckSection, index: Int, state: DeckBuilderState, neue: NeueState) {
        when (action) {
            MouseAction.SELECT -> {
                // While a group is being drawn up, a click on the main deck is a vote, not a selection.
                if (state.groupDraft != null && section == DeckSection.MAIN) {
                    state.toggleDraftSelection(card.id)
                } else {
                    neue.selection = Selection.InDeck(card, section, index)
                }
            }
            // Another copy into the main deck — beside this one when it is already there
            // (or in the extra deck, which the rules keep it in), at the end from the side.
            // Drawing up a group, the deck is being chosen from, not edited.
            MouseAction.ADD_COPY -> if (state.groupDraft == null && state.remaining(card) > 0) {
                val home = card.requiredSection()
                if (section == home) state.addCardAt(card, section, index + 1) else state.addCard(card, home)
            }
            MouseAction.REMOVE -> {
                state.removeAt(card, section, index)
                val sel = neue.selection as? Selection.InDeck
                if (sel != null && sel.section == section && sel.index >= index) neue.selection = null
            }
            MouseAction.VIEW -> {
                neue.selection = Selection.InDeck(card, section, index)
                neue.viewing = Viewing(card, section, index)
            }
            MouseAction.ADD, MouseAction.ADD_TO_SIDE, MouseAction.INSPECT, MouseAction.PICK_UP -> Unit
        }
    }

    fun copyName(card: Card) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(card.name), null) }
    }

    fun poolMenu(card: Card, state: DeckBuilderState): List<MenuEntry> {
        val home = card.requiredSection()
        val left = state.remaining(card)
        return listOf(
            MenuEntry(
                "Add to ${home.displayName.lowercase()} deck",
                hint = hint(MouseTarget.POOL, MouseAction.ADD),
                enabled = left > 0 && state.canDrop(card, null, home),
            ) { add(state, card) },
            MenuEntry(
                "Add to side deck",
                hint = hint(MouseTarget.POOL, MouseAction.ADD_TO_SIDE),
                enabled = left > 0 && state.canDrop(card, null, DeckSection.SIDE),
            ) { add(state, card, toSide = true) },
            MenuEntry("Copy name", separatorBefore = true) { copyName(card) },
        )
    }

    fun deckMenu(card: Card, section: DeckSection, index: Int, state: DeckBuilderState, neue: NeueState): List<MenuEntry> {
        val home = card.requiredSection()
        val other = if (section == DeckSection.SIDE) home else DeckSection.SIDE
        val groups = state.groups.ordered()
        val current = state.groups.groupOf(card.id)
        return buildList {
            add(MenuEntry("Add a copy", hint = hint(MouseTarget.DECK, MouseAction.ADD_COPY), enabled = state.remaining(card) > 0) { state.addCardAt(card, section, index + 1) })
            add(MenuEntry("Move to ${other.displayName.lowercase()} deck", enabled = state.canDrop(card, section, other)) {
                state.moveCardTo(card, section, index, other, state.deck[other].size)
            })
            add(MenuEntry("Group", separatorBefore = true))
            groups.forEach { group ->
                add(MenuEntry(group.name, hint = if (group.id == current) "✓" else null) { state.assignCardToGroup(card.id, group.id) })
            }
            if (current != null) add(MenuEntry("No group") { state.assignCardToGroup(card.id, null) })
            add(MenuEntry("New group from this card") {
                state.startGroupDraft(seed = card.id)
            })
            if (current != null) {
                groups.firstOrNull { it.id == current }?.let { group ->
                    add(MenuEntry("Edit “${group.name}”") { state.editGroup(group) })
                }
            }
            add(MenuEntry("Manage groups", hint = "G") { neue.drawer = Drawer.GROUPS })
            add(MenuEntry("Copy name", separatorBefore = true) { copyName(card) })
            add(MenuEntry("Remove this copy", hint = hint(MouseTarget.DECK, MouseAction.REMOVE), danger = true, separatorBefore = true) { state.removeAt(card, section, index) })
            if (state.copiesIn(card.id, section) > 1) {
                add(MenuEntry("Remove all copies", danger = true) { state.removeAllCopies(card, section) })
            }
        }
    }
}
