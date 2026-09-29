package com.kaiharimoto.mastertool.core.layout

/**
 * The deck broken into pieces by its groups — kai's picture for 1.0.15: "have
 * cards of one group together with no gaps, and have it separate from the next
 * group … if cards are in the same group across rows, have them be close
 * together with no gap vertically. The end result should be a deck broken apart
 * and split into pieces separated by groups, some pieces looking like tetris
 * pieces."
 *
 * A **piece** is a set of cards of one group that touch — left, right, above or
 * below — in the grid the deck is read in (cards in no group are a group of
 * their own, so they sit flush with each other). Every card keeps its size and
 * its cell; a piece moves as one rigid shape, right by [shiftX] gaps and down
 * by [shiftY] gaps, so inside a piece every card is flush with its neighbours
 * and its rows stay aligned. Between two pieces there is always at least one
 * gap:
 *
 * - a card whose right-hand neighbour is in another piece has that piece at
 *   least one gap further right than its own, and
 * - a card whose neighbour below is in another piece has that piece at least
 *   one gap further down.
 *
 * Those two rules are a pair of orderings over the pieces, and each piece is
 * given the smallest shift that satisfies them — the longest path to it from
 * the pieces nothing pushes. The one thing no shift can satisfy is a piece on
 * both sides of another: ungrouped cards in a U round a grouped one, which is
 * the ordinary case rather than a rare one. So pieces are grown rather than
 * found: every run along a row is a piece to begin with (runs can always be
 * ordered), and a run joins the run of its group above it unless that would make
 * such a loop. The arm of the U that would close it stays a piece of its own, a
 * gap from the rest — and everything else stays flush.
 *
 * Nothing here moves a card out of its row, or changes the deck's order: a grid
 * index is still a deck position, so a drop still means insert. **The last row is
 * the one exception to "every card in its own column"** (kai, 1.0.33: "cards in a
 * group overhang past a row and aren't grouped together in the last row… have the
 * straggler cards join their group"): it is short, so its runs can stand anywhere
 * along it, in order, and each is placed under its own group in the row above where
 * that makes more of them touch ([column]; [StragglerSlide]).
 */
data class PieceLayout(
    val columns: Int,
    /** Which piece each position is in: an index into the pieces, stable in reading order. */
    val piece: List<Int>,
    /** How many gaps right each position's piece is moved. */
    val shiftX: List<Int>,
    /** How many gaps down. */
    val shiftY: List<Int>,
    /** The column each position stands in: its index's, except in a last row slid under its groups. */
    val column: List<Int> = List(piece.size) { it % columns.coerceAtLeast(1) },
) {
    /** Position [p]'s column. */
    fun col(p: Int): Int = column.getOrElse(p) { p % columns }

    /** Position [p]'s row: always its index's. */
    fun row(p: Int): Int = p / columns

    private val cells: Map<Int, Int> by lazy { piece.indices.associateBy { row(it) * columns + col(it) } }

    /** The position standing at [row], [col], or null for an empty cell or off the grid. */
    fun at(row: Int, col: Int): Int? = if (col !in 0 until columns || row < 0) null else cells[row * columns + col]

    /** The most gaps any row gained: what the grid is wider by, in gaps. */
    val spanX: Int get() = shiftX.maxOrNull() ?: 0

    /** The most gaps any column gained: what the grid is taller by. */
    val spanY: Int get() = shiftY.maxOrNull() ?: 0

    val pieces: Int get() = (piece.maxOrNull() ?: -1) + 1

    /**
     * Which of card [p]'s four sides face outside its piece — or the edge of the
     * grid — as left, top, right, bottom: where the piece's outline runs.
     */
    fun outerSides(p: Int): BooleanArray {
        val r = row(p)
        val c = col(p)
        fun other(q: Int?) = q == null || piece[q] != piece[p]
        return booleanArrayOf(
            other(at(r, c - 1)),
            other(at(r - 1, c)),
            other(at(r, c + 1)),
            other(at(r + 1, c)),
        )
    }

    /**
     * Where [key]'s name is written (1.0.18, moved in 1.0.22): on a tab over a
     * stretch of a piece's top edge — a run of cards along one row with nothing
     * of their own piece above them, so the tab stands in a gap. The longest
     * stretch wins, because the name is cut short to fit what it stands on: a
     * lone card in one row atop three in the next is named over the next row,
     * not squeezed onto the one card. A stretch the name already fits ([need],
     * in card widths) is as long as any, so a name that fits stays where it
     * always was: the largest piece's top-left, then reading order.
     */
    fun labelEdge(keys: List<String?>, key: String, need: Float): LabelEdge? {
        if (keys.size != piece.size) return null
        val sizes = HashMap<Int, Int>()
        keys.indices.filter { keys[it] == key }.forEach { sizes[piece[it]] = (sizes[piece[it]] ?: 0) + 1 }
        fun top(p: Int) = keys[p] == key && outerSides(p)[1]
        var best: LabelEdge? = null
        var bestScore = -1f
        var bestSize = -1
        for (p in keys.indices) {
            val left = at(row(p), col(p) - 1)
            if (!top(p) || (left != null && top(left) && piece[left] == piece[p])) continue
            var run = 1
            while (true) {
                val next = at(row(p), col(p) + run) ?: break
                if (!top(next) || piece[next] != piece[p]) break
                run++
            }
            val score = minOf(run.toFloat(), need)
            val size = sizes[piece[p]] ?: 0
            if (score > bestScore + 1e-4f || (score > bestScore - 1e-4f && size > bestSize)) {
                best = LabelEdge(p, run)
                bestScore = score
                bestSize = size
            }
        }
        return best
    }

    companion object {
        val EMPTY = PieceLayout(1, emptyList(), emptyList(), emptyList())
    }
}

