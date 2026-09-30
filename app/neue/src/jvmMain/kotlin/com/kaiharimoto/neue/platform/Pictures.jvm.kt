package com.kaiharimoto.neue.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

actual fun decodePicture(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun encodePng(image: ImageBitmap): ByteArray? =
    runCatching { Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes }.getOrNull()

actual fun encodeJpeg(image: ImageBitmap, quality: Int): ByteArray? =
    runCatching { Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, quality)?.bytes }.getOrNull()

actual fun clipboardHasPicture(): Boolean = runCatching {
    val clip = Toolkit.getDefaultToolkit().systemClipboard
    clip.isDataFlavorAvailable(DataFlavor.imageFlavor) ||
        (clip.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) &&
            (clip.getData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()?.any { it.extension.lowercase() in PICTURE_EXTENSIONS } == true)
}.getOrDefault(false)

actual suspend fun pastedPicture(): PickedFile? = withContext(Dispatchers.IO) {
    runCatching { pictureIn(Toolkit.getDefaultToolkit().systemClipboard.getContents(null)) }.getOrNull()
}

@OptIn(ExperimentalComposeUiApi::class)
private fun transferableOf(event: DragAndDropEvent): Transferable? = when (val e = event.nativeEvent) {
    is DropTargetDropEvent -> e.transferable
    is DropTargetDragEvent -> e.transferable
    else -> null
}

@OptIn(ExperimentalComposeUiApi::class)
actual fun mayBePicture(event: DragAndDropEvent): Boolean = when (val e = event.nativeEvent) {
    is DropTargetDragEvent -> e.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || e.isDataFlavorSupported(DataFlavor.imageFlavor)
    is DropTargetDropEvent -> e.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || e.isDataFlavorSupported(DataFlavor.imageFlavor)
    else -> true
}

actual fun droppedPicture(event: DragAndDropEvent): PickedFile? =
    runCatching { transferableOf(event)?.let(::pictureIn) }.getOrNull()

/**
 * The picture a clipboard or a drop holds: the first image file among files
 * (Explorer, Finder, Files), else an image itself (a browser's "Copy image",
 * a screenshot tool), written out as a PNG.
 */
private fun pictureIn(t: Transferable?): PickedFile? {
    if (t == null) return null
    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        val file = files?.filterIsInstance<File>()?.firstOrNull { it.isFile && it.extension.lowercase() in PICTURE_EXTENSIONS }
        if (file != null) return PickedFile(file.name, file.readBytes())
    }
    if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val image = t.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val buffered = image as? BufferedImage ?: run {
            val w = image.getWidth(null)
            val h = image.getHeight(null)
            if (w <= 0 || h <= 0) return null
            BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { b ->
                val g = b.createGraphics()
                g.drawImage(image, 0, 0, null)
                g.dispose()
            }
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(buffered, "png", out)) return null
        return PickedFile("pasted.png", out.toByteArray())
    }
    return null
}
