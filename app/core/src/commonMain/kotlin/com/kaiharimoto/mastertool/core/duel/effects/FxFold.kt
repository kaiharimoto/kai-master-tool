package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelState

/**
 * [FxState] as a fold over a duel's log (D.md §2.1, §4.2) — **agent (c) fills this file**; agent (a) left the
 * signatures.
 *
 * Once filled: an entry tagged by the engine (`DuelEntry.fx`) is folded exactly — a `rule` Normal Summon counts, a `proc`
 * summon is proper, an activation's use is counted by its effect's [Opt] key ([FxRules.optKey]); a log made by hand has
 * no tags and is inferred (Normal Summons from `how` "normal" or "set" from the hand, this turn's summons from `how`),
 * marking the state [FxState.inferred]. Every move bumps its card's [FxState.lives] ([FxState.moved]). `FxFoldTest`: a
 * tagged log's fold equals the engine's own state.
 */
object FxFold {
    /** The state after [entries] on [header]'s deal: agent (c). Until then, a fresh state at the table's turn. */
    fun fold(header: DuelHeader, entries: List<DuelEntry>, book: ScriptBook, facts: FxFacts, at: DuelState): FxState = FxState.at(at)

    /** [fx] after one more [entry], the table [before] it and [after] it: agent (c). */
    fun step(fx: FxState, before: DuelState, entry: DuelEntry, after: DuelState, book: ScriptBook, facts: FxFacts): FxState =
        fx.forTurn(after.turn)
}
