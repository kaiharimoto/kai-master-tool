package com.kaiharimoto.mastertool.core.layout

/**
 * The size a card's picture is decoded at, which follows the card up.
 *
 * Coil decodes a picture to the size of the box it is drawn in, *as measured
 * when the request starts*, and never looks again: a card first laid out small
 * — the window at its default size before the saved bounds and Maximized
 * arrive, the interface scale before the settings are read, a deck that has
 * not yet been fitted — kept a small decode, stretched to its final size, and
 * looked blurry until something recomposed it from scratch (going to Settings
 * and back). So the card asks for its decode size explicitly, from this.
 *
 * - **Only up.** A card drawn smaller keeps the sharper decode it has;
 *   shrinking never costs a request.
 * - **In steps** of [STEP] pixels, so a deck re-fitting by a pixel at a time
 *   (the wheel, a pane sliding) asks again a handful of times, not every frame.
 * - **Never past the source** ([cap]): the small render is 268 px wide and the
 *   original 813; asking for more would decode the same pixels again.
 */
object DecodeSize {
    const val STEP = 64
    const val FLOOR = 128

    /** A card is 59 × 86. */
    private const val RATIO = 86.0 / 59.0

    /**
     * The width to decode at for a card drawn [drawn] pixels wide that has
     * already decoded at [previous] (0 for never), from a source [cap] pixels
     * wide (0 for unknown).
     */
    fun width(drawn: Int, previous: Int, cap: Int = 0): Int {
        if (drawn <= previous || drawn <= 0) return previous
        val stepped = maxOf(FLOOR, (drawn + STEP - 1) / STEP * STEP)
        val capped = if (cap > 0) minOf(stepped, cap) else stepped
        return maxOf(capped, previous)
    }

    /** The height that goes with [width] at the card's ratio, rounded up. */
    fun height(width: Int): Int = kotlin.math.ceil(width * RATIO).toInt()
}
