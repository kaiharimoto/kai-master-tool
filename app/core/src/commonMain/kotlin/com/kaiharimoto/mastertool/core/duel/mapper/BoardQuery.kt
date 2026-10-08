package com.kaiharimoto.mastertool.core.duel.mapper

import kotlinx.serialization.Serializable

/*
 * Choosing from the board library (M.md §2.7, kai: "a range of boards based on what the user wants like a filter system with
 * adjustable weights"). Filters narrow, weights rank, and the Pareto front shows the boards nothing beats on every weighted
 * trait at once — the real trade-offs. A query reads the library and never changes it, and nothing a query does reaches the
 * training: the network predicts each trait on its own, so any weights can be asked of it without training again.
 */

/** A bound on one trait ([BoardTraits.get]'s heads): at least [min], at most [max]. A trait not measured never passes. */
@Serializable
data class BoardFilter(val head: String, val min: Double? = null, val max: Double? = null) {
    fun passes(t: BoardTraits): Boolean {
        val v = t[head] ?: return false
        return (min == null || v >= min) && (max == null || v <= max)
    }
}

/**
 * What the person wants of a board: [filters] and the cards it must use or must not ([uses], [avoids]: on the board or in
 * a line's starter), and [weights] by trait — positive where more is better, negative where less is. Saved as presets; one
 * Ai suggests is marked as Ai's ([by]) with its reason ([why]), and its weights are shown like any other.
 */
@Serializable
data class BoardPreset(
    val id: String = "",
    val name: String = "",
    val filters: List<BoardFilter> = emptyList(),
    val weights: Map<String, Double> = emptyMap(),
    val uses: List<Int> = emptyList(),
    val avoids: List<Int> = emptyList(),
    /** Boards stale since the deck changed are left out unless asked for. */
    val stale: Boolean = false,
    val by: String = PERSON,
    val why: String = "",
) {
    companion object {
        const val PERSON = "person"
        const val AI = "ai"

        /** The starting weights: one for every counted interruption, nothing else said. */
        val DEFAULT = BoardPreset("default", "Interruptions", weights = mapOf("interruptions" to 1.0))
    }
}

object BoardQuery {
    /**
     * A board as the query ranked it: its [score], each weighted trait's part of it ([parts]: weight × value ÷ the most any
     * passing board has, so a slider means the same whatever the trait's scale), whether it is on the Pareto [front], and the
     * weighted traits it was not measured on ([unmeasured]: they add nothing, and say so).
     */
    data class Ranked(
        val entry: BoardEntry,
        val score: Double,
        val parts: Map<String, Double>,
        val front: Boolean,
        val unmeasured: List<String>,
    )

    /** [boards] filtered by [preset] and ranked: best score first, then the cheapest line, then the key. */
    fun rank(boards: List<BoardEntry>, preset: BoardPreset): List<Ranked> {
        val pass = boards.filter { passes(it, preset) }
        val heads = preset.weights.filterValues { it != 0.0 }
        val scale = heads.keys.associateWith { h -> pass.maxOfOrNull { kotlin.math.abs(it.traits[h] ?: 0.0) }?.takeIf { it > 0 } ?: 1.0 }
        val front = pareto(pass, heads)
        return pass.map { e ->
            val parts = LinkedHashMap<String, Double>()
            val missing = ArrayList<String>()
            heads.forEach { (h, w) ->
                val v = e.traits[h]
                if (v == null) missing += h else parts[h] = w * v / scale.getValue(h)
            }
            Ranked(e, parts.values.sum(), parts, e.key in front, missing)
        }.sortedWith(compareBy({ -it.score }, { cheapest(it.entry) }, { it.entry.key }))
    }

    /** Whether [e] passes [preset]'s filters and card rules. */
    fun passes(e: BoardEntry, preset: BoardPreset): Boolean {
        if (e.stale && !preset.stale) return false
        if (!preset.filters.all { it.passes(e.traits) }) return false
        val present = cardsOf(e)
        if (preset.uses.any { it !in present }) return false
        if (preset.avoids.any { it in present }) return false
        return true
    }

    /**
     * The keys of the boards no other board beats on every trait of [weights] at once (more where its weight is positive,
     * less where negative; a trait not measured counts as the worst). With no weights, every [BoardTraits.HEADS] trait, more
     * being better.
     */
    fun pareto(boards: List<BoardEntry>, weights: Map<String, Double>): Set<String> {
        val dims: List<Pair<String, Double>> = weights.filterValues { it != 0.0 }.toList().ifEmpty { BoardTraits.HEADS.map { it to 1.0 } }
        val points = boards.map { e -> e.key to dims.map { (h, w) -> (e.traits[h] ?: Double.NEGATIVE_INFINITY * kotlin.math.sign(w)) * kotlin.math.sign(w) } }
        return points.filter { (_, p) ->
            points.none { (_, q) -> q.indices.all { q[it] >= p[it] } && q.indices.any { q[it] > p[it] } }
        }.mapTo(LinkedHashSet()) { it.first }
    }

    /** Every card a board holds or a line to it starts from. */
    private fun cardsOf(e: BoardEntry): Set<Int> = buildSet {
        with(e.cards) { addAll(monsters); addAll(setMonsters); addAll(spells); addAll(set); addAll(hand); addAll(gy); addAll(banished) }
        e.starters.forEach { addAll(it) }
    }

    private fun cheapest(e: BoardEntry): Int = e.lines.minOfOrNull { it.cost } ?: Int.MAX_VALUE
}
