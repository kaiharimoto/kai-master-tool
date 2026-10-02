package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboCodec
import com.kaiharimoto.mastertool.core.duel.ai.ComboBook
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelAiTest {
    private val table = ok(DuelFixtures.bare(), DuelAction.Draw(0, 4))
    private val ash = uid(0, 0)
    private val pot = uid(0, 2)
    private val zeus = uid(0, 40)

    @Test
    fun theBriefNeverNamesWhatTheSeatCannotSee() {
        val set = ok(table, DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_DOWN_ATK))
        val mine = DuelBrief.describe(set, 0, catalog, 9L)
        val theirs = DuelBrief.describe(set, 1, catalog, 9L)
        assertTrue("Pot of Prosperity" in mine)
        assertTrue("Ash Blossom" in mine)
        assertFalse("Pot of Prosperity" in theirs)
        assertFalse("Ash Blossom" in theirs)
        assertTrue("a face-down card [?" in theirs)
        assertTrue("#$ash Ash Blossom" in DuelBrief.describe(set, null, catalog, 9L))
    }

    @Test
    fun aCardIsNamedByItsUidButOnlyIfTheSeatSeesIt() {
        val p = DuelCommand.parse("#$ash to gy", table, 0, catalog)
        assertTrue(p is DuelCommand.Parsed.Actions)
        assertTrue(DuelCommand.parse("#$ash to gy", table, 1, catalog) is DuelCommand.Parsed.Problem)
        val link = DuelCommand.parse("link #$ash", table, 0, catalog) as DuelCommand.Parsed.Actions
        assertEquals(listOf(DuelAction.ChainAdd(0, ash)), link.actions)
    }

    @Test
    fun aRecordedLineReplaysAgainstAnotherShuffle() {
        // Played once on one deal…
        val start = table
        var g = DuelGame(header(), emptyList(), 0, start, 0)
        val hand = start.seats[0].hand
        val monster = hand.first { catalog.info(start.cards.getValue(it).code)?.kind == CardKind.MONSTER }
        g = g.act(DuelAction.Move(monster, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal"), 0).game
        g = g.act(DuelAction.Move(start.seats[0].extra[0], Place.Zone(0, ZoneKind.EMZ, 0), CardPosition.FACE_UP_ATK, "special"), 0).game
        g = g.act(DuelAction.Move(monster, Place.Under(start.seats[0].extra[0]), how = "attach"), 0).game
        g = g.act(DuelAction.ChainAdd(0, start.seats[0].extra[0]), 0).game
        val span = g.entries
        val steps = ComboRecorder.steps(start, span, catalog)
        val needs = ComboRecorder.needs(start, 0, span, catalog)
        assertEquals(4, steps.size)
        assertEquals(1, needs.size)

        // …and run on a table where that card sits elsewhere in the hand.
        val other = ok(ok(DuelFixtures.bare(), DuelAction.Draw(0, 5)), DuelAction.Move(uid(0, 3), Place.Pile(0, PileKind.HAND, 0)))
        val combo = Combo("c1", "Opening", needs = needs, steps = steps)
        val run = ComboRunner.plan(other, 0, combo.steps, catalog)
        assertTrue(run.ok, run.problem)
        val (after, _) = DuelRules.applyAll(other, run.steps.flatMap { it.second }, 0)
        assertNotNull(after)
        assertEquals(zeus, after.emz[0])
        assertEquals(1, after.cards.getValue(zeus).under.size)
        assertEquals(1, after.chain.size)
    }

    @Test
    fun aComboThatCannotFinishSaysWhereBeforeAnythingMoves() {
        val run = ComboRunner.plan(table, 0, listOf("summon ash to m1", "summon droll to m1"), catalog)
        assertFalse(run.ok)
        assertEquals(1, run.stoppedAt)
        assertTrue(run.problem!!.startsWith("Step 2"))
        assertEquals(listOf("pot"), ComboRunner.missing(table, 0, Combo("c", "x", needs = listOf("ash", "pot", "pot")), catalog))
        assertNull(ComboRunner.missing(table, 0, Combo("c", "x", needs = listOf("ash")), catalog).firstOrNull())
    }

    @Test
    fun aDecksCombosSurviveTheirFile() {
        val book = ComboBook(listOf(Combo("c1", "Opening", "deck-1", listOf("Ash"), listOf("summon ash to m3"), "Go first")))
        assertEquals(book, ComboCodec.decode(ComboCodec.encode(book)))
        assertEquals(ComboBook(), ComboCodec.decode("not json"))
        assertEquals("combos/deck-1.json", ComboCodec.path("deck-1"))
        assertEquals("combos/deck.json", ComboCodec.path("../"))
    }
}
