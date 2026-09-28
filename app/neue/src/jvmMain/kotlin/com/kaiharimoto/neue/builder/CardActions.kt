package com.kaiharimoto.neue.builder

import com.kaiharimoto.mastertool.core.library.DeckCovers
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
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
import com.kaiharimoto.mastertool.core.ydk.DeckExportFormat
import com.kaiharimoto.mastertool.core.ydk.DeckText
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
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
        // The pool's Side switch trades its two adds.
        when (DeskMouse.forPool(action, neue.prefs.poolToSide)) {
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
                // While a group is being drawn up, a click on the deck is a vote, not a selection —
                // on the extra and side decks too (1.0.17: a group may hold any card of the deck).
                if (state.groupDraft != null) {
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

    /**
     * The card as one of the deck's covers in the library, or off them (kai,
     * 1.0.14: up to three, `DeckCovers`). By the passcode in the deck, so an
     * alternate artwork the deck holds is the picture on its cover.
     */
    private fun coverEntry(section: DeckSection, index: Int, state: DeckBuilderState, neue: NeueState): MenuEntry {
        val deckId = state.deckId
        val raw = state.deck[section].getOrNull(index)?.value
        val covers = deckId?.let { neue.prefs.covers[it] }.orEmpty()
        val on = raw != null && raw in covers
        return MenuEntry(
            if (on) "Take off the deck's cover" else "Put on the deck's cover",
            hint = "${covers.size} of ${DeckCovers.MAX}",
            enabled = deckId != null && raw != null,
            separatorBefore = true,
            reason = "Save the deck first",
        ) {
            if (deckId != null && raw != null) {
                neue.update { p -> p.copy(covers = p.covers + (deckId to DeckCovers.toggle(p.covers[deckId].orEmpty(), raw))) }
            }
        }
    }

    fun copyName(card: Card) = copy(card.name)

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    /**
     * Export, as kai asked for it in 1.0.15: a file — plain `.ydk`, or `.ydkx` with
     * the groups — or a line for the clipboard: the `ydke://` code the simulators
     * and deck sites paste, or the decklist as text.
     */
    fun exportMenu(state: DeckBuilderState, neue: NeueState): List<MenuEntry> = DeckExportFormat.entries.map { format ->
        MenuEntry(
            format.label,
            hint = when (format) {
                DeckExportFormat.YDK -> ".ydk"
                DeckExportFormat.YDKX -> ".ydkx"
                else -> "Copies"
            },
            separatorBefore = format == DeckExportFormat.YDKE,
        ) { export(format, state, neue) }
    }

    fun export(format: DeckExportFormat, state: DeckBuilderState, neue: NeueState) {
        when (format) {
            DeckExportFormat.YDK -> state.exportFile(withGroups = false)
            DeckExportFormat.YDKX -> state.exportFile(withGroups = true)
            DeckExportFormat.YDKE -> {
                copy(YdkeCodec.encode(state.deck))
                neue.note = com.kaiharimoto.neue.Note("YDKe code copied")
            }
            DeckExportFormat.TEXT -> {
                copy(DeckText.write(state.deck) { state.index.byId(it)?.name })
                neue.note = com.kaiharimoto.neue.Note("Decklist copied as text")
            }
        }
    }

    /** "Next artwork · 2 of 9", for a card printed with more than one picture (1.0.16); else nothing. */
    private fun artEntry(card: Card, neue: NeueState): MenuEntry? {
        val all = CardArt.arts(card)
        if (all.size < 2) return null
        val at = all.indexOf(neue.prefs.arts[card.id.value]?.let(::CardId) ?: card.id).coerceAtLeast(0) + 1
        return MenuEntry("Next artwork · $at of ${all.size}", hint = "A", separatorBefore = true) { neue.stepArt(card, 1) }
    }

    fun poolMenu(card: Card, state: DeckBuilderState, neue: NeueState? = null): List<MenuEntry> {
        val home = card.requiredSection()
        val left = state.remaining(card)
        return listOfNotNull(
            MenuEntry(
                "Add to ${home.displayName.lowercase()} deck",
                hint = hint(MouseTarget.POOL, MouseAction.ADD),
                enabled = left > 0 && state.canDrop(card, null, home),
                reason = if (left <= 0) "No copies left" else "${home.displayName} deck is full",
            ) { add(state, card) },
            MenuEntry(
                "Add to side deck",
                hint = hint(MouseTarget.POOL, MouseAction.ADD_TO_SIDE),
                enabled = left > 0 && state.canDrop(card, null, DeckSection.SIDE),
                reason = if (left <= 0) "No copies left" else "Side deck is full",
            ) { add(state, card, toSide = true) },
            neue?.let { artEntry(card, it) },
            MenuEntry("Copy name", separatorBefore = true) { copyName(card) },
        )
    }

    fun deckMenu(card: Card, section: DeckSection, index: Int, state: DeckBuilderState, neue: NeueState): List<MenuEntry> {
        val home = card.requiredSection()
        val other = if (section == DeckSection.SIDE) home else DeckSection.SIDE
        val groups = state.groups.ordered()
        val current = state.groups.groupOf(card.id)
        return buildList {
            add(MenuEntry("Add a copy", hint = hint(MouseTarget.DECK, MouseAction.ADD_COPY), enabled = state.remaining(card) > 0, reason = "No copies left") { state.addCardAt(card, section, index + 1) })
            add(MenuEntry("Move to ${other.displayName.lowercase()} deck", enabled = state.canDrop(card, section, other), reason = "${other.displayName} deck is full") {
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
            add(MenuEntry("Manage groups", hint = "G") { state.useLens(com.kaiharimoto.mastertool.core.deck.Lens.ROLES) })
            artEntry(card, neue)?.let(::add)
            add(coverEntry(section, index, state, neue))
            add(MenuEntry("Copy name", separatorBefore = true) { copyName(card) })
            add(MenuEntry("Remove this copy", hint = hint(MouseTarget.DECK, MouseAction.REMOVE), danger = true, separatorBefore = true) { state.removeAt(card, section, index) })
            if (state.copiesIn(card.id, section) > 1) {
                add(MenuEntry("Remove all copies", danger = true) { state.removeAllCopies(card, section) })
            }
        }
    }
}
