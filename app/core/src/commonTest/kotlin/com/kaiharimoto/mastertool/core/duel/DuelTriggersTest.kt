package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.bare
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers
import com.kaiharimoto.mastertool.core.duel.ai.Trigger
import com.kaiharimoto.mastertool.core.duel.ai.Watch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ai's response triggers (1.0.85): watches it leaves, fired by the table without asking the model. */
class DuelTriggersTest {
    private val ash = uid(0, 0)
    private val pot = uid(0, 2)
    private val zeus = uid(0, 40)
    private val ai = 1

    private fun m(seat: Int, i: Int) = Place.Zone(seat, ZoneKind.MONSTER, i)
    private fun e(a: DuelAction, seat: Int = 0) = DuelEntry(0, 0L, seat, 0, a)
    private fun watch(vararg on: String, by: String = Watch.BY_OPPONENT, card: String = "", phase: String = "", atLeast: Int = 0) =
        DuelTriggers.make(on.toList(), by, card, phase, atLeast, "", false, "", 1, 1).first!!

    private fun happen(s: DuelState, vararg a: DuelAction, seat: Int = 0) =
        DuelTriggers.happenings(s, a.map { e(it, seat) }, catalog, ai)

    @Test
    fun aMonsterFromTheHandIsANormalSummonAndFromTheExtraDeckASpecial() {
        val s = ok(bare(), DuelAction.Draw(0, 1))
        val normal = happen(s, DuelAction.Move(ash, m(0, 2), CardPosition.FACE_UP_ATK, how = "normal")).map { it.kind }
        assertEquals(listOf(Trigger.SUMMON, Trigger.NORMAL_SUMMON), normal)
        val special = happen(s, DuelAction.Move(zeus, m(0, 1), CardPosition.FACE_UP_ATK)).map { it.kind }
        assertEquals(listOf(Trigger.SUMMON, Trigger.SPECIAL_SUMMON), special)
    }

