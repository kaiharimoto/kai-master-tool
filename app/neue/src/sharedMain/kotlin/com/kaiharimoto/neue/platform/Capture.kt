package com.kaiharimoto.neue.platform

import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.ui.graphics.ImageBitmap
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.neue.present.paint.SlideContext
import java.io.File

/**
 * A camera opened (1.1.13, Present's recording): its newest picture for the live preview in the camera zone, and,
 * while a take records, every frame written to a file stamped on the take's own clock. Each platform's own: on the
 * desk FFmpeg's device input (DirectShow, AVFoundation, Video4Linux) through JavaCV; on a phone or tablet nothing yet
 * ([Capture.canRecord]).
 */
interface LiveCamera {
    /** The camera's name, as the system gives it. */
    val name: String

    /** The newest picture; null until the first arrives. Read it where it is drawn, so a frame redraws, never recomposes. */
    val picture: State<ImageBitmap?>

    /** What went wrong, in words, once something has; null while it works. */
    val error: State<String?>

    /**
     * Frames from now on written into [file] (Matroska, MJPEG), each stamped with [clockMs] — the take's clock — and
     * none while [paused] says so. False when the file could not be begun.
     */
    fun startRecording(file: File, clockMs: () -> Long, paused: () -> Boolean): Boolean

    /** The file finished and closed; returns once it is. */
    fun stopRecording()

    /** The camera let go of. */
    fun close()
}

/**
 * A microphone opened (1.1.13): how loud it is now, for a meter, and while a take records its sound written to a
 * WAV file — none of it while [startRecording]'s `paused` says so, so the sound keeps step with the take's clock.
 */
interface LiveMic {
    val name: String

    /** The level, 0–1, of what it hears now. */
    val level: State<Float>

    fun startRecording(file: File, paused: () -> Boolean): Boolean

    fun stopRecording()

    fun close()
}

/**
 * The camera and microphone for recording a take (1.1.13). The desk records; a phone or tablet does not yet — CameraX
 * and `MediaCodec` are the plan there (`docs/present/RECORDING.md`), and until then [canRecord] is false and
 * [whyNot] says so.
 */
expect object Capture {
    /** Whether a take can be recorded here. */
    val canRecord: Boolean

    /** Why not, in words, where it cannot; null where it can. */
    val whyNot: String?

    /** The cameras here, by name: the system's first first. */
    suspend fun cameras(): List<String>

    /** The microphones here, by name: the system's default first. */
    suspend fun microphones(): List<String>

    /** The camera named [name], else the first, opened — or null when there is none or it would not open ([lastError]). */
    suspend fun openCamera(name: String?): LiveCamera?

    /** The microphone named [name], else the default, opened — or null when there is none. */
    suspend fun openMic(name: String?): LiveMic?

    /** Why the last camera or microphone did not open, in words. */
    val lastError: String?
}

/** A take to render: its folder, the presentation frozen when it began, and the video's size and rate. */
class RenderRequest(
    val take: Take,
    val dir: File,
    val presentation: Presentation,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = take.fps,
)

/** How far a render is: frames done of the total, and now and then a picture of the frame just made. */
class RenderProgress(val done: Int, val total: Int, val preview: ImageBitmap?, val stage: String)

/** How a render ended. */
sealed interface RenderResult {
    /** The video, and the encoder that made it. */
    data class Done(val file: File, val codec: String) : RenderResult

    data class Failed(val reason: String) : RenderResult

    data object Cancelled : RenderResult
}

/**
 * Turning a take into a video (1.1.13): every frame drawn offscreen by the slide painter from the take's events —
 * transitions, builds, the whole deck, the laser and the pen as they were — with the camera's picture in its zone,
 * encoded by FFmpeg with the sound. The desk renders; a phone or tablet will through `MediaCodec`.
 */
expect object TakeVideo {
    val canRender: Boolean

    /**
     * [request] rendered into its folder, drawn with [ctx] (the frozen presentation's fonts, cards and pictures) under
     * the window's [locals]; [progress] told as it goes. Cancelling the call stops it and leaves no half a file.
     */
    suspend fun render(
        request: RenderRequest,
        ctx: SlideContext,
        locals: CompositionLocalContext?,
        progress: (RenderProgress) -> Unit,
    ): RenderResult
}

/**
 * [source] handed to the person under [name]: on the desk saved where they choose (a copy; videos are large, so never
 * read whole), on a phone or tablet shared. What to tell them, or null when they cancelled.
 */
expect suspend fun deliverCopy(source: File, name: String, mime: String): String?
