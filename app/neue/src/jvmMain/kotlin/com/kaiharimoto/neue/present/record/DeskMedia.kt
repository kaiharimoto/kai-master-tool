package com.kaiharimoto.neue.present.record

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kaiharimoto.mastertool.core.present.record.SyntheticCamera
import com.kaiharimoto.mastertool.core.present.record.Wav
import com.kaiharimoto.neue.platform.LiveCamera
import com.kaiharimoto.neue.platform.LiveMic
import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

/**
 * FFmpeg's own words, caught (1.1.13): DirectShow and AVFoundation list their cameras only by printing them, so a
 * listing is read off what FFmpeg says. One callback for the app's lifetime (FFmpeg keeps a pointer to it; a callback
 * collected by the JVM would be a crash), quiet unless a listing is being caught or FFmpeg reports an error.
 */
internal object FfmpegLog {
    @Volatile private var sink: StringBuilder? = null
    private var installed = false

    private val callback = object : LogCallback() {
        override fun call(level: Int, msg: BytePointer?) {
            val text = runCatching { msg?.getString(Charsets.UTF_8) }.getOrNull() ?: return
            val s = sink
            if (s != null) synchronized(s) { s.append(text) } else if (level <= avutil.AV_LOG_ERROR) System.err.print("[ffmpeg] $text")
        }
    }

    @Synchronized
    fun install() {
        if (installed) return
        avutil.setLogCallback(callback)
        installed = true
    }

    /** What FFmpeg printed while [block] ran, at the info level. */
    @Synchronized
    fun <T> capture(block: () -> T): Pair<T?, String> {
        install()
        val buffer = StringBuilder()
        val level = avutil.av_log_get_level()
        sink = buffer
        avutil.av_log_set_level(avutil.AV_LOG_INFO)
        val result = try {
            runCatching(block).getOrNull()
        } finally {
            avutil.av_log_set_level(level)
            sink = null
        }
        return result to synchronized(buffer) { buffer.toString() }
    }
}

/** One picture from a camera, as B, G, R, A bytes with [stride] bytes a row. */
internal class Bgra(val width: Int, val height: Int, val stride: Int, val bytes: ByteArray) {
    /** As a picture the window draws. */
    fun bitmap(): ImageBitmap =
        Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, stride).toComposeImageBitmap()
}

/** A [Bgra] read out of a JavaCV frame grabbed as BGRA (the frame's buffer is the grabber's, so it is copied). */
internal fun Frame.bgra(): Bgra? {
    val buffer = image?.firstOrNull() as? ByteBuffer ?: return null
    val stride = imageStride.takeIf { it > 0 } ?: (imageWidth * 4)
    val bytes = ByteArray(stride * imageHeight)
    val view = buffer.duplicate()
    view.position(0)
    view.get(bytes, 0, minOf(bytes.size, view.remaining()))
    return Bgra(imageWidth, imageHeight, stride, bytes)
}

/**
 * A camera's frames written into a Matroska file as MJPEG (1.1.13): cheap to make while the person talks, and read
 * back frame by frame when the take is rendered. Each frame is placed at its moment on the take's clock — a frame
 * the camera was late with stands where it arrived, and two in one slot keep the first.
 */
internal class CameraWriter(file: File, private val width: Int, private val height: Int) {
    private val recorder = FFmpegFrameRecorder(file, width, height, 0).apply {
        format = "matroska"
        videoCodec = avcodec.AV_CODEC_ID_MJPEG
        pixelFormat = avutil.AV_PIX_FMT_YUVJ420P
        frameRate = FPS.toDouble()
        // MJPEG's quality scale, 2 the best: 3 keeps a face sharp at a third of the size.
        videoQuality = 3.0
    }
    private var last = -1L

    fun start() = recorder.start()

    @Synchronized
    fun write(frame: Bgra, atMs: Long) {
        val slot = (atMs * FPS + 500) / 1000
        if (slot <= last) return
        last = slot
        recorder.frameNumber = slot.toInt()
        recorder.recordImage(frame.width, frame.height, Frame.DEPTH_UBYTE, 4, frame.stride, avutil.AV_PIX_FMT_BGRA, ByteBuffer.wrap(frame.bytes))
    }

    @Synchronized
    fun close() {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
    }

    companion object {
        const val FPS = 30
    }
}

/**
 * A camera on the desk (1.1.13): a thread reading [next] — FFmpeg's device input, or the synthetic camera — keeping
 * the newest picture for the preview and, while a take records, writing every frame into its file.
 */
