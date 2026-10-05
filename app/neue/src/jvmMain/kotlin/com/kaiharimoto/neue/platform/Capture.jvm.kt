package com.kaiharimoto.neue.platform

import androidx.compose.runtime.CompositionLocalContext
import com.kaiharimoto.mastertool.core.present.record.CameraNames
import com.kaiharimoto.mastertool.core.present.record.EncoderPick
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.record.CameraInput
import com.kaiharimoto.neue.present.record.DeskCamera
import com.kaiharimoto.neue.present.record.DeskMic
import com.kaiharimoto.neue.present.record.FfmpegLog
import com.kaiharimoto.neue.present.record.SyntheticFrames
import com.kaiharimoto.neue.present.record.TakeRenderer
import com.kaiharimoto.neue.present.record.bgra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The desk's camera and microphone (1.1.13): FFmpeg's device input through JavaCV — DirectShow on Windows,
 * AVFoundation on a Mac, Video4Linux here — and the microphone through Java Sound, as the voice feature opens it.
 * `-Dneue.camera=synthetic` (or `NEUE_CAMERA=synthetic`) puts a synthetic camera and a silent microphone in their
 * place, for a machine with neither: the studio and the tests.
 */
actual object Capture {
    actual val canRecord: Boolean = true

    actual val whyNot: String? = null

    private val os: String get() = EncoderPick.osOf(System.getProperty("os.name").orEmpty())

    /** No camera here, so one made of arithmetic. */
    val synthetic: Boolean
        get() = System.getProperty("neue.camera") == "synthetic" || System.getenv("NEUE_CAMERA") == "synthetic"

    private const val SYNTHETIC = "Synthetic camera"

    @Volatile private var error: String? = null
    actual val lastError: String? get() = error

    private suspend fun devices(): List<CameraNames.Device> = withContext(Dispatchers.IO) {
        if (synthetic) return@withContext listOf(CameraNames.Device(SYNTHETIC, SYNTHETIC))
        when (os) {
            EncoderPick.LINUX -> {
                val nodes = File("/sys/class/video4linux").listFiles().orEmpty()
                    .filter { File("/dev/${it.name}").exists() }
                    .associate { it.name to (runCatching { File(it, "name").readText() }.getOrNull() ?: it.name) }
                CameraNames.video4linux(nodes)
            }
            else -> runCatching {
                val (_, log) = FfmpegLog.capture {
                    val g = FFmpegFrameGrabber(if (os == EncoderPick.WINDOWS) "dummy" else "")
                    g.format = CameraInput.format(os)
                    g.setOption("list_devices", "true")
                    try { g.start() } finally { runCatching { g.release() } }
                }
                if (os == EncoderPick.WINDOWS) CameraNames.dshow(log) else CameraNames.avfoundation(log)
            }.getOrElse { e ->
                error = "The cameras could not be listed: ${e.message ?: e::class.simpleName}"
                emptyList()
            }
        }
    }

    actual suspend fun cameras(): List<String> = devices().map { it.name }

    actual suspend fun microphones(): List<String> = withContext(Dispatchers.IO) {
        if (synthetic) listOf("Silence") else DeskMic.names()
    }

    actual suspend fun openCamera(name: String?): LiveCamera? = withContext(Dispatchers.IO) {
        val device = CameraNames.choose(devices(), name) ?: run {
            error = error ?: "No camera was found"
            return@withContext null
        }
        if (device.name == SYNTHETIC) {
            val frames = SyntheticFrames()
            return@withContext DeskCamera(SYNTHETIC, { frames.next() }, {})
        }
        val grabber = runCatching { CameraInput.open(os, device.input) }.getOrElse { e ->
            error = "${device.name} would not open: ${e.message ?: e::class.simpleName}"
            null
        } ?: run {
            error = error ?: "${device.name} would not open${CameraInput.lastError?.let { ": $it" } ?: ""}" +
                if (os == EncoderPick.MAC) ". Is it allowed in System Settings › Privacy & Security › Camera?" else ""
            return@withContext null
        }
        error = null
        DeskCamera(device.name, { grabber.grabImage()?.bgra() }, {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        })
    }

    actual suspend fun openMic(name: String?): LiveMic? = withContext(Dispatchers.IO) {
        if (synthetic) DeskMic.silent() else DeskMic.open(name)
    }
}

actual object TakeVideo {
    actual val canRender: Boolean = true

    actual suspend fun render(
        request: RenderRequest,
        ctx: SlideContext,
        locals: CompositionLocalContext?,
        progress: (RenderProgress) -> Unit,
    ): RenderResult = TakeRenderer.render(request, ctx, locals, progress)
}

/** [source] copied where the person chooses: a video is large, so it is streamed, never read whole. */
actual suspend fun deliverCopy(source: File, name: String, mime: String): String? {
    val extension = name.substringAfterLast('.', "")
    val target = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Save $name", FileDialog.SAVE).apply {
            file = name
            isVisible = true
        }
        val chosen = dialog.file ?: return@withContext null
        val named = if (extension.isNotEmpty() && !chosen.endsWith(".$extension", ignoreCase = true)) "$chosen.$extension" else chosen
        File(dialog.directory, named)
    } ?: return null
    val copied = withContext(Dispatchers.IO) { runCatching { source.copyTo(target, overwrite = true) }.isSuccess }
    return if (copied) "Saved ${target.name}" else "${target.name} could not be saved there"
}
