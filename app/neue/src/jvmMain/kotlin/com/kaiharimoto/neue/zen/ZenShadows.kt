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
