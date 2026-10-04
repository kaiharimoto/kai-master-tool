package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The red team on Phase C (stage 3), a provenance gap: a number key just after a card was placed re-made the log's last
 * group under that group's provenance — and when Ai had moved since, the person's key undid Ai's move and wrote it again
 * as Ai's. Now the key moves only its own placement.
 */
class DuelNumberKeyTest {
    @Test
    fun aNumberKeyNeverRewritesAisMove() = runBlocking {
        withContext(Dispatchers.Main) {
            val d = Duels(Files.createTempDirectory("duel").toFile())
            d.start(DuelHeader("n1", 3, listOf(SeatSetup("Kai", List(40) { 1 }), SeatSetup("Ai", List(40) { 2 }))))
            val card = d.game!!.state.seats[0].hand.first()
            assertTrue(d.verb(card, DuelVerb.SUMMON, seat = 0), d.problem.orEmpty())
            d.aiActing = true
            try { assertTrue(d.act(listOf(DuelAction.Draw(1)), 1)) } finally { d.aiActing = false }
            val before = d.game
            assertFalse(d.replace(ZoneKind.MONSTER, 4), "Ai's draw is not the placement")
            assertEquals(before, d.game)
            assertEquals(Provenance.AI, d.game!!.played.last().by?.by)

            // With nothing of Ai's between, the key moves the person's placement, still the person's.
            val other = d.game!!.state.seats[0].hand.first()
            assertTrue(d.verb(other, DuelVerb.SUMMON, seat = 0))
            assertTrue(d.replace(ZoneKind.MONSTER, 4))
            assertEquals(Place.Zone(0, ZoneKind.MONSTER, 4), d.game!!.state.placeOf(other))
            assertEquals(Provenance.PERSON, d.game!!.played.last().by?.by)
        }
    }
}
