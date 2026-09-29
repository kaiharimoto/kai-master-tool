package com.kaiharimoto.neue

import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.kit.isPrimaryPress
import com.kaiharimoto.neue.platform.reportIssue
import com.kaiharimoto.neue.cursor.CursorLayer
import com.kaiharimoto.neue.cursor.FamilyCursor
import com.kaiharimoto.neue.cursor.LocalCursor
import com.kaiharimoto.neue.kit.LocalOverlays
import com.kaiharimoto.neue.kit.OverlayLayer
import com.kaiharimoto.neue.kit.Overlays
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import com.kaiharimoto.mastertool.core.motion.ZenClock
import com.kaiharimoto.mastertool.core.motion.ZenCorner
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.neue.zen.LocalZen
import com.kaiharimoto.neue.zen.ZenReset
import com.kaiharimoto.neue.zen.ZenLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.kaiharimoto.mastertool.core.layout.EdgeReveal
import com.kaiharimoto.mastertool.core.layout.Revealed
import com.kaiharimoto.neue.art.ArtLibrary
import com.kaiharimoto.neue.art.LocalArt
import com.kaiharimoto.neue.cards.LocalNameStyle
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.neue.builder.IMMERSIVE_TOP
import com.kaiharimoto.neue.builder.BuilderBar
import com.kaiharimoto.neue.shot.DeckShots
import com.kaiharimoto.neue.theme.MuShell
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.core.layout.GridStep
import com.kaiharimoto.mastertool.core.layout.StepDirection
import androidx.compose.runtime.SideEffect
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.mastertool.core.ydk.DeckExportFormat
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.neue.builder.LENS_TABS
import com.kaiharimoto.neue.builder.groupsOn
import com.kaiharimoto.mastertool.core.motion.ZenPick
import com.kaiharimoto.mastertool.core.motion.ZenGestures
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import com.kaiharimoto.mastertool.core.library.StartingDeck
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskContext
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.DeskMenuBar
import com.kaiharimoto.mastertool.core.input.BackChain
import com.kaiharimoto.mastertool.core.input.BackFlags
import com.kaiharimoto.mastertool.core.input.Unwind
import com.kaiharimoto.mastertool.core.input.ActionEcho
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.configureImageLoader
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckLayoutState
import com.kaiharimoto.neue.builder.BuilderPage
import com.kaiharimoto.neue.builder.CardViewer
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.builder.NeueDrag
import com.kaiharimoto.neue.builder.rememberCarryMotion
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.MenuLayer
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.ToastBox
import com.kaiharimoto.neue.pages.DecksPage
import com.kaiharimoto.neue.pages.OddsPage
import com.kaiharimoto.neue.pages.SettingsHost
import com.kaiharimoto.neue.pages.SettingsPage
import com.kaiharimoto.neue.pages.StatsPage
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.shell.Command
import com.kaiharimoto.neue.shell.CommandPalette
import com.kaiharimoto.neue.shell.Drawers
import com.kaiharimoto.neue.shell.HelpDialog
import com.kaiharimoto.neue.shell.Rail
import com.kaiharimoto.neue.shell.ShellStatus
import com.kaiharimoto.neue.shell.TitleBar
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.theme.MuTheme
import com.kaiharimoto.neue.update.NeueUpdates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The window's state holders, remembered together so the key handler and the tree share them. */
class NeueHolders(
    val deps: AppDependencies,
    val builder: DeckBuilderState,
    val layout: DeckLayoutState,
    val neue: NeueState,
    val drag: NeueDrag,
    val updates: NeueUpdates,
    val art: ArtLibrary,
    val shots: DeckShots,
) {
    private val held = mutableSetOf<androidx.compose.ui.input.key.Key>()
    var focus: FocusManager? = null

    /** Zen's amounts and clock, shared with everything that fades or floats. */
    val zen = ZenLayer()

    /** Pictures the person added to cards themselves (1.0.18). */
    val customArt = com.kaiharimoto.neue.art.CustomArt(java.io.File(Platform.dataDir, "custom-art")).also { neue.customArt = it }

    /** The family pointer, Crop caption: one per window. */
    val cursor = FamilyCursor()

    /** Anchored surfaces in the window's own layer (a select's list), under the cursor. */
    val overlays = Overlays()

    /** Bumped by the Z key: zen, now (`ZenClockwork` carries it out). */
    var zenRequest by mutableStateOf(0)
    var zenWaitsForLayout = false

    /**
     * The last Z carried out. Plain: the clockwork reads it when [zenRequest] moves.
     * A tree composed afresh — a window swapped in for immersive mode on Windows —
     * would otherwise read a Z pressed long ago as pressed now, and go straight to zen.
     */
    var zenHandled = 0

    /** When the person last did anything, in `System.nanoTime`. */
    var lastInput = System.nanoTime()

    /** Whether idleness deepens into zen by itself. The studio turns it off and sets the phase by hand. */
    var zenAuto = true

    /** Something happened: zen, if it had begun, ends. Returns the phase it woke from. */
    fun wake(): ZenPhase {
        lastInput = System.nanoTime()
        val was = neue.zen
        if (was != ZenPhase.AWAKE) neue.zen = ZenPhase.AWAKE
        if (was == ZenPhase.DEEP) zen.forget()
        return was
    }
    var decksReload by mutableStateOf(0)

    fun setFormat(format: Format) {
        builder.onFormatChange(format)
        layout.update { it.copy(format = format) }
    }

    fun setSearchEffects(on: Boolean) {
        builder.onSearchEffectsChange(on)
        layout.update { it.copy(searchEffects = on) }
    }

    /** The window's key handler: one table, one resolve, one dispatch (`DeskShortcuts`). */
    fun onKey(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp) {
            held.remove(event.key)
            return false
        }
        if (event.type != KeyEventType.KeyDown) return false
        // The first key after deep zen only wakes the builder: nothing should
        // happen to a deck you were not looking at.
        if (wake() == ZenPhase.DEEP) {
            held.add(event.key)
            return true
        }
        val repeat = !held.add(event.key)
        val chord = DeskKeys.chord(event) ?: return false
        val shortcut = DeskShortcuts.resolveShortcut(chord, deskContext()) ?: return false
        if (repeat && !shortcut.repeatable) return true
        if (echo.admit(shortcut.action, System.currentTimeMillis())) run(shortcut.action)
        return true
    }

    /** What is on screen, as the shortcut table reads it. */
    /** Every kit text field's focus, reported by the fields themselves (touch swarm, rec 6). */
    val textFocus = com.kaiharimoto.neue.kit.TextFocus()

    fun deskContext() = DeskContext(
        textInputFocused = textFocus.any || builder.textInputFocused || neue.searchFocused,
        searchFocused = neue.searchFocused,
        overlayOpen = neue.overlayOpen || overlays.isOpen || builder.editingGoal != null || updates.dialogOpen,
        onBuilder = neue.page == Page.BUILDER,
    )

    /**
     * The Mac's menu bar choosing [action] (`DeskMenuBar`): only where its key
     * would have worked, and once per press — the menu's accelerator and
     * [onKey] may both hear the same Command chord, and [echo] keeps the second out.
     */
    fun runFromMenu(action: DeskAction) {
        wake()
        if (!DeskMenuBar.enabled(action, deskContext())) return
        if (echo.admit(action, System.currentTimeMillis())) run(action)
    }

    private val echo = ActionEcho()

    fun run(action: DeskAction) {
        val state = builder
        when (action) {
            DeskAction.PALETTE -> neue.paletteOpen = !neue.paletteOpen
            DeskAction.GO_DECKS -> neue.go(Page.DECKS)
            DeskAction.GO_BUILDER -> neue.go(Page.BUILDER)
            DeskAction.GO_ODDS -> neue.go(Page.ODDS)
            DeskAction.GO_STATS -> neue.go(Page.STATS)
            DeskAction.GO_SETTINGS -> neue.go(Page.SETTINGS)
            DeskAction.HELP -> neue.helpOpen = true
            DeskAction.DISMISS -> dismiss()
            DeskAction.SAVE -> state.save { decksReload++ }
            DeskAction.UNDO -> state.undo()
            DeskAction.REDO -> state.redo()
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
            DeskAction.REMOVE_SELECTED -> (neue.selection as? Selection.InDeck)?.let { sel ->
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
            DeskAction.TOGGLE_INSPECTOR -> neue.update { it.copy(inspectorVisible = !it.inspectorVisible) }
            DeskAction.TOGGLE_POOL -> neue.update { it.copy(poolVisible = !it.poolVisible) }
            DeskAction.TOGGLE_FILTERS -> neue.update { it.copy(filtersOpen = !it.filtersOpen, poolVisible = true) }
            DeskAction.NEXT_LENS -> stepLens(1)
            DeskAction.PREVIOUS_LENS -> stepLens(-1)
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
        }
    }

    /**
     * The Groups button (1.0.15): the Roles lens and the panel beside the deck,
     * together — on, the deck breaks into its groups and they can be edited; off,
     * it is the plain deck again, with no gaps and no colour.
     */
    fun setGroups(on: Boolean) {
        val state = builder
        if (on) {
            state.useLens(Lens.ROLES)
        } else {
            state.cancelGroupDraft()
            state.useLens(Lens.DECK)
        }
    }

    /**
     * The arrow keys (kai, 1.0.18): the selected card's neighbour becomes the
     * selection, and the inspector shows it — the hover is let go of, since it
     * outranks the selection there. In the deck, up past a section's top row and
     * down past its bottom carry on into the section above or below, in the same
     * column where it can. With nothing selected, up and down walk the pool.
     */
    private fun moveSelection(direction: StepDirection) {
        val state = builder
        when (val sel = if (neue.searchFocused) null else neue.selection) {
            is Selection.InDeck -> {
                val ids = state.deck[sel.section]
                val columns = com.kaiharimoto.neue.builder.columnsOf(sel.section)
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
                    val cols = com.kaiharimoto.neue.builder.columnsOf(target)
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

    /** The foil on every card face, on or off (the shiny button beside Groups, 1.0.15). */
    fun toggleFoil() = neue.update { it.copy(foil = if (it.foil == com.kaiharimoto.neue.cards.Foils.OFF) com.kaiharimoto.neue.cards.Foils.HOLO else com.kaiharimoto.neue.cards.Foils.OFF) }

    /** `b` and Shift `b`: the lens tabs in turn — the Roles lens is the Groups button's, not a tab. */
    private fun stepLens(by: Int) {
        val tabs = LENS_TABS
        val at = tabs.indexOf(builder.lens).coerceAtLeast(0)
        builder.useLens(tabs[((at + by) % tabs.size + tabs.size) % tabs.size])
    }

    /** What is open, as `BackChain` reads it (touch swarm, rec 2): Esc and Android's Back share one chain. */
    private fun backFlags() = BackFlags(
        updateDialog = updates.dialogOpen,
        overlay = overlays.isOpen,
        top = neue.hasTop,
        coverPicker = neue.coverPicking != null,
        goal = builder.editingGoal != null,
        draft = builder.groupDraft != null,
        focus = textFocus.any || builder.textInputFocused || neue.searchFocused,
        palettes = neue.groupPalettesOpen,
        isolation = builder.isolatedKey != null,
        selection = neue.selection != null,
        immersive = neue.immersive,
        offBuilder = neue.page != Page.BUILDER,
    )

    /** Esc unwinds one layer at a time, from the top: overlays, then modes, then focus, then selection. */
    private fun dismiss() {
        BackChain.esc(backFlags())?.let(::unwind)
    }

    /** Whether Back has anything to close; with nothing, the system's own back (and its predictive preview) is right. */
    fun canGoBack(): Boolean = BackChain.back(backFlags()) != null

    /** Android's Back: one layer, as Esc — never focus or the selection. Returns false when there was nothing. */
    fun back(): Boolean {
        wake()
        val step = BackChain.back(backFlags()) ?: return false
        unwind(step)
        return true
    }

    private fun unwind(step: Unwind) {
        val state = builder
        when (step) {
            Unwind.UPDATE_DIALOG -> updates.dialogOpen = false
            Unwind.OVERLAY -> overlays.dismiss()
            Unwind.TOP -> neue.dismissTop()
            Unwind.COVER_PICKER -> neue.coverPicking = null
            Unwind.GOAL -> state.cancelGoal()
            Unwind.DRAFT -> state.cancelGroupDraft()
            Unwind.FOCUS -> focus?.clearFocus()
            Unwind.PALETTES -> neue.groupPalettesOpen = false
            Unwind.ISOLATION -> state.isolatedKey?.let(state::toggleIsolation)
            Unwind.SELECTION -> neue.selection = null
            Unwind.IMMERSIVE -> run(DeskAction.IMMERSIVE)
            Unwind.TO_BUILDER -> neue.go(Page.BUILDER)
        }
    }

    fun commands(query: String): List<Command> {
        val q = query.trim().lowercase()
        fun kbd(a: DeskAction) = DeskShortcuts.chordFor(a)?.let(DeskShortcuts::kbd)
        fun cmd(group: String, label: String, action: DeskAction) = Command(group, label, kbd(action)) { run(action) }
        val fixed = listOf(
            cmd("Go", "Decks", DeskAction.GO_DECKS),
            cmd("Go", "Builder", DeskAction.GO_BUILDER),
            cmd("Go", "Odds", DeskAction.GO_ODDS),
            cmd("Go", "Stats", DeskAction.GO_STATS),
            cmd("Go", "Settings", DeskAction.GO_SETTINGS),
            cmd("Deck", "Save", DeskAction.SAVE),
            cmd("Deck", "New deck", DeskAction.NEW_DECK),
            cmd("Deck", "Import a .ydk or .ydkx", DeskAction.IMPORT),
            cmd("Deck", "Export", DeskAction.EXPORT),
            Command("Deck", "Export as a .ydk file") { CardActions.export(DeckExportFormat.YDK, builder, neue) },
            Command("Deck", "Export as a .ydkx file, with groups") { CardActions.export(DeckExportFormat.YDKX, builder, neue) },
            Command("Deck", "Copy the YDKe code") { CardActions.export(DeckExportFormat.YDKE, builder, neue) },
            Command("Deck", "Copy the decklist as text") { CardActions.export(DeckExportFormat.TEXT, builder, neue) },
            Command("Deck", if (neue.prefs.extraVisible) "Hide the extra deck" else "Show the extra deck") { neue.update { it.copy(extraVisible = !it.extraVisible) } },
            Command("Deck", if (neue.prefs.sideVisible) "Hide the side deck" else "Show the side deck") { neue.update { it.copy(sideVisible = !it.sideVisible) } },
            Command("Deck", if (neue.prefs.foil == com.kaiharimoto.neue.cards.Foils.OFF) "Foil on" else "Foil off") { toggleFoil() },
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
            cmd("Deck", "Next lens", DeskAction.NEXT_LENS),
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
            Command("App", "Refresh the card pool") { builder.refreshCardPool(force = true) },
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
}

@Composable
fun rememberHolders(deps: AppDependencies, makeUpdates: (kotlinx.coroutines.CoroutineScope) -> NeueUpdates): NeueHolders {
    val scope = rememberCoroutineScope()
    return remember {
        val builder = DeckBuilderState(deps, scope)
        val art = ArtLibrary(java.io.File(Platform.dataDir, "card-art-hd"), scope)
        NeueHolders(
            deps = deps,
            builder = builder,
            layout = DeckLayoutState(deps.preferencesRepository, scope),
            neue = NeueState(deps.preferencesRepository, scope),
            drag = NeueDrag(builder),
            updates = makeUpdates(scope),
            art = art,
            shots = DeckShots(art, scope),
        )
    }
}

/**
 * The window's content: title bar, rail, the page, and every layer above it —
 * drawers, dialogs, the palette, the menu, the card in the air, the toast.
 *
 * [launchEffects] brings the app's own lifetime with it ([NeueEffects]): right for
 * the tablet's one activity. A host that swaps windows — the desktop, for immersive
 * mode on Windows — composes [NeueEffects] once, outside its windows, and passes
 * false, so a window swapped in does not start the app over.
 */
@Composable
fun NeueRoot(h: NeueHolders, launchEffects: Boolean = true) {
    val focus = LocalFocusManager.current
    h.focus = focus

    if (launchEffects) NeueEffects(h)

    NeueWindowContent(h)
}

/**
 * What lives as long as the app, not as long as a window: the database, the card
 * pool, the preferences, the update check, the art library, and the deck to open
 * with. Until 1.0.24 these were the window's, and on Windows every trip into or out
 * of immersive mode — a new window — stopped them all and started them again: the
 * image loader made afresh, so every card's picture was read again, the pool
 * reloaded, the art library restarted. That was much of the moment in which the
 * whole app went blank (kai: "the whole app disappears for a second").
 */
@Composable
fun NeueEffects(h: NeueHolders) {
    val neue = h.neue
    val state = h.builder
    run {
        DisposableEffect(Unit) {
            configureImageLoader(java.io.File(Platform.dataDir, "card-art").absolutePath)
            h.layout.start { prefs ->
                state.onFormatChange(prefs.format)
                state.onSearchEffectsChange(prefs.searchEffects)
            }
            neue.start()
            state.start()
            h.updates.check(userInitiated = false)
            h.art.start()
            onDispose {
                h.art.stop()
                h.layout.flush()
                neue.flush()
            }
        }
    }

    run {
        // The deck to open with (kai, 1.0.14): the default, else the one saved last — and
        // only onto an empty builder, so an import made while the library was opening wins.
        LaunchedEffect(Unit) {
            snapshotFlow { neue.ready }.first { it }
            if (state.deckId != null || !state.deck.isEmpty) return@LaunchedEffect
            val id = StartingDeck.pick(h.deps.deckRepository.all().map { it.entry }, neue.prefs.defaultDeckId)
            if (id != null && state.deckId == null && state.deck.isEmpty) state.load(id)
        }
        // The art library takes the pool in its own order, and what is on screen first.
        LaunchedEffect(state.index) { if (state.index.size > 0) h.art.catalogue(state.index.cards) }
        LaunchedEffect(state.deck, state.index) {
            h.art.want(DeckSection.entries.flatMap { state.deck[it] }.distinct().mapNotNull(state.index::byId))
        }
        LaunchedEffect(state.results) { h.art.want(state.results.take(48)) }
        LaunchedEffect(neue.inspected) { neue.inspected?.let(h.art::want) }
        LaunchedEffect(neue.prefs.hdArt) { h.art.enable(neue.prefs.hdArt) }
    }
}

@Composable
private fun NeueWindowContent(h: NeueHolders) {
    val neue = h.neue
    // The groups' palette: read wherever a group is coloured, so set once here.
    SideEffect { com.kaiharimoto.neue.cards.GroupMarkers.palette = com.kaiharimoto.neue.cards.GroupMarkers.byId(neue.prefs.groupPalette) }
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * neue.prefs.scale, base.fontScale), LocalArt provides h.art, LocalNameStyle provides neue.prefs.foilNames, LocalZen provides h.zen, LocalCursor provides h.cursor, LocalOverlays provides h.overlays, com.kaiharimoto.neue.kit.LocalTouchFirst provides neue.touchFirst, com.kaiharimoto.neue.kit.LocalTextFocus provides h.textFocus, com.kaiharimoto.neue.cards.LocalArts provides neue.prefs.arts, com.kaiharimoto.neue.cards.LocalArtStep provides neue::stepArt, com.kaiharimoto.neue.art.LocalCustomArt provides h.customArt) {
        MuTheme(ink = neue.prefs.theme == NeueTheme.INK, high = neue.prefs.contrast == NeuePreferences.CONTRAST_HIGH) {
            com.kaiharimoto.neue.kit.ProvideTextMenus {
                Shell(h)
            }
        }
    }
}

@Composable
private fun Shell(h: NeueHolders) {
    val neue = h.neue
    val state = h.builder
    val c = Mu.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val immersive = neue.immersive
    val pinned = neue.railPinned && !immersive
    // How tall the folded bars are when out, measured, so the pointer knows when it has left them.
    val measured = remember { FoldedBars() }

    val status = when {
        state.isSyncing -> ShellStatus("Syncing card pool", running = true)
        state.index.size == 0 -> ShellStatus("No card pool", running = false)
        else -> null
    }
    val titleBar: @Composable () -> Unit = {
        // What is being fetched, and how far it has got (kai: "a progress bar indicating if the
        // program is downloading images or updating the card pool"). Read here, in the bar's
        // own scope, so the art's arrivals redraw the bar and not the window.
        val work = com.kaiharimoto.mastertool.core.offline.Offline.readout(
            pool = state.poolProgress ?: if (state.isSyncing) com.kaiharimoto.mastertool.core.data.PoolProgress.Asking else null,
            art = h.art.count,
            artRunning = h.art.running && neue.prefs.hdArt,
            problem = h.art.problem,
        )
        TitleBar(
            neue = neue,
            update = h.updates.available?.versionName,
            onUpdate = { h.updates.dialogOpen = true },
            onImmersive = { h.run(DeskAction.IMMERSIVE) },
            work = work,
            onWork = { neue.go(Page.SETTINGS) },
        ) { narrow ->
            if (neue.page == Page.BUILDER) {
                BuilderBar(state, neue, h::setFormat, onScreenshot = { h.run(DeskAction.SCREENSHOT) }, onSave = { h.run(DeskAction.SAVE) }, narrow = narrow)
            } else {
                Box(Modifier.weight(1f))
            }
        }
    }
    val rail: @Composable () -> Unit = {
        Rail(
            neue = neue,
            version = Platform.version,
            counts = mapOf(Page.BUILDER to state.deck.main.size.toString()),
            status = status,
            art = h.art.progressLine,
        )
    }

    ZenClockwork(h)
    AutoSave(h)
    PoolSource(h)
    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .onSizeChanged { h.zen.window = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            // The family cursor draws the pointer; the system's is hidden everywhere in the
            // window, over every child's own icon, unless the cursor has stepped aside.
            .pointerHoverIcon(if (h.cursor.native) PointerIcon.Default else FamilyCursor.BLANK, overrideDescendants = true)
            .pointerInput(Unit) {
                // One watcher over the whole window, on the way down, consuming
                // nothing: every bar that folds away comes out from here.
                awaitPointerEventScope {
                    var still = Offset.Unspecified
                    // A box being dragged over the table in deep zen: where it started, and what
                    // was picked out before it when Shift added to that.
                    var boxFrom: Offset? = null
                    var boxBase = emptySet<Int>()
                    var boxShift = false
                    // A finger's press, for telling a tap from a swipe (touch swarm, rec 3).
                    var fingerFrom: Offset? = null
                    var fingerAt = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val at = event.changes.firstOrNull()?.position
                        val gone = event.type == PointerEventType.Exit
                        // The family cursor is a mouse's: a finger (or a tablet, where the system
                        // draws its own pointer) leaves it hidden.
                        val mouse = event.changes.none { it.byFinger } && !neue.touchFirst
                        h.cursor.moved(if (gone || !mouse) null else at, mouse && event.buttons.areAnyPressed)
                        // Where the pointer is, for the deck to turn toward in zen.
                        if (neue.immersive) h.zen.pointer = if (gone) null else at
                        // Waking. Before zen is deep, any real movement, press or scroll brings
                        // the builder back (a pointer that twitches a pixel on a desk does not).
                        // Once it is deep, nothing the pointer does wakes it — the pointer is for
                        // arranging the cards, which are the garden — and only a key ends it.
                        val deepZen = neue.zen == ZenPhase.DEEP
                        // The groups' palettes stay out while they are tried, and fold at a press
                        // off the Groups panel (1.0.24). Consuming nothing, so the press still does
                        // what it was for. An overlay's press is the overlay's.
                        if (event.type == PointerEventType.Press && neue.groupPalettesOpen && !neue.overlayOpen && at != null) {
                            val panel = neue.groupsPanel
                            if (panel == null || !panel.contains(at)) neue.groupPalettesOpen = false
                        }
                        when (event.type) {
                            PointerEventType.Press -> if (!deepZen) h.wake()
                            // In deep zen the wheel opens and closes the gaps between the groups
                            // (kai, 1.0.17); up past closed opens them, and zen re-fits the cards.
                            PointerEventType.Scroll -> if (!deepZen) {
                                h.wake()
                            } else {
                                val d = event.changes.firstOrNull()?.scrollDelta ?: Offset.Zero
                                val step = if (d.y != 0f) d.y else d.x
                                if (step != 0f && state.groups.groups.isNotEmpty()) {
                                    if (!h.zen.groups && step < 0f) {
                                        h.zen.groups = true
                                    } else if (h.zen.groups) {
                                        h.zen.gapScale = (h.zen.gapScale - step * 0.15f).coerceIn(ZEN_GAP_MIN, ZEN_GAP_MAX)
                                    }
                                }
                            }
                            PointerEventType.Move -> if (at != null && !deepZen) {
                                if (!still.isSpecified || (at - still).getDistance() > 3f) {
                                    still = at
                                    h.wake()
                                }
                            }
                        }
                        // The corner where "put the cards back" comes out.
                        // On a touch screen nothing can reach for the corner, so in deep zen it stays out.
                        h.zen.corner = deepZen && (neue.touchFirst || at != null && !gone &&
                            ZenCorner.reaches(at.x, at.y, size.width.toFloat(), size.height.toFloat(), h.zen.cornerRow))
                        // Deep zen, 1.0.14: a press on the table rather than on a card draws a box,
                        // and the cards it touches are picked out to move together (ZenGestures).
                        // The press is spent here, on the way down, so the pool and the inspector
                        // — faded out, not gone — never hear it.
                        val zen = h.zen
                        val from = boxFrom
                        when {
                            from == null && deepZen && event.type == PointerEventType.Press && at != null &&
                                event.isPrimaryPress && zen.deck.width > 0f &&
                                !zen.corner && zen.pickAt(at) == null -> {
                                boxFrom = at
                                boxShift = event.keyboardModifiers.isShiftPressed
                                boxBase = zen.selection
                                zen.marquee = Rect(at, at)
                                event.changes.forEach { it.consume() }
                            }
                            from != null && at != null && event.type == PointerEventType.Move -> {
                                val box = Rect(minOf(from.x, at.x), minOf(from.y, at.y), maxOf(from.x, at.x), maxOf(from.y, at.y))
                                zen.marquee = box
                                if (box.width > ZenPick.BOX_SLOP || box.height > ZenPick.BOX_SLOP) {
                                    zen.selection = ZenPick.combine(boxBase, zen.within(box), boxShift)
                                }
                                event.changes.forEach { it.consume() }
                            }
                            from != null && (event.type == PointerEventType.Release || !event.buttons.areAnyPressed) -> {
                                val box = zen.marquee
                                if (box == null || (box.width <= ZenPick.BOX_SLOP && box.height <= ZenPick.BOX_SLOP)) {
                                    zen.selection = ZenGestures.tableClick(boxBase, boxShift)
                                }
                                zen.marquee = null
                                boxFrom = null
                                event.changes.forEach { it.consume() }
                            }
                        }
                        if (boxFrom != null && !deepZen) {
                            zen.marquee = null
                            boxFrom = null
                        }
                        // A click anywhere below the folded-out header lets go of the deck name:
                        // on a desktop nothing else takes focus from a text field, so the bar
                        // that is held out while you type would otherwise never fold away.
                        if (event.type == PointerEventType.Press && neue.immersive && neue.revealed.top &&
                            state.textInputFocused && !neue.searchFocused && at != null && at.y > measured.top
                        ) {
                            h.focus?.clearFocus()
                        }
                        neue.revealed = EdgeReveal.next(
                            current = neue.revealed,
                            x = if (gone) null else at?.x,
                            y = if (gone) null else at?.y,
                            height = size.height.toFloat(),
                            railWidth = (if (neue.touchFirst) MuShell.strip else MuShell.rail).toPx(),
                            topHeight = measured.top.toFloat(),
                            bottomHeight = measured.bottom.toFloat(),
                            immersive = neue.immersive,
                            holdTop = state.textInputFocused && !neue.searchFocused,
                            suppress = h.drag.held != null || neue.menu != null || neue.zen == ZenPhase.DEEP,
                        // The builder has no footer since 1.0.9: nothing comes up from the bottom.
                        ).copy(bottom = false)
                        // A finger cannot reach an edge the system does not take, so in immersive a
                        // tap on the paper strip along the top, or in the gutter down the left,
                        // brings that bar out; a tap anywhere else folds it (EdgeReveal.onTap).
                        val finger = event.changes.firstOrNull()?.takeIf { it.byFinger }
                        if (finger != null && event.type == PointerEventType.Press) {
                            fingerFrom = finger.position
                            fingerAt = finger.uptimeMillis
                        } else if (finger != null && event.type == PointerEventType.Release) {
                            val from0 = fingerFrom
                            fingerFrom = null
                            if (from0 != null && neue.immersive && neue.zen != ZenPhase.DEEP && h.drag.held == null &&
                                (finger.position - from0).getDistance() < viewConfiguration.touchSlop &&
                                finger.uptimeMillis - fingerAt < com.kaiharimoto.mastertool.core.input.DeskTouch.DOUBLE_TAP_MS
                            ) {
                                neue.revealed = EdgeReveal.onTap(
                                    current = neue.revealed,
                                    x = finger.position.x,
                                    y = finger.position.y,
                                    topStrip = IMMERSIVE_TOP.toPx(),
                                    leftStrip = 32.dp.toPx(),
                                    topHeight = measured.top.toFloat(),
                                    railWidth = MuShell.strip.toPx(),
                                    immersive = true,
                                )
                            }
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!immersive) titleBar()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (pinned) rail()
                Box(Modifier.weight(1f)) {
                    Crossfade(neue.page, animationSpec = tween(MuMotion.PAGE, easing = MuMotion.ease), label = "page") { page ->
                        when (page) {
                            Page.DECKS -> DecksPage(h.deps, state, neue, h.decksReload)
                            Page.BUILDER -> BuilderPage(state, neue, h.drag, h::setSearchEffects)
                            Page.ODDS -> OddsPage(state)
                            Page.STATS -> StatsPage(state)
                            Page.SETTINGS -> SettingsPage(
                                state,
                                neue,
                                SettingsHost(
                                    version = Platform.version,
                                    dataDir = Platform.dataDir.absolutePath,
                                    updateStatus = h.updates.status,
                                    checking = h.updates.checking,
                                    onCheckUpdates = { h.updates.check(userInitiated = true) },
                                    onReportIssue = { Platform.reportIssue() },
                                    onOpenDataDir = { Platform.open(Platform.dataDir) },
                                    onSearchEffects = h::setSearchEffects,
                                    art = h.art,
                                ),
                            )
                        }
                    }
                    Drawers(state, neue)
                }
            }
        }

        // The bars that fold away slide over the page rather than pushing it:
        // a bar that pushed would re-fit the deck, and every card would jump.
        val out = neue.revealed
        if (immersive) {
            val top by animateFloatAsState(if (out.top) 1f else 0f, tween(MuMotion.BASE, easing = MuMotion.ease), label = "top")
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { measured.top = it.height }
                    .offset { IntOffset(0, (-(1f - top) * (measured.top + 2)).toInt()) }
                    .background(c.paper),
            ) {
                titleBar()
            }
        }
        if (!pinned) {
            val left by animateFloatAsState(if (out.left) 1f else 0f, tween(MuMotion.BASE, easing = MuMotion.ease), label = "rail")
            val railPx = with(density) { (if (neue.touchFirst) MuShell.strip else MuShell.rail).roundToPx() }
            if (left > 0.001f) {
                Box(
                    Modifier
                        .padding(top = if (immersive) 0.dp else MuShell.top)
                        .fillMaxHeight()
                        .offset { IntOffset((-(1f - left) * (railPx + 2)).toInt(), 0) },
                ) {
                    rail()
                }
            }
        }

        // The card in the air: drawn where the pointer is, lifted off the page and
        // leaning back against the motion (DeskLean.carried) — kai's one
        // exception to Master UI's stillness, and only ever on a card.
        val carry = rememberCarryMotion(h.drag)
        h.drag.held?.let { held ->
            Box(
                Modifier
                    .offset { IntOffset((h.drag.pointer.x - held.size.width / 2f).toInt(), (h.drag.pointer.y - held.size.height / 2f).toInt()) }
                    .size(with(density) { held.size.width.toDp() }, with(density) { held.size.height.toDp() }),
            ) {
                NeueCard(
                    held.card,
                    Modifier.fillMaxSize(),
                    format = state.format,
                    foil = neue.prefs.foil,
                    outlined = true,
                    motion = { carry.pose(held.size.width.toFloat()) },
                )
            }
        }

        if (neue.helpOpen) HelpDialog { neue.helpOpen = false }
        neue.confirmDelete?.let { (id, name) ->
            MuDialog(
                title = "Delete deck",
                onDismiss = { neue.confirmDelete = null },
                width = 384.dp,
                description = "“$name” will be removed from this computer. This cannot be undone.",
                footer = {
                    MuButton("Cancel", { neue.confirmDelete = null }, variant = BtnVariant.GHOST)
                    MuButton("Delete", {
                        neue.confirmDelete = null
                        scope.launch {
                            h.deps.deckRepository.delete(id)
                            if (neue.prefs.defaultDeckId == id || id in neue.prefs.covers) {
                                neue.update { it.copy(defaultDeckId = it.defaultDeckId?.takeIf { d -> d != id }, covers = it.covers - id) }
                            }
                            // The builder is never left empty while the library has a deck to open.
                            if (state.deckId == id) {
                                val next = StartingDeck.pick(h.deps.deckRepository.all().map { it.entry }, neue.prefs.defaultDeckId)
                                if (next != null) state.load(next) else state.newDeck()
                            }
                            h.decksReload++
                        }
                    }, variant = BtnVariant.PRIMARY)
                },
            ) {}
        }
        if (h.updates.dialogOpen) UpdateDialog(h.updates)
        if (neue.paletteOpen) CommandPalette(h::commands) { neue.paletteOpen = false }
        if (immersive) {
            ZenReset(
                h.zen,
                hasGroups = state.groups.groups.isNotEmpty(),
                onLeave = { h.wake() },
                labels = neue.prefs.zenLabels,
                onLabels = { neue.update { it.copy(zenLabels = !it.zenLabels) } },
                modifier = Modifier.align(Alignment.BottomEnd),
                always = neue.touchFirst,
                onLeaveFullScreen = if (neue.touchFirst) ({ h.wake(); h.run(DeskAction.IMMERSIVE) }) else null,
            )
        }
        // The box being dragged over the table in deep zen: a hairline and the faintest wash.
        h.zen.marquee?.let { box ->
            Canvas(Modifier.fillMaxSize()) {
                drawRect(c.ink06, box.topLeft, box.size)
                drawRect(c.ink, box.topLeft, box.size, style = Stroke(1.dp.toPx()))
            }
        }
        com.kaiharimoto.neue.builder.SearchStudio(state, neue)
        CardViewer(state, neue)
        MenuLayer(neue.menu) { neue.menu = null }
        OverlayLayer(h.overlays)

        Toasts(h, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 24.dp))

        // Long jobs the whole window waits on: the cursor ticks and says so.
        val job = when {
            h.updates.downloading -> "Downloading" to h.updates.progress?.let { it * 100f }
            h.shots.taking -> "Exporting" to null
            else -> null
        }
        LaunchedEffect(job) { if (job == null) h.cursor.clearBusy() else h.cursor.setBusy(job.first, job.second) }
        // Last in the window, over everything in it.
        CursorLayer(h.cursor)
    }
}

/**
 * The pool shows the list it was switched to (1.0.19): the list is the filter's
 * `onlyIds`, kept in step here — so a list changed from a menu, or a filter
 * replaced whole by a click in the inspector, still leaves the pool on the list.
 */
@Composable
private fun PoolSource(h: NeueHolders) {
    val state = h.builder
    val wanted = h.neue.list(h.neue.prefs.poolList)?.ids?.toSet()
    LaunchedEffect(wanted, state.filter.onlyIds) {
        if (state.filter.onlyIds != wanted) state.onFilterChange(state.filter.copy(onlyIds = wanted))
    }
}

/**
 * Auto save (kai, 1.0.18): while it is on, a deck that has changed is saved a
 * second and a half after the last change — quietly, because a toast after every
 * edit would be noise. An empty deck that was never saved is left alone, or
 * starting a new deck would put an empty one in the library.
 */
@Composable
private fun AutoSave(h: NeueHolders) {
    val state = h.builder
    val on = h.neue.prefs.autoSave
    LaunchedEffect(on, state.dirty, state.deck, state.deckName, state.groups, state.goals) {
        if (!on || !state.dirty) return@LaunchedEffect
        if (state.deckId == null && state.deck.totalCards == 0) return@LaunchedEffect
        delay(1_500)
        state.save(quiet = true) { h.decksReload++ }
    }
}

/**
 * Zen's clockwork: the phase deepens with idleness (`ZenClock`), and two
 * amounts follow the phase — slowly in, and back "slowly" as kai asked, a little
 * slower than they went. They are written into [ZenLayer] frame by frame and read
 * only in layers and draw blocks, so nothing recomposes while they move. The
 * clock that floats the cards runs only while zen is deep.
 */
@Composable
private fun ZenClockwork(h: NeueHolders) {
    val neue = h.neue
    // An empty deck has nothing to float (kai, 1.0.14): zen waits for a card.
    val eligible = neue.immersive && neue.page == Page.BUILDER && h.builder.deck.totalCards > 0
    LaunchedEffect(eligible, h.zenAuto, neue.prefs.autoZen) {
        if (!eligible) {
            neue.zen = ZenPhase.AWAKE
            return@LaunchedEffect
        }
        // The bar's Zen switch (1.0.16): off, zen comes only when asked for (Z).
        if (!h.zenAuto || !neue.prefs.autoZen) return@LaunchedEffect
        h.lastInput = System.nanoTime()
        while (true) {
            // A menu, a dialog or a card in the hand is someone doing something.
            if (h.drag.held != null || neue.overlayOpen || h.updates.dialogOpen) h.lastInput = System.nanoTime()
            val idle = (System.nanoTime() - h.lastInput) / 1_000_000
            val phase = ZenClock.phase(idle)
            if (phase > neue.zen) neue.zen = phase
            val wait = ZenClock.untilNext(idle)
            if (wait == null) {
                snapshotFlow { neue.zen }.first { it != ZenPhase.DEEP }
            } else {
                delay(wait.coerceAtLeast(50))
            }
        }
    }

    // From where the amounts are, so a tree composed afresh mid-fade carries on from there.
    val quiet = remember { Animatable(h.zen.quiet) }
    val deep = remember { Animatable(h.zen.deep) }
    val phase = if (eligible) neue.zen else ZenPhase.AWAKE
    LaunchedEffect(phase) {
        // Deep is the pointer's: from this moment, nothing but the cards answers it.
        val begins = phase == ZenPhase.DEEP && !h.zen.asleep
        h.zen.asleep = phase == ZenPhase.DEEP
        // Every zen starts with the cards in their slots (1.0.24): the last one's are forgotten.
        if (begins) h.zen.begin()
        val q = if (phase != ZenPhase.AWAKE) 1f else 0f
        val d = if (phase == ZenPhase.DEEP) 1f else 0f
        coroutineScope {
            launch {
                quiet.animateTo(q, tween(if (q > 0f) ZEN_IN else ZEN_OUT, delayMillis = if (q > 0f) 0 else 400, easing = MuMotion.ease)) { h.zen.quiet = value }
            }
            launch {
                deep.animateTo(d, tween(if (d > 0f) ZEN_DEEP_IN else ZEN_OUT, easing = MuMotion.ease)) { h.zen.deep = value }
            }
        }
    }
    // Zen's pieces (1.0.15): on from the start when the builder had its groups on, so
    // the pieces the person was looking at stay open; asked for from the corner otherwise.
    LaunchedEffect(phase) {
        if (phase == ZenPhase.DEEP) {
            val on = h.builder.lens == Lens.ROLES
            h.zen.groups = on
            h.zen.groupsAmount = if (on) 1f else 0f
        } else {
            h.zen.groups = false
        }
    }
    val pieces = remember { Animatable(0f) }
    LaunchedEffect(h.zen.groups) {
        pieces.snapTo(h.zen.groupsAmount)
        pieces.animateTo(if (h.zen.groups) 1f else 0f, tween(ZEN_PIECES, easing = MuMotion.ease)) { h.zen.groupsAmount = value }
    }
    // The groups' names on their pieces (1.0.24): the corner's Labels switch, kept in the settings.
    val labels = remember { Animatable(h.zen.labelsAmount) }
    LaunchedEffect(neue.prefs.zenLabels) {
        labels.snapTo(h.zen.labelsAmount)
        labels.animateTo(if (neue.prefs.zenLabels) 1f else 0f, tween(MuMotion.SLOW, easing = MuMotion.ease)) { h.zen.labelsAmount = value }
    }
    // The Z key (1.0.15): immersive if it was not, and deep at once. A moment first when
    // immersive is only now coming on, so the deck has been laid out full screen before it
    // is measured for the middle of it.
    LaunchedEffect(h.zenRequest) {
        if (h.zenRequest == h.zenHandled) return@LaunchedEffect
        delay(if (h.zenWaitsForLayout) 450 else 0)
        h.zenWaitsForLayout = false
        h.zenHandled = h.zenRequest
        if (neue.immersive && neue.page == Page.BUILDER && h.builder.deck.totalCards > 0) {
            h.lastInput = System.nanoTime()
            neue.zen = ZenPhase.DEEP
        }
    }
    val floating by remember { derivedStateOf { h.zen.deep > 0f } }
    LaunchedEffect(floating) {
        var last = 0L
        while (floating && h.zen.deep > 0f) {
            withFrameNanos { now ->
                if (last != 0L) h.zen.time += ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
            }
        }
    }
}

/** Zen's fades, in milliseconds: a breath in, a longer one for the deck to come forward, and back a little slower. */
private const val ZEN_IN = 1400
private const val ZEN_DEEP_IN = 2600
private const val ZEN_OUT = 1600

/** How far the wheel may close and open zen's gaps, as multiples of the standard gap. */
internal const val ZEN_GAP_MIN = 0.3f
internal const val ZEN_GAP_MAX = 5f

/** The pieces opening or closing in zen: slow enough to watch the deck come apart. */
private const val ZEN_PIECES = 900

/** How tall the folded bars were when last laid out, in pixels. Plain fields: only the pointer watcher reads them. */
private class FoldedBars {
    var top: Int = 0
    var bottom: Int = 0
}

@Composable
private fun Toasts(h: NeueHolders, modifier: Modifier) {
    val toast = h.builder.toast
    val note = h.updates.message
    val own = h.neue.note
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (note != null) {
            LaunchedEffect(note) { delay(4000); h.updates.message = null }
            ToastBox(note, null, {})
        }
        if (own != null) {
            LaunchedEffect(own.id) { delay(6000); if (h.neue.note?.id == own.id) h.neue.note = null }
            ToastBox(own.message, own.action, {
                own.onAction()
                h.neue.note = null
            })
        }
        if (toast != null) {
            LaunchedEffect(toast.id) { delay(4000); h.builder.consumeToast() }
            ToastBox(
                message = toast.message,
                action = toast.undo?.let { "Undo" },
                onAction = {
                    toast.undo?.invoke()
                    h.builder.consumeToast()
                },
            )
        }
    }
}

