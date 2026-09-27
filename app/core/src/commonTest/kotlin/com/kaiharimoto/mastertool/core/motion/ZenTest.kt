package com.kaiharimoto.mastertool.core.motion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZenTest {

    @Test
    fun thePhasesAreKaisThreeAndTenSeconds() {
        assertEquals(ZenPhase.AWAKE, ZenClock.phase(2_999))
        assertEquals(ZenPhase.QUIET, ZenClock.phase(3_000))
        assertEquals(ZenPhase.QUIET, ZenClock.phase(9_999))
        assertEquals(ZenPhase.DEEP, ZenClock.phase(10_000))
        assertEquals(3_000L, ZenClock.untilNext(0))
        assertEquals(6_000L, ZenClock.untilNext(4_000))
        assertNull(ZenClock.untilNext(12_000))
    }

    @Test
    fun theStagePutsTheDeckInTheMiddleWithRoomRoundIt() {
        val stage = ZenStage.of(deckLeft = 400f, deckTop = 100f, deckWidth = 1000f, deckHeight = 800f, windowWidth = 1920f, windowHeight = 1080f)
        val (cx, cy) = stage.apply(900f, 500f, 900f, 500f, 1f)
        assertEquals(960f, cx, 0.5f)
        assertEquals(540f, cy, 0.5f)
        // The deck takes no more than its share of the width, or of the height.
        assertTrue(1000f * stage.scale <= 1920f * 0.72f + 0.5f)
        assertTrue(800f * stage.scale <= 1080f * 0.8f + 0.5f)
        // At zero it is where it was.
        assertEquals(400f to 100f, stage.apply(400f, 100f, 900f, 500f, 0f))
    }

    @Test
    fun theFloatIsSmallDeterministicAndOutOfStep() {
        for (i in 0 until 60) for (step in 0 until 200) {
            val p = ZenFloat.pose(i, step * 0.137f)
            assertTrue(abs(p.dx) <= ZenFloat.DRIFT + 1e-4f && abs(p.dy) <= ZenFloat.DRIFT + 1e-4f)
            assertTrue(abs(p.spin) <= ZenFloat.SPIN + 1e-4f)
            assertTrue(abs(p.rotationX) <= ZenFloat.LEAN + 1e-3f && abs(p.rotationY) <= ZenFloat.LEAN + 1e-3f)
        }
        assertEquals(ZenFloat.pose(7, 3.3f), ZenFloat.pose(7, 3.3f))
        assertNotEquals(ZenFloat.pose(7, 3.3f), ZenFloat.pose(8, 3.3f))
        // Continuous: a frame later is barely anywhere else.
        val a = ZenFloat.pose(3, 5f)
        val b = ZenFloat.pose(3, 5f + 1f / 60f)
        assertTrue(abs(a.rotationX - b.rotationX) < 0.2f && abs(a.dx - b.dx) < 0.002f)
    }

    @Test
    fun theDreamyLeanIsWiderAndGentler() {
        // Two cards away the builder's bump has all but gone; zen's has not.
        assertTrue(abs(DeskLean.dreamy(2f, 0f).rotationY) > abs(DeskLean.toward(2f, 0f).rotationY))
        assertTrue(abs(DeskLean.dreamy(0.45f, 0f).rotationY) < abs(DeskLean.toward(0.45f, 0f).rotationY))
    }

    @Test
    fun anArrangementIsOffsetsThatAddUpAndReset() {
        val a = ZenArrangement()
        assertTrue(a.isEmpty)
        val k = ZenArrangement.key(section = 0, index = 7)
        a.move(k, 10f, -4f)
        a.move(k, 5f, 4f)
        assertEquals(15f to 0f, a.offsetOf(k))
        assertEquals(0f to 0f, a.offsetOf(ZenArrangement.key(1, 7)))
        val before = a.version
        a.reset()
        assertTrue(a.isEmpty && a.version > before)
        assertEquals(0f to 0f, a.offsetOf(k))
        assertEquals(0, a.layerOf(k))
        // A card put down lands on top of what it is put on.
        val first = ZenArrangement.key(0, 1)
        val second = ZenArrangement.key(0, 2)
        a.move(second, 1f, 1f)
        a.move(first, 1f, 1f)
        assertTrue(a.layerOf(first) > a.layerOf(second) && a.layerOf(second) > 0)
        // Keys do not collide between sections of a legal deck.
        assertNotEquals(ZenArrangement.key(0, 59), ZenArrangement.key(1, 0))
    }

    @Test
    fun aHigherCardCastsAFurtherSofterFainterShadow() {
        val low = ZenShadow.of(ZenShadow.REST_LIFT)
        val high = ZenShadow.of(0.12f)
        assertTrue(high.dy > low.dy && high.dx > low.dx)
        assertTrue(high.blur > low.blur)
        assertTrue(high.alpha < low.alpha)
        // Down and to the right: the light is up and to the left.
        assertTrue(low.dx > 0f && low.dy > 0f)
    }

    @Test
    fun theResetCornerIsTheBottomRightOnly() {
        assertTrue(ZenCorner.reaches(1900f, 1070f, 1920f, 1080f))
        assertTrue(!ZenCorner.reaches(1900f, 500f, 1920f, 1080f))
        assertTrue(!ZenCorner.reaches(100f, 1070f, 1920f, 1080f))
    }

    @Test
    fun aBlockFloatsAsOneAndItsScalesRunDiagonally() {
        for (step in 0 until 200) {
            val t = step * 0.137f
            // Every card of a block drifts the same way, so the block keeps its shape.
            val a = ZenFloat.inBlock(4, 0, 0, t)
            val b = ZenFloat.inBlock(4, 5, 2, t)
            assertEquals(a.dx, b.dx, 1e-6f)
            assertEquals(a.dy, b.dy, 1e-6f)
            assertEquals(0f, a.spin)
            // Cards on one diagonal flutter together; the next diagonal a step behind.
            assertEquals(ZenFloat.scales(3, 1, t), ZenFloat.scales(2, 2, t))
            val f = ZenFloat.scales(0, 0, t)
            assertTrue(abs(f.rotationX) <= ZenFloat.SCALE_LEAN + 1e-4f)
            // Leaning about the diagonal: the two axes move against each other.
            assertEquals(f.rotationX, -f.rotationY, 1e-6f)
        }
        assertNotEquals(ZenFloat.scales(0, 0, 1f), ZenFloat.scales(1, 0, 1f))
        // Two blocks do not drift in step.
        assertNotEquals(ZenFloat.group(1, 2f), ZenFloat.group(2, 2f))
    }

    @Test
    fun aCardPutDownHasLeftItsBlock() {
        val a = ZenArrangement()
        val k = ZenArrangement.key(0, 3)
        assertTrue(!a.isMoved(k))
        a.move(k, 4f, 0f)
        assertTrue(a.isMoved(k))
        a.reset()
        assertTrue(!a.isMoved(k))
    }

    private fun homes(): Map<Int, ZenHome> {
        // Two blocks of two cards, 100 × 146, side by side: block 1 at x 0 and 100, block 2 at 300 and 400.
        fun at(x: Float, group: Int, col: Int) = ZenHome(x, 0f, 100f, 146f, ZenMembership(group, col, 0))
        return mapOf(
            ZenArrangement.key(0, 0) to at(0f, 1, 0),
            ZenArrangement.key(0, 1) to at(100f, 1, 1),
            ZenArrangement.key(0, 2) to at(300f, 2, 0),
            ZenArrangement.key(0, 3) to at(400f, 2, 1),
        )
    }

    @Test
    fun aCardLetGoNearItsSlotGoesBackIn() {
        val a = ZenArrangement()
        val k = ZenArrangement.key(0, 0)
        a.move(k, 20f, 10f)
        assertEquals(ZenSnap.Result.Home, a.drop(k, homes()))
        assertTrue(!a.isMoved(k))
        assertEquals(0, a.layerOf(k))
    }

    @Test
    fun aCardLetGoBesideAnotherSnapsFlushAndJoinsItsBlock() {
        val a = ZenArrangement()
        val k = ZenArrangement.key(0, 0)
        // Carried to just right of card 3 (whose right edge is at 500): lands at 510.
        a.move(k, 510f, 8f)
        val r = a.drop(k, homes())
        assertTrue(r is ZenSnap.Result.Beside)
        assertEquals(500f to 0f, a.offsetOf(k)) // flush: home 0 + 500
        val m = a.membershipOf(k, ZenMembership(1, 0, 0))
        assertEquals(ZenMembership(2, 2, 0), m) // block 2, one cell right of card 3
    }

    @Test
    fun aSlotAlreadyFilledIsSkipped() {
        val a = ZenArrangement()
        val k = ZenArrangement.key(0, 3)
        // Right of card 1 (x 200) — empty; left of card 2 would be 200 too. Both fine; now fill it.
        a.move(ZenArrangement.key(0, 2), -100f, 0f) // card 2 now sits at 200
        a.drop(ZenArrangement.key(0, 2), homes())
        a.move(k, -200f, 0f) // card 3 carried to 200, onto card 2
        val r = a.drop(k, homes())
        assertTrue(r !is ZenSnap.Result.Beside || (r.dx + 400f) != 200f)
    }

    @Test
    fun aCardLetGoInTheOpenStaysAndKeepsItsBlock() {
        val a = ZenArrangement()
        val k = ZenArrangement.key(0, 0)
        a.move(k, 0f, 900f)
        assertEquals(ZenSnap.Result.Free, a.drop(k, homes()))
        assertTrue(a.isMoved(k))
        assertEquals(ZenMembership(1, 0, 0), a.membershipOf(k, ZenMembership(1, 0, 0)))
    }

    // ---- 1.0.14: many cards at once --------------------------------------

    private val b0 = ZenArrangement.key(0, 0)
    private val b1 = ZenArrangement.key(0, 1)
    private val c0 = ZenArrangement.key(0, 2)
    private val c1 = ZenArrangement.key(0, 3)

    @Test
    fun aGroupMovesAsOneAndComesUpInTheOrderItLay() {
        val a = ZenArrangement()
        a.move(b1, 0f, 0f) // b1 above b0 before anything else
        a.moveAll(listOf(b0, b1), 30f, 40f)
        assertEquals(30f to 40f, a.offsetOf(b0))
        assertEquals(30f to 40f, a.offsetOf(b1))
        assertTrue(a.layerOf(b1) > a.layerOf(b0), "picking a group up does not reshuffle it")
        assertTrue(a.layerOf(b0) > 0 && a.layerOf(c0) == 0)
    }

    @Test
    fun aGroupLetGoNearHomeGoesHomeTogether() {
        val a = ZenArrangement()
        a.moveAll(listOf(b0, b1), 12f, -9f)
        assertEquals(ZenSnap.GroupResult.Home, a.dropAll(listOf(b0, b1), b0, homes()))
        assertTrue(!a.isMoved(b0) && !a.isMoved(b1))
    }

    @Test
    fun aGroupPutAgainstAnotherSnapsFlushAndCombines() {
        val a = ZenArrangement()
        // Block 1 (x 0 and 100) carried to just right of block 2's last card (right edge 500).
        a.moveAll(listOf(b0, b1), 506f, 7f)
        val r = a.dropAll(listOf(b0, b1), b0, homes())
        assertTrue(r is ZenSnap.GroupResult.Beside, "$r")
        assertEquals(500f to 0f, a.offsetOf(b0)) // flush at 500, and its partner beside it at 600
        assertEquals(500f to 0f, a.offsetOf(b1))
        // Both now float with block 2, one and two cells right of its last card.
        assertEquals(ZenMembership(2, 2, 0), a.membershipOf(b0, ZenMembership(1, 0, 0)))
        assertEquals(ZenMembership(2, 3, 0), a.membershipOf(b1, ZenMembership(1, 1, 0)))
        assertEquals(setOf(b0, b1, c0, c1), a.blockOf(c0, homes()))
    }

    @Test
    fun aGroupThatWouldLandOnACardDoesNotSnapThere() {
        val a = ZenArrangement()
        // Block 1 carried so its first card is just left of card c0 (x 300): landing at 200
        // would put its second card at 300, on top of c0.
        a.moveAll(listOf(b0, b1), 204f, 0f)
        val r = a.dropAll(listOf(b0, b1), b0, homes())
        if (r is ZenSnap.GroupResult.Beside) {
            val (x0, _) = a.offsetOf(b0)
            val (x1, _) = a.offsetOf(b1)
            val lefts = listOf(0f + x0, 100f + x1)
            assertTrue(lefts.none { abs(it - 300f) < 50f || abs(it - 400f) < 50f }, "landed on a card: $lefts")
        }
    }

    @Test
    fun cardsGatheredFromTwoBlocksAndLeftInTheOpenBecomeOneBlock() {
        val a = ZenArrangement()
        // One card from each block, carried far below everything, side by side.
        a.move(c0, -100f, 0f) // c0 beside b1, at x 200
        a.moveAll(listOf(b1, c0), 0f, 900f)
        val r = a.dropAll(listOf(b1, c0), b1, homes())
        assertTrue(r is ZenSnap.GroupResult.Free, "$r")
        val m1 = a.membershipOf(b1, ZenMembership(1, 1, 0))
        val m2 = a.membershipOf(c0, ZenMembership(2, 0, 0))
        assertEquals(m1.group, m2.group)
        assertEquals(1, m2.col - m1.col) // measured from the card it was carried by
        assertEquals(setOf(b0, b1, c0), a.blockOf(b1, homes()))
    }

    @Test
    fun aGroupOfOneIsLetGoLikeOneCard() {
        val a = ZenArrangement()
        a.moveAll(listOf(b0), 0f, 900f)
        assertTrue(a.dropAll(listOf(b0), b0, homes()) is ZenSnap.GroupResult.Free)
        assertEquals(ZenMembership(1, 0, 0), a.membershipOf(b0, ZenMembership(1, 0, 0)))
    }

    @Test
    fun aBoxTouchesWhatItCoversAndAPointFindsTheTopCard() {
        val a = ZenArrangement()
        val stage = ZenStage.NONE
        // A box over the right half of b1 and the gap: b1 alone.
        assertEquals(setOf(b1), ZenPick.within(150f, 10f, 250f, 60f, homes(), a, stage, 0f, 0f))
        // Dragged the other way, the same box.
        assertEquals(setOf(b1), ZenPick.within(250f, 60f, 150f, 10f, homes(), a, stage, 0f, 0f))
        assertEquals(setOf(b0, b1, c0, c1), ZenPick.within(-10f, -10f, 600f, 200f, homes(), a, stage, 0f, 0f))
        assertEquals(b0, ZenPick.at(50f, 50f, homes(), a, stage, 0f, 0f))
        assertNull(ZenPick.at(250f, 50f, homes(), a, stage, 0f, 0f))
        // c1 put down over b0: the point on both finds the one on top.
        a.move(c1, -400f, 0f)
        assertEquals(c1, ZenPick.at(50f, 50f, homes(), a, stage, 0f, 0f))
    }

    @Test
    fun pickingFollowsTheCardsIntoTheMiddleOfTheWindow() {
        val a = ZenArrangement()
        val stage = ZenStage(scale = 2f, dx = 100f, dy = 0f)
        // About the origin, doubled and moved right 100: b1 (100..200) is drawn at 300..500.
        val r = ZenPick.rectOf(b1, homes(), a, stage, 0f, 0f)!!
        assertEquals(300f, r[0], 1e-3f)
        assertEquals(500f, r[2], 1e-3f)
        assertEquals(b1, ZenPick.at(400f, 100f, homes(), a, stage, 0f, 0f))
    }

    @Test
    fun shiftAddsAndABareBoxReplaces() {
        assertEquals(setOf(1, 2, 3), ZenPick.combine(setOf(1, 2), setOf(3), additive = true))
        assertEquals(setOf(3), ZenPick.combine(setOf(1, 2), setOf(3), additive = false))
        assertEquals(setOf(1), ZenPick.toggle(setOf(1, 2), 2))
        assertEquals(setOf(1, 2), ZenPick.toggle(setOf(1), 2))
    }

    @Test
    fun theGrammarOfPickingOut() {
        // A bare click picks out one card; again, none.
        assertEquals(setOf(5), ZenGestures.click(setOf(1, 2), 5, shift = false))
        assertEquals(emptySet(), ZenGestures.click(setOf(5), 5, shift = false))
        // Shift puts in and takes out.
        assertEquals(setOf(1, 5), ZenGestures.click(setOf(1), 5, shift = true))
        assertEquals(setOf(1), ZenGestures.click(setOf(1, 5), 5, shift = true))
        // A double-click takes the block; Shift adds it.
        assertEquals(setOf(7, 8), ZenGestures.doubleClick(setOf(1), setOf(7, 8), shift = false))
        assertEquals(setOf(1, 7, 8), ZenGestures.doubleClick(setOf(1), setOf(7, 8), shift = true))
        // Dragging a picked-out card carries them all, and they stay picked out.
        assertEquals(setOf(1, 2, 3), ZenGestures.carried(setOf(1, 2, 3), 2, shift = false))
        assertEquals(setOf(1, 2, 3), ZenGestures.selectionWhileCarrying(setOf(1, 2, 3), 2, shift = false))
        // Dragging one that is not carries it alone, and lets go of the rest.
        assertEquals(setOf(9), ZenGestures.carried(setOf(1, 2), 9, shift = false))
        assertEquals(emptySet(), ZenGestures.selectionWhileCarrying(setOf(1, 2), 9, shift = false))
        // Shift-dragging one that is not adds it and carries them all.
        assertEquals(setOf(1, 2, 9), ZenGestures.carried(setOf(1, 2), 9, shift = true))
        // The table lets go, unless Shift is held.
        assertEquals(emptySet(), ZenGestures.tableClick(setOf(1), shift = false))
        assertEquals(setOf(1), ZenGestures.tableClick(setOf(1), shift = true))
    }
}
