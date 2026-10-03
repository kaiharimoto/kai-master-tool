package com.kaiharimoto.neue.platform

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Pictures in and out of pixels, and the two ways a picture arrives that are
 * not a file dialog (kai, 1.0.34: "upload an image, drag a file from an
 * explorer, or just paste a copied image"). Each platform's own: Skia and AWT
 * on the desk, `BitmapFactory` and the `ContentResolver` on a tablet.
 */

/** The file extensions a picture may have. */
val PICTURE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

/** A photograph is read no larger than this on its longer side: a crop never needs more. */
const val MAX_PICTURE_SIDE = 4096

/** [bytes] as pixels, or null when they are not a picture this platform reads. */
expect fun decodePicture(bytes: ByteArray): ImageBitmap?

/** [image] as a PNG file's bytes, or null when it could not be written. */
expect fun encodePng(image: ImageBitmap): ByteArray?

/** [image] as a JPEG file's bytes at [quality] (0–100), or null when it could not be written. */
expect fun encodeJpeg(image: ImageBitmap, quality: Int): ByteArray?

/** The picture on the clipboard — an image copied, or an image file copied in a file manager — or null. */
expect suspend fun pastedPicture(): PickedFile?

/** Whether the clipboard holds a picture now, without reading it: a paste into a text field decides by it. */
expect fun clipboardHasPicture(): Boolean

/** Whether [event] is carrying something that may be a picture, so the drop target lights up for it. */
expect fun mayBePicture(event: DragAndDropEvent): Boolean

/** The picture dropped with [event] — an image file, or an image dragged from another app — or null. */
expect fun droppedPicture(event: DragAndDropEvent): PickedFile?

/**
 * The address of the picture [event] carries when it carries no picture itself (1.0.89, kai: an image dragged out of
 * Chrome "wouldn't react"): a browser hands over the image's address — its `<img src>`, a link — never its bytes. Read
 * at the drop (a drag's data is only readable then), fetched after with [fetchPicture].
 */
expect fun droppedLink(event: DragAndDropEvent): String?

/** The picture at [address] — `http(s):`, or a `data:image/…;base64,` address — or null when it is no picture. */
suspend fun fetchPicture(address: String): PickedFile? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val a = address.trim()
        if (a.startsWith("data:image/", ignoreCase = true) && ";base64," in a) {
            val kind = a.substringAfter("data:image/").substringBefore(';').lowercase().let { if (it == "jpeg") "jpg" else it }
            return@runCatching PickedFile("dropped.$kind", java.util.Base64.getDecoder().decode(a.substringAfter(";base64,")))
        }
        if (!a.startsWith("http://", ignoreCase = true) && !a.startsWith("https://", ignoreCase = true)) return@runCatching null
        val c = java.net.URI(a).toURL().openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "NeueMasterTool")
        c.setRequestProperty("Accept", "image/*")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val type = c.contentType.orEmpty().lowercase()
            val bytes = c.inputStream.use { it.readNBytes(MAX_FETCH) }
            if (!type.startsWith("image/") && decodePicture(bytes) == null) return@runCatching null
            val kind = type.substringAfter("image/", "").substringBefore(';').let { if (it == "jpeg") "jpg" else it }.ifBlank { "png" }
            PickedFile("dropped.$kind", bytes)
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}

/** The most a fetched picture may be: a card's art, never a whole site. */
private const val MAX_FETCH = 25 * 1024 * 1024

/** The `src` of the first `<img>` in [html] (what a browser drags with an image), or null. */
fun imageSource(html: String): String? =
    Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        ?.replace("&amp;", "&")
