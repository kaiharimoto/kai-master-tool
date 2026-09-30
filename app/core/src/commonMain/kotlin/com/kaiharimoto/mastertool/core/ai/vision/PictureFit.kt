package com.kaiharimoto.mastertool.core.ai.vision

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How big a picture is sent to a model (1.0.55). Anthropic's guidance is a longer side of
 * at most 1568 pixels and about 1.15 megapixels — past that a picture is shrunk on their
 * side anyway, costs more tokens and waits longer — and the other providers read the same
 * size well. A screenshot keeps its crisp PNG while that stays small; anything else, a
 * photograph most of all, goes as a JPEG.
 */
object PictureFit {
    const val LONG_SIDE = 1568
    const val PIXELS = 1_150_000

    /** The most pictures one message may carry. */
    const val MOST = 5

    /** A PNG larger than this is sent as a JPEG instead. */
    const val PNG_BYTES = 1_500_000

    const val JPEG_QUALITY = 88

    /** The size [width] × [height] is sent at: never larger than it was, aspect kept. */
    fun size(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val bySide = LONG_SIDE.toDouble() / max(width, height)
        val byArea = sqrt(PIXELS.toDouble() / (width.toDouble() * height))
        val scale = min(1.0, min(bySide, byArea))
        if (scale >= 1.0) return width to height
        return max(1, floor(width * scale).toInt()) to max(1, floor(height * scale).toInt())
    }

    /** Whether a picture should go as a PNG: it came as one, and its PNG is small enough. */
    fun keepsPng(wasPng: Boolean, pngBytes: Int?): Boolean = wasPng && pngBytes != null && pngBytes <= PNG_BYTES

    /** A picture's type from its file name or first bytes. */
    fun mime(name: String, bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
    }

    /** How many tokens a picture of this size costs a Claude model: width × height / 750. */
    fun tokens(width: Int, height: Int): Int = (width.toDouble() * height / 750).roundToInt()
}
