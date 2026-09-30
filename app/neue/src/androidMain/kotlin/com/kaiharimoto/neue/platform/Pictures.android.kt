package com.kaiharimoto.neue.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

actual fun decodePicture(bytes: ByteArray): ImageBitmap? = runCatching {
    // A phone's photograph is 50 megapixels: read it at the largest halving under the cap.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

actual fun encodePng(image: ImageBitmap): ByteArray? = runCatching {
    val out = ByteArrayOutputStream()
    if (!image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) return null
    out.toByteArray()
}.getOrNull()

actual fun encodeJpeg(image: ImageBitmap, quality: Int): ByteArray? = runCatching {
    val out = ByteArrayOutputStream()
    if (!image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
    out.toByteArray()
}.getOrNull()

actual fun clipboardHasPicture(): Boolean = runCatching {
    val manager = Platform.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.primaryClipDescription?.hasMimeType("image/*") == true
}.getOrDefault(false)

actual suspend fun pastedPicture(): PickedFile? {
    val clip = runCatching {
        (Platform.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
    }.getOrNull() ?: return null
    return withContext(Dispatchers.IO) { pictureIn(clip) }
}

actual fun mayBePicture(event: DragAndDropEvent): Boolean {
    val description = runCatching { event.toAndroidDragEvent().clipDescription }.getOrNull() ?: return true
    return (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") } ||
        description.hasMimeType("application/octet-stream")
}

actual fun droppedPicture(event: DragAndDropEvent): PickedFile? = runCatching {
    val drag = event.toAndroidDragEvent()
    // Another app's file is only ours to read once the activity has asked for it.
    Platform.activity?.get()?.requestDragAndDropPermissions(drag)
    drag.clipData?.let(::pictureIn)
}.getOrNull()

/** The first picture among [clip]'s items, read through the content resolver. */
private fun pictureIn(clip: ClipData): PickedFile? {
    val resolver = Platform.context.contentResolver
    for (i in 0 until clip.itemCount) {
        val uri: Uri = clip.getItemAt(i).uri ?: continue
        val type = runCatching { resolver.getType(uri) }.getOrNull()
        if (type != null && !type.startsWith("image/")) continue
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: continue
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "picture.${type?.substringAfter('/') ?: "png"}"
        return PickedFile(name, bytes)
    }
    return null
}
