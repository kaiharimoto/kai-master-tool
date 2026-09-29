package com.kaiharimoto.neue.builder

import com.kaiharimoto.mastertool.core.deck.DeckHistory
import com.kaiharimoto.mastertool.core.haptics.DeskEvent
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
import com.kaiharimoto.mastertool.core.ydk.DeckExportFormat
import com.kaiharimoto.mastertool.core.ydk.DeckText
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import com.kaiharimoto.neue.platform.QrScan
import com.kaiharimoto.neue.platform.QrSource

/**
 * What a card can be asked to do, written once for the card viewer, the
 * palette and the keyboard, so the three cannot drift.
 */
object CardActions {

    /** Where a card from the pool goes when it is simply "added": the extra deck for extra-deck cards, else the main. */
    fun add(state: DeckBuilderState, card: Card, toSide: Boolean = false, neue: NeueState? = null) {
        val section = if (toSide) DeckSection.SIDE else card.requiredSection()
        val before = state.deck[section]
        val added = state.addCard(card, section)
        if (neue == null) return
        // A finger's add is felt, and ringed where it landed (touch swarm, rec 13 and 15).
        neue.felt(if (added) DeskEvent.ADDED else DeskEvent.ADD_REFUSED)
        if (added && neue.fingerActing) {
            DeckHistory.addedAt(before, state.deck[section])?.let { state.revealAt(section, it) }
        }
    }

    /** The gesture beside a menu entry: a finger's on the tablet (`DeskTouch`), where there is one, else the mouse's. */
    private fun hint(target: MouseTarget, action: MouseAction): String? =
        if (com.kaiharimoto.neue.platform.Platform.os == com.kaiharimoto.mastertool.core.update.DesktopOs.ANDROID) {
            com.kaiharimoto.mastertool.core.input.DeskTouch.all
                .firstOrNull { it.target == target && it.action == action }?.gesture?.label
        } else {
            DeskMouse.gestureFor(target, action)?.label
        }

    /** A mouse gesture on a pool card, as `DeskMouse` resolved it. */
    fun onPool(action: MouseAction, at: Offset, card: Card, row: Int, state: DeckBuilderState, neue: NeueState) {
        // On a phone a finger's tap opens the card large, once no second tap follows (v1.3.5).
        neue.cancelViewSoon()
        // The pool's Side switch trades its two adds.
        when (DeskMouse.forPool(action, neue.prefs.poolToSide)) {
            MouseAction.SELECT -> {
                neue.selection = Selection.InPool(card, row)
                if (neue.phone && neue.fingerActing) neue.viewSoon(Viewing(card, null, row))
            }
            MouseAction.ADD -> add(state, card, neue = neue)
            MouseAction.ADD_TO_SIDE -> add(state, card, toSide = true, neue = neue)
            MouseAction.VIEW -> {
                neue.selection = Selection.InPool(card, row)
                neue.viewing = Viewing(card, null, row)
                neue.felt(DeskEvent.HOLD_OPENED)
            }
            MouseAction.ADD_COPY, MouseAction.REMOVE, MouseAction.INSPECT, MouseAction.PICK_UP -> Unit
        }
    }

