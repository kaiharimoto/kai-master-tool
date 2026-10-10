package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFork
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit

/**
 * "Solve this turn" (Phase E, E.2; `docs/phases/E.md` §4.2): the best whole turn for the seat whose turn it is, searched on
 * the table **as that seat sees it** — a fork of the duel built from its view alone ([DuelFork]): the other seat's hand,
 * Deck and Set cards unknown (and so playing nothing), the seat's own Deck in an order it cannot know. The engine's state
 * (once-per-turn uses, this turn's summons) is the live duel's, folded from its log.
 *
 * What the search assumes is said with every answer ([ASSUMES]): the other seat holds nothing it uses, and battle effects
 * fight by their printed numbers. The line is committed on the fork ([Solved.game]), so it opens as an unsaved replay.
 */
object TurnSolve {
    /** What a solve found: the [result], the fork it was searched on, and the fork with the line made on it ([game]). */
    class Solved(val seat: Int, val result: TurnResult, val fork: DuelGame, val game: DuelGame, val problem: String? = null)

    /** Said beside every answer: what the search could not see. */
    const val ASSUMES = "Assuming they hold nothing they can use (their hand and Set cards are unknown to you), and that battle " +
        "goes by the printed ATK and DEF."

    /** The same, when the seat sees everything (full knowledge): their cards are known, and still used for nothing. */
    const val ASSUMES_SEEN = "Assuming they use nothing they hold, and that battle goes by the printed ATK and DEF."

    /** Why [seat] cannot have its turn solved on [game] now, or null. */
    fun refusal(game: DuelGame, seat: Int): String? {
        val s = game.state
        return when {
            s.conceded != null || s.seats.any { it.lp <= 0 } -> "The duel is over."
            s.active != seat -> "It is not that seat's turn: a turn is solved by the player whose turn it is."
            s.phase != DuelPhase.MAIN1 && s.phase != DuelPhase.MAIN2 -> "A turn is solved from a Main Phase: it is the ${s.phase.label} Phase."
            else -> null
        }
    }

    /**
     * [seat]'s turn on [game], read with [knows] (`DuelBrief`'s knowledge: the seat's own eyes by default), searched within
     * [budget] engine moves. Null when it cannot be ([refusal]).
     */
    fun solve(
        game: DuelGame,
        seat: Int,
        kit: GoldfishKit,
        knows: String = DuelBrief.SELF,
        budget: Int = TurnSearch.DEFAULT_BUDGET,
        by: Provenance? = null,
        cancelled: () -> Boolean = { false },
    ): Solved? {
        if (refusal(game, seat) != null) return null
        val fork = DuelFork.table(DuelFork.source(game, seat, knows), game.header.seed xor FORK_SALT, "solve-${game.header.id}")
        val fx = FxFold.fold(game.header, game.played, kit.book, kit.facts, game.state)
        val t = FxTable(fork.state, fx, kit.book, kit.facts, fork.header.seed)
        val result = TurnSearch(kit, budget = budget, cancelled = cancelled).search(t, seat)
        val played = TurnLine.play(fork, t, seat, result.steps, by)
        return Solved(seat, result, fork, played.game, played.problem)
    }

    /** The fork's own seed: its Decks shuffled apart from the live duel's. */
    private const val FORK_SALT = 0x5f0e_7a11L
}
