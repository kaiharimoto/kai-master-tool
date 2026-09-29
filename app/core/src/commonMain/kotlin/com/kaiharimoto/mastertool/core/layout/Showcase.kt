package com.kaiharimoto.mastertool.core.layout

/**
 * Where a card is drawn when it is shown full screen (kai, v1.3.6: "a full screen
 * card art with gyroscopic as a strong demo point"). Two ways:
 *
 * - [card]: the whole card, as large as the screen allows with a margin, in the
 *   middle — it turns with the phone and its foil catches the light.
 * - [art]: the artwork alone, the card drawn so large that its art window covers
 *   the screen, with [PARALLAX] of the screen to spare on every side; the tilt
 *   slides it within that spare, so the picture seems to sit behind the glass.
 *
 * Pixels in, pixels out, so the arithmetic is the same on any screen.
 */
object Showcase {
    /** The whole card's share of the screen's shorter fit. */
    const val CARD_FILL = 0.86f

    /** How far the artwork may slide, as a share of the screen, each way. */
    const val PARALLAX = 0.05f

    data class Placement(val width: Float, val height: Float, val left: Float, val top: Float)

    /** The whole card, [aspect] its width over its height, centred. */
    fun card(screenWidth: Float, screenHeight: Float, aspect: Float): Placement {
        val height = minOf(screenHeight * CARD_FILL, screenWidth * CARD_FILL / aspect).coerceAtLeast(0f)
        val width = height * aspect
        return Placement(width, height, (screenWidth - width) / 2f, (screenHeight - height) / 2f)
    }

    /**
     * The card drawn so its art window, [frame], covers the screen whatever [tiltX] and
     * [tiltY] (−1..1), the artwork's middle at the screen's middle when level.
     */
    fun art(frame: ArtFrame, screenWidth: Float, screenHeight: Float, aspect: Float, tiltX: Float, tiltY: Float): Placement {
        val spare = 1f + 2f * PARALLAX
        val byWidth = screenWidth * spare / frame.width.coerceAtLeast(0.01f)
        val byHeight = screenHeight * spare * aspect / frame.height.coerceAtLeast(0.01f)
        val width = maxOf(byWidth, byHeight)
        val height = width / aspect
        val artCentreX = (frame.left + frame.width / 2f) * width
        val artCentreY = (frame.top + frame.height / 2f) * height
        val left = screenWidth / 2f - artCentreX - tiltX.coerceIn(-1f, 1f) * PARALLAX * screenWidth
        val top = screenHeight / 2f - artCentreY - tiltY.coerceIn(-1f, 1f) * PARALLAX * screenHeight
        return Placement(width, height, left, top)
    }
}
