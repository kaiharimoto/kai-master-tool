package com.kaiharimoto.mastertool.ui.deckbuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.DeckGrouping
import com.kaiharimoto.mastertool.core.input.ShortcutAction
import com.kaiharimoto.mastertool.core.input.ShortcutContext
import com.kaiharimoto.mastertool.core.layout.Posture
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.components.CardInspector
import com.kaiharimoto.mastertool.ui.egg.EasterEgg
import com.kaiharimoto.mastertool.ui.dnd.DragController
import com.kaiharimoto.mastertool.ui.dnd.DragOverlay
import com.kaiharimoto.mastertool.ui.dnd.DragSession
import com.kaiharimoto.mastertool.ui.dnd.DropHover
import com.kaiharimoto.mastertool.core.input.ShortcutLayer
import com.kaiharimoto.mastertool.ui.input.ShortcutHelpSheet
import com.kaiharimoto.mastertool.ui.input.ShortcutHost
import com.kaiharimoto.mastertool.ui.inspect.CardInspect3D
import com.kaiharimoto.mastertool.ui.input.ShortcutRelay
import com.kaiharimoto.mastertool.ui.update.UpdateState

/**
 * The deck builder, in whichever of its two arrangements the window is for.
 *
 * **Wide** is the tablet builder and is unchanged: search on the left under the
 * thumb of whichever hand is holding the tablet, and the three deck sections
 * stacked down the right where they can all be seen at once — the thing the
 * desktop tool's collapsible panes were working around on a small screen. Every
 * boundary there is draggable and remembered, so the split is whatever the deck
 * being built needs it to be.
 *
 * **Tall** is the phone builder — see [TallBuilderBody]. A portrait window has
 * no width to divide, so the pool docks along the bottom where the thumbs are
 * and the deck takes what is left above it.
 *
 * Everything *around* the two — the drag overlay, every sheet, the shortcut
 * host, the snackbar and what Escape closes — is shared, because none of it is
 * about the shape of the window. Only the body differs, which is the whole
 * seam: two arrangements of one builder rather than two builders.
 */
