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
 * Nothing here moves a card between cells: a grid index is still a deck
 * position, so a drop still means insert.
 */
data class PieceLayout(
    val columns: Int,
    /** Which piece each position is in: an index into the pieces, stable in reading order. */
    val piece: List<Int>,
    /** How many gaps right each position's piece is moved. */
    val shiftX: List<Int>,
    /** How many gaps down. */
    val shiftY: List<Int>,
) {
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
        val n = piece.size
        val c = p % columns
        fun other(q: Int) = q !in 0 until n || piece[q] != piece[p]
        return booleanArrayOf(
            c == 0 || other(p - 1),
            other(p - columns),
            c == columns - 1 || other(p + 1),
            other(p + columns),
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
            if (!top(p) || (p % columns != 0 && top(p - 1) && piece[p - 1] == piece[p])) continue
            var run = 1
            while ((p + run) % columns != 0 && p + run < keys.size && top(p + run) && piece[p + run] == piece[p]) run++
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

/** A group's name tab: over [cells] cards along one row, from position [first]. */
data class LabelEdge(val first: Int, val cells: Int)

object GroupPieces {

    /** [keys] is each position's group (null for none), read in rows of [columns]. */
    fun of(keys: List<String?>, columns: Int): PieceLayout {
        if (keys.isEmpty() || columns <= 0) return PieceLayout(columns.coerceAtLeast(1), emptyList(), emptyList(), emptyList())
        val n = keys.size
        // Start from runs one row long, which can always be ordered, and join a run to
        // the one above it wherever they share a group — unless the join would make a
        // piece both left and right of another (or above and below), which no shift
        // can satisfy. Joined in reading order, so the top of the deck is kept whole first.
        val parent = rowRuns(keys, columns)
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (p in 0 until n - columns) {
            val q = p + columns
            if (keys[p] != keys[q]) continue
            val a = find(p)
            val b = find(q)
            if (a == b) continue
            val low = minOf(a, b)
            val high = maxOf(a, b)
            parent[high] = low
            if (solve(keys, columns, IntArray(n) { find(it) }) == null) parent[high] = high
        }
        return solve(keys, columns, number(IntArray(n) { find(it) }))
            ?: PieceLayout(columns, List(n) { 0 }, List(n) { 0 }, List(n) { 0 })
    }

    /** Every cell pointing at the first cell of its run along its row: the pieces nothing can stop. */
    private fun rowRuns(keys: List<String?>, columns: Int): IntArray {
        val parent = IntArray(keys.size) { it }
        for (p in keys.indices) {
            if (p % columns != 0 && keys[p] == keys[p - 1]) parent[p] = parent[p - 1]
        }
        return parent
    }

    /** Renumbers roots 0, 1, 2… in the order they are first met. */
    private fun number(roots: IntArray): IntArray {
        val seen = HashMap<Int, Int>()
        return IntArray(roots.size) { p -> seen.getOrPut(roots[p]) { seen.size } }
    }

    private fun solve(keys: List<String?>, columns: Int, roots: IntArray): PieceLayout? {
        val piece = number(roots)
        val n = keys.size
        val count = (piece.maxOrNull() ?: -1) + 1
        val right = Array(count) { HashSet<Int>() }
        val down = Array(count) { HashSet<Int>() }
        for (p in 0 until n) {
            if (p % columns != columns - 1 && p + 1 < n && piece[p + 1] != piece[p]) right[piece[p]] += piece[p + 1]
            if (p + columns < n && piece[p + columns] != piece[p]) down[piece[p]] += piece[p + columns]
        }
        val x = longest(right, count) ?: return null
        val y = longest(down, count) ?: return null
        return PieceLayout(columns, piece.toList(), List(n) { x[piece[it]] }, List(n) { y[piece[it]] })
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
