package com.kaiharimoto.mastertool.core.present.record

import kotlin.math.roundToInt

/**
 * The camera's picture in its zone (1.1.13): cropped to fill — the middle of the picture, as large as the zone's shape
 * allows, never stretched and never letterboxed — whatever the camera's shape and the zone's (a 16:9 camera in a
 * circle, a column, a pill). The live preview and the rendered video crop the same way.
 */
object CameraFit {
    /** The part of a [srcW] × [srcH] picture that fills a [dstW] × [dstH] zone: x, y, width, height in the picture's pixels. */
    fun cover(srcW: Int, srcH: Int, dstW: Float, dstH: Float): IntArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0f || dstH <= 0f) return intArrayOf(0, 0, srcW.coerceAtLeast(0), srcH.coerceAtLeast(0))
        val zone = dstW / dstH
        val pic = srcW.toFloat() / srcH
        return if (pic > zone) {
            // Wider than the zone: the sides go.
            val w = (srcH * zone).roundToInt().coerceIn(1, srcW)
            intArrayOf((srcW - w) / 2, 0, w, srcH)
        } else {
            val h = (srcW / zone).roundToInt().coerceIn(1, srcH)
            intArrayOf(0, (srcH - h) / 2, srcW, h)
        }
    }
}