/**
 * Where the last row's cards stand (1.0.33). A row cut short at the end of a
 * section is the only place a card can be moved sideways without moving another,
 * so its runs — each group's cards, kept together and in deck order — are placed
 * along it where the most of them stand under a card of their own group in the row
 * above: a straggler touches its group, and joins its piece. The row stays as it
 * was when nothing gains, and among placements that gain as much, the one nearest
 * the row as read wins. Every other card keeps its index's column.
 */
object StragglerSlide {

    fun columns(keys: List<String?>, columns: Int): IntArray {
        val n = keys.size
        val col = IntArray(n) { it % columns.coerceAtLeast(1) }
        if (columns <= 1) return col
        val first = (n / columns) * columns
        if (n % columns == 0 || first == 0) return col
        // The last row's runs, in order: start position, length, group.
        val runs = mutableListOf<Triple<Int, Int, String?>>()
        var p = first
        while (p < n) {
            var q = p
            while (q + 1 < n && keys[q + 1] == keys[p]) q++
            runs += Triple(p, q - p + 1, keys[p])
            p = q + 1
        }
        val above = first - columns
        fun score(run: Triple<Int, Int, String?>, start: Int) =
            (0 until run.second).count { keys[above + start + it] == run.third }
        val tail = IntArray(runs.size + 1)
        for (i in runs.indices.reversed()) tail[i] = tail[i + 1] + runs[i].second
        // Best placement of runs i.. with run i starting at or after [from]: score, then least moved.
        val memo = HashMap<Long, Pair<Int, Int>>()
        val choice = HashMap<Long, Int>()
        fun best(i: Int, from: Int): Pair<Int, Int> {
            if (i == runs.size) return 0 to 0
            val key = i.toLong() * 1024 + from
            memo[key]?.let { return it }
            var top = Int.MIN_VALUE to Int.MIN_VALUE
            var at = from
            for (start in from..(columns - tail[i])) {
                val rest = best(i + 1, start + runs[i].second)
                val here = (score(runs[i], start) + rest.first) to (rest.second - kotlin.math.abs(start - (runs[i].first - first)))
                if (here.first > top.first || (here.first == top.first && here.second > top.second)) {
                    top = here
                    at = start
                }
            }
            memo[key] = top
            choice[key] = at
            return top
        }
        val asRead = runs.sumOf { score(it, it.first - first) }
        if (best(0, 0).first <= asRead) return col
        var from = 0
        runs.forEachIndexed { i, run ->
            val start = choice.getValue(i.toLong() * 1024 + from)
            for (k in 0 until run.second) col[run.first + k] = start + k
            from = start + run.second
        }
        return col
    }
}

