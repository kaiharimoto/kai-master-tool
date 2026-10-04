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
    val paint = BlurPaints.paint(radius)
    paint.color = color.toArgb()
    drawIntoCanvas { it.nativeCanvas.drawRect(left, top, right, bottom, paint) }
}

/**
 * The blur's paints, one per exact radius (1.0.92): sixty shadows a frame share a
 * handful, and a paint and mask filter minted per rectangle were sixty objects a
 * frame for the collector. The same radius is the same blur, so the same pixels;
 * a canvas copies what it needs from a paint as it draws, so one is reused safely.
 */
private object BlurPaints {
    private const val KEPT = 16

    private val paints = object : LinkedHashMap<Float, Paint>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Float, Paint>): Boolean = size > KEPT
    }

    fun paint(radius: Float): Paint = synchronized(paints) {
        paints.getOrPut(radius) {
            Paint().apply {
                isAntiAlias = true
                maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
}
