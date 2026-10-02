package com.kaiharimoto.neue.present.paint

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

// Android's blur mask filter takes the radius the creator set.
internal actual fun DrawScope.slideShadow(path: Path, color: Color, blur: Float) {
    if (color.alpha <= 0f) return
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
}
