package com.kaiharimoto.neue.present

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.pdf.PdfDocument
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.platform.deliverFile
import com.kaiharimoto.neue.platform.encodeJpeg
import com.kaiharimoto.neue.platform.encodePng
import com.kaiharimoto.neue.present.paint.SlideView
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What is being exported: a PDF of the slides, pictures of them, or a YouTube thumbnail of one. */
class ExportJob(val kind: String, val slides: List<Int>) {
    companion object {
        const val PDF = "PDF"
        const val PNG = "PNG"
        const val THUMBNAIL = "THUMBNAIL"
    }

    val width: Int get() = if (kind == THUMBNAIL) 1280 else 1920
    val height: Int get() = if (kind == THUMBNAIL) 720 else 1080
}

/**
 * Exporting slides (1.0.71): each drawn by the painter at full size with every build done,
 * caught as a picture once its art has arrived, then put in a PDF (a page a slide, 16:9),
 * a picture or a zip of pictures, or a 1280 × 720 YouTube thumbnail. The same drawing as on
 * screen, on the desk and the tablet alike.
 */
@Composable
internal fun ExportOverlay(h: NeueHolders) {
    val present = h.present
    val job = present.exporting ?: return
    val p = present.open ?: run { present.exporting = null; return }
    val ctx = rememberSlideContext(h, p)
    val show = remember(p) { CompiledShow(p) }
    val density = LocalDensity.current
    val layer = rememberGraphicsLayer()
    var at by remember(job) { mutableIntStateOf(0) }
    val shots = remember(job) { ArrayList<ImageBitmap>() }
    val c = Mu.colors
    val index = job.slides.getOrNull(at) ?: return
    val slide = show.slides.getOrNull(index) ?: return

    LaunchedEffect(job, at) {
        // The first picture waits for the art to arrive; the rest are mostly cached by then.
        delay(if (at == 0) 1500 else 700)
        shots += layer.toImageBitmap()
        if (at + 1 < job.slides.size) {
            at++
        } else {
            val bytes = withContext(Dispatchers.Default) { build(job, shots, p.name) }
            present.exporting = null
            if (bytes == null) {
                h.neue.note = Note("The export could not be written")
                return@LaunchedEffect
            }
            val (name, mime) = when {
                job.kind == ExportJob.PDF -> "${p.name}.pdf" to "application/pdf"
                job.slides.size == 1 -> "${p.name}${if (job.kind == ExportJob.THUMBNAIL) " thumbnail" else " slide ${index + 1}"}.png" to "image/png"
                else -> "${p.name} slides.zip" to "application/zip"
            }
            deliverFile(name.replace(Regex("[\\\\/:*?\"<>|]"), " "), mime, bytes)?.let { h.neue.note = Note("Saved $it") }
        }
    }

    Box(Modifier.fillMaxSize().background(c.overlay), contentAlignment = Alignment.Center) {
        // The slide at its full size, recorded into the layer as it is drawn.
        Box(
            Modifier
                .requiredSize(with(density) { job.width.toDp() }, with(density) { job.height.toDp() })
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) {
            val last = (show.builds.getOrNull(index)?.count ?: 1) - 1
            SlideView(
                ctx, slide, show.zone(index), show.stage(index), Modifier.fillMaxSize(),
                deck = if (slide.deck != null) ({ show.deckFrame(index) }) else null,
                deckKeys = show.deckFrame(index)?.cards?.map { it.key }.orEmpty(),
                state = { e -> show.state(Cursor(index, last), e, Long.MAX_VALUE / 4) },
            )
        }
        Column(
            Modifier.width(360.dp).background(c.paper).border(1.dp, c.ink).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Micro(if (job.kind == ExportJob.PDF) "Writing the PDF" else "Saving pictures", color = c.ink)
            Small("Slide ${at + 1} of ${job.slides.size}", color = c.ink70)
            Progress((at + 1f) / job.slides.size)
            MuButton("Stop", { present.exporting = null })
        }
    }
}

private fun build(job: ExportJob, shots: List<ImageBitmap>, title: String): ByteArray? {
    if (shots.isEmpty()) return null
    return when (job.kind) {
        ExportJob.PDF -> {
            val doc = PdfDocument(JvmZlib, title, "Neue Master Tool")
            for (img in shots) {
                val jpeg = encodeJpeg(img, 90) ?: return null
                val page = doc.page(960f, 540f)
                page.image(PdfImage.jpeg(img.width, img.height, jpeg), 0f, 0f, 960f, 540f)
            }
            doc.write()
        }
        else -> if (shots.size == 1) {
            encodePng(shots[0])
        } else {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                shots.forEachIndexed { i, img ->
                    zip.putNextEntry(ZipEntry("slide %02d.png".format(i + 1)))
                    zip.write(encodePng(img) ?: return null)
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
    }
}
