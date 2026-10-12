package com.kaiharimoto.neue.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.record.CameraXCamera
import com.kaiharimoto.neue.present.record.PhoneMic
import com.kaiharimoto.neue.present.record.PhoneTakeRenderer
import com.kaiharimoto.neue.present.record.SyntheticPhoneCamera
import com.kaiharimoto.neue.present.record.TakeRenderHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recording a take on a phone or tablet (I.9, `docs/present/RECORDING.md`): the camera through CameraX, bound to a
 * lifecycle of its own, its pictures written as JPEGs into the take's frames file; the microphone through `AudioRecord`
 * into the same WAV the desk writes. Each is asked for (CAMERA, RECORD_AUDIO) the first time it opens, the way voice asks.
 * The system property `neue.camera=synthetic` — the desk's `-Dneue.camera=synthetic` — puts a synthetic camera and a
 * silent microphone in their place, for the emulator's walk.
 */
actual object Capture {
    actual val canRecord: Boolean = true

    actual val whyNot: String? = null

    /** No camera here worth recording (the emulator): one made of arithmetic, and silence. Read as each opens. */
    val synthetic: Boolean get() = System.getProperty("neue.camera") == "synthetic"

    @Volatile private var error: String? = null
    actual val lastError: String? get() = error

    /** CameraX's provider for the app, or null when the device has no camera service to give one. */
    private suspend fun provider(): ProcessCameraProvider? =
        runCatching { ProcessCameraProvider.awaitInstance(Platform.context) }.getOrNull()

    /** [permission] held, asked for the first time through the activity (as voice asks for the microphone). */
    private suspend fun allowed(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(Platform.context, permission) == PackageManager.PERMISSION_GRANTED) return true
        val ask = Platform.permission ?: return false
        return withContext(Dispatchers.Main) { runCatching { ask(permission) }.getOrDefault(false) }
    }

    actual suspend fun cameras(): List<String> {
        if (synthetic) return listOf(SyntheticPhoneCamera.NAME)
        val p = provider() ?: return emptyList()
        return withContext(Dispatchers.Main) { runCatching { CameraXCamera.named(p).map { it.first } }.getOrDefault(emptyList()) }
    }

    actual suspend fun microphones(): List<String> =
        if (synthetic) listOf(PhoneMic.SILENCE) else withContext(Dispatchers.IO) { PhoneMic.inputs(Platform.context).map { it.first } }

    actual suspend fun openCamera(name: String?): LiveCamera? {
        if (synthetic) {
            error = null
            return SyntheticPhoneCamera()
        }
        if (!allowed(Manifest.permission.CAMERA)) {
            error = "The camera was not allowed. Allow it in Settings › Apps › Neue Master Tool › Permissions to record yourself."
            return null
        }
        val p = provider() ?: run {
            error = "No camera was found"
            return null
        }
        var why: String? = null
        val camera = withContext(Dispatchers.Main) { CameraXCamera.open(Platform.context, p, name) { why = it } }
        error = if (camera == null) why ?: "The camera would not open" else null
        return camera
    }

    actual suspend fun openMic(name: String?): LiveMic? {
        if (synthetic) return PhoneMic.silent()
        if (!allowed(Manifest.permission.RECORD_AUDIO)) {
            error = "The microphone was not allowed: takes record without sound. Allow it in Settings › Apps › Neue Master Tool › Permissions."
            return null
        }
        return withContext(Dispatchers.IO) { PhoneMic.open(Platform.context, name) }
    }
}

/**
 * Rendering a take on a phone or tablet (I.9): drawn out of sight in the window ([Host]) by the same slide painter, encoded
 * by the device's own H.264 and AAC through `MediaCodec`, muxed into an MP4 by `MediaMuxer`. [locals] is not needed: the
 * drawing is in the window, under its own.
 */
actual object TakeVideo {
    actual val canRender: Boolean = true

    @Composable
    actual fun Host() = TakeRenderHost()

    actual suspend fun render(
        request: RenderRequest,
        ctx: SlideContext,
        locals: CompositionLocalContext?,
        progress: (RenderProgress) -> Unit,
    ): RenderResult = PhoneTakeRenderer.render(request, ctx, progress)
}

/** [source] shared through the system's share sheet, copied into the provider's shared folder first. */
actual suspend fun deliverCopy(source: File, name: String, mime: String): String? {
    val context = Platform.context
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, name)
            source.copyTo(file, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    } ?: return "$name could not be shared"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        null
    }.getOrElse { "Nothing here can take a $mime file" }
}