internal class DeskCamera(
    override val name: String,
    private val next: () -> Bgra?,
    private val release: () -> Unit,
) : LiveCamera {
    private val pictureState = mutableStateOf<ImageBitmap?>(null)
    private val errorState = mutableStateOf<String?>(null)
    override val picture: State<ImageBitmap?> get() = pictureState
    override val error: State<String?> get() = errorState

    @Volatile private var running = true
    @Volatile private var writer: CameraWriter? = null
    @Volatile private var clock: () -> Long = { 0L }
    @Volatile private var paused: () -> Boolean = { true }
    @Volatile private var size: Pair<Int, Int>? = null
    private val lock = Object()

    private val thread = Thread({ loop() }, "neue-camera").apply { isDaemon = true; start() }

    private fun loop() {
        var failures = 0
        while (running) {
            val f = try {
                next()
            } catch (e: Throwable) {
                failures++
                if (failures > 30) {
                    errorState.value = "The camera stopped: ${e.message ?: e::class.simpleName}"
                    break
                }
                Thread.sleep(30)
                null
            } ?: continue
            failures = 0
            size = f.width to f.height
            runCatching { pictureState.value = f.bitmap() }
            synchronized(lock) {
                val w = writer
                if (w != null && !paused()) runCatching { w.write(f, clock()) }.onFailure { errorState.value = "The camera's file: ${it.message}" }
            }
        }
    }

    override fun startRecording(file: File, clockMs: () -> Long, paused: () -> Boolean): Boolean {
        // The first frame says the camera's size; wait a moment for it.
        val deadline = System.currentTimeMillis() + 3_000
        while (size == null && System.currentTimeMillis() < deadline && running) Thread.sleep(20)
        val (w, h) = size ?: return false
        return synchronized(lock) {
            runCatching {
                val cw = CameraWriter(file, w, h)
                cw.start()
                clock = clockMs
                this.paused = paused
                writer = cw
                true
            }.getOrElse {
                errorState.value = "The camera's file could not be begun: ${it.message}"
                false
            }
        }
    }

    override fun stopRecording() {
        val w = synchronized(lock) { writer.also { writer = null } }
        w?.close()
    }

    override fun close() {
        stopRecording()
        running = false
        thread.join(2_000)
        runCatching { release() }
    }
}

/** FFmpeg's camera input for this system, and how it names a device. */
internal object CameraInput {
    fun format(os: String): String = when (os) {
        "WINDOWS" -> "dshow"
        "MAC" -> "avfoundation"
        else -> "video4linux2"
    }

    /**
     * [input] opened, trying 1280 × 720 at 30 a second, then the camera's own size at 30, then whatever it gives —
     * a camera refuses a size or a rate it does not have, and AVFoundation refuses any rate not exactly its own.
     */
    fun open(os: String, input: String): FFmpegFrameGrabber? {
        val tries = listOf(Triple(1280, 720, 30.0), Triple(0, 0, 30.0), Triple(0, 0, 0.0))
        var last: Throwable? = null
        for ((w, h, rate) in tries) {
            val g = FFmpegFrameGrabber(input)
            g.format = format(os)
            if (w > 0) { g.imageWidth = w; g.imageHeight = h }
            if (rate > 0) g.frameRate = rate
            g.pixelFormat = avutil.AV_PIX_FMT_BGRA
            // A camera that does not answer is not waited on for ever.
            g.setOption("rw_timeout", "5000000")
            try {
                g.start()
                return g
            } catch (e: Throwable) {
                last = e
                runCatching { g.release() }
            }
        }
        lastError = last?.message
        return null
    }

    @Volatile var lastError: String? = null
}

/**
 * The synthetic camera's frames at 30 a second, paced by the clock: what a machine with no camera records (the test,
 * the studio, `-Dneue.camera=synthetic`).
 */
internal class SyntheticFrames {
    private val started = System.nanoTime()
    private var n = 0

    fun next(): Bgra {
        val due = started + n * 1_000_000_000L / CameraWriter.FPS
        val wait = due - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        val px = SyntheticCamera.frame(n++)
        return Bgra(SyntheticCamera.WIDTH, SyntheticCamera.HEIGHT, SyntheticCamera.WIDTH * 4, SyntheticCamera.bgra(px))
    }
}

/**
 * A take's sound written as a WAV (1.1.13): 16-bit PCM behind a header whose lengths go in when it is closed. A take
 * cut short by a crash still reads ([Wav.read] counts to the file's end).
 */
