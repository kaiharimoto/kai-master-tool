package com.kaiharimoto.neue.zen

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.kaiharimoto.mastertool.core.motion.ZenShadow
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint

/**
 * The shadow under a floating card in deep zen — **kai's exception to Master
 * UI's "no shadows"**, and the only shadow in Neue. `MasterUiLawTest` allows it
 * in this file and nowhere else.
 *
 * Drawn with Skia's own blur on a rectangle, which it does analytically — sixty
 * soft shadows a frame cost next to nothing — and in the theme's ink: black on
 * paper, and on ink the exact inversion, a faint light under each card, because
 * a black shadow on black says nothing and dark is paper and ink swapped.
 */
fun DrawScope.zenShadow(card: Rect, lift: Float, amount: Float, ink: Color) {
    if (amount <= 0.001f || card.width <= 0f) return
    val s = ZenShadow.of(lift)
    val w = card.width
    val paint = Paint().apply {
        isAntiAlias = true
        color = ink.copy(alpha = s.alpha * amount).toArgb()
        maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, (s.blur * w / 2f).coerceAtLeast(0.5f))
    }
    val r = card.translate(s.dx * w, s.dy * w)
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(r.left, r.top, r.right, r.bottom), paint)
    }
    paint.close()
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
    val paint = Paint().apply {
        isAntiAlias = true
        maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, band * 1.4f)
    }
    val strips = arrayOf(
        org.jetbrains.skia.Rect.makeLTRB(card.left - band, card.top - band, card.left, card.bottom + band),
        org.jetbrains.skia.Rect.makeLTRB(card.left - band, card.top - band, card.right + band, card.top),
        org.jetbrains.skia.Rect.makeLTRB(card.right, card.top - band, card.right + band, card.bottom + band),
        org.jetbrains.skia.Rect.makeLTRB(card.left - band, card.bottom, card.right + band, card.bottom + band),
    )
    drawIntoCanvas { canvas ->
        for (s in 0..3) {
            if (!sides[s]) continue
            val r = strips[s]
            val phase = time * 1.3f + (r.left + r.top) / (cardWidth * 1.7f)
            paint.color = com.kaiharimoto.neue.cards.GroupMarkers.shimmer(color, phase).copy(alpha = 0.62f * amount).toArgb()
            canvas.nativeCanvas.drawRect(r, paint)
        }
    }
    paint.close()
}
