package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTable

/**
 * A world's choices inside a move, made again on the real table by what they mean (Phase E, E.3): a world's cards are not the
 * table's — its hidden cards are guesses, its own Deck's are in another order — so a choice is carried by the cards' codes
 * and places, a zone, a position, an option or a name by its value, and anything that does not carry by the first answer
 * the decision allows. Only the seat's own choices are carried: the other seat's are never the search's to make.
 */
class Carry private constructor(private val asked: List<Pair<Decision, List<Int>>>, private val world: FxTable) {
    private var next = 0

    /** The seat's next choice on the real table: [d], with the codes of its cards the seat may see ([cards], null where hidden). */
    fun choose(d: Decision, cards: List<Int?>): List<Int> {
        val (w, a) = asked.getOrNull(next++) ?: return Deciders.first(d)
        return carried(d, cards, w, a) ?: Deciders.first(d)
    }

    private fun carried(d: Decision, cards: List<Int?>, w: Decision, a: List<Int>): List<Int>? = when {
        d is Decision.Cards && w is Decision.Cards -> {
            val used = HashSet<Int>()
            val out = ArrayList<Int>()
            for (i in a) {
                val u = w.among.getOrNull(i) ?: continue
                val code = world.state.cards[u]?.code
                val place = w.from.getOrNull(i)
                // The same card in the same place; else the same card; else the same place.
                val j = d.among.indices.firstOrNull { it !in used && code != null && cards.getOrNull(it) == code && d.from.getOrNull(it) == place }
                    ?: d.among.indices.firstOrNull { it !in used && code != null && cards.getOrNull(it) == code }
                    ?: d.among.indices.firstOrNull { it !in used && place != null && d.from.getOrNull(it) == place }
                if (j != null) { used += j; out += j }
            }
            out.takeIf { it.size in d.min..d.max }
        }
        d is Decision.Zone && w is Decision.Zone -> a.singleOrNull()?.let { w.among.getOrNull(it) }?.let { z -> d.among.indexOf(z).takeIf { it >= 0 } }?.let(::listOf)
        d is Decision.Position && w is Decision.Position -> a.singleOrNull()?.let { w.among.getOrNull(it) }?.let { p -> d.among.indexOf(p).takeIf { it >= 0 } }?.let(::listOf)
        d is Decision.Option && w is Decision.Option -> a.singleOrNull()?.let { w.among.getOrNull(it) }?.let { o -> d.among.indexOf(o).takeIf { it >= 0 } }?.let(::listOf)
        d is Decision.Declare && w is Decision.Declare -> a.singleOrNull()?.let { w.among.getOrNull(it) }?.let { o -> d.among.indexOf(o).takeIf { it >= 0 } }?.let(::listOf)
        d is Decision.YesNo && w is Decision.YesNo -> a
        else -> null
    }

    companion object {
        /**
         * The seat's choices in [step] (a world's line's first move, made by [seat] on [world]): the move played again on the world
         * with its answers, each decision kept with its answer, the other seat's left out.
         */
        fun of(world: FxTable, seat: Int, step: TurnStep.Move): Carry {
            val asked = ArrayList<Pair<Decision, List<Int>>>()
            var i = 0
            runCatching {
                FxEngine.play(world, step.seat, step.move, Chooser { d ->
                    val a = step.answers.getOrNull(i++) ?: Chooser.CANCEL
                    if (Deciders.of(d, world.state, step.seat) == seat) asked += d to a
                    a
                })
            }
            return Carry(asked, world)
        }

        /** No world's choices to carry: every choice the first the decision allows. */
        fun none(world: FxTable): Carry = Carry(emptyList(), world)

        /** Whether [m] is a move the engine makes (a summon, a procedure, an activation, resolving), not a phase or a pass. */
        fun engineMove(m: FxMove): Boolean = m is FxMove.Activate || m is FxMove.NormalSummon || m is FxMove.Procedure || m == FxMove.Resolve
    }
}
