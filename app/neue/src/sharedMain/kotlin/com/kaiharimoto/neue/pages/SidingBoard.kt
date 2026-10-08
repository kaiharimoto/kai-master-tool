package com.kaiharimoto.neue.pages

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.mastertool.core.layout.PieceLayout
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.BoardFit
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingLayout
import com.kaiharimoto.mastertool.core.siding.SidingMarks
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.arrangementWords
import com.kaiharimoto.neue.builder.Labels
import com.kaiharimoto.neue.builder.drawPieces
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.WordToggle
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * How the board shows the deck's groups (2026-10, kai: "much like how the deck builder allows
 * fitted, as is and groups enabled"): the deck's own [groups], whether they are [on], how the
 * Main Deck stands while they are, and the two switches. Null where the board offers no
 * groups (a deck with none).
 */
internal class BoardGroups(
    val groups: DeckGroups,
    val on: Boolean,
    val arrangement: GroupArrangement,
    val onToggle: () -> Unit,
    val onArrange: (GroupArrangement) -> Unit,
)

/**
 * The deck to side from, laid out as the builder lays it out (1.0.49, kai: "the card picker
 * organized like the deck builder because that's what the user is most familiar with"): every
 * copy its own card in the deck's own order. A card of the Main or Extra Deck sides out with a
 * click; a card of the Side Deck comes in. What the turn already moves is marked on the copies
 * themselves — dimmed and labelled OUT, or framed and labelled IN — and **the copy marked is
 * the copy clicked** (2026-10, kai: "per copy, not per card name"; `SidePlan.outCopies`,
 * [SidingMarks]). A click on a marked copy (or a right-click on any) takes it back.
 * [SidingMath] still guards every move.
 *
 * **Groups** ([grouping], 2026-10): with them on, the Main Deck stands As is, Fitted or
 * Separate exactly as the builder's does ([SidingLayout]) — pieces a gap apart, outlined in
 * each group's colour and named on a tab — and every Extra and Side Deck card in a group wears
 * its colour round its edge.
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
    grouping: BoardGroups? = null,
    onPlan: (SidePlan) -> Unit,
) {
    val c = Mu.colors
    val when_ = if (turn == Turn.FIRST) "going first" else "going second"
    // Only an Extra Deck card from the Side Deck can replace one sided out of the Extra Deck.
    val sideHasExtra = remember(deck.side, state.index) { deck.side.any { state.index.byId(it)?.isExtraDeck == true } }
    val extraSidable = deck.extra.isNotEmpty() && sideHasExtra
    val extraShown = extraSidable && showExtra
    // Each with the copy clicked, so that copy is the one marked.
    val takeOut: (CardId, Int) -> Unit = { id, at -> if (SidingMath.canOut(deck, plan, id)) onPlan(plan.plusOut(id, at)) }
    val takeBackOut: (CardId, Int?) -> Unit = { id, at -> onPlan(plan.minusOut(id, at)) }
    val bringIn: (CardId, Int) -> Unit = { id, at -> if (SidingMath.canIn(deck, plan, id)) onPlan(plan.plusIn(id, at)) }
    val takeBackIn: (CardId, Int?) -> Unit = { id, at -> onPlan(plan.minusIn(id, at)) }
    val outs = Moves(plan.out, plan.outCopies, strong = false, "Side out", takeOut, takeBackOut)
    val ins = Moves(plan.into, plan.inCopies, strong = true, "Bring in", bringIn, takeBackIn)
    // The groups are offered only to a deck that has some.
    val groupsOffered = grouping?.takeIf { g -> g.groups.groups.isNotEmpty() }
    val groups = groupsOffered?.takeIf { it.on }?.groups
    val controls: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            groupsOffered?.let { g ->
                Tip(if (g.on) "Hide the groups: the plain deck" else "The deck in its groups, as the builder shows it") {
                    WordToggle("Groups", g.on, g.onToggle)
                }
                Tip("As is keeps your order; Fitted fits the groups together as blocks; Separate gives each group its own rows") {
                    Box(Modifier.alpha(if (g.on) 1f else 0.45f)) {
                        Segmented(g.arrangement, GroupArrangement.entries, { arrangementWords(it) }, { a -> g.onArrange(a) }, small = true, compact = true)
                    }
                }
            }
            if (extraSidable) WordToggle("Extra Deck ${deck.extra.size}", extraShown) { onShowExtra(!showExtra) }
        }
    }
    val gap = 4.dp
    val pieceGap = if (groups != null) PIECE_GAP else 0.dp
    val tabHeight = with(LocalDensity.current) { maxOf(15.dp, 10.sp.toDp() * 1.3f + 2.dp) }
    if (fit) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            // The pane the Main Deck has, from the plain board's fit: what a Fitted layout is solved for.
            val plainFit = BoardFit.fit(
                maxWidth.value, maxHeight.value, deck.main.size, if (extraShown) deck.extra.size else 0, deck.side.size,
                gap = gap.value, sectionGap = SECTION_GAP.value, header = HEADER.value, ratio = CARD_RATIO, maxCard = MAX_CARD,
            )
            val paneWidth = maxWidth.value - SECTION_GAP.value - plainFit.sideColumns * (plainFit.card + gap.value)
            val pane = paneWidth.coerceAtLeast(1f) to (maxHeight.value - HEADER.value).coerceAtLeast(1f)
            val layout = rememberMainLayout(deck.main, groups, grouping?.arrangement, pane, pieceGap)
            val tab = if (groups != null && layout.pieces > 1) tabHeight else 0.dp
            val f = BoardFit.fit(
                maxWidth.value, maxHeight.value, layout.columns, layout.rowCount, if (extraShown) deck.extra.size else 0, deck.side.size,
                gap = gap.value, sectionGap = SECTION_GAP.value, header = HEADER.value, ratio = CARD_RATIO, maxCard = MAX_CARD,
                spanX = layout.spanX, spanY = layout.spanY, pieceGap = pieceGap.value, tab = tab.value,
            )
            val card = f.card.dp
            Row(horizontalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                    Column {
                        Header("Main Deck", "click to side out, $when_", deck.main.size, Modifier.height(HEADER), controls)
                        MainGrid(deck.main, layout, groups, card, gap, pieceGap, tab, outs, state)
                    }
                    if (extraShown) {
                        Column {
                            Header("Extra Deck", "click to side out", deck.extra.size, Modifier.height(HEADER))
                            Section(deck.extra, layout.columns, card, gap, outs, groups, state)
                        }
                    }
                }
                Column {
                    Header("Side Deck", "click to bring in", deck.side.size, Modifier.height(HEADER))
                    if (deck.side.isEmpty()) {
                        Small("Empty", color = c.ink45)
                    } else {
                        // Column by column, top to bottom, so the side reads down beside the main deck.
                        SideColumns(deck.side, f.rows, card, gap, ins, groups, state)
                    }
                }
            }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        // A pane of the screen's shape for a Fitted layout to be solved for; the width is what binds.
        val layout = rememberMainLayout(deck.main, groups, grouping?.arrangement, width.value to width.value * 0.62f, pieceGap)
        val tab = if (groups != null && layout.pieces > 1) tabHeight else 0.dp
        // The builder's proportions, at the layout's columns, and never bigger than a readable card.
        val main = ((width - gap * (layout.columns - 1) - pieceGap * layout.spanX) / layout.columns).coerceAtMost(112.dp)
        val row15 = ((width - gap * 14) / 15).coerceAtMost(main)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header("Main Deck", "click to side out, $when_", deck.main.size, trailing = controls)
            MainGrid(deck.main, layout, groups, main, gap, pieceGap, tab, outs, state)
            if (extraShown) {
                Header("Extra Deck", "click to side out", deck.extra.size)
                Section(deck.extra, 15, row15, gap, outs, groups, state)
            }
            Header("Side Deck", "click to bring in, $when_", deck.side.size)
            if (deck.side.isEmpty()) Small("The Side Deck is empty.", color = c.ink45)
            Section(deck.side, 15, row15, gap, ins, groups, state)
        }
    }
}

private val HEADER = 28.dp
private val SECTION_GAP = 16.dp
private const val MAX_CARD = 150f

/** The room between two pieces of the Main Deck while its groups are on: the outlines and a name tab stand in it. */
private val PIECE_GAP = 20.dp