    @Test
    fun aCardSetIsASetAndItsNameStaysHidden() {
        val s = ok(bare(), DuelAction.Draw(0, 3))
        val h = happen(s, DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_DOWN_ATK)).single()
        assertEquals(Trigger.SET, h.kind)
        assertNull(h.name)
        assertFalse(h.words.contains("Pot"))
    }

    @Test
    fun aPhaseChangeIsLeavingOneAndEnteringTheNext() {
        val s = ok(bare(), DuelAction.Phase(DuelPhase.MAIN1))
        val h = happen(s, DuelAction.Phase(DuelPhase.BATTLE))
        assertEquals(listOf(Trigger.PHASE_LEAVE to DuelPhase.MAIN1, Trigger.PHASE_ENTER to DuelPhase.BATTLE), h.map { it.kind to it.phase })
        val leave = watch("phase_leave", phase = "main 1")
        assertEquals(1, DuelTriggers.hits(listOf(leave), h, ai).size)
        assertTrue(DuelTriggers.hits(listOf(watch("phase_leave", phase = "battle")), h, ai).isEmpty())
    }

    @Test
    fun ending_the_turn_enters_the_next_players_draw_phase() {
        val s = ok(bare(), DuelAction.Phase(DuelPhase.MAIN2))
        val h = happen(s, DuelAction.EndTurn)
        assertEquals(Trigger.PHASE_ENTER, h[1].kind)
        assertEquals(1, h[1].seat)
        assertEquals(DuelPhase.DRAW, h[1].phase)
        // Ai's own Draw Phase is not the opponent's.
        assertTrue(DuelTriggers.hits(listOf(watch("phase_enter", phase = "draw")), h, ai).isEmpty())
        assertEquals(1, DuelTriggers.hits(listOf(watch("phase_enter", by = Watch.BY_SELF, phase = "draw")), h, ai).size)
    }

    @Test
    fun aWatchOnACardMatchesOnlyWhatTheWatcherCanSee() {
        var s = ok(bare(), DuelAction.Draw(0, 1))
        val summon = happen(s, DuelAction.Move(ash, m(0, 0), CardPosition.FACE_UP_ATK))
        assertEquals(1, DuelTriggers.hits(listOf(watch("summon", card = "ash blossom")), summon, ai).size)
        assertTrue(DuelTriggers.hits(listOf(watch("summon", card = "droll")), summon, ai).isEmpty())
        // Activated from the hand, its name is on the chain; a face-down card's is not.
        s = ok(s, DuelAction.Move(ash, m(0, 0), CardPosition.FACE_DOWN_DEF))
        val flipped = happen(s, DuelAction.Position(ash, CardPosition.FACE_UP_ATK))
        assertEquals(Trigger.SUMMON, flipped.single().kind)
        assertNotNull(flipped.single().name)
    }

    @Test
    fun activationsAttacksAndSearchesAreSeen() {
        var s = ok(bare(), DuelAction.Draw(0, 1))
        assertEquals(Trigger.ACTIVATE, happen(s, DuelAction.ChainAdd(0, ash)).single().kind)
        assertEquals(Trigger.SEARCH, happen(s, DuelAction.Move(pot, Place.Pile(0, PileKind.HAND), how = "search")).single().kind)
        s = ok(s, DuelAction.Move(ash, m(0, 0), CardPosition.FACE_UP_ATK))
        s = ok(s, DuelAction.Phase(DuelPhase.BATTLE))
        val attack = happen(s, DuelAction.Attack(0, ash, null)).single()
        assertEquals(Trigger.ATTACK, attack.kind)
        assertEquals(DuelPhase.BATTLE, attack.phase)
    }

    @Test
    fun aSummonCountWatchWaitsForTheFifthSummon() {
        val s = ok(bare(), DuelAction.Draw(0, 1))
        val h = happen(s, DuelAction.Move(zeus, m(0, 1), CardPosition.FACE_UP_ATK))
        val nibiru = watch("summon", atLeast = 5)
        assertTrue(DuelTriggers.hits(listOf(nibiru), h, ai) { 4 }.isEmpty())
        assertEquals(1, DuelTriggers.hits(listOf(nibiru), h, ai) { 5 }.size)
    }

    @Test
    fun aWatchByTheOpponentIgnoresAisOwnMoves() {
        val s = ok(bare(), DuelAction.Draw(1, 1), by = 1)
        val own = happen(s, DuelAction.Move(uid(1, 0), Place.Zone(1, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK), seat = 1)
        assertTrue(DuelTriggers.hits(listOf(watch("set")), own, ai).isEmpty())
        assertEquals(1, DuelTriggers.hits(listOf(watch("set", by = Watch.BY_ANY)), own, ai).size)
    }

    @Test
    fun aTypoIsRefusedWithTheKindsThatExist() {
        val (w, problem) = DuelTriggers.make(listOf("sumon"), "", "", "", 0, "", false, "", 1, 1)
        assertNull(w)
        assertTrue(problem!!.contains("sumon") && problem.contains("summon"))
        assertEquals(listOf("activate", "phase_leave"), DuelTriggers.make(listOf("chain", "leave"), "", "", "", 0, "", false, "", 1, 1).first!!.on)
    }

    @Test
    fun aTurnsWatchEndsWithItsTurn() {
        val w = DuelTriggers.make(listOf("attack"), "", "", "", 0, "", false, "turn", 3, 1).first!!
        assertEquals(listOf(w), DuelTriggers.alive(listOf(w), 3))
        assertTrue(DuelTriggers.alive(listOf(w), 4).isEmpty())
    }

    @Test
    fun thePersonIsToldTheKindsNeverTheCards() {
        val w = DuelTriggers.make(listOf("summon", "activate"), "", "Ash", "", 0, "Ash Blossom in hand", false, "", 1, 1).first!!
        val words = DuelTriggers.kindsWords(listOf(w))
        assertEquals("Summons, activations", words)
        assertTrue(DuelTriggers.describe(w).contains("Ash Blossom in hand"))
    }
}