    /** A mouse gesture on a deck card, as `DeskMouse` resolved it. */
    fun onDeck(action: MouseAction, at: Offset, card: Card, section: DeckSection, index: Int, state: DeckBuilderState, neue: NeueState) {
        neue.noteAction("deck $action ${card.id.value}@$index finger=${neue.fingerActing}")
        neue.cancelViewSoon()
        when (action) {
            MouseAction.SELECT -> {
                // While a group is being drawn up, a click on the deck is a vote, not a selection —
                // on the extra and side decks too (1.0.17: a group may hold any card of the deck).
                if (state.groupDraft != null) {
                    state.toggleDraftSelection(card.id)
                } else {
                    neue.selection = Selection.InDeck(card, section, index)
                    // On a phone there is no inspector: a finger's tap opens the card large (v1.3.5).
                    if (neue.phone && neue.fingerActing) neue.viewSoon(Viewing(card, section, index))
                }
            }
            // Another copy into the main deck — beside this one when it is already there
            // (or in the extra deck, which the rules keep it in), at the end from the side.
            // Drawing up a group, the deck is being chosen from, not edited.
            MouseAction.ADD_COPY -> if (state.groupDraft == null && state.remaining(card) > 0) {
                val home = card.requiredSection()
                if (section == home) state.addCardAt(card, section, index + 1) else state.addCard(card, home)
            }
            // Drawing up a group, the deck is being chosen from, not edited — a right-click
            // there as much as a finger's double-tap (touch swarm, rec 7).
            MouseAction.REMOVE -> if (state.groupDraft == null) {
                if (state.removeAt(card, section, index)) {
                    neue.felt(DeskEvent.REMOVED)
                    // The card that closed the gap is ringed, so a finger sees where the copy went from.
                    if (neue.fingerActing) state.revealAt(section, index)
                }
                val sel = neue.selection as? Selection.InDeck
                if (sel != null && sel.section == section && sel.index >= index) neue.selection = null
            }
            MouseAction.VIEW -> {
                neue.selection = Selection.InDeck(card, section, index)
                neue.viewing = Viewing(card, section, index)
                neue.felt(DeskEvent.HOLD_OPENED)
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

    fun copy(text: String) = com.kaiharimoto.neue.platform.Platform.copy(text)

    /**
     * Export, as kai asked for it in 1.0.15: a file — plain `.ydk`, or `.ydkx` with
     * the groups — or a line for the clipboard: the `ydke://` code the simulators
     * and deck sites paste, or the decklist as text.
     */
    fun exportMenu(state: DeckBuilderState, neue: NeueState): List<MenuEntry> = DeckExportFormat.entries.map { format ->
        exportEntry(format, empty = state.deck.isEmpty) { export(format, state, neue) }
    } + shareEntries(
        code = { YdkeCodec.encode(state.deck) },
        name = state.deckName,
        file = { state.shareDeck() },
    )

    /** One line of an Export menu, the builder's or a library row's: what it makes, and where that goes. */
    fun exportEntry(format: DeckExportFormat, empty: Boolean, onClick: () -> Unit) = MenuEntry(
        format.label,
        hint = when (format) {
            DeckExportFormat.YDK -> ".ydk"
            DeckExportFormat.YDKX -> ".ydkx"
            DeckExportFormat.YDKE, DeckExportFormat.TEXT -> "Copies"
            DeckExportFormat.QR -> "Shows"
        },
        separatorBefore = format == DeckExportFormat.YDKE || format == DeckExportFormat.QR,
        enabled = format != DeckExportFormat.QR || !empty,
        reason = "The deck is empty",
        onClick = onClick,
    )

    /** [deck] as a QR code on the screen (1.0.30), for a phone or a tablet to scan. */
    fun showQr(name: String, deck: com.kaiharimoto.mastertool.core.model.Deck, neue: NeueState) {
        neue.qr = com.kaiharimoto.neue.qr.QrShown(name.ifBlank { "Untitled Deck" }, deck, YdkeCodec.encode(deck))
    }

    /**
     * Import on a phone or a tablet (v1.3.7): a deck file, or a deck's QR code —
     * scanned with the camera off another screen (the desk's Export → QR code), or
     * found in a picture of one, a screenshot a friend sent. The desk imports a file
     * at once and has no menu.
     */
    fun importMenu(state: DeckBuilderState, neue: NeueState): List<MenuEntry> {
        val sources = com.kaiharimoto.neue.platform.Platform.scanSources
        return buildList {
            add(MenuEntry("A .ydk or .ydkx file") { importFile(state, neue) })
            if (sources.isNotEmpty()) {
                add(MenuEntry("Scan a QR code", hint = "Camera", separatorBefore = true, enabled = QrSource.CAMERA in sources, reason = "No camera") {
                    scan(QrSource.CAMERA, state, neue)
                })
                add(MenuEntry("A picture of a QR code", enabled = QrSource.PICTURE in sources) { scan(QrSource.PICTURE, state, neue) })
            }
        }
    }

    /** Import, as the desk's button and `Ctrl O` do it: the file picker, then the builder. */
    fun importFile(state: DeckBuilderState, neue: NeueState) {
        state.importFromFile()
        neue.go(com.kaiharimoto.neue.Page.BUILDER)
    }

    /** A deck read off a QR code (v1.3.7) replaces the one on the builder, as a file does, with Undo on the toast. */
    fun scan(from: QrSource, state: DeckBuilderState, neue: NeueState) {
        state.importFrom("Scanned deck") {
            when (val scan = com.kaiharimoto.neue.platform.Platform.scanQr(from)) {
                is QrScan.Read -> {
                    neue.go(com.kaiharimoto.neue.Page.BUILDER)
                    scan.text
                }
                QrScan.Cancelled -> null
                QrScan.NotFound -> {
                    neue.note = com.kaiharimoto.neue.Note("No QR code found in that picture")
                    null
                }
                QrScan.NoCamera -> {
                    neue.note = com.kaiharimoto.neue.Note("The camera could not be opened. Allow it in the app's settings")
                    null
                }
            }
        }
    }

    /**
     * Sharing the Android way (touch swarm, rec 25): the code to a chat, or the file
     * to anything that takes one, through the system's share sheet — where copying
     * meant leaving the app, finding the chat and pasting. Nothing on the desk.
     */
    fun shareEntries(code: () -> String, name: String, file: () -> Unit): List<MenuEntry> {
        val platform = com.kaiharimoto.neue.platform.Platform
        if (!platform.canShare) return emptyList()
        return listOf(
            MenuEntry("Share YDKe code…", separatorBefore = true) { platform.shareText(code(), name.ifBlank { "Deck" }) },
            MenuEntry("Share .ydkx file…", onClick = file),
        )
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
            DeckExportFormat.QR -> if (!state.deck.isEmpty) showQr(state.deckName, state.deck, neue)
        }
    }

    /** "Next artwork · 2 of 9", for a card printed with more than one picture (1.0.16); else nothing. */
    private fun artEntry(card: Card, neue: NeueState): MenuEntry? {
        val all = neue.artChoices(card)
        if (all.size < 2) return null
        val at = all.indexOf(neue.prefs.arts[card.id.value] ?: card.id.value).coerceAtLeast(0) + 1
        return MenuEntry("Next artwork · $at of ${all.size}", hint = "A", separatorBefore = true) { neue.stepArt(card, 1) }
    }

    /** Your own picture off the card, behind a confirm: on a tablet it lives here, not in the inspector's row (rec 24). */
    private fun removeArtEntry(card: Card, neue: NeueState): MenuEntry? {
        val chosen = neue.prefs.arts[card.id.value]?.takeIf { it < 0 } ?: return null
        return MenuEntry("Remove your picture", danger = true) { neue.confirmRemoveArt = card to -chosen }
    }

    /**
     * The card onto a list of cards kept for consideration, or off it (1.0.19):
     * the active list first, marked `L`; with no list yet, one is made.
     */
    fun listEntries(card: Card, neue: NeueState): List<MenuEntry> {
        val lists = neue.prefs.cardLists
        if (lists.isEmpty()) return listOf(MenuEntry("Put on a new list", hint = "L", separatorBefore = true) { neue.toggleOnList(card, null) })
        val active = neue.activeList
        return lists.sortedByDescending { it.id == active?.id }.mapIndexed { i, list ->
            val on = card.id.value in list.ids
            MenuEntry(if (on) "Take off ${list.name}" else "Put on ${list.name}", hint = if (list.id == active?.id) "L" else null, separatorBefore = i == 0) {
                neue.toggleOnList(card, list.id)
            }
        }
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
            neue?.let { removeArtEntry(card, it) },
            MenuEntry("Copy name", separatorBefore = true) { copyName(card) },
        ) + (neue?.let { listEntries(card, it) } ?: emptyList())
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
            removeArtEntry(card, neue)?.let(::add)
            add(coverEntry(section, index, state, neue))
            addAll(listEntries(card, neue))
            add(MenuEntry("Copy name", separatorBefore = true) { copyName(card) })
            add(MenuEntry("Remove this copy", hint = hint(MouseTarget.DECK, MouseAction.REMOVE), danger = true, separatorBefore = true) { state.removeAt(card, section, index) })
            if (state.copiesIn(card.id, section) > 1) {
                add(MenuEntry("Remove all copies", danger = true) { state.removeAllCopies(card, section) })
            }
        }
    }
}
