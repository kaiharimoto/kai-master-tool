package com.kaiharimoto.mastertool.core.motion

import com.kaiharimoto.mastertool.core.layout.LabelEdge
import com.kaiharimoto.mastertool.core.layout.PieceLayout

/**
 * The groups' names in zen (kai, 1.0.24: "let the user toggle the labels for the
 * groups as well"): the builder's tab (`PieceLayout.labelEdge`), on the pieces zen
 * opens. Two things differ from the builder, and both are here.
 *
 * - A card carried out of its piece takes no name with it: the name stands on the
 *   cards still in the piece ([edge]), so it never floats over an empty slot.
 * - Zen reserves no room over the pieces for a tab. The tab stands in whatever gap
 *   is over its edge ([tab]): a gap between two pieces, as wide as the wheel has
 *   made it; the paper between two sections; or, over the top of the deck, the
 *   table itself. A tab too short to read is not drawn.
 */
object ZenLabels {

    /** Where [key]'s name stands: over a top edge of the cards of its pieces that [moved] has not taken away. */
    fun edge(layout: PieceLayout, keys: List<String?>, key: String, need: Float, moved: (Int) -> Boolean): LabelEdge? =
        layout.labelEdge(keys.mapIndexed { p, k -> if (moved(p)) null else k }, key, need)

    /**
     * How tall the tab over an edge on [row] of its section may be: [tab] where the
     * room allows, less where it does not, and 0 below [least]. Over row 0 of the
     * [first] section there is the table; over row 0 of another, [between] (the
     * rule and paper between two sections); over any other row, [gap] — a top edge
     * off the first row always has another piece above it, a whole gap away.
     */
    fun tab(row: Int, first: Boolean, gap: Float, between: Float, tab: Float, least: Float): Float {
        val room = when {
            row > 0 -> gap
            first -> tab
            else -> between
        }
        val height = minOf(tab, room)
        return if (height < least) 0f else height
    }
}
