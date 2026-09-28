package com.kaiharimoto.mastertool.core.layout

/**
 * The arithmetic of the decklist picture (the screenshot's List shape, 1.0.23):
 * each card once with how many copies, under its group, the main deck in two
 * columns. The picture itself is drawn in `neue/shot`.
 */
object DeckList {

    /** [count] copies of the card first met at [first], in group [key]. */
    data class Stack(val first: Int, val count: Int, val key: String?)

    /**
     * A section's cards with each card's copies collapsed into one, in the order
     * each is first met. [ids] is each position's card (null for one the pool does
     * not know, which is never merged); a card in two groups is two stacks.
     */
    fun stacks(ids: List<Int?>, keys: List<String?>): List<Stack> {
        val order = LinkedHashMap<Pair<Int, String?>, Stack>()
        ids.forEachIndexed { i, id ->
            val k = (id ?: -(i + 1)) to keys.getOrNull(i)
            order[k] = order[k]?.let { it.copy(count = it.count + 1) } ?: Stack(i, 1, keys.getOrNull(i))
        }
        return order.values.toList()
    }

    /** Without groups, the list is split the way every decklist is quoted (kai, 1.0.23). */
    enum class Kind(val label: String) { MONSTERS("Monsters"), SPELLS("Spells"), TRAPS("Traps") }

    /** The kind a YGOPRODeck `type` ("Effect Monster", "Quick-Play Spell Card", "Counter Trap Card"…) is. */
    fun kindOf(type: String): Kind {
        val t = type.lowercase()
        return when {
            "spell" in t -> Kind.SPELLS
            "trap" in t -> Kind.TRAPS
            else -> Kind.MONSTERS
        }
    }

    /**
     * How many of the blocks, whose heights are [heights], go in the left column
     * so the taller column is as short as whole blocks allow. Ties go left.
     */
    fun split(heights: List<Float>): Int {
        val total = heights.sum()
        var best = heights.size
        var bestHeight = Float.MAX_VALUE
        var left = 0f
        for (k in 0..heights.size) {
            if (k > 0) left += heights[k - 1]
            val h = maxOf(left, total - left)
            if (h <= bestHeight + 1e-3f) {
                bestHeight = h
                best = k
            }
        }
        return best
    }
}
