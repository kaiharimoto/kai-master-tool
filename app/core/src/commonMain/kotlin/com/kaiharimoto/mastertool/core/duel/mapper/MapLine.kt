package com.kaiharimoto.mastertool.core.duel.mapper

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
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.LineStep
import kotlinx.serialization.Serializable

/*
 * A line kept in the board library (M.md §2.6, kai: "each sequence is replayable"): the deal it starts from and its moves with
 * their answers, by uid — the deal makes the same uids every time, so the line plays again exactly on the Duel page.
 */

/** One move of a kept line: [kind] a, n, s, p, f, x or r (activate, Normal Summon, Set a monster, procedure, phase, pass, resolve). */
@Serializable
data class MapStep(
    val seat: Int = 0,
    val kind: String,
    val uid: Int = 0,
    /** The effect's id (activate), or the procedure's index (as text). */
    val what: String = "",
    val answers: List<List<Int>> = emptyList(),
    /** The card the move is about (canonical), for words and pictures. */
    val card: Int? = null,
) {
    fun move(): FxMove = when (kind) {
        "a" -> FxMove.Activate(uid, what)
        "n" -> FxMove.NormalSummon(uid)
        "s" -> FxMove.NormalSummon(uid, set = true)
        "p" -> FxMove.Procedure(uid, what.toInt())
        "f" -> FxMove.Phase(DuelPhase.valueOf(what))
        "x" -> FxMove.Pass
        else -> FxMove.Resolve
    }

    companion object {
        fun of(s: LineStep): MapStep = when (val m = s.move) {
            is FxMove.Activate -> MapStep(s.seat, "a", m.uid, m.effect, s.answers, s.card)
            is FxMove.NormalSummon -> MapStep(s.seat, if (m.set) "s" else "n", m.uid, "", s.answers, s.card)
            is FxMove.Procedure -> MapStep(s.seat, "p", m.uid, m.proc.toString(), s.answers, s.card)
            is FxMove.Phase -> MapStep(s.seat, "f", 0, m.to.name, s.answers)
            FxMove.Pass -> MapStep(s.seat, "x", answers = s.answers)
            FxMove.Resolve -> MapStep(s.seat, "r", answers = s.answers)
        }
    }
}

/** A kept line: [deal], its [steps], and the cards Set at the turn's end ([sets], uids). */
@Serializable
data class MapLine(
    val deal: MapDeal,
    val steps: List<MapStep>,
    val sets: List<Int> = emptyList(),
) {
    /** Cards the deal starts with: what a player needs in hand. */
    val starter: List<Int> get() = deal.hand.sorted()

    /** How costly the line is, to pick between lines to one board: fewer starting cards, then fewer moves. */
    val cost: Int get() = deal.hand.size * 1_000 + steps.size

    companion object {
        fun of(deal: MapDeal, end: MapSearch.End): MapLine = MapLine(deal, end.line.map(MapStep::of), end.sets)
    }
}

/** A kept line made again (M.md §2.6): the deal, then each move played by the engine and committed to a duel the page opens. */
object MapReplay {
    /** [game] as far as the line went, [table] the engine's view of it, and [problem] when a move could not be made again. */
    class Replay(val game: DuelGame, val table: FxTable, val problem: String? = null)

    fun of(line: MapLine, main: List<Int>, extra: List<Int>, kit: GoldfishKit): Replay {
        var game = line.deal.game(main, extra)
        var t = line.deal.table(main, extra, kit)
        var setDone = line.sets.isEmpty()
        fun sets(): String? {
            setDone = true
            line.sets.forEach { uid ->
                val zone = game.state.freeZones(0, ZoneKind.SPELL).firstOrNull() ?: return "No Spell & Trap Zone was free to Set a card in."
                val r = game.act(listOf(DuelAction.Move(uid, zone, CardPosition.FACE_DOWN_DEF, "set")), 0)
                if (!r.ok) return r.problem
                game = r.game
            }
            t = FxTable(game.state, t.fx.copy(setCards = t.fx.setCards + line.sets), t.book, t.facts, t.seed)
            return null
        }
        for (step in line.steps) {
            val move = step.move()
            if (!setDone && (move as? FxMove.Phase)?.to == DuelPhase.END) sets()?.let { return Replay(game, t, it) }
            val p = FxEngine.play(t, step.seat, move, Answers(step.answers))
            if (p !is FxPlay.Done) return Replay(game, t, "The move $move was not made again: ${(p as? FxPlay.Refused)?.why ?: "cancelled"}")
            val r = game.act(p.actions, step.seat, fx = p.tags)
            if (!r.ok) return Replay(game, t, r.problem)
            game = r.game
            t = FxTable(p.state, p.fx, t.book, t.facts, t.seed)
        }
        if (!setDone) sets()?.let { return Replay(game, t, it) }
        return Replay(game, t)
    }

    private class Answers(private val answers: List<List<Int>>) : Chooser {
        private var i = 0
        override fun choose(d: Decision): List<Int> = answers.getOrNull(i++) ?: Chooser.CANCEL
    }
}
