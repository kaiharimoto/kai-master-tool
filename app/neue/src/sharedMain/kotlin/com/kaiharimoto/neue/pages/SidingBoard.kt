package com.kaiharimoto.neue.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.BoardFit
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.WordToggle
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.theme.Mu

/**
 * The deck to side from, laid out as the builder lays it out (1.0.49, kai: "the card picker
 * organized like the deck builder because that's what the user is most familiar with"): the
 * Main Deck ten to a row, every copy its own card in the deck's own order. A card of the Main
 * or Extra Deck sides out with a click; a card of the Side Deck comes in. What the turn already
 * moves is marked on the copies themselves — dimmed and labelled OUT, or framed and labelled
 * IN — and a click on a marked copy (or a right-click on any) takes it back. [SidingMath] still
 * guards every move.
 *
 * **Fitted** ([fit], 1.0.51, kai: "have the main deck all fit in the screen without needing to
 * scroll… with the remaining space horizontally put the side deck next to the main deck"): in a
 * bounded space the Side Deck stands beside the Main Deck, and the cards are as large as lets
 * the whole board fit ([BoardFit]). Otherwise — a phone, the Prep page's drills — the sections
 * stack, sized by the width. **The Extra Deck is a toggle** ([showExtra], off by default: siding
 * it is rare), offered only when the Side Deck holds Extra Deck cards to bring in for it.
 */
@Composable
internal fun SidingBoard(
    deck: Deck,
    plan: SidePlan,
    turn: Turn,
    state: DeckBuilderState,
    showExtra: Boolean,
    onShowExtra: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
    onPlan: (SidePlan) -> Unit,
) {
    val c = Mu.colors
    val when_ = if (turn == Turn.FIRST) "going first" else "going second"
    // Only an Extra Deck card from the Side Deck can replace one sided out of the Extra Deck.
    val sideHasExtra = remember(deck.side, state.index) { deck.side.any { state.index.byId(it)?.isExtraDeck == true } }
    val extraSidable = deck.extra.isNotEmpty() && sideHasExtra
    val extraShown = extraSidable && showExtra
    val takeOut: (CardId) -> Unit = { id -> if (SidingMath.canOut(deck, plan, id)) onPlan(plan.plusOut(id)) }
    val takeBackOut: (CardId) -> Unit = { onPlan(plan.minusOut(it)) }
    val bringIn: (CardId) -> Unit = { id -> if (SidingMath.canIn(deck, plan, id)) onPlan(plan.plusIn(id)) }
    val takeBackIn: (CardId) -> Unit = { onPlan(plan.minusIn(it)) }
    val toggle: (@Composable () -> Unit)? = if (!extraSidable) null else {
        { WordToggle("Extra Deck ${deck.extra.size}", extraShown) { onShowExtra(!showExtra) } }
    }
    val gap = 4.dp
    if (fit) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val f = BoardFit.fit(
                maxWidth.value, maxHeight.value, deck.main.size, if (extraShown) deck.extra.size else 0, deck.side.size,
                gap = gap.value, sectionGap = SECTION_GAP.value, header = HEADER.value, ratio = CARD_RATIO, maxCard = 150f,
            )
            val card = f.card.dp
            Row(horizontalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                    Column {
                        Header("Main Deck", "click to side out, $when_", deck.main.size, Modifier.height(HEADER), toggle)
                        Section(deck.main, BoardFit.MAIN_COLUMNS, card, gap, plan.out, strong = false, state, "Side out", takeOut, takeBackOut)
                    }
                    if (extraShown) {
                        Column {
                            Header("Extra Deck", "click to side out", deck.extra.size, Modifier.height(HEADER))
                            Section(deck.extra, BoardFit.MAIN_COLUMNS, card, gap, plan.out, strong = false, state, "Side out", takeOut, takeBackOut)
                        }
                    }
                }
                Column {
                    Header("Side Deck", "click to bring in", deck.side.size, Modifier.height(HEADER))
                    if (deck.side.isEmpty()) {
                        Small("Empty", color = c.ink45)
                    } else {
                        // Column by column, top to bottom, so the side reads down beside the main deck.
                        SideColumns(deck.side, f.rows, card, gap, plan.into, state, bringIn, takeBackIn)
                    }
                }
            }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The builder's proportions: ten Main Deck cards to a row, and never bigger than a readable card.
        val main = ((maxWidth - gap * 9) / 10).coerceAtMost(112.dp)
        val row15 = ((maxWidth - gap * 14) / 15).coerceAtMost(main)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header("Main Deck", "click to side out, $when_", deck.main.size, trailing = toggle)
            Section(deck.main, 10, main, gap, plan.out, strong = false, state, "Side out", takeOut, takeBackOut)
            if (extraShown) {
                Header("Extra Deck", "click to side out", deck.extra.size)
                Section(deck.extra, 15, row15, gap, plan.out, strong = false, state, "Side out", takeOut, takeBackOut)
            }
            Header("Side Deck", "click to bring in, $when_", deck.side.size)
            if (deck.side.isEmpty()) Small("The Side Deck is empty.", color = c.ink45)
            Section(deck.side, 15, row15, gap, plan.into, strong = true, state, "Bring in", bringIn, takeBackIn)
        }
    }
}

