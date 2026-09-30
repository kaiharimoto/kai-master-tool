package com.kaiharimoto.neue.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.zxing.RGBLuminanceSource
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.qr.QrReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Neue on a tablet. The activity that hosts it calls [attach] before anything
 * reads it, with the application context (for files and the clipboard) and the
 * ways it has of asking the person for something — Android's pickers are
 * activity results, and only the activity can register those.
 */
actual object Platform {
    internal lateinit var context: Context
        private set

    /** The activity's document picker: MIME types in, the chosen file out. */
    private var picker: (suspend (Array<String>) -> PickedFile?)? = null

    /** The activity's camera scanner (v1.3.7), which only it can start: a QR code's text, or why there is none. */
    private var scanner: (suspend () -> QrScan)? = null

    /**
     * The activity itself, weakly: a picture dragged in from another app can only
     * be read once the activity asks for the drop's permissions (1.0.34).
     */
    internal var activity: java.lang.ref.WeakReference<android.app.Activity>? = null
        private set

    /** The activity's camera app, for a photo to show Ai (1.0.55). */
    private var camera: (suspend () -> PickedFile?)? = null

    /** The activity asking for a runtime permission (1.0.57: the microphone): granted or not. */
    internal var permission: (suspend (String) -> Boolean)? = null
        private set

    /** The APK's foreground service for Ai at work (1.0.61): on, its title and line; or off. */
    private var work: ((Boolean, String, String) -> Unit)? = null

    /** The APK's "Ai answered" notification, posted only while the app is out of sight. */
    private var answer: ((String, String) -> Unit)? = null

    actual fun working(on: Boolean, title: String, line: String) {
        runCatching { work?.invoke(on, title, line) }
    }

    actual fun answered(title: String, line: String) {
        runCatching { answer?.invoke(title, line) }
    }

    fun attach(
        context: Context,
        picker: suspend (Array<String>) -> PickedFile?,
        scanner: (suspend () -> QrScan)? = null,
        camera: (suspend () -> PickedFile?)? = null,
        permission: (suspend (String) -> Boolean)? = null,
        work: ((Boolean, String, String) -> Unit)? = null,
        answer: ((String, String) -> Unit)? = null,
    ) {
        this.work = work
        this.answer = answer
        this.context = context.applicationContext
        activity = (context as? android.app.Activity)?.let { java.lang.ref.WeakReference(it) }
        this.picker = picker
        this.scanner = scanner
        this.camera = camera
        this.permission = permission
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

    actual val canShare: Boolean = true

    actual fun shareText(text: String, title: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    actual fun onUnmeteredNetwork(): Boolean = runCatching {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        connectivity.isActiveNetworkMetered.not()
    }.getOrDefault(false)

    actual val scanSources: Set<QrSource>
        get() = buildSet {
            val camera = runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }.getOrDefault(false)
            if (camera && scanner != null) add(QrSource.CAMERA)
            add(QrSource.PICTURE)
        }

    actual val canTakePhoto: Boolean
        get() = camera != null && runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }.getOrDefault(false)

    actual suspend fun takePhoto(): PickedFile? = camera?.invoke()

    actual suspend fun scanQr(from: QrSource): QrScan = when (from) {
        QrSource.CAMERA -> scanner?.invoke() ?: QrScan.NoCamera
        QrSource.PICTURE -> {
            val picture = pick("A picture of a QR code", setOf("png", "jpg", "jpeg", "webp"))
            if (picture == null) {
                QrScan.Cancelled
            } else {
                withContext(Dispatchers.Default) { qrIn(picture.bytes) }.takeIf { it.isNotEmpty() }?.let { QrScan.Read(it) } ?: QrScan.NotFound
            }
        }
    }

    /**
     * The QR codes in a picture — a split deck's grid holds several (1.0.32). A
     * photo is shrunk to at most 3,200 pixels a side first: a phone's screenshot
     * of a grid of codes keeps every module, and a full-size photo is fifty
     * megabytes of pixels.
     */
    private fun qrIn(bytes: ByteArray): List<String> = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching emptyList()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        QrReader.readAll(source)
    }.getOrDefault(emptyList())

    private val MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "ydk" to "*/*", "ydkx" to "*/*",
    )
}
