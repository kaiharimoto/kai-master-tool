package com.kaiharimoto.neue.cards

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.toBitmap
import kotlin.math.roundToInt

internal actual fun coil3.Image.argb(x: Int, y: Int, w: Int, h: Int): IntArray? {
    val decoded = (this as? BitmapImage)?.bitmap ?: toBitmap()
    // A hardware bitmap lives on the GPU and cannot be read; a software copy can. The copy is
    // ours alone, a whole card's worth of pixels, so it is freed as soon as the bar is read
    // (1.0.92) rather than left for the collector.
    val copied = decoded.config == Bitmap.Config.HARDWARE
    val bitmap: Bitmap = if (copied) (decoded.copy(Bitmap.Config.ARGB_8888, false) ?: return null) else decoded
    try {
        if (x + w > bitmap.width || y + h > bitmap.height) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x, y, w, h)
        for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
        return pixels
    } finally {
        if (copied) bitmap.recycle()
    }
}

internal actual fun alphaBitmap(mask: FloatArray, w: Int, h: Int): ImageBitmap {
    // Alpha alone, a byte a pixel (1.0.92): a mask is only ever drawn through its alpha (DstIn,
    // and a SrcIn tint), and an ALPHA_8 bitmap is drawn as that alpha in the paint's colour.
    // setPixels takes unpremultiplied colours and keeps their alpha: white, at each alpha.
    val colors = IntArray(w * h) { i -> ((mask[i] * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    bitmap.setPixels(colors, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}
