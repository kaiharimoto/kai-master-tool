package com.kaiharimoto.neue.ai

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.kaiharimoto.mastertool.core.ai.vision.PictureFit
import com.kaiharimoto.neue.platform.PickedFile
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.platform.encodeJpeg
import com.kaiharimoto.neue.platform.encodePng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A picture waiting in the composer to go with the next message (1.0.55): already the size a
 * model reads ([PictureFit]), as the bytes that will be sent, with a small [preview] to draw.
 */
class Attachment(
    val name: String,
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
    val preview: ImageBitmap,
)

object Attachments {
    /**
     * [file] ready to send, or null when it is not a picture: shrunk to what a model reads, kept a
     * PNG when it came as a small one (a screenshot's text stays crisp), a JPEG otherwise.
     */
    suspend fun prepare(file: PickedFile): Attachment? = withContext(Dispatchers.Default) {
        runCatching {
            val image = decodePicture(file.bytes) ?: return@runCatching null
            val (w, h) = PictureFit.size(image.width, image.height)
            val fitted = if (w == image.width && h == image.height) image else scaled(image, w, h)
            val wasPng = PictureFit.mime(file.name, file.bytes) == "image/png"
            val png = if (wasPng) encodePng(fitted) else null
            val (bytes, mime) = if (PictureFit.keepsPng(wasPng, png?.size)) {
                png!! to "image/png"
            } else {
                (encodeJpeg(fitted, PictureFit.JPEG_QUALITY) ?: return@runCatching null) to "image/jpeg"
            }
            Attachment(file.name, bytes, mime, w, h, fitted)
        }.getOrNull()
    }

    /** [image] drawn again at [w] × [h], smoothly. */
    fun scaled(image: ImageBitmap, w: Int, h: Int): ImageBitmap {
        val out = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w.toFloat(), h.toFloat())) {
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w, h),
                filterQuality = FilterQuality.High,
            )
        }
        return out
    }
}
