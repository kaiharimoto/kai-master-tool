package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The play-around set (Phase E, E.5a; E.md §4.6): a search that lets the other seat answer knowing your hand never plays the
 * card that matters into its answer, and keeps it; one that cannot see your hand either draws the answer out with the bait,
 * then plays it.
 */
class PlayAroundTest {
    private val kit = GoldfishFixtures.kit(GoldfishFixtures.trust(FxRef.book.cards.mapNotNull { FxRef.book.script(it) }))

    private fun options(p: PlayAround.Position, hold: Double?): Map<Root, TurnOption> {
        val search = TurnSearch(kit, budget = 200_000, answers = true, hold = hold)
        val opts = search.options(p.table, 0, 0)
        assertTrue(search.complete, "${p.id}: searched whole")
        return opts.associateBy { it.root }
    }

    @Test
    fun aSearchThatCannotSeeYourHandEitherDrawsTheAnswerOutWithTheBaitThenPlaysTheKey() {
        for (p in PlayAround.all) {
            val bait = Root.Move(p.lead(p.bait))
            val key = Root.Move(p.lead(p.key))
            val sees = options(p, null)
            val blind = options(p, Worlds.HOLD_BACK)
            // Both lead with the bait — for different reasons.
            assertEquals(bait, sees.values.maxBy { it.value }.root, p.id)
            assertEquals(bait, blind.values.maxBy { it.value }.root, p.id)
            // Knowing your hand, the other seat keeps its answer for the key, so the key is never played after the bait: it is
            // kept, and the bait's whole edge is that card kept in hand.
            val seesLine = sees.getValue(bait).steps
            assertTrue(p.plays(seesLine, p.key, 0) < 0, "${p.id}: the clairvoyant line keeps the key")
            assertEquals(ValueWeights().hand, sees.getValue(bait).value - sees.getValue(key).value, 1e-9, p.id)
            // Not seeing it, the other seat spends its answer on the bait, and the key follows it.
            val blindLine = blind.getValue(bait).steps
            val spent = p.plays(blindLine, p.answer, 1)
            val played = p.plays(blindLine, p.key, 0)
            assertTrue(spent >= 0 && played > spent, "${p.id}: ${p.about}")
            // The bait's edge grows by what drawing the answer out is worth.
            val edge = blind.getValue(bait).value - blind.getValue(key).value
            assertTrue(edge > ValueWeights().hand + 0.25, "${p.id}: the bait's edge ${edge}")
        }
    }
}
