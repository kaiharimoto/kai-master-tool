package com.kaiharimoto.neue.present.record

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.mastertool.core.present.record.EncoderPick
import com.kaiharimoto.mastertool.core.present.record.RenderPlan
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.mastertool.core.present.record.TakeEvent
import com.kaiharimoto.mastertool.core.present.record.Wav
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * A take rendered into a video on the desk (1.1.13, the audit's track A4): every frame drawn offscreen in an
 * `ImageComposeScene` by [StageView] — the presenter's own drawing — with the presenter's state set from the take's
 * events ([RenderPlan]), the camera's recorded frame in its zone, then encoded by FFmpeg ([VideoEncoder]) with the
 * sound. It runs on a thread of its own, never the window's, and stops when its coroutine is cancelled, leaving no
 * half a file.
 */
object TakeRenderer {
    /** The frame clock's start: never 0, which [Playing] reads as "not yet". */
    private const val BASE = 1_000_000_000L

    suspend fun render(
        request: RenderRequest,
        ctx: SlideContext,
        locals: CompositionLocalContext?,
        progress: (RenderProgress) -> Unit,
        os: String = EncoderPick.osOf(System.getProperty("os.name").orEmpty()),
    ): RenderResult {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "neue-take-render").apply { isDaemon = true } }
        val thread = pool.asCoroutineDispatcher()
        return try {
            withContext(thread) { renderHere(request, ctx, locals, progress, os) }
        } catch (e: CancellationException) {
            RenderResult.Cancelled
        } catch (e: Throwable) {
            RenderResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            thread.close()
        }
    }

    private suspend fun renderHere(
        request: RenderRequest,
        ctx: SlideContext,
        locals: CompositionLocalContext?,
        progress: (RenderProgress) -> Unit,
        os: String,
    ): RenderResult {
        val take = request.take
        val fps = request.fps
        val w = request.width
        val h = request.height
        val show = CompiledShow(request.presentation)
        val pl = Playing(show, Cursor(0))
        val waits = ArtWaits()
        val cameraPicture = mutableStateOf<ImageBitmap?>(null)
        val cameraFile = take.camera?.let { File(request.dir, it) }?.takeIf { it.isFile && it.length() > 0 }
        val camera: (@Composable () -> Unit)? = if (cameraFile != null) ({ CameraPicture({ cameraPicture.value }, take.mirror) }) else null
        val scene = ImageComposeScene(w, h, Density(1f)) {
            val body: @Composable () -> Unit = {
                CompositionLocalProvider(LocalArtWaits provides waits) {
                    Box(Modifier.fillMaxSize()) { StageView(pl, ctx, camera, final = true) }
                }
            }
            if (locals != null) CompositionLocalProvider(locals) { body() } else body()
        }
        val total = RenderPlan.frames(take, fps)
        var out: File? = null
        var encoder: VideoEncoder? = null
        var grabber: FFmpegFrameGrabber? = null
        var sound: SoundReader? = null
        try {
            // The art first: every slide the take shows drawn once until its cards and pictures have arrived.
            warm(scene, pl, show, take, waits, ctx, progress, total)
            grabber = cameraFile?.let { f -> FFmpegFrameGrabber(f).apply { pixelFormat = avutil.AV_PIX_FMT_BGRA; start() } }
            sound = take.audio?.let { File(request.dir, it) }?.takeIf { it.isFile }?.let { SoundReader.open(it) }
            val (enc, codec) = VideoEncoder.open(request.dir, w, h, fps, sound?.channels ?: 0, os)
            encoder = enc
            out = enc.file
            val track = grabber?.let { CameraTrack(it) }
            val target = Bitmap().apply { allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
            var preview: ImageBitmap? = null
            for (f in RenderPlan.walk(take, fps)) {
                currentCoroutineContext().ensureActive()
                set(pl, f)
                track?.advanceTo(f.atMs)?.let { cameraPicture.value = it }
                Snapshot.sendApplyNotifications()
                val image = scene.render(BASE + f.atMs * 1_000_000L)
                image.readPixels(target, 0, 0)
                val bytes = target.readPixels(target.imageInfo, w * 4, 0, 0) ?: error("frame ${f.n} could not be read")
                enc.picture(bytes, w * 4)
                sound?.let { s -> enc.sound(s.take(RenderPlan.samplesThrough(f.n, fps, s.rate)), s) }
                val showPreview = f.n % 15 == 0
                if (showPreview) preview = image.toComposeImageBitmap() else image.close()
                if (showPreview || f.n == total - 1) progress(RenderProgress(f.n + 1, total, preview, "Drawing and encoding · ${EncoderPick.describe(codec)}"))
            }
            enc.finish()
            encoder = null
            return RenderResult.Done(enc.file, codec)
        } catch (e: Throwable) {
            encoder?.abandon()
            out?.delete()
            throw e
        } finally {
            runCatching { grabber?.stop(); grabber?.release() }
            sound?.close()
            scene.close()
        }
    }

    /** [pl] set to frame [f]: what the presenter showed at that moment of the take. */
    private fun set(pl: Playing, f: RenderPlan.Frame) {
        val s = f.state
        Snapshot.withMutableSnapshot {
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
    }

    /**
     * Each slide the take shows drawn as it ends, until nothing on it is waiting for its art and its pictures are
     * decoded — at most [PER_SLIDE_MS] a slide — so the video never has a blank card the window would have shown.
     */
    private suspend fun warm(
        scene: ImageComposeScene,
        pl: Playing,
        show: CompiledShow,
        take: Take,
        waits: ArtWaits,
        ctx: SlideContext,
        progress: (RenderProgress) -> Unit,
        total: Int,
    ) {
        val slides = (listOf(0) + take.events.filter { it.kind == TakeEvent.GO }.map { it.slide }).distinct()
            .filter { it in show.slides.indices }
        var t = 0L
        slides.forEachIndexed { i, index ->
            progress(RenderProgress(0, total, null, "Getting the art ready · slide ${i + 1} of ${slides.size}"))
            val last = (show.builds.getOrNull(index)?.count ?: 1) - 1
            val pictures = picturesOf(show.slides[index])
            Snapshot.withMutableSnapshot {
                pl.cursor = Cursor(index, last)
                pl.from = null
                pl.since = 0L
                pl.overview = false
                pl.overviewSince = 0L
            }
            val start = System.currentTimeMillis()
            var ready = 0
            while (System.currentTimeMillis() - start < PER_SLIDE_MS) {
                currentCoroutineContext().ensureActive()
                Snapshot.sendApplyNotifications()
                scene.render(BASE + t * 1_000_000L).close()
                t += 33
                ready = if (waits.pending == 0 && pictures.all { ctx.bitmap(it) != null }) ready + 1 else 0
                if (ready >= 3) break
                delay(30)
            }
        }
    }

    /** How long one slide's art is waited for before the render goes on without it. */
    private const val PER_SLIDE_MS = 10_000L
}

/** The recorded camera, read front to back as the video's frames ask for its pictures. */
private class CameraTrack(private val grabber: FFmpegFrameGrabber) {
    private var next: Bgra? = read()
    private var nextMs: Long = grabber.timestamp / 1000

    private fun read(): Bgra? = runCatching { grabber.grabImage()?.bgra() }.getOrNull()

    /** The newest camera picture at or before [atMs], when it is a new one; null when the picture stands. */
    fun advanceTo(atMs: Long): ImageBitmap? {
        var chosen: Bgra? = null
        while (RenderPlan.advanceCamera(next?.let { nextMs }, atMs)) {
            chosen = next
            next = read()
            nextMs = grabber.timestamp / 1000
        }
        return chosen?.bitmap()
    }
}

/** A take's WAV read in order, in the samples each frame takes. */
internal class SoundReader private constructor(private val file: RandomAccessFile, val rate: Int, val channels: Int, private val end: Long) {
    private var given = 0L

    /** The samples up to [through] (per channel) not handed out yet; silence past the file's end. */
    fun take(through: Long): ShortArray {
        val want = (through - given).coerceAtLeast(0)
        given += want
        val out = ShortArray((want * channels).toInt())
        val bytes = ByteArray(out.size * 2)
        val left = (end - file.filePointer).coerceAtLeast(0)
        val n = minOf(bytes.size.toLong(), left).toInt()
        if (n > 0) file.readFully(bytes, 0, n)
        ByteBuffer.wrap(bytes, 0, n - n % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, (n - n % 2) / 2)
        return out
    }

    fun close() = runCatching { file.close() }

    companion object {
        fun open(f: File): SoundReader? {
            val raf = RandomAccessFile(f, "r")
            val head = ByteArray(minOf(4096L, raf.length()).toInt())
            raf.readFully(head)
            val info = Wav.read(head, raf.length()) ?: run { raf.close(); return null }
            raf.seek(info.dataStart.toLong())
            return SoundReader(raf, info.rate, info.channels, info.dataStart + info.dataBytes)
        }
    }
}

/**
 * FFmpeg's encoder for a take's video (1.1.13): the machine's own H.264 where it opens, else VP9, in an MP4
 * ([EncoderPick]); BGRA pictures in, the sound resampled to 48 kHz.
 */
internal class VideoEncoder private constructor(private val recorder: FFmpegFrameRecorder, val file: File) {
    fun picture(bgra: ByteArray, stride: Int) {
        recorder.recordImage(recorder.imageWidth, recorder.imageHeight, Frame.DEPTH_UBYTE, 4, stride, avutil.AV_PIX_FMT_BGRA, ByteBuffer.wrap(bgra))
    }

    fun sound(samples: ShortArray, from: SoundReader) {
        if (samples.isEmpty()) return
        recorder.recordSamples(from.rate, from.channels, java.nio.ShortBuffer.wrap(samples))
    }

    fun finish() {
        recorder.stop()
        recorder.release()
    }

    fun abandon() {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        file.delete()
    }

    companion object {
        /** The first of [os]'s encoders that opens, writing into [dir]; the encoder and its name. */
        fun open(dir: File, width: Int, height: Int, fps: Int, audioChannels: Int, os: String): Pair<VideoEncoder, String> {
            val tried = ArrayList<String>()
            for (codec in EncoderPick.order(os)) {
                if (codec in EncoderPick.NEVER) continue
                val file = File(dir, "rendering.${EncoderPick.container(codec)}")
                file.delete()
                val r = FFmpegFrameRecorder(file, width, height, audioChannels)
                r.format = EncoderPick.container(codec)
                r.videoCodecName = codec
                r.pixelFormat = if (EncoderPick.pixelFormat(codec) == "nv12") avutil.AV_PIX_FMT_NV12 else avutil.AV_PIX_FMT_YUV420P
                r.frameRate = fps.toDouble()
                r.videoBitrate = EncoderPick.bitrate(width, height, fps)
                r.gopSize = fps * 2
                EncoderPick.options(codec).forEach { (k, v) -> r.setVideoOption(k, v) }
                if (audioChannels > 0) {
                    r.audioCodecName = EncoderPick.audioCodec(codec)
                    r.sampleRate = EncoderPick.AUDIO_RATE
                    r.audioBitrate = EncoderPick.AUDIO_BITRATE
                    r.audioChannels = audioChannels
                }
                try {
                    r.start()
                    return VideoEncoder(r, file) to codec
                } catch (e: Throwable) {
                    tried += "$codec (${e.message?.take(80)})"
                    runCatching { r.release() }
                    file.delete()
                }
            }
            error("No video encoder would open here: ${tried.joinToString("; ")}")
        }
    }
}
