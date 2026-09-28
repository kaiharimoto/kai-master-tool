package com.kaiharimoto.neue.cards

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.toBitmap
import kotlin.math.roundToInt

internal actual fun coil3.Image.argb(x: Int, y: Int, w: Int, h: Int): IntArray? {
    var bitmap = (this as? BitmapImage)?.bitmap ?: toBitmap()
    // A hardware bitmap lives on the GPU and cannot be read; a software copy can.
    if (bitmap.config == Bitmap.Config.HARDWARE) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    if (x + w > bitmap.width || y + h > bitmap.height) return null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, x, y, w, h)
    return IntArray(pixels.size) { pixels[it] or (0xFF shl 24) }
}

internal actual fun alphaBitmap(mask: FloatArray, w: Int, h: Int): ImageBitmap {
    // Android takes unpremultiplied colours: white, at each alpha.
    val colors = IntArray(w * h) { i -> ((mask[i] * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF }
    return Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
