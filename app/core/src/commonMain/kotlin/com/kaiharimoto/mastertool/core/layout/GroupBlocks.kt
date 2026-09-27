package com.kaiharimoto.mastertool.core.layout

/** Which of a card's seams its own group fills: toward the next card along, the card below, and the corner between the four. */
data class BlockJoin(val right: Boolean, val down: Boolean, val corner: Boolean)

/**
 * The deck as tetris blocks — kai's picture of groups for 1.0.10: "cards in
 * groups flush with no gaps between them, and the groups themselves separated
 * from other groups."
 *
 * Every card keeps its place and its size (the breakdown never moves a card,
 * and a block cannot be flush and aligned at once if its cards shrink at its
 * edges, which is what 1.0.9's crack did). Instead, while a lens is on, the grid
 * opens one even seam between every pair of cards, and each group fills the
 * seams inside itself with its own colour. What is left open — paper — is
 * exactly the seams between two groups. So a group reads as one solid piece,
 * cells and all, and the pieces are separated.
 *
 * [keys] is each position's key (null for a card in none); the answer is one
 * [BlockJoin] per position.
 */
object GroupBlocks {
    fun joins(keys: List<String?>, columns: Int): List<BlockJoin> {
        if (columns <= 0) return keys.map { BlockJoin(false, false, false) }
        fun same(a: Int, b: Int): Boolean = b in keys.indices && keys[a] != null && keys[a] == keys[b]
        return keys.indices.map { p ->
            val lastColumn = p % columns == columns - 1
            val right = !lastColumn && same(p, p + 1)
            val down = same(p, p + columns)
            val corner = right && down && !lastColumn && same(p, p + columns + 1)
            BlockJoin(right, down, corner)
        }
    }
}
