package com.kaiharimoto.mastertool.core.layout

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The bands' memos (1.0.92) change nothing a deck looks like: a block packed once and
 * remembered for every width ([GroupBands.PackMemo]), and a deck's solutions kept from one
 * layout to the next while only the pane or the gaps move ([BandSolves]), give the very
 * layout the arithmetic gave packing and solving everything afresh.
 */
class GroupBandsMemoTest {

    private class Deck(val ids: List<Int>, val keys: List<String?>, val order: List<String>)

    private fun deck(vararg groups: Pair<String?, List<Pair<Int, Int>>>): Deck {
        val ids = mutableListOf<Int>()
        val keys = mutableListOf<String?>()
        groups.forEach { (key, sets) -> sets.forEach { (card, n) -> repeat(n) { ids += card; keys += key } } }
        return Deck(ids, keys, groups.mapNotNull { it.first })
    }

    private val a = deck(
        "Starters" to listOf(1 to 3, 2 to 3, 3 to 2, 4 to 1),
        "Extenders" to listOf(5 to 2, 6 to 2, 7 to 2, 8 to 1),
        "Handtraps" to listOf(9 to 3, 10 to 3, 11 to 2, 12 to 1),
        "Breakers" to listOf(13 to 2, 14 to 2, 15 to 1, 16 to 1),
        null to listOf(17 to 2, 18 to 1, 19 to 2, 20 to 2, 21 to 1, 22 to 1),
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

    /** The arithmetic as it was: every block packed afresh, nothing kept. */
    private fun fresh(
        d: Deck,
        pane: Pair<Float, Float>,
        others: Int = 0,
        gapX: Float = 0f,
        gapY: Float = 0f,
        memory: BandMemory? = null,
        widths: IntRange? = null,
        setOrder: List<Int> = emptyList(),
    ) = GroupBands.solveLayout(d.ids, d.keys, d.order, pane, others, 86f / 59f, memory, widths, gapX, gapY, setOrder, solves = null, packMemo = false)

    private val panes = listOf(1400f to 760f, 1000f to 900f, 1000f to 600f, 500f to 1400f, 2400f to 1200f)

    @Test
    fun aRememberedBlockIsTheBlockPackedAfresh() {
        listOf(a, big, singles).forEach { d ->
            val groups = GroupBands.groupsOf(d.ids, d.keys, d.order)
            val memo = GroupBands.PackMemo(groups)
            groups.forEachIndexed { q, g ->
                (1..18).forEach { w ->
                    // Asked twice: the second answer is the first, and both are the search's.
                    repeat(2) {
                        val kept = memo.get(q, w)
                        val packed = GroupBands.pack(g.sets, w)
                        assertEquals(packed == null, kept == null, "group $q at $w")
                        if (packed != null && kept != null) {
                            assertEquals(packed.width, kept.width)
                            assertEquals(packed.height, kept.height)
                            assertEquals(packed.cells, kept.cells)
                            assertEquals(packed.holes, kept.holes)
                            assertEquals(packed.clean, kept.clean)
                            assertEquals(packed.moves, kept.moves)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun theMemoisedLayoutIsTheLayoutWorkedOutAfresh() {
        listOf(a, big, singles).forEach { d ->
            panes.forEach { pane ->
                listOf(0, 2).forEach { others ->
                    listOf(0f to 0f, 26f to 58f).forEach { (gx, gy) ->
                        val was = fresh(d, pane, others, gx, gy)
                        assertNotNull(was)
                        assertEquals(was, GroupBands.layout(d.ids, d.keys, d.order, pane, others, gapX = gx, gapY = gy), "$pane, $others, $gx")
                        // With a memory and a Fitted order too.
                        val memory = was.memory
                        val setOrder = d.ids.distinct().reversed()
                        assertEquals(
                            fresh(d, pane, others, gx, gy, memory = memory, setOrder = setOrder),
                            GroupBands.layout(d.ids, d.keys, d.order, pane, others, memory = memory, gapX = gx, gapY = gy, setOrder = setOrder),
                        )
                        // One width, as a drag's preview asks.
                        assertEquals(
                            fresh(d, pane, others, gx, gy, memory = memory, widths = was.columns..was.columns),
                            GroupBands.layout(d.ids, d.keys, d.order, pane, others, memory = memory, widths = was.columns..was.columns, gapX = gx, gapY = gy),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun keptSolutionsGiveTheSameLayoutWhateverThePaneAndTheGaps() {
        val solves = BandSolves()
        val random = Random(92)
        // A pinch on the gaps, the window resized, an edit, the memory carried: one keeper throughout.
        var memory: BandMemory? = null
        listOf(a, big, a, singles, singles, big).forEach { d ->
            repeat(12) {
                val pane = panes.random(random)
                val others = random.nextInt(0, 3)
                val gx = random.nextFloat() * 60f
                val gy = random.nextFloat() * 90f
                val kept = GroupBands.layout(d.ids, d.keys, d.order, pane, others, memory = memory, gapX = gx, gapY = gy, solves = solves)
                assertEquals(fresh(d, pane, others, gx, gy, memory = memory), kept, "$pane, $others, $gx, $gy")
                // Now and then the memory moves on, as the builder's does after an edit.
                if (random.nextInt(4) == 0) memory = kept?.memory
            }
        }
    }

    @Test
    fun randomDecksComeOutTheSame() {
        val random = Random(1092)
        val solves = BandSolves()
        repeat(30) {
            val groups = (0 until random.nextInt(1, 8)).map { g ->
                val sets = (0 until random.nextInt(1, 7)).map { s -> (g * 20 + s) to listOf(1, 1, 2, 2, 3, 3, 3).random(random) }
                (if (g == 0 && random.nextBoolean()) null else "G$g") to sets
            }.distinctBy { it.first }
            val d = deck(*groups.toTypedArray())
            if (d.ids.size !in 1..60) return@repeat
            val pane = panes.random(random)
            val was = fresh(d, pane, 1, 20f, 40f)
            assertEquals(was, GroupBands.layout(d.ids, d.keys, d.order, pane, 1, gapX = 20f, gapY = 40f))
            assertEquals(was, GroupBands.layout(d.ids, d.keys, d.order, pane, 1, gapX = 20f, gapY = 40f, solves = solves))
        }
    }
}
