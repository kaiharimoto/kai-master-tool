package com.kaiharimoto.mastertool.core.input

/**
 * The sizes a finger needs of the chrome outside the deck (touch swarm, rec 18),
 * in dp, read by the kit when the window is a touch screen first.
 *
 * The densest finger grids were all chrome: 24dp filter chips 4dp apart (a finger
 * pad covers two), 13dp-tall links that act, 34dp menu rows abutting, and small
 * segments whose ≥ and ≤ looked alike. None of this is deck budget, so none of it
 * moves a card; the lens row over the deck keeps its 28dp.
 */
object TouchMetrics {
    /** The least distance between the centres of two packed targets: a finger's pad. */
    const val MIN_PITCH = 44

    /** A chip or a list tag, and the gap between chips both ways. */
    const val CHIP = 32
    const val CHIP_GAP = 12
    const val CHIP_PAD = 14

    /** A link that acts, boxed to this height. */
    const val LINK = 32

    /** A menu row, the hairline between them included. */
    const val MENU_ROW = 44

    /** A small segmented control outside the deck. */
    const val SEGMENT = 36

    /** An icon button that stands alone in a row (the Groups rows' arrows, the art arrows). */
    const val ICON = 40

    /** A switch row in Settings, the whole row one target (rec 27). */
    const val SETTING_ROW = 48
}
