package com.kaiharimoto.neue.builder

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.kit.MenuEntry
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

    fun copyName(card: Card) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(card.name), null) }
    }

    fun poolMenu(card: Card, state: DeckBuilderState): List<MenuEntry> {
        val home = card.requiredSection()
        val left = state.remaining(card)
        return listOf(
            MenuEntry(
                "Add to ${home.displayName.lowercase()} deck",
                hint = "Double-click",
                enabled = left > 0 && state.canDrop(card, null, home),
            ) { add(state, card) },
            MenuEntry(
                "Add to side deck",
                hint = "Shift double-click",
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
            add(MenuEntry("Add a copy", enabled = state.remaining(card) > 0) { state.addCard(card, section) })
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
            add(MenuEntry("Manage groups", hint = "G") { neue.drawer = Drawer.GROUPS })
            add(MenuEntry("Copy name", separatorBefore = true) { copyName(card) })
            add(MenuEntry("Remove this copy", hint = "Del", danger = true, separatorBefore = true) { state.removeAt(card, section, index) })
            if (state.copiesIn(card.id, section) > 1) {
                add(MenuEntry("Remove all copies", danger = true) { state.removeAllCopies(card, section) })
            }
        }
    }
}
