package com.kaiharimoto.neue.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.kaiharimoto.mastertool.core.update.DesktopOs
import java.io.File

/**
 * Neue on a tablet. The activity that hosts it calls [attach] before anything
 * reads it, with the application context (for files and the clipboard) and the
 * ways it has of asking the person for something — Android's pickers are
 * activity results, and only the activity can register those.
 */
actual object Platform {
    private lateinit var context: Context

    /** The activity's document picker: MIME types in, the chosen file out. */
    private var picker: (suspend (Array<String>) -> PickedFile?)? = null

    fun attach(context: Context, picker: suspend (Array<String>) -> PickedFile?) {
        this.context = context.applicationContext
        this.picker = picker
    }

    actual val os: DesktopOs = DesktopOs.ANDROID

    actual val version: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.0.0-dev"

    /** The app's own files: never visible to other apps, removed with the app. */
    actual val dataDir: File
        get() = context.filesDir

    actual fun systemLine(): String =
        "Neue Master Tool $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    actual fun browse(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** A file of the app's, handed to whatever opens it, through the app's FileProvider. */
    actual fun open(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    actual fun copy(text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Neue Master Tool", text))
        }
    }

    actual suspend fun pick(title: String, extensions: Set<String>): PickedFile? {
        val types = extensions.mapNotNull { MIME[it] }.distinct().ifEmpty { listOf("*/*") }.toTypedArray()
        return picker?.invoke(types)
    }

    private val MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "ydk" to "*/*", "ydkx" to "*/*",
    )
}
