package com.kaiharimoto.neue.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import com.kaiharimoto.neue.kit.onPointer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.search.CardFilter
import com.kaiharimoto.mastertool.core.search.EffectKinds
import com.kaiharimoto.mastertool.core.search.SearchOutcome
import com.kaiharimoto.mastertool.core.search.SearchScope
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The search pop-out (kai, 1.0.19: "an advanced card search pop out that focuses
 * the screen on searching with a dedicated layout. it should incorporate elements
 * from the inspector as well"). The window given over to one question — which
 * card? — in three columns:
 *
 * - **the filters**, every one of them, always open (`FilterPanel`, the pool's own);
 * - **the results**, many to a row and a thousand deep, rather than the pool's 150;
 * - **the card**, read large: the inspector's picture, artwork switch, heading,
 *   text, what it is filed under (a click filters by it, here), what it does
 *   (`EffectKinds`), the copies in the deck — and the actions.
 *
 * It keeps its own query and filter, starting from the pool's, so a search here
 * does not disturb the pool. It adds to the deck, or — opened on a list, which is
 * how kai asked for cards to get onto one ("a separate pop out that lets you
 * search and add cards to the list") — to that list: a double-click is the add,
 * a right-click the card's whole menu. Esc or Done closes it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchStudio(state: DeckBuilderState, neue: NeueState) {
    val studio = neue.studio ?: return
    val c = Mu.colors
    val list = neue.list(studio.listId)
    val memory = neue.studioMemory
    var query by remember { mutableStateOf(memory?.query ?: state.query) }
    var filter by remember { mutableStateOf(memory?.filter ?: state.filter.copy(onlyIds = null)) }
    var onlyList by remember { mutableStateOf(memory?.onlyList ?: false) }
    LaunchedEffect(query, filter, onlyList) { neue.studioMemory = com.kaiharimoto.neue.StudioMemory(query, filter, onlyList) }
    var outcome by remember { mutableStateOf(SearchOutcome.EMPTY) }
    val listIds = list?.ids
    LaunchedEffect(query, filter, onlyList, listIds, state.index, state.format, state.searchEffects) {
        delay(90)
        val f = filter.copy(format = state.format, onlyIds = if (onlyList && listIds != null) listIds.toSet() else null)
        val scope = if (state.searchEffects) SearchScope.ALL else SearchScope.NAMES
        outcome = withContext(Dispatchers.Default) { state.index.search(query, f, scope, limit = STUDIO_LIMIT) }
    }
    var hovered by remember { mutableStateOf<Card?>(null) }
    var picked by remember { mutableStateOf<Card?>(null) }
    val reading = hovered ?: picked ?: outcome.cards.firstOrNull()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (studio.focus) runCatching { focus.requestFocus() } }

    // What a double-click and Enter do: into the deck, or onto the list.
    fun primary(card: Card) {
        if (list != null) neue.toggleOnList(card, list.id) else CardActions.add(state, card)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            // The page under it hears nothing while this is up.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Micro(if (list != null) "Adding to ${list.name}" else "Search", color = c.ink45)
                MuInput(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "A name, or words from the text — name: and text: choose one",
                    focusRequester = focus,
                    onFocusChange = { neue.searchFocused = it },
                    onSubmit = { outcome.cards.firstOrNull()?.let(::primary) },
                    textStyle = MuType.h2(LocalMuFonts.current),
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                )
                Mono(
                    if (outcome.truncated) "${outcome.cards.size} of ${"%,d".format(outcome.matchCount)}" else "%,d".format(outcome.matchCount),
                    color = c.ink70,
                )
                Tip("Also match the words printed on the card") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Micro("Text", color = c.ink45)
                        MuSwitch(state.searchEffects, state::onSearchEffectsChange)
                    }
                }
                if (list != null) {
                    Segmented(onlyList, listOf(false, true), { if (it) "On the list · ${list.ids.size}" else "Every card" }, { onlyList = it }, small = true)
                }
                MuButton("Done", { neue.studio = null }, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
            }
            HRule(strong = true)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // The filters, always open.
                val filterScroll = rememberScrollState()
                Box(Modifier.width(340.dp).fillMaxHeight()) {
                    FilterPanel(filter, { filter = it }, state.index, Modifier.verticalScroll(filterScroll).padding(24.dp))
                    ScrollbarFor(filterScroll)
                }
                VRule(color = c.ink12)
                // The results.
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val grid = rememberLazyGridState()
                    LaunchedEffect(query, filter, onlyList) { grid.scrollToItem(0) }
                    if (outcome.cards.isEmpty()) {
                        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.kaiharimoto.neue.kit.MuText(if (onlyList) "Nothing on it yet." else "No matches.", style = MuType.h1(LocalMuFonts.current))
                            Small(if (onlyList) "Show every card, find one, and double-click it to put it on the list." else "Try fewer words, or turn off a filter.", color = c.ink70)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(132.dp),
                            state = grid,
                            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                            contentPadding = PaddingValues(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(outcome.cards, key = { _, card -> card.id.value }) { _, card ->
                                val onList = listIds?.contains(card.id.value) == true
                                var origin by remember { mutableStateOf(Offset.Zero) }
                                Box(
                                    Modifier
                                        .aspectRatio(CARD_RATIO)
                                        .onGloballyPositioned { origin = it.positionInWindow() }
                                        .onPointer(PointerEventType.Enter) { hovered = card }
                                        .onPointer(PointerEventType.Exit) { if (hovered == card) hovered = null }
                                        .onContextMenu { local -> neue.menu = MenuSpec(origin + local, CardActions.poolMenu(card, state, neue)) }
                                        .pointerInput(card, list?.id) {
                                            detectTapGestures(onTap = { picked = card }, onDoubleTap = { primary(card) })
                                        }
                                        .cursorPointer(caption = if (list != null) (if (onList) "Take off" else "Put on") else "Read"),
                                ) {
                                    NeueCard(
                                        card = card,
                                        modifier = Modifier.fillMaxSize(),
                                        format = state.format,
                                        copies = state.copiesInDeck(card.id),
                                        selected = card == picked,
                                        dimmed = list == null && state.remaining(card) <= 0,
                                        foil = neue.prefs.foil,
                                    )
                                    // On the list: an inverted tick in the corner, the way a chosen cover is numbered.
                                    if (onList) {
                                        Box(Modifier.align(Alignment.TopStart).padding(6.dp).background(c.ink).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                            Mono("✓", color = c.paper)
                                        }
                                    }
                                }
                            }
                        }
                        ScrollbarFor(grid)
                    }
                }
                VRule(color = c.ink12)
                // The card, read large.
                Box(Modifier.width(420.dp).fillMaxHeight()) {
                    reading?.let { card -> Reading(card, state, neue, list?.id, { filter = it }, filter) }
                }
            }
        }
    }
}

