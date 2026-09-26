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
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * What a card can be asked to do, written once for the context menu, the
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
            MouseAction.MENU -> neue.menu = MenuSpec(at, poolMenu(card, state))
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
            MouseAction.ADD_COPY -> if (state.remaining(card) > 0) state.addCardAt(card, section, index + 1)
            MouseAction.REMOVE -> {
                state.removeAt(card, section, index)
                val sel = neue.selection as? Selection.InDeck
                if (sel != null && sel.section == section && sel.index >= index) neue.selection = null
            }
            MouseAction.MENU -> {
                neue.selection = Selection.InDeck(card, section, index)
                neue.menu = MenuSpec(at, deckMenu(card, section, index, state, neue))
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
