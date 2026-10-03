package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.ok
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import com.kaiharimoto.mastertool.core.duel.text.DuelCoverage
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every mouse gesture has words (1.0.87): a verb or a drop without a row, or a row the line cannot do, fails here. */
class DuelCoverageTest {
    private val s = battle()

    /** The kind of a drop spot — a `when` with no else, so a new [DropSpot] does not compile until it is listed. */
    private fun kind(d: DropSpot): String = when (d) {
        is DropSpot.Zone -> "zone"
        is DropSpot.Pile -> "pile"
        is DropSpot.Hand -> "hand"
        DropSpot.Chain -> "chain"
        is DropSpot.Score -> "score"
    }

    private val spots = listOf(
        DropSpot.Zone(Place.Zone(0, ZoneKind.MONSTER, 2)), DropSpot.Pile(0, PileKind.GY), DropSpot.Hand(0, 0), DropSpot.Chain, DropSpot.Score(1),
    )

    private fun table(row: DuelCoverage.Row): DuelState = when (row.needs) {
        DuelCoverage.NEEDS_CHAIN -> ok(s, DuelAction.ChainAdd(0, uid(0, 5)))
        DuelCoverage.NEEDS_PROPOSAL -> ok(s, DuelAction.Propose(1, end = true), 1)
        DuelCoverage.NEEDS_OPENING -> s.copy(opening = Opening())
        DuelCoverage.NEEDS_CHOICE -> s.copy(opening = Opening(dice = listOf(listOf(6, 5), listOf(1, 2)), winner = 0))
        else -> s
    }

    @Test
    fun everyVerbAndEveryDropHasWords() {
        val missing = DuelVerb.entries.filter { DuelCoverage.forVerb(it) == null }.map { it.name } +
            DuelCoverage.INTENTS.filter { DuelCoverage.forIntent(it) == null }
        assertTrue(missing.isEmpty(), "No typed form for: $missing")
        // Every kind of drop spot has at least one intent listed.
        spots.forEach { spot -> assertTrue(DuelCoverage.INTENTS.any { it == kind(spot) || it.startsWith(kind(spot) + ".") }, kind(spot)) }
    }

    @Test
    fun everyRowDoesWhatItSays() {
        val bad = DuelCoverage.ROWS.mapNotNull { row ->
            val t = table(row)
            when (val p = DuelCommand.parse(row.typed, t, 0, catalog)) {
                is Parsed.Problem -> "“${row.typed}” (${row.gesture}): ${p.text}"
                is Parsed.Actions -> DuelRules.applyAll(t, p.actions, 0).second?.let { "“${row.typed}”: $it" }
                    ?: verbMismatch(row, p.actions)
                is Parsed.Many -> null
                else -> null
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    /** A verb's row does what the verb does on that card. */
    private fun verbMismatch(row: DuelCoverage.Row, actions: List<DuelAction>): String? {
        val verb = row.verb ?: return null
        val expect = when (verb) {
            DuelVerb.ATTACK -> actions.all { it is DuelAction.Attack }
            DuelVerb.TARGET -> actions.all { it is DuelAction.Target }
            DuelVerb.COUNTER_UP, DuelVerb.COUNTER_DOWN -> actions.all { it is DuelAction.Counter }
            DuelVerb.REVEAL -> actions.all { it is DuelAction.Reveal }
            DuelVerb.POSITION, DuelVerb.FLIP -> actions.all { it is DuelAction.Position }
            DuelVerb.ACTIVATE -> actions.any { it is DuelAction.ChainAdd }
            DuelVerb.ATTACH -> actions.all { it is DuelAction.Move && it.to is Place.Under }
            else -> actions.isNotEmpty()
        }
        return if (expect) null else "“${row.typed}” did not ${verb.label}: $actions"
    }
}