/** How many results the pop-out lists: enough to scroll through a whole archetype and then some. */
private const val STUDIO_LIMIT = 1000

/**
 * The card being read in the pop-out: the inspector's parts, and what can be done
 * with it — into the deck, onto the side, onto or off the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reading(card: Card, state: DeckBuilderState, neue: NeueState, listId: String?, onFilter: (CardFilter) -> Unit, filter: CardFilter) {
    val c = Mu.colors
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NeueCard(card, Modifier.fillMaxWidth().aspectRatio(CARD_RATIO), format = state.format, foil = neue.prefs.foil)
            ArtSwitch(card, neue)
            CardHeading(card)
            SelectionContainer { Body(card.description.ifBlank { "No card text." }, color = c.ink) }
            // The actions, the add first — or the list's, when the pop-out is adding to one.
            val home = card.requiredSection()
            val left = state.remaining(card)
            val list = neue.list(listId) ?: neue.activeList
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val onList = list != null && card.id.value in list.ids
                if (listId != null && list != null) {
                    MuButton(if (onList) "Take off ${list.name}" else "Put on ${list.name}", { neue.toggleOnList(card, list.id) }, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
                }
                MuButton(
                    "Add to ${home.displayName.lowercase()} deck",
                    { CardActions.add(state, card) },
                    variant = if (listId == null) BtnVariant.PRIMARY else BtnVariant.SECONDARY,
                    size = BtnSize.SM,
                    enabled = left > 0 && state.canDrop(card, null, home),
                    reason = if (left <= 0) "No copies left" else "${home.displayName} deck is full",
                )
                MuButton(
                    "Add to side",
                    { CardActions.add(state, card, toSide = true) },
                    size = BtnSize.SM,
                    enabled = left > 0 && state.canDrop(card, null, DeckSection.SIDE),
                    reason = if (left <= 0) "No copies left" else "Side deck is full",
                )
                if (listId == null) {
                    MuButton(
                        if (list == null) "Put on a new list" else if (onList) "Take off ${list.name}" else "Put on ${list.name}",
                        { neue.toggleOnList(card, list?.id) },
                        variant = BtnVariant.GHOST,
                        size = BtnSize.SM,
                    )
                }
            }
            // What it is filed under: a click narrows the results to it, here.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("Filed under", color = c.ink45)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.race?.let { race ->
                        if (card.category == CardCategory.MONSTER) {
                            Tag(race, race in filter.races, { onFilter(filter.copy(races = filter.races + race)) }, Modifier.height(24.dp), caption = "Filter")
                        } else {
                            Tag(race, race in filter.properties, { onFilter(filter.copy(properties = filter.properties + race)) }, Modifier.height(24.dp), caption = "Filter")
                        }
                    }
                    if (card.category == CardCategory.MONSTER && card.attribute != Attribute.UNKNOWN) {
                        val a = card.attribute
                        Tag(a.name.lowercase().replaceFirstChar { it.uppercase() }, a in filter.attributes, { onFilter(filter.copy(attributes = filter.attributes + a)) }, Modifier.height(24.dp), caption = "Filter")
                    }
                    card.archetype?.let { a -> Tag(a, a in filter.archetypes, { onFilter(filter.copy(archetypes = filter.archetypes + a)) }, Modifier.height(24.dp), caption = "Filter") }
                }
            }
            val kinds = remember(card) { EffectKinds.of(card) }
            if (kinds.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Micro("What it does", color = c.ink45)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        kinds.forEach { kind -> Tag(kind.label, kind in filter.effects, { onFilter(filter.copy(effects = filter.effects + kind)) }, Modifier.height(24.dp), caption = "Filter") }
                    }
                }
            }
            HRule(color = c.ink25)
            Copies(card, state)
        }
        ScrollbarFor(scroll)
    }
}
