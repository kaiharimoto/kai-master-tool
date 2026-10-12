package com.kaiharimoto.neue.present.record

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.async.threads
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.mastertool.core.present.record.CameraFrames
import com.kaiharimoto.mastertool.core.present.record.EncoderPick
import com.kaiharimoto.mastertool.core.present.record.RenderPlan
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.mastertool.core.present.record.TakeEvent
import com.kaiharimoto.mastertool.core.present.record.WavReader
import com.kaiharimoto.neue.cards.ArtWaits
import com.kaiharimoto.neue.cards.LocalArtWaits
import com.kaiharimoto.neue.platform.RenderProgress
import com.kaiharimoto.neue.platform.RenderRequest
import com.kaiharimoto.neue.platform.RenderResult
import com.kaiharimoto.neue.present.Playing
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.picturesOf
import com.kaiharimoto.neue.present.play.StageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors

/**
 * One render's drawing (I.9): the presenter's state the take's events set, what it is drawn with, the video's size, and
 * the frame asked for against the frame last drawn — [TakeRenderHost] draws in the window what this holds, and the
 * render waits for each frame to be drawn before it reads it back.
 */
internal class RenderFrame(
    val pl: Playing,
    val ctx: SlideContext,
    val width: Int,
    val height: Int,
    val camera: (@Composable () -> Unit)?,
) {
    /** The cards still waiting on their art, provided to the drawing. */
    val waits = ArtWaits()

    /** The frame asked for: bumped with each, and read in the host's draw, so each is drawn. */
    var asked by mutableIntStateOf(0)

    /** The frame last drawn into [layer]. */
    val drawn = MutableStateFlow(-1)

    /** The layer the host records into, while it is in the window. */
    @Volatile var layer: GraphicsLayer? = null
}

/**
 * Where a take is drawn on a phone or tablet (I.9), placed in the window for good by `TakesHost` and composing nothing
 * while nothing renders. Android has no scene off a window (the desk's `ImageComposeScene`), so the render draws here,
 * out of sight, as the deck picture does (`DeckShots.Host`): the stage laid out at the video's exact pixel size at a
 * density of one — the desk's scene's — under the window's own theme, fonts and art, recorded into a layer and never onto
 * the screen. Each frame the render asks for invalidates the drawing, and the frame drawn is said back.
 */
@Composable
internal fun TakeRenderHost() {
    val frame = PhoneTakeRenderer.current ?: return
    key(frame) {
        val layer = rememberGraphicsLayer()
        DisposableEffect(layer) {
            frame.layer = layer
            onDispose { if (frame.layer === layer) frame.layer = null }
        }
        Box(
            Modifier.layout { m, _ ->
                val p = m.measure(Constraints.fixed(frame.width, frame.height))
                layout(0, 0) { p.place(0, 0) }
            },
        ) {
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    // Read here, so a frame asked for draws again; said back once it is in the layer.
                    val asked = frame.asked
                    frame.layer = layer
                    layer.record { this@drawWithContent.drawContent() }
                    frame.drawn.value = asked
                },
            ) {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalArtWaits provides frame.waits) {
                    StageView(frame.pl, frame.ctx, frame.camera, final = true)
                }
            }
        }
    }
}

/**
 * A take rendered into a video on a phone or tablet (I.9, `docs/present/RECORDING.md`): each frame's presenter state set
 * from the take's events ([RenderPlan]) — the desk's renderer's own arithmetic — the camera's picture for that moment put
 * in its zone, the frame drawn by the slide painter in the window ([TakeRenderHost]), read back from its layer, and handed
 * to the device's H.264 encoder with the sound ([PhoneEncoder]) on a thread of its own while the next frame draws.
 * Cancelling it stops it and leaves no half a file.
 */
internal object PhoneTakeRenderer {
    /** The frame clock's start: never 0, which [Playing] reads as "not yet". */
    private const val BASE = 1_000_000_000L

    /** How long one slide's art is waited for before the render goes on without it. */
    private const val PER_SLIDE_MS = 10_000L

    /** The preview's width in the Takes dialog, in pixels. */
    private const val PREVIEW_W = 320

    /** The drawing asked of the window now; null while nothing renders. */
    var current by mutableStateOf<RenderFrame?>(null)
        private set

    suspend fun render(request: RenderRequest, ctx: SlideContext, progress: (RenderProgress) -> Unit): RenderResult {
        val pool = Executors.newSingleThreadExecutor(Background.threads("a take's encoder"))
        val encoding = pool.asCoroutineDispatcher()
        return try {
            withContext(Dispatchers.Main) { renderHere(request, ctx, progress, encoding) }
        } catch (e: CancellationException) {
            RenderResult.Cancelled
        } catch (e: Throwable) {
            RenderResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            encoding.close()
        }
    }

