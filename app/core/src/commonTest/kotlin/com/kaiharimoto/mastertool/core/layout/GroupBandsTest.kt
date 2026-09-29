package com.kaiharimoto.mastertool.core.layout

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupBandsTest {

    /** A deck as its list reads: groups (null for none) of copy sets (card, copies). */
    private class Deck(val ids: List<Int>, val keys: List<String?>, val order: List<String>)

    private fun deck(vararg groups: Pair<String?, List<Pair<Int, Int>>>): Deck {
        val ids = mutableListOf<Int>()
        val keys = mutableListOf<String?>()
        groups.forEach { (key, sets) -> sets.forEach { (card, n) -> repeat(n) { ids += card; keys += key } } }
        return Deck(ids, keys, groups.mapNotNull { it.first })
    }

    // The decks of the design run (1.0.37): kai's samples A and B, a 56-card pile, and one of lone cards.
    private val a = deck(
        "Starters" to listOf(1 to 3, 2 to 3, 3 to 2, 4 to 1),
        "Extenders" to listOf(5 to 2, 6 to 2, 7 to 2, 8 to 1),
        "Handtraps" to listOf(9 to 3, 10 to 3, 11 to 2, 12 to 1),
        "Breakers" to listOf(13 to 2, 14 to 2, 15 to 1, 16 to 1),
        null to listOf(17 to 2, 18 to 1, 19 to 2, 20 to 2, 21 to 1, 22 to 1),
    )
    private val b = deck(
        "Engine" to listOf(1 to 3, 2 to 3, 3 to 3, 4 to 2, 5 to 2, 6 to 1),
        "Traps" to listOf(7 to 3, 8 to 2, 9 to 2, 10 to 1),
        "Handtraps" to listOf(11 to 3, 12 to 3, 13 to 3),
        "Breakers" to listOf(14 to 1, 15 to 2),
        null to listOf(16 to 3, 17 to 1, 18 to 1, 19 to 2),
    )
    private val big = deck(
        "Engine" to listOf(1 to 3, 2 to 3, 3 to 3, 4 to 2, 5 to 3, 6 to 2, 7 to 3, 8 to 1),
        "Extenders" to listOf(9 to 3, 10 to 2, 11 to 1, 12 to 1, 13 to 1),
        "Handtraps" to listOf(14 to 3, 15 to 3, 16 to 2, 17 to 3, 18 to 2),
        "Breakers" to listOf(19 to 2, 20 to 1, 21 to 2, 22 to 1),
        null to listOf(23 to 2, 24 to 1, 25 to 1, 26 to 2, 27 to 1, 28 to 1, 29 to 1),
    )
    private val singles = deck(
        "Engine" to (1..12).map { it to 1 } + (13 to 3),
        "Handtraps" to listOf(20 to 3, 21 to 3, 22 to 2, 23 to 1, 24 to 1),
        null to (30..44).map { it to 1 },
    )

    private val desk = 1400f to 760f
    private val phone = 1000f to 900f

    private fun lay(d: Deck, pane: Pair<Float, Float> = desk, memory: BandMemory? = null) =
        GroupBands.layout(d.ids, d.keys, d.order, pane, memory = memory)!!

    /** Everything kai asked of the layout, for any deck. */
    private fun holds(d: Deck, l: BandLayout) {
        val n = d.ids.size
        // Every card in a cell of its own, inside the grid.
        val cells = (0 until n).map { l.row[it] to l.col[it] }
        assertEquals(n, cells.toSet().size, "one card a cell")
        assertTrue(cells.all { (r, c) -> r in 0 until l.rows && c in 0 until l.columns })
        // A copy set is one strip: a 3-of across, a 2-of across or standing, never broken.
        d.ids.distinct().forEach { card ->
            val at = (0 until n).filter { d.ids[it] == card }.map { l.row[it] to l.col[it] }.sortedWith(compareBy({ it.first }, { it.second }))
            when (at.size) {
                3 -> assertTrue(at.all { it.first == at[0].first } && at[2].second - at[0].second == 2, "3-of $card is a strip: $at")
                2 -> assertTrue(
                    (at[0].first == at[1].first && at[1].second - at[0].second == 1) || (at[0].second == at[1].second && at[1].first - at[0].first == 1),
                    "2-of $card together: $at",
                )
            }
        }
        // Every group one block, in its rectangle, no taller than four rows, the blocks not overlapping.
        l.blocks.forEachIndexed { i, block ->
            assertTrue(block.height <= GroupBands.MAX_ROWS, "block ${block.key} is ${block.height} tall")
            val mine = (0 until n).filter { l.block[it] == i }
            assertTrue(mine.all { d.keys[it] == block.key }, "a block is one group's")
            assertTrue(mine.all { l.row[it] in block.row until block.row + block.height && l.col[it] in block.col until block.col + block.width })
        }
        assertEquals(d.keys.distinct().size, l.blocks.size, "one block a group")
        // Groups keep their order: the blocks in reading order of band, stack, place in the stack.
        val order = d.order
        val named = l.blocks.filter { it.key != null }.sortedWith(compareBy({ it.band }, { it.stack }, { it.above })).map { it.key }
        assertEquals(order.filter { it in d.keys }, named)
        // The cards in no group come last, read band by band, stack by stack, top to bottom.
        val read = l.blocks.sortedWith(compareBy({ it.band }, { it.stack }, { it.above }))
        if (null in d.keys) assertEquals(null, read.last().key, "the cards in no group are last")
    }

    @Test
    fun theDesignRunsDecksComeOutAsTheyWereChosen() {
        val la = lay(a)
        holds(a, la)
        // Its three named groups the squares the design run chose; the deck no longer fourteen
        // across, which left every card a fifth smaller than the plain deck's (1.0.38).
        assertTrue(la.blocks.take(3).all { it.width == 3 && it.height == 3 }, "A: ${la.blocks}")
        assertTrue(la.columns <= 12, "A: ${la.columns}x${la.rows}")
        val lb = lay(b)
        holds(b, lb)
        assertEquals(11 to 4, lb.columns to lb.rows)
        listOf(big, singles).forEach { holds(it, lay(it)) }
    }

    @Test
    fun theWidthFollowsThePane() {
        val wide = lay(a, 2000f to 500f)
        val upright = lay(a, 800f to 1400f)
        holds(a, upright)
        assertTrue(upright.columns < wide.columns, "a taller pane takes a narrower deck: ${upright.columns} vs ${wide.columns}")
    }

    // kai's Angelechy Labrynth (1.0.38), which on a phone upright came out six wide and seven
    // rows tall, every card smaller than As is and the side deck crushed under it.
    private val angelechy = deck(
        "Combo" to listOf(1 to 3, 2 to 3, 3 to 3, 4 to 1),
        "Angelechy" to listOf(5 to 3, 6 to 3, 7 to 3),
        "Non-engine" to listOf(8 to 3, 9 to 3, 10 to 3),
        "Breakers" to listOf(11 to 2, 12 to 2),
        "Requirements" to listOf(13 to 2, 14 to 1, 15 to 1, 16 to 2, 17 to 1, 18 to 1),
    )

    /** The card a layout leaves room for, as the chooser works it out. */
    private fun cardIn(l: BandLayout, pane: Pair<Float, Float>, others: Int, gapX: Float, gapY: Float): Float {
        val pieces = l.pieces()
        val aspect = 86f / 59f
        return minOf(
            (pane.first - pieces.spanX * gapX) / l.columns,
            (pane.second - pieces.spanY * gapY) / (l.rows * aspect + others * aspect * l.columns / 15f),
        )
    }

    @Test
    fun fittedCardsAreNeverMuchSmallerThanAsIs() {
        // A phone upright at 2.625 dp to the pixel, the extra and side decks under the deck, and
        // desks of two shapes: the fitted cards are at least nine tenths of the plain deck's.
        val cases = listOf(
            Triple(1000f to 600f, 26f, 58f), Triple(1400f to 760f, 10f, 22f), Triple(1100f to 1000f, 10f, 22f),
            // The desk's deck column between the pool and the Groups panel.
            Triple(750f to 870f, 10f, 22f),
        )
        listOf(a, b, big, singles, angelechy).forEach { d ->
            cases.forEach { (pane, gx, gy) ->
                listOf(0, 2).forEach { others ->
                    val l = GroupBands.layout(d.ids, d.keys, d.order, pane, others, gapX = gx, gapY = gy)!!
                    holds(d, l)
                    val rows = (maxOf(d.ids.size, 40) + 9) / 10
                    val aspect = 86f / 59f
                    val plain = minOf(pane.first / 10, pane.second / (rows * aspect + others * aspect * 10 / 15f))
                    val card = cardIn(l, pane, others, gx, gy)
                    // Held to the plain deck, or, where no bands can reach it, to the best they can do.
                    val best = (6..17).mapNotNull { w ->
                        GroupBands.layout(d.ids, d.keys, d.order, pane, others, widths = w..w, gapX = gx, gapY = gy)?.let { cardIn(it, pane, others, gx, gy) }
                    }.max()
                    // With the extra and side decks drawn at its width, the deck is nearly as wide as the plain one.
                    if (others > 0) assertTrue(card * l.columns + l.pieces().spanX * gx >= 0.85f * plain * 10, "${l.columns}x${l.rows} in $pane is narrow")
                    assertTrue(card >= 0.85f * minOf(plain, best), "${l.columns}x${l.rows} in $pane with $others: $card against the plain $plain, the best $best")
                }
            }
        }
        // And the phone's case itself is a full-width deck again.
        val phone = GroupBands.layout(angelechy.ids, angelechy.keys, angelechy.order, 1000f to 600f, 2, gapX = 26f, gapY = 58f)!!
        assertTrue(phone.columns >= 9, "the phone's deck is ${phone.columns} wide")
    }

    @Test
    fun theMemoryOfANarrowDeckDoesNotHoldIt() {
        // Laid out once in a narrow pane, the deck is not kept narrow in a wide one by its memory.
        val narrow = GroupBands.layout(angelechy.ids, angelechy.keys, angelechy.order, 500f to 1400f)!!
        val l = GroupBands.layout(angelechy.ids, angelechy.keys, angelechy.order, 1000f to 600f, 2, memory = narrow.memory, gapX = 26f, gapY = 58f)!!
        assertTrue(l.columns >= 9, "held at ${l.columns} wide by the memory of ${narrow.columns}")
    }

    /** The card a layout leaves room for in the desk's pane, with no gaps. */
    private fun card(l: BandLayout) = minOf(desk.first / l.columns, desk.second / (l.rows * 86f / 59f))

    @Test
    fun anEditDoesNotReshuffleTheDeck() {
        var memory = lay(a).memory
        // One card at a time, each edit laid out knowing the last.
        val edits = listOf(
            deck(
                "Starters" to listOf(1 to 3, 2 to 3, 3 to 2, 4 to 1),
                "Extenders" to listOf(5 to 2, 6 to 2, 7 to 2, 8 to 2),
                "Handtraps" to listOf(9 to 3, 10 to 3, 11 to 2, 12 to 1),
                "Breakers" to listOf(13 to 2, 14 to 2, 15 to 1, 16 to 1),
                null to listOf(17 to 2, 18 to 1, 19 to 2, 20 to 2, 21 to 1, 22 to 1),
            ),
            deck(
                "Starters" to listOf(1 to 3, 2 to 3, 3 to 2, 4 to 1),
                "Extenders" to listOf(5 to 2, 6 to 2, 7 to 2, 8 to 2),
                "Handtraps" to listOf(9 to 3, 10 to 3, 11 to 2),
                "Breakers" to listOf(13 to 2, 14 to 2, 15 to 1, 16 to 1),
                null to listOf(17 to 2, 18 to 1, 19 to 2, 20 to 2, 21 to 1, 22 to 1),
            ),
        )
        var before = lay(a)
        edits.forEach { d ->
            val l = lay(d, memory = memory)
            holds(d, l)
            // An edit keeps the deck as it was — unless it lets the cards grow by a tenth or more
            // (1.0.38): space comes first, and a reshuffle has to buy some.
            val grew = card(l) >= card(before) * 1.1f
            val changed = l.memory.shapes.count { (k, v) -> memory.shapes[k] != null && memory.shapes[k] != v }
            assertTrue(changed <= 1 || grew, "at most the edited block changes: $changed")
            if (!grew) assertEquals(memory.columns, l.columns, "the width holds")
            memory = l.memory
            before = l
        }
    }

    @Test
    fun aDeckInNoGroupsOrOneIsStillLaidOut() {
        val plain = deck(null to (1..40).map { it to 1 })
        holds(plain, lay(plain))
        val one = deck("All" to (1..13).map { it to 3 } + (14 to 1))
        holds(one, lay(one))
        assertEquals(null, GroupBands.layout(emptyList(), emptyList(), emptyList(), desk))
    }

    @Test
    fun randomDecksKeepEveryRule() {
        val random = Random(37)
        repeat(40) {
            val groups = (0 until random.nextInt(1, 8)).map { g ->
                val sets = (0 until random.nextInt(1, 7)).map { s -> (g * 20 + s) to listOf(1, 1, 2, 2, 3, 3, 3).random(random) }
                (if (g == 0 && random.nextBoolean()) null else "G$g") to sets
            }.distinctBy { it.first }
            val d = deck(*groups.toTypedArray())
            if (d.ids.size !in 1..60) return@repeat
            holds(d, lay(d, if (random.nextBoolean()) desk else phone))
        }
    }

    @Test
    fun asPiecesEveryBlockIsOnePieceAndTheGapsFollowTheBands() {
        val l = lay(b)
        val pieces = l.pieces()
        assertEquals(l.blocks.size, pieces.pieces)
        (b.ids.indices).forEach { p ->
            assertEquals(l.row[p], pieces.row(p))
            assertEquals(l.col[p], pieces.col(p))
            val block = l.blocks[l.block[p]]
            assertEquals(block.stack, pieces.shiftX[p])
            assertEquals(block.band + block.above, pieces.shiftY[p])
        }
        assertEquals(l.rows, pieces.rowCount)
    }
}
