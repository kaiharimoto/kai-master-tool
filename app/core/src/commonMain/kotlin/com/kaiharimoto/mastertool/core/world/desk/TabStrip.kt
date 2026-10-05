package com.kaiharimoto.mastertool.core.world.desk

import kotlin.math.floor

/**
 * How the Browser's tab strip shares its room (§4, `TabStripTest`): tabs as wide as the room allows, between [MIN_W] —
 * room for the page's glyph, a readable title and the ✕ — and [MAX_W]. Past what fits at [MIN_W], the strip shows as many
 * whole tabs as fit, stretched to fill the room exactly (never a tab cut at the edge), and the rest are a count beside
 * it that opens them as a list. The window of tabs shown follows the selected one ([follow]).
 */
object TabStrip {
    /**
     * The narrowest a tab gets: its number, its glyph, a card's art, at least [TabTitles.FLOOR] characters of title in the
     * body tier and the ✕ (READABILITY.md §4) — 12 + 16 + 22 + 19 + 83 + 24.
     */
    const val MIN_W = 176.0

    /** The widest a tab gets (§4). */
    const val MAX_W = 220.0

    /** The `+` cell after the tabs. */
    const val NEW_W = 32.0

    /** The overflow's cell: a count and ⌄, opening every tab as a list. */
    const val LIST_W = 52.0

    /**
     * The strip's measure for [count] tabs in [room] dp: each tab's [width], how many are [shown] at once, and how many
     * are not ([hidden]).
     */
    data class Fit(val width: Double, val shown: Int, val hidden: Int) {
        val overflows: Boolean get() = hidden > 0
    }

    fun fit(room: Double, count: Int): Fit {
        if (count <= 0) return Fit(MAX_W, 0, 0)
        val free = (room - NEW_W).coerceAtLeast(0.0)
        if (count * MIN_W <= free) return Fit((free / count).coerceIn(MIN_W, MAX_W), count, 0)
        val left = (free - LIST_W).coerceAtLeast(0.0)
        val shown = floor(left / MIN_W).toInt().coerceIn(1, count)
        return Fit((left / shown).coerceIn(MIN_W.coerceAtMost(left), MAX_W), shown, count - shown)
    }

    /**
     * The first tab shown, for the [selected] tab (its index, or −1) to stand in a window of [shown] tabs out of [count]:
     * [first] kept while the selected one is inside it, else moved the least that brings it in.
     */
    fun follow(first: Int, selected: Int, shown: Int, count: Int): Int {
        val last = (count - shown).coerceAtLeast(0)
        val f = first.coerceIn(0, last)
        if (selected < 0 || shown <= 0) return f
        return when {
            selected < f -> selected
            selected >= f + shown -> selected - shown + 1
            else -> f
        }.coerceIn(0, last)
    }
}
