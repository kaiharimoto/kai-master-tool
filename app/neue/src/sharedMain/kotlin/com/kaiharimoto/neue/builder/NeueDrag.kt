package com.kaiharimoto.neue.builder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.kaiharimoto.mastertool.core.haptics.DeskEvent
import com.kaiharimoto.mastertool.core.input.CarryOffset
import com.kaiharimoto.mastertool.core.layout.DeckReorder
import com.kaiharimoto.mastertool.core.layout.GridDropResolver
import com.kaiharimoto.mastertool.core.layout.ItemBox
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
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
     * in pieces (`GroupPieces`). Null for the plain grid. While a card is being
     * reordered, where each is drawn in the preview, which is what the pointer is over.
     */
    val placed: List<Offset>? = null,
    /** Each position's card (passcode). */
    val ids: List<Int> = emptyList(),
    /**
     * Laid out in bands (Fitted, Separate): each position's group, and the Fitted order
     * as drawn (`DeckGroups.fitted`) — a drag here moves a copy set within its group.
     * Null for a grid of cells.
     */
    val bandKeys: List<String?>? = null,
    val bandSets: List<Int>? = null,
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

    /**
     * A card being moved within its own section, as the deck will be if it is let go now
     * (1.0.39): the deck opens a slot where it will land and its old place closes up.
     */
    sealed interface Preview {
        val section: DeckSection
    }

    /** Cells (As is): the section's positions in the order they are drawn. */
    data class Cells(override val section: DeckSection, val order: List<Int>) : Preview

    /** Bands (Fitted, Separate): the Fitted order, passcodes, each once. */
    data class Sets(override val section: DeckSection, val sets: List<Int>) : Preview

    var preview by mutableStateOf<Preview?>(null)
        private set

    /** The card an insertion bar is drawn by, and whether on its right: the end of a row keeps its bar (1.0.39). */
    var mark by mutableStateOf<Pair<Int, Boolean>?>(null)
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

    /** Where card [index] of [section] is drawn, in window pixels: for the studio's drags. */
    fun boxOf(section: DeckSection, index: Int): Rect? =
        grids[section]?.boxes()?.getOrNull(index)?.let { Rect(it.left, it.top, it.right, it.bottom) }

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
        preview = null
        mark = null
    }

    /**
     * Lets go, and makes the edit the indicator promised. Says how it went, for a
     * finger's haptics; a finger's card that landed is ringed where it did (rec 15).
     */
    fun drop(): DeskEvent? {
        val active = held ?: return null
        val landed = hover
        val shown = preview
        held = null
        hover = null
        preview = null
        mark = null
        if (landed == null) return null
        if (!landed.accepted) return DeskEvent.DROP_REFUSED
        val target = landed.section
        // Within its own section the drop is the preview, exactly (1.0.39).
        if (target != null && target == active.from) {
            return when (shown) {
                is Cells -> {
                    if (shown.section != target) return null
                    val cards = state.deck[target]
                    if (!state.reorderSection(target, shown.order.map { cards[it] })) return null
                    if (active.finger) state.revealAt(target, shown.order.indexOf(active.index))
                    DeskEvent.DROPPED
                }
                is Sets -> {
                    if (shown.section != target) return null
                    state.setFittedOrder(shown.sets.map { CardId(it) })
                    DeskEvent.DROPPED
                }
                // Let go where it started: nothing to do.
                null -> null
            }
        }
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
            if (section == active.from && grid.count > 0) {
                mark = null
                return reorder(section, grid, point, active)
            }
            preview = null
            val previous = hover?.takeIf { it.section == section }?.index
            val items = grid.boxes()
            // Rows of a deck in pieces sit up to a few gaps apart; a row is still a row.
            val rowTolerance = if (grid.placed != null) grid.cardHeight * 0.45f else grid.spacing + 8f
            val index = GridDropResolver.insertionIndex(
                items = items,
                cursorX = point.x,
                cursorY = point.y,
                rowTolerance = rowTolerance,
                hysteresis = CarryOffset.HYSTERESIS_DP * active.density,
                previous = previous,
            )
            mark = GridDropResolver.anchor(items, point.y, rowTolerance, index)
            return DropHover(section, index, state.canDrop(active.card, active.from, section))
        }
        preview = null
        mark = null
        // Back onto the pool: the copy leaves the deck.
        if (active.from != null && poolBounds.contains(point)) return DropHover(null, 0, true)
        return null
    }

    /**
     * A card over its own section (1.0.39, `DeckReorder`): it takes the place of the card
     * it is over — one copy through the cells As is; its whole copy set within its own
     * group's block in bands, where a card of another group refuses it (kai's choice).
     * Between cards, nothing changes.
     */
    private fun reorder(section: DeckSection, grid: GridGeometry, point: Offset, active: Held): DropHover {
        val boxes = grid.boxes()
        val over = DeckReorder.hit(boxes, point.x, point.y)
        val keys = grid.bandKeys
        val sets = grid.bandSets
        if (keys != null && sets != null) {
            val shown = (preview as? Sets)?.takeIf { it.section == section }
            val mine = grid.ids.getOrNull(active.index)
            fun here(sets: List<Int>) = DropHover(section, mine?.let { sets.indexOf(it) } ?: active.index, true)
            if (over == null || mine == null) return hover?.takeIf { it.section == section && it.accepted } ?: here(shown?.sets ?: sets)
            if (keys.getOrNull(over) != keys.getOrNull(active.index)) {
                preview = null
                return DropHover(section, active.index, false)
            }
            val next = DeckReorder.moveSet(shown?.sets ?: sets, mine, grid.ids[over])
            preview = if (next == sets) null else Sets(section, next)
            return here(next)
        }
        val identity = (0 until grid.count).toList()
        val shown = (preview as? Cells)?.takeIf { it.section == section }
        val base = shown?.order ?: identity
        val slot = when {
            over == active.index -> null
            over != null -> base.indexOf(over)
            DeckReorder.pastEnd(boxes, point.x, point.y) -> grid.count - 1
            else -> null
        } ?: return DropHover(section, base.indexOf(active.index), true)
        val next = DeckReorder.moveCell(base, active.index, slot)
        preview = if (next == identity) null else Cells(section, next)
        return DropHover(section, next.indexOf(active.index), true)
    }

}
