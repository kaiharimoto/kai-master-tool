package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.neue.ai.openWizard
import com.kaiharimoto.neue.ai.askTune
import com.kaiharimoto.neue.ai.openGuide
import com.kaiharimoto.neue.ai.openBook
import com.kaiharimoto.neue.ai.openProfile
import androidx.compose.ui.geometry.Offset
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.ydk.DeckExportFormat
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.builder.groupsOn
import com.kaiharimoto.neue.builder.historyMenu
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.QrSource
import com.kaiharimoto.neue.platform.reportIssue
import com.kaiharimoto.neue.shell.Command

// Every tool in one list, on [NeueHolders]: the phone's overflow menu and the command palette.

/**
 * The phone's overflow (v1.3.5): every tool of the desk's bar, which a phone has no
 * room to lay out, in one menu under the thumb. [at] is where it opened, so its
 * second menus (Export, History) open in the same place.
 */
fun NeueHolders.phoneMenu(at: Offset): List<MenuEntry> {
    val state = builder
    val onBuilder = neue.page == Page.BUILDER
    val next = neue.orientation.next()
    return buildList {
        add(MenuEntry("Search cards and commands", hint = "Search") { neue.paletteOpen = true })
        if (neue.prefs.ai.enabled) add(MenuEntry(ai.name, hint = "Your assistant") { ai.setOpen(true) })
        if (neue.prefs.ai.enabled) add(MenuEntry("Look into ${ai.name}", hint = "What it knows") { ai.memoryOpen = "USER.md" })
        add(MenuEntry("Advanced search") { run(DeskAction.ADVANCED_SEARCH) })
        add(MenuEntry("Present", hint = "Deck profiles as slides") { neue.go(Page.PRESENT) })
        add(MenuEntry("Duel", hint = "The duel simulator") { neue.go(Page.DUEL) })
        add(MenuEntry("Ai World", hint = "Ai's own computer, watched") { neue.go(Page.WORLD) })
        if (onBuilder) {
            add(MenuEntry(if (groupsOn(state)) "Hide the groups" else "Groups", hint = "The deck in pieces") { run(DeskAction.TOGGLE_KEYS) })
            add(MenuEntry("History…", enabled = state.canUndo || state.canRedo, reason = "Nothing changed yet") {
                neue.menu = MenuSpec(at, historyMenu(state, touch = true))
            })
            add(MenuEntry("Format: ${state.format.name}", hint = "Switch to ${if (state.format == Format.TCG) "OCG" else "TCG"}") {
                setFormat(if (state.format == Format.TCG) Format.OCG else Format.TCG)
            })
            // Always there (1.1.1): what the deck is checked against is chosen in the drawer.
            val count = state.validation.errors.size + state.validation.warnings.size
            add(MenuEntry(if (count > 0) "Issues" else "Legality", hint = if (count > 0) "$count" else state.rulesInForce.words()) { neue.drawer = Drawer.ISSUES })
        }
        add(MenuEntry(if (state.dirty) "Save" else "Saved", separatorBefore = true, enabled = state.dirty || !neue.prefs.autoSave) { run(DeskAction.SAVE) })
        add(MenuEntry("Auto save: ${if (neue.prefs.autoSave) "on" else "off"}", hint = "Turn ${if (neue.prefs.autoSave) "off" else "on"}") {
            neue.update { it.copy(autoSave = !it.autoSave) }
        })
        add(MenuEntry("New deck") { run(DeskAction.NEW_DECK) })
        // A deck of a web (1.0.33): the phone's switcher, one entry each way.
        webs.webOf(state.deckId)?.takeIf { onBuilder && it.entries.size > 1 }?.let { web ->
            add(MenuEntry("Previous deck in ${web.name}", hint = "${web.position(state.deckId!!)}/${web.entries.size}") { stepWeb(-1) })
            add(MenuEntry("Next deck in ${web.name}") { stepWeb(1) })
        }
        add(MenuEntry("Import…", hint = "File, QR code") { neue.menu = MenuSpec(at, CardActions.importMenu(state, neue)) })
        add(MenuEntry("Export…", hint = "File, code, text, QR") { neue.menu = MenuSpec(at, CardActions.exportMenu(state, neue)) })
        add(MenuEntry("Rotate: ${next.label}", hint = "Now ${neue.orientation.label}", separatorBefore = true) { neue.rotate() })
        add(MenuEntry(if (neue.immersive) "Leave full screen" else "Full screen") { run(DeskAction.IMMERSIVE) })
        add(MenuEntry(if (neue.prefs.theme == NeueTheme.PAPER) "Ink, the dark theme" else "Paper, the light theme") { neue.toggleTheme() })
        add(MenuEntry("Gestures") { neue.helpOpen = true })
        add(MenuEntry(
            if (updates.available != null) "Update to ${updates.available?.versionName}" else "Check for updates",
            hint = "v${Platform.version}",
            separatorBefore = true,
        ) {
            if (updates.available != null) updates.dialogOpen = true else updates.check(userInitiated = true)
        })
    }
}

