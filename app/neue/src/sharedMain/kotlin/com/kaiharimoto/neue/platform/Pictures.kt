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

/** The picture on the clipboard — an image copied, or an image file copied in a file manager — or null. */
expect suspend fun pastedPicture(): PickedFile?

/** Whether [event] is carrying something that may be a picture, so the drop target lights up for it. */
expect fun mayBePicture(event: DragAndDropEvent): Boolean

/** The picture dropped with [event] — an image file, or an image dragged from another app — or null. */
expect fun droppedPicture(event: DragAndDropEvent): PickedFile?
