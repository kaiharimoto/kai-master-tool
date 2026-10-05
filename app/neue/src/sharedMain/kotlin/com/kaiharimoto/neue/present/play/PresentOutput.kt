package com.kaiharimoto.neue.present.play

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.present.paint.SlideRender
import com.kaiharimoto.neue.present.paint.rememberSlideShot
import com.kaiharimoto.neue.present.rememberSlideContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a presentation goes besides this window (1.1.x, the audit's I1 and M9): the slides in a window of
 * their own for a screen recorder, and a slide drawn as a picture for whoever asks — Ai's `present_view`.
 * Kept on the holder for the app's lifetime.
 */
class PresentOutput {
    /**
     * The slides in an ordinary, resizable window while this one is the presenter's (desktop): OBS's Window
     * Capture records that window and never the notes, which stay here. Works on one screen.
     */
    var slidesWindow by mutableStateOf(false)

    /** Slides waiting to be drawn as pictures, oldest first; [SlideRenderHost] draws them. */
    internal val requests = mutableStateListOf<SlideRequest>()

    /**
     * Slide [index] of [p] with every build done, drawn as a finished picture ([SlideRender]) at [width] ×
     * [height] once its art has arrived — or null if the window could not draw it within [timeoutMs].
     */
    suspend fun picture(p: Presentation, index: Int, width: Int = 960, height: Int = 540, timeoutMs: Long = 20_000): ImageBitmap? {
        val r = SlideRequest(p, index, width, height)
        requests += r
        return try {
            withTimeoutOrNull(timeoutMs) { r.done.await() }
        } finally {
            requests.remove(r)
        }
    }
}

/**
 * Present ▾'s entries for where the slides go (the desk only: a phone or tablet has one screen and no
 * window to give them): the slides in a window of their own, for a screen recorder.
 */
internal fun presentOutputEntries(h: NeueHolders): List<MenuEntry> {
    if (Platform.os == DesktopOs.ANDROID) return emptyList()
    val output = h.present.output
    return listOf(
        MenuEntry(
            if (output.slidesWindow) "Slides in a window: on" else "Slides in a window: off",
            hint = "For OBS: record that window; your notes stay here",
        ) { output.slidesWindow = !output.slidesWindow },
    )
}

internal class SlideRequest(val presentation: Presentation, val index: Int, val width: Int, val height: Int) {
    val done = CompletableDeferred<ImageBitmap?>()
}

/**
 * Draws the slides [PresentOutput.picture] asks for, one at a time and never on screen: placed in the window
 * for good (it composes nothing while nothing is asked), since a picture can only be drawn by a window's
 * frame clock — on the desk and the tablet alike.
 */
@Composable
internal fun SlideRenderHost(h: NeueHolders) {
    val output = h.present.output
    val r = output.requests.firstOrNull() ?: return
    key(r) {
        val ctx = rememberSlideContext(h, r.presentation)
        val show = remember(r) { CompiledShow(r.presentation) }
        val shot = rememberSlideShot()
        val last = (show.builds.getOrNull(r.index)?.count ?: 1) - 1
        LaunchedEffect(r) {
            val picture = runCatching { shot.capture(timeoutMs = 10_000) }.getOrNull()
            r.done.complete(picture)
            output.requests.remove(r)
        }
        // A box of no size: the slide is laid out at its own pixel size round it and drawn only into the shot.
        Box(Modifier.size(0.dp)) {
            SlideRender(shot, ctx, show, Cursor(r.index, last), r.width, r.height)
        }
    }
}
