package com.kaiharimoto.mastertool.core.world.desk

import kotlin.math.hypot
import kotlinx.serialization.Serializable

/**
 * A point on the World's desktop (`docs/world/DESKTOP.md`), in dp from the page's top-left: the desktop is everything
 * below Neue's bar, so its own top-left is wherever the page puts it — the desk only ever compares points it was given.
 * Named for the desk so it never reads as Compose's `Offset` in a file that imports both.
 */
data class DeskPoint(val x: Double, val y: Double) {
    operator fun plus(o: DeskPoint) = DeskPoint(x + o.x, y + o.y)
    operator fun minus(o: DeskPoint) = DeskPoint(x - o.x, y - o.y)
    operator fun times(k: Double) = DeskPoint(x * k, y * k)
    val length: Double get() = hypot(x, y)
    fun distanceTo(o: DeskPoint): Double = hypot(o.x - x, o.y - y)

    companion object {
        val ZERO = DeskPoint(0.0, 0.0)
    }
}

/** A width and a height in dp. */
@Serializable
data class DeskSize(val w: Double, val h: Double)

/** A rectangle on the desktop in dp: its top-left, width and height. */
data class DeskRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h
    val center: DeskPoint get() = DeskPoint(x + w / 2, y + h / 2)
    val size: DeskSize get() = DeskSize(w, h)

    fun contains(p: DeskPoint): Boolean = p.x >= x && p.x <= right && p.y >= y && p.y <= bottom
    fun moved(dx: Double, dy: Double) = copy(x = x + dx, y = y + dy)

    /** This rectangle's size held to [min] and to [area], then moved the least it takes to stand inside [area]. */
    fun inside(area: DeskRect, min: DeskSize = DeskSize(0.0, 0.0)): DeskRect {
        val nw = w.coerceAtLeast(minOf(min.w, area.w)).coerceAtMost(area.w)
        val nh = h.coerceAtLeast(minOf(min.h, area.h)).coerceAtMost(area.h)
        val nx = x.coerceIn(area.x, area.right - nw)
        val ny = y.coerceIn(area.y, area.bottom - nh)
        return DeskRect(nx, ny, nw, nh)
    }
}

/**
 * Where a window may stand, measured by the page each time it lays out: [full] is the desktop between Neue's bar and
 * the taskbar; [work] is the part right of the icon columns, where windows open (§2.3). Maximising and snapping fill
 * [full] — "the icons covered" — and the stored frames are fractions of [full], so a 1366 × 768 laptop and a 4K
 * display read the same `desk.json` (decided here: the spec names one "work area" for both, and covering the icons
 * is only possible if maximise reaches past it).
 */
data class DeskArea(val full: DeskRect, val iconColumns: Double = 0.0) {
    val work: DeskRect
        get() = DeskRect(full.x + iconColumns.coerceIn(0.0, full.w * 0.5), full.y, full.w - iconColumns.coerceIn(0.0, full.w * 0.5), full.h)

    /** A stored [Frame] as dp on this desktop. */
    fun rect(f: Frame): DeskRect = DeskRect(full.x + f.x * full.w, full.y + f.y * full.h, f.w * full.w, f.h * full.h)

    /** A rectangle in dp as the [Frame] `desk.json` keeps. */
    fun frame(r: DeskRect): Frame =
        if (full.w <= 0 || full.h <= 0) Frame() else Frame((r.x - full.x) / full.w, (r.y - full.y) / full.h, r.w / full.w, r.h / full.h)

    companion object {
        /** A laptop's desktop, for tests and the studio: 1366 × 768 less Neue's bar (48) and the taskbar (44). */
        val LAPTOP = DeskArea(DeskRect(0.0, 0.0, 1366.0, 676.0), iconColumns = 120.0)
    }
}

/** A window's place as fractions of [DeskArea.full]: what `desk.json` keeps, so it reads on any display. */
@Serializable
data class Frame(val x: Double = 0.1, val y: Double = 0.1, val w: Double = 0.5, val h: Double = 0.6)
