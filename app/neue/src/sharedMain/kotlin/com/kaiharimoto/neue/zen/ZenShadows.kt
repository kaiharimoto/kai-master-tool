package com.kaiharimoto.neue.zen

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kaiharimoto.mastertool.core.motion.ZenShadow
import com.kaiharimoto.neue.cards.GroupMarkers

/**
 * The shadow under a floating card in deep zen — **kai's exception to Master
 * UI's "no shadows"**, and the only shadow in Neue. `MasterUiLawTest` allows it
 * in this file and nowhere else.
 *
 * Drawn with the platform's own blur on a rectangle ([blurRect]: Skia's mask
 * filter on the desktop, Android's `BlurMaskFilter`), which both do analytically
 * — sixty soft shadows a frame cost next to nothing — and in the theme's ink: black on
 * paper, and on ink the exact inversion, a faint light under each card, because
 * a black shadow on black says nothing and dark is paper and ink swapped.
 */
fun DrawScope.zenShadow(card: Rect, lift: Float, amount: Float, ink: Color) {
    if (amount <= 0.001f || card.width <= 0f) return
    val s = ZenShadow.of(lift)
    val w = card.width
    val r = card.translate(s.dx * w, s.dy * w)
    blurRect(r.left, r.top, r.right, r.bottom, ink.copy(alpha = s.alpha * amount), (s.blur * w / 2f).coerceAtLeast(0.5f))
}

/**
 * A Roles piece's outline in deep zen, glowing (kai, 1.0.15: "have the color
 * slightly glow in a prismatic way in the assigned color around the border of
 * the card group subtly"): along each of [card]'s [sides] that is on the outline
 * — left, top, right, bottom — a soft band of the group's [color], its hue
 * swung a little either way by where it is and by [time]
 * (`GroupMarkers.shimmer`), so the light runs round a piece rather than sitting
 * on it. A blur, so it lives here with the shadows, the one file allowed one.
 */
fun DrawScope.zenGlow(card: Rect, sides: BooleanArray, color: Color, amount: Float, time: Float, cardWidth: Float) {
    if (amount <= 0.001f || card.width <= 0f) return
    val band = (cardWidth * 0.035f).coerceAtLeast(2f)
    val strips = arrayOf(
        Rect(card.left - band, card.top - band, card.left, card.bottom + band),
        Rect(card.left - band, card.top - band, card.right + band, card.top),
        Rect(card.right, card.top - band, card.right + band, card.bottom + band),
        Rect(card.left - band, card.bottom, card.right + band, card.bottom + band),
    )
    for (i in 0..3) {
        if (!sides[i]) continue
        val r = strips[i]
        val phase = time * 1.3f + (r.left + r.top) / (cardWidth * 1.7f)
        blurRect(r.left, r.top, r.right, r.bottom, GroupMarkers.shimmer(color, phase).copy(alpha = 0.62f * amount), band * 1.4f)
    }
}

/** A rectangle in [color], blurred with a Gaussian of [sigma] pixels: the one blur Neue draws, on each platform. */
internal expect fun DrawScope.blurRect(left: Float, top: Float, right: Float, bottom: Float, color: Color, sigma: Float)
