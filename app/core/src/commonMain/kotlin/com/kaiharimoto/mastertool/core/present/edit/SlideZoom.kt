package com.kaiharimoto.mastertool.core.present.edit

import com.kaiharimoto.mastertool.core.present.Presentation
import kotlin.math.max
import kotlin.math.min

/**
 * The editor's view of the slide (the editor's audit, M2): how much larger than fitted it is drawn
 * ([zoom], 1 is the whole slide in the window) and how far it is moved ([panX], [panY], in screen
 * pixels from centred). Ctrl wheel and a pinch zoom round the pointer or the fingers; the wheel, two
 * fingers or the keys move round a zoomed slide; Fit puts it back. On a phone the slide is about 500 dp
 * wide and a small element cannot be grabbed until it is zoomed.
 */
data class SlideZoom(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {

    /** The scale a canvas unit is drawn at in a [width] × [height] pixel view, and where the slide's corner stands. */
    fun scale(width: Float, height: Float): Float = fitted(width, height) * zoom

    fun originX(width: Float, height: Float): Float = (width - Presentation.WIDTH * scale(width, height)) / 2f + panX

    fun originY(width: Float, height: Float): Float = (height - Presentation.HEIGHT * scale(width, height)) / 2f + panY

    /**
     * Zoomed by [factor] round the view point ([fx], [fy]) — the pointer, or the middle of two fingers —
     * so what is under it stays under it, then kept in reach.
     */
    fun zoomAround(factor: Float, fx: Float, fy: Float, width: Float, height: Float): SlideZoom {
        val next = (zoom * factor).coerceIn(MIN, MAX)
        if (next == zoom) return this
        val s0 = scale(width, height)
        val ox = originX(width, height)
        val oy = originY(width, height)
        // The canvas point under the focus, before.
        val cx = (fx - ox) / s0
        val cy = (fy - oy) / s0
        val s1 = fitted(width, height) * next
        // Where the corner must stand for (cx, cy) to stay at the focus, as a pan from centred.
        val px = fx - cx * s1 - (width - Presentation.WIDTH * s1) / 2f
        val py = fy - cy * s1 - (height - Presentation.HEIGHT * s1) / 2f
        return SlideZoom(next, px, py).kept(width, height)
    }

    /** Moved by ([dx], [dy]) screen pixels, kept in reach. */
    fun panned(dx: Float, dy: Float, width: Float, height: Float): SlideZoom = copy(panX = panX + dx, panY = panY + dy).kept(width, height)

    /**
     * Kept in reach: a slide no larger than the view stays centred; a larger one may move only until
     * its edge meets the view's, plus a little room, so it is never lost off screen.
     */
    fun kept(width: Float, height: Float): SlideZoom {
        val s = scale(width, height)
        val rx = max(0f, (Presentation.WIDTH * s - width) / 2f + ROOM)
        val ry = max(0f, (Presentation.HEIGHT * s - height) / 2f + ROOM)
        val x = if (zoom <= 1f) 0f else panX.coerceIn(-rx, rx)
        val y = if (zoom <= 1f) 0f else panY.coerceIn(-ry, ry)
        return if (x == panX && y == panY) this else copy(panX = x, panY = y)
    }

    /** The view point ([vx], [vy]) as a canvas point. */
    fun toCanvasX(vx: Float, width: Float, height: Float): Float = (vx - originX(width, height)) / scale(width, height)

    fun toCanvasY(vy: Float, width: Float, height: Float): Float = (vy - originY(width, height)) / scale(width, height)

    /** As a percentage of fitted, for the zoom readout. */
    val percent: Int get() = (zoom * 100f).toInt()

    val isFitted: Boolean get() = zoom == 1f && panX == 0f && panY == 0f

    companion object {
        const val MIN = 1f
        const val MAX = 6f

        /** One notch of the wheel or one press of a key. */
        const val STEP = 1.25f

        /** Room past the slide's edge a zoomed slide may be moved, in pixels. */
        const val ROOM = 48f

        val FIT = SlideZoom()

        fun fitted(width: Float, height: Float): Float = min(width / Presentation.WIDTH, height / Presentation.HEIGHT).coerceAtLeast(0.0001f)
    }
}
