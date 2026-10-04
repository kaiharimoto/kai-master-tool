package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.Provenance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The duel puzzles (Phase C stage 3, `docs/phases/C.md` §5): each laid out, solved by its recorded line and not by its
 * tempting wrong one, every manual shortcut a real duel forbids refused by the referee, and the set's baseline bounds.
 */
class PuzzleTest {
    private fun ops(vararg o: String): JsonObject = JsonObject(mapOf("ops" to JsonArray(o.map(::JsonPrimitive))))

    @Test
    fun everyPuzzleIsSolvedByItsLineAndNotByTheTemptingOne() {
        assertTrue(Puzzles.all.size in 10..20, "10–20 puzzles")
        assertEquals(Puzzles.all.size, Puzzles.all.map { it.id }.toSet().size, "ids are unique")
        Puzzles.all.forEach { p ->
            val start = PuzzleTable(p)
            assertEquals(DuelPhase.MAIN1, start.state.phase, p.id)
            assertEquals(Puzzles.TURN, start.state.turn, p.id)
            assertFalse(start.grade().pass, "${p.id}: met before a move")
            val solved = PuzzleTable(p)
            val played = solved.play(p.solution)
            assertTrue(played.all { it.ok }, "${p.id}: ${played.filterNot { it.ok }}")
            assertTrue(p.solution.size <= p.budget, "${p.id}: the solution fits the budget")
            val g = solved.grade()
            assertTrue(g.pass, "${p.id}: ${g.read}")
            val wrong = PuzzleTable(p).also { it.play(p.wrong) }
            assertFalse(wrong.grade().pass, "${p.id}: the wrong line ${p.wrong} must not meet the goal (${wrong.grade().read})")
        }
    }

    @Test
    fun theBaselineBoundsTheSet() {
        val (nothing, greedy, solved) = PuzzleBaselines.bounds()
        val n = Puzzles.all.size
        assertEquals(0, nothing, "doing nothing meets no goal")
        assertEquals(n, solved, "every recorded solution meets its goal")
        // The battle-only greedy player solves only the two warm-ups: every other puzzle needs a summon, a Spell, a position or a trade.
        assertEquals(listOf("p01", "p02"), Puzzles.all.filter { PuzzleBaselines.greedy(it).pass }.map { it.id })
        assertEquals(2, greedy)
    }

    @Test
    fun manualShortcutsARealDuelForbidsAreRefused() {
        val p = Puzzles.byId("p02")!!
        fun refused(vararg line: String) {
            val t = PuzzleTable(p)
            val played = t.play(line.toList())
            assertFalse(played.last().ok, "${line.toList()} should be refused")
            assertFalse(t.grade().pass)
        }
        refused("lp opp -2000")
        refused("lp opp =0")
        refused("g om1")
        refused("b om1")
        refused("h om1")
        refused("draw")
        refused("end")
        refused("concede")
        refused("ss gy1")
        refused("a m1 om1") // not in the Battle Phase
        refused("bp", "a m1 direct") // they control a monster
        refused("bp", "a m1 om1", "a m1 direct") // attacked already
        refused("bp", "m1") // phases go forward only
        refused("bp", "m2", "bp")
        refused("token sheep atk 5000", "bp")
        // Battle damage is the referee's: typed damage after an attack is refused, the attack's own applied once.
        val t = PuzzleTable(p)
        t.play(listOf("bp", "a m1 om1"))
        assertEquals(1400, t.state.seats[1].lp, "600 by battle, applied by the referee")
        assertFalse(t.play(listOf("lp opp -1400")).single().ok)
    }