/** A group's name tab: over [cells] cards along one row, from position [first]. */
data class LabelEdge(val first: Int, val cells: Int)

object GroupPieces {

    /** [keys] is each position's group (null for none), read in rows of [columns]. */
    fun of(keys: List<String?>, columns: Int): PieceLayout {
        if (keys.isEmpty() || columns <= 0) return PieceLayout(columns.coerceAtLeast(1), emptyList(), emptyList(), emptyList())
        val n = keys.size
        val col = StragglerSlide.columns(keys, columns)
        val grid = Grid(columns, col)
        // Start from runs one row long, which can always be ordered, and join a run to
        // the one above it wherever they share a group — unless the join would make a
        // piece both left and right of another (or above and below), which no shift
        // can satisfy. Joined in reading order, so the top of the deck is kept whole first.
        val parent = rowRuns(keys, grid)
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (p in 0 until n) {
            val q = grid.below(p) ?: continue
            if (keys[p] != keys[q]) continue
            val a = find(p)
            val b = find(q)
            if (a == b) continue
            val low = minOf(a, b)
            val high = maxOf(a, b)
            parent[high] = low
            if (solve(keys, grid, IntArray(n) { find(it) }) == null) parent[high] = high
        }
        return solve(keys, grid, number(IntArray(n) { find(it) }))
            ?: PieceLayout(columns, List(n) { 0 }, List(n) { 0 }, List(n) { 0 }, col.toList())
    }

    /** Where each position stands: its row by its index, its column by [col]; who stands beside it. */
    private class Grid(val columns: Int, val col: IntArray) {
        private val cells = HashMap<Int, Int>().apply { col.indices.forEach { put((it / columns) * columns + col[it], it) } }
        private fun at(row: Int, c: Int): Int? = if (c !in 0 until columns) null else cells[row * columns + c]
        fun right(p: Int): Int? = at(p / columns, col[p] + 1)
        fun left(p: Int): Int? = at(p / columns, col[p] - 1)
        fun below(p: Int): Int? = at(p / columns + 1, col[p])
    }

    /** Every cell pointing at the first cell of its run along its row: the pieces nothing can stop. */
    private fun rowRuns(keys: List<String?>, grid: Grid): IntArray {
        val parent = IntArray(keys.size) { it }
        for (p in keys.indices) {
            val left = grid.left(p) ?: continue
            if (keys[p] == keys[left]) parent[p] = parent[left]
        }
        return parent
    }

    /** Renumbers roots 0, 1, 2… in the order they are first met. */
    private fun number(roots: IntArray): IntArray {
        val seen = HashMap<Int, Int>()
        return IntArray(roots.size) { p -> seen.getOrPut(roots[p]) { seen.size } }
    }

    private fun solve(keys: List<String?>, grid: Grid, roots: IntArray): PieceLayout? {
        val piece = number(roots)
        val n = keys.size
        val count = (piece.maxOrNull() ?: -1) + 1
        val right = Array(count) { HashSet<Int>() }
        val down = Array(count) { HashSet<Int>() }
        for (p in 0 until n) {
            grid.right(p)?.let { q -> if (piece[q] != piece[p]) right[piece[p]] += piece[q] }
            grid.below(p)?.let { q -> if (piece[q] != piece[p]) down[piece[p]] += piece[q] }
        }
        val x = longest(right, count) ?: return null
        val y = longest(down, count) ?: return null
        return PieceLayout(grid.columns, piece.toList(), List(n) { x[piece[it]] }, List(n) { y[piece[it]] }, grid.col.toList())
    }

    /** The longest path to every node of a graph, or null when it loops. */
    private fun longest(edges: Array<HashSet<Int>>, count: Int): IntArray? {
        val incoming = IntArray(count)
        edges.forEach { out -> out.forEach { incoming[it]++ } }
        val ready = ArrayDeque((0 until count).filter { incoming[it] == 0 })
        val depth = IntArray(count)
        var done = 0
        while (ready.isNotEmpty()) {
            val a = ready.removeFirst()
            done++
            for (b in edges[a]) {
                depth[b] = maxOf(depth[b], depth[a] + 1)
                if (--incoming[b] == 0) ready.addLast(b)
            }
        }
        return if (done == count) depth else null
    }
}
