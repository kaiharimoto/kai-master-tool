package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * How the builder lays the main deck out while its groups are on (kai, 1.0.37: "offer
 * 3 toggles: as is — meaning custom order, fitted, and separate").
 *
 * - **As is**: the deck's own order, broken into pieces where groups touch ([GroupPieces]).
 * - **Fitted**: the deck as bands of group blocks fitted together ([GroupBands]), a whole
 *   gap between any two (1.0.40, kai: "organized and with gaps separating them").
 * - **Separate**: each group on rows of its own, as it reads ([GroupRows]; 1.0.40, kai:
 *   "each group in its own line/row — separate does not follow the rules for fitment").
 */
enum class GroupArrangement { AS_IS, FITTED, SEPARATE }

/** A group's block in a [BandLayout]: its rectangle in cells, and where it stands among the others. */
data class BandBlock(
    /** The group's key, or null for the cards in no group. */
    val key: String?,
    val row: Int,
    val col: Int,
    val width: Int,
    val height: Int,
    /** Which band, from the top. */
    val band: Int,
    /** Which stack along its band, from the left. */
    val stack: Int,
    /** How many blocks stand above it in its stack. */
    val above: Int,
)

/**
 * The deck as bands (1.0.37): where every card stands, and the blocks. [row] and [col]
 * are per deck position, so a drop still means "insert here" in the deck's order.
 */
data class BandLayout(
    val columns: Int,
    val rows: Int,
    val row: List<Int>,
    val col: List<Int>,
    /** Which block each position is in. */
    val block: List<Int>,
    val blocks: List<BandBlock>,
) {
    /** The shapes to prefer next time, so an edit does not reshuffle the deck. */
    val memory: BandMemory
        get() = BandMemory(columns, blocks.associate { (it.key ?: UNGROUPED) to (it.width to it.height) })

    /**
     * As the builder's pieces: one piece per block, each [BandBlock.stack] gaps right and
     * [BandBlock.band] + [BandBlock.above] gaps down, every card in its own row and column.
     */
    /**
     * The cards as the bands read — block by block in reading order, each block's cells row
     * by row — each once: the Fitted order this layout shows (`DeckGroups.fitted`).
     */
    fun setOrder(ids: List<Int>): List<Int> {
        val reading = blocks.indices.sortedWith(compareBy({ blocks[it].band }, { blocks[it].stack }, { blocks[it].above }))
        val rank = IntArray(blocks.size).also { r -> reading.forEachIndexed { i, b -> r[b] = i } }
        return ids.indices.sortedWith(compareBy({ rank[block[it]] }, { row[it] }, { col[it] })).map { ids[it] }.distinct()
    }

    fun pieces(): PieceLayout = PieceLayout(
        columns = columns,
        piece = block,
        shiftX = block.map { blocks[it].stack },
        shiftY = block.map { blocks[it].band + blocks[it].above },
        column = col,
        rowOf = row,
    )

    companion object {
        const val UNGROUPED = "\u0000ungrouped"
    }
}

/** What a deck looked like last: its width and each group's block, by key. */
data class BandMemory(val columns: Int, val shapes: Map<String, Pair<Int, Int>>)

/**
 * A deck's bands as solved at each width, kept from one [GroupBands.layout] to the next
 * (1.0.92). The solutions depend only on the deck, its keys, the groups' order, the Fitted
 * order and the memory; the pane, the other rows and the gaps only weigh them. So a pinch
 * or a Shift-wheel opening the gaps weighs the same solutions again instead of solving the
 * deck at every width on every step — the layout it picks is the same either way. Kept for
 * one deck at a time; not for use from two threads at once.
 */
class BandSolves {
    internal var from: List<Any?>? = null
    internal val byWidth = HashMap<Int, GroupBands.Solved?>()
    internal var packs: GroupBands.PackMemo? = null
}

/**
 * The band layout (1.0.37), found over a long design run with kai:
 *
 * 1. **A copy set is one thing to the eye.** A 3-of is a strip of three and never
 *    breaks; a 2-of is a strip of two, across or standing up; a 1-of is one card.
 * 2. **A group is a block**, read across, never taller than [MAX_ROWS] rows, about a
 *    glance ([GLANCE]) wide — wider only as a big group needs to stay four rows. Its
 *    copy sets go in the deck's order, each to the first free cell; only when the next
 *    does not fit in what is left of a line may a later, smaller one move up to fill it,
 *    at a cost ([MOVE]). The only empty cells a block may keep end its last line.
 * 3. **The cards in no group are a group**, last.
 * 4. **The deck is bands**: a band is one height and the deck's width, made of stacks
 *    side by side, a stack one or more blocks of one width. Every edge is straight and
 *    shared. Groups keep their order, band by band, left to right, top to bottom.
 * 5. **The width is chosen for the pane**: every width is tried, and the one with the
 *    fewest empty cells, blocks that read best and the largest cards in [pane] wins.
 * 6. **An edit does not reshuffle**: the width and the block shapes the deck already
 *    has ([BandMemory]) are kept unless a change buys something.
 */
object GroupBands {
    const val MAX_ROWS = 4
    const val GLANCE = 5
    private const val MOVE = 1.5f
    private const val KEEP = 3f
    private const val KEEP_WIDTH = 8f
    private const val SCALE = 30f
    private const val MIN_WIDTH = 6
    private const val MAX_WIDTH = 17

    /**
     * Fitted must manage space as well as As is (kai, 1.0.38: "as is is a lot better at
     * managing space than fitted, and I can't see the side deck cards anymore"). A layout
     * whose cards come out smaller than this share of the plain ten-wide deck's pays
     * [SHORT] for every unit of log it falls short — more than any block's shape saves, and
     * more than the memory of an earlier layout holds it by.
     */
    private const val AS_IS_SHARE = 0.9f
    private const val SHORT = 300f

    /** How many cards across the extra and side decks' rows are: they are drawn the deck's width. */
    private const val OTHER_COLUMNS = 15f

    /** The plain deck the fitted one is held to: ten across, sized for forty from the start. */
    private const val PLAIN_COLUMNS = 10
    private const val PLAIN_BASELINE = 40

    /**
     * The layout for positions [ids] (each a card, by passcode) keyed [keys] (a group, or
     * null), groups in [order] (keys not in it follow in the order first met). [pane] is
     * the room the deck has, width by height, in any unit; [otherRows] the rows below it
     * of other sections, fifteen across at the deck's width; [cardAspect] a card's height
     * over width; [gapX] and [gapY] the room, in the pane's unit, between two stacks across
     * and between two bands (or two blocks of a stack) down. Null when there is nothing to
     * lay out.
     */
    fun layout(
        ids: List<Int>,
        keys: List<String?>,
        order: List<String>,
        pane: Pair<Float, Float>,
        otherRows: Int = 0,
        cardAspect: Float = 86f / 59f,
        memory: BandMemory? = null,
        widths: IntRange? = null,
        gapX: Float = 0f,
        gapY: Float = 0f,
        /** The Fitted order (`DeckGroups.fitted`): a group's copy sets in this order, any not in it after, as the deck reads. */
        setOrder: List<Int> = emptyList(),
        /** The deck's bands solved before, kept for the next layout of the same deck ([BandSolves]); null keeps nothing. */
        solves: BandSolves? = null,
    ): BandLayout? = solveLayout(ids, keys, order, pane, otherRows, cardAspect, memory, widths, gapX, gapY, setOrder, solves, packMemo = true)

    /**
     * [layout], with every block packed afresh when [packMemo] is false: the arithmetic as it
     * was before 1.0.92, which the tests hold the memoised one to.
     */
    internal fun solveLayout(
        ids: List<Int>,
        keys: List<String?>,
        order: List<String>,
        pane: Pair<Float, Float>,
        otherRows: Int,
        cardAspect: Float,
        memory: BandMemory?,
        widths: IntRange?,
        gapX: Float,
        gapY: Float,
        setOrder: List<Int>,
        solves: BandSolves?,
        packMemo: Boolean,
    ): BandLayout? {
        if (ids.isEmpty() || ids.size != keys.size) return null
        val groups = groupsOf(ids, keys, order, setOrder)
        val n = ids.size
        // What the solutions are worked out from: the pane, the other rows and the gaps only weigh them.
        val solvedFrom = listOf(ids, keys, order, setOrder, memory)
        if (solves != null && solves.from != solvedFrom) {
            solves.from = solvedFrom
            solves.byWidth.clear()
            solves.packs = PackMemo(groups)
        }
        val packs: (Int, Int) -> Block? = when {
            !packMemo -> { q, w -> pack(groups[q].sets, w) }
            solves != null -> solves.packs!!::get
            else -> PackMemo(groups)::get
        }
        fun solve(w: Int): Solved? {
            if (solves == null) return Solver(groups, w, memory, packs).solve()
            if (solves.byWidth.containsKey(w)) return solves.byWidth[w]
            return Solver(groups, w, memory, packs).solve().also { solves.byWidth[w] = it }
        }
        val range = widths ?: (min(MIN_WIDTH, n)..min(MAX_WIDTH, max(n, 1)))
        // The card each shape leaves room for: across, the cards and the gaps between stacks;
        // down, the rows, the gaps between bands, and the other sections' rows at this width.
        fun card(w: Int, rows: Float, spanX: Int, spanY: Int): Float = min(
            (pane.first - spanX * gapX) / w,
            (pane.second - spanY * gapY) / (rows * cardAspect + otherRows * cardAspect * w / OTHER_COLUMNS),
        ).coerceAtLeast(1e-6f)
        // The plain deck's rows as a fraction, so the floor moves smoothly with the count: a
        // whole row appearing at the forty-first card would move the floor, and the deck, at once.
        val plainRows = max(n, PLAIN_BASELINE).toFloat() / PLAIN_COLUMNS
        val candidates = range.mapNotNull { w ->
            solve(w)?.let { it to card(w, it.rows.toFloat(), it.spanX, it.spanY) }
        }
        val plain = card(PLAIN_COLUMNS, plainRows, 0, 0)
        val floor = plain * AS_IS_SHARE
        // The extra and side decks are drawn at the deck's width, so with them showing the
        // deck is held to the plain deck's width too: a narrow one shrinks every card in them.
        val wideFloor = if (otherRows > 0) PLAIN_COLUMNS * plain * AS_IS_SHARE else 0f
        var best: Pair<Float, Solved>? = null
        for ((solved, scale) in candidates) {
            val w = solved.width
            val wide = w * scale + solved.spanX * gapX
            var total = solved.cost - SCALE * ln(scale) + SHORT * max(0f, ln(floor / scale)) + SHORT * max(0f, ln(wideFloor / wide))
            if (memory != null && memory.columns != w) total += KEEP_WIDTH
            if (best == null || total < best.first - 1e-4f) best = total to solved
        }
        return best?.second?.toLayout(ids, groups)
    }

    // ---- groups and copy sets ------------------------------------------------------

    /** A group of the deck: its key and copy sets in order, and which positions hold it. */
    internal class Group(val key: String?, val sets: List<Pair<Int, Int>>, val positions: List<Int>) {
        val size: Int get() = positions.size
    }

    internal fun groupsOf(ids: List<Int>, keys: List<String?>, order: List<String>, setOrder: List<Int> = emptyList()): List<Group> {
        val placed = setOrder.withIndex().associate { it.value to it.index }
        val byKey = LinkedHashMap<String?, MutableList<Int>>()
        keys.indices.forEach { p -> byKey.getOrPut(keys[p]) { mutableListOf() } += p }
        val rank = order.withIndex().associate { it.value to it.index }
        val named = byKey.keys.filterNotNull().sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { byKey.getValue(it).first() }))
        // The cards in no group are a group too, last (kai).
        val all: List<String?> = named + (if (null in byKey) listOf(null) else emptyList())
        return all.map { key ->
                val positions = byKey.getValue(key)
                val counts = LinkedHashMap<Int, Int>()
                positions.forEach { counts[ids[it]] = (counts[ids[it]] ?: 0) + 1 }
                // The Fitted order first, where it names a card; the rest as the deck reads, after.
                val sets = counts.map { it.key to it.value }.withIndex()
                    .sortedWith(compareBy({ placed[it.value.first] ?: Int.MAX_VALUE }, { it.index }))
                    .map { it.value }
                Group(key, sets, positions)
            }
    }

    // ---- a group's block -------------------------------------------------------

    /** A block: its width and height, cells (row, column, card), empty cells, whether they only end its last line, moves. */
    internal class Block(val width: Int, val height: Int, val cells: List<Triple<Int, Int, Int>>, val holes: Int, val clean: Boolean, val moves: Int)

    /** The best block [width] wide for [sets], in order, or null when none is. */
    internal fun pack(sets: List<Pair<Int, Int>>, width: Int): Block? {
        val total = sets.sumOf { it.second }
        var best: Block? = null
        var bestKey = Triple(Int.MAX_VALUE, Float.MAX_VALUE, Int.MAX_VALUE)
        var leaves = 0
        val taken = HashSet<Long>()
        val cells = ArrayList<Triple<Int, Int, Int>>()
        fun k(r: Int, c: Int) = r.toLong() * 64 + c

        fun finish(moves: Int) {
            leaves++
            val h = cells.maxOf { it.first } + 1
            val occupied = cells.mapTo(HashSet()) { k(it.first, it.second) }
            val last = cells.filter { it.first == h - 1 }.map { it.second }.sorted()
            val clean = last == last.indices.toList() && (0 until h - 1).all { r -> (0 until width).all { c -> k(r, c) in occupied } }
            val holes = width * h - total
            val key = Triple(if (clean) 0 else 1, holes + moves * MOVE, h)
            if (key.first < bestKey.first || (key.first == bestKey.first && (key.second < bestKey.second - 1e-4f || (abs(key.second - bestKey.second) < 1e-4f && key.third < bestKey.third)))) {
                bestKey = key
                best = Block(width, h, cells.toList(), holes, clean, moves)
            }
        }

        fun go(remaining: List<Pair<Int, Int>>, moves: Int) {
            if (leaves > LEAF_LIMIT) return
            if (remaining.isEmpty()) {
                finish(moves)
                return
            }
            var r = 0
            var c: Int
            while (true) {
                c = (0 until width).firstOrNull { k(r, it) !in taken } ?: -1
                if (c >= 0) break
                r++
            }
            if (r >= MAX_ROWS) return
            var run = 0
            while (c + run < width && k(r, c + run) !in taken) run++

            fun options(n: Int): List<List<Pair<Int, Int>>> = buildList {
                if (n <= run) add((0 until n).map { r to c + it })
                if (n == 2 && r + 1 < MAX_ROWS && k(r + 1, c) !in taken) add(listOf(r to c, r + 1 to c))
            }

            fun place(idx: Int, spot: List<Pair<Int, Int>>, moved: Int) {
                val card = remaining[idx].first
                spot.forEach { taken += k(it.first, it.second); cells += Triple(it.first, it.second, card) }
                go(remaining.filterIndexed { i, _ -> i != idx }, moved)
                repeat(spot.size) { cells.removeAt(cells.size - 1) }
                spot.forEach { taken -= k(it.first, it.second) }
            }

            val next = options(remaining[0].second)
            next.forEach { place(0, it, moves) }
            if (next.isEmpty()) {
                // The next set does not fit here: the first later one that does moves up.
                for (idx in 1 until remaining.size) {
                    val opts = options(remaining[idx].second)
                    if (opts.isNotEmpty()) {
                        opts.forEach { place(idx, it, moves + 1) }
                        break
                    }
                }
                // …or the line ends here, its cells left empty.
                val skipped = (c until c + run).map { k(r, it) }
                taken += skipped
                go(remaining, moves)
                taken -= skipped.toSet()
            }
        }

        go(sets, 0)
        return best
    }

    private const val LEAF_LIMIT = 5000

    internal fun blockCost(b: Block, n: Int): Float {
        var cost = b.holes * 3f + (if (b.clean) 0f else 40f) + b.moves * MOVE
        cost += max(0, b.width - max(GLANCE, ceil(n / MAX_ROWS.toFloat()).toInt())) * 4f
        if (b.height == 1 && n > 3) cost += 2f
        cost += abs(b.width - b.height * 1.4f) * 0.3f
        return cost
    }

    // ---- the deck's bands ------------------------------------------------------

    internal class Placed(val group: Int, val block: Block)

    internal class Band(val height: Int, val stacks: List<List<Placed>>)

    internal class Solved(val cost: Float, val width: Int, val bands: List<Band>) {
        val rows: Int get() = bands.sumOf { it.height }

        /** The most gaps across any band (between its stacks), and down the deck (between bands and stacked blocks). */
        val spanX: Int get() = bands.maxOfOrNull { it.stacks.size - 1 } ?: 0
        val spanY: Int get() = bands.withIndex().maxOfOrNull { (i, b) -> i + (b.stacks.maxOfOrNull { it.size } ?: 1) - 1 } ?: 0

        fun toLayout(ids: List<Int>, groups: List<Group>): BandLayout {
            val row = IntArray(ids.size)
            val col = IntArray(ids.size)
            val blockOf = IntArray(ids.size)
            val blocks = mutableListOf<BandBlock>()
            var top = 0
            bands.forEachIndexed { bi, band ->
                var left = 0
                band.stacks.forEachIndexed { si, stack ->
                    var y = top
                    stack.forEachIndexed { above, placed ->
                        val group = groups[placed.group]
                        val b = placed.block
                        val index = blocks.size
                        blocks += BandBlock(group.key, y, left, b.width, b.height, bi, si, above)
                        // Each cell of a card goes to that card's next position in the deck.
                        val waiting = HashMap<Int, ArrayDeque<Int>>()
                        group.positions.forEach { p -> waiting.getOrPut(ids[p]) { ArrayDeque() }.addLast(p) }
                        b.cells.sortedWith(compareBy({ it.first }, { it.second })).forEach { (r, c, card) ->
                            val p = waiting.getValue(card).removeFirst()
                            row[p] = y + r
                            col[p] = left + c
                            blockOf[p] = index
                        }
                        y += b.height
                    }
                    left += stack.first().block.width
                }
                top += band.height
            }
            return BandLayout(width, top, row.toList(), col.toList(), blockOf.toList(), blocks)
        }
    }

    /**
     * Each group's best block at each width, packed once (1.0.92): every width the deck is
     * tried at asks for the same blocks again, and [pack] is a search.
     */
    internal class PackMemo(private val groups: List<Group>) {
        private val packs = HashMap<Long, Block?>()

        fun get(group: Int, width: Int): Block? {
            val key = (group.toLong() shl 32) or width.toLong()
            if (packs.containsKey(key)) return packs[key]
            return pack(groups[group].sets, width).also { packs[key] = it }
        }
    }

    /** [packs] is [pack] for group q at a width: packed afresh, or remembered ([PackMemo]). */
    private class Solver(val groups: List<Group>, val width: Int, val memory: BandMemory?, packs: (Int, Int) -> Block?) {
        val k = groups.size
        val options: List<List<Block>> = groups.mapIndexed { q, g ->
            val widest = g.sets.maxOf { it.second }
            (widest..min(g.size, width)).mapNotNull { packs(q, it) }
        }

        fun cost(q: Int, b: Block): Float {
            var c = blockCost(b, groups[q].size)
            val was = memory?.shapes?.get(groups[q].key ?: BandLayout.UNGROUPED)
            if (was != null && was != (b.width to b.height)) c += KEEP
            return c
        }

        private val memo = HashMap<Int, Pair<Float, List<Band>>>()

        fun solve(): Solved? {
            if (options.any { it.isEmpty() }) return null
            val (c, bands) = best(0)
            if (c == Float.MAX_VALUE) return null
            return Solved(c, width, bands)
        }

        private fun best(i: Int): Pair<Float, List<Band>> {
            if (i == k) return 0f to emptyList()
            memo[i]?.let { return it }
            var found: Pair<Float, List<Band>> = Float.MAX_VALUE to emptyList()
            for (h in 1..MAX_ROWS) {
                for ((cost0, stacks, j, used) in band(i, h)) {
                    var cost = cost0
                    val short = width - used
                    cost += if (j == k) short * h * 1.2f else short * h * 3f
                    val (rest, bands) = best(j)
                    if (rest == Float.MAX_VALUE) continue
                    val total = cost + rest + 1f
                    if (total < found.first - 1e-4f) found = total to (listOf(Band(h, stacks)) + bands)
                }
            }
            memo[i] = found
            return found
        }

        private data class Filled(val cost: Float, val stacks: List<List<Placed>>, val next: Int, val used: Int)

        private fun band(i: Int, h: Int): List<Filled> {
            val out = mutableListOf<Filled>()
            fun grow(pos: Int, used: Int, stacks: List<List<Placed>>, cost: Float) {
                if (stacks.isNotEmpty() && (pos == k || used == width)) {
                    out += Filled(cost, stacks, pos, used)
                    return
                }
                if (pos == k) return
                for (b in options[pos]) {
                    if (b.height > h || used + b.width > width) continue
                    val first = cost + cost(pos, b)
                    var height = b.height
                    val stack = mutableListOf(Placed(pos, b))
                    grow(pos + 1, used + b.width, stacks + listOf(stack.toList()), first + (h - height) * b.width * 2.5f)
                    // …or more blocks of the same width under it.
                    var q = pos + 1
                    var c = first
                    while (q < k && height < h) {
                        val fits = options[q].filter { it.width == b.width && height + it.height <= h }
                        val o = fits.minByOrNull { cost(q, it) } ?: break
                        stack += Placed(q, o)
                        c += cost(q, o)
                        height += o.height
                        q++
                        grow(q, used + b.width, stacks + listOf(stack.toList()), c + (h - height) * b.width * 2.5f)
                    }
                }
                // A band may close short here, when nothing else fits.
                if (stacks.isNotEmpty()) out += Filled(cost, stacks, pos, used)
            }
            grow(i, 0, emptyList(), 0f)
            return out
        }
    }
}
