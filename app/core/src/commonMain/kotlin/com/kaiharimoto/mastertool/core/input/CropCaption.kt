package com.kaiharimoto.mastertool.core.input

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Master UI family cursor, "Crop caption" (Master-UI `kit/guides/CURSOR.md`,
 * reference `kit/cursor/cursor.js`), as arithmetic: what state the pointer is in,
 * where its four trim marks go, what its caption says and where the caption sits.
 *
 * The web kit reads all of this off the DOM. Neue is Compose, with no DOM to
 * read, so the screen declares each target's intent ([CursorTarget]) and this
 * object answers the same questions `cursor.js` answers, with the same numbers —
 * so a Neue pointer and a web Master app's pointer are the same pointer, and a
 * change to either has to be made to both.
 */
enum class CursorMode { DEFAULT, POINTER, TEXT, DRAG, NO, BUSY, NATIVE }

/** A rectangle in window pixels. */
data class CursorBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    fun contains(px: Float, py: Float): Boolean = px >= x && px < right && py >= y && py < bottom
    val area: Float get() = w * h

    fun lerp(to: CursorBox, t: Float): CursorBox =
        CursorBox(x + (to.x - x) * t, y + (to.y - y) * t, w + (to.w - w) * t, h + (to.h - h) * t)
}

/**
 * One thing on screen and what the pointer means over it — the Compose form of
 * the DOM semantics and `data-cursor*` hooks the web kit reads.
 *
 * - [caption]: `data-cursor-caption`, the verb.
 * - [label]: `aria-label`, shown only when the target [showsWords] is false.
 * - [reason]: `data-cursor-reason`, on a [CursorMode.NO] target.
 * - [value]: `data-cursor-value` / `aria-valuetext`, on a [CursorMode.DRAG] target.
 * - [fontSize], [singleLine], [focused], [readOnly]: what a text field's caret needs.
 * - [slider]: a horizontal range, whose bar sits on its middle rather than under the pointer.
 */
data class CursorTarget(
    val mode: CursorMode,
    val bounds: CursorBox,
    val caption: String? = null,
    val label: String? = null,
    val showsWords: Boolean = false,
    val reason: String? = null,
    val value: String? = null,
    val fontSize: Float = 14f,
    val singleLine: Boolean = true,
    val focused: Boolean = false,
    val readOnly: Boolean = false,
    val slider: Boolean = false,
)

/** `setBusy(…)`: a label and a percentage, both optional. */
data class CursorBusy(val label: String? = null, val pct: Float? = null)

/** The caption's words: micro caps [text] and a mono [value]. */
data class CursorCaption(val text: String, val value: String = "")

object CropCaption {
    const val SNAP_MS = 180
    const val FADE_MS = 120
    const val PAD = 5f
    const val INSET = 6f
    const val WIDE_W = 480f
    const val WIDE_H = 160f
    const val REST = 16f
    const val CAPTION_H = 20f
    const val CAPTION_GAP = 6f
    const val EDGE = 4f
    const val TICK_MS = 300
    const val BREATHE_MS = 2_400

    /** The mark's arm: 6 px at rest, 8 px when it frames something. */
    fun arm(mode: CursorMode): Float = if (mode == CursorMode.POINTER) 8f else 6f

    /**
     * The target under ([x], [y]): the innermost of those containing it, which is
     * what walking up from the DOM's hit node finds first. The smaller of two
     * nested boxes is the inner one; of two the same size, the later declared.
     */
    fun <T> innermost(targets: List<T>, x: Float, y: Float, boundsOf: (T) -> CursorBox): T? {
        var best: T? = null
        var bestArea = Float.MAX_VALUE
        for (t in targets) {
            val b = boundsOf(t)
            if (!b.contains(x, y)) continue
            if (b.area <= bestArea) {
                best = t
                bestArea = b.area
            }
        }
        return best
    }

    /** The state: busy over everything, else the target's own, else default. */
    fun modeOf(target: CursorTarget?, busy: CursorBusy?): CursorMode = when {
        busy != null -> CursorMode.BUSY
        target == null -> CursorMode.DEFAULT
        else -> target.mode
    }

