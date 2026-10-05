package com.kaiharimoto.mastertool.core.layout

/**
 * A bar of tools that folds into a ⋯ menu as its width runs out, as `PhoneBar` does (Present's editor
 * bar, the editor's audit B11: sixteen controls in one row, so Present shrank to a bare arrow at 1280 px
 * and everything after More was off the edge of a phone).
 *
 * Each tool has a width and a rank: rank 0 is pinned and never folds (Back, Export, Present); otherwise
 * the higher the rank, the sooner it goes. The bar keeps its order; what folds is listed in the menu in
 * the bar's order too.
 */
object ToolFold {
    data class Tool(val width: Float, val rank: Int)

    /** Which tools stand in the bar ([shown], by index) and whether the ⋯ button is needed. */
    data class Fit(val shown: Set<Int>, val folded: List<Int>) {
        val overflows: Boolean get() = folded.isNotEmpty()
    }

    /**
     * The tools of [tools] that fit [available] with [gap] between neighbours, the ⋯ button ([more] wide)
     * taking its place once anything folds. Pinned tools always stand, even past [available]: a bar that
     * cannot present is worse than one that overflows by a few pixels.
     */
    fun fit(tools: List<Tool>, available: Float, gap: Float = 0f, more: Float = 0f): Fit {
        fun width(ids: Collection<Int>, withMore: Boolean): Float {
            val n = ids.size + if (withMore) 1 else 0
            return ids.sumOf { tools[it].width.toDouble() }.toFloat() + (if (withMore) more else 0f) + gap * (n - 1).coerceAtLeast(0)
        }
        val all = tools.indices.toSet()
        if (width(all, false) <= available) return Fit(all, emptyList())
        val shown = all.toMutableSet()
        // Fold the highest rank first; among equals, the rightmost first, so the bar shortens from its end.
        val order = tools.indices.filter { tools[it].rank > 0 }.sortedWith(compareByDescending<Int> { tools[it].rank }.thenByDescending { it })
        for (i in order) {
            if (width(shown, true) <= available) break
            shown -= i
        }
        val folded = tools.indices.filter { it !in shown }
        return Fit(shown, folded)
    }
}
