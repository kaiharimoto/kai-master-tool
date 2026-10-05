package com.kaiharimoto.neue.present.paint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.neue.cards.ArtWaits
import com.kaiharimoto.neue.cards.LocalArtWaits

/**
 * A slide drawn offscreen as a finished picture (1.1.x, the audit's I2 and M9): the one way the app turns a
 * slide into pixels for anything but the screen — the export's PDF pages, pictures and thumbnail, Ai's look at
 * a slide (`present_view`), and the recording's frames once there is a recorder.
 *
 * [SlideRender] draws one moment of a show ([at], [ms] into its builds) at an exact pixel size into this
 * shot's graphics layer and never onto the screen; [capture] waits for what the slide shows to arrive — every
 * card's art ([ArtWaits]) and every picture — then for the frame to settle, and catches it. It waits by the
 * frame clock, never the wall clock, so the headless studio (whose clock is turned by hand) and a window both
 * work, and it gives up after [capture]'s time limit rather than wait for ever on a download.
 */
@Stable
class SlideShot internal constructor(internal val layer: GraphicsLayer) {
    /** The cards still waiting on their art, provided to the slide being drawn. */
    val waits = ArtWaits()

    /** Frames drawn into the layer so far: [capture] counts them, and a preview redraws on them. */
    private val frames = mutableLongStateOf(0L)
    internal var drawn: Long
        get() = frames.longValue
        set(v) { frames.longValue = v }

    /** Whether every picture the slide shows is decoded: set by [SlideRender] for the slide in it. */
    internal var picturesReady: () -> Boolean = { true }

    /**
     * The slide as drawn, once nothing it shows is still arriving and the frame has held still for
     * [settleMs] (name masks and late decodes), or once [timeoutMs] has passed. Call it from the
     * composition's own coroutine (a `LaunchedEffect`): it counts frames.
     */
    suspend fun capture(timeoutMs: Long = 10_000, settleMs: Long = 300): ImageBitmap {
        val start = withFrameMillis { it }
        val first = drawn
        var readySince = -1L
        while (true) {
            val now = withFrameMillis { it }
            val ready = drawn > first && waits.pending == 0 && picturesReady()
            if (ready) {
                if (readySince < 0) readySince = now
                if (now - readySince >= settleMs) break
            } else {
                readySince = -1L
            }
            if (now - start >= timeoutMs) break
        }
        // One more frame so what was decoded last is in the layer.
        withFrameMillis { }
        return layer.toImageBitmap()
    }
}

@Composable
fun rememberSlideShot(): SlideShot {
    val layer = rememberGraphicsLayer()
    return remember(layer) { SlideShot(layer) }
}

/**
 * Slide [at] of [show], [ms] into its builds (every build done by default), drawn as a finished picture
 * ([SlideView]'s `final`: no empty camera panel, no guides) at [widthPx] × [heightPx] into [shot] — offscreen.
 * [camera] fills the camera zone when there is a real picture for it (a recording's frame).
 */
@Composable
fun SlideRender(
    shot: SlideShot,
    ctx: SlideContext,
    show: CompiledShow,
    at: Cursor,
    widthPx: Int,
    heightPx: Int,
    ms: Long = Long.MAX_VALUE / 4,
    camera: (@Composable () -> Unit)? = null,
) {
    val index = at.slide
    val slide = show.slides.getOrNull(index) ?: return
    val density = LocalDensity.current
    val pictures = remember(slide) { picturesOf(slide) }
    shot.picturesReady = { pictures.all { ctx.bitmap(it) != null } }
    Box(
        Modifier
            .requiredSize(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .drawWithContent {
                // Recorded, never drawn here: a preview draws the layer where it wants it ([SlidePreview]).
                shot.layer.record { this@drawWithContent.drawContent() }
                // Counted without being read here, or the count would redraw the slide for ever.
                Snapshot.withoutReadObservation { shot.drawn++ }
            },
    ) {
        CompositionLocalProvider(LocalArtWaits provides shot.waits) {
            SlideView(
                ctx, slide, show.zone(index), show.stage(index), Modifier.fillMaxSize(),
                deck = if (slide.deck != null) ({ show.deckFrame(index) }) else null,
                deckKeys = show.deckFrame(index)?.cards?.map { it.key }.orEmpty(),
                state = { e -> show.state(at, e, ms) },
                camera = camera,
                final = true,
            )
        }
    }
}

/** What [shot] last caught in its layer, scaled to fit this box: a preview that never overflows the window. */
@Composable
fun SlidePreview(shot: SlideShot, widthPx: Int, heightPx: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            val k = minOf(size.width / widthPx, size.height / heightPx)
            scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                // Read every frame the render draws, so the preview follows it.
                shot.drawn
                drawLayer(shot.layer)
            }
        },
    )
}

/** Every picture [slide] draws: its background's and its picture elements'. */
fun picturesOf(slide: Slide): List<String> = listOfNotNull(slide.background?.media) +
    slide.elements.filter { it.type == Element.IMAGE }.mapNotNull { it.media }
