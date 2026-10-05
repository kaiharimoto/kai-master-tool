package com.kaiharimoto.mastertool.core.world.desk

/**
 * The desktop's icon grid (§2.1, `DeskGridTest`): 88 × 80 dp tiles from the top-left, [INSET] in, the built-ins in a
 * column and Ai's apps in columns of their own under *Made by Ai* ([MADE_HEAD]). How wide the icon columns stand is what
 * the work area starts after, so it is measured from the apps there are *now* — a window Ai opens on the app it has just
 * made must not land on that app's own new column, half covering its icon (the sliver at x ≈ 112–128 in the studio).
 */
object DeskGrid {
    const val TILE_W = 88.0
    const val TILE_H = 80.0
    const val GAP = 8.0
    const val INSET = 16.0

    /** The *Made by Ai* heading over Ai's apps: their column's tiles start under it. */
    const val MADE_HEAD = 28.0

    /** How many tiles a column holds on a desktop [height] dp tall. */
    fun rows(height: Double): Int = ((height - INSET * 2 - MADE_HEAD) / TILE_H).toInt().coerceAtLeast(1)

    /** How many icon columns there are: the built-ins, then Ai's [apps], a column more each time one is full. */
    fun columns(apps: Int, rows: Int): Int = 1 + if (apps <= 0) 0 else (apps + rows - 1) / rows.coerceAtLeast(1)

    /** How far the icon columns reach from the desktop's left edge, in dp: where the work area begins. */
    fun width(apps: Int, height: Double): Double {
        val cols = columns(apps, rows(height))
        return INSET + TILE_W * cols + GAP * (cols - 1)
    }

    /** [area] with its icon columns measured for [apps] apps. */
    fun area(area: DeskArea, apps: Int): DeskArea = area.copy(iconColumns = width(apps, area.full.h))
}
