package com.kaiharimoto.neue

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalContextMenuRepresentation
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
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.configureImageLoader
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckLayoutState
import com.kaiharimoto.neue.builder.BuilderPage
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.builder.NeueDrag
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.MenuLayer
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuContextMenuRepresentation
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
) {
    private val held = mutableSetOf<androidx.compose.ui.input.key.Key>()
    var focus: FocusManager? = null
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
        val repeat = !held.add(event.key)
        val chord = DeskKeys.chord(event) ?: return false
        val context = DeskContext(
            textInputFocused = builder.textInputFocused || neue.searchFocused,
            searchFocused = neue.searchFocused,
            overlayOpen = neue.overlayOpen || builder.editingGoal != null || updates.dialogOpen,
            onBuilder = neue.page == Page.BUILDER,
        )
        val shortcut = DeskShortcuts.resolveShortcut(chord, context) ?: return false
        if (repeat && !shortcut.repeatable) return true
        run(shortcut.action)
        return true
    }

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
            DeskAction.EXPORT -> state.exportToFile()
            DeskAction.FOCUS_SEARCH -> neue.focusSearch()
            DeskAction.POOL_PREVIOUS -> neue.poolCursor = (neue.poolCursor - 1).coerceAtLeast(0)
            DeskAction.POOL_NEXT -> neue.poolCursor = (neue.poolCursor + 1).coerceAtMost((state.results.size - 1).coerceAtLeast(0))
            DeskAction.POOL_ADD, DeskAction.POOL_ADD_TO_SIDE -> state.results.getOrNull(neue.poolCursor)?.let { card ->
                CardActions.add(state, card, toSide = action == DeskAction.POOL_ADD_TO_SIDE)
            }
            DeskAction.REMOVE_SELECTED -> (neue.selection as? Selection.InDeck)?.let { sel ->
                state.removeAt(sel.card, sel.section, sel.index)
                // Stay on the same slot, so Delete held down clears a row.
                val ids = state.deck[sel.section]
                val next = sel.index.coerceAtMost(ids.size - 1)
                neue.selection = ids.getOrNull(next)?.let(state.index::byId)?.let { Selection.InDeck(it, sel.section, next) }
            }
            DeskAction.TOGGLE_INSPECTOR -> neue.update { it.copy(inspectorVisible = !it.inspectorVisible) }
            DeskAction.TOGGLE_POOL -> neue.update { it.copy(poolVisible = !it.poolVisible) }
            DeskAction.TOGGLE_FILTERS -> neue.update { it.copy(filtersOpen = !it.filtersOpen, poolVisible = true) }
            DeskAction.NEXT_LENS -> state.nextLens()
            DeskAction.PREVIOUS_LENS -> state.previousLens()
            DeskAction.NEW_GROUP -> state.startGroupDraft(seed = (neue.selection as? Selection.InDeck)?.card?.id)
            DeskAction.GROUPS -> neue.drawer = if (neue.drawer == Drawer.GROUPS) null else Drawer.GROUPS
            DeskAction.ISSUES -> neue.drawer = if (neue.drawer == Drawer.ISSUES) null else Drawer.ISSUES
            DeskAction.ZOOM_IN -> neue.update { it.zoomedIn() }
            DeskAction.ZOOM_OUT -> neue.update { it.zoomedOut() }
            DeskAction.ZOOM_RESET -> neue.update { it.copy(scale = 1f) }
            DeskAction.TOGGLE_THEME -> neue.toggleTheme()
        }
    }

    /** Esc unwinds one layer at a time, from the top: overlays, then modes, then focus, then selection. */
    private fun dismiss() {
        val state = builder
        when {
            updates.dialogOpen -> updates.dialogOpen = false
            neue.dismissTop() -> Unit
            state.editingGoal != null -> state.cancelGoal()
            state.groupDraft != null -> state.cancelGroupDraft()
            state.textInputFocused || neue.searchFocused -> focus?.clearFocus()
            state.isolatedKey != null -> state.isolatedKey?.let(state::toggleIsolation)
            neue.selection != null -> neue.selection = null
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
            cmd("Deck", "Undo", DeskAction.UNDO),
            cmd("Deck", "Redo", DeskAction.REDO),
            cmd("Deck", "Issues", DeskAction.ISSUES),
            cmd("Deck", "Groups", DeskAction.GROUPS),
            cmd("Deck", "New group", DeskAction.NEW_GROUP),
            cmd("Deck", "Next lens", DeskAction.NEXT_LENS),
            Command("Deck", "Format: ${if (builder.format == Format.TCG) "switch to OCG" else "switch to TCG"}") {
                setFormat(if (builder.format == Format.TCG) Format.OCG else Format.TCG)
            },
            cmd("App", if (neue.prefs.theme == NeueTheme.PAPER) "Switch to ink (dark)" else "Switch to paper (light)", DeskAction.TOGGLE_THEME),
            cmd("App", "Show or hide the pool", DeskAction.TOGGLE_POOL),
            cmd("App", "Show or hide the inspector", DeskAction.TOGGLE_INSPECTOR),
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
        NeueHolders(
            deps = deps,
            builder = builder,
            layout = DeckLayoutState(deps.preferencesRepository, scope),
            neue = NeueState(deps.preferencesRepository, scope),
            drag = NeueDrag(builder),
            updates = makeUpdates(scope),
        )
    }
}