    /** The picture last made, for the dialog: read and written from the drawing's thread and the encoder's. */
    private class Shown {
        @Volatile var preview: ImageBitmap? = null
    }

    private suspend fun renderHere(
        request: RenderRequest,
        ctx: SlideContext,
        progress: (RenderProgress) -> Unit,
        encoding: CoroutineDispatcher,
    ): RenderResult {
        val take = request.take
        val fps = request.fps
        val (w, h) = withContext(Dispatchers.Default) { PhoneEncoder.size(request.width, request.height) }
        val show = CompiledShow(request.presentation)
        val pl = Playing(show, Cursor(0))
        val cameraPicture = mutableStateOf<ImageBitmap?>(null)
        val total = RenderPlan.frames(take, fps)
        val shown = Shown()
        var frames: CameraFrames.Reader? = null
        var sound: WavReader? = null
        var encoder: PhoneEncoder? = null
        try {
            frames = withContext(Dispatchers.IO) { cameraFile(request)?.let { CameraFrames.Reader(it) } }
            val camera: (@Composable () -> Unit)? = if (frames != null) ({ CameraPicture({ cameraPicture.value }, take.mirror) }) else null
            val frame = RenderFrame(pl, ctx, w, h, camera)
            current = frame
            warm(frame, show, take, ctx, progress, total)
            sound = withContext(Dispatchers.IO) { take.audio?.let { File(request.dir, it) }?.takeIf { it.isFile }?.let { WavReader.open(it) } }
            val enc = withContext(encoding) {
                PhoneEncoder.open(File(request.dir, "rendering.mp4"), w, h, fps, sound?.rate ?: 0, sound?.channels ?: 0)
            }
            encoder = enc
            val track = frames?.let { CameraFrames.Track(it) }
            val argb = IntArray(w * h)
            val stage = "Drawing and encoding · ${EncoderPick.describe(EncoderPick.MEDIACODEC)}"
            coroutineScope {
                var pending: Deferred<Unit>? = null
                for (f in RenderPlan.walk(take, fps)) {
                    currentCoroutineContext().ensureActive()
                    val picture = track?.advanceTo(f.atMs)?.let { jpeg -> withContext(Dispatchers.Default) { decode(jpeg) } }
                    val asked = frame.asked + 1
                    Snapshot.withMutableSnapshot {
                        set(pl, f)
                        if (picture != null) cameraPicture.value = picture
                        frame.asked = asked
                    }
                    drawn(frame, asked) { progress(RenderProgress(f.n, total, shown.preview, "Waiting for Neue to be on screen: a phone draws the video in its window")) }
                    val layer = frame.layer ?: error("the drawing left the window")
                    val shot = layer.toImageBitmap().asAndroidBitmap()
                    // The frame before is encoded while this one drew; this one is encoded while the next draws.
                    pending?.await()
                    val s = sound
                    pending = async(encoding) {
                        encode(enc, shot, argb, f.n)
                        s?.let { enc.sound(it.take(RenderPlan.samplesThrough(f.n, fps, it.rate))) }
                        if (f.n % 15 == 0) shown.preview = previewOf(enc, argb)
                        if (f.n % 15 == 0 || f.n == total - 1) progress(RenderProgress(f.n + 1, total, shown.preview, stage))
                    }
                }
                pending?.await()
            }
            withContext(encoding) { enc.finish() }
            encoder = null
            return RenderResult.Done(enc.file, EncoderPick.MEDIACODEC)
        } catch (e: Throwable) {
            encoder?.let { enc -> withContext(NonCancellable + encoding) { enc.abandon() } }
            throw e
        } finally {
            current = null
            frames?.let { runCatching { it.close() } }
            sound?.close()
        }
    }

    /** The take's camera file when it is one a phone wrote ([CameraFrames]); a desk's Matroska is not read here. */
    private fun cameraFile(request: RenderRequest): File? =
        request.take.camera?.let { File(request.dir, it) }?.takeIf { it.isFile && it.length() > 0 && CameraFrames.isFrames(it) }

