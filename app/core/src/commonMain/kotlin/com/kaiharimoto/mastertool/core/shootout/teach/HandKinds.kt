package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.shootout.bench.TrialDraws
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.search.EffectKind
import com.kaiharimoto.mastertool.core.search.EffectKinds
import com.kaiharimoto.mastertool.core.shootout.model.Stratum

/**
 * A kind of hand (S.md §6½ "The confidence score"): agreement is counted per kind, never as one number, because Ai can
 * be right about going-first hands with a starter and wrong about bricks into interaction. Three questions make a kind:
 * which turn, whether the hand holds a starter, and — in a matchup — whether the opponent's hand holds interaction.
 */
data class HandKind(val first: Boolean, val starter: Boolean, val interaction: Boolean?) {

    /** Stable words for storage and the gate's tables: `first·starter·interaction`, `second·none` (the deck alone). */
    val key: String
        get() = listOfNotNull(
            if (first) "first" else "second",
            if (starter) "starter" else "none",
            interaction?.let { if (it) "interaction" else "clear" },
        ).joinToString("·")

    /** How a person reads it: "going first with a starter, interaction from them" (design review, 1.1.6: never "into none"). */
    val words: String
        get() = buildString {
            append(if (first) "going first" else "going second")
            append(if (starter) " with a starter" else " without a starter")
            when (interaction) {
                true -> append(", interaction from them")
                false -> append(", no interaction from them")
                null -> Unit
            }
        }

    companion object {
        /** A kind read back from its [key]; null when the key is not one. */
        fun parse(key: String?): HandKind? {
            val parts = key?.split('·') ?: return null
            if (parts.size !in 2..3) return null
            val first = when (parts[0]) { "first" -> true; "second" -> false; else -> return null }
            val starter = when (parts[1]) { "starter" -> true; "none" -> false; else -> return null }
            val interaction = when (parts.getOrNull(2)) { null -> null; "interaction" -> true; "clear" -> false; else -> return null }
            return HandKind(first, starter, interaction)
        }

        /** Every kind a model of [alone] or a matchup has, in the trust panel's order. */
        fun all(alone: Boolean): List<HandKind> = listOf(true, false).flatMap { first ->
            listOf(true, false).flatMap { starter ->
                if (alone) listOf(HandKind(first, starter, null)) else listOf(true, false).map { HandKind(first, starter, it) }
            }
        }
    }
}

/**
 * Sorts hands into [HandKind]s: [starters] are your cards that start a play by themselves, [interaction] the opponent's
 * cards that stop one (both canonical passcodes). The deck alone has no opponent, so its kinds have no interaction.
 */
class HandKinds(val starters: Set<Int>, val interaction: Set<Int>) {

    /**
     * The kind of [hand] against [opponent]. When you go first their sixth, [theirDraw], is drawn on their turn and stops
     * nothing on yours, so it is not interaction (the red team, 2026-10); a hand that does not say which it was counts whole.
     */
    fun of(stratum: Stratum, hand: List<Int>, opponent: List<Int>?, theirDraw: Int? = null): HandKind {
        val theirs = opponent.orEmpty().let { o ->
            if (stratum.goingFirst && theirDraw != null && o.size > TrialDraws.OPENING) o.toMutableList().also { it.remove(theirDraw) } else o
        }
        return HandKind(
            first = stratum.goingFirst,
            starter = hand.any { it in starters },
            interaction = if (stratum.alone) null else theirs.any { it in interaction },
        )
    }

    companion object {
        /**
         * Your starters: the cards of a group whose name says so ("Starters", "Starter", "1-card starters"); with no such
         * group, the cards that search the deck by themselves (EffectKinds' Search), a player's usual first guess.
         */
        fun starters(main: List<Int>, roleOf: (Int) -> String?, cards: (Int) -> Card?): Set<Int> {
            val named = main.filter { roleOf(it)?.contains("start", ignoreCase = true) == true }.toSet()
            if (named.isNotEmpty()) return named
            return main.filter { id -> cards(id)?.let { EffectKind.SEARCH in EffectKinds.of(it) } == true }.toSet()
        }

        /**
         * The opponent's interaction: hand traps, and the Spells and Traps that negate, destroy or banish on your turn —
         * the cards that turn "plays through" into "interrupted".
         */
        fun interaction(main: List<Int>, cards: (Int) -> Card?): Set<Int> = main.filter { id ->
            val card = cards(id) ?: return@filter false
            val kinds = EffectKinds.of(card)
            EffectKind.HAND_TRAP in kinds ||
                (card.category == CardCategory.TRAP && (EffectKind.NEGATE in kinds || EffectKind.DESTROY in kinds || EffectKind.BANISH in kinds)) ||
                (card.category == CardCategory.SPELL && card.race == "Quick-Play" && EffectKind.NEGATE in kinds)
        }.toSet()
    }
}
