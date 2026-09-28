package com.kaiharimoto.neue.builder

import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.mastertool.core.input.CursorMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.Studio
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.mastertool.core.input.MouseAction
import com.kaiharimoto.mastertool.core.input.MouseTarget
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Selection
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.zen.zenDeep
import com.kaiharimoto.neue.zen.zenQuiet
import com.kaiharimoto.neue.kit.Hatch
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * The card pool: search at the top, filters under it when asked for, results
 * below as a ruled grid of cards.
 *
 * The keyboard lives here too. With the caret in the search field ↑ and ↓ walk
 * the results and Enter adds the highlighted card — type a name, press Enter,
 * never touch the mouse. That is [NeueState.poolCursor].
 */
@Composable
fun PoolPane(
    state: DeckBuilderState,
    neue: NeueState,
    drag: NeueDrag,
    onSearchEffects: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Mu.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(neue.focusSearchTick) {
        if (neue.focusSearchTick > 0) runCatching { focus.requestFocus() }
    }

    // Zen: the search and its controls fade with the chrome; the pool, cards and all, goes at ten seconds.
    val gutter = if (neue.railPinned && !neue.immersive) 0.dp else RAIL_GUTTER
    Column(
        modifier
            .zenDeep()
            .onGloballyPositioned { drag.registerPool(it.boundsInWindow()) }
            // aria-busy: the pool is the region that is working while the card pool syncs.
            .let { if (state.isSyncing) it.cursor(CursorMode.BUSY) else it }
            .padding(start = gutter),
    ) {
        Column(Modifier.zenQuiet().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MuInput(
                    value = state.query,
                    onValueChange = {
                        state.onQueryChange(it)
                        neue.poolCursor = 0
                    },
                    placeholder = if (state.index.size > 0) "Search ${"%,d".format(state.index.size)} cards" else "Search cards",
                    focusRequester = focus,
                    onFocusChange = {
                        neue.searchFocused = it
                        state.onTextFieldFocusChanged(it)
                    },
                    modifier = Modifier.weight(1f),
                )
                Kbd("/")
                // The search pop-out (1.0.19): the window given over to finding cards.
                Tip("Advanced search: every filter, and the card read large", kbd = DeskShortcuts.chordFor(DeskAction.ADVANCED_SEARCH)?.let(DeskShortcuts::kbd)) {
                    IconButton(Icons.Search, { neue.studio = Studio() }, label = "Advanced search")
                }
                // Hidden from where it stands (kai, 1.0.19), rather than from the window's bar.
                Tip("Hide the pool", kbd = DeskShortcuts.chordFor(DeskAction.TOGGLE_POOL)?.let(DeskShortcuts::kbd)) {
                    IconButton(Icons.PanelLeftClose, { neue.update { it.copy(poolVisible = false) } }, label = "Hide pool")
                }
            }
            ListsRow(neue)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val shown = state.results.size
                val meta = buildString {
                    append(if (shown < state.matchCount) "$shown of ${"%,d".format(state.matchCount)}" else "%,d".format(state.matchCount))
                    if (state.searchEffects && state.effectMatchCount > 0) append(" · ${state.effectMatchCount} by text")
                }
                // The pool's size lives here since the title bar gave it up, and so does its sync.
                if (state.isSyncing) com.kaiharimoto.neue.kit.Breathe(running = true)
                Mono(meta, Modifier.weight(1f), color = c.ink70)
                Tip("Also match the words printed on the card. Prefix name: or text: to choose one") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Micro("Text", color = c.ink45)
                        MuSwitch(state.searchEffects, onSearchEffects)
                    }
                }
                // kai, 1.0.14: a pool that adds to the side deck, for siding a whole list in.
                Tip(if (neue.prefs.poolToSide) "Adding to the side deck. Shift adds to the main" else "Add to the side deck with right-click and Enter. Shift adds to the main") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Micro("Side", color = if (neue.prefs.poolToSide) c.ink else c.ink45)
                        MuSwitch(neue.prefs.poolToSide, { on -> neue.update { it.copy(poolToSide = on) } })
                    }
                }
                val facets = state.filter.activeFacetCount
                MicroLink(
                    if (facets > 0) "Filters ($facets)" else "Filters",
                    { neue.update { it.copy(filtersOpen = !it.filtersOpen) } },
                    color = if (neue.prefs.filtersOpen || facets > 0) c.ink else c.ink45,
                )
            }
        }
        if (neue.prefs.filtersOpen) {
            HRule(Modifier.zenQuiet())
            // Every facet is a long panel: it scrolls within the upper part of the pool,
            // and the results keep the rest.
            val scroll = rememberScrollState()
            Box(Modifier.zenQuiet().fillMaxWidth().heightIn(max = 380.dp)) {
                FilterPanel(state.filter, state::onFilterChange, state.index, Modifier.verticalScroll(scroll).padding(16.dp))
                ScrollbarFor(scroll)
            }
        }
        HRule(Modifier.zenQuiet(), color = c.ink)

        val grid = rememberLazyGridState()
        val cursor = neue.poolCursor.coerceIn(0, (state.results.size - 1).coerceAtLeast(0))
        // How many cards a row holds as drawn, for the arrow keys; and a selection the
        // arrows moved off the visible rows is scrolled back into view.
        LaunchedEffect(grid) {
            snapshotFlow { grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } }
                .collect { max -> if (max != null) neue.poolColumns = max + 1 }
        }
        val selectedRow = (neue.selection as? Selection.InPool)?.row
        LaunchedEffect(selectedRow) {
            val row = selectedRow ?: return@LaunchedEffect
            val visible = grid.layoutInfo.visibleItemsInfo
            if (visible.isNotEmpty() && (row < visible.first().index || row > visible.last().index)) grid.scrollToItem(row)
        }
        LaunchedEffect(cursor, state.results) {
            val visible = grid.layoutInfo.visibleItemsInfo
            if (visible.isNotEmpty() && (cursor < visible.first().index || cursor > visible.last().index)) {
                grid.scrollToItem(cursor)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.index.size == 0 -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Hatch(Modifier.fillMaxWidth().aspectRatio(3f), live = state.isSyncing, color = c.ink25)
                    Small(if (state.isSyncing) "Fetching the card pool" else state.syncMessage ?: "No card pool yet", color = c.ink70)
                }
                state.results.isEmpty() -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val list = neue.list(neue.prefs.poolList)
                    if (list != null && list.ids.isEmpty()) {
                        com.kaiharimoto.neue.kit.MuText("Nothing on it yet.", style = MuType.h1(LocalMuFonts.current))
                        Small("Press L on any card, or use its menu, to put it on ${list.name}. Or search for cards to add.", color = c.ink70)
                        MicroLink("Add cards", { neue.studio = Studio(list.id) }, color = c.ink)
                    } else {
                        com.kaiharimoto.neue.kit.MuText("No matches.", style = MuType.h1(LocalMuFonts.current))
                        Small("Try fewer words, or turn off a filter.", color = c.ink70)
                    }
                }
                else -> {
                    val columns = neue.prefs.poolColumns
                    val deckWidth = neue.deckCardWidth
                    LazyVerticalGrid(
                        columns = if (columns > 0) GridCells.Fixed(columns) else DeckSized(if (deckWidth.isSpecified) deckWidth else 96.dp),
                        state = grid,
                        modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 12.dp, bottom = 16.dp, end = 4.dp),
                    ) {
                        itemsIndexed(state.results, key = { _, card -> card.id.value }) { i, card ->
                            val left = state.remaining(card)
                            val selected = (neue.selection as? Selection.InPool)?.card?.id == card.id ||
                                (neue.searchFocused && i == cursor)
                            val held = drag.held?.let { it.from == null && it.card.id == card.id } == true
                            val press = rememberPress()
                            NeueCard(
                                card = card,
                                modifier = Modifier
                                    .aspectRatio(CARD_RATIO)
                                    .cardPointer(
                                        card = card,
                                        neue = neue,
                                        drag = drag,
                                        from = null,
                                        index = i,
                                        target = MouseTarget.POOL,
                                        press = press,
                                        onAction = { action, at ->
                                            if (action == MouseAction.SELECT) neue.poolCursor = i
                                            CardActions.onPool(action, at, card, i, state, neue)
                                        },
                                        dragEnabled = left > 0,
                                    ),
                                motion = press::pose,
                                artChip = true,
                                format = state.format,
                                copies = state.copiesInDeck(card.id),
                                selected = selected,
                                dimmed = left <= 0 || held,
                                foil = neue.prefs.foil,
                            )
                        }
                    }
                    Box(Modifier.matchParentSize().zenQuiet()) { ScrollbarFor(grid) }
                }
            }
        }
    }
}