@Composable
fun DeckBuilderScreen(
    state: DeckBuilderState,
    layout: DeckLayoutState,
    updateState: UpdateState,
    onOpenLibrary: () -> Unit,
    onOpenPlay: () -> Unit = {},
) {
    val snackbarHost = remember { SnackbarHostState() }
    val density = LocalDensity.current
    val drag = remember { DragController(canDrop = state::canDrop) }

    // The resolver works in pixels; these are the only two numbers in it that
    // are really about how big a finger is.
    with(density) {
        drag.rowTolerancePx = 8.dp.toPx()
        drag.hysteresisPx = 10.dp.toPx()
    }

    LaunchedEffect(state.toast?.id) {
        val toast = state.toast ?: return@LaunchedEffect
        try {
            val result = snackbarHost.showSnackbar(
                message = toast.message,
                actionLabel = if (toast.undo != null) "Undo" else null,
                withDismissAction = toast.undo == null,
            )
            if (result == SnackbarResult.ActionPerformed) toast.undo?.invoke()
        } finally {
            // Consumed even when this effect is cancelled mid-show — the state
            // holder outlives the screen, and an unconsumed toast replayed
            // itself every time the builder came back from the library.
            state.consumeToast()
        }
    }

    LaunchedEffect(updateState.message) {
        val text = updateState.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(text, withDismissAction = true)
        updateState.consumeMessage()
    }

    val onDropped: (DragSession, DropHover?) -> Unit = { session, landed ->
        applyDrop(state, session, landed, stacked = layout.preferences.stacked)
    }

    val searchFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // At most one thing covers the builder; resolution needs to know which,
    // because a toggle stays live while its own panel is the one on top. The
    // order here matches dismissTopLayer, topmost first.
    val topLayer = when {
        state.eggVisible -> ShortcutLayer.EGG
        state.inspection != null -> ShortcutLayer.INSPECTOR
        state.helpVisible -> ShortcutLayer.HELP
        state.groupManagerVisible -> ShortcutLayer.GROUPS
        state.editingGoal != null -> ShortcutLayer.CONSISTENCY
        state.filtersVisible -> ShortcutLayer.FILTERS
        state.statsVisible -> ShortcutLayer.STATS
        state.issuesVisible -> ShortcutLayer.ISSUES
        else -> ShortcutLayer.BUILDER
    }

    val shortcutContext = ShortcutContext(
        textInputFocused = state.textInputFocused,
        topLayer = topLayer,
    )
    val onShortcut: (ShortcutAction) -> Unit = { action ->
        when (action) {
            ShortcutAction.SAVE -> state.save()
            ShortcutAction.UNDO -> state.undo()
            ShortcutAction.REDO -> state.redo()
            ShortcutAction.FOCUS_SEARCH -> searchFocus.requestFocus()
            ShortcutAction.TOGGLE_FILTERS -> state.filtersVisible = !state.filtersVisible
            ShortcutAction.TOGGLE_STATS -> state.statsVisible = !state.statsVisible
            ShortcutAction.TOGGLE_ISSUES -> state.issuesVisible = !state.issuesVisible
            ShortcutAction.TOGGLE_HELP -> state.helpVisible = !state.helpVisible
            ShortcutAction.NEXT_LENS -> state.nextLens()
            ShortcutAction.PREVIOUS_LENS -> state.previousLens()
            ShortcutAction.TOGGLE_GROUP_MANAGER ->
                state.groupManagerVisible = !state.groupManagerVisible
            ShortcutAction.NEW_GROUP -> state.startGroupDraft()
            ShortcutAction.TOGGLE_SEARCH_PANE -> layout.toggleSearchPane()
            ShortcutAction.TOGGLE_CONSISTENCY ->
                if (state.editingGoal != null) state.cancelGoal() else state.openGoal()
            ShortcutAction.DISMISS -> dismissTopLayer(state) {
                focusManager.clearFocus()
                // Cleared focus is nobody's focus, and key events only arrive
                // somewhere focused — without this, the Escape that cleared the
                // search box also switched the keyboard off for good.
                hostFocus.requestFocus()
            }
            ShortcutAction.FOCUS_MAIN -> layout.focusSection(DeckSection.MAIN)
            ShortcutAction.FOCUS_EXTRA -> layout.focusSection(DeckSection.EXTRA)
            ShortcutAction.FOCUS_SIDE -> layout.focusSection(DeckSection.SIDE)
            ShortcutAction.PREVIOUS_CARD -> state.pageInspection(-1)
            ShortcutAction.NEXT_CARD -> state.pageInspection(1)

            // The play stage's own, which cannot reach this handler because
            // their scope is never live while the builder is. Listed rather
            // than swept up by an `else`, so that adding an action still has to
            // be a decision here — which is the whole value of the exhaustive
            // check, and the reason this file is where it was caught.
            ShortcutAction.PLAY_DRAW,
            ShortcutAction.PLAY_SHUFFLE,
            ShortcutAction.PLAY_SEARCH_DECK,
            ShortcutAction.PLAY_NEW_HAND,
            ShortcutAction.PLAY_NEXT_PHASE,
            ShortcutAction.PLAY_END_TURN,
            ShortcutAction.PLAY_SCENE,
            ShortcutAction.PLAY_CAMERA_TOUCH,
            ShortcutAction.PLAY_SEAT_OVERHEAD,
            ShortcutAction.PLAY_SEAT_TABLE,
            ShortcutAction.PLAY_SEAT_SEATED,
            ShortcutAction.PLAY_SEAT_POV,
            ShortcutAction.PLAY_HEAD_SWAY,
            ShortcutAction.PLAY_GUIDE,
            -> Unit
        }
    }
    // Sheets compose into their own focus boundary, so each stands up its own
    // host inside it — same context, same handler, same table.
    val shortcutRelay = ShortcutRelay(shortcutContext, onShortcut)

    // A closing sheet takes its focus with it. Reclaim the keyboard for the
    // builder — unless the user is typing, in which case the field keeps it.
    LaunchedEffect(topLayer) {
        if (topLayer == ShortcutLayer.BUILDER && !state.textInputFocused) {
            hostFocus.requestFocus()
        }
    }

    ShortcutHost(
        context = shortcutContext,
        onAction = onShortcut,
        focusRequester = hostFocus,
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            // The dragged card is composed here, outside every pane, so it is not
            // clipped by the grid it was lifted out of.
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val posture = Posture.of(maxWidth.value, maxHeight.value)

                if (posture.isTall) {
                    TallBuilderBody(
                        state = state,
                        layout = layout,
                        updateState = updateState,
                        drag = drag,
                        searchFocus = searchFocus,
                        onDropped = onDropped,
                        onOpenLibrary = onOpenLibrary,
                        onOpenPlay = onOpenPlay,
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        DeckBuilderTopBar(
                            state,
                            layout,
                            updateState,
                            onOpenLibrary,
                            onOpenPlay,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                        Row(
                            Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { layout.builderWidthPx = it.size.width.toFloat() },
                        ) {
                            // The pool is where cards come from; once they are in,
                            // it is the thing between you and the list. Switching it
                            // off hands its width to the deck, and the deck is
                            // re-fitted into it on the same frame.
                            if (layout.preferences.searchVisible) {
                                SearchPane(
                                    state = state,
                                    layout = layout,
                                    drag = drag,
                                    searchFocus = searchFocus,
                                    onDropped = onDropped,
                                    modifier = Modifier
                                        .weight(layout.preferences.searchWeight)
                                        .fillMaxHeight(),
                                )

                                SearchDeckDivider(onDrag = layout::resizeSearchPane)
                            }

                            DeckPanes(
                                state = state,
                                layout = layout,
                                drag = drag,
                                onDropped = onDropped,
                                modifier = Modifier
                                    .weight(
                                        if (layout.preferences.searchVisible) {
                                            1f - layout.preferences.searchWeight
                                        } else {
                                            1f
                                        }
                                    )
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }

                DragOverlay(drag, state.format)
            }
        }
    } // ShortcutHost

    state.inspection?.let { inspection ->
        CardInspector(
            cards = inspection.cards,
            initialIndex = inspection.index,
            format = state.format,
            copiesBySection = { card ->
                DeckSection.entries.associateWith { state.copiesIn(card.id, it) }
            },
            mainDeckSize = state.deck.main.size,
            openingHandOdds = { copies, handSize ->
                state.mainStatistics.openingHandOdds(copies, handSize)
            },
            onDismiss = { state.inspection = null },
            onPageChanged = state::onInspectionPageChanged,
            onSetCount = { card, section, count -> state.setCount(card, section, count) },
            onMove = { card, from, to -> state.moveCard(card, from, to) },
            onBrowse = { filter ->
                state.onQueryChange("")
                state.onFilterChange(filter)
                state.inspection = null
            },
            onHold = { card -> state.heldCard = card },
            shortcuts = shortcutRelay,
        )
    }

    // Above everything, including open sheets: a Dialog stacks over them.
    state.heldCard?.let { held ->
        CardInspect3D(card = held, onDismiss = { state.heldCard = null })
    }

    if (state.filtersVisible) {
        FilterSheet(
            index = state.index,
            filter = state.filter,
            onChange = state::onFilterChange,
            onClear = state::clearFilters,
            onDismiss = { state.filtersVisible = false },
            onTextInputFocusChanged = state::onTextFieldFocusChanged,
            shortcuts = shortcutRelay,
        )
    }

    if (state.statsVisible) {
        DeckStatsPanel(
            statistics = state.statistics,
            section = state.statsSection,
            onSectionChange = { state.statsSection = it },
            onDismiss = { state.statsVisible = false },
            shortcuts = shortcutRelay,
        )
    }

    if (state.issuesVisible) {
        IssuesPanel(
            validation = state.validation,
            onReveal = { issue ->
                val id = issue.cardId ?: return@IssuesPanel
                val section = issue.section ?: state.sectionsHolding(id).firstOrNull()
                if (section != null) {
                    state.reveal(section, id)
                    state.issuesVisible = false
                }
            },
            onDismiss = { state.issuesVisible = false },
            shortcuts = shortcutRelay,
        )
    }

    if (state.helpVisible) {
        ShortcutHelpSheet(
            onDismiss = { state.helpVisible = false },
            shortcuts = shortcutRelay,
        )
    }

    if (state.groupManagerVisible) {
        GroupManagerSheet(
            state = state,
            onDismiss = { state.groupManagerVisible = false },
            shortcuts = shortcutRelay,
        )
    }

    GoalSheet(state = state, shortcuts = shortcutRelay)

    if (state.eggVisible) {
        val pinned = layout.preferences.easterEggPool

        // A pinned set if one was kept, otherwise whatever is in the deck — which
        // is the version of this that needs no curating and is never empty when
        // there is anything to throw.
        val pool = remember(pinned, state.deck, state.index) {
            pinned.mapNotNull { state.index.byId(CardId(it)) }
                .ifEmpty {
                    (state.deck.main + state.deck.extra + state.deck.side)
                        .distinct()
                        .mapNotNull(state.index::byId)
                }
        }

        EasterEgg(
            pool = pool,
            pinned = pinned.isNotEmpty(),
            onPin = {
                layout.update { preferences ->
                    preferences.copy(
                        easterEggPool = if (pinned.isNotEmpty()) {
                            emptyList()
                        } else {
                            pool.map { it.id.value }
                        },
                    )
                }
            },
            onDismiss = { state.eggVisible = false },
        )
    }
}

/**
 * What Escape closes, in order.
 *
 * One ordered list rather than a handler per surface. The tool this replaces had
 * Escape implemented in four separate places that did not agree about which one
 * won, which is the sort of thing that is invisible until the one time it closes
 * the wrong thing.
 */
private fun dismissTopLayer(state: DeckBuilderState, clearFocus: () -> Unit) {
    when {
        // Topmost first. The egg covers everything, so it leaves first too.
        state.eggVisible -> state.eggVisible = false
        state.inspection != null -> state.inspection = null
        state.helpVisible -> state.helpVisible = false
        state.groupManagerVisible -> state.groupManagerVisible = false
        state.editingGoal != null -> state.cancelGoal()
        state.filtersVisible -> state.filtersVisible = false
        state.statsVisible -> state.statsVisible = false
        state.issuesVisible -> state.issuesVisible = false
        // Before the search box, because a draft is a mode: while one is open
        // it is what Escape obviously means.
        state.groupDraft != null -> state.cancelGroupDraft()
        state.query.isNotEmpty() -> state.onQueryChange("")
        else -> clearFocus()
    }
}

/**
 * Turns a completed drag into a deck edit.
 *
 * Three outcomes, and every one of them routes through the same editor that tap
 * and the stepper use — so a drop is undoable, and a drop the rules refuse says
 * so in the same words as any other rejected edit.
 */
private fun applyDrop(
    state: DeckBuilderState,
    session: DragSession,
    landed: DropHover?,
    stacked: Boolean,
) {
    if (landed == null || !landed.accepted) return

    val target = landed.section

    // The lens needs no special case: it never moves a card, so a grid index is
    // a deck position with it on exactly as with it off, and a drop means what
    // it has always meant.
    when {
        // Dropped back on the pool: the copy leaves the deck.
        target == null -> session.section?.let { from ->
            state.removeAt(session.card, from, session.index)
        }

        session.section == null -> state.addCardAt(
            session.card,
            target,
            // The resolver reports a grid index. In the stacked view the grid
            // shows one tile per distinct card, so that index counts stacks and
            // has to be mapped back onto the list the deck actually stores —
            // fed in raw, it would land the card inside another card's copies.
            if (stacked) stackIndexToListIndex(state.deck[target], landed.index) else landed.index,
        )

        // `DragSession` lives in `:builder` now, so the compiler will not smart-cast
        // across the module boundary; the branch above has already ruled out null.
        else -> state.moveCardTo(
            card = session.card,
            from = session.section!!,
            fromIndex = session.index,
            to = target,
            insertBefore = if (stacked) {
                stackIndexToListIndex(state.deck[target], landed.index)
            } else {
                landed.index
            },
        )
    }
}

/** "Before the Nth stack" as a position in the flat list the deck stores. */
private fun stackIndexToListIndex(ids: List<CardId>, stackIndex: Int): Int {
    val stacks = DeckGrouping.stacks(ids)
    return if (stackIndex >= stacks.size) ids.size else stacks[stackIndex].firstIndex
}

@Composable
private fun SearchDeckDivider(onDrag: (Float) -> Unit) {
    val draggableState = rememberDraggableState(onDelta = onDrag)

    Box(
        Modifier
            .width(10.dp)
            .fillMaxHeight()
            .draggable(draggableState, Orientation.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}
