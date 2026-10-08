package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.mastertool.core.layout.GroupBands
import com.kaiharimoto.mastertool.core.layout.GroupPieces
import com.kaiharimoto.mastertool.core.layout.GroupRows
import com.kaiharimoto.mastertool.core.layout.PieceLayout
import com.kaiharimoto.mastertool.core.model.CardId

/**
 * The siding board's Main Deck laid out as the builder lays it out (kai, 2026-10: "give the user
 * more options for viewing … much like how the deck builder allows fitted, as is and groups
 * enabled"). With the groups off, ten across in the deck's order; on, the builder's three
 * arrangements over the deck's own groups — **As is** its order broken into pieces
 * ([GroupPieces]), **Fitted** bands of group blocks ([GroupBands]), **Separate** a group to its
 * rows ([GroupRows]) — each handed on as a [PieceLayout]: a cell per copy, and whole gaps
 * between pieces.
 */
object SidingLayout {
    const val COLUMNS = BoardFit.MAIN_COLUMNS

    /** Each copy's group, or null: what the outlines and the pieces read. */
    fun keys(ids: List<CardId>, groups: DeckGroups): List<String?> = ids.map { groups.groupOf(it) }

    /**
     * Where each of [ids] stands. [pane] is the room the Main Deck has (width by height, any
     * unit), [cardAspect] a card's height over its width, [gap] the room between two pieces in
     * the pane's unit. A layout that cannot be solved falls back to the plain ten across.
     */
    fun main(
        ids: List<CardId>,
        groups: DeckGroups,
        groupsOn: Boolean,
        arrangement: GroupArrangement,
        pane: Pair<Float, Float>,
        cardAspect: Float,
        gap: Float,
    ): PieceLayout {
        if (ids.isEmpty()) return PieceLayout.EMPTY
        val keys = keys(ids, groups)
        if (!groupsOn || keys.all { it == null }) return plain(ids.size)
        val order = groups.ordered().map { it.id }
        val passcodes = ids.map { it.value }
        val setOrder = groups.fitted.map { it.value }
        return when (arrangement) {
            GroupArrangement.AS_IS -> GroupPieces.of(keys, COLUMNS)
            GroupArrangement.FITTED -> GroupBands.layout(
                passcodes, keys, order, pane, cardAspect = cardAspect, gapX = gap, gapY = gap, setOrder = setOrder,
            )?.pieces()
            GroupArrangement.SEPARATE -> GroupRows.layout(
                passcodes, keys, order, pane, cardAspect = cardAspect, gapY = gap, setOrder = setOrder,
            )?.pieces()
        } ?: plain(ids.size)
    }

    /** [n] copies ten across, one piece, no gaps. */
    fun plain(n: Int, columns: Int = COLUMNS): PieceLayout =
        if (n <= 0) PieceLayout.EMPTY else PieceLayout(columns, List(n) { 0 }, List(n) { 0 }, List(n) { 0 })
}
