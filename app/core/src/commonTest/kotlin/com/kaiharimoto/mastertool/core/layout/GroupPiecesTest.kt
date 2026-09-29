package com.kaiharimoto.mastertool.core.layout

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupPiecesTest {

    private fun keys(vararg rows: String): List<String?> = rows.joinToString("").map { if (it == '.') null else it.toString() }

    /** Where each card is drawn, in a grid of unit cards with gaps of [gap]. */
    private fun rects(layout: PieceLayout, gap: Float): List<FloatArray> = layout.piece.indices.map { p ->
        val x = layout.col(p) + layout.shiftX[p] * gap
        val y = (p / layout.columns) * 1.5f + layout.shiftY[p] * gap
        floatArrayOf(x, y, x + 1f, y + 1.5f)
    }

    /** kai's example: A and B flush, C and D flush, a gap between the two. */
    @Test
    fun cardsOfOneGroupAreFlushAndGroupsAreApart() {
        val l = GroupPieces.of(keys("aabb"), 4)
        assertEquals(listOf(0, 0, 1, 1), l.piece)
        assertEquals(listOf(0, 0, 1, 1), l.shiftX)
        assertEquals(1, l.spanX)
        assertEquals(0, l.spanY)
    }

    /** "If cards are in the same group across rows, have them be close together with no gap vertically." */
    @Test
    fun aGroupAcrossRowsStaysFlushAndAligned() {
        // A run of a's wrapping from the end of row 0 onto the start of row 1, under itself.
        val l = GroupPieces.of(keys("bbaa", "aaac"), 4)
        val a = listOf(2, 3, 4, 5, 6)
        assertEquals(1, a.map { l.piece[it] }.distinct().size, "one piece")
        assertEquals(1, a.map { l.shiftX[it] }.distinct().size, "moved as one: flush and aligned")
        assertEquals(1, a.map { l.shiftY[it] }.distinct().size)
        // b sits over part of the a piece, so the a piece is a gap lower; c is a gap right of it.
        assertTrue(l.shiftY[4] > l.shiftY[0])
        assertTrue(l.shiftX[7] > l.shiftX[6])
    }

    @Test
    fun cardsInNoGroupAreFlushWithEachOther() {
        val l = GroupPieces.of(keys("..a.", "...."), 4)
        assertEquals(l.piece[0], l.piece[1])
        assertEquals(l.piece[0], l.piece[4])
        // The one grouped card is its own piece, a gap from its neighbours on both sides.
        assertTrue(l.shiftX[2] > l.shiftX[1] && l.shiftX[3] > l.shiftX[2])
    }

    @Test
    fun withNoGroupsTheDeckIsOnePlainGrid() {
        val l = GroupPieces.of(List(40) { null }, 10)
        assertEquals(1, l.pieces)
        assertEquals(0, l.spanX)
        assertEquals(0, l.spanY)
    }

    @Test
    fun theArmThatWouldCloseAUStaysApart() {
        // a is a U round b: a is left and right of b in row 1, so no order can put b right of a.
        val l = GroupPieces.of(keys("aaa", "aba"), 3)
        assertTrue(l.shiftX[4] > l.shiftX[3] && l.shiftX[5] > l.shiftX[4])
        assertTrue(l.shiftY[4] > l.shiftY[1])
    }

    @Test
    fun theOutlineRunsWhereAPieceEnds() {
        val l = GroupPieces.of(keys("aab", "abb"), 3)
        // Card 0: left and top are the grid's edge; right is a; below is a.
        assertEquals(listOf(true, true, false, false), l.outerSides(0).toList())
        // Card 1: right is b, below is b.
        assertEquals(listOf(false, true, true, true), l.outerSides(1).toList())
    }

    /** Whatever the deck, no two different pieces touch along a side, and no two cards overlap. */
    @Test
    fun differentPiecesNeverTouchAndCardsNeverOverlap() {
        val random = Random(7)
        repeat(400) {
            val columns = if (random.nextBoolean()) 10 else 15
            val n = random.nextInt(1, 61)
            val groups = random.nextInt(1, 6)
            // Runs, as a deck sorted by group reads, with some scatter.
            val k: List<String?> = buildList {
                while (size < n) {
                    val g = random.nextInt(groups + 1)
                    val len = random.nextInt(1, 9)
                    repeat(len) { if (size < n) add(if (g == groups) null else "g$g") }
                }
            }
            val l = GroupPieces.of(k, columns)
            val gap = 0.25f
            val r = rects(l, gap)
            for (p in k.indices) {
                // Flush inside a piece, at least a gap between pieces, along rows and columns —
                // between the cards that stand beside each other, which in a last row slid under
                // its groups (1.0.33) are not always the next index.
                l.at(l.row(p), l.col(p) + 1)?.let { q ->
                    val d = r[q][0] - r[p][2]
                    if (l.piece[p] == l.piece[q]) assertEquals(0f, d, 1e-4f) else assertTrue(d >= gap - 1e-4f, "row gap $d")
                }
                l.at(l.row(p) + 1, l.col(p))?.let { q ->
                    val d = r[q][1] - r[p][3]
                    if (l.piece[p] == l.piece[q]) {
                        assertEquals(0f, d, 1e-4f)
                        assertEquals(r[p][0], r[q][0], 1e-4f)
                    } else {
                        assertTrue(d >= gap - 1e-4f, "column gap $d")
                    }
                }
                // Every card stands in its row, in reading order.
                if (p > 0 && l.row(p - 1) == l.row(p)) assertTrue(l.col(p - 1) < l.col(p), "order in $k")
            }
            for (a in k.indices) for (b in a + 1 until n) {
                val overlap = r[a][0] < r[b][2] - 1e-4f && r[b][0] < r[a][2] - 1e-4f && r[a][1] < r[b][3] - 1e-4f && r[b][1] < r[a][3] - 1e-4f
                assertTrue(!overlap, "cards $a and $b overlap in $k")
            }
        }
    }

    /** kai's example: one card of a group in a row, three under it; the name goes over the three, not the one. */
    @Test
    fun aNameStandsOnTheLongestEdgeOfItsPiece() {
        val k = keys("..a..", "..aaa")
        val l = GroupPieces.of(k, 5)
        // The lone card is one card wide; under it, the two cards with nothing of theirs above.
        assertEquals(LabelEdge(8, 2), l.labelEdge(k, "a", need = 1.8f))
    }

    @Test
    fun aNameThatFitsStaysAtTheTopLeft() {
        val k = keys("..a..", "..aaa")
        val l = GroupPieces.of(k, 5)
        assertEquals(LabelEdge(2, 1), l.labelEdge(k, "a", need = 0.8f))
    }

    @Test
    fun aNameGoesToTheLargestPieceWhenEdgesTie() {
        val k = keys("a.aa.", "...a.")
        val l = GroupPieces.of(k, 5)
        assertEquals(LabelEdge(2, 2), l.labelEdge(k, "a", need = 1.5f))
        // A name that fits on one card still prefers the larger piece.
        assertEquals(LabelEdge(2, 2), l.labelEdge(k, "a", need = 0.5f))
    }

    @Test
    fun anEdgeUnderItsOwnPieceIsNoPlaceForAName() {
        // Row 1's a's are all under row 0's, so the only edge is row 0's.
        val k = keys(".aaa.", ".aaa.", ".aaaa")
        val l = GroupPieces.of(k, 5)
        assertEquals(LabelEdge(1, 3), l.labelEdge(k, "a", need = 5f))
        assertEquals(null, l.labelEdge(k, "b", need = 1f))
    }
}