internal class WavWriter(file: File, private val rate: Int, private val channels: Int) {
    private val out = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(Wav.header(rate, channels, 0))
    }
    private var bytes = 0L

    @Synchronized
    fun write(data: ByteArray, length: Int) {
        out.write(data, 0, length)
        bytes += length
    }

    /** [ms] of silence, for a microphone that is not there (the synthetic one). */
    @Synchronized
    fun silence(frames: Long) {
        val chunk = ByteArray(4096)
        var left = frames * 2 * channels
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            out.write(chunk, 0, n)
            bytes += n
            left -= n
        }
    }

    @Synchronized
    fun close() {
        runCatching {
            out.seek(0)
            out.write(Wav.header(rate, channels, bytes))
        }
        runCatching { out.close() }
    }
}

/**
 * A microphone on the desk through Java Sound (1.1.13), as the voice feature opens it: a thread reading the line,
 * its level for the meter, and while a take records its sound into a WAV — nothing while paused, so the sound keeps
 * the take's clock. [line] null is the silent microphone (`-Dneue.camera=synthetic`): silence at the clock's pace.
 */
internal class DeskMic(override val name: String, private val line: TargetDataLine?, private val format: AudioFormat) : LiveMic {
    private val levelState = mutableFloatStateOf(0f)
    override val level: State<Float> get() = levelState

    @Volatile private var running = true
    @Volatile private var writer: WavWriter? = null
    @Volatile private var paused: () -> Boolean = { true }
    private val lock = Object()

    private val thread = Thread({ loop() }, "neue-microphone").apply { isDaemon = true; start() }

    private fun loop() {
        val rate = format.sampleRate.toInt()
        if (line == null) {
            var last = System.nanoTime()
            while (running) {
                Thread.sleep(20)
                val now = System.nanoTime()
                val frames = (now - last) * rate / 1_000_000_000L
                last += frames * 1_000_000_000L / rate
                synchronized(lock) { writer?.takeIf { !paused() }?.silence(frames) }
            }
            return
        }
        val buffer = ByteArray((rate / 25) * format.frameSize)
        val line = line ?: return
        line.start()
        while (running) {
            val n = line.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            levelState.floatValue = rms(buffer, n)
            synchronized(lock) { writer?.takeIf { !paused() }?.write(buffer, n) }
        }
        runCatching { line.stop() }
    }

    private fun rms(b: ByteArray, n: Int): Float {
        val s = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        var count = 0
        while (s.hasRemaining()) { val v = s.get() / 32768.0; sum += v * v; count++ }
        // A voice at a normal distance sits round 0.05–0.2 RMS: stretched so it fills the meter.
        return if (count == 0) 0f else (sqrt(sum / count) * 4.0).toFloat().coerceIn(0f, 1f)
    }

    override fun startRecording(file: File, paused: () -> Boolean): Boolean = synchronized(lock) {
        runCatching {
            // What the line held from before the take is not the take's.
            line?.flush()
            this.paused = paused
            writer = WavWriter(file, format.sampleRate.toInt(), format.channels)
            true
        }.getOrDefault(false)
    }

    override fun stopRecording() {
        val w = synchronized(lock) { writer.also { writer = null } }
        w?.close()
    }

    override fun close() {
        stopRecording()
        running = false
        runCatching { line?.stop() }
        runCatching { line?.close() }
        thread.join(1_000)
    }

    companion object {
        /** 48 kHz first (the video's own rate), then 44.1 kHz: mono, 16-bit. */
        val FORMATS = listOf(AudioFormat(48_000f, 16, 1, true, false), AudioFormat(44_100f, 16, 1, true, false))

        /** The microphones Java Sound offers, by their mixers' names: those that can record one of [FORMATS]. */
        fun names(): List<String> = runCatching {
            AudioSystem.getMixerInfo().filter { info ->
                val mixer = AudioSystem.getMixer(info)
                FORMATS.any { f -> runCatching { mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, f)) }.getOrDefault(false) }
            }.map { it.name }.distinct()
        }.getOrDefault(emptyList())

        /** [name]'s microphone (else the system's default) opened, or null. */
        fun open(name: String?): DeskMic? {
            val mixerInfo = name?.let { n -> AudioSystem.getMixerInfo().firstOrNull { it.name == n } }
            for (f in FORMATS) {
                val info = DataLine.Info(TargetDataLine::class.java, f)
                val line = runCatching {
                    (if (mixerInfo != null) AudioSystem.getMixer(mixerInfo).getLine(info) else AudioSystem.getLine(info)) as TargetDataLine
                }.getOrNull() ?: continue
                if (runCatching { line.open(f) }.isFailure) continue
                return DeskMic(mixerInfo?.name ?: "The system's microphone", line, f)
            }
            return null
        }

        fun silent(): DeskMic = DeskMic("Silence", null, FORMATS.first())
    }
}
