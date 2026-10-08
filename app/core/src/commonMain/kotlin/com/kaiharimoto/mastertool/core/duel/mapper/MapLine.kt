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
import com.kaiharimoto.mastertool.core.duel.effects.FxState
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
    /** The move, or null when this build cannot read it (a phase or a kind a newer build wrote). */
    fun move(): FxMove? = when (kind) {
        "a" -> FxMove.Activate(uid, what)
        "n" -> FxMove.NormalSummon(uid)
        "s" -> FxMove.NormalSummon(uid, set = true)
        "p" -> what.toIntOrNull()?.let { FxMove.Procedure(uid, it) }
        "f" -> DuelPhase.entries.firstOrNull { it.name == what }?.let { FxMove.Phase(it) }
        "x" -> FxMove.Pass
        "r" -> FxMove.Resolve
        else -> null
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

/**
 * A kept line: [deal], its [steps], and the cards Set at the turn's end ([sets], uids). [deck] is the deck's fingerprint when
 * it was found: uids are the deal's, so a line found on another version of the deck may not play again ([BoardLibrary.add]
 * keeps only the current deck's lines, and [BoardLibrary.revalidated] replays the rest).
 */
@Serializable
data class MapLine(
    val deal: MapDeal,
    val steps: List<MapStep>,
    val sets: List<Int> = emptyList(),
    val deck: String = "",
) {
    /** Cards the deal starts with: what a player needs in hand. */
    val starter: List<Int> get() = deal.hand.sorted()

    /** How costly the line is, to pick between lines to one board: fewer starting cards, then fewer moves. */
    val cost: Int get() = deal.hand.size * 1_000 + steps.size

    /** The line in one canonical text: what breaks a tie between lines of one cost, the same on every device. */
    val text: String get() = buildString {
        append(deal.hand.joinToString(".")).append('+').append(deal.fodder.joinToString(".")).append('/').append(deal.first).append('/').append(deal.seed)
        steps.forEach { append(';').append(it.seat).append(it.kind).append(it.uid).append(':').append(it.what).append(it.answers) }
        append("|S").append(sets.joinToString(","))
    }

    companion object {
        fun of(deal: MapDeal, end: MapSearch.End, deck: String = ""): MapLine = MapLine(deal, end.line.map(MapStep::of), end.sets, deck)
    }
}

/** A kept line made again (M.md §2.6): the deal, then each move played by the engine and committed to a duel the page opens. */
object MapReplay {
    /**
     * [game] as far as the line went, [table] the engine's view of it, and [problem] when a move could not be made again. Both
     * are null when the deal itself could not be made (the deck no longer holds a card of the hand).
     */
    class Replay(val game: DuelGame?, val table: FxTable?, val problem: String? = null, private val fodder: Set<Int> = emptySet()) {
        /** The board the line ended on, keyed as the search keyed it, or null when it did not play to the end. */
        val key: String? get() = if (problem == null && table != null) BoardKey.of(BoardCards.of(table, 0, fodder), BoardTraits.of(table, 0, fodder)) else null
    }

    /** [line] played again on the deck [main] and [extra]. Never throws: what could not be made again is the [Replay.problem]. */
    fun of(line: MapLine, main: List<Int>, extra: List<Int>, kit: GoldfishKit): Replay {
        val dealt = runCatching { line.deal.game(main, extra) }
        var game = dealt.getOrElse { return Replay(null, null, "The hand could not be dealt again: ${it.message}") }
        var t = FxTable(game.state, FxState.at(game.state), kit.book, kit.facts, game.header.seed)
        val fodder = line.deal.fodderUids(t)
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
            val move = step.move() ?: return Replay(game, t, "A move of the line (${step.kind} ${step.what}) is not one this version can read.")
            if (!setDone && (move as? FxMove.Phase)?.to == DuelPhase.END) sets()?.let { return Replay(game, t, it) }
            val p = runCatching { FxEngine.play(t, step.seat, move, Answers(step.answers)) }
                .getOrElse { return Replay(game, t, "The move $move failed: ${it.message}") }
            if (p !is FxPlay.Done) return Replay(game, t, "The move $move was not made again: ${(p as? FxPlay.Refused)?.why ?: "cancelled"}")
            val r = game.act(p.actions, step.seat, fx = p.tags)
            if (!r.ok) return Replay(game, t, r.problem)
            game = r.game
            t = FxTable(p.state, p.fx, t.book, t.facts, t.seed)
        }
        if (!setDone) sets()?.let { return Replay(game, t, it) }
        return Replay(game, t, fodder = fodder)
    }

    private class Answers(private val answers: List<List<Int>>) : Chooser {
        private var i = 0
        override fun choose(d: Decision): List<Int> = answers.getOrNull(i++) ?: Chooser.CANCEL
    }
}