/** An outline's width round a piece, in the gap outside it. */
private val FRAME = 3.dp

/** One direction of a plan on the board: what it moves, the copies picked, and what a click does. */
private class Moves(
    val moved: List<CardId>,
    val picked: Map<CardId, List<Int>>,
    val strong: Boolean,
    val verb: String,
    val onTake: (CardId, Int) -> Unit,
    val onBack: (CardId, Int?) -> Unit,
)

/** The Main Deck's layout, solved again only when the deck, its groups or the room change. */
@Composable
private fun rememberMainLayout(
    ids: List<CardId>,
    groups: DeckGroups?,
    arrangement: GroupArrangement?,
    pane: Pair<Float, Float>,
    pieceGap: Dp,
): PieceLayout {
    // The pane rounded, so a pixel's change in the window does not solve the bands again.
    val rounded = (pane.first / 8f).toInt() to (pane.second / 8f).toInt()
    return remember(ids, groups, arrangement, rounded, pieceGap) {
        SidingLayout.main(
            ids, groups ?: DeckGroups.EMPTY, groupsOn = groups != null, arrangement ?: GroupArrangement.FITTED,
            rounded.first * 8f to rounded.second * 8f, cardAspect = 1f / CARD_RATIO, gap = pieceGap.value,
        )
    }
}