fun NeueHolders.commands(query: String): List<Command> {
    val q = query.trim().lowercase()
    fun kbd(a: DeskAction) = DeskShortcuts.chordFor(a)?.let(DeskShortcuts::kbd)
    fun cmd(group: String, label: String, action: DeskAction) = Command(group, label, kbd(action)) { run(action) }
    val fixed = listOf(
        cmd("Go", "Decks", DeskAction.GO_DECKS),
        cmd("Go", "Builder", DeskAction.GO_BUILDER),
        cmd("Go", "Siding", DeskAction.GO_SIDING),
        cmd("Go", "Format", DeskAction.GO_FORMAT),
        cmd("Go", "Prep", DeskAction.GO_PREP),
        cmd("Go", "Present", DeskAction.GO_PRESENT),
        cmd("Go", "Duel", DeskAction.GO_DUEL),
        cmd("Go", "Ai World", DeskAction.GO_WORLD),
        Command("World", "New world") { neue.go(Page.WORLD); com.kaiharimoto.neue.world.newWorld(this) },
        *(if (neue.page == Page.WORLD) arrayOf(
            cmd("World", "Run the file in the editor", DeskAction.WORLD_RUN),
            cmd("World", "Stop the run", DeskAction.WORLD_STOP),
            cmd("World", if (neue.prefs.world.follow) "Stay put: stop following Ai" else "Follow Ai from pane to pane", DeskAction.WORLD_FOLLOW),
        ) else emptyArray()),
        Command("Duel", "New duel") { neue.go(Page.DUEL); duel.setupOpen = true },
        Command("Duel", "Test hand: the builder's deck, one player") { neue.go(Page.DUEL); com.kaiharimoto.neue.duel.testHand(this) },
        Command("Duel", "Replays: keep this duel, watch one again") { neue.go(Page.DUEL); duel.libraryOpen = true },
        Command("Present", "New deck profile") { neue.go(Page.PRESENT); present.creating = true },
        *(if (present.open != null) arrayOf(
            cmd("Present", "Present from the start", DeskAction.PRESENT_START),
            cmd("Present", "Present from this slide", DeskAction.PRESENT_FROM_HERE),
            Command("Present", "Rehearse timings") { present.present(0, rehearse = true) },
            cmd("Present", "New slide", DeskAction.SLIDE_NEW),
        ) else emptyArray()),
        *(if (present.open != null && neue.prefs.ai.enabled) arrayOf(
            Command("Present", "Build these slides with ${ai.name}") { neue.go(Page.PRESENT); present.briefing = true },
            Command("Present", "Restyle these slides with ${ai.name}") { neue.go(Page.PRESENT); present.restyling = true },
        ) else emptyArray()),
        cmd("Go", "Settings", DeskAction.GO_SETTINGS),
        cmd("Deck", "Save", DeskAction.SAVE),
        cmd("Deck", "New deck", DeskAction.NEW_DECK),
        cmd("Deck", "Import a .ydk or .ydkx", DeskAction.IMPORT),
        cmd("Deck", "Export", DeskAction.EXPORT),
        Command("Deck", "Export as a .ydk file") { CardActions.export(DeckExportFormat.YDK, builder, neue) },
        Command("Deck", "Export as a .ydkx file, with groups") { CardActions.export(DeckExportFormat.YDKX, builder, neue) },
        Command("Deck", "Copy the YDKe code") { CardActions.export(DeckExportFormat.YDKE, builder, neue) },
        Command("Deck", "Copy the decklist as text") { CardActions.export(DeckExportFormat.TEXT, builder, neue) },
        Command("Deck", "Show the deck as a QR code to scan") { CardActions.export(DeckExportFormat.QR, builder, neue) },
        Command("Deck", if (neue.prefs.extraVisible) "Hide the extra deck" else "Show the extra deck") { neue.update { it.copy(extraVisible = !it.extraVisible) } },
        Command("Deck", if (neue.prefs.sideVisible) "Hide the side deck" else "Show the side deck") { neue.update { it.copy(sideVisible = !it.sideVisible) } },
        Command("Deck", if (neue.prefs.foil == Foils.OFF) "Foil on" else "Foil off") { toggleFoil() },
        cmd("Deck", "Undo", DeskAction.UNDO),
        cmd("Deck", "Redo", DeskAction.REDO),
        cmd("Deck", "Issues", DeskAction.ISSUES),
        cmd("Deck", "Groups", DeskAction.GROUPS),
        cmd("App", "Zen, now", DeskAction.ZEN),
        Command("App", if (neue.prefs.autoZen) "Zen by itself: off" else "Zen by itself: on") { neue.update { it.copy(autoZen = !it.autoZen) } },
        cmd("Card", "Next artwork", DeskAction.NEXT_ART),
        cmd("Cards", "Advanced search", DeskAction.ADVANCED_SEARCH),
        cmd("Cards", "Put the card on the list, or take it off", DeskAction.LIST_CARD),
        cmd("Cards", if (neue.prefs.poolList != null) "Show every card in the pool" else "Show the list in the pool", DeskAction.SHOW_LIST),
        Command("Cards", "New list of cards") { neue.showList(neue.newList()) },
        cmd("Deck", "New group", DeskAction.NEW_GROUP),
        Command("Deck", if (neue.prefs.genesys) "Check against the Forbidden & Limited list" else "Check against Genesys") {
            neue.update { it.copy(genesys = !it.genesys) }
        },
        Command("Deck", "Legality: what the deck is checked against…") { neue.drawer = Drawer.ISSUES },
        Command("Deck", "Format: ${if (builder.format == Format.TCG) "switch to OCG" else "switch to TCG"}") {
            setFormat(if (builder.format == Format.TCG) Format.OCG else Format.TCG)
        },
        cmd("App", if (neue.prefs.theme == NeueTheme.PAPER) "Switch to ink (dark)" else "Switch to paper (light)", DeskAction.TOGGLE_THEME),
        cmd("App", "Show or hide the pool", DeskAction.TOGGLE_POOL),
        cmd("App", "Show or hide the inspector", DeskAction.TOGGLE_INSPECTOR),
        cmd("App", if (neue.immersive) "Leave immersive mode" else "Immersive mode", DeskAction.IMMERSIVE),
        cmd("Deck", "Screenshot of the deck", DeskAction.SCREENSHOT),
        cmd("App", "Larger interface", DeskAction.ZOOM_IN),
        cmd("App", "Smaller interface", DeskAction.ZOOM_OUT),
        cmd("App", "Keyboard shortcuts", DeskAction.HELP),
        Command("App", "Check for updates") { updates.check(userInitiated = true) },
        Command("App", if (neue.frameMeter) "Hide frame times" else "Show frame times") { neue.frameMeter = !neue.frameMeter },
        // The phone's and the tablet's screen, the one-tap toggle in words (v1.3.5).
        *(if (neue.touchFirst) arrayOf(Command("App", "Rotate the screen: ${neue.orientation.next().label}") { neue.rotate() }) else emptyArray()),
        // A deck's QR code, off another screen or out of a picture (v1.3.7).
        *(if (QrSource.CAMERA in Platform.scanSources) arrayOf(Command("Deck", "Scan a deck's QR code") { CardActions.scan(QrSource.CAMERA, builder, neue) }) else emptyArray()),
        *(if (QrSource.PICTURE in Platform.scanSources) arrayOf(Command("Deck", "Import a picture of a QR code") { CardActions.scan(QrSource.PICTURE, builder, neue) }) else emptyArray()),
        Command("App", "Refresh the card pool") { builder.refreshCardPool(force = true) },
        // The assistant's own, while it is on (1.0.43).
        *(if (neue.prefs.ai.enabled) arrayOf(
            cmd(ai.name, "${ai.name}: open or close", DeskAction.AI_PANEL),
            cmd(ai.name, "${ai.name}: speak to it", DeskAction.AI_VOICE),
            cmd(ai.name, "${ai.name}: talk mode, a conversation out loud", DeskAction.AI_TALK),
            Command(ai.name, "${ai.name}: new conversation") { ai.setOpen(true); ai.newChat() },
            Command(ai.name, "${ai.name}: set up a connection") { ai.openWizard() },
            Command(ai.name, "${ai.name}'s brain: read and edit what it knows") { ai.memoryOpen = "USER.md" },
            Command(ai.name, "${ai.name}'s context: how full it is, and make room") { ai.contextOpen = true },
            Command(ai.name, "${ai.name}: settings — model, effort and the rest") { ai.quickOpen = true },
            Command(ai.name, "${ai.name}: Fine Tuning, teach it this deck") { ai.setOpen(true); ai.askTune() },
            Command(ai.name, "${ai.name}: learn this deck from first principles") { ai.setOpen(true); ai.askTune(AiSession.MODE_PRINCIPLES) },
            Command(ai.name, "${ai.name}: refactor this deck's guide") { ai.setOpen(true); ai.askTune(AiSession.MODE_REFACTOR) },
            Command(ai.name, "${ai.name}: this deck's guide") { ai.openGuide() },
            Command(ai.name, "${ai.name}: read this deck's reader's guide") { ai.openBook() },
            Command(ai.name, "${ai.name}: write this deck's reader's guide") { ai.setOpen(true); ai.askTune(AiSession.MODE_WRITE) },
            Command(ai.name, "${ai.name}: learn about you") { ai.setOpen(true); ai.profileAsk = true },
            Command(ai.name, "${ai.name}: your profile") { ai.openProfile() },
            Command(ai.name, "${ai.name}: what can you do?") { ai.setOpen(true); ai.demoOpen = true },
        ) else emptyArray()),
        Command("App", "Report an issue →") { Platform.reportIssue() },
    ).filter { q.isEmpty() || it.label.lowercase().contains(q) || it.group.lowercase().startsWith(q) }

    if (q.length < 2 || builder.index.size == 0) return fixed
    val cards = builder.index.search(query, limit = 12).cards.map { card ->
        Command(
            "Card",
            card.name,
            hint = if (builder.remaining(card) > 0) "Enter · Shift Enter side" else "At the limit",
            alt = { CardActions.add(builder, card, toSide = true) },
        ) {
            CardActions.add(builder, card)
            neue.selection = Selection.InPool(card, 0)
            neue.go(Page.BUILDER)
        }
    }
    return fixed + cards
}
