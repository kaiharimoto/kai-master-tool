package com.kaiharimoto.neue

import androidx.compose.foundation.layout.imePadding
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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
import androidx.compose.foundation.layout.width
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
import com.kaiharimoto.neue.builder.historyMenu
import com.kaiharimoto.neue.shell.PhoneBar
import com.kaiharimoto.neue.shell.TabBar
import com.kaiharimoto.neue.kit.MenuEntry
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
import com.kaiharimoto.neue.pages.SettingsHost
import com.kaiharimoto.neue.pages.SettingsPage
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
    /** The webs of decks, for Format and the builder's switcher (1.0.33). */
    val webs: com.kaiharimoto.neue.web.Webs,
    /** Tournament prep (1.0.50): events, the test games log, drills. */
    val prep: com.kaiharimoto.neue.prep.Prep,
) {
    private val held = mutableSetOf<androidx.compose.ui.input.key.Key>()
    var focus: FocusManager? = null

    /** Zen's amounts and clock, shared with everything that fades or floats. */
    val zen = ZenLayer()

    /** Pictures the person added to cards themselves (1.0.18). */
    val customArt = com.kaiharimoto.neue.art.CustomArt(java.io.File(Platform.dataDir, "custom-art")).also { neue.customArt = it }

    /** Present (1.0.70): the presentations, the one open in the editor, and the one playing. */
    val present: com.kaiharimoto.neue.present.Presentations by lazy { com.kaiharimoto.neue.present.Presentations(java.io.File(Platform.dataDir, "present")) }

    /** Backups (1.0.69): made when a new version first opens and weekly; exported, restored. */
    val backups: com.kaiharimoto.neue.backup.BackupCenter by lazy { com.kaiharimoto.neue.backup.BackupCenter(this) }

    /** Whether the library held a deck as the app opened: someone new has none (1.0.69, the setup). */
    var decksKnown = false

    /** Offers the setup: what is still to do since the version last opened here, or with [again] every step not done yet. */
    suspend fun offerStart(again: Boolean = false) {
        decksKnown = deps.deckRepository.all().isNotEmpty()
        val state = com.kaiharimoto.neue.start.startState(this)
        val android = Platform.os == com.kaiharimoto.mastertool.core.update.DesktopOs.ANDROID
        val steps = if (again) {
            com.kaiharimoto.mastertool.core.start.StartSteps.pending(Platform.version, com.kaiharimoto.mastertool.core.start.StartPrefs(), state, android)
        } else {
            com.kaiharimoto.mastertool.core.start.StartSteps.pending(Platform.version, neue.prefs.start, state, android)
        }
        if (steps.isEmpty()) {
            if (neue.prefs.start.seen != Platform.version) neue.update { it.copy(start = it.start.copy(seen = Platform.version)) }
        } else {
            neue.startSteps = steps
        }
    }

    /** Sync across devices (1.0.68): where to, what the last sync did, signing in. */
    val sync: com.kaiharimoto.neue.sync.SyncCenter by lazy { com.kaiharimoto.neue.sync.SyncCenter(this) }

    /** The assistant (Ai, 1.0.43): the conversation, its model and its tools, for the app's lifetime. */
    val ai: com.kaiharimoto.neue.ai.AiState by lazy { com.kaiharimoto.neue.ai.AiState(this) }

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
        onBuilder = neue.page == Page.BUILDER && present.playing == null,
        ai = neue.prefs.ai.enabled,
        onPresent = neue.page == Page.PRESENT,
        presenting = present.playing != null,
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

    /**
     * [id] on the builder, the deck there saved first (1.0.33: "saved as you
     * switch"): the web's switcher and the Format page's tiles both come here. A deck
     * never saved and holding cards is saved to the library rather than dropped.
     */
    fun openDeck(id: String) {
        val state = builder
        neue.go(Page.BUILDER)
        if (id == state.deckId) return
        if (state.dirty && (state.deckId != null || !state.deck.isEmpty)) {
            state.save(quiet = true) {
                decksReload++
                state.load(id)
            }
        } else {
            state.load(id)
        }
    }

    /** The deck [step] along in the web of the deck on the builder: `Alt ←`/`Alt →`, and the bar's ‹ ›. */
    fun stepWeb(step: Int) {
        val id = builder.deckId ?: return
        val next = webs.webOf(id)?.neighbour(id, step) ?: return
        openDeck(next)
    }

    fun run(action: DeskAction) {
        val state = builder
        when (action) {
            DeskAction.PALETTE -> neue.paletteOpen = !neue.paletteOpen
            DeskAction.GO_DECKS -> neue.go(Page.DECKS)
            DeskAction.GO_BUILDER -> neue.go(Page.BUILDER)
            DeskAction.GO_SIDING -> neue.go(Page.SIDING)
            DeskAction.GO_FORMAT -> neue.go(Page.FORMAT)
            DeskAction.GO_PREP -> neue.go(Page.PREP)
            DeskAction.GO_PRESENT -> neue.go(Page.PRESENT)
            DeskAction.WEB_PREVIOUS -> stepWeb(-1)
            DeskAction.WEB_NEXT -> stepWeb(1)
            DeskAction.GO_SETTINGS -> neue.go(Page.SETTINGS)
            DeskAction.HELP -> neue.helpOpen = true
            DeskAction.DISMISS -> dismiss()
            DeskAction.SAVE -> state.save { decksReload++ }
            DeskAction.UNDO -> if (neue.page == Page.PRESENT) present.undo() else state.undo()
            DeskAction.REDO -> if (neue.page == Page.PRESENT) present.redo() else state.redo()
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
                val all = com.kaiharimoto.mastertool.core.layout.GroupArrangement.entries
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
            else -> com.kaiharimoto.neue.present.runPresent(this, action)
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

    /** The foil on every card face, on or off (the shiny button beside Groups, 1.0.15). */
    fun toggleFoil() = neue.update { it.copy(foil = if (it.foil == com.kaiharimoto.neue.cards.Foils.OFF) com.kaiharimoto.neue.cards.Foils.HOLO else com.kaiharimoto.neue.cards.Foils.OFF) }

    /** What is open, as `BackChain` reads it (touch swarm, rec 2): Esc and Android's Back share one chain. */
    private fun backFlags() = BackFlags(
        updateDialog = updates.dialogOpen,
        overlay = overlays.isOpen,
        top = neue.hasTop,
        coverPicker = neue.coverPicking != null,
        goal = builder.editingGoal != null,
        draft = builder.groupDraft != null,
        focus = textFocus.any || builder.textInputFocused || neue.searchFocused,
        // Siding is a page of its own (1.0.40): Back leaves it as it leaves any page.
        siding = false,
        palettes = neue.groupPalettesOpen,
        isolation = builder.isolatedKey != null,
        selection = neue.selection != null,
        immersive = neue.immersive,
        offBuilder = neue.page != Page.BUILDER,
    )

    /** Esc unwinds one layer at a time, from the top: overlays, then modes, then focus, then selection. */
    private fun dismiss() {
        if (com.kaiharimoto.neue.present.dismissPresent(this, esc = true)) return
        BackChain.esc(backFlags())?.let(::unwind)
    }

    /** Whether Back has anything to close; with nothing, the system's own back (and its predictive preview) is right. */
    fun canGoBack(): Boolean = present.playing != null || (neue.page == Page.PRESENT && present.open != null) || BackChain.back(backFlags()) != null

    /** Android's Back: one layer, as Esc — never focus or the selection. Returns false when there was nothing. */
    fun back(): Boolean {
        wake()
        if (com.kaiharimoto.neue.present.dismissPresent(this, esc = false)) return true
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
            Unwind.SIDING -> {
                webs.sidingDeckId = null
                webs.sidingAgainst = null
            }
            Unwind.PALETTES -> neue.groupPalettesOpen = false
            Unwind.ISOLATION -> state.isolatedKey?.let(state::toggleIsolation)
            Unwind.SELECTION -> neue.selection = null
            Unwind.IMMERSIVE -> run(DeskAction.IMMERSIVE)
            Unwind.TO_BUILDER -> neue.go(Page.BUILDER)
        }
    }

    /**
     * The phone's overflow (v1.3.5): every tool of the desk's bar, which a phone has no
     * room to lay out, in one menu under the thumb. [at] is where it opened, so its
     * second menus (Export, History) open in the same place.
     */
    fun phoneMenu(at: Offset): List<MenuEntry> {
        val state = builder
        val onBuilder = neue.page == Page.BUILDER
        val next = neue.orientation.next()
        return buildList {
            add(MenuEntry("Search cards and commands", hint = "Search") { neue.paletteOpen = true })
            if (neue.prefs.ai.enabled) add(MenuEntry(ai.name, hint = "Your assistant") { ai.setOpen(true) })
            if (neue.prefs.ai.enabled) add(MenuEntry("Look into ${ai.name}", hint = "What it knows") { ai.memoryOpen = "USER.md" })
            add(MenuEntry("Advanced search") { run(DeskAction.ADVANCED_SEARCH) })
            add(MenuEntry("Present", hint = "Deck profiles as slides") { neue.go(Page.PRESENT) })
            if (onBuilder) {
                add(MenuEntry(if (groupsOn(state)) "Hide the groups" else "Groups", hint = "The deck in pieces") { run(DeskAction.TOGGLE_KEYS) })
                add(MenuEntry("History…", enabled = state.canUndo || state.canRedo, reason = "Nothing changed yet") {
                    neue.menu = MenuSpec(at, historyMenu(state, touch = true))
                })
                add(MenuEntry("Format: ${state.format.name}", hint = "Switch to ${if (state.format == Format.TCG) "OCG" else "TCG"}") {
                    setFormat(if (state.format == Format.TCG) Format.OCG else Format.TCG)
                })
                if (state.validation.errors.isNotEmpty() || state.validation.warnings.isNotEmpty()) {
                    add(MenuEntry("Issues", hint = "${state.validation.errors.size + state.validation.warnings.size}") { neue.drawer = Drawer.ISSUES })
                }
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

    fun commands(query: String): List<Command> {
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
            Command("Present", "New deck profile") { neue.go(Page.PRESENT); present.creating = true },
            *(if (present.open != null) arrayOf(
                cmd("Present", "Present from the start", DeskAction.PRESENT_START),
                cmd("Present", "Present from this slide", DeskAction.PRESENT_FROM_HERE),
                Command("Present", "Rehearse timings") { present.present(0, rehearse = true) },
                cmd("Present", "New slide", DeskAction.SLIDE_NEW),
            ) else emptyArray()),
            *(if (present.open != null && neue.prefs.ai.enabled) arrayOf(
                Command("Present", "Build these slides with ${ai.name}") { neue.go(Page.PRESENT); present.briefing = true },
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
            // The phone's and the tablet's screen, the one-tap toggle in words (v1.3.5).
            *(if (neue.touchFirst) arrayOf(Command("App", "Rotate the screen: ${neue.orientation.next().label}") { neue.rotate() }) else emptyArray()),
            // A deck's QR code, off another screen or out of a picture (v1.3.7).
            *(if (com.kaiharimoto.neue.platform.QrSource.CAMERA in Platform.scanSources) arrayOf(Command("Deck", "Scan a deck's QR code") { CardActions.scan(com.kaiharimoto.neue.platform.QrSource.CAMERA, builder, neue) }) else emptyArray()),
            *(if (com.kaiharimoto.neue.platform.QrSource.PICTURE in Platform.scanSources) arrayOf(Command("Deck", "Import a picture of a QR code") { CardActions.scan(com.kaiharimoto.neue.platform.QrSource.PICTURE, builder, neue) }) else emptyArray()),
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
                Command(ai.name, "${ai.name}: learn this deck from first principles") { ai.setOpen(true); ai.askTune(com.kaiharimoto.mastertool.core.ai.AiSession.MODE_PRINCIPLES) },
                Command(ai.name, "${ai.name}: refactor this deck's guide") { ai.setOpen(true); ai.askTune(com.kaiharimoto.mastertool.core.ai.AiSession.MODE_REFACTOR) },
                Command(ai.name, "${ai.name}: this deck's guide") { ai.openGuide() },
                Command(ai.name, "${ai.name}: read this deck's reader's guide") { ai.openBook() },
                Command(ai.name, "${ai.name}: write this deck's reader's guide") { ai.setOpen(true); ai.askTune(com.kaiharimoto.mastertool.core.ai.AiSession.MODE_WRITE) },
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
            webs = com.kaiharimoto.neue.web.Webs(deps, scope),
            prep = com.kaiharimoto.neue.prep.Prep(deps, scope),
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
            h.webs.load()
            h.prep.load()
            // A .ydkw opened through a deck's Import (or handed over by another app) is a web: to Format with it.
            state.onWebFile = { text ->
                h.webs.open(text) { made ->
                    if (made != null) {
                        neue.go(Page.FORMAT)
                        neue.note = Note("Opened “${made.name}”: ${made.entries.size} decks")
                    }
                }
            }
            h.updates.check(userInitiated = false)
            h.art.start()
            // Ai's notes follow a deck into a web, and go with a web that is deleted (1.0.43).
            h.webs.onJoined = { from, name, web -> if (neue.prefs.ai.enabled) h.ai.foldIntoWeb(from, name, web) }
            h.webs.onDeleted = { web -> h.ai.files.delete(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(com.kaiharimoto.mastertool.core.ai.memory.MemoryKind.WEB, web)) }
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
        // Ai off (Settings → Assistant): every trace gone — the menu's item, the key, and
        // anything running or listening (1.0.43).
        LaunchedEffect(neue.prefs.ai.enabled) {
            DeskMenuBar.aiShown = neue.prefs.ai.enabled
            if (!neue.prefs.ai.enabled) h.ai.shutDown()
        }
        LaunchedEffect(neue.prefs.ai.name) { DeskMenuBar.aiName = neue.prefs.ai.name }
        LaunchedEffect(neue.inspected) { neue.inspected?.let(h.art::want) }
        // As the app opens (1.0.69): a backup first when this version is new here, then the setup still to do.
        LaunchedEffect(neue.ready) {
            if (!neue.ready) return@LaunchedEffect
            h.backups.onOpen()
            h.offerStart()
        }
        // Sync (1.0.68): once everything is read, then every few minutes while the app is open…
        LaunchedEffect(neue.ready, neue.prefs.sync.service, neue.prefs.sync.auto) {
            if (!neue.ready || !neue.prefs.sync.auto) return@LaunchedEffect
            snapshotFlow { h.webs.loaded && h.prep.loaded }.first { it }
            while (true) {
                h.sync.syncNow(quiet = true)
                kotlinx.coroutines.delay(com.kaiharimoto.neue.sync.SyncCenter.EVERY_MS)
            }
        }
        // …and a little after anything that travels changes: a deck saved, a setting, a web, prep, Ai's notes.
        LaunchedEffect(neue.ready) {
            if (!neue.ready) return@LaunchedEffect
            snapshotFlow {
                listOf(
                    h.decksReload, com.kaiharimoto.mastertool.core.sync.SyncedPrefs.extract(neue.prefs).contentHashCode(),
                    h.layout.preferences.format, h.webs.revision, h.prep.doc.hashCode(), h.ai.bookVersion, h.customArt.version,
                )
            }.drop(1).collectLatest {
                kotlinx.coroutines.delay(20_000)
                if (neue.prefs.sync.auto) h.sync.syncNow(quiet = true)
            }
        }
        // The ~2 GB library waits for Wi-Fi on a tablet (touch swarm, rec 27); looked at again each half minute.
        LaunchedEffect(neue.prefs.hdArt) {
            while (true) {
                val free = Platform.onUnmeteredNetwork()
                neue.waitingForWifi = neue.prefs.hdArt && !free
                h.art.enable(neue.prefs.hdArt && free)
                if (!neue.prefs.hdArt) break
                kotlinx.coroutines.delay(30_000)
            }
        }
    }
}

@Composable
private fun NeueWindowContent(h: NeueHolders) {
    val neue = h.neue
    // The groups' palette: read wherever a group is coloured, so set once here.
    SideEffect { com.kaiharimoto.neue.cards.GroupMarkers.palette = com.kaiharimoto.neue.cards.GroupMarkers.byId(neue.prefs.groupPalette) }
    val base = LocalDensity.current
    // What the app is running on and which way round (the phone, v1.3.5): the window's size
    // in physical dp, before the interface scale — a phone does not become a tablet by zoom.
    Box(
        Modifier.fillMaxSize().onSizeChanged { px ->
            val w = px.width / base.density
            val h2 = px.height / base.density
            neue.form = neue.formOverride ?: com.kaiharimoto.mastertool.core.layout.FormFactor.of(w, h2, neue.touchFirst)
            neue.posture = com.kaiharimoto.mastertool.core.layout.Posture.of(w, h2)
        },
    ) {
    // The foil follows the phone's tilt (v1.3.6), while it is on and there is foil to light.
    val tilt = com.kaiharimoto.neue.kit.rememberDeviceTilt(
        on = neue.touchFirst && neue.prefs.foilTilt && neue.prefs.foil != com.kaiharimoto.neue.cards.Foils.OFF,
    )
    CompositionLocalProvider(com.kaiharimoto.neue.kit.LocalTilt provides tilt, LocalDensity provides Density(base.density * neue.prefs.scale, base.fontScale * neue.prefs.textScaleOn(neue.touchFirst, neue.phone)), LocalArt provides h.art, LocalNameStyle provides neue.prefs.foilNames, LocalZen provides h.zen, LocalCursor provides h.cursor, LocalOverlays provides h.overlays, com.kaiharimoto.neue.kit.LocalTouchFirst provides neue.touchFirst, com.kaiharimoto.neue.kit.LocalPhone provides neue.phone, com.kaiharimoto.neue.kit.LocalKeepCase provides (if (neue.prefs.ai.enabled) setOf(neue.prefs.ai.name.ifBlank { "Ai" }, "Ai") else emptySet()), com.kaiharimoto.neue.kit.LocalTextFocus provides h.textFocus, com.kaiharimoto.neue.kit.LocalHardwareKeyboard provides (!neue.touchFirst || neue.hardwareKeyboard), com.kaiharimoto.neue.kit.LocalReasonNote provides { reason: String -> neue.note = Note(reason) }, com.kaiharimoto.neue.cards.LocalArts provides neue.prefs.arts, com.kaiharimoto.neue.cards.LocalArtStep provides { card: com.kaiharimoto.mastertool.core.model.Card, by: Int ->
        neue.stepArt(card, by)
        // A finger stepping a card's art feels it turn over (touch swarm, rec 13).
        neue.actingBy(finger = neue.touchFirst) { neue.felt(com.kaiharimoto.mastertool.core.haptics.DeskEvent.ART_STEPPED) }
    }, com.kaiharimoto.neue.art.LocalCustomArt provides h.customArt) {
        MuTheme(ink = neue.prefs.theme == NeueTheme.INK, high = neue.prefs.contrast == NeuePreferences.CONTRAST_HIGH) {
            com.kaiharimoto.neue.kit.ProvideTextMenus {
                Shell(h)
            }
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
    // The tablet's haptics: nothing on the desk (touch swarm, rec 13).
    neue.feel = com.kaiharimoto.neue.kit.rememberFeel()
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
        if (neue.phone) {
            PhoneBar(
                neue = neue,
                state = state,
                update = h.updates.available?.versionName,
                onUpdate = { h.updates.dialogOpen = true },
                menu = { h.phoneMenu(it) },
                working = work != null,
                ai = if (neue.prefs.ai.enabled) {
                    { _ -> com.kaiharimoto.neue.ai.avatar.AiBadge(h, height = 40.dp) }
                } else {
                    null
                },
            )
        } else TitleBar(
            neue = neue,
            update = h.updates.available?.versionName,
            onUpdate = { h.updates.dialogOpen = true },
            onImmersive = { h.run(DeskAction.IMMERSIVE) },
            work = work,
            onWork = { neue.go(Page.SETTINGS) },
            trailing = {
                if (neue.prefs.ai.enabled) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Ai's face and name in a box on every platform (1.0.63, kai: "on desktop the app is not
                        // the marquee"); its brain is in its panel now, not beside it in the bar.
                        com.kaiharimoto.neue.ai.avatar.AiBadge(h, height = 32.dp)
                    }
                }
            },
        ) { narrow ->
            if (neue.page == Page.BUILDER) {
                BuilderBar(state, neue, h::setFormat, onScreenshot = { h.run(DeskAction.SCREENSHOT) }, onSave = { h.run(DeskAction.SAVE) }, narrow = narrow, webs = h.webs, onStepWeb = h::stepWeb, onOpenDeck = h::openDeck)
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

    // The soft keyboard put away by its own key or a swipe: typing is over, so the
    // field lets go too, and the keys go back to the deck (touch swarm, rec 10).
    val imeOpen = com.kaiharimoto.neue.kit.softKeyboardVisible()
    var imeWas by remember { mutableStateOf(false) }
    LaunchedEffect(imeOpen) {
        if (imeWas && !imeOpen && neue.touchFirst) h.focus?.clearFocus()
        imeWas = imeOpen
    }

    // The tablet's first run (touch swarm, rec 20): the three things a finger does to a
    // card, once, with the way to the rest.
    LaunchedEffect(neue.ready) {
        if (neue.ready && neue.touchFirst && !neue.prefs.touchIntroSeen) {
            neue.note = Note(com.kaiharimoto.mastertool.core.input.DeskWords.TOUCH_INTRO, "All gestures", lastsMs = 12_000) { neue.helpOpen = true }
            neue.update { it.copy(touchIntroSeen = true) }
        }
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
                    // Every finger of the gesture in hand: when each went down and came up, and how
                    // far the furthest travelled — two together are undo, three redo (rec 22).
                    val tapDowns = mutableMapOf<androidx.compose.ui.input.pointer.PointerId, Pair<Long, Offset>>()
                    val tapUps = mutableMapOf<androidx.compose.ui.input.pointer.PointerId, Long>()
                    var tapTravel = 0f
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
                        // Two fingers tapped together undo, three redo, anywhere in the window (rec 22,
                        // `MultiTap`): a pinch travels, so it is never one. Watched on the way down,
                        // and never consumed: a card under the first finger has already let it go.
                        // More than one finger down: whatever each is doing to a card, it is not a tap to read it.
                        if (event.changes.count { it.byFinger && it.pressed } > 1) neue.fingersAt = System.nanoTime() / 1_000_000
                        event.changes.filter { it.byFinger }.forEach { change ->
                            if (change.pressed && !change.previousPressed) {
                                if (tapDowns.isEmpty()) {
                                    tapUps.clear()
                                    tapTravel = 0f
                                }
                                tapDowns[change.id] = change.uptimeMillis to change.position
                                // A second finger in the same tap, whether or not one event ever held
                                // both pressed (the emulator's injected pairs did not, 1.0.32): no card's
                                // tap under it opens the viewer.
                                if (tapDowns.size > 1) neue.fingersAt = System.nanoTime() / 1_000_000
                            }
                            tapDowns[change.id]?.let { (_, from) -> tapTravel = maxOf(tapTravel, (change.position - from).getDistance()) }
                            if (!change.pressed && change.previousPressed && change.id in tapDowns) tapUps[change.id] = change.uptimeMillis
                        }
                        if (tapDowns.isNotEmpty() && tapUps.size == tapDowns.size) {
                            val gesture = com.kaiharimoto.mastertool.core.input.MultiTap.classify(
                                downs = tapDowns.values.map { it.first },
                                ups = tapDowns.keys.map { tapUps.getValue(it) },
                                travel = tapTravel,
                                slop = viewConfiguration.touchSlop,
                            )
                            // The last finger's own tap is released after this, in the card's pass, and
                            // schedules its open then: marked now, it is skipped when it comes due.
                            if (tapDowns.size > 1) neue.fingersAt = System.nanoTime() / 1_000_000
                            tapDowns.clear()
                            tapUps.clear()
                            com.kaiharimoto.mastertool.core.input.DeskTouch.window.firstOrNull { it.gesture == gesture }?.let { h.run(it.action) }
                        }
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
        // A phone (v1.3.5): the slim bar, and the pages as tabs along the bottom — or,
        // lying down, as a strip down the left, where the height is the deck's.
        val phone = neue.phone
        val phoneTall = phone && neue.posture.isTall
        Column(Modifier.fillMaxSize()) {
            if (!immersive) titleBar()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (phone && !phoneTall && !immersive) {
                    TabBar(neue, vertical = true, onSearch = { neue.paletteOpen = true })
                } else if (pinned && !phone) rail()
                Box(Modifier.weight(1f)) {
                    // Siding asked for from anywhere (the builder's web switch, a matchup, the editor's
                    // own deck menu) opens the Siding page (1.0.40).
                    LaunchedEffect(h.webs.sidingAsked) { if (h.webs.sidingAsked > 0) neue.go(Page.SIDING) }
                    // Another deck on the builder: the Siding page sides it, not the one asked for before (1.0.42).
                    LaunchedEffect(state.deckId) { if (h.webs.sidingDeckId != null && h.webs.sidingDeckId != state.deckId) h.webs.sidingDeckId = null }
                    Crossfade(neue.page, animationSpec = tween(MuMotion.PAGE, easing = MuMotion.ease), label = "page") { page ->
                        when (page) {
                            Page.DECKS -> DecksPage(h.deps, state, neue, h.decksReload, hidden = h.webs.library.deckIds)
                            Page.BUILDER -> BuilderPage(state, neue, h.drag, h::setSearchEffects)
                            Page.SIDING -> com.kaiharimoto.neue.pages.SidingPage(h.webs, state, neue, h.decksReload, onSave = { h.run(DeskAction.SAVE) })
                            Page.FORMAT -> com.kaiharimoto.neue.pages.FormatPage(h.deps, h.webs, state, neue, h.decksReload, onOpenDeck = h::openDeck)
                            Page.PREP -> com.kaiharimoto.neue.prep.PrepPage(h.prep, h.webs, state, neue, h.decksReload)
                            Page.PRESENT -> com.kaiharimoto.neue.present.PresentPage(h)
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
                                    ai = h.ai,
                                    sync = h.sync,
                                    backups = h.backups,
                                    onSetupAgain = { scope.launch { h.offerStart(again = true) } },
                                ),
                            )
                        }
                    }
                    Drawers(state, neue)
                }
                // Ai's panel (1.0.43): docked beside every page, the page re-fitting beside it —
                // in immersive mode too (1.0.46); on a phone it is a sheet.
                if (neue.aiDocked) {
                    com.kaiharimoto.neue.ai.AiPanel(h, Modifier.width((neue.prefs.ai.panelWidth / neue.prefs.scale).dp).fillMaxHeight())
                }
            }
            // Put away while the keyboard is up: the dock's field sits on the keyboard, not on the tabs.
            if (phoneTall && !immersive && !imeOpen) TabBar(neue)
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
        // A finger's card rides above the finger, where it can be seen, and lands where it
        // is drawn (touch swarm, rec 12: CarryOffset); a mouse's is centred on the pointer.
        val carry = rememberCarryMotion(h.drag)
        h.drag.held?.let { held ->
            val drawn = h.drag.drawn() ?: return@let
            Box(
                Modifier
                    .offset { IntOffset(drawn.left.toInt(), drawn.top.toInt()) }
                    .size(with(density) { drawn.width.toDp() }, with(density) { drawn.height.toDp() }),
            ) {
                NeueCard(
                    held.card,
                    Modifier.fillMaxSize(),
                    format = state.format,
                    foil = neue.prefs.foil,
                    outlined = true,
                    motion = { carry.pose(drawn.width) },
                )
                // A drop that would be refused says so on the card itself, where the eye is.
                if (h.drag.refused) {
                    com.kaiharimoto.neue.kit.Hatch(Modifier.matchParentSize(), color = c.ink25)
                    Box(Modifier.align(Alignment.Center).background(c.paper).padding(horizontal = 4.dp)) {
                        com.kaiharimoto.neue.kit.Micro("✕", color = c.ink)
                    }
                }
            }
        }

        // Ai on a phone: the whole screen, over the page and under its dialogs (1.0.43).
        if (neue.aiSheet) com.kaiharimoto.neue.ai.AiPanel(h, Modifier.fillMaxSize(), phone = true)
        // The setup offered on opening (1.0.69): under Ai's own setup, which its Ai step can open.
        if (neue.starting) com.kaiharimoto.neue.start.StartScreen(h, Modifier.fillMaxSize())
        // Ai's first setup takes the whole window, bars and all (1.0.45).
        if (neue.aiSetup) com.kaiharimoto.neue.ai.AiSetupScreen(h.ai, Modifier.fillMaxSize())
        // The reader's guide, read as a book over the whole window (1.0.67); the card viewer opens over it.
        neue.reading?.let { com.kaiharimoto.neue.ai.reader.BookReader(h, it, Modifier.fillMaxSize()) }
        if (neue.prefs.ai.enabled) {
            com.kaiharimoto.neue.ai.avatar.AiFaceClock(h.ai)
            com.kaiharimoto.neue.ai.MemoryDialog(h.ai)
            com.kaiharimoto.neue.ai.ReviewDialog(h.ai)
            com.kaiharimoto.neue.ai.TuneLauncher(h.ai)
            com.kaiharimoto.neue.ai.ProfileLauncher(h.ai)
            com.kaiharimoto.neue.ai.LivingDocDialog(h.ai)
            com.kaiharimoto.neue.ai.PictureDialog(h.ai)
            com.kaiharimoto.neue.ai.ContextPanel(h.ai)
            com.kaiharimoto.neue.ai.VoiceDialog(h.ai)
            com.kaiharimoto.neue.ai.QuickSettings(h.ai)
            if (h.ai.forgetAsked) {
                MuDialog(
                    title = "Forget everything",
                    onDismiss = { h.ai.forgetAsked = false },
                    width = 384.dp,
                    description = "${h.ai.name}'s memory, the skills it wrote and every conversation will be deleted. Its connections stay. This cannot be undone.",
                    footer = {
                        MuButton("Cancel", { h.ai.forgetAsked = false }, variant = BtnVariant.GHOST)
                        MuButton("Forget", {
                            h.ai.forgetAsked = false
                            h.ai.forgetEverything()
                            neue.note = Note("${h.ai.name} forgot everything")
                        }, variant = BtnVariant.PRIMARY)
                    },
                ) {}
            }
        }
        if (neue.helpOpen) HelpDialog { neue.helpOpen = false }
        neue.qr?.let { shown ->
            com.kaiharimoto.neue.qr.QrDialog(
                shown,
                onCopy = {
                    CardActions.copy(shown.ydke)
                    neue.note = com.kaiharimoto.neue.Note("YDKe code copied")
                },
                onDismiss = { neue.qr = null },
            )
        }
        neue.confirmRemoveArt?.let { (card, k) ->
            MuDialog(
                title = "Remove your picture",
                onDismiss = { neue.confirmRemoveArt = null },
                width = 384.dp,
                description = "Your picture for “${card.name}” will be deleted from this ${if (neue.touchFirst) "tablet" else "computer"}. This cannot be undone.",
                footer = {
                    MuButton("Cancel", { neue.confirmRemoveArt = null }, variant = BtnVariant.GHOST)
                    MuButton("Remove", {
                        neue.confirmRemoveArt = null
                        neue.chooseArt(card, card.id.value)
                        h.customArt.remove(card.id.value, k)
                    }, variant = BtnVariant.PRIMARY)
                },
            ) {}
        }
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
                            // Ai's notes on the deck go with it (1.0.43).
                            h.ai.files.delete(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(com.kaiharimoto.mastertool.core.ai.memory.MemoryKind.DECK, id))
                            h.ai.files.delete(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(com.kaiharimoto.mastertool.core.ai.memory.MemoryKind.GUIDE, id))
                            h.ai.files.delete(com.kaiharimoto.mastertool.core.ai.report.book.GuideBook.path(id))
                            h.ai.files.deleteReports(id)
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
        // Over the viewer it was opened from (v1.3.6).
        com.kaiharimoto.neue.builder.Showcase(state, neue)
        // Over the viewer and the pop-out, whose art row opens it (1.0.34).
        neue.cropping?.let { (card, picture) ->
            com.kaiharimoto.neue.art.ArtCropDialog(
                card = card,
                initial = picture,
                // The printing whose frame is kept: the artwork chosen, when the pool knows it.
                base = com.kaiharimoto.mastertool.core.model.CardArt.show(
                    card,
                    neue.prefs.arts[card.id.value]?.takeIf { it > 0 }?.let { com.kaiharimoto.mastertool.core.model.CardId(it) },
                ),
                custom = h.customArt,
                library = h.art,
                touch = neue.touchFirst,
                onChosen = { choice ->
                    neue.cropping = null
                    neue.chooseArt(card, choice)
                },
                onNote = { neue.note = Note(it) },
                onDismiss = { neue.cropping = null },
            )
        }
        // A presentation playing, over everything but its own menus (1.0.70).
        com.kaiharimoto.neue.present.PresentOverlay(h)
        MenuLayer(neue.menu) { neue.menu = null }
        OverlayLayer(h.overlays)

        Toasts(h, Modifier.align(Alignment.BottomEnd).imePadding().padding(end = 24.dp, bottom = 24.dp))

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
    // Ai open is someone at work (1.0.46): the deck does not float away from a conversation.
    val eligible = neue.immersive && neue.page == Page.BUILDER && h.builder.deck.totalCards > 0 && !neue.aiDocked
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
            LaunchedEffect(own.id) { delay(own.lastsMs); if (h.neue.note?.id == own.id) h.neue.note = null }
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
        // The notes scroll themselves, so the footer's Install is never scrolled away.
        scrolls = false,
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

/** An arrangement in the words its switch shows. */
internal fun arrangementWords(a: com.kaiharimoto.mastertool.core.layout.GroupArrangement): String = when (a) {
    com.kaiharimoto.mastertool.core.layout.GroupArrangement.AS_IS -> "As is"
    com.kaiharimoto.mastertool.core.layout.GroupArrangement.FITTED -> "Fitted"
    com.kaiharimoto.mastertool.core.layout.GroupArrangement.SEPARATE -> "Separate"
}
