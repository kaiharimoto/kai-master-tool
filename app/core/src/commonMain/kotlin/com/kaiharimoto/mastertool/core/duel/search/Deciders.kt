package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.effects.Decision

/** Who answers a choice the engine puts, and the answer the table gives for a seat that is not asked (Phase E). */
object Deciders {
    /** The seat that makes [d]: the one it names ("your opponent chooses"), else its effect's controller, else [mover]. */
    fun of(d: Decision, s: DuelState, mover: Int): Int {
        val by = when (d) {
            is Decision.Cards -> d.by
            is Decision.Order -> d.by
            is Decision.YesNo -> d.by
            else -> null
        }
        if (by != null) return by
        val source = when (d) {
            is Decision.Cards -> d.effect
            is Decision.Zone -> d.effect
            is Decision.Position -> d.effect
            is Decision.YesNo -> d.effect
            is Decision.Option -> d.effect
            is Decision.Declare -> d.effect
            is Decision.Order -> null
        }
        return source?.let { s.cards[it.uid]?.controller } ?: mover
    }

    /** The first answer [d] allows: the fewest cards it takes, in order; the first of anything else; triggers as they stand. */
    fun first(d: Decision): List<Int> = when (d) {
        is Decision.Cards -> (0 until d.min.coerceAtLeast(1).coerceAtMost(d.among.size)).toList()
        is Decision.Order -> d.triggers.indices.toList()
        else -> listOf(0)
    }
}
