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
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.search.CardFilter
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
    val gutter = if (neue.prefs.railPinned && !neue.immersive) 0.dp else RAIL_GUTTER
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
            }
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
            FilterPanel(state, Modifier.zenQuiet().padding(16.dp))
        }
        HRule(Modifier.zenQuiet(), color = c.ink)

        val grid = rememberLazyGridState()
        val cursor = neue.poolCursor.coerceIn(0, (state.results.size - 1).coerceAtLeast(0))
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
                    com.kaiharimoto.neue.kit.MuText("No matches.", style = MuType.h1(LocalMuFonts.current))
                    Small("Try fewer words, or turn off a filter.", color = c.ink70)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(state: DeckBuilderState, modifier: Modifier = Modifier) {
    val f = state.filter
    fun <T> Set<T>.toggle(item: T) = if (item in this) this - item else this + item

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FacetRow("Kind") {
            listOf(CardCategory.MONSTER to "Monster", CardCategory.SPELL to "Spell", CardCategory.TRAP to "Trap").forEach { (value, label) ->
                Tag(label, value in f.categories, { state.onFilterChange(f.copy(categories = f.categories.toggle(value))) })
            }
            Tag("Main deck", f.extraDeckOnly == false, { state.onFilterChange(f.copy(extraDeckOnly = if (f.extraDeckOnly == false) null else false)) })
            Tag("Extra deck", f.extraDeckOnly == true, { state.onFilterChange(f.copy(extraDeckOnly = if (f.extraDeckOnly == true) null else true)) })
        }
        FacetRow("Attribute") {
            Attribute.entries.filter { it != Attribute.UNKNOWN }.forEach { a ->
                Tag(a.name.lowercase().replaceFirstChar { it.uppercase() }, a in f.attributes, { state.onFilterChange(f.copy(attributes = f.attributes.toggle(a))) })
            }
        }
        FacetRow("Level or rank") {
            (1..12).forEach { level ->
                Tag(level.toString(), level in f.levels, { state.onFilterChange(f.copy(levels = f.levels.toggle(level))) })
            }
        }
        FacetRow("Banlist") {
            listOf(BanStatus.FORBIDDEN to "Forbidden", BanStatus.LIMITED to "Limited", BanStatus.SEMI_LIMITED to "Semi-limited").forEach { (s, label) ->
                Tag(label, s in f.banStatuses, { state.onFilterChange(f.copy(banStatuses = f.banStatuses.toggle(s))) })
            }
        }
        if (f.isActive) MicroLink("Clear filters", { state.onFilterChange(CardFilter(format = f.format)) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro(label, color = Mu.colors.ink45)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
