package com.kaiharimoto.neue.builder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.kaiharimoto.mastertool.core.layout.GridDropResolver
import com.kaiharimoto.mastertool.core.layout.ItemBox
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.dnd.DropHover

/** A card in the air: what it is, where it came from (null section = the pool), and how big it was drawn. */
data class Held(val card: Card, val from: DeckSection?, val index: Int, val size: IntSize)

/** Where a deck section's cards are, in window pixels, so a drop can be resolved without asking the layout. */
data class GridGeometry(
    val bounds: Rect,
    val origin: Offset,
    val columns: Int,
    val cardWidth: Float,
    val cardHeight: Float,
    val spacing: Float,
    val count: Int,
    /**
     * Where each card sits from [origin], when the grid is not even — the deck
     * in pieces (`GroupPieces`). Null for the plain grid.
     */
    val placed: List<Offset>? = null,
) {
    fun boxes(): List<ItemBox> = List(count) { i ->
        val at = placed?.getOrNull(i)
        val left = origin.x + (at?.x ?: ((i % columns) * (cardWidth + spacing)))
        val top = origin.y + (at?.y ?: ((i / columns) * (cardHeight + spacing)))
        ItemBox(i, left, top, left + cardWidth, top + cardHeight)
    }
}

/**
 * Drag and drop for a mouse.
 *
 * The tablet's `DragController` waits 120 ms before a press in the pool may
 * become a drag, because on glass a drag and a scroll start the same way. A
 * mouse scrolls with its wheel, so here a press becomes a drag the moment it
 * passes the slop. Everything about *what a drop means* is the same core code
 * the tablet uses — [GridDropResolver] for the gap, `DeckBuilderState` for the
 * rule — so the two apps cannot disagree about where a card lands.
 *
 * The deck here is never scrolled (it is fitted to the window), so a grid's
 * boxes are arithmetic rather than a lazy list's visible items.
 */
class NeueDrag(private val state: DeckBuilderState) {
    var held by mutableStateOf<Held?>(null)
        private set

    /** Where the pointer is, in window pixels. */
    var pointer by mutableStateOf(Offset.Zero)
        private set

    var hover by mutableStateOf<DropHover?>(null)
        private set

    private val grids = mutableMapOf<DeckSection, GridGeometry>()
    private var poolBounds: Rect = Rect.Zero

    fun register(section: DeckSection, geometry: GridGeometry) {
        grids[section] = geometry
    }

    fun unregister(section: DeckSection) {
        grids.remove(section)
    }

    fun registerPool(bounds: Rect) {
        poolBounds = bounds
    }

    fun start(held: Held, at: Offset) {
        this.held = held
        pointer = at
        hover = resolve(at, held)
    }

    fun moveTo(at: Offset) {
        val active = held ?: return
        pointer = at
        val next = resolve(at, active)
        if (next != hover) hover = next
    }

    fun cancel() {
        held = null
        hover = null
    }

    /** Lets go, and makes the edit the indicator promised. */
    fun drop() {
        val active = held ?: return
        val landed = hover
        held = null
        hover = null
        if (landed == null || !landed.accepted) return
        val target = landed.section
        when {
            target == null -> active.from?.let { from -> state.removeAt(active.card, from, active.index) }
            active.from == null -> state.addCardAt(active.card, target, landed.index)
            else -> state.moveCardTo(
                card = active.card,
                from = active.from,
                fromIndex = active.index,
                to = target,
                insertBefore = landed.index,
            )
        }
    }

    private fun resolve(point: Offset, active: Held): DropHover? {
        grids.entries.firstOrNull { it.value.bounds.contains(point) }?.let { (section, grid) ->
            val previous = hover?.takeIf { it.section == section }?.index
            val index = GridDropResolver.insertionIndex(
                items = grid.boxes(),
                cursorX = point.x,
                cursorY = point.y,
                // Rows of a deck in pieces sit up to a few gaps apart; a row is still a row.
                rowTolerance = if (grid.placed != null) grid.cardHeight * 0.45f else grid.spacing + 8f,
                hysteresis = 12f,
                previous = previous,
            )
            return DropHover(section, index, state.canDrop(active.card, active.from, section))
        }
        // Back onto the pool: the copy leaves the deck.
        if (active.from != null && poolBounds.contains(point)) return DropHover(null, 0, true)
        return null
    }

}
