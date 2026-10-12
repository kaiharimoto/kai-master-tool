package com.kaiharimoto.neue.present.record

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Display
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.async.thread
import com.kaiharimoto.mastertool.core.async.threads
import com.kaiharimoto.mastertool.core.present.record.CameraFrames
import com.kaiharimoto.mastertool.core.present.record.SyntheticCamera
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.neue.platform.LiveCamera
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A camera on a phone or tablet (I.9): pictures handed in by CameraX ([CameraXCamera]) or made of arithmetic
 * ([SyntheticPhoneCamera]) — the newest kept for the live preview in the camera zone, and, while a take records, each
 * written as a JPEG into the take's frames file ([CameraFrames], [Take.CAMERA_FRAMES]) at its moment on the take's clock,
 * one a 1/30 s slot. A write that fails (a full disk) ends the camera's file and says so ([error]); the take carries on
 * with the slides and the sound.
 */
internal abstract class PhoneCamera(override val name: String) : LiveCamera {
    override val file: String get() = Take.CAMERA_FRAMES

    private val pictureState = mutableStateOf<ImageBitmap?>(null)
    protected val errorState = mutableStateOf<String?>(null)
    override val picture: State<ImageBitmap?> get() = pictureState
    override val error: State<String?> get() = errorState

    private val lock = Any()
    private var writer: CameraFrames.Writer? = null
    private var clock: () -> Long = { 0L }
    private var paused: () -> Boolean = { true }
    private val jpeg = Jpeg()

    /** The bitmaps pictures are copied into, in turn: the one shown is never the one being written (see [BitmapRing]). */
    protected val ring = BitmapRing()

    /**
     * A picture arrived, on the camera's own thread: shown, and written while a take records. [bitmap] is [ring]'s, so it
     * stands untouched while the next two pictures are made.
     */
    protected fun deliver(bitmap: Bitmap) {
        pictureState.value = bitmap.asImageBitmap()
        synchronized(lock) {
            val w = writer ?: return
            if (paused()) return
            val at = clock()
            // A picture whose slot is taken is never encoded.
            if (!w.wants(at)) return
            try {
                jpeg.reset()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg)) error("the picture would not encode")
                w.write(jpeg.bytes(), at, jpeg.size())
            } catch (e: Exception) {
                errorState.value = "The camera's file: ${e.message ?: e::class.simpleName}"
                writer = null
                runCatching { w.close() }
            }
        }
    }

    override fun startRecording(file: File, clockMs: () -> Long, paused: () -> Boolean): Boolean = synchronized(lock) {
        try {
            writer?.let { runCatching { it.close() } }
            clock = clockMs
            this.paused = paused
            writer = CameraFrames.Writer(file)
            true
        } catch (e: Exception) {
            errorState.value = "The camera's file could not be begun: ${e.message ?: e::class.simpleName}"
            false
        }
    }

    override fun stopRecording() {
        val w = synchronized(lock) { writer.also { writer = null } }
        w?.let { runCatching { it.close() } }
    }

    /** A byte stream whose buffer is read in place, so a picture is not copied once more to be written. */
    private class Jpeg : ByteArrayOutputStream(256 * 1024) {
        fun bytes(): ByteArray = buf
    }

    companion object {
        /** JPEG's quality for a take's camera: a face sharp in a 1080p video's zone at about 50 KB a picture. */
        const val QUALITY = 82

        /** The size asked of a camera: 1280 × 720, which fills any camera zone of a 1080p video, else the nearest. */
        val SIZE = Size(1280, 720)
    }
}

/**
 * Three bitmaps a camera's pictures are copied into in turn, kept while their size holds: a picture shown stands while
 * the next two are made — a frame and more at 30 a second — so the window never draws one half overwritten, and the
 * camera allocates nothing a picture (1280 × 720 is 3.7 MB, thirty times a second).
 */
internal class BitmapRing(private val size: Int = 3) {
    private val bitmaps = arrayOfNulls<Bitmap>(size)
    private var at = 0

    fun next(width: Int, height: Int): Bitmap {
        at = (at + 1) % size
        bitmaps[at]?.takeIf { it.width == width && it.height == height }?.let { return it }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmaps[at] = it }
    }
}

/**
 * A lifecycle of the camera's own (I.9): resumed while the camera is open, destroyed when it is let go of — so the
 * camera never follows the activity's, and a dialog over it or a turn of the screen does not close it.
 */
private class CameraLife : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        // A lifecycle never resumed cannot be destroyed (it goes from nothing to nothing).
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** [block] on the main thread, waited for (at most [ms]) when called from another. */
internal fun onMain(ms: Long = 3_000, block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
        return
    }
    val done = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post {
        try {
            block()
        } finally {
            done.countDown()
        }
    }
    done.await(ms, TimeUnit.MILLISECONDS)
}

/**
 * A camera through CameraX (I.9): an [ImageAnalysis] giving RGBA pictures turned upright for the screen as it stands
 * ([ImageAnalysis.Builder.setOutputImageRotationEnabled], following the display as it turns), the newest only, bound to
 * the camera's own [CameraLife]. Mirrored, as the desk's is, only where it is drawn ([Take.mirror]).
 */
