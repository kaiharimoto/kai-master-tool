package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelState

/**
 * Who is running steps, for what (the step executor's context): [seat] using [uid]'s ([card], canonical) [effect] — an
 * effect id, or [FxTag.PROC] for an inherent summon's cost — in its [part] ([FxTag.COST], [FxTag.ACTIVATE],
 * [FxTag.RESOLVE]), on chain link [link] when it has one, with the cards [bound] so far ([Pick.SELF] is always [uid];
 * [Pick.TARGETS] once targets are chosen). Every action the steps make is tagged from this.
 */
data class FxAct(
    val seat: Int,
    val uid: Int,
    val card: Int,
    val effect: String,
    val part: String,
    val link: Int? = null,
    val bound: Map<String, List<Int>> = emptyMap(),
    val script: String = "",
    val verified: Boolean = false,
    /** What was declared so far ([Op.Declare]), by name. */
    val declared: Map<String, Declared> = emptyMap(),
) {
    /** The eyes this act's filters and conditions are judged through. */
    fun scope(t: FxTable): FxScope = FxScope(t, seat, uid, bound + (Pick.SELF to listOf(uid)), declared)

    fun tag(): FxTag = FxTag(uid, effect, part, link, script, verified)
}

/** What running steps came to. */
sealed interface FxRun {
    /**
     * The tagged [actions] (applied already: [state] is the table after them) and the engine state after; [events] in
     * their batches; [bound] the bindings after (targets, names the steps bound); [whole] false when a step could not
     * happen in full — what `AND_IF_YOU_DO` and `THEN` read.
     */
    data class Done(
        val actions: List<DuelAction>,
        val tags: List<FxTag>,
        val state: DuelState,
        val fx: FxState,
        val events: List<FxEvent> = emptyList(),
        val bound: Map<String, List<Int>> = emptyMap(),
        val whole: Boolean = true,
        val declared: Map<String, Declared> = emptyMap(),
    ) : FxRun

    data class Refused(val why: String) : FxRun

    data object Cancelled : FxRun
}

/**
 * The step executor (D.md §2.2, §2.3) — **agent (c) fills this file**; agent (a) left the signatures the summons
 * (`FxSummons`, an inherent summon's cost) and the chain (`FxChain`, agent (b)) call. (b) and (c) meet here.
 *
 * Once filled: each [Op] turned into ordinary `DuelAction`s with its `how` word and its [FxEvent]s; every choice a
 * [Decision] to the [Chooser]; [Join]s grouping steps into batches; the picks bounded ([Pick.MOST]) and judged by
 * `FxFilters`; [Op.Unknown] refused; once-per-turn uses, restrictions ([Op.Restrict], [Effect.leaves]), Level changes and
 * Normal Summon grants written into [FxState].
 */
object FxSteps {
    /** Runs [steps] for [act] on [t]: agent (c). */
    fun run(t: FxTable, act: FxAct, steps: List<Step>, chooser: Chooser): FxRun =
        if (steps.isEmpty()) FxRun.Done(emptyList(), emptyList(), t.state, t.fx, bound = act.bound)
        else FxRun.Refused("The engine does not run effects' steps yet.")

    /** Chooses [targets] for [act] as it is activated, bound as [Pick.TARGETS] (and each pick's own [Pick.bind]): agent (c). */
    fun target(t: FxTable, act: FxAct, targets: List<Pick>, chooser: Chooser): FxRun =
        if (targets.isEmpty()) FxRun.Done(emptyList(), emptyList(), t.state, t.fx, bound = act.bound)
        else FxRun.Refused("The engine does not choose targets yet.")
}
