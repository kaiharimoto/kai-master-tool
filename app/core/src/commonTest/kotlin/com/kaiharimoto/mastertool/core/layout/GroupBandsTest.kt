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
        assertEquals(14 to 3, la.columns to la.rows, "A: one band of five blocks")
        assertEquals(listOf(3, 3, 3, 2, 3), la.blocks.map { it.width })
        val lb = lay(b)
        holds(b, lb)
        assertEquals(11 to 4, lb.columns to lb.rows)
        listOf(big, singles).forEach { holds(it, lay(it)) }
    }

    @Test
    fun theWidthFollowsThePane() {
        val wide = lay(a)
        val upright = lay(a, phone)
        holds(a, upright)
        assertTrue(upright.columns < wide.columns, "a taller pane takes a narrower deck: ${upright.columns} vs ${wide.columns}")
    }

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
        edits.forEach { d ->
            val l = lay(d, memory = memory)
            holds(d, l)
            val changed = l.memory.shapes.count { (k, v) -> memory.shapes[k] != null && memory.shapes[k] != v }
            assertTrue(changed <= 1, "at most the edited block changes: $changed")
            assertEquals(memory.columns, l.columns, "the width holds")
            memory = l.memory
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
