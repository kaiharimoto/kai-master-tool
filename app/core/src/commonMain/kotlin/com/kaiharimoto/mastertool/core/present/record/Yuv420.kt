package com.kaiharimoto.mastertool.core.present.record

/**
 * A rendered frame's pixels as a video encoder takes them (I.9, Android's render through `MediaCodec`): Y at full size,
 * U and V at half each way (4:2:0), in BT.709's limited range — what an HD video is, what Android's render tells the
 * encoder to write into the stream ([MATRIX]), and what YouTube takes an HD video as when it says nothing. Integer
 * arithmetic on 16-bit fixed point, so a phone turns a 1080p frame round in milliseconds and a test holds it exactly.
 *
 * The desk hands FFmpeg its pixels and lets it convert; a phone has no FFmpeg, and its encoders take only YUV.
 */
object Yuv420 {
    /** The colour matrix the planes are in, as the encoder is told. */
    const val MATRIX = "BT.709 limited"

    /** The width of the U and V planes for a picture [width] wide: half, rounded up. */
    fun chromaWidth(width: Int): Int = (width + 1) / 2

    /** The height of the U and V planes for a picture [height] high. */
    fun chromaHeight(height: Int): Int = (height + 1) / 2

    // BT.709, limited range, × 65,536: Y = 16 + 0.1826 R + 0.6142 G + 0.0620 B; Cb and Cr centred on 128.
    private const val YR = 11_966
    private const val YG = 40_254
    private const val YB = 4_064
    private const val UR = -6_596
    private const val UG = -22_189
    private const val UB = 28_785
    private const val VR = 28_785
    private const val VG = -26_145
    private const val VB = -2_640

    /**
     * [argb] — [width] × [height] pixels, row by row, alpha ignored — into [y] ([width] × [height]) and [u] and [v]
     * ([chromaWidth] × [chromaHeight], each the mean of its 2 × 2 pixels). An odd last row or column repeats its edge.
     */
    fun convert(argb: IntArray, width: Int, height: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        require(argb.size >= width * height) { "the picture is smaller than $width × $height" }
        val cw = chromaWidth(width)
        val ch = chromaHeight(height)
        require(y.size >= width * height && u.size >= cw * ch && v.size >= cw * ch) { "the planes are too small" }
        for (cy in 0 until ch) {
            val y0 = cy * 2
            val y1 = minOf(y0 + 1, height - 1)
            for (cx in 0 until cw) {
                val x0 = cx * 2
                val x1 = minOf(x0 + 1, width - 1)
                val p00 = argb[y0 * width + x0]
                val p01 = argb[y0 * width + x1]
                val p10 = argb[y1 * width + x0]
                val p11 = argb[y1 * width + x1]
                y[y0 * width + x0] = luma(p00)
                if (x1 != x0) y[y0 * width + x1] = luma(p01)
                if (y1 != y0) {
                    y[y1 * width + x0] = luma(p10)
                    if (x1 != x0) y[y1 * width + x1] = luma(p11)
                }
                val r = red(p00) + red(p01) + red(p10) + red(p11)
                val g = green(p00) + green(p01) + green(p10) + green(p11)
                val b = blue(p00) + blue(p01) + blue(p10) + blue(p11)
                // Four pixels summed, so two more bits to shift away; half of what is shifted away rounds.
                val i = cy * cw + cx
                u[i] = chroma((UR * r + UG * g + UB * b + (1 shl 17)) shr 18)
                v[i] = chroma((VR * r + VG * g + VB * b + (1 shl 17)) shr 18)
            }
        }
    }

    /** One pixel's Y, 16 (black) to 235 (white). */
    fun luma(argb: Int): Byte = (16 + ((YR * red(argb) + YG * green(argb) + YB * blue(argb) + (1 shl 15)) shr 16)).coerceIn(16, 235).toByte()

    private fun chroma(offset: Int): Byte = (128 + offset).coerceIn(16, 240).toByte()

    private fun red(c: Int) = (c shr 16) and 0xFF

    private fun green(c: Int) = (c shr 8) and 0xFF

    private fun blue(c: Int) = c and 0xFF
}