/**
 * The Main Deck: each copy at its cell of [layout], whole [pieceGap]s between its pieces, the
 * pieces outlined in their groups' colours with each group's name on a tab ([drawPieces], the
 * builder's own), [tab] kept over the top row for those.
 */
@Composable
private fun MainGrid(
    cards: List<CardId>,
    layout: PieceLayout,
    groups: DeckGroups?,
    width: Dp,
    gap: Dp,
    pieceGap: Dp,
    tab: Dp,
    moves: Moves,
    state: DeckBuilderState,
) {
    val c = Mu.colors
    val height = width / CARD_RATIO
    val marks = remember(cards, moves.moved, moves.picked) { SidingMarks.of(cards, moves.moved, moves.picked) }
    val ordinals = remember(cards) { SidingMarks.ordinals(cards) }
    val movedCount = moves.moved.groupingBy { it }.eachCount()
    val placed = layout.piece.size == cards.size
    fun x(p: Int): Dp = if (placed) (width + gap) * layout.col(p) + pieceGap * layout.shiftX[p] else (width + gap) * (p % SidingLayout.COLUMNS)
    fun y(p: Int): Dp = tab + if (placed) (height + gap) * layout.row(p) + pieceGap * layout.shiftY[p] else (height + gap) * (p / SidingLayout.COLUMNS)
    val columns = if (placed) layout.columns else SidingLayout.COLUMNS
    val rows = if (placed) layout.rowCount else (cards.size + SidingLayout.COLUMNS - 1) / SidingLayout.COLUMNS
    val totalWidth = ((width + gap) * columns - gap + pieceGap * layout.spanX).coerceAtLeast(0.dp)
    val totalHeight = (tab + (height + gap) * rows - gap + pieceGap * layout.spanY).coerceAtLeast(0.dp)
    val keys = remember(cards, groups) { groups?.let { SidingLayout.keys(cards, it) } }
    val measurer = rememberTextMeasurer()
    val tabStyle = MuType.micro(LocalMuFonts.current).copy(fontSize = 10.sp)
    Box(Modifier.size(totalWidth, totalHeight)) {
        if (groups != null && keys != null && placed) {
            Canvas(Modifier.matchParentSize()) {
                drawPieces(
                    keys = keys,
                    pieces = layout,
                    at = { p -> Offset(x(p).toPx(), y(p).toPx()) },
                    cardWidth = width.toPx(),
                    cardHeight = height.toPx(),
                    frame = FRAME.toPx(),
                    colorOf = { id -> groups.byId(id)?.let { GroupMarkers.hue(it.color) } ?: c.ink },
                    alphaOf = { 1f },
                    labels = Labels(measurer, tabStyle, tab.toPx()) { id -> groups.byId(id)?.name.orEmpty() },
                )
            }
        }
        cards.indices.forEach { i ->
            val px = x(i)
            val py = y(i)
            Box(Modifier.offset { IntOffset(px.roundToPx(), py.roundToPx()) }) {
                Copy(cards[i], ordinals[i], marks[i], (movedCount[cards[i]] ?: 0) > 0, width, null, moves, state)
            }
        }
    }
}