    @Test
    fun summonsTributesAndPositionsFollowTheRules() {
        // p04: Summoned Skull (Level 6) needs exactly one Tribute, and only one Normal Summon a turn.
        val skull = Puzzles.byId("p04")!!
        assertFalse(PuzzleTable(skull).play(listOf("s h1 m2")).last().ok, "no Tribute paid")
        assertFalse(PuzzleTable(skull).play(listOf("ss h1 m2")).last().ok, "no Special Summon")
        PuzzleTable(skull).let { t ->
            assertTrue(t.play(listOf("g m1")).single().ok, "a Tribute for the Skull in hand")
            assertFalse(t.play(listOf("bp")).single().ok, "the Tribute Summon is finished first")
            assertTrue(t.play(listOf("s h1 m1")).single().ok)
            assertFalse(t.play(listOf("p m1")).single().ok, "not the turn it was summoned")
        }
        // p03: a Level 4 needs no Tribute, so no monster may be sent to the GY as one; one Normal Summon.
        val jinn = Puzzles.byId("p03")!!
        assertFalse(PuzzleTable(jinn).play(listOf("g m1")).single().ok, "no Tribute is needed for La Jinn")
        // p11: the Flip Summon is no Normal Summon; a face-down monster is not flipped face-up in Defense by hand.
        val flip = Puzzles.byId("p11")!!
        assertFalse(PuzzleTable(flip).play(listOf("f m1")).single().ok)
        PuzzleTable(flip).let { t ->
            assertTrue(t.play(listOf("s m1", "s h1 m2")).all { it.ok })
            assertFalse(t.play(listOf("p m1")).single().ok, "changed position once already (its Flip Summon)")
        }
        // p10: a monster set on an earlier turn changes position once; after attacking it may not.
        val gaia = Puzzles.byId("p10")!!
        PuzzleTable(gaia).let { t ->
            assertTrue(t.play(listOf("p m1")).single().ok)
            assertFalse(t.play(listOf("p m1")).single().ok, "once a turn")
        }
        // The budget is the budget.
        PuzzleTable(Puzzles.byId("p15")!!).let { t ->
            assertTrue(t.play(listOf("bp", "m2", "ep")).all { it.ok })
            assertTrue(t.play(listOf("say hello")).single().ok, "talk is no move")
            assertEquals(3, t.turn.moves)
        }
    }

    @Test
    fun spellsResolveAsTheirTextSaysAndNoOtherWay() {
        val p = Puzzles.byId("p09")!!
        val t = PuzzleTable(p)
        assertTrue(t.play(listOf("activate fissure")).single().ok)
        val s = t.state
        assertEquals(listOf(PuzzleCards.FERAL_IMP), s.seats[1].monsters.filterNotNull().map { s.cards.getValue(it).code }, "the 800-ATK Mystical Elf went, by ATK not DEF")
        assertEquals(PuzzleCards.FISSURE, s.cards.getValue(s.seats[0].gy.first()).code, "the Spell to the GY once resolved")
        assertTrue(s.chain.isEmpty())
        // Dark Hole with nothing on the field cannot be activated; a Spell is a Main Phase card.
        val hole = Puzzles.byId("p08")!!
        PuzzleTable(hole).let { x ->
            assertTrue(x.play(listOf("bp")).single().ok)
            assertFalse(x.play(listOf("activate dark hole")).single().ok)
        }
        // A Spell only from the hand, by activating it: placing it face-up, or sending it, is refused.
        assertFalse(PuzzleTable(hole).play(listOf("place dark hole")).single().ok)
        assertFalse(PuzzleTable(hole).play(listOf("g h1")).single().ok)
    }

    @Test
    fun theMenuOffersOnlyWhatTheRefereeAdmitsAndTheToolsAnswer() {
        val p = Puzzles.byId("p05")!!
        val t = PuzzleTable(p)
        val menu = t.menu()
        assertTrue("`activate" in menu || "`a h1`" in menu, menu)
        assertFalse("`draw`" in menu, "drawing is no puzzle move: $menu")
        assertFalse("`g om1`" in menu, menu)
        val lines = Regex("`([^`]+)`").findAll(menu.substringAfter('\n')).map { it.groupValues[1] }.toList()
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertTrue(t.admits(it), "the menu offered $it") }
        val (brief, err) = t.tool("duel_state", JsonObject(emptyMap()))
        assertFalse(err)
        assertTrue("Goal: reduce your opponent to 0 LP" in brief && "Raigeki (Normal Spell): Destroy all monsters your opponent controls." in brief, brief)
        val (act, bad) = t.tool("duel_act", ops("bp", "lp opp -4200"))
        assertTrue(bad && "✓ bp" in act && "✗ lp opp -4200" in act, act)
        assertTrue(t.tool("card_info", JsonObject(emptyMap())).second, "no look-ups in a puzzle")
        // Ai's moves carry its provenance.
        assertTrue(t.game.played.drop(t.game.floor).all { it.by?.by == Provenance.AI && it.by?.aiSeat == 0 })
    }

    @Test
    fun theSetIsInTrustsListAndGradedOnlyByPlaying() {
        val set = EvalSets.byId(EvalSets.PUZZLES)!!
        assertEquals(Puzzles.all.size, set.items.size)
        set.items.forEach { item ->
            assertTrue(item.grader is Grader.Puzzle && Puzzles.byId((item.grader as Grader.Puzzle).id) != null, item.id)
            assertTrue("DONE" in item.prompt && "duel_act" in item.prompt, item.prompt)
            assertFalse(Grading.grade(item.grader, "I won.\nANSWER: yes").pass, "words never pass a puzzle")
        }
    }
}
