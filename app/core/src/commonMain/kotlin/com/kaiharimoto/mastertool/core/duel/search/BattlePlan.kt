package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.Attack
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * A Battle Phase planned (Phase E, E.2; `docs/phases/E.md` §4.2): every order of attacks the rules allow, each attack worked
 * out by the printed numbers ([DuelBattle], as the puzzles' referee works it out), and the best of them by a value the caller
 * gives. One attack a monster, from face-up Attack Position; a direct attack only when the other seat controls no monster; a
 * face-down monster turned face-up as it is attacked. **No battle effects**: the engine has none yet, so a card whose text
 * changes a battle fights by its printed numbers, and a plan says so where it matters ([Plan.printed]).
 *
 * A table of positions makes two orders that come to the same table one; ending the Battle Phase is an option from every
 * position, so the plan never attacks into a loss it could have left alone.
 */
object BattlePlan {
    /** One attack: what was declared, and every action it came to (the flip, the attack, damage and destruction). */
    class Step(val attack: DuelAction.Attack, val actions: List<DuelAction>)

    /** The attacks in order, what they leave ([end]), and its value. */
    class Plan<N>(val steps: List<Step>, val end: N, val value: Double)

    /** Positions one plan may visit: far more than five attackers into five monsters need. */
    const val MOST_POSITIONS = 50_000

    /**
     * The best Battle Phase for [seat] from [s] (in the Battle Phase), by [value] of the table it leaves: the attacks in
     * order. None at all when no attack is worth making.
     */
    fun best(s: DuelState, seat: Int, catalog: DuelCatalog, value: (DuelState) -> Double, most: Int = MOST_POSITIONS): Plan<DuelState> =
        best(s, { it }, { at, step -> DuelRules.applyAll(at, step.actions, seat).first }, seat, catalog, value, most)

    /**
     * The same over any [start] that carries a table ([state]): [apply] makes a step on it (the engine's table folds the
     * actions as the log will), and the plan ends on what the steps made.
     */
    fun <N> best(
        start: N,
        state: (N) -> DuelState,
        apply: (N, Step) -> N?,
        seat: Int,
        catalog: DuelCatalog,
        value: (N) -> Double,
        most: Int = MOST_POSITIONS,
    ): Plan<N> {
        class Got(val value: Double, val steps: List<Step>, val end: N)
        // The monsters that attacked are the plan's to remember: the table forgets an attack once its target leaves the field.
        val memo = HashMap<Pair<DuelState, Set<Int>>, Got>()
        var visited = 0
        fun go(at: N, attacked: Set<Int>): Got {
            val s = state(at)
            val key = s to attacked
            memo[key]?.let { return it }
            var best = Got(value(at), emptyList(), at)
            if (++visited <= most) {
                for (step in attacks(s, seat, catalog, attacked)) {
                    val after = apply(at, step) ?: continue
                    val got = go(after, attacked + step.attack.attacker)
                    if (got.value > best.value) best = Got(got.value, listOf(step) + got.steps, got.end)
                }
            }
            memo[key] = best
            return best
        }
        val got = go(start, state(start).attacks.map { it.attacker }.toSet())
        return Plan(got.steps, got.end, got.value)
    }

    /**
     * Every attack [seat] may declare on [s], each with what it comes to; none outside the Battle Phase. [attacked]: the
     * monsters that have attacked this turn.
     */
    fun attacks(s: DuelState, seat: Int, catalog: DuelCatalog, attacked: Set<Int> = s.attacks.map { it.attacker }.toSet()): List<Step> {
        if (s.phase != DuelPhase.BATTLE) return emptyList()
        val attackers = monsters(s, seat).filter { it !in attacked && DuelVerbs.canAttack(s, seat, it) }
        val targets = monsters(s, 1 - seat)
        return attackers.flatMap { a ->
            val aims: List<Int?> = if (targets.isEmpty()) listOf(null) else targets
            aims.mapNotNull { t -> step(s, seat, a, t, catalog) }
        }
    }

    /** [attacker] declared at [target] (null: directly), and what battle makes of it; null when the numbers cannot be read. */
    fun step(s: DuelState, seat: Int, attacker: Int, target: Int?, catalog: DuelCatalog): Step? {
        val flip = target?.takeIf { s.cards[it]?.faceUp == false }?.let { listOf(DuelAction.Position(it, CardPosition.FACE_UP_DEF)) }.orEmpty()
        val declare = DuelAction.Attack(seat, attacker, target)
        val declared = DuelRules.applyAll(s, flip + declare, seat).first ?: return null
        val outcome = DuelBattle.outcome(declared, Attack(seat, attacker, target), catalog) ?: return null
        return Step(declare, flip + declare + DuelBattle.actions(declared, outcome))
    }

    /** [seat]'s monsters on the field, the Extra Monster Zones it controls counted in. */
    fun monsters(s: DuelState, seat: Int): List<Int> =
        s.cards.values.filter { c ->
            c.controller == seat && s.placeOf(c.uid).let { it is Place.Zone && (it.kind == ZoneKind.MONSTER || it.kind == ZoneKind.EMZ) }
        }.map { it.uid }.sorted()
}
