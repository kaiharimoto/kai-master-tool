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
 *
 * [fodder] is dealt with the hand and is not part of it: the deck's cards that do nothing ([StarterTable.fodder]), there so a
 * starter is mapped as it is opened — beside other cards a cost may discard — and not alone in an empty hand.
 */
@Serializable
data class MapDeal(
    val hand: List<Int>,
    val first: Boolean = true,
    val seed: Long = 1L,
    val fodder: List<Int> = emptyList(),
) {
    /** Every card dealt: [hand], then [fodder]. */
    val dealt: List<Int> get() = hand + fodder

    /**
     * The Main Deck as dealt: [dealt] first, then [main] less one copy of each dealt card, sorted and riffled by [seed]. Sorted
     * first, so the uids a line names never depend on the order the decklist happens to list its cards in.
     */
    fun order(main: List<Int>): List<Int> {
        val rest = main.toMutableList()
        dealt.forEach { c -> check(rest.remove(c)) { "the hand holds a card the Main Deck does not: #$c" } }
        return dealt + DuelRandom.riffle(rest.sorted(), seed)
    }

    /** The uids of the [fodder] in the hand of [t], a table this deal made: what every board leaves out ([BoardCards.of]). */
    fun fodderUids(t: FxTable): Set<Int> {
        val want = fodder.groupingBy { it }.eachCount().toMutableMap()
        return t.state.seats[0].hand.filter { u ->
            val c = t.code(u) ?: return@filter false
            val n = want[c] ?: 0
            if (n > 0) { want[c] = n - 1; true } else false
        }.toSet()
    }

    /**
     * The deal as a duel: a one-player table (`solo`), the hand drawn, at seat 0's Main Phase 1; going second, the turn
     * passed once first. The deal is behind undo's reach, as a goldfish hand's is.
     */
    fun game(main: List<Int>, extra: List<Int>, name: String = ""): DuelGame {
        val header = DuelHeader(
            id = "mapper-$seed-${dealt.joinToString(".")}",
            seed = seed,
            seats = listOf(SeatSetup(name = name, main = order(main), extra = extra.sorted()), SeatSetup()),
            first = 0,
            solo = true,
            handSize = 0,
        )
        val base = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        val deal = buildList {
            if (!first) add(DuelAction.EndTurn)
            if (dealt.isNotEmpty()) add(DuelAction.Draw(0, dealt.size))
            add(DuelAction.Phase(DuelPhase.MAIN1))
        }
        val done = base.act(deal, null)
        check(done.ok) { "the mapper's deal was refused: ${done.problem}" }
        return done.game.copy(floor = done.game.cursor)
    }

    /** The engine's view of the deal. */
    fun table(main: List<Int>, extra: List<Int>, kit: GoldfishKit): FxTable {
        val g = game(main, extra)
        return FxTable(g.state, FxState.at(g.state), kit.book, kit.facts, g.header.seed)
    }
}
