package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderPlayers
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderRoster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The search's versions (Phase E, E.5b; E.md §4.6): which plays, how a candidate is made, judged and kept. */
class SearchVersionTest {
    private fun passed(v: SearchVersion, pass: Boolean) = v.copy(verdict = GateRecord(10, 1, 13, 0.006, 0.05, pass))

    @Test
    fun theVersionInUseIsTheNewestThatPassedElseV0() {
        assertEquals(0, SearchVersions.inUse(SearchVersions.builtIn).n, "v1 did not pass: v0 plays")
        val v2 = ValueTuning.apply(SearchVersions.V0, "damage up", 2)
        assertEquals(0, SearchVersions.inUse(SearchVersions.all(listOf(v2))).n, "a candidate not yet judged never plays")
        assertEquals(0, SearchVersions.inUse(SearchVersions.all(listOf(passed(v2, false)))).n)
        val all = SearchVersions.all(listOf(passed(v2, true), ValueTuning.apply(passed(v2, true), "hand up", 3)))
        assertEquals(2, SearchVersions.inUse(all).n)
        // A file never takes a built-in number.
        assertEquals(SearchVersions.V1, SearchVersions.all(listOf(SearchVersions.V1.copy(chance = false, source = SearchVersions.TUNED))).first { it.n == 1 })
    }

    @Test
    fun everyCandidateSinceTheLastPromotionSharesTheGatesChance() {
        val v2 = ValueTuning.apply(SearchVersions.V0, "damage up", 2)
        val v3 = ValueTuning.apply(SearchVersions.V0, "damage down", 3)
        var all = SearchVersions.all(listOf(v2, v3))
        assertEquals(1, SearchVersions.attempts(SearchVersions.V1, all))
        assertEquals(2, SearchVersions.attempts(v2, all))
        assertEquals(3, SearchVersions.attempts(v3, all))
        // v3 passes: the next candidate starts the count again.
        all = SearchVersions.all(listOf(v2, passed(v3, true)))
        val v4 = ValueTuning.apply(all.first { it.n == 3 }, "interruptions up", 4)
        assertEquals(1, SearchVersions.attempts(v4, SearchVersions.all(listOf(v2, passed(v3, true), v4))))
    }

    @Test
    fun tuningStepsOneCoordinateAtATimeFromTheVersionInUse() {
        val first = assertNotNull(ValueTuning.next(SearchVersions.builtIn))
        assertEquals(2, first.n)
        assertEquals(0, first.parent)
        assertEquals("interruptions up", first.step)
        assertEquals(ValueWeights().interruptions * ValueTuning.UP, first.weights.interruptions, 1e-9)
        assertEquals(ValueWeights().copy(interruptions = 4.5, version = 2), first.weights)
        // The hold steps by a tenth and stays inside its bounds.
        assertEquals(Worlds.HOLD_BACK + 0.1, ValueTuning.apply(SearchVersions.V0, "hold up", 9).hold, 1e-9)
        assertEquals(0.05, ValueTuning.apply(SearchVersions.V0.copy(hold = 0.1), "hold down", 9).hold, 1e-9)
        // Every step tried from v0: the sweep is done.
        var all = SearchVersions.builtIn
        repeat(ValueTuning.steps().size) {
            val next = assertNotNull(ValueTuning.next(all))
            all = SearchVersions.all(all.filter { it.source != SearchVersions.BUILT_IN } + passed(next, false))
        }
        assertNull(ValueTuning.next(all))
        assertEquals(ValueTuning.steps().toSet(), all.mapNotNull { it.step }.toSet())
        // One that passed starts the sweep again from itself.
        val promoted = passed(all.first { it.step == "bodies up" }, true)
        val again = SearchVersions.all(all.filter { it.source != SearchVersions.BUILT_IN && it.n != promoted.n } + promoted)
        val step = assertNotNull(ValueTuning.next(again))
        assertEquals(promoted.n, step.parent)
        assertEquals(promoted.weights.bodies, step.weights.bodies, 1e-9)
    }

    @Test
    fun aProposalIsCheckedAndKeptAsACandidateOfTheVersionInUse() {
        val from = SearchVersions.V0
        assertTrue(ValueProposal.of(from, mapOf("speed" to 2.0), null, null, "faster").second!!.startsWith("No such weight"))
        assertTrue("from 0 to 20" in ValueProposal.of(from, mapOf("damage" to 40.0), null, null, "more").second!!)
        assertTrue("hold" in ValueProposal.of(from, emptyMap(), 0.99, null, "x").second!!)
        assertTrue("Nothing changed" in ValueProposal.of(from, mapOf("damage" to 1.0), null, null, "same").second!!)
        assertTrue("why" in ValueProposal.of(from, mapOf("damage" to 2.0), null, null, " ").second!!)
        val (v, why) = ValueProposal.of(from, mapOf("damage" to 2.0, "taken" to 1.0), null, true, "Life points win games.")
        assertNull(why)
        assertEquals(2.0, v!!.weights.damage)
        assertEquals(1.0, v.weights.taken)
        assertEquals(ValueWeights().bodies, v.weights.bodies, "the rest stay the version in use's")
        assertEquals(0, v.parent)
        assertEquals(SearchVersions.PROPOSED, v.source)
        assertTrue(v.chance)
    }

    @Test
    fun aVersionFileReadsBackAndABrokenOneIsNone() {
        val v = passed(ValueTuning.apply(SearchVersions.V0, "damage up", 2, at = 5L), true)
        assertEquals(v, SearchVersions.decode(SearchVersions.encode(v)))
        assertNull(SearchVersions.decode("{"))
        assertEquals("value-2.json", SearchVersions.file(2))
    }

    @Test
    fun theLadderGivesEveryVersionAPlayerAPairAndItsGate() {
        val v2 = ValueTuning.apply(SearchVersions.V0, "damage up", 2)
        val roster = LadderRoster(SearchVersions.all(listOf(v2)))
        assertNotNull(roster.of(LadderPlayers.fast(2)))
        assertNotNull(roster.make(LadderPlayers.fast(2)))
        assertNull(roster.make(LadderPlayers.fast(3)), "a version this ladder has no file for is not played")
        assertTrue(roster.pairs.any { it.a == LadderPlayers.fast(2) && it.b == LadderPlayers.FAST })
        val gate = roster.questions.single { it.gate && it.candidate == LadderPlayers.fast(2) }
        assertEquals(LadderPlayers.FAST, gate.parent)
        assertEquals(2, gate.attempts)
        assertTrue("damage up" in gate.label, gate.label)
        assertEquals(LadderPlayers.FAST, roster.inUse)
        // The built-in ladder is the one E.5a measured.
        assertEquals(LadderPlayers.builtIn.pairs, LadderRoster(SearchVersions.builtIn).pairs)
    }
}
