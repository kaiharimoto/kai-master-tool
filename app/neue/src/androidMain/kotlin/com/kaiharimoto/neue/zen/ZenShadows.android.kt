package com.kaiharimoto.neue.zen

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

// Android's BlurMaskFilter takes a radius and turns it into a sigma as
// 0.57735 * radius + 0.5 (the same rule Skia's legacy radius used), so the sigma
// the shadow is designed in is converted back. Hardware-accelerated from API 28.
internal actual fun DrawScope.blurRect(left: Float, top: Float, right: Float, bottom: Float, color: Color, sigma: Float) {
    val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
    val paint = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { it.nativeCanvas.drawRect(left, top, right, bottom, paint) }
}