/**
 * The window's content: title bar, rail, the page, and every layer above it —
 * drawers, dialogs, the palette, the menu, the card in the air, the toast.
 */
@Composable
fun NeueRoot(h: NeueHolders, launchEffects: Boolean = true) {
    val neue = h.neue
    val state = h.builder
    val focus = LocalFocusManager.current
    h.focus = focus

    if (launchEffects) {
        DisposableEffect(Unit) {
            configureImageLoader(java.io.File(Platform.dataDir, "card-art").absolutePath)
            h.layout.start { prefs ->
                state.onFormatChange(prefs.format)
                state.onSearchEffectsChange(prefs.searchEffects)
            }
            neue.start()
            state.start()
            h.updates.check(userInitiated = false)
            onDispose {
                h.layout.flush()
                neue.flush()
            }
        }
    }

    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * neue.prefs.scale, base.fontScale)) {
        MuTheme(ink = neue.prefs.theme == NeueTheme.INK) {
            CompositionLocalProvider(LocalContextMenuRepresentation provides remember { MuContextMenuRepresentation() }) {
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

    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize()) {
            val status = when {
                state.isSyncing -> ShellStatus("Syncing card pool", running = true)
                state.index.size == 0 -> ShellStatus("No card pool", running = false)
                else -> ShellStatus("${"%,d".format(state.index.size)} cards · ${state.format.name}", running = false)
            }
            TitleBar(
                neue = neue,
                status = status,
                update = h.updates.available?.versionName,
                onUpdate = { h.updates.dialogOpen = true },
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Rail(
                    neue = neue,
                    version = Platform.version,
                    counts = mapOf(Page.BUILDER to state.deck.main.size.toString()),
                )
                Box(Modifier.weight(1f)) {
                    Crossfade(neue.page, animationSpec = tween(MuMotion.PAGE, easing = MuMotion.ease), label = "page") { page ->
                        when (page) {
                            Page.DECKS -> DecksPage(h.deps, state, neue, h.decksReload)
                            Page.BUILDER -> BuilderPage(state, neue, h.drag, h::setFormat, h::setSearchEffects)
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
                                ),
                            )
                        }
                    }
                    Drawers(state, neue)
                }
            }
        }

        // The card in the air: drawn where the pointer is, at the size it was, no shadow and no lift (§7).
        h.drag.held?.let { held ->
            val density = LocalDensity.current
            Box(
                Modifier
                    .offset { IntOffset((h.drag.pointer.x - held.size.width / 2f).toInt(), (h.drag.pointer.y - held.size.height / 2f).toInt()) }
                    .size(with(density) { held.size.width.toDp() }, with(density) { held.size.height.toDp() }),
            ) {
                NeueCard(held.card, Modifier.fillMaxSize(), format = state.format, foil = neue.prefs.foil, outlined = true)
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
                            if (state.deckId == id) state.newDeck()
                            h.decksReload++
                        }
                    }, variant = BtnVariant.PRIMARY)
                },
            ) {}
        }
        if (h.updates.dialogOpen) UpdateDialog(h.updates)
        if (neue.paletteOpen) CommandPalette(h::commands) { neue.paletteOpen = false }
        MenuLayer(neue.menu) { neue.menu = null }

        Toasts(h, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 88.dp))
    }
}

@Composable
private fun Toasts(h: NeueHolders, modifier: Modifier) {
    val toast = h.builder.toast
    val note = h.updates.message
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (note != null) {
            LaunchedEffect(note) { delay(4000); h.updates.message = null }
            ToastBox(note, null, {})
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
