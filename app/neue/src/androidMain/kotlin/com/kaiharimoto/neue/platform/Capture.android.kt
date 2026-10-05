package com.kaiharimoto.neue.platform

import android.content.Intent
import androidx.compose.runtime.CompositionLocalContext
import androidx.core.content.FileProvider
import com.kaiharimoto.neue.present.paint.SlideContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recording a take on a phone or tablet (1.1.13): not yet. The seam is here for CameraX (the camera, its preview and
 * its file) and `MediaCodec`/`MediaMuxer` (the device's own H.264 encoder) — never FFmpeg, whose Android natives alone
 * are 23 MB (`docs/present/RECORDING.md`). Until then the page says so, and a presentation's camera zone shows its
 * fill, as before.
 */
actual object Capture {
    actual val canRecord: Boolean = false

    actual val whyNot: String? = "Recording a take is on the desktop app for now; a phone or tablet presents, and Neue Master Tool on a computer records."

    actual suspend fun cameras(): List<String> = emptyList()

    actual suspend fun microphones(): List<String> = emptyList()

    actual suspend fun openCamera(name: String?): LiveCamera? = null

    actual suspend fun openMic(name: String?): LiveMic? = null

    actual val lastError: String? get() = whyNot
}

/** Rendering a take on a phone or tablet: not yet (takes are made and kept on the desk, never synced). */
actual object TakeVideo {
    actual val canRender: Boolean = false

    actual suspend fun render(
        request: RenderRequest,
        ctx: SlideContext,
        locals: CompositionLocalContext?,
        progress: (RenderProgress) -> Unit,
    ): RenderResult = RenderResult.Failed(Capture.whyNot ?: "Not on this device")
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
