package com.kaiharimoto.mastertool.core.input

/**
 * Where a carried card is drawn, and where it lands (touch swarm, rec 12).
 *
 * A mouse's card rides centred on the pointer, and the pointer is a pixel: the
 * card is where the pointer is. A finger covers the card it carries, and the
 * drop's ink bar with it. So a finger's card is drawn [LIFT_DP] **above** the
 * finger, at least [MIN_WIDTH_DP] wide, and the drop resolves at the **drawn**
 * card's centre — the classic app's rule, "a drop lands where the card is drawn".
 *
 * Lengths are in pixels; [density] is pixels per dp.
 */
object CarryOffset {
    const val LIFT_DP = 12f
    const val MIN_WIDTH_DP = 64f

    /** How far a drop point must move past a boundary before the target changes, in dp. */
    const val HYSTERESIS_DP = 12f

    /** How far a finger must travel on a deck card before it picks the card up, at least, in dp. */
    const val PICKUP_DP = 12f

    data class Drawn(val left: Float, val top: Float, val width: Float, val height: Float) {
        val centreX: Float get() = left + width / 2f
        val centreY: Float get() = top + height / 2f
    }

    fun carried(pointerX: Float, pointerY: Float, cardWidth: Float, cardHeight: Float, finger: Boolean, density: Float): Drawn {
        if (!finger) return Drawn(pointerX - cardWidth / 2f, pointerY - cardHeight / 2f, cardWidth, cardHeight)
        val width = maxOf(cardWidth, MIN_WIDTH_DP * density)
        val height = if (cardWidth > 0f) cardHeight * width / cardWidth else cardHeight
        return Drawn(pointerX - width / 2f, pointerY - LIFT_DP * density - height, width, height)
    }

    /** Where the drop resolves: the drawn card's centre, for either hand. */
    fun dropPoint(drawn: Drawn): Pair<Float, Float> = drawn.centreX to drawn.centreY
}