/** Empty paper between the window's edge and the pool, while the rail comes out at that edge. */
private val RAIL_GUTTER = 32.dp

/**
 * As many columns as cards of about [target] fill, rounded to the nearest:
 * a pool read at the deck's own scale. `Adaptive` would only ever round the
 * count down, and so draw every card larger than asked.
 */
private class DeckSized(private val target: Dp) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val want = target.roundToPx().coerceAtLeast(1)
        val count = kotlin.math.round((availableSize + spacing).toFloat() / (want + spacing)).toInt().coerceIn(2, 12)
        val cell = (availableSize - spacing * (count - 1)) / count
        val extra = (availableSize - spacing * (count - 1)) % count
        return List(count) { if (it < extra) cell + 1 else cell }
    }

    override fun equals(other: Any?) = other is DeckSized && other.target == target
    override fun hashCode() = target.hashCode()
}

/**
 * What the pool is drawn from (kai, 1.0.19: "a custom list of cards … for
 * consideration and toggle the custom list"): every card, or one of the lists.
 * Each list is a tag with its count; a click shows it, a right-click edits or
 * deletes it, and **+ List** starts one. **Add cards** opens the search pop-out
 * adding to the list showing.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ListsRow(neue: NeueState) {
    val c = Mu.colors
    val lists = neue.prefs.cardLists
    val showing = neue.list(neue.prefs.poolList)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Tag("All cards", showing == null, { neue.showList(null) }, Modifier.height(24.dp), caption = "Every card")
        lists.forEach { list ->
            var at by remember { mutableStateOf(Offset.Zero) }
            Tip("Right-click to edit, rename or delete. L puts the card you are reading on it") {
                Tag(
                    list.name,
                    showing?.id == list.id,
                    { neue.showList(if (showing?.id == list.id) null else list.id) },
                    Modifier
                        .height(24.dp)
                        .onGloballyPositioned { at = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }
                        // Right-click, or a finger held on the tag (1.3.0).
                        .onContextMenu {
                            run {
                                neue.menu = MenuSpec(
                                    at,
                                    listOf(
                                        MenuEntry("Add cards…") { neue.studio = Studio(list.id) },
                                        MenuEntry(if (showing?.id == list.id) "Show every card" else "Show in the pool", hint = "Shift L") { neue.showList(if (showing?.id == list.id) null else list.id) },
                                        MenuEntry("Delete “${list.name}”", danger = true, separatorBefore = true) { neue.deleteList(list.id) },
                                    ),
                                )
                            }
                        },
                    count = "${list.ids.size}",
                    caption = if (showing?.id == list.id) "Every card" else "Show",
                )
            }
        }
        MicroLink("+ List", { neue.showList(neue.newList()) }, Modifier.padding(horizontal = 6.dp), color = c.ink45)
        if (showing != null) MicroLink("Add cards →", { neue.studio = Studio(showing.id) }, Modifier.padding(start = 6.dp), color = c.ink)
    }
}
