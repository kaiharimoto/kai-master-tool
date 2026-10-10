package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxTable

/**
 * **Any hand opens as a replay** (D.md §5.6): hand k made again from the seed and its index — its own deal, searched (or its
 * line played) again exactly as the run did — and its line committed through `DuelGame.act`, every entry tagged with the
 * engine's own [com.kaiharimoto.mastertool.core.duel.effects.FxTag]s, one group a move. The game is the Duel page's to show;
 * it is stored only if the person saves it.
 */
object GoldfishReplay {
    /**
     * Hand k as a duel: [game] (the deal behind undo's reach, then the line), how the hand ended ([found]), its skeleton, and
     * [problem] when a move of the line could not be committed (it never should: the engine's actions are the table's).
     */
    data class Replay(val game: DuelGame, val found: GoldfishSearch.Found, val skeleton: String, val problem: String? = null) {
        val end: HandEnd get() = found.end
    }

    /** Hand [k] of [setup], with [kit]: searched again on its own and its line played onto its deal. */
    fun of(setup: GoldfishSetup, kit: GoldfishKit, k: Int): Replay {
        val main = setup.deck.main.map(kit::canonical)
        val extra = setup.deck.extra.map(kit::canonical)
        val found = Goldfish.hand(setup, kit, k)
        var game = GoldfishHands.game(main, extra, setup.seed, k, setup.first, setup.deck.name, setup.deal)
        var t = GoldfishHands.table(game, kit)
        val skeleton = GoldfishWords.skeleton(found, kit)
        if (found.end != HandEnd.REACHED) return Replay(game, found, skeleton)
        var setDone = found.sets.isEmpty()
        fun sets(): String? {
            setDone = true
            found.sets.forEach { uid ->
                val zone = game.state.freeZones(0, ZoneKind.SPELL).firstOrNull() ?: return "No Spell & Trap Zone was free to Set a card in."
                val r = game.act(listOf(DuelAction.Move(uid, zone, CardPosition.FACE_DOWN_DEF, "set")), 0)
                if (!r.ok) return r.problem
                game = r.game
            }
            t = FxTable(game.state, t.fx.copy(setCards = t.fx.setCards + found.sets), t.book, t.facts, t.seed)
            return null
        }
        for (step in found.line) {
            // The Spells and Traps Set at the turn's end are Set before it ends.
            if (!setDone && (step.move as? FxMove.Phase)?.to == DuelPhase.END) sets()?.let { return Replay(game, found, skeleton, it) }
            val p = FxEngine.play(t, step.seat, step.move, Answers(step.answers))
            if (p !is FxPlay.Done) return Replay(game, found, skeleton, "The line's move ${step.move} was not made again: ${(p as? FxPlay.Refused)?.why ?: "cancelled"}")
            val r = game.act(p.actions, step.seat, fx = p.tags)
            if (!r.ok) return Replay(game, found, skeleton, r.problem)
            game = r.game
            t = FxTable(p.state, p.fx, t.book, t.facts, t.seed)
        }
        if (!setDone) sets()?.let { return Replay(game, found, skeleton, it) }
        return Replay(game, found, skeleton)
    }

    /** The line's answers, in the order the engine asks again. */
    private class Answers(private val answers: List<List<Int>>) : Chooser {
        private var i = 0
        override fun choose(d: Decision): List<Int> = answers.getOrNull(i++) ?: Chooser.CANCEL
    }
}
