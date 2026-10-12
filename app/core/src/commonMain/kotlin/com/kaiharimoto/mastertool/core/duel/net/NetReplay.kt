package com.kaiharimoto.mastertool.core.duel.net

import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.record.DuelResults

/**
 * A networked duel kept or reviewed while it goes on (the red team's R8): the host's log is the whole duel, the guest's hidden
 * cards and choices in it, while the table shows each player only their own seat. A replay of it, or a review naming the
 * guest's decisions, would hand the host what the table keeps from them — so neither is allowed until the duel has ended.
 */
object NetReplay {
    const val REFUSED = "A networked duel is two people's while it goes on: keep it as a replay, or review it, once it has ended."

    /** Why the duel in play ([live]) may not be kept as a replay now, or null when it may. */
    fun keepRefusal(networked: Boolean, live: DuelState?): String? =
        if (networked && live != null && DuelResults.ending(live) == null) REFUSED else null

    /** Why a replay of [record] may not be reviewed now: it is the networked duel still in play ([live]); else null. */
    fun reviewRefusal(networked: Boolean, live: DuelGame?, record: DuelHeader): String? =
        if (live != null && live.header.id == record.id) keepRefusal(networked, live.state) else null
}
