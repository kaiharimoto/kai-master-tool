package com.kaiharimoto.neue.cards

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import coil3.BitmapImage
import coil3.toBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

internal actual fun coil3.Image.argb(x: Int, y: Int, w: Int, h: Int): IntArray? =
    ((this as? BitmapImage)?.bitmap ?: toBitmap()).argb(x, y, w, h)

private fun Bitmap.argb(x: Int, y: Int, w: Int, h: Int): IntArray? {
    val bytes = readPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), w * 4, x, y) ?: return null
    return IntArray(w * h) { i ->
        val b = bytes[i * 4].toInt() and 0xFF
        val g = bytes[i * 4 + 1].toInt() and 0xFF
        val r = bytes[i * 4 + 2].toInt() and 0xFF
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

internal actual fun alphaBitmap(mask: FloatArray, w: Int, h: Int): ImageBitmap {
    val bytes = ByteArray(w * h * 4)
    mask.forEachIndexed { i, a ->
        val v = (a * 255f).roundToInt().coerceIn(0, 255).toByte()
        bytes[i * 4] = v
        bytes[i * 4 + 1] = v
        bytes[i * 4 + 2] = v
        bytes[i * 4 + 3] = v
    }
    return Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL), bytes, w * 4).toComposeImageBitmap()
}

/** The masks of a Skia image the screenshot has decoded itself. */
fun NameMasks.read(image: Image, frameType: String): NameMask? {
    val bitmap = Bitmap.makeFromImage(image)
    return compute(bitmap.width, bitmap.height, frameType) { x, y, w, h -> bitmap.argb(x, y, w, h) }
}