internal class CameraXCamera private constructor(
    name: String,
    private val context: Context,
    private val provider: ProcessCameraProvider,
    private val selector: CameraSelector,
) : PhoneCamera(name) {
    private val life = CameraLife()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(Background.threads("the camera"))
    private var analysis: ImageAnalysis? = null
    private var tight: ByteBuffer? = null
    private val displays = context.getSystemService(DisplayManager::class.java)

    /** The display turning turns the camera's pictures with it. */
    private val turns = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) rotation()?.let { r -> analysis?.targetRotation = r }
        }
    }

    private fun rotation(): Int? = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation

    /** On the main thread: the use case made and bound. False, with [error] said, when the camera would not bind. */
    private fun bind(): Boolean = try {
        val a = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .apply { rotation()?.let { setTargetRotation(it) } }
            .build()
        a.setAnalyzer(executor) { proxy -> analyze(proxy) }
        life.resume()
        val camera = provider.bindToLifecycle(life, selector, a)
        analysis = a
        displays?.registerDisplayListener(turns, Handler(Looper.getMainLooper()))
        camera.cameraInfo.cameraState.observe(life) { state -> errorState.value = state.error?.let { words(it.code) } }
        true
    } catch (e: Exception) {
        errorState.value = "$name would not open: ${e.message ?: e::class.simpleName}"
        life.destroy()
        false
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val plane = proxy.planes.firstOrNull() ?: return
            val w = proxy.width
            val h = proxy.height
            val bitmap = ring.next(w, h)
            val src = plane.buffer
            src.rewind()
            if (plane.rowStride == w * 4 && plane.pixelStride == 4) {
                bitmap.copyPixelsFromBuffer(src)
            } else {
                // Rows padded past the picture's width: copied row by row into a buffer of the picture alone.
                val out = tight?.takeIf { it.capacity() == w * h * 4 } ?: ByteBuffer.allocateDirect(w * h * 4).also { tight = it }
                out.clear()
                val row = ByteArray(w * 4)
                for (y in 0 until h) {
                    src.position(y * plane.rowStride)
                    src.get(row, 0, minOf(row.size, src.remaining()))
                    out.put(row)
                }
                out.rewind()
                bitmap.copyPixelsFromBuffer(out)
            }
            deliver(bitmap)
        } catch (e: Exception) {
            errorState.value = "The camera's picture: ${e.message ?: e::class.simpleName}"
        } finally {
            proxy.close()
        }
    }

    override fun close() {
        stopRecording()
        onMain {
            runCatching { displays?.unregisterDisplayListener(turns) }
            analysis?.let { a ->
                runCatching { a.clearAnalyzer() }
                runCatching { provider.unbind(a) }
            }
            analysis = null
            life.destroy()
        }
        executor.shutdown()
    }

    companion object {
        /** The cameras here, named for a person: the front first (a creator records themself), then the back, then any other. */
        fun named(provider: ProcessCameraProvider): List<Pair<String, CameraInfo>> {
            val infos = provider.availableCameraInfos.sortedBy { facingOrder(it.lensFacing) }
            val seen = HashMap<String, Int>()
            return infos.map { info ->
                val base = when (info.lensFacing) {
                    CameraSelector.LENS_FACING_FRONT -> "Front camera"
                    CameraSelector.LENS_FACING_BACK -> "Back camera"
                    else -> "External camera"
                }
                val n = (seen[base] ?: 0) + 1
                seen[base] = n
                (if (n == 1) base else "$base $n") to info
            }
        }

        private fun facingOrder(facing: Int): Int = when (facing) {
            CameraSelector.LENS_FACING_FRONT -> 0
            CameraSelector.LENS_FACING_BACK -> 1
            else -> 2
        }

        /** The camera named [name] (else the first) opened, on the main thread; null with [onError] told why when it would not. */
        fun open(context: Context, provider: ProcessCameraProvider, name: String?, onError: (String) -> Unit): CameraXCamera? {
            val cameras = named(provider)
            val (chosen, info) = cameras.firstOrNull { it.first == name } ?: cameras.firstOrNull() ?: run {
                onError("No camera was found")
                return null
            }
            val cam = CameraXCamera(chosen, context, provider, info.cameraSelector)
            if (!cam.bind()) {
                onError(cam.error.value ?: "$chosen would not open")
                cam.executor.shutdown()
                return null
            }
            return cam
        }

        /** What CameraX's error [code] means for the person. */
        fun words(code: Int): String = when (code) {
            CameraState.ERROR_CAMERA_IN_USE -> "Another app is using the camera"
            CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras are open: close the other app using one"
            CameraState.ERROR_CAMERA_DISABLED -> "The camera is turned off on this device (its privacy setting, or the device's policy)"
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Do Not Disturb keeps the camera closed"
            CameraState.ERROR_STREAM_CONFIG -> "The camera could not give pictures of this size"
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "The camera stopped for a moment; it comes back by itself"
            else -> "The camera stopped"
        }
    }
}

/**
 * The synthetic camera on a phone or tablet (I.9), as the desk's `-Dneue.camera=synthetic`: [SyntheticCamera]'s moving
 * bands, crossing square and frame counter at 30 a second, for the emulator, which has no camera of its own worth
 * recording. A test turns it on with the system property `neue.camera=synthetic`.
 */
internal class SyntheticPhoneCamera : PhoneCamera(NAME) {
    @Volatile private var running = true
    private val thread = Background.thread("the synthetic camera") { loop() }

    private fun loop() {
        val started = System.nanoTime()
        var n = 0
        while (running) {
            val due = started + n * 1_000_000_000L / CameraFrames.FPS
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            val pixels = SyntheticCamera.frame(n++)
            val bitmap = ring.next(SyntheticCamera.WIDTH, SyntheticCamera.HEIGHT)
            bitmap.setPixels(pixels, 0, SyntheticCamera.WIDTH, 0, 0, SyntheticCamera.WIDTH, SyntheticCamera.HEIGHT)
            deliver(bitmap)
        }
    }

    override fun close() {
        stopRecording()
        running = false
        thread.join(1_000)
    }

    companion object {
        const val NAME = "Synthetic camera"
    }
}
