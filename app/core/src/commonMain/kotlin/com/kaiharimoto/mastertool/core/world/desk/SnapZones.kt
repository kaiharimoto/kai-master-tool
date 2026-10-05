package com.kaiharimoto.mastertool.core.world.desk

/**
 * Snapping (§2.3): let go with the pointer within [BAND] dp of the desktop's left or right edge — half; the top edge —
 * maximised; a corner (within the band of two edges) — a quarter. The page draws [frame] dashed while the pointer is
 * held in a zone. Corners win over edges; the top edge over the sides.
 */
object SnapZones {
    const val BAND = 8.0

    /** The zone [pointer] is in on [full], or null. */
    fun zone(pointer: DeskPoint, full: DeskRect): Snap? {
        val left = pointer.x <= full.x + BAND
        val right = pointer.x >= full.right - BAND
        val top = pointer.y <= full.y + BAND
        val bottom = pointer.y >= full.bottom - BAND
        return when {
            top && left -> Snap.TOP_LEFT
            top && right -> Snap.TOP_RIGHT
            bottom && left -> Snap.BOTTOM_LEFT
            bottom && right -> Snap.BOTTOM_RIGHT
            top -> Snap.TOP
            left -> Snap.LEFT
            right -> Snap.RIGHT
            else -> null
        }
    }

    /** Where a window snapped to [snap] stands on [full]. */
    fun frame(snap: Snap, full: DeskRect): DeskRect {
        val hw = full.w / 2
        val hh = full.h / 2
        return when (snap) {
            Snap.LEFT -> DeskRect(full.x, full.y, hw, full.h)
            Snap.RIGHT -> DeskRect(full.x + hw, full.y, full.w - hw, full.h)
            Snap.TOP -> full
            Snap.TOP_LEFT -> DeskRect(full.x, full.y, hw, hh)
            Snap.TOP_RIGHT -> DeskRect(full.x + hw, full.y, full.w - hw, hh)
            Snap.BOTTOM_LEFT -> DeskRect(full.x, full.y + hh, hw, full.h - hh)
            Snap.BOTTOM_RIGHT -> DeskRect(full.x + hw, full.y + hh, full.w - hw, full.h - hh)
        }
    }
}
