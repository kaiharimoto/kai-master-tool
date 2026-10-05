package com.kaiharimoto.mastertool.core.duel.effects

/**
 * The chain, triggers and timing (D.md §2.3) — **agent (b) fills this file**; agent (a) left the signatures [FxEngine]
 * calls, answering nothing yet.
 *
 * What it holds, once filled:
 * - **Open state**: with the chain empty and nothing pending the turn player may use speed-1 effects in their Main Phase;
 *   speed 2 and 3 whenever their conditions allow (speeds from [FxRules.speed]).
 * - **Adding a link**: speed 2 or more, never below the newest link's; the other seat responds first, then priority
 *   alternates; both passing resolves the newest. Costs paid and targets chosen at activation through [FxSteps]
 *   (`part` [FxTag.COST] and [FxTag.ACTIVATE]); at resolution a target that left or no longer matches is dropped. A
 *   negated link resolves doing nothing (`DuelAction.Negate`). The chain's own Spells and Traps go to the GY through
 *   `DuelVerbs.resolve`. Once-per-turn is counted at activation ([FxRules.optRefusal], [FxRules.use]).
 * - **Batches and timing**: `AND`/`AND_IF_YOU_DO` one batch, `THEN`/`ALSO` a new one ([FxEvent.batch]); an optional
 *   `WHEN` trigger whose event was not last misses the timing.
 * - **SEGOC**: triggers gathered ([gather]) wait for the chain or action to end, then form a chain — the turn player's
 *   mandatory, their optional, the other's mandatory, the other's optional — each player ordering their own
 *   ([Decision.Order]).
 * - Bounds (§7): a chain of at most 32 links, at most 64 triggers gathered at once, a loop stopped.
 */
object FxChain {
    /** The activations, passes and resolutions [seat] may make on [t] (already [FxTable.current]): agent (b). */
    fun moves(t: FxTable, seat: Int): List<FxMove> = emptyList()

    /** An [FxMove.Activate], [FxMove.Pass] or [FxMove.Resolve] made: agent (b). */
    fun play(t: FxTable, seat: Int, move: FxMove, chooser: Chooser): FxPlay =
        FxPlay.Refused("The engine does not activate effects yet.")

    /**
     * [events] happened on [t] (a summon, a batch, a resolution): the triggers they set off, put in [FxState.pending] to
     * wait for the chain or action to end — agent (b). Until then nothing is gathered.
     */
    fun gather(t: FxTable, events: List<FxEvent>): FxState = t.fx
}
