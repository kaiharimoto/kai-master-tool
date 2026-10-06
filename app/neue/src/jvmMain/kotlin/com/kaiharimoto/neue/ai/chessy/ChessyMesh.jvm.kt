package com.kaiharimoto.neue.ai.chessy

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode
import java.util.WeakHashMap

internal actual fun DrawScope.drawMesh(
    image: ImageBitmap,
    positions: FloatArray,
    texs: FloatArray,
    colors: IntArray,
    indices: ShortArray,
    vertexCount: Int,
    alpha: Float,
) {
    val paint = MeshPaints.paint(image)
    paint.setAlphaf(alpha.coerceIn(0f, 1f))
    drawIntoCanvas { it.nativeCanvas.drawVertices(VertexMode.TRIANGLES, positions, colors, texs, indices, BlendMode.MODULATE, paint) }
}

/** A picture's paint, its shader made once: the same picture drawn every frame needs no new native objects. */
private object MeshPaints {
    private val paints = WeakHashMap<ImageBitmap, Paint>()
    // mipmapped: she is drawn far smaller than her pictures (the bar), and plain linear sampling would shimmer
    private val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

    fun paint(image: ImageBitmap): Paint = synchronized(paints) {
        paints.getOrPut(image) {
            Paint().apply {
                isAntiAlias = true
                shader = Image.makeFromBitmap(image.asSkiaBitmap()).makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null)
            }
        }
    }
}
