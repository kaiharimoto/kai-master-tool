package com.kaiharimoto.mastertool.core.present.record

/**
 * A camera that is not there (1.1.13): pictures made of arithmetic, for a machine with no camera — the test that
 * renders a real video, the studio, and the app run with `-Dneue.camera=synthetic`. Each frame is plainly
 * synthetic: soft bands, a square crossing it once every two seconds, and a bar of eight cells that counts the
 * frames in binary, so a frame dropped or doubled in a render shows. Colours are ARGB.
 */
object SyntheticCamera {
    const val WIDTH = 640
    const val HEIGHT = 360

    /** Frame [n] of a camera at [fps], [width] × [height], as ARGB pixels row by row. */
    fun frame(n: Int, fps: Int = 30, width: Int = WIDTH, height: Int = HEIGHT): IntArray {
        val px = IntArray(width * height)
        val phase = (n % (fps * 2)).toFloat() / (fps * 2)
        val side = height / 3
        val sx = ((width - side) * phase).toInt()
        val sy = (height - side) / 2
        val cell = width / 16
        for (y in 0 until height) {
            val band = 0x30 + (y * 0x50 / height)
            for (x in 0 until width) {
                var r = band
                var g = band + 0x10
                var b = band + 0x28
                if (x in sx until sx + side && y in sy until sy + side) { r = 0xE8; g = 0xE8; b = 0xE0 }
                if (y < cell && x < cell * 8) {
                    val bit = (n shr (7 - x / cell)) and 1
                    val v = if (bit == 1) 0xF0 else 0x10
                    r = v; g = v; b = v
                }
                px[y * width + x] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return px
    }

    /** [argb] as bytes in B, G, R, A order (what FFmpeg calls BGRA, and Skia's N32 on these machines). */
    fun bgra(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val c = argb[i]
            out[i * 4] = (c and 0xFF).toByte()
            out[i * 4 + 1] = ((c shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((c shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((c ushr 24) and 0xFF).toByte()
        }
        return out
    }
}
