package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.net.NetReplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The red team's R8: a networked duel is two people's while it goes on — the host's record holds the guest's hidden cards
 * and choices — so it is neither kept as a replay nor reviewed until it has ended. A table on one screen always may.
 */
class NetReplayTest {
    @Test
    fun aNetworkedDuelStillGoingIsNeitherKeptNorReviewed() {
        val live = DuelGame.start(header())
        assertEquals(NetReplay.REFUSED, NetReplay.keepRefusal(networked = true, live = live.state))
        assertEquals(NetReplay.REFUSED, NetReplay.reviewRefusal(networked = true, live = live, record = live.header))
    }

    @Test
    fun anEndedOrUnnetworkedDuelMayBe() {
        val live = DuelGame.start(header())
        assertNull(NetReplay.keepRefusal(networked = false, live = live.state), "a table on one screen")
        val conceded = live.state.copy(conceded = 1)
        assertNull(NetReplay.keepRefusal(networked = true, live = conceded), "conceded: the duel has ended")
        val down = live.state.copy(seats = live.state.seats.mapIndexed { i, s -> if (i == 0) s.copy(lp = 0) else s })
        assertNull(NetReplay.keepRefusal(networked = true, live = down), "at 0 LP: the duel has ended")
        assertNull(NetReplay.keepRefusal(networked = true, live = null), "no duel in play")
        // A replay of another duel is reviewed, whatever the networked one does.
        val other = header(seed = 7L).copy(id = "another")
        assertNull(NetReplay.reviewRefusal(networked = true, live = live, record = other))
        assertNull(NetReplay.reviewRefusal(networked = true, live = live.copy(state = conceded), record = live.header))
    }
}
