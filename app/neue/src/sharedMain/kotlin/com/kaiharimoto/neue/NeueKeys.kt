package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.input.KeyChord
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.neue.ai.toggleVoice
import com.kaiharimoto.neue.ai.toggleTalk
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskContext
import com.kaiharimoto.mastertool.core.input.DeskMenuBar
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.layout.GridStep
import com.kaiharimoto.mastertool.core.layout.Revealed
import com.kaiharimoto.mastertool.core.layout.StepDirection
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.builder.groupsOn
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.mapper.runMapper
import com.kaiharimoto.neue.shootout.runShootout

// The window's keyboard, on [NeueHolders]: the key handler, the held rows, what the shortcut table reads, and the
// one dispatch every action goes through — keys, menus, the palette and gestures alike. Its state (the keys down,
// [NeueHolders.held]; the held rows, [NeueHolders.holding]; the menu's echo, [NeueHolders.echo]) stays on the holders.

/** The window's key handler: one table, one resolve, one dispatch (`DeskShortcuts`). */
fun NeueHolders.onKey(event: KeyEvent): Boolean {
    if (event.type == KeyEventType.KeyUp) {
        held.remove(event.key)
        // Ctrl let go while the World's strip of windows is out: the one chosen comes forward (DESKTOP.md §9.1).
        if (worldStarted && world.desk.switching != null && event.key in WINDOW_KEY_HOLD) {
            world.desk.commitSwitch()
            return true
        }
        // A held row's key coming up ends what its going down started (1.0.87: M let go sends what was said).
        holding.remove(event.key)?.let { action ->
            hold(action, down = false)
            return true
        }
        return false
    }
    // While a key is held for a held row, what it would type is swallowed too (Alt M in the command line).
    if (event.type != KeyEventType.KeyDown) return holding.isNotEmpty()
    // The first key after deep zen only wakes the builder: nothing should
    // happen to a deck you were not looking at.
    if (wake() == ZenPhase.DEEP) {
        held.add(event.key)
        return true
    }
    val repeat = !held.add(event.key)
    // The key's own repeats while a held row is down are nothing: never a second start.
    if (event.key in holding) return true
    val chord = DeskKeys.chord(event) ?: return false
    val context = deskContext()
    // Command mode (1.0.87, the red team): in a Spotlight a voice filled — or one listening — M is the voice key
    // still, held to speak again ("yes"), never an m typed and repeated into the line.
    if (neue.page == Page.DUEL && chord == KeyChord("m") && !context.overlayOpen && !neue.hasTop) {
        val spot = duel.spotlight
        // Only while the box holds words heard (an edit makes them typed) and no other field has the keys: an m typed
        // into the chat beside an open box is an m (the red team).
        val voiced = spot != null && (spot.heard != null || spot.mode == com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode.LISTENING)
        if (voiced && (!context.textInputFocused || duel.spotlightTyping)) {
            holding[event.key] = DeskAction.DUEL_VOICE
            hold(DeskAction.DUEL_VOICE, down = true)
            return true
        }
    }
    // Keys typed after the box opened and before its field has the keyboard (a frame or two) are the box's, never
    // duel keys: "zs…" typed fast opens the box on "zs", not a Summon (the red team).
    if (neue.page == Page.DUEL && duel.spotlight != null && !duel.spotlightTyping && !context.textInputFocused && !context.overlayOpen && !neue.hasTop &&
        !chord.ctrl && !chord.alt
    ) {
        val typed = when {
            chord.key.length == 1 && (chord.key[0] in 'a'..'z' || chord.key[0] in '0'..'9') -> if (chord.shift) chord.key.uppercase() else chord.key
            chord.key == "space" -> " "
            else -> null
        }
        if (typed != null) {
            duel.typeIntoSpotlight(typed)
            return true
        }
    }
    // The World's desktop in focus: the arrows walk its icons and Enter opens one (never a table row, §9.1).
    if (!context.overlayOpen && !neue.hasTop && com.kaiharimoto.neue.world.worldDeskKey(this, chord, context.textInputFocused)) return true
    val shortcut = DeskShortcuts.resolveShortcut(chord, context) ?: return spotlightOn(chord, context)
    // Ctrl ` walks the windows while Ctrl is held, and its letting go chooses.
    if (shortcut.action == DeskAction.WORLD_NEXT_WINDOW || shortcut.action == DeskAction.WORLD_PREVIOUS_WINDOW) {
        com.kaiharimoto.neue.world.runWorld(this, shortcut.action, ctrlHeld = true)
        return true
    }
    if (repeat && !shortcut.repeatable) return true
    if (shortcut.hold) {
        holding[event.key] = shortcut.action
        hold(shortcut.action, down = true)
        return true
    }
    if (echo.admit(shortcut.action, System.currentTimeMillis())) run(shortcut.action)
    return true
}