/** The Side Deck in [rows] rows, filled column by column in the deck's order. */
@Composable
private fun SideColumns(
    cards: List<CardId>,
    rows: Int,
    width: Dp,
    gap: Dp,
    moves: Moves,
    groups: DeckGroups?,
    state: DeckBuilderState,
) {
    val movedCount = moves.moved.groupingBy { it }.eachCount()
    val marks = remember(cards, moves.moved, moves.picked) { SidingMarks.of(cards, moves.moved, moves.picked) }
    val ordinals = remember(cards) { SidingMarks.ordinals(cards) }
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        cards.indices.chunked(rows.coerceAtLeast(1)).forEach { column ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                column.forEach { i -> Copy(cards[i], ordinals[i], marks[i], (movedCount[cards[i]] ?: 0) > 0, width, groups, moves, state) }
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
        // The hint gives way to the switches when the row is short (a phone).
        Small(hint, Modifier.weight(1f, fill = false), color = c.ink45, maxLines = 1)
        trailing?.invoke()
    }
}

/**
 * One section's copies in order, [perRow] to a row. The copies marked are the ones picked,
 * then the first of each card the plan moves beyond them ([SidingMarks]).
 */
@Composable
private fun Section(
    cards: List<CardId>,
    perRow: Int,
    width: Dp,
    gap: Dp,
    moves: Moves,
    groups: DeckGroups?,
    state: DeckBuilderState,
) {
    val movedCount = moves.moved.groupingBy { it }.eachCount()
    // Worked out before the row, not while it is drawn: the row's content recomposes on its own
    // (a hover), and a count kept across it would run on past the marked copies.
    val marks = remember(cards, moves.moved, moves.picked) { SidingMarks.of(cards, moves.moved, moves.picked) }
    val ordinals = remember(cards) { SidingMarks.ordinals(cards) }
    // Rows of a fixed length, as the builder's: a row that wrapped by a pixel's rounding would break the grid.
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        cards.indices.chunked(perRow.coerceAtLeast(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { i -> Copy(cards[i], ordinals[i], marks[i], (movedCount[cards[i]] ?: 0) > 0, width, groups, moves, state) }
            }
        }
    }
}

/**
 * One copy, the [ordinal]th of its card in its section: a click sides this copy, or, marked,
 * takes it back; a right-click takes back a copy of a moved card. With [groups], a card in a
 * group wears its colour round its edge, in the gap outside it.
 */
@Composable
private fun Copy(
    id: CardId,
    ordinal: Int,
    marked: Boolean,
    anyMoved: Boolean,
    width: Dp,
    groups: DeckGroups?,
    moves: Moves,
    state: DeckBuilderState,
) {
    val c = Mu.colors
    val card = state.index.byId(id)
    val source = remember { MutableInteractionSource() }
    val strong = moves.strong
    val hue = groups?.groupOf(id)?.let { g -> groups.byId(g)?.let { GroupMarkers.hue(it.color) } }
    Box(
        Modifier
            .width(width)
            .aspectRatio(CARD_RATIO)
            .let { m ->
                if (hue == null) m else m.drawBehind {
                    val w = 2.dp.toPx()
                    drawRect(hue, Offset(-w / 2, -w / 2), Size(size.width + w, size.height + w), style = Stroke(w))
                }
            }
            .let { if (marked && strong) it.border(2.dp, c.ink) else it }
            .hoverable(source)
            .cursorPointer(caption = if (marked) "Take back" else moves.verb)
            .onContextMenu { if (anyMoved) moves.onBack(id, if (marked) ordinal else null) }
            .muClickable(interactionSource = source) { if (marked) moves.onBack(id, ordinal) else moves.onTake(id, ordinal) },
    ) {
        if (card != null) {
            NeueCard(card, Modifier.fillMaxSize().padding(if (marked && strong) 2.dp else 0.dp), format = state.format, limits = state.limits, foil = "off", dimmed = marked && !strong)
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
