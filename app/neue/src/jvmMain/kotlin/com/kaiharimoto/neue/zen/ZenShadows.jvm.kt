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
    val paint = BlurPaints.paint(sigma)
    paint.color = color.toArgb()
    drawIntoCanvas { it.nativeCanvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), paint) }
}

/**
 * The blur's paints, one per exact sigma (1.0.92): sixty shadows a frame share a
 * handful of sigmas, and a paint and mask filter minted per rectangle were sixty
 * native objects a frame left to the collector. The same sigma is the same blur, so
 * the same pixels. A paint let go is closed with its filter; a recording that drew
 * with it holds its own reference, so closing is never a use after free.
 */
internal object BlurPaints {
    private const val KEPT = 16

    private val paints = object : LinkedHashMap<Float, Pair<Paint, MaskFilter>>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Float, Pair<Paint, MaskFilter>>): Boolean {
            if (size <= KEPT) return false
            eldest.value.first.close()
            eldest.value.second.close()
            return true
        }
    }

    fun paint(sigma: Float): Paint = synchronized(paints) {
        paints.getOrPut(sigma) {
            val filter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
            Paint().apply {
                isAntiAlias = true
                maskFilter = filter
            } to filter
        }.first
    }

    val size: Int get() = synchronized(paints) { paints.size }
}
