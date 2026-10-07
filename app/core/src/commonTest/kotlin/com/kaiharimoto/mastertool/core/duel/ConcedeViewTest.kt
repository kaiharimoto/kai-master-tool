package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A concession reaches every table (kai, after 1.1.49): the seat that conceded is in each viewer's view, so a guest's or
 * a Lounge friend's table ends as the host's does — no Concede button left standing, the duel over in words.
 */
class ConcedeViewTest {
    @Test
    fun aConcessionIsInEveryViewersTable() {
        val header = DuelHeader(id = "c", seed = 3, seats = listOf(SeatSetup("Ash", List(40) { 1 }), SeatSetup("Mira", List(40) { 2 })))
        val g = DuelGame.start(header, 0L)
        val r = g.act(listOf(DuelAction.Concede(1)), 1, 1L)
        val after = r.game.state
        assertEquals(1, after.conceded)
        listOf(0, 1, null).forEach { viewer ->
            val mirrored = DuelMirror.state(DuelView.of(after, viewer, header.seed))
            assertEquals(1, mirrored.conceded, "viewer $viewer")
            assertEquals(0 to DuelResult.CONCEDE, DuelResults.ending(mirrored))
        }
        assertNull(DuelMirror.state(DuelView.of(g.state, 0, header.seed)).conceded)
    }
}