/**
 * A letter that is no duel key, typed at the table (1.0.87, the Spotlight): the box opens holding it, so a move is
 * typed straight onto the table. Only with nothing covering the page and no field taking the keys.
 */
private fun NeueHolders.spotlightOn(chord: KeyChord, context: DeskContext): Boolean {
    if (neue.page != Page.DUEL || context.textInputFocused || context.overlayOpen || neue.hasTop) return false
    if (chord.ctrl || chord.alt || chord.key.length != 1 || chord.key[0] !in 'a'..'z') return false
    if (duel.shown == null || duel.replay != null) return false
    // The Shortcut window open (Phase D §5¾.10): a letter types its answer, a coordinate or a label, never a command.
    if (duel.choosing) return duel.shortcutPart.type(chord.key)
    duel.openSpotlight(chord.key)
    return true
}

/** The keys whose letting go ends the World's walk through its windows: Ctrl, and the Mac's Command. */
private val WINDOW_KEY_HOLD = setOf(
    androidx.compose.ui.input.key.Key.CtrlLeft,
    androidx.compose.ui.input.key.Key.CtrlRight,
    androidx.compose.ui.input.key.Key.MetaLeft,
    androidx.compose.ui.input.key.Key.MetaRight,
)

/** A held row's action: [down] starts it, the key coming up ends it. */
private fun NeueHolders.hold(action: DeskAction, down: Boolean) {
    when (action) {
        DeskAction.DUEL_VOICE -> if (down) duelVoice.press() else duelVoice.release()
        else -> if (down) run(action)
    }
}

/**
 * The window lost the keyboard (1.0.87, the red team): no key-up will come for the keys down now, so they are
 * forgotten — or the next press reads as a repeat — and whatever a held key started ends here.
 */
fun NeueHolders.keysLost() {
    held.clear()
    val started = holding.values.toList()
    holding.clear()
    started.forEach { hold(it, down = false) }
}

/** What is on screen, as the shortcut table reads it. */
fun NeueHolders.deskContext() = DeskContext(
    textInputFocused = textFocus.any || builder.textInputFocused || neue.searchFocused,
    searchFocused = neue.searchFocused,
    overlayOpen = neue.overlayOpen || overlays.isOpen || builder.editingGoal != null || updates.dialogOpen ||
        (shootoutStarted && shootout.behind != null),
    onBuilder = neue.page == Page.BUILDER && present.playing == null,
    ai = neue.prefs.ai.enabled,
    onPresent = neue.page == Page.PRESENT,
    presenting = present.playing != null,
    onDuel = neue.page == Page.DUEL,
    onWorld = neue.page == Page.WORLD,
    browserInFront = neue.page == Page.WORLD && worldStarted && !neue.phone &&
        world.desk.desk.front == com.kaiharimoto.mastertool.core.world.desk.BuiltInApp.BROWSER.id,
    onShootout = neue.page == Page.SHOOTOUT,
    onMapper = neue.page == Page.MAPPER,
    replaying = neue.page == Page.DUEL && duel.replay != null,
    choosing = neue.page == Page.DUEL && duel.choosing,
)

/**
 * The Mac's menu bar choosing [action] (`DeskMenuBar`): only where its key
 * would have worked, and once per press — the menu's accelerator and
 * [onKey] may both hear the same Command chord, and [echo] keeps the second out.
 */
fun NeueHolders.runFromMenu(action: DeskAction) {
    wake()
    if (!DeskMenuBar.enabled(action, deskContext())) return
    if (echo.admit(action, System.currentTimeMillis())) run(action)
}

