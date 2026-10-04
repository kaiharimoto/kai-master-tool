package com.kaiharimoto.neue.cards

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
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

// Alpha alone, a byte a pixel (1.0.92): a mask is only ever drawn through its alpha, and an
// alpha-only image drawn with Compose's paint is that alpha in black, which DstIn and a SrcIn
// tint read exactly as they read the white this was before (`CardDrawingCacheTest`).
internal actual fun alphaBitmap(mask: FloatArray, w: Int, h: Int): ImageBitmap {
    val bytes = ByteArray(w * h) { i -> (mask[i] * 255f).roundToInt().coerceIn(0, 255).toByte() }
    // Installed straight into a bitmap: an image handed to Compose is converted to 32-bit colour on the way.
    val bitmap = Bitmap()
    check(bitmap.installPixels(ImageInfo(w, h, ColorType.ALPHA_8, ColorAlphaType.PREMUL), bytes, w)) { "the mask did not install" }
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

/** The masks of a Skia image the screenshot has decoded itself. */
fun NameMasks.read(image: Image, frameType: String): NameMask? {
    val bitmap = Bitmap.makeFromImage(image)
    return compute(bitmap.width, bitmap.height, frameType) { x, y, w, h -> bitmap.argb(x, y, w, h) }
}
