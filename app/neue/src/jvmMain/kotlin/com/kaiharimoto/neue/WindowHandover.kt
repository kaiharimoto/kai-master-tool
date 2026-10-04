package com.kaiharimoto.neue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * One window Neue is shown in (1.0.24): the decorated one ([full] null) or, for
 * immersive mode on Windows, a borderless one over the monitor [full]. `Main.kt`
 * keeps a list of them, newest last — one, but for the moment of a handover.
 */
internal class Shown(val id: Int, val full: java.awt.Rectangle?) {
    /** Asked to hand over: keep the last frame as a picture, and let the tree go. */
    var freeze by mutableStateOf(false)

    /** The picture is taken, and drawn in place of the tree. */
    var frozen by mutableStateOf(false)

    /** The tree has gone, and everything it undoes as it goes is undone: the next window may begin. */
    var released by mutableStateOf(false)

    /** On screen, and painted. */
    var ready by mutableStateOf(false)
}

/**
 * [content], until [me] is asked to hand over: then one more frame is drawn through
 * a graphics layer, the layer is kept as a picture, and the picture is drawn in
 * place of [content] — the same pixels, with nothing behind them. A picture that
 * could not be taken leaves the window blank, never the tree alive: two live trees
 * would register the same drop targets and zen's slots, and the one let go last
 * would take them from the other.
 */
@Composable
internal fun Handover(me: Shown, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(me.freeze) {
        if (!me.freeze) {
            me.frozen = false
            me.released = false
            still = null
            return@LaunchedEffect
        }
        // A frame drawn through the layer, and the frame after it begun, so the recording is whole.
        withFrameNanos { }
        withFrameNanos { }
        still = runCatching { layer.toImageBitmap() }.getOrNull()
        me.frozen = true
    }
    Box(
        Modifier.fillMaxSize().drawWithContent {
            if (me.freeze && !me.frozen) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else {
                drawContent()
            }
        },
    ) {
        if (me.frozen) {
            val picture = still
            if (picture != null) {
                Canvas(Modifier.fillMaxSize()) { drawImage(picture) }
                // A whole window of pixels: given back the moment it leaves the screen (1.0.92) — the next
                // window is up, or the handover was called off — not whenever the collector comes by.
                // What has already drawn it keeps its own reference to the pixels.
                DisposableEffect(picture) { onDispose { runCatching { picture.asSkiaBitmap().close() } } }
            }
        } else {
            content()
            // Let go of in the same pass as everything in the tree: by the time anyone
            // hears it, the drop targets and zen's slots the tree held are given up.
            DisposableEffect(Unit) { onDispose { me.released = true } }
        }
    }
}

/** How long a window may take to keep its last frame before the next is built anyway. */
internal const val FREEZE_MS = 500L

/** How long the picture of the last window waits for the next to be on screen, at most. */
internal const val HANDOVER_MS = 3_000L