fun NeueHolders.run(action: DeskAction) {
    val state = builder
    when (action) {
        DeskAction.PALETTE -> neue.paletteOpen = !neue.paletteOpen
        DeskAction.GO_DECKS -> neue.go(Page.DECKS)
        DeskAction.GO_BUILDER -> neue.go(Page.BUILDER)
        DeskAction.GO_SIDING -> neue.go(Page.SIDING)
        DeskAction.GO_FORMAT -> neue.go(Page.FORMAT)
        DeskAction.GO_PREP -> neue.go(Page.PREP)
        DeskAction.GO_PRESENT -> neue.go(Page.PRESENT)
        DeskAction.GO_DUEL -> neue.go(Page.DUEL)
        DeskAction.GO_WORLD -> neue.go(Page.WORLD)
        // Shootout's own (1.1.2): from its keys, the palette and the menus alike.
        DeskAction.GO_SHOOTOUT, DeskAction.SHOOTOUT_ANSWER_1, DeskAction.SHOOTOUT_ANSWER_2, DeskAction.SHOOTOUT_ANSWER_3,
        DeskAction.SHOOTOUT_ANSWER_4, DeskAction.SHOOTOUT_ANSWER_5, DeskAction.SHOOTOUT_LEFT, DeskAction.SHOOTOUT_RIGHT,
        DeskAction.SHOOTOUT_START, DeskAction.SHOOTOUT_STOP, DeskAction.SHOOTOUT_RESULTS, DeskAction.SHOOTOUT_ACCEPT, DeskAction.SHOOTOUT_TRUST,
        DeskAction.SHOOTOUT_DRAW_MINE, DeskAction.SHOOTOUT_DRAW_THEIRS,
        -> runShootout(this, action)
        // Gameplay Mapper's own (Phase M): from its keys, the palette and the menus alike.
        DeskAction.GO_MAPPER, DeskAction.MAPPER_LIBRARY, DeskAction.MAPPER_STARTERS, DeskAction.MAPPER_SIDE, DeskAction.MAPPER_PREV,
        DeskAction.MAPPER_NEXT, DeskAction.MAPPER_REPLAY, DeskAction.MAPPER_RUN, DeskAction.MAPPER_RUN_STARTERS, DeskAction.MAPPER_STOP,
        DeskAction.MAPPER_DENSER, DeskAction.MAPPER_LOOSER, DeskAction.MAPPER_ORDER, DeskAction.MAPPER_TUNE, DeskAction.MAPPER_COMPARE,
        -> runMapper(this, action)
        // Ai World's own (1.0.97): from its keys, the palette and the menus alike.
        DeskAction.WORLD_RUN, DeskAction.WORLD_STOP, DeskAction.WORLD_FOLLOW, DeskAction.WORLD_NEW,
        DeskAction.WORLD_APP_FILES, DeskAction.WORLD_APP_EDITOR, DeskAction.WORLD_APP_TERMINAL, DeskAction.WORLD_APP_BROWSER,
        DeskAction.WORLD_APP_THOUGHTS, DeskAction.WORLD_APP_INSTRUMENTS, DeskAction.WORLD_APP_LIBRARY, DeskAction.WORLD_APP_EFFECTS, DeskAction.WORLD_LAUNCHER,
        DeskAction.WORLD_NEXT_WINDOW, DeskAction.WORLD_PREVIOUS_WINDOW, DeskAction.WORLD_CLOSE, DeskAction.WORLD_MINIMISE,
        DeskAction.WORLD_SNAP_UP, DeskAction.WORLD_SNAP_LEFT, DeskAction.WORLD_SNAP_RIGHT, DeskAction.WORLD_SNAP_DOWN,
        DeskAction.WORLD_TAB_NEW, DeskAction.WORLD_TAB_ADDRESS, DeskAction.WORLD_TAB_NEXT, DeskAction.WORLD_TAB_PREVIOUS,
        DeskAction.WORLD_TAB_BACK, DeskAction.WORLD_TAB_FORWARD, DeskAction.WORLD_SKIP,
        DeskAction.WORLD_TAB_1, DeskAction.WORLD_TAB_2, DeskAction.WORLD_TAB_3, DeskAction.WORLD_TAB_4, DeskAction.WORLD_TAB_5,
        DeskAction.WORLD_TAB_6, DeskAction.WORLD_TAB_7, DeskAction.WORLD_TAB_8, DeskAction.WORLD_TAB_9,
        -> com.kaiharimoto.neue.world.runWorld(this, action)
        // From a menu or the palette, where nothing is let go of: a press, and the next one sends (1.0.87).
        DeskAction.DUEL_VOICE -> duelVoice.toggle()
        DeskAction.WEB_PREVIOUS -> stepWeb(-1)
        DeskAction.WEB_NEXT -> stepWeb(1)
        DeskAction.GO_SETTINGS -> neue.go(Page.SETTINGS)
        DeskAction.HELP -> neue.helpOpen = true
        DeskAction.DISMISS -> dismiss()
        // On the World page Ctrl S is the editor's: the person's edit to the file, saved.
        DeskAction.SAVE -> if (neue.page == Page.WORLD) world.saveEditor() else state.save { decksReload++ }
        DeskAction.UNDO -> when (neue.page) {
            Page.PRESENT -> present.undo()
            Page.DUEL -> duel.undo()
            else -> state.undo()
        }
        DeskAction.REDO -> when (neue.page) {
            Page.PRESENT -> present.redo()
            Page.DUEL -> duel.redo()
            else -> state.redo()
        }
        DeskAction.NEW_DECK -> { state.newDeck(); neue.go(Page.BUILDER) }
        DeskAction.IMPORT -> { state.importFromFile(); neue.go(Page.BUILDER) }
        DeskAction.EXPORT -> neue.menu = MenuSpec(neue.exportAnchor, CardActions.exportMenu(state, neue))
        DeskAction.FOCUS_SEARCH -> neue.focusSearch()
        // ↑ and ↓ are one pair of keys with two jobs (1.0.18): in the search field, or with
        // nothing selected, they walk the results; with a card selected, the selection.
        DeskAction.POOL_PREVIOUS -> moveSelection(StepDirection.UP)
        DeskAction.POOL_NEXT -> moveSelection(StepDirection.DOWN)
        DeskAction.POOL_ADD, DeskAction.POOL_ADD_TO_SIDE -> state.results.getOrNull(neue.poolCursor)?.let { card ->
            CardActions.add(state, card, toSide = (action == DeskAction.POOL_ADD_TO_SIDE) != neue.prefs.poolToSide)
        }
        DeskAction.REMOVE_SELECTED -> if (neue.page == Page.PRESENT) com.kaiharimoto.neue.present.deleteSelection(this) else (neue.selection as? Selection.InDeck)?.let { sel ->
            state.removeAt(sel.card, sel.section, sel.index)
            // Stay on the same slot, so Delete held down clears a row.
            val ids = state.deck[sel.section]
            val next = sel.index.coerceAtMost(ids.size - 1)
            neue.selection = ids.getOrNull(next)?.let(state.index::byId)?.let { Selection.InDeck(it, sel.section, next) }
        }
        DeskAction.VIEW_SELECTED -> neue.viewing = when (val sel = neue.selection) {
            is Selection.InDeck -> Viewing(sel.card, sel.section, sel.index)
            is Selection.InPool -> Viewing(sel.card, null, sel.row)
            null -> state.results.getOrNull(neue.poolCursor)?.let { Viewing(it, null, neue.poolCursor) }
        }
        DeskAction.TOGGLE_KEYS -> setGroups(!groupsOn(state))
        DeskAction.GROUP_ARRANGEMENT -> {
            // As is, fitted, separate, round (1.0.37); the groups come out if they were not.
            val all = GroupArrangement.entries
            val next = all[(all.indexOf(neue.prefs.arrangement) + 1) % all.size]
            neue.update { it.copy(groupArrangement = next.name) }
            if (!groupsOn(state)) setGroups(true)
            neue.note = Note("Groups ${arrangementWords(next).lowercase()}")
        }
        // Ai stands in the inspector's place (1.0.45): asking for the inspector puts Ai away.
        DeskAction.TOGGLE_INSPECTOR -> if (neue.aiDocked && neue.page == Page.BUILDER) {
            neue.update { it.copy(inspectorVisible = true, ai = it.ai.copy(panelOpen = false)) }
        } else {
            neue.update { it.copy(inspectorVisible = !it.inspectorVisible) }
        }
        DeskAction.TOGGLE_POOL -> neue.update { it.copy(poolVisible = !it.poolVisible) }
        DeskAction.TOGGLE_FILTERS -> neue.update { it.copy(filtersOpen = !it.filtersOpen, poolVisible = true) }
        DeskAction.NEW_GROUP -> state.startGroupDraft(seed = (neue.selection as? Selection.InDeck)?.card?.id)
        DeskAction.GROUPS -> setGroups(true)
        DeskAction.ADVANCED_SEARCH -> {
            neue.page = Page.BUILDER
            neue.studio = com.kaiharimoto.neue.Studio()
        }
        DeskAction.LIST_CARD -> (neue.inspected ?: state.results.getOrNull(neue.poolCursor))?.let { neue.toggleOnList(it) }
        DeskAction.SHOW_LIST -> if (neue.prefs.poolList != null) {
            neue.showList(null)
        } else {
            neue.showList(neue.activeList?.id ?: neue.newList())
            neue.update { it.copy(poolVisible = true) }
        }
        DeskAction.SELECT_LEFT -> moveSelection(StepDirection.LEFT)
        DeskAction.SELECT_RIGHT -> moveSelection(StepDirection.RIGHT)
        DeskAction.NEXT_ART, DeskAction.PREVIOUS_ART -> neue.inspected?.let { card ->
            if (neue.artChoices(card).size > 1) {
                neue.stepArt(card, if (action == DeskAction.NEXT_ART) 1 else -1)
            } else {
                neue.note = Note("${card.name} has one artwork")
            }
        }
        DeskAction.ZEN -> if (state.deck.totalCards > 0) {
            neue.dismissTop()
            neue.page = Page.BUILDER
            if (!neue.immersive) {
                neue.immersive = true
                neue.revealed = Revealed.NONE
                zenWaitsForLayout = true
            }
            zenRequest++
        }
        DeskAction.ISSUES -> neue.drawer = if (neue.drawer == Drawer.ISSUES) null else Drawer.ISSUES
        DeskAction.ZOOM_IN -> neue.update { it.zoomedIn() }
        DeskAction.ZOOM_OUT -> neue.update { it.zoomedOut() }
        DeskAction.ZOOM_RESET -> neue.update { it.copy(scale = 1f) }
        DeskAction.TOGGLE_THEME -> neue.toggleTheme()
        DeskAction.IMMERSIVE -> {
            neue.immersive = !neue.immersive
            neue.revealed = Revealed.NONE
        }
        DeskAction.SCREENSHOT -> shots.export(builder, neue)
        DeskAction.AI_PANEL -> if (neue.prefs.ai.enabled) ai.toggle()
        DeskAction.AI_VOICE -> if (neue.prefs.ai.enabled) { ai.setOpen(true); ai.toggleVoice() }
        DeskAction.AI_TALK -> if (neue.prefs.ai.enabled) { ai.setOpen(true); ai.toggleTalk() }
        else -> if (neue.page == Page.DUEL) com.kaiharimoto.neue.duel.runDuel(table, action) else com.kaiharimoto.neue.present.runPresent(this, action)
    }
}

