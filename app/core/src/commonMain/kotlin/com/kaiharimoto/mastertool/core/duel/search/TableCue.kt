package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.match.CueKind

/**
 * What is asked of a seat at kai's own table now (Phase E, E.3b): the cue a match's referee would give it, read off the
 * table and the engine's priority — an ask to move on to answer, a window its move must answer, a chain it has priority
 * on (its own link the other passed on: resolve it), its turn to play — or null when nothing waits on it. The opening roll
 * is the table's own (its dice are thrown for it), and a one-player table has no other seat.
 */
object TableCue {
    fun of(t: FxTable, seat: Int): CueKind? {
        val s: DuelState = t.state
        if (s.solo || s.conceded != null || s.seats.any { it.lp <= 0 } || s.beforeTurnOne) return null
        s.proposal?.let { p -> return if (p.seat != seat) CueKind.ANSWER else null }
        s.window?.let { w -> return if (w.responder == seat) CueKind.RESPOND else null }
        if (s.chain.isNotEmpty()) {
            if (FxEngine.next(t) != seat) return null
            return if (s.chain.last().seat == seat && t.fx.passes >= 1) CueKind.RESOLVE else CueKind.CHAIN
        }
        return if (s.active == seat) CueKind.PLAY else null
    }
}
