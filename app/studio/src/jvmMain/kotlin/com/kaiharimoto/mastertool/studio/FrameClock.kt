package com.kaiharimoto.mastertool.studio

import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.delay

private const val FRAME_NANOS = 16_666_667L

/**
 * A frame clock that advances in real time and in stage time at once.
 *
 * Animations integrate the nanos they are handed; the network hands back card
 * art on other threads and needs the wall clock to move for its callbacks to
 * land. Advancing only one of the two is how a harness photographs either a
 * frozen screen or a screen with no pictures on it.
 */
internal class FrameClock(private val scene: ImageComposeScene, private val pauseMillis: Long) {
    private var nanos = 0L

    suspend fun run(frames: Int) {
        repeat(frames) {
            scene.render(nanos)
            nanos += FRAME_NANOS
            if (pauseMillis > 0) delay(pauseMillis)
        }
    }

    fun frame() = scene.render(nanos)

    /** One frame, and how long it took to raster. No pause: this is a stopwatch. */
    fun timed(): Long {
        val started = System.nanoTime()
        scene.render(nanos)
        val took = System.nanoTime() - started
        nanos += FRAME_NANOS
        return took
    }
}
