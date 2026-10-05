package com.kaiharimoto.mastertool.core.world.desk

/**
 * Where a window opens (§2.3): at its app's comfort size — a fraction of the work area for a built-in, a made app's
 * manifest size in dp clamped to [APP_MIN]–[APP_MAX] — in the next cascade slot, [CASCADE] dp down and right of the
 * last, held inside the work area. A sensible window on a 1024 × 600 netbook and on a 4K display alike.
 */
object DeskPlacer {
    /** The smallest a window may be (§2.3). */
    val MIN = DeskSize(320.0, 200.0)

    /** A made app's size is held to this … */
    val APP_MIN = DeskSize(320.0, 240.0)

    /** … and to this, and to the work area. */
    val APP_MAX = DeskSize(1200.0, 900.0)

    /** Each window opens this far down and right of the last. */
    const val CASCADE = 32.0

    /** The first slot's distance from the work area's top-left. */
    const val INSET = 24.0

    /** How many slots before the cascade starts again at the top. */
    const val SLOTS = 8

    /** The size [app] opens at on [work]; [manifest] is a made app's own size in dp. */
    fun comfort(app: AppRef, work: DeskRect, manifest: DeskSize? = null): DeskSize {
        val raw = when (app) {
            is AppRef.BuiltIn -> DeskSize(app.kind.comfortW * work.w, app.kind.comfortH * work.h)
            is AppRef.Made -> {
                val m = manifest ?: DeskSize(480.0, 360.0)
                DeskSize(m.w.coerceIn(APP_MIN.w, APP_MAX.w), m.h.coerceIn(APP_MIN.h, APP_MAX.h))
            }
        }
        return DeskSize(
            raw.w.coerceAtLeast(MIN.w).coerceAtMost(work.w),
            raw.h.coerceAtLeast(MIN.h).coerceAtMost(work.h),
        )
    }

    /** Where the [cascade]th window to open on [area] stands: its comfort size, its slot, inside the work area. */
    fun place(app: AppRef, area: DeskArea, cascade: Int, manifest: DeskSize? = null): DeskRect {
        val work = area.work
        val size = comfort(app, work, manifest)
        val slot = (cascade % SLOTS).coerceAtLeast(0) * CASCADE
        return DeskRect(work.x + INSET + slot, work.y + INSET + slot, size.w, size.h).inside(work, MIN)
    }
}
