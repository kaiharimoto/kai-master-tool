package com.kaiharimoto.mastertool.core.duel.mapper.stress

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.effects.FxState
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import kotlinx.serialization.Serializable

/**
 * A stress test's table (E.md §4.1, M.md §3.1): the mapper's [deal] for seat 0, and [against] — the interruptions under test,
 * canonical passcodes — in seat 1's hand, its field empty. A two-player table (never `solo`): after each link the other seat
 * may respond, as at a real table. Seat 0's cards take the same uids as the mapper's own deal of [deal], so a kept line
 * plays here move for move.
 */
@Serializable
data class StressDeal(val deal: MapDeal, val against: List<Int>) {
    /** The deal as a duel at seat 0's Main Phase 1: both hands drawn, behind undo's reach. */
    fun game(main: List<Int>, extra: List<Int>, name: String = ""): DuelGame {
        val header = DuelHeader(
            id = "stress-${deal.seed}-${deal.dealt.joinToString(".")}-${against.sorted().joinToString(".")}",
            seed = deal.seed,
            seats = listOf(
                SeatSetup(name = name, main = deal.order(main), extra = extra.sorted()),
                SeatSetup(name = THEM, main = against.sorted()),
            ),
            first = if (deal.first) 0 else 1,
            solo = false,
            handSize = 0,
        )
        val base = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        val acts = buildList {
            if (!deal.first) add(DuelAction.EndTurn)
            if (deal.dealt.isNotEmpty()) add(DuelAction.Draw(0, deal.dealt.size))
            if (against.isNotEmpty()) add(DuelAction.Draw(1, against.size))
            add(DuelAction.Phase(DuelPhase.MAIN1))
        }
        val done = base.act(acts, null)
        check(done.ok) { "the stress test's deal was refused: ${done.problem}" }
        return done.game.copy(floor = done.game.cursor)
    }

    /** The engine's view of the deal. */
    fun table(main: List<Int>, extra: List<Int>, kit: GoldfishKit): FxTable {
        val g = game(main, extra)
        return FxTable(g.state, FxState.at(g.state), kit.book, kit.facts, g.header.seed)
    }

    companion object {
        /** The other seat's name on a stress table. */
        const val THEM = "Interruptions"
    }
}
