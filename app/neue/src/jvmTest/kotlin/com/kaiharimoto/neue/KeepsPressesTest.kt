package com.kaiharimoto.neue

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kaiharimoto.neue.kit.keepsPresses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A surface over the page keeps presses from what is under it without spoiling its own controls (1.1.36, kai: "I can't
 * scroll down the keepsake window to see the rest of the items or press the take out button" on the phone). A finger,
 * headless: dragged in small steps it scrolls what is inside, a tap that moves a pixel still presses a button, and the
 * page under it hears nothing. The blocker it replaced, which spent every move, is held to the bug it had.
 */
class KeepsPressesTest {
    private val spendsEverything = Modifier.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }

    private class Seen { var scrolled = 0; var pressed = false; var under = false }

    private fun run(blocker: Modifier, gesture: (finger: (PointerEventType, Float, Float) -> Unit) -> Unit): Seen {
        val seen = Seen()
        lateinit var state: ScrollState
        val scene = ImageComposeScene(200, 400, Density(1f))
        scene.setContent {
            state = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                // the page under the surface
                Box(Modifier.fillMaxSize().clickable { seen.under = true })
                Box(Modifier.fillMaxSize().then(blocker)) {
                    Column(Modifier.fillMaxSize().verticalScroll(state)) {
                        Box(Modifier.fillMaxWidth().height(60.dp).clickable { seen.pressed = true })
                        Box(Modifier.fillMaxWidth().height(2000.dp))
                    }
                }
            }
        }
        var t = 0L
        fun frame() { scene.render(t * 1_000_000L) }
        frame()
        gesture { type, x, y ->
            t += 16
            scene.sendPointerEvent(type, Offset(x, y), timeMillis = t, type = PointerType.Touch)
            frame()
        }
        repeat(30) { t += 16; frame() }
        seen.scrolled = state.value
        scene.close()
        return seen
    }

    private val drag: ((PointerEventType, Float, Float) -> Unit) -> Unit = { f ->
        f(PointerEventType.Press, 100f, 350f)
        var y = 350f
        while (y > 120f) { y -= 3f; f(PointerEventType.Move, 100f, y) }
        f(PointerEventType.Release, 100f, y)
    }

    // a finger's tap is never still: it lands, shifts a pixel, and lifts
    private val tap: ((PointerEventType, Float, Float) -> Unit) -> Unit = { f ->
        f(PointerEventType.Press, 100f, 30f)
        f(PointerEventType.Move, 101f, 31f)
        f(PointerEventType.Release, 101f, 31f)
    }

    @Test
    fun aFingerScrollsInsideIt() {
        assertTrue(run(Modifier.keepsPresses(), drag).scrolled > 150)
        assertEquals(0, run(spendsEverything, drag).scrolled, "the old blocker spent the moves the scroll was weighing")
    }

    @Test
    fun aFingersTapPressesAButtonInsideIt() {
        assertTrue(run(Modifier.keepsPresses(), tap).pressed)
        assertFalse(run(spendsEverything, tap).pressed, "the old blocker spent the pixel the tap moved")
    }

    @Test
    fun thePageUnderItHearsNothing() {
        assertFalse(run(Modifier.keepsPresses(), tap).under)
        assertFalse(run(Modifier.keepsPresses(), drag).under)
    }
}
