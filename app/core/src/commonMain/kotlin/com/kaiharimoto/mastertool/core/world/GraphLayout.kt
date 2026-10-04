package com.kaiharimoto.mastertool.core.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Where a web's nodes stand: Fruchterman and Reingold's spring model — every pair pushes apart, every edge pulls
 * together, and the push cools to rest. Deterministic: the nodes start round a circle in their given order with a
 * small jitter from [seed], so the same web is always drawn the same way and a re-run never shuffles kai's picture.
 * Positions are in the unit square, with a margin, for the painter to scale.
 */
object GraphLayout {
    data class Pos(val x: Double, val y: Double)

    const val MARGIN = 0.06

    fun force(nodes: List<String>, edges: List<Pair<String, String>>, seed: Long = 7L, iterations: Int = 300): Map<String, Pos> {
        val n = nodes.size
        if (n == 0) return emptyMap()
        if (n == 1) return mapOf(nodes[0] to Pos(0.5, 0.5))
        val index = nodes.withIndex().associate { (i, id) -> id to i }
        val links = edges.mapNotNull { (a, b) -> index[a]?.let { i -> index[b]?.let { j -> if (i != j) i to j else null } } }.distinct()
        val random = Random(seed)
        val x = DoubleArray(n) { 0.5 + 0.4 * cos(2 * PI * it / n) + (random.nextDouble() - 0.5) * 0.01 }
        val y = DoubleArray(n) { 0.5 + 0.4 * sin(2 * PI * it / n) + (random.nextDouble() - 0.5) * 0.01 }
        val k = sqrt(1.0 / n)
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        val steps = iterations.coerceIn(1, 1000)
        for (step in 0 until steps) {
            val temperature = 0.1 * (1.0 - step.toDouble() / steps)
            dx.fill(0.0)
            dy.fill(0.0)
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var ex = x[i] - x[j]
                    var ey = y[i] - y[j]
                    var d2 = ex * ex + ey * ey
                    if (d2 < 1e-9) {
                        // Two on one spot are parted along their indices, so the result never depends on luck.
                        ex = 1e-3 * (i - j)
                        ey = 1e-3
                        d2 = ex * ex + ey * ey
                    }
                    val f = k * k / d2
                    dx[i] += ex * f
                    dy[i] += ey * f
                    dx[j] -= ex * f
                    dy[j] -= ey * f
                }
            }
            for ((i, j) in links) {
                val ex = x[i] - x[j]
                val ey = y[i] - y[j]
                val d = sqrt(ex * ex + ey * ey).coerceAtLeast(1e-6)
                val f = d / k
                dx[i] -= ex * f
                dy[i] -= ey * f
                dx[j] += ex * f
                dy[j] += ey * f
            }
            // A gentle pull to the middle keeps loose pieces of the web on the page.
            for (i in 0 until n) {
                dx[i] += (0.5 - x[i]) * 0.05
                dy[i] += (0.5 - y[i]) * 0.05
                val len = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (len > 0) {
                    val move = minOf(len, temperature)
                    x[i] = (x[i] + dx[i] / len * move).coerceIn(0.0, 1.0)
                    y[i] = (y[i] + dy[i] / len * move).coerceIn(0.0, 1.0)
                }
            }
        }
        // Fitted to the square, keeping its shape.
        val minX = x.min()
        val maxX = x.max()
        val minY = y.min()
        val maxY = y.max()
        val span = maxOf(maxX - minX, maxY - minY).coerceAtLeast(1e-9)
        val scale = (1 - 2 * MARGIN) / span
        val offX = MARGIN + ((1 - 2 * MARGIN) - (maxX - minX) * scale) / 2
        val offY = MARGIN + ((1 - 2 * MARGIN) - (maxY - minY) * scale) / 2
        return nodes.withIndex().associate { (i, id) -> id to Pos(offX + (x[i] - minX) * scale, offY + (y[i] - minY) * scale) }
    }
}
