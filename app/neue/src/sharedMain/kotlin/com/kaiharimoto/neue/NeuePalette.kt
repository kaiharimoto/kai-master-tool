package com.kaiharimoto.neue

import com.kaiharimoto.neue.effects.openGoldfish
import com.kaiharimoto.mastertool.core.deck.PlayChoice
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.neue.ai.openWizard
import com.kaiharimoto.neue.ai.startRubricInterview
import com.kaiharimoto.neue.ai.askTune
import com.kaiharimoto.neue.ai.openGuide
import com.kaiharimoto.neue.ai.openBook
import com.kaiharimoto.neue.ai.openProfile
import androidx.compose.ui.geometry.Offset
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.ydk.DeckExportFormat
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.builder.groupSetMenu
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
        // Ai World's own first, on its page (DESKTOP.md §2.5): the world picker, New world, Follow, Skip, Close this app.
        if (neue.page == Page.WORLD) addAll(com.kaiharimoto.neue.world.worldPhoneMenu(this@phoneMenu, at))
        add(MenuEntry("Search cards and commands", hint = "Search", separatorBefore = neue.page == Page.WORLD) { neue.paletteOpen = true })
        if (neue.prefs.ai.enabled) add(MenuEntry(ai.name, hint = "Your assistant") { ai.setOpen(true) })
        if (neue.prefs.ai.enabled) add(MenuEntry("Look into ${ai.name}", hint = "What it knows") { ai.memoryOpen = "USER.md" })
        if (neue.prefs.ai.enabled) add(MenuEntry("${ai.name}'s test scores", hint = "Known answers") { ai.trustOpen = true })
        add(MenuEntry("Advanced search") { run(DeskAction.ADVANCED_SEARCH) })
        add(MenuEntry("Present", hint = "Deck profiles as slides") { neue.go(Page.PRESENT) })
        add(MenuEntry("Duel", hint = "The duel simulator") { neue.go(Page.DUEL) })
        add(MenuEntry("Ai World", hint = "Ai's own computer, watched") { neue.go(Page.WORLD) })
        add(MenuEntry("Shootout", hint = "Hands judged, cards rated") { neue.go(Page.SHOOTOUT) })
        add(MenuEntry("Gameplay Mapper", hint = "The end boards a deck can make") { neue.go(Page.MAPPER) })
        if (onBuilder) {
            add(MenuEntry(if (groupsOn(state)) "Hide the groups" else "Groups", hint = "The deck in pieces") { run(DeskAction.TOGGLE_KEYS) })
            add(MenuEntry("Sets of groups…", hint = state.groupSets.current.name) { neue.menu = MenuSpec(at, groupSetMenu(state, neue)) })
            add(MenuEntry("History…", enabled = state.canUndo || state.canRedo, reason = "Nothing changed yet") {
                neue.menu = MenuSpec(at, historyMenu(state, touch = true))
            })
            // One row for what is played (1.1.8): TCG, OCG or Genesys, chosen from a second menu where this one opened.
            add(MenuEntry("Play: ${play.label}", hint = "The region, or Genesys") {
                neue.menu = MenuSpec(at, playMenu())
            })
            // Always there (1.1.1): what the deck is checked against is chosen in the drawer.
            val count = state.validation.errors.size + state.validation.warnings.size
            add(MenuEntry("Legality", hint = if (count > 0) "✕ $count" else state.rulesInForce.short()) { neue.drawer = Drawer.ISSUES })
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

/**
 * The palette's rows (the 1.1.2 design review, finding 11): Go in the pages' own order, then this page's own commands,
 * then the rest and App, and only then the other pages' own — their keys work on their page alone, so there they say
 * which page instead of a key. [Command.words] are what else a row answers to ("banlist" finds Legality).
 */
fun NeueHolders.commands(query: String): List<Command> {
    val q = query.trim().lowercase()
    fun kbd(a: DeskAction) = DeskShortcuts.chordFor(a)?.let(DeskShortcuts::kbd)
    fun cmd(group: String, label: String, action: DeskAction, words: List<String> = emptyList()) = Command(group, label, kbd(action), words = words) { run(action) }
    val go = listOf(
        Page.BUILDER to DeskAction.GO_BUILDER,
        Page.DECKS to DeskAction.GO_DECKS,
        Page.SIDING to DeskAction.GO_SIDING,
        Page.FORMAT to DeskAction.GO_FORMAT,
        Page.PREP to DeskAction.GO_PREP,
        Page.PRESENT to DeskAction.GO_PRESENT,
        Page.DUEL to DeskAction.GO_DUEL,
        Page.WORLD to DeskAction.GO_WORLD,
        Page.SHOOTOUT to DeskAction.GO_SHOOTOUT,
        Page.MAPPER to DeskAction.GO_MAPPER,
    ).sortedBy { it.first.numeral ?: Int.MAX_VALUE }.map { (page, action) ->
        cmd("Go", when (page) { Page.WORLD -> "Ai World"; Page.MAPPER -> "Gameplay Mapper"; else -> page.title }, action)
    } + cmd("Go", "Settings", DeskAction.GO_SETTINGS)
    // Each page's own commands, kept apart so they stand after Go on their page and after App elsewhere.
    val pages: Map<Page, List<Command>> = mapOf(
        Page.SHOOTOUT to listOfNotNull(
            cmd("Shootout", "Begin a session, or carry on", DeskAction.SHOOTOUT_START),
            cmd("Shootout", "The results, or back to the trials", DeskAction.SHOOTOUT_RESULTS),
            if (neue.prefs.ai.enabled) cmd("Shootout", "Trust: how far ${ai.name} is trusted on this matchup", DeskAction.SHOOTOUT_TRUST) else null,
            if (neue.prefs.ai.enabled) Command("Shootout", "Interview: write how you judge this matchup") { neue.go(Page.SHOOTOUT); ai.startRubricInterview() } else null,
            if (neue.page == Page.SHOOTOUT && shootoutStarted && shootout.running) cmd("Shootout", "Stop the session, every answer kept", DeskAction.SHOOTOUT_STOP) else null,
        ),
        Page.MAPPER to listOfNotNull(
            cmd("Mapper", "Deal hands and map them", DeskAction.MAPPER_RUN, words = listOf("end boards", "combos")),
            cmd("Mapper", "Map the starter table", DeskAction.MAPPER_RUN_STARTERS, words = listOf("starters", "extenders")),
            cmd("Mapper", "The library of end boards", DeskAction.MAPPER_LIBRARY),
            cmd("Mapper", "The starter table", DeskAction.MAPPER_STARTERS),
            cmd("Mapper", "Going first, or going second", DeskAction.MAPPER_SIDE),
            if (mapperStarted && mapper.busy) cmd("Mapper", "Stop the run, what it mapped kept", DeskAction.MAPPER_STOP) else null,
        ),
        Page.WORLD to listOf(
            Command("World", "New world") { neue.go(Page.WORLD); com.kaiharimoto.neue.world.newWorld(this) },
            // Phase D step 4: the Effects app on its Goldfish tab, on the open deck.
            Command("World", "Goldfish: how often the open deck reaches an end board") { openGoldfish() },
        ) + (
            if (neue.page == Page.WORLD) listOf(
                cmd("World", "Run the file in the editor", DeskAction.WORLD_RUN),
                cmd("World", "Stop the run", DeskAction.WORLD_STOP),
                cmd("World", if (neue.prefs.world.follow) "Stay put: stop following Ai" else "Follow Ai to the window it works in", DeskAction.WORLD_FOLLOW),
                cmd("World", "Skip ahead: Ai's typing and travel finish at once", DeskAction.WORLD_SKIP),
                cmd("World", "The launcher: apps and worlds", DeskAction.WORLD_LAUNCHER),
                cmd("World", "Files", DeskAction.WORLD_APP_FILES),
                cmd("World", "Editor", DeskAction.WORLD_APP_EDITOR),
                cmd("World", "Terminal", DeskAction.WORLD_APP_TERMINAL),
                cmd("World", "Browser", DeskAction.WORLD_APP_BROWSER),
                cmd("World", "Thoughts", DeskAction.WORLD_APP_THOUGHTS),
                cmd("World", "Instruments", DeskAction.WORLD_APP_INSTRUMENTS),
                cmd("World", "Library: everything Ai knows", DeskAction.WORLD_APP_LIBRARY),
                cmd("World", "Effects: the written effects, and asking for more", DeskAction.WORLD_APP_EFFECTS),
                cmd("World", "The next window", DeskAction.WORLD_NEXT_WINDOW),
                cmd("World", "Close the tab, else the window", DeskAction.WORLD_CLOSE),
                cmd("World", "Minimise the window", DeskAction.WORLD_MINIMISE),
                cmd("World", "Maximise the window, or restore it", DeskAction.WORLD_SNAP_UP),
                cmd("World", "Snap the window left", DeskAction.WORLD_SNAP_LEFT),
                cmd("World", "Snap the window right", DeskAction.WORLD_SNAP_RIGHT),
            ) else emptyList()
        ),
        Page.DUEL to listOf(
            Command("Duel", "New duel") { neue.go(Page.DUEL); duel.setupOpen = true },
            Command("Duel", "Test hand: the builder's deck, one player") { neue.go(Page.DUEL); com.kaiharimoto.neue.duel.testHand(this) },
            Command("Duel", "Replays: keep this duel, watch one again") { neue.go(Page.DUEL); duel.libraryOpen = true },
        ),
        Page.PRESENT to listOf(Command("Present", "New deck profile") { neue.go(Page.PRESENT); present.creating = true }) + (
            if (present.open != null) listOf(
                cmd("Present", "Present from the start", DeskAction.PRESENT_START),
                cmd("Present", "Present from this slide", DeskAction.PRESENT_FROM_HERE),
                Command("Present", "Rehearse timings") { present.present(0, rehearse = true) },
                cmd("Present", "New slide", DeskAction.SLIDE_NEW),
            ) else emptyList()
        ) + (
            if (present.open != null && neue.prefs.ai.enabled) listOf(
                Command("Present", "Build these slides with ${ai.name}") { neue.go(Page.PRESENT); present.briefing = true },
                Command("Present", "Restyle these slides with ${ai.name}") { neue.go(Page.PRESENT); present.restyling = true },
            ) else emptyList()
        ),
    )
    val here = pages[neue.page].orEmpty()
    // Elsewhere a page's keys do something else or nothing: the row names its page instead.
    val elsewhere = pages.filterKeys { it != neue.page }.entries.sortedBy { it.key.numeral ?: Int.MAX_VALUE }.flatMap { (page, rows) ->
        rows.map { if (it.hint != null) it.copy(hint = "on ${if (page == Page.WORLD) "Ai World" else page.title}") else it }
    }
    val rest = listOf(
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
        // One row for the drawer (finding 4), found by what people call it.
        cmd("Deck", "Legality", DeskAction.ISSUES, words = LEGALITY_WORDS),
        cmd("Deck", "Groups", DeskAction.GROUPS),
        cmd("App", "Zen, now", DeskAction.ZEN),
        Command("App", if (neue.prefs.autoZen) "Zen by itself: off" else "Zen by itself: on") { neue.update { it.copy(autoZen = !it.autoZen) } },
        cmd("Card", "Next artwork", DeskAction.NEXT_ART),
        cmd("Cards", "Advanced search", DeskAction.ADVANCED_SEARCH),
        cmd("Cards", "Put the card on the list, or take it off", DeskAction.LIST_CARD),
        cmd("Cards", if (neue.prefs.poolList != null) "Show every card in the pool" else "Show the list in the pool", DeskAction.SHOW_LIST),
        Command("Cards", "New list of cards") { neue.showList(neue.newList()) },
        cmd("Deck", "New group", DeskAction.NEW_GROUP),
        // The deck's sets of groups (2026-10): each set to use, and a new one.
        *builder.groupSets.sets.filter { it.id != builder.groupSets.current.id }.map { set ->
            Command("Deck", "Use the set of groups “${set.name}”", words = SET_WORDS) { builder.useGroupSet(set.id) }
        }.toTypedArray(),
        Command("Deck", "New set of groups", words = SET_WORDS) { builder.addGroupSet(copy = false) },
        Command("Deck", "Copy this set of groups", words = SET_WORDS) { builder.addGroupSet(copy = true) },
        Command("Deck", "Rename this set of groups", words = SET_WORDS) { neue.renamingSet = builder.groupSets.current.id },
        // What is played (1.1.8): the bar's three choices, each but the one in force.
        *PlayChoice.entries.filter { it != play }.map { choice ->
            Command("Deck", "Play ${choice.label}", words = playWords(choice)) { setPlay(choice) }
        }.toTypedArray(),
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
    )
    // The assistant's own, while it is on (1.0.43).
    val assistant = listOf<Command>(
        *(if (neue.prefs.ai.enabled) arrayOf(
            cmd(ai.name, "${ai.name}: open or close", DeskAction.AI_PANEL),
            cmd(ai.name, "${ai.name}: speak to it", DeskAction.AI_VOICE),
            cmd(ai.name, "${ai.name}: talk mode, a conversation out loud", DeskAction.AI_TALK),
            Command(ai.name, "${ai.name}: new conversation") { ai.setOpen(true); ai.newChat() },
            Command(ai.name, "${ai.name}: set up a connection") { ai.openWizard() },
            Command(ai.name, "${ai.name}'s brain: read and edit what it knows") { ai.memoryOpen = "USER.md" },
            Command(ai.name, "${ai.name}'s context: how full it is, and make room") { ai.contextOpen = true },
            // Settings' Test scores (named Trust until the design review): its old name and its kind still find it.
            Command(ai.name, "${ai.name}'s test scores: questions with known answers", words = listOf("trust", "accuracy", "benchmark", "eval")) { ai.trustOpen = true },
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
    )
    val fixed = (go + here + rest + assistant + elsewhere + Command("App", "Report an issue →") { Platform.reportIssue() })
        .filter { c -> q.isEmpty() || c.label.lowercase().contains(q) || c.group.lowercase().startsWith(q) || c.words.any { it.lowercase().contains(q) } }

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

/** What the Legality row answers to besides its name: what people call the list and the drawer. */
private val SET_WORDS = listOf("groups", "set", "sets", "grouping", "breakdown", "view")

private val LEGALITY_WORDS = listOf("issues", "banlist", "ban list", "F&L", "forbidden", "limited", "format", "legal", "check against")

/** What a person may type for a choice of what is played: the region's words, or Genesys's. */
private fun playWords(choice: PlayChoice): List<String> = when (choice) {
    PlayChoice.GENESYS -> GENESYS_WORDS
    else -> listOf("format", "region", choice.label, "play")
}

/** The phone's second menu for what is played: the three choices, the one in force ticked. */
fun NeueHolders.playMenu(): List<MenuEntry> = PlayChoice.entries.map { choice ->
    MenuEntry(
        choice.label,
        hint = when {
            choice == play -> "✓"
            choice == PlayChoice.GENESYS -> "Points, no list"
            else -> null
        },
    ) { setPlay(choice) }
}

/** What Genesys answers to. */
private val GENESYS_WORDS = listOf("genesys", "points", "format", "legal")
