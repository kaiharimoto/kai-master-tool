package com.kaiharimoto.neue.present.paint

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint

// Skia's mask-filter blur, which takes the Gaussian's sigma: half the blur radius a creator sets.
internal actual fun DrawScope.slideShadow(path: Path, color: Color, blur: Float) {
    if (color.alpha <= 0f) return
    val paint = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        if (blur > 0.5f) maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, blur / 2f)
    }
    drawIntoCanvas { it.nativeCanvas.drawPath(path.asSkiaPath(), paint) }
    paint.close()
}
