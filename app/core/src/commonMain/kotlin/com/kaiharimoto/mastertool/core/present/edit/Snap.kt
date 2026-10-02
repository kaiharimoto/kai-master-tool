package com.kaiharimoto.mastertool.core.present.edit

import com.kaiharimoto.mastertool.core.present.stage.Box
import kotlin.math.abs

/** A guide drawn while snapping: a vertical line at [at] (x) or horizontal (y), from [from] to [to]. */
data class Guide(val vertical: Boolean, val at: Float, val from: Float, val to: Float)

/** A move snapped: how much further to go, and the guides that caught it. */
data class Snapped(val dx: Float, val dy: Float, val guides: List<Guide>)

/**
 * Smart guides: a box being moved catches the edges and middles of the canvas, the safe
 * area, the stage, the webcam zone and the other elements, within [threshold] canvas units
 * (the caller turns a few screen pixels into canvas units, so it feels the same at any zoom).
 */
object Snap {
    fun move(moving: Box, targets: List<Box>, threshold: Float): Snapped {
        val xs = listOf(moving.x, moving.cx, moving.right)
        val ys = listOf(moving.y, moving.cy, moving.bottom)
        var bestX: Pair<Float, Float>? = null // delta, line
        var bestY: Pair<Float, Float>? = null
        for (t in targets) {
            for (line in listOf(t.x, t.cx, t.right)) for (x in xs) {
                val d = line - x
                if (abs(d) <= threshold && (bestX == null || abs(d) < abs(bestX.first))) bestX = d to line
            }
            for (line in listOf(t.y, t.cy, t.bottom)) for (y in ys) {
                val d = line - y
                if (abs(d) <= threshold && (bestY == null || abs(d) < abs(bestY.first))) bestY = d to line
            }
        }
        val dx = bestX?.first ?: 0f
        val dy = bestY?.first ?: 0f
        val moved = moving.copy(x = moving.x + dx, y = moving.y + dy)
        val guides = buildList {
            bestX?.let { (_, line) ->
                val ends = targets.filter { t -> listOf(t.x, t.cx, t.right).any { abs(it - line) < 0.5f } } + moved
                add(Guide(true, line, ends.minOf { it.y }, ends.maxOf { it.bottom }))
            }
            bestY?.let { (_, line) ->
                val ends = targets.filter { t -> listOf(t.y, t.cy, t.bottom).any { abs(it - line) < 0.5f } } + moved
                add(Guide(false, line, ends.minOf { it.x }, ends.maxOf { it.right }))
            }
        }
        return Snapped(dx, dy, guides)
    }
}

/** Align and distribute, to each other or to a box (the slide, or the stage). */
object Align {
    const val LEFT = "LEFT"
    const val CENTER = "CENTER"
    const val RIGHT = "RIGHT"
    const val TOP = "TOP"
    const val MIDDLE = "MIDDLE"
    const val BOTTOM = "BOTTOM"

    /** [boxes] lined up on [edge] of [to], or of their own bounds when [to] is null. */
    fun align(boxes: List<Box>, edge: String, to: Box? = null): List<Box> {
        val ref = to ?: Box.around(boxes) ?: return boxes
        return boxes.map { b ->
            when (edge) {
                LEFT -> b.copy(x = ref.x)
                CENTER -> b.copy(x = ref.cx - b.w / 2f)
                RIGHT -> b.copy(x = ref.right - b.w)
                TOP -> b.copy(y = ref.y)
                MIDDLE -> b.copy(y = ref.cy - b.h / 2f)
                BOTTOM -> b.copy(y = ref.bottom - b.h)
                else -> b
            }
        }
    }

    /** [boxes] spaced evenly across ([horizontal]) or down, the outermost two held still. */
    fun distribute(boxes: List<Box>, horizontal: Boolean): List<Box> {
        if (boxes.size < 3) return boxes
        val order = boxes.indices.sortedBy { if (horizontal) boxes[it].x else boxes[it].y }
        val first = boxes[order.first()]
        val last = boxes[order.last()]
        val span = if (horizontal) last.right - first.x else last.bottom - first.y
        val sizes = boxes.sumOf { (if (horizontal) it.w else it.h).toDouble() }.toFloat()
        val gap = (span - sizes) / (boxes.size - 1)
        val out = boxes.toMutableList()
        var at = if (horizontal) first.x else first.y
        for (i in order) {
            val b = boxes[i]
            out[i] = if (horizontal) b.copy(x = at) else b.copy(y = at)
            at += (if (horizontal) b.w else b.h) + gap
        }
        return out
    }
}
