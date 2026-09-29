package com.kaiharimoto.neue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Immersive mode on Windows swaps one window for another (1.0.24): the window on
 * screen hands over by keeping its last frame as a picture and letting its tree go
 * before the next is built. This holds [Handover] to both halves of that, headless:
 * the picture is the frame it replaces, pixel for pixel, and the tree is gone —
 * every `onDispose` run — before [Shown.released] says so.
 */
class WindowHandoverTest {

    private fun pixels(image: org.jetbrains.skia.Image): ByteArray = org.jetbrains.skia.Bitmap.makeFromImage(image).readPixels()!!

    @Test
    fun aWindowHandingOverKeepsItsLastFrameAndLetsItsTreeGo() {
        val me = Shown(0, null)
        var disposed = false
        ImageComposeScene(120, 80).let { scene ->
            scene.setContent {
                Handover(me) {
                    DisposableEffect(Unit) { onDispose { disposed = true } }
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color.White)
                        drawRect(Color.Black, Offset(10f, 10f), Size(50f, 30f))
                    }
                }
            }
            var t = 0L
            fun frame() = scene.render(t).also { t += 16_000_000L }
            val live = frame()
            me.freeze = true
            repeat(8) { frame() }
            assertTrue(me.frozen, "the picture is taken")
            assertTrue(me.released, "the tree is let go")
            assertTrue(disposed, "and everything in it disposed")
            val still = frame()
            assertEquals(live.width, still.width)
            assertContentEquals(pixels(live), pixels(still), "the same pixels")
            scene.close()
        }
    }

    @Test
    fun aHandoverCalledOffBringsTheTreeBack() {
        val me = Shown(0, null)
        var composed = 0
        ImageComposeScene(40, 40).let { scene ->
            scene.setContent {
                Handover(me) {
                    DisposableEffect(Unit) {
                        composed++
                        onDispose { }
                    }
                }
            }
            var t = 0L
            fun frame() = scene.render(t).also { t += 16_000_000L }
            frame()
            me.freeze = true
            repeat(8) { frame() }
            assertTrue(me.released)
            me.freeze = false
            repeat(4) { frame() }
            assertFalse(me.frozen)
            assertFalse(me.released)
            assertEquals(2, composed, "the tree is composed again")
            scene.close()
        }
    }
}
