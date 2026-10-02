package com.kaiharimoto.mastertool.core.present.edit

import com.kaiharimoto.mastertool.core.present.stage.Box
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Moving, sizing and turning boxes as a slide editor does: eight handles, the opposite edge
 * held still, Shift keeping the shape, Alt sizing from the middle, a turn snapping to 15°
 * with Shift. A turned box is sized along its own axes, so a handle follows the pointer.
 */
object Transform {
    /** The smallest a box may be sized to, in canvas units. */
    const val MIN = 12f

    /** Handles, as which edges they move: horizontal −1 left, 0 none, 1 right; the same down. */
    enum class Handle(val hx: Int, val hy: Int) {
        TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1),
        LEFT(-1, 0), RIGHT(1, 0),
        BOTTOM_LEFT(-1, 1), BOTTOM(0, 1), BOTTOM_RIGHT(1, 1),
    }

    fun move(b: Box, dx: Float, dy: Float): Box = b.copy(x = b.x + dx, y = b.y + dy)

    /**
     * [b], turned [rotation] degrees, with [handle] dragged by ([dx], [dy]) on the canvas.
     * [keepAspect] holds its shape (Shift, or always on a corner of a picture); [fromCentre]
     * moves the opposite edge too (Alt).
     */
    fun resize(b: Box, rotation: Float, handle: Handle, dx: Float, dy: Float, keepAspect: Boolean = false, fromCentre: Boolean = false): Box {
        val r = rotation * PI.toFloat() / 180f
        val c = cos(r)
        val s = sin(r)
        // The drag in the box's own axes.
        val lx = dx * c + dy * s
        val ly = -dx * s + dy * c
        val k = if (fromCentre) 2f else 1f
        var w = b.w + handle.hx * lx * k
        var h = b.h + handle.hy * ly * k
        if (keepAspect && b.w > 0f && b.h > 0f) {
            val aspect = b.w / b.h
            when {
                handle.hx == 0 -> w = h * aspect
                handle.hy == 0 -> h = w / aspect
                abs(w / b.w) > abs(h / b.h) -> h = w / aspect
                else -> w = h * aspect
            }
        }
        w = max(MIN, w)
        h = max(MIN, h)
        if (fromCentre) return Box(b.cx - w / 2f, b.cy - h / 2f, w, h)
        // The fixed point: the opposite handle (or edge middle), in the box's axes from its centre.
        val fx = -handle.hx * b.w / 2f
        val fy = -handle.hy * b.h / 2f
        // Where it is on the canvas now, and where the new centre must be to keep it there.
        val fixedX = b.cx + fx * c - fy * s
        val fixedY = b.cy + fx * s + fy * c
        val nfx = -handle.hx * w / 2f
        val nfy = -handle.hy * h / 2f
        val ncx = fixedX - (nfx * c - nfy * s)
        val ncy = fixedY - (nfx * s + nfy * c)
        return Box(ncx - w / 2f, ncy - h / 2f, w, h)
    }

    /** The turn of a box about its centre that points its top at ([px], [py]); snapped to 15° when [snap]. */
    fun rotation(b: Box, px: Float, py: Float, snap: Boolean): Float {
        val a = atan2(py - b.cy, px - b.cx) * 180f / PI.toFloat() + 90f
        val deg = ((a % 360f) + 360f) % 360f
        return if (snap) ((deg / 15f).roundToInt() * 15f) % 360f else deg
    }

    /** Whether ([px], [py]) falls inside [b] turned [rotation] degrees. */
    fun hit(b: Box, rotation: Float, px: Float, py: Float, slop: Float = 0f): Boolean {
        val r = -rotation * PI.toFloat() / 180f
        val dx = px - b.cx
        val dy = py - b.cy
        val lx = dx * cos(r) - dy * sin(r)
        val ly = dx * sin(r) + dy * cos(r)
        return abs(lx) <= b.w / 2f + slop && abs(ly) <= b.h / 2f + slop
    }

    /** The upright box round [b] turned [rotation] degrees. */
    fun bounds(b: Box, rotation: Float): Box {
        if (rotation % 360f == 0f) return b
        val r = rotation * PI.toFloat() / 180f
        val c = abs(cos(r))
        val s = abs(sin(r))
        val w = b.w * c + b.h * s
        val h = b.w * s + b.h * c
        return Box(b.cx - w / 2f, b.cy - h / 2f, w, h)
    }

    /** A handle's point on the canvas for [b] turned [rotation]. */
    fun handlePoint(b: Box, rotation: Float, handle: Handle): Pair<Float, Float> {
        val r = rotation * PI.toFloat() / 180f
        val lx = handle.hx * b.w / 2f
        val ly = handle.hy * b.h / 2f
        return (b.cx + lx * cos(r) - ly * sin(r)) to (b.cy + lx * sin(r) + ly * cos(r))
    }

    /** Every box in [boxes] scaled with their group from [from] to [to], as a group resize does. */
    fun scaleGroup(boxes: List<Box>, from: Box, to: Box): List<Box> {
        val sx = if (from.w > 0f) to.w / from.w else 1f
        val sy = if (from.h > 0f) to.h / from.h else 1f
        return boxes.map { Box(to.x + (it.x - from.x) * sx, to.y + (it.y - from.y) * sy, it.w * sx, it.h * sy) }
    }
}
