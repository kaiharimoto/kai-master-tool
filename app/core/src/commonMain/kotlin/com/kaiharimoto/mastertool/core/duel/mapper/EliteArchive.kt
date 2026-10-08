package com.kaiharimoto.mastertool.core.duel.mapper

/*
 * The library seen as a MAP-Elites archive (M.md §4.2): quality-diversity search keeps, for every cell of a grid of board
 * descriptions, the best board found there, and spends the next runs on the cells that are empty or weak. That is what keeps
 * the library wide instead of collapsing onto one "best" board — kai's point that one ranking given in advance skews the
 * search. The descriptors are counted traits, so the grid is measurement, not opinion; inside a cell the elite is the
 * cheapest board to reach (fewest starting cards, then fewest moves), which is measurement too.
 */

/** One axis of the grid: a trait ([BoardTraits.get]'s heads) cut into bins [0, 1, …, cap] where the last bin is "cap or more". */
data class EliteAxis(val head: String, val cap: Int) {
    fun bin(t: BoardTraits): Int? = t[head]?.let { it.toInt().coerceIn(0, cap) }
}

class EliteArchive(val axes: List<EliteAxis>) {
    /** A cell: one bin an axis. */
    data class Cell(val bins: List<Int>)

    private val elites = HashMap<Cell, BoardEntry>()

    /** Every cell's elite. */
    val filled: Map<Cell, BoardEntry> get() = elites

    /** The cells the grid has. */
    val size: Int get() = axes.fold(1) { n, a -> n * (a.cap + 1) }

    /** The share of cells holding a board. */
    val coverage: Double get() = elites.size.toDouble() / size

    /** [e]'s cell, or null when an axis's trait was not measured on it. */
    fun cellOf(e: BoardEntry): Cell? = axes.map { it.bin(e.traits) ?: return null }.let(::Cell)

    /** Offers [e]: it takes its cell when the cell is empty or [e] is cheaper. Whether it did. */
    fun offer(e: BoardEntry): Boolean {
        if (e.stale) return false
        val c = cellOf(e) ?: return false
        val old = elites[c]
        if (old != null && compare(old, e) <= 0) return false
        elites[c] = e
        return true
    }

    /**
     * Where to look next: the empty cells next to a filled one (one bin away on one axis), most promising first — the
     * neighbour whose elite is cheapest leads. Each with the starters of that neighbour, which the next run maps from first.
     */
    fun frontier(): List<Pair<Cell, List<List<Int>>>> {
        val out = LinkedHashMap<Cell, BoardEntry>()
        elites.entries.sortedWith { a, b -> compare(a.value, b.value) }.forEach { (cell, e) ->
            axes.indices.forEach { i ->
                listOf(-1, 1).forEach { step ->
                    val b = cell.bins[i] + step
                    if (b < 0 || b > axes[i].cap) return@forEach
                    val next = Cell(cell.bins.toMutableList().also { it[i] = b })
                    if (next !in elites && next !in out) out[next] = e
                }
            }
        }
        return out.map { (cell, e) -> cell to e.starters }
    }

    /** Cheaper first: fewer starting cards, then fewer moves, then the key. */
    private fun compare(a: BoardEntry, b: BoardEntry): Int {
        val ca = a.lines.minOfOrNull { it.cost } ?: Int.MAX_VALUE
        val cb = b.lines.minOfOrNull { it.cost } ?: Int.MAX_VALUE
        return if (ca != cb) ca.compareTo(cb) else a.key.compareTo(b.key)
    }

    companion object {
        /** The default grid: interruptions, negates, bodies and cards kept (M.md §4.2). */
        val DEFAULT_AXES = listOf(EliteAxis("interruptions", 5), EliteAxis("negates", 3), EliteAxis("bodies", 5), EliteAxis("hand", 3))

        /** [library]'s live boards in an archive over [axes]. */
        fun of(library: BoardLibrary, axes: List<EliteAxis> = DEFAULT_AXES): EliteArchive =
            EliteArchive(axes).also { a -> library.live.forEach(a::offer) }
    }
}
