package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRandom
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.effects.FxState
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import kotlinx.serialization.Serializable

/*
 * Gameplay Mapper (Phase M, `docs/phases/M.md`): a hand chosen, not dealt. The goldfish deals hand k from a seed; the mapper
 * also starts from the hands a player names — a starter alone, a pair — so its table is made from the hand itself.
 */

/**
 * A hand to map: [hand] on top of the Main Deck (canonical passcodes, in hand order), the rest of the deck under it in the
 * duel's riffle keyed by [seed], going first or second. Everything a line needs to be made again is here and the deck's own
 * lists: the same deal makes the same uids, so a kept line replays exactly ([MapReplay]).
 */
@Serializable
data class MapDeal(
    val hand: List<Int>,
    val first: Boolean = true,
    val seed: Long = 1L,
) {
    /** The Main Deck as dealt: [hand] first, then [main] less one copy of each hand card, riffled by [seed]. */
    fun order(main: List<Int>): List<Int> {
        val rest = main.toMutableList()
        hand.forEach { c -> check(rest.remove(c)) { "the hand holds a card the Main Deck does not: #$c" } }
        return hand + DuelRandom.riffle(rest, seed)
    }

    /**
     * The deal as a duel: a one-player table (`solo`), the hand drawn, at seat 0's Main Phase 1; going second, the turn
     * passed once first. The deal is behind undo's reach, as a goldfish hand's is.
     */
    fun game(main: List<Int>, extra: List<Int>, name: String = ""): DuelGame {
        val header = DuelHeader(
            id = "mapper-$seed-${hand.joinToString(".")}",
            seed = seed,
            seats = listOf(SeatSetup(name = name, main = order(main), extra = extra), SeatSetup()),
            first = 0,
            solo = true,
            handSize = 0,
        )
        val base = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        val deal = buildList {
            if (!first) add(DuelAction.EndTurn)
            if (hand.isNotEmpty()) add(DuelAction.Draw(0, hand.size))
            add(DuelAction.Phase(DuelPhase.MAIN1))
        }
        val dealt = base.act(deal, null)
        check(dealt.ok) { "the mapper's deal was refused: ${dealt.problem}" }
        return dealt.game.copy(floor = dealt.game.cursor)
    }

    /** The engine's view of the deal. */
    fun table(main: List<Int>, extra: List<Int>, kit: GoldfishKit): FxTable {
        val g = game(main, extra)
        return FxTable(g.state, FxState.at(g.state), kit.book, kit.facts, g.header.seed)
    }
}