private val HEADER = 28.dp
private val SECTION_GAP = 16.dp

/** The Side Deck in [rows] rows, filled column by column in the deck's order. */
@Composable
private fun SideColumns(
    cards: List<CardId>,
    rows: Int,
    width: Dp,
    gap: Dp,
    moved: List<CardId>,
    state: DeckBuilderState,
    onTake: (CardId) -> Unit,
    onBack: (CardId) -> Unit,
) {
    val movedCount = moved.groupingBy { it }.eachCount()
    val marks = remember(cards, moved) { SidingBoardMarks.of(cards, moved) }
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        cards.indices.chunked(rows.coerceAtLeast(1)).forEach { column ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                column.forEach { i -> Copy(cards[i], marks[i], (movedCount[cards[i]] ?: 0) > 0, width, strong = true, state, "Bring in", onTake, onBack) }
            }
        }
    }
}

@Composable
private fun Header(title: String, hint: String, count: Int, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = Mu.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Micro(title, color = c.ink)
        Mono(count.toString(), color = c.ink45)
        // The hint gives way to the toggle when the row is short (a phone).
        Small(hint, Modifier.weight(1f, fill = false), color = c.ink45, maxLines = 1)
        trailing?.invoke()
    }
}

/**
 * One section's copies in order. The first n copies of a card are the ones marked, where n is
 * how many the turn moves: the deck reads left to right, as the plan does.
 */
@Composable
private fun Section(
    cards: List<CardId>,
    perRow: Int,
    width: Dp,
    gap: Dp,
    moved: List<CardId>,
    strong: Boolean,
    state: DeckBuilderState,
    verb: String,
    onTake: (CardId) -> Unit,
    onBack: (CardId) -> Unit,
) {
    val movedCount = moved.groupingBy { it }.eachCount()
    // Worked out before the row, not while it is drawn: the row's content recomposes on its own
    // (a hover), and a count kept across it would run on past the marked copies.
    val marks = remember(cards, moved) { SidingBoardMarks.of(cards, moved) }
    // Rows of a fixed length, as the builder's: a row that wrapped by a pixel's rounding would break the grid.
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        cards.indices.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { i -> Copy(cards[i], marks[i], (movedCount[cards[i]] ?: 0) > 0, width, strong, state, verb, onTake, onBack) }
            }
        }
    }
}

/** One copy: a click sides it, or, marked, takes it back; a right-click takes back any copy of a moved card. */
@Composable
private fun Copy(
    id: CardId,
    marked: Boolean,
    anyMoved: Boolean,
    width: Dp,
    strong: Boolean,
    state: DeckBuilderState,
    verb: String,
    onTake: (CardId) -> Unit,
    onBack: (CardId) -> Unit,
) {
    val c = Mu.colors
    val card = state.index.byId(id)
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .width(width)
            .aspectRatio(CARD_RATIO)
            .let { if (marked && strong) it.border(2.dp, c.ink) else it }
            .hoverable(source)
            .cursorPointer(caption = if (marked) "Take back" else verb)
            .onContextMenu { if (anyMoved) onBack(id) }
            .muClickable(interactionSource = source) { if (marked) onBack(id) else onTake(id) },
    ) {
        if (card != null) {
            NeueCard(card, Modifier.fillMaxSize().padding(if (marked && strong) 2.dp else 0.dp), format = state.format, foil = "off", dimmed = marked && !strong)
        } else {
            Box(Modifier.fillMaxSize().border(1.dp, c.ink25))
        }
        if (marked) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .background(if (strong) c.ink else c.paper)
                    .border(1.dp, c.ink)
                    .padding(horizontal = 4.dp),
            ) {
                Mono(if (strong) "IN" else "OUT", color = if (strong) c.paper else c.ink, size = 9.sp)
            }
        }
    }
}

/** Which copies a turn moves: the first n of a card, in the deck's order. */
internal object SidingBoardMarks {
    fun of(cards: List<CardId>, moved: List<CardId>): List<Boolean> {
        val left = moved.groupingBy { it }.eachCount().toMutableMap()
        return cards.map { id ->
            val n = left[id] ?: 0
            if (n > 0) left[id] = n - 1
            n > 0
        }
    }
}