/**
 * The arrow keys (kai, 1.0.18): the selected card's neighbour becomes the
 * selection, and the inspector shows it — the hover is let go of, since it
 * outranks the selection there. In the deck, up past a section's top row and
 * down past its bottom carry on into the section above or below, in the same
 * column where it can. With nothing selected, up and down walk the pool.
 */
private fun NeueHolders.moveSelection(direction: StepDirection) {
    val state = builder
    when (val sel = if (neue.searchFocused) null else neue.selection) {
        is Selection.InDeck -> {
            val ids = state.deck[sel.section]
            val columns = (neue.phoneColumns ?: com.kaiharimoto.neue.builder.columnsOf(sel.section))
            GridStep.move(sel.index, ids.size, columns, direction)?.let { next ->
                state.index.byId(ids[next])?.let { neue.selection = Selection.InDeck(it, sel.section, next) }
            } ?: run {
                if (direction != StepDirection.UP && direction != StepDirection.DOWN) return@run
                val shown = buildList {
                    add(DeckSection.MAIN)
                    if (neue.prefs.extraVisible) add(DeckSection.EXTRA)
                    if (neue.prefs.sideVisible) add(DeckSection.SIDE)
                }.filter { state.deck[it].isNotEmpty() }
                val at = shown.indexOf(sel.section)
                val target = shown.getOrNull(if (direction == StepDirection.DOWN) at + 1 else at - 1) ?: return@run
                val there = state.deck[target]
                val cols = (neue.phoneColumns ?: com.kaiharimoto.neue.builder.columnsOf(target))
                val column = (sel.index % columns).coerceAtMost(cols - 1)
                val index = if (direction == StepDirection.DOWN) {
                    column.coerceAtMost(there.lastIndex)
                } else {
                    (((there.size - 1) / cols) * cols + column).coerceAtMost(there.lastIndex)
                }
                state.index.byId(there[index])?.let { neue.selection = Selection.InDeck(it, target, index) }
            }
        }
        is Selection.InPool -> {
            GridStep.move(sel.row, state.results.size, neue.poolColumns.coerceAtLeast(1), direction)?.let { next ->
                neue.selection = Selection.InPool(state.results[next], next)
                neue.poolCursor = next
            }
        }
        null -> when (direction) {
            StepDirection.UP -> neue.poolCursor = (neue.poolCursor - 1).coerceAtLeast(0)
            StepDirection.DOWN -> neue.poolCursor = (neue.poolCursor + 1).coerceAtMost((state.results.size - 1).coerceAtLeast(0))
            else -> Unit
        }
    }
    neue.hovered = null
}