    private fun decode(jpeg: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()

    /** The frame [asked] drawn into the layer; told [waiting] when the window has not drawn for a while (out of sight). */
    private suspend fun drawn(frame: RenderFrame, asked: Int, waiting: () -> Unit) {
        if (withTimeoutOrNull(3_000) { frame.drawn.first { it >= asked } } != null) return
        waiting()
        frame.drawn.first { it >= asked }
    }

    /** [shot], read back from the GPU, into the encoder as frame [n]; the bitmaps let go of at once (a 1080p frame is 8 MB). */
    private fun encode(enc: PhoneEncoder, shot: Bitmap, argb: IntArray, n: Int) {
        val soft = if (shot.config == Bitmap.Config.HARDWARE) {
            shot.copy(Bitmap.Config.ARGB_8888, false) ?: run {
                shot.recycle()
                error("frame $n could not be read back from the GPU")
            }
        } else {
            shot
        }
        val sized = if (soft.width != enc.width || soft.height != enc.height) Bitmap.createScaledBitmap(soft, enc.width, enc.height, true) else soft
        try {
            sized.getPixels(argb, 0, enc.width, 0, 0, enc.width, enc.height)
        } finally {
            if (sized !== soft) sized.recycle()
            if (soft !== shot) soft.recycle()
            shot.recycle()
        }
        enc.picture(argb, n)
    }

    /** A small picture of the frame just encoded, for the Takes dialog. */
    private fun previewOf(enc: PhoneEncoder, argb: IntArray): ImageBitmap {
        val full = Bitmap.createBitmap(argb, enc.width, enc.height, Bitmap.Config.ARGB_8888)
        val small = Bitmap.createScaledBitmap(full, PREVIEW_W, (PREVIEW_W * enc.height / enc.width).coerceAtLeast(1), true)
        if (small !== full) full.recycle()
        return small.asImageBitmap()
    }

    /** [pl] set to frame [f]: what the presenter showed at that moment of the take (the desk's `TakeRenderer.set`). */
    private fun set(pl: Playing, f: RenderPlan.Frame) {
        val s = f.state
        pl.cursor = s.cursor
        pl.from = s.from
        pl.backward = s.back
        pl.now = BASE + f.atMs * 1_000_000L
        // The slide the take began on is already in: its builds were run before recording (or in the count-in).
        pl.since = if (s.from == null && s.since == 0L) 0L else BASE + s.since * 1_000_000L
        pl.overview = s.overview
        pl.overviewSince = if (!s.overview && s.overviewSince == 0L) 0L else BASE + s.overviewSince * 1_000_000L
        pl.blank = s.blank
        pl.laser = s.laser != null
        pl.laserAt = s.laser?.let { (x, y) -> Offset(x, y) }
        pl.ink = s.ink
    }

    /**
     * Each slide the take shows drawn as it ends, until nothing on it waits for its art and its pictures are decoded — at
     * most [PER_SLIDE_MS] a slide — so the video never has a blank card the window would have shown (the desk's `warm`).
     */
    private suspend fun warm(
        frame: RenderFrame,
        show: CompiledShow,
        take: Take,
        ctx: SlideContext,
        progress: (RenderProgress) -> Unit,
        total: Int,
    ) {
        val pl = frame.pl
        val slides = (listOf(0) + take.events.filter { it.kind == TakeEvent.GO }.map { it.slide }).distinct()
            .filter { it in show.slides.indices }
        var t = 0L
        for ((i, index) in slides.withIndex()) {
            progress(RenderProgress(0, total, null, "Getting the art ready · slide ${i + 1} of ${slides.size}"))
            val last = (show.builds.getOrNull(index)?.count ?: 1) - 1
            val pictures = picturesOf(show.slides[index])
            val start = SystemClock.uptimeMillis()
            var ready = 0
            while (SystemClock.uptimeMillis() - start < PER_SLIDE_MS) {
                currentCoroutineContext().ensureActive()
                val asked = frame.asked + 1
                Snapshot.withMutableSnapshot {
                    pl.cursor = Cursor(index, last)
                    pl.from = null
                    pl.since = 0L
                    pl.overview = false
                    pl.overviewSince = 0L
                    // Every build of the slide done: its cards all drawn, so all asked for.
                    pl.now = BASE + PER_SLIDE_MS * 1_000_000L + t * 1_000_000L
                    frame.asked = asked
                }
                t += 33
                val left = PER_SLIDE_MS - (SystemClock.uptimeMillis() - start)
                withTimeoutOrNull(left.coerceAtLeast(1)) { frame.drawn.first { it >= asked } } ?: break
                ready = if (frame.waits.pending == 0 && pictures.all { ctx.bitmap(it) != null }) ready + 1 else 0
                if (ready >= 3) break
                delay(30)
            }
        }
    }
}