    /** Where the marks go, and whether the target is wide (the caption then follows the pointer). */
    fun geometry(mode: CursorMode, target: CursorTarget?, x: Float, y: Float, windowWidth: Float, windowHeight: Float): Pair<CursorBox, Boolean> {
        var wide = false
        val box = when {
            mode == CursorMode.POINTER && target != null -> {
                val r = target.bounds
                wide = r.w >= WIDE_W || r.h >= WIDE_H
                val g = if (wide) -INSET else PAD
                CursorBox(r.x - g, r.y - g, r.w + 2 * g, r.h + 2 * g)
            }
            mode == CursorMode.TEXT -> {
                val h = (target?.fontSize ?: 14f).times(1.8f).coerceIn(20f, 56f).roundToInt().toFloat()
                val cy = if (target != null && target.singleLine) target.bounds.y + target.bounds.h / 2f else y
                CursorBox(x - 5f, cy - h / 2f, 10f, h)
            }
            mode == CursorMode.DRAG -> {
                val r = target?.bounds
                val vertical = r != null && r.h > r.w * 1.5f
                if (vertical) {
                    CursorBox(r!!.x + r.w / 2f - 7f, y - 18f, 14f, 36f)
                } else {
                    val cy = if (r != null && target.slider) r.y + r.h / 2f else y
                    CursorBox(x - 18f, cy - 7f, 36f, 14f)
                }
            }
            else -> CursorBox(x - REST / 2f, y - REST / 2f, REST, REST)
        }
        // Keep the marks on screen.
        val x0 = box.x.coerceIn(1f, max(1f, windowWidth - 3f))
        val y0 = box.y.coerceIn(1f, max(1f, windowHeight - 3f))
        val w = max(2f, min(box.right, windowWidth - 1f) - x0)
        val h = max(2f, min(box.bottom, windowHeight - 1f) - y0)
        return CursorBox(x0, y0, w, h) to wide
    }

    /** What the caption says, or null for none. */
    fun caption(mode: CursorMode, target: CursorTarget?, busy: CursorBusy?): CursorCaption? {
        if (mode == CursorMode.BUSY) {
            return CursorCaption(busy?.label ?: "Working", busy?.pct?.let { "${it.roundToInt()}%" } ?: "")
        }
        if (target == null) return null
        return when (mode) {
            CursorMode.POINTER -> when {
                target.caption != null -> CursorCaption(target.caption)
                // A button that already shows its words needs no caption; an icon-only one does.
                target.label != null && !target.showsWords -> CursorCaption(target.label)
                else -> null
            }
            CursorMode.TEXT -> if (target.focused || target.readOnly) null else CursorCaption(target.caption ?: "Edit")
            CursorMode.DRAG -> {
                val name = target.caption ?: target.label ?: ""
                val value = target.value ?: ""
                if (name.isEmpty() && value.isEmpty()) null else CursorCaption(name, value)
            }
            CursorMode.NO -> (target.reason ?: target.caption)?.let { CursorCaption(it) }
            else -> null
        }
    }

    /**
     * Where a caption [capW] × [capH] sits: [CAPTION_GAP] under the frame, flush
     * with its left edge (or just left of the pointer on a wide target); above
     * the frame near the bottom of the window; never out of it.
     */
    fun captionAt(box: CursorBox, wide: Boolean, x: Float, capW: Float, capH: Float, windowWidth: Float, windowHeight: Float): Pair<Float, Float> {
        var cx = if (wide) x - 8f else box.x
        var cy = box.bottom + CAPTION_GAP
        if (cy + capH > windowHeight - EDGE) cy = box.y - CAPTION_GAP - capH
        cx = cx.coerceIn(EDGE, max(EDGE, windowWidth - capW - EDGE))
        return cx.roundToInt().toFloat() to cy.roundToInt().toFloat()
    }

    /** Busy: which mark is lit, clockwise from the top left, one every [TICK_MS]. */
    fun litMark(elapsedMs: Long): Int = ((elapsedMs % (TICK_MS * 4)) / TICK_MS).toInt()

    /** The breathing square's opacity: 1 → 0.35 → 1 over [BREATHE_MS]. */
    fun breathe(elapsedMs: Long): Float {
        val t = (elapsedMs % BREATHE_MS).toFloat() / BREATHE_MS
        val tri = if (t < 0.5f) t * 2f else (1f - t) * 2f
        val eased = (1f - kotlin.math.cos(tri * kotlin.math.PI.toFloat())) / 2f
        return 1f - 0.65f * eased
    }

    /** The family's one easing, `cubic-bezier(0.2, 0, 0, 1)`, at [t] in 0..1. */
    fun ease(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        // Solve the bezier's x for its parameter, then read y.
        var u = p
        repeat(8) {
            val x = bez(u, 0.2f, 0f) - p
            val dx = bezD(u, 0.2f, 0f)
            if (kotlin.math.abs(dx) < 1e-6f) return@repeat
            u = (u - x / dx).coerceIn(0f, 1f)
        }
        return bez(u, 0f, 1f)
    }

    private fun bez(u: Float, p1: Float, p2: Float): Float {
        val v = 1f - u
        return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u
    }

    private fun bezD(u: Float, p1: Float, p2: Float): Float {
        val v = 1f - u
        return 3f * v * v * p1 + 6f * v * u * (p2 - p1) + 3f * u * u * (1f - p2)
    }
}
