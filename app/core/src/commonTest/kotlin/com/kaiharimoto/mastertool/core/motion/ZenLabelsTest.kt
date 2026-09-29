package com.kaiharimoto.mastertool.core.motion

import com.kaiharimoto.mastertool.core.layout.GroupPieces
import com.kaiharimoto.mastertool.core.layout.LabelEdge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZenLabelsTest {

    private fun keys(vararg rows: String): List<String?> = rows.joinToString("").map { if (it == '.') null else it.toString() }

    @Test
    fun withNothingMovedTheNameStandsWhereTheBuilderPutsIt() {
        val k = keys("aab.", "aabb")
        val l = GroupPieces.of(k, 4)
        assertEquals(l.labelEdge(k, "a", 1.5f), ZenLabels.edge(l, k, "a", 1.5f) { false })
        assertEquals(LabelEdge(0, 2), ZenLabels.edge(l, k, "a", 1.5f) { false })
    }

    /** A card carried away takes no name with it: the rest of its piece keeps it. */
    @Test
    fun aCardCarriedAwayTakesTheNameOffItsSlot() {
        val k = keys("aab.", "aabb")
        val l = GroupPieces.of(k, 4)
        // The first card of the top edge carried off: the name stands on the one left beside it.
        assertEquals(LabelEdge(1, 1), ZenLabels.edge(l, k, "a", 1.5f) { it == 0 })
        // The whole top edge carried off: the cards under it still have their piece above
        // them, so no edge of the group is bare and it is not named.
        assertNull(ZenLabels.edge(l, k, "a", 1.5f) { it == 0 || it == 1 })
        // Every card of a group carried off: no name at all.
        assertNull(ZenLabels.edge(l, k, "b", 1f) { k[it] == "b" })
    }

    @Test
    fun theTabStandsInTheRoomOverItsEdge() {
        // Over the top of the deck: the table, so the whole tab.
        assertEquals(17f, ZenLabels.tab(row = 0, first = true, gap = 4f, between = 13f, tab = 17f, least = 8f))
        // Over the top of the extra deck: the paper between the sections.
        assertEquals(13f, ZenLabels.tab(row = 0, first = false, gap = 40f, between = 13f, tab = 17f, least = 8f))
        // Inside a section: the gap between the pieces, as wide as the wheel has made it.
        assertEquals(17f, ZenLabels.tab(row = 2, first = true, gap = 40f, between = 13f, tab = 17f, least = 8f))
        assertEquals(10f, ZenLabels.tab(row = 2, first = true, gap = 10f, between = 13f, tab = 17f, least = 8f))
        // Too little room to read: no tab.
        assertEquals(0f, ZenLabels.tab(row = 2, first = true, gap = 5f, between = 13f, tab = 17f, least = 8f))
        assertEquals(0f, ZenLabels.tab(row = 0, first = false, gap = 40f, between = 6f, tab = 17f, least = 8f))
    }
}
