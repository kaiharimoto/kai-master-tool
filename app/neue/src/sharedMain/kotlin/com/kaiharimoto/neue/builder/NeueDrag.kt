package com.kaiharimoto.neue.builder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.kaiharimoto.mastertool.core.haptics.DeskEvent
import com.kaiharimoto.mastertool.core.input.CarryOffset
import com.kaiharimoto.mastertool.core.layout.GridDropResolver
import com.kaiharimoto.mastertool.core.layout.ItemBox
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.dnd.DropHover

/**
 * A card in the air: what it is, where it came from (null section = the pool), and
 * how big it was drawn. A [finger]'s card rides above the finger and lands where it
 * is drawn (touch swarm, rec 12: `CarryOffset`); [density] is pixels per dp.
 */
data class Held(
    val card: Card,
    val from: DeckSection?,
    val index: Int,
    val size: IntSize,
    val finger: Boolean = false,
    val density: Float = 1f,
)

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
        hover = resolve(landing(at, held), held)
    }

    /** Moves the card; true when the slot it would land in changed (a finger feels that, rec 13). */
    fun moveTo(at: Offset): Boolean {
        val active = held ?: return false
        pointer = at
        val next = resolve(landing(at, active), active)
        if (next == hover) return false
        val slot = next != null && (next.section != hover?.section || next.index != hover?.index) && next.accepted
        hover = next
        return slot
    }

    /** Where the card in the air is drawn, in window pixels: above a finger, centred on a mouse. */
    fun drawn(): CarryOffset.Drawn? = held?.let { drawn(pointer, it) }

    /** A deck card over the pool, which lets it go: the pool says "Let go to remove" (rec 12). */
    val overPool: Boolean get() = held?.from != null && hover?.let { it.section == null && it.accepted } == true

    /** A drop that would be refused: the carried card is hatched (rec 12). */
    val refused: Boolean get() = hover?.accepted == false

    private fun drawn(at: Offset, held: Held) = CarryOffset.carried(
        at.x, at.y, held.size.width.toFloat(), held.size.height.toFloat(), held.finger, held.density,
    )

    /** A drop lands where the card is drawn — the classic rule (docs/classic/LOOP.md). */
    private fun landing(at: Offset, held: Held): Offset =
        CarryOffset.dropPoint(drawn(at, held)).let { (x, y) -> Offset(x, y) }

    fun cancel() {
        held = null
        hover = null
    }

    /**
     * Lets go, and makes the edit the indicator promised. Says how it went, for a
     * finger's haptics; a finger's card that landed is ringed where it did (rec 15).
     */
    fun drop(): DeskEvent? {
        val active = held ?: return null
        val landed = hover
        held = null
        hover = null
        if (landed == null) return null
        if (!landed.accepted) return DeskEvent.DROP_REFUSED
        val target = landed.section
        return when {
            target == null -> {
                val from = active.from ?: return null
                if (state.removeAt(active.card, from, active.index)) DeskEvent.REMOVED else null
            }
            active.from == null -> {
                if (!state.addCardAt(active.card, target, landed.index)) return DeskEvent.DROP_REFUSED
                if (active.finger) state.revealAt(target, landed.index)
                DeskEvent.DROPPED
            }
            else -> {
                val moved = state.moveCardTo(
                    card = active.card,
                    from = active.from,
                    fromIndex = active.index,
                    to = target,
                    insertBefore = landed.index,
                )
                if (!moved) return DeskEvent.DROP_REFUSED
                // Within a section, the slots after the one it left each moved up by one.
                val at = if (active.from == target && active.index < landed.index) landed.index - 1 else landed.index
                if (active.finger) state.revealAt(target, at)
                DeskEvent.DROPPED
            }
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
                hysteresis = CarryOffset.HYSTERESIS_DP * active.density,
                previous = previous,
            )
            return DropHover(section, index, state.canDrop(active.card, active.from, section))
        }
        // Back onto the pool: the copy leaves the deck.
        if (active.from != null && poolBounds.contains(point)) return DropHover(null, 0, true)
        return null
    }

}
