package com.kaiharimoto.neue.zen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint

// Skia's mask-filter blur, which takes the Gaussian's sigma directly.
internal actual fun DrawScope.blurRect(left: Float, top: Float, right: Float, bottom: Float, color: Color, sigma: Float) {
    val paint = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
    }
    drawIntoCanvas { it.nativeCanvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), paint) }
    paint.close()
}
