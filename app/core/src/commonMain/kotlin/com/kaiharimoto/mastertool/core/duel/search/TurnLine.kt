package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.mapper.MapperWords
import com.kaiharimoto.mastertool.core.duel.mapper.MapStep
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.LineStep

/**
 * A turn's line made on a duel (E.2): each step committed as one group — the engine's moves with their tags, the Sets, each
 * attack with its damage and destruction — so it opens as a replay and folds back to the table the search ended on.
 */
object TurnLine {
    /** What [play] came to: the duel as far as it went, its engine table, and the step that could not be made, if any. */
    class Played(val game: DuelGame, val table: FxTable, val problem: String? = null)

    /** [steps] made on [game] from [t] (its engine table) by [seat], each stamped [by]. Never throws. */
    fun play(game: DuelGame, t: FxTable, seat: Int, steps: List<TurnStep>, by: Provenance? = null): Played {
        var g = game
        var at = t
        for ((i, step) in steps.withIndex()) {
            when (step) {
                is TurnStep.Move -> {
                    var k = 0
                    val p = runCatching { FxEngine.play(at, step.seat, step.move) { step.answers.getOrNull(k++) ?: Chooser.CANCEL } }.getOrNull()
                    if (p !is FxPlay.Done) return Played(g, at, "Step ${i + 1} (${step.move}) was not made again: ${(p as? FxPlay.Refused)?.why ?: "cancelled"}")
                    val r = g.act(p.actions, step.seat, by = by.takeIf { step.seat == seat }, fx = p.tags)
                    if (!r.ok) return Played(g, at, r.problem)
                    g = r.game
                    at = FxTable(p.state, p.fx, at.book, at.facts, at.seed)
                }
                is TurnStep.Sets -> {
                    val zones = g.state.freeZones(seat, ZoneKind.SPELL)
                    if (zones.size < step.uids.size) return Played(g, at, "No Spell & Trap Zone was free to Set a card in.")
                    val actions = step.uids.mapIndexed { n, u -> DuelAction.Move(u, zones[n], CardPosition.FACE_DOWN_DEF, "set") }
                    val r = g.act(actions, seat, by = by)
                    if (!r.ok) return Played(g, at, r.problem)
                    g = r.game
                    at = FxTable(g.state, at.fx.copy(setCards = at.fx.setCards + step.uids), at.book, at.facts, at.seed)
                }
                is TurnStep.Attack -> {
                    val before = at
                    val r = g.act(step.step.actions, seat, by = by)
                    if (!r.ok) return Played(g, at, r.problem)
                    var fx = before.fx
                    var s = before.state
                    // The engine's state folded over the attack's actions as the log holds them.
                    r.game.played.takeLast(step.step.actions.size).forEach { e ->
                        val next = DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok ?: return@forEach
                        fx = FxFold.step(fx, s, e, next.state, before.book, before.facts)
                        s = next.state
                    }
                    g = r.game
                    at = FxTable(g.state, fx, before.book, before.facts, before.seed)
                }
            }
        }
        return Played(g, at)
    }

    /** The line in words, the other seat's passes left out: "Normal Summon A · Activate B · To the Battle Phase · A attacks directly". */
    fun words(steps: List<TurnStep>, seat: Int, name: (Int) -> String, uidName: (Int) -> String): List<String> = steps.mapNotNull { s ->
        when (s) {
            is TurnStep.Move -> if (s.seat != seat || s.move == FxMove.Pass || s.move == FxMove.Resolve) null
            else MapperWords.step(MapStep.of(LineStep(s.seat, s.move, s.answers, s.card)), name)
            is TurnStep.Sets -> "Set ${s.uids.joinToString(" and ") { uidName(it) }}"
            is TurnStep.Attack -> s.step.attack.let { a -> "${uidName(a.attacker)} attacks " + (a.target?.let { uidName(it) } ?: "directly") }
        }
    }
}
