package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit

/**
 * A decision by search across worlds (Phase E, E.3; `docs/phases/E.md` §4.4): every way on the deciding seat has, valued in
 * each world by the turn's search with the other seat answering from what that world dealt it ([TurnSearch], answers on),
 * and the way with the **least expected regret** — in each world, the best way's value less this one's (for the turn player;
 * the other way round for the seat answering it), averaged over the worlds' weights.
 *
 * - The budget is shared across the worlds, and a world the budget did not reach counts as unknown, never as zero: a way is
 *   weighed over the worlds it was valued in, and one valued in fewer than half of them is not chosen.
 * - Ties go to the first way in the fixed order the engine lists them ([MapMoves]), so the same table, worlds and budget
 *   give the same choice on any device.
 */
object Regret {
    /** A way on, weighed: its expected [regret], its mean value for the turn player, and how many worlds valued it. */
    class Weighed(val root: Root, val regret: Double, val mean: Double, val worlds: Int)

    /**
     * What a decision came to: the [choice] (null when nothing could be valued), every way weighed, the worlds it was made in,
     * the line the choice was valued by in each world that valued it ([lines], by world), whether every world was searched
     * whole, and the engine moves spent.
     */
    class Decision(
        val choice: Weighed?,
        val all: List<Weighed>,
        val worlds: List<World>,
        val lines: Map<Int, TurnOption>,
        val complete: Boolean,
        val moves: Int,
    )

    /** The decision for the seat that [seen] its view, over [k] worlds from [prior], within [budget] engine moves. */
    fun decide(
        seen: Seen,
        prior: Prior,
        kit: GoldfishKit,
        k: Int,
        budget: Int,
        value: TurnValue = TurnValue.V0,
        cancelled: () -> Boolean = { false },
    ): Decision {
        val worlds = Worlds.deal(seen, prior, k, kit)
        val turn = seen.view.active
        val decider = seen.seat
        val share = (budget / k.coerceAtLeast(1)).coerceAtLeast(1)
        var moves = 0
        var complete = true
        val per = ArrayList<Pair<World, List<TurnOption>>>()
        for (w in worlds) {
            if (cancelled()) {
                complete = false
                break
            }
            val t = FxTable(w.game.state, seen.fx, kit.book, kit.facts, seen.seed).current()
            val search = TurnSearch(kit, value, budget = share, cancelled = cancelled, answers = true, soft = true)
            per += w to search.options(t, turn, decider)
            moves += search.spentMoves
            if (!search.complete) complete = false
        }
        val mine = decider == turn
        // Sums by way, in the order the ways are first met.
        val regret = LinkedHashMap<Root, Double>()
        val weight = HashMap<Root, Double>()
        val mean = HashMap<Root, Double>()
        val count = HashMap<Root, Int>()
        for ((w, opts) in per) {
            if (opts.isEmpty()) continue
            val best = if (mine) opts.maxOf { it.value } else opts.minOf { it.value }
            for (o in opts) {
                val r = if (mine) best - o.value else o.value - best
                regret[o.root] = (regret[o.root] ?: 0.0) + w.weight * r
                weight[o.root] = (weight[o.root] ?: 0.0) + w.weight
                mean[o.root] = (mean[o.root] ?: 0.0) + w.weight * o.value
                count[o.root] = (count[o.root] ?: 0) + 1
            }
        }
        val searched = per.count { it.second.isNotEmpty() }
        val all = regret.keys.map { root ->
            val wsum = weight.getValue(root).takeIf { it > 0 } ?: 1.0
            Weighed(root, regret.getValue(root) / wsum, mean.getValue(root) / wsum, count.getValue(root))
        }
        val choice = all.filter { it.worlds * 2 >= searched }.minByOrNull { it.regret }
        val lines = choice?.let { c -> per.mapNotNull { (w, opts) -> opts.firstOrNull { it.root == c.root }?.let { w.k to it } }.toMap() }.orEmpty()
        return Decision(choice, all, worlds, lines, complete, moves)
    }
}