@Composable
private fun UpdateDialog(updates: NeueUpdates) {
    val update = updates.available ?: return
    val c = Mu.colors
    val inPlace = update.installer != null && Platform.os == com.kaiharimoto.mastertool.core.update.DesktopOs.WINDOWS
    MuDialog(
        title = "Neue Master Tool ${update.versionName}",
        onDismiss = { updates.dialogOpen = false },
        width = 672.dp,
        description = "You have ${Platform.version}. " + when {
            update.installer == null -> "This release has no installer for this system yet."
            inPlace -> "It installs over this one and reopens."
            else -> "The installer downloads and opens."
        },
        footer = {
            MuButton("Later", { updates.dialogOpen = false }, variant = BtnVariant.GHOST)
            MuButton("Release page", updates::openReleasePage, variant = BtnVariant.SUBTLE)
            if (update.installer != null) {
                MuButton(
                    if (updates.downloading) "Downloading" else if (inPlace) "Install" else "Download",
                    updates::install,
                    variant = BtnVariant.PRIMARY,
                    arrow = true,
                    enabled = !updates.downloading,
                )
            }
        },
    ) {
        Box(Modifier.heightIn(max = 360.dp).fillMaxWidth()) {
            SelectionContainer {
                Body(
                    update.release.notes.ifBlank { "No notes for this release." },
                    Modifier.verticalScroll(rememberScrollState()),
                    color = c.ink70,
                )
            }
        }
        if (updates.downloading) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Progress(updates.progress)
                Mono(updates.progress?.let { "${(it * 100).toInt()}%" } ?: "Starting")
            }
        }
    }
}
