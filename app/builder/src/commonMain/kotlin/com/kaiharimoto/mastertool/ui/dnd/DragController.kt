package com.kaiharimoto.mastertool.ui.dnd

import androidx.compose.foundation.lazy.grid.LazyGridState
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

/**
 * What is being dragged.
 *
 * [section] is null when the card came from the search pane, which is what makes
 * "add a copy" and "move the copy you picked up" distinguishable at the drop.
 */
data class DragSession(
    val card: Card,
    val section: DeckSection?,
    val index: Int,
    val size: IntSize,
)

/**
 * Where the card would land right now.
 *
 * A null [section] means the search pane, which is the drag-out gesture: pulling
 * a card off the deck and dropping it back where cards come from.
 */
data class DropHover(
    val section: DeckSection?,
    val index: Int,
    val accepted: Boolean,
)

private data class PaneRegistration(
    val bounds: Rect,
    val gridState: LazyGridState?,
    val gridOrigin: Offset,
)

/**
 * The drag session, and where a drop would go.
 *
 * Built by hand rather than on `Modifier.dragAndDropSource`, which is designed
 * for transfer *between* applications: its payload is a platform clip object, so
 * a card would be serialised to text and parsed back within one process, and its
 * drop targets are per-modifier, so resolving a position inside a lazy grid would
 * mean attaching one to every tile.
 *
 * The split between what is snapshot state here and what is not is deliberate.
 * [pointer] changes every frame and is only ever read inside a `graphicsLayer`
 * block, so it invalidates a layer rather than recomposing the screen. Pane
 * bounds are a plain map, written during layout and read during a drag, and
 * never observed at all. Only [session] and [hover] recompose anything, and
 * [hover] is written just when the answer changes — which is what the resolver's
 * deadzone is for.
 */
class DragController(
    /** Given the card, the section it came from (null = the pool) and the target. */
    private val canDrop: (Card, DeckSection?, DeckSection) -> Boolean,
) {
    var session by mutableStateOf<DragSession?>(null)
        private set

    var hover by mutableStateOf<DropHover?>(null)
        private set

    /** Pointer position in root coordinates. Read inside `graphicsLayer` only. */
    var pointer by mutableStateOf(Offset.Zero)
        private set

    var rowTolerancePx: Float = 8f
    var hysteresisPx: Float = 12f

    private val panes = mutableMapOf<DeckSection, PaneRegistration>()
    private var searchBounds: Rect = Rect.Zero

    fun registerPane(
        section: DeckSection,
        bounds: Rect,
        gridState: LazyGridState?,
        gridOrigin: Offset,
    ) {
        panes[section] = PaneRegistration(bounds, gridState, gridOrigin)
    }

    fun registerSearchPane(bounds: Rect) {
        searchBounds = bounds
    }

    fun start(session: DragSession, at: Offset) {
        this.session = session
        pointer = at
        hover = resolve(at, session)
    }

    fun move(delta: Offset) {
        val active = session ?: return
        pointer += delta
        val next = resolve(pointer, active)
        if (next != hover) hover = next
    }

    /** Ends the drag and reports where it landed. */
    fun finish(): DropHover? {
        val landed = hover
        session = null
        hover = null
        return landed
    }

    fun cancel() {
        session = null
        hover = null
    }

    private fun resolve(point: Offset, active: DragSession): DropHover? {
        panes.entries.firstOrNull { it.value.bounds.contains(point) }?.let { (section, pane) ->
            val previous = hover?.takeIf { it.section == section }?.index
            return DropHover(
                section = section,
                index = insertionIndex(pane, point, previous),
                accepted = canDrop(active.card, active.section, section),
            )
        }

        // Dropping back onto the pool takes the card out of the deck. Only
        // meaningful for a card that came from one.
        if (active.section != null && searchBounds.contains(point)) {
            return DropHover(section = null, index = 0, accepted = true)
        }

        return null
    }

    private fun insertionIndex(pane: PaneRegistration, point: Offset, previous: Int?): Int {
        val grid = pane.gridState ?: return 0
        val visible = grid.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return 0

        // Item offsets are relative to the grid's own viewport, so they have to be
        // lifted into the same space the pointer is reported in.
        val boxes = visible.map { item ->
            val left = pane.gridOrigin.x + item.offset.x
            val top = pane.gridOrigin.y + item.offset.y
            ItemBox(
                index = item.index,
                left = left,
                top = top,
                right = left + item.size.width,
                bottom = top + item.size.height,
            )
        }

        return GridDropResolver.insertionIndex(
            items = boxes,
            cursorX = point.x,
            cursorY = point.y,
            rowTolerance = rowTolerancePx,
            hysteresis = hysteresisPx,
            previous = previous,
        )
    }
}
