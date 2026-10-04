package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide

/**
 * How the engine's cards find each other, laid out to be drawn (1.0.67): a layered graph — each card
 * on the row after the cards that lead to it, rows ordered so arrows cross as little as they can —
 * and its hubs, the cards every route passes through. A phone is tall, so rows run down the page.
 */
data class EngineLayout(
    /** The rows, top to bottom, each its cards left to right. */
    val rows: List<List<String>>,
    val edges: List<ReaderGuide.Edge>,
    /** The cards on the most routes from a starting card to an end: what the deck runs through. */
    val hubs: Set<String>,
) {
    fun rowOf(card: String): Int = rows.indexOfFirst { card in it }

    companion object {
        fun of(edges: List<ReaderGuide.Edge>): EngineLayout {
            val clean = edges.filter { it.from.isNotBlank() && it.to.isNotBlank() && it.from != it.to }.distinctBy { it.from to it.to }
            val nodes = (clean.map { it.from } + clean.map { it.to }).distinct()
            val out = nodes.associateWith { n -> clean.filter { it.from == n }.map { it.to } }
            val into = nodes.associateWith { n -> clean.filter { it.to == n }.map { it.from } }
            // Cycles broken where they close: an edge back to a card on the current route is not followed.
            val forward = mutableListOf<ReaderGuide.Edge>()
            val seen = HashSet<String>()
            fun walk(n: String, route: MutableSet<String>) {
                route += n
                out.getValue(n).forEach { m ->
                    val e = clean.first { it.from == n && it.to == m }
                    if (m in route) return@forEach
                    forward += e
                    if (m !in seen) {
                        seen += m
                        walk(m, route)
                    }
                }
                route -= n
            }
            val sources = nodes.filter { into.getValue(it).isEmpty() }.ifEmpty { nodes.take(1) }
            sources.forEach { s ->
                if (s !in seen) {
                    seen += s
                    walk(s, mutableSetOf())
                }
            }
            nodes.filter { it !in seen }.forEach { n ->
                seen += n
                walk(n, mutableSetOf())
            }
            val acyclic = forward.distinctBy { it.from to it.to }
            // The row: the longest route to the card.
            val layer = HashMap<String, Int>()
            fun depth(n: String, guard: Int = 0): Int = layer.getOrPut(n) {
                val preds = acyclic.filter { it.to == n }.map { it.from }
                if (preds.isEmpty() || guard > nodes.size) 0 else preds.maxOf { depth(it, guard + 1) } + 1
            }
            nodes.forEach { depth(it) }
            val count = (layer.values.maxOrNull() ?: 0) + 1
            val rows = MutableList(count) { r -> nodes.filter { layer[it] == r }.toMutableList() }
            // Order each row by where its cards' neighbours sit (the barycentre): down, up, down.
            fun down() {
                for (r in 1 until rows.size) {
                    val above = rows[r - 1]
                    rows[r].sortBy { n ->
                        val parents = acyclic.filter { it.to == n }.map { above.indexOf(it.from) }.filter { it >= 0 }
                        if (parents.isEmpty()) Double.MAX_VALUE else parents.average()
                    }
                }
            }
            fun up() {
                for (r in rows.size - 2 downTo 0) {
                    val below = rows[r + 1]
                    val was = rows[r].toList()
                    rows[r].sortBy { n ->
                        val children = acyclic.filter { it.from == n }.map { below.indexOf(it.to) }.filter { it >= 0 }
                        if (children.isEmpty()) was.indexOf(n).toDouble() else children.average()
                    }
                }
            }
            down()
            up()
            down()
            // Routes from a source to an end, and how many pass through each card: the routes into a card times the
            // routes out of it, counted once per card (1.0.97). Walking every route, as before, is exponential in a
            // dense web, and Ai World draws any web a script makes; the counts are the same.
            val routesIn = HashMap<String, Double>()
            fun inCount(n: String): Double = routesIn.getOrPut(n) {
                val preds = acyclic.filter { it.to == n }
                if (preds.isEmpty()) 1.0 else preds.sumOf { inCount(it.from) }
            }
            val routesOut = HashMap<String, Double>()
            fun outCount(n: String): Double = routesOut.getOrPut(n) {
                val next = acyclic.filter { it.from == n }
                if (next.isEmpty()) 1.0 else next.sumOf { outCount(it.to) }
            }
            val through = nodes.filter { n -> acyclic.any { it.to == n } && acyclic.any { it.from == n } }
                .associateWith { inCount(it) * outCount(it) }
            val most = through.values.maxOrNull() ?: 0.0
            val hubs = if (most >= 2) through.filterValues { it == most }.keys else emptySet()
            return EngineLayout(rows.map { it.toList() }, clean, hubs)
        }
    }
}
