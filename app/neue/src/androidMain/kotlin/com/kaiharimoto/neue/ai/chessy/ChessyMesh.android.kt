package com.kaiharimoto.neue.ai.chessy

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
    paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
    drawIntoCanvas {
        val c = it.nativeCanvas
        // Drawn on the GPU, drawVertices needs Android 10; before it the picture goes flat, carried by its corners.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || !c.isHardwareAccelerated) {
            c.drawVertices(Canvas.VertexMode.TRIANGLES, vertexCount * 2, positions, 0, texs, 0, colors, 0, indices, 0, indices.size, paint)
        } else {
            val last = vertexCount - 1
            val bmp = image.asAndroidBitmap()
            c.drawBitmap(bmp, null, RectF(positions[0], positions[1], positions[last * 2], positions[last * 2 + 1]), paint.flat)
        }
    }
}

private class MeshPaint(image: ImageBitmap) : Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) {
    val flat = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        // Her pictures are her whole 1320 x 1740 sheet, drawn four to eight times smaller: without mipmaps that
        // shrink skips texels and her lines shimmer. With them the GPU samples trilinearly, as Skia does on the desk.
        val bitmap = image.asAndroidBitmap()
        bitmap.setHasMipMap(true)
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    override fun setAlpha(a: Int) {
        super.setAlpha(a)
        flat.alpha = a
    }
}

private object MeshPaints {
    private val paints = WeakHashMap<ImageBitmap, MeshPaint>()
    fun paint(image: ImageBitmap): MeshPaint = synchronized(paints) { paints.getOrPut(image) { MeshPaint(image) } }
}
