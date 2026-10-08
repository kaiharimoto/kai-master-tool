package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The siding board marks the copies picked (kai, 2026-10: "per copy, not per card name"), and lays the deck out as the builder does. */
class SidingCopiesTest {
    private fun ids(vararg v: Int) = v.map(::CardId)
    private val a = CardId(1)
    private val b = CardId(2)
    private val c = CardId(3)

    @Test
    fun withoutPicksTheFirstCopiesOfEachMovedCardAreMarked() {
        val deck = ids(1, 1, 1, 2, 3, 3, 2)
        assertEquals(listOf(true, true, false, true, true, false, true), SidingMarks.of(deck, ids(1, 1, 3, 2, 2)))
        assertEquals(List(3) { false }, SidingMarks.of(ids(5, 5, 6), emptyList()))
    }

    @Test
    fun theCopyClickedIsTheCopyMarked() {
        val deck = ids(1, 1, 1, 2)
        val plan = SidePlan().plusOut(a, at = 2)
        assertEquals(listOf(false, false, true, false), SidingMarks.of(deck, plan.out, plan.outCopies))
        val two = plan.plusOut(a, at = 0)
        assertEquals(listOf(true, false, true, false), SidingMarks.of(deck, two.out, two.outCopies))
    }

    @Test
    fun takingBackAnUnpickedMarkKeepsThePickedOne() {
        val deck = ids(1, 1, 1)
        // Copy 2 picked; a second out with no pick marks the first copy beside it.
        val plan = SidePlan().plusOut(a, at = 2).plusOut(a)
        assertEquals(listOf(true, false, true), SidingMarks.of(deck, plan.out, plan.outCopies))
        val back = plan.minusOut(a, at = 0)
        assertEquals(listOf(false, false, true), SidingMarks.of(deck, back.out, back.outCopies))
        val other = plan.minusOut(a, at = 2)
        assertEquals(listOf(true, false, false), SidingMarks.of(deck, other.out, other.outCopies))
    }

    @Test
    fun picksNeverOutnumberTheCopiesMovedAndStalePicksAreIgnored() {
        val plan = SidePlan().plusOut(a, at = 1).plusOut(a, at = 2).minusOut(a)
        assertEquals(1, plan.outCopies.getValue(a).size)
        assertTrue(plan.minusOut(a).outCopies.isEmpty())
        // A pick past the copies the deck has now (the deck changed) falls back to the first copy.
        val stale = SidePlan(out = ids(2), outCopies = mapOf(b to listOf(5)))
        assertEquals(listOf(true, false), SidingMarks.of(ids(2, 2), stale.out, stale.outCopies))
        // Ins pick their own copies.
        val ins = SidePlan().plusIn(c, at = 1)
        assertEquals(listOf(false, true), SidingMarks.of(ids(3, 3), ins.into, ins.inCopies))
    }

    @Test
    fun picksTravelInThePlanAndAPlanWithoutThemWritesAsBefore() {
        val plain = DeckSiding(listOf(Matchup("m1", "Yubel", first = SidePlan(out = ids(1)))))
        val text = SidingCodec.node(plain).toString()
        assertTrue("Copies" !in text, text)
        val picked = DeckSiding(listOf(Matchup("m1", "Yubel", first = SidePlan().plusOut(a, at = 2).plusIn(c, at = 1))))
        val back = SidingCodec.read(JsonObject(mapOf(SidingCodec.KEY to SidingCodec.node(picked))))
        assertEquals(picked, back)
        // Anything broken in the picks is skipped, not a failed read.
        val broken = Json.parseToJsonElement(
            """{"siding":{"matchups":[{"id":"m","name":"X","first":{"out":[1],"in":[],"outCopies":{"1":[-1,"x",0],"nope":[1],"2":5}}}]}}"""
        ) as JsonObject
        assertEquals(mapOf(a to listOf(0)), SidingCodec.read(broken).matchups.single().first.outCopies)
    }

    @Test
    fun theMainDeckIsLaidOutAsTheBuilderLaysItOut() {
        val main = ids(1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 4, 5, 6, 7, 8)
        val groups = DeckGroups.EMPTY
            .upsert(DeckGroup("g1", "Engine", 2, 0)).upsert(DeckGroup("g2", "Traps", 3, 1))
            .assign(CardId(1), "g1").assign(CardId(3), "g1").assign(CardId(4), "g2")
        val pane = 1000f to 600f
        val plain = SidingLayout.main(main, groups, groupsOn = false, GroupArrangement.FITTED, pane, 86f / 59f, 20f)
        assertEquals(10, plain.columns)
        assertEquals(0, plain.spanX + plain.spanY)
        GroupArrangement.entries.forEach { arrangement ->
            val laid = SidingLayout.main(main, groups, groupsOn = true, arrangement, pane, 86f / 59f, 20f)
            assertEquals(main.size, laid.piece.size, "$arrangement places every copy")
            // No two copies in one cell.
            assertEquals(main.size, main.indices.map { laid.row(it) to laid.col(it) }.toSet().size, "$arrangement")
            assertTrue(laid.pieces > 1, "$arrangement breaks the deck into pieces")
        }
    }

    @Test
    fun theFitMakesRoomForThePiecesGaps() {
        val flat = BoardFit.fit(1200f, 700f, 10, 4, 0, 15, gap = 4f, sectionGap = 16f, header = 28f, ratio = 59f / 86f, maxCard = 500f)
        val apart = BoardFit.fit(1200f, 700f, 10, 4, 0, 15, gap = 4f, sectionGap = 16f, header = 28f, ratio = 59f / 86f, maxCard = 500f, spanX = 2, spanY = 2, pieceGap = 20f, tab = 17f)
        assertTrue(apart.card < flat.card)
        // The old ten-across fit is the same arithmetic.
        val old = BoardFit.fit(1200f, 700f, 40, 0, 15, gap = 4f, sectionGap = 16f, header = 28f, ratio = 59f / 86f, maxCard = 500f)
        assertEquals(old, flat)
    }
}
