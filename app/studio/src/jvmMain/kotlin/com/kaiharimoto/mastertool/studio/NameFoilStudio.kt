package com.kaiharimoto.mastertool.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.layout.NameInk
import com.kaiharimoto.neue.cards.Holo
import com.kaiharimoto.neue.cards.drawFoil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.net.URI
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * kai's exploration: the card's printed name stamped in the same foil as its
 * border. Nothing in the app does this yet — this draws it, so it can be judged
 * before it is built.
 *
 * Each row is one card, three times: **as it is** today; **foil letters**, the
 * whole letter replaced by the stamp; and **foil letters with an ink outline**,
 * the letters grown by a hair in ink underneath — how a foil name is printed,
 * and what keeps silver legible on a pale Normal or Synchro frame. `--feel=x,y` moves the light, `--card=W` sets
 * the card width, `--cards=id:frame,...` chooses the cards.
 */
object NameFoilStudio {
    @JvmStatic
    fun main(args: Array<String>) {
        val map = args.mapNotNull { a -> a.removePrefix("--").split("=", limit = 2).let { if (it.size == 2) it[0] to it[1] else it[0] to "true" } }.toMap()
        val out = File(map["out"] ?: "../shots").apply { mkdirs() }
        val name = map["name"] ?: "names"
        val cardW = map["card"]?.toInt() ?: 420
        val cardH = (cardW * 1185f / 813f).roundToInt()
        val feel = map["feel"]?.split(",")?.let { (x, y) -> Offset(x.toFloat(), y.toFloat()) } ?: Offset(-0.4f, -0.3f)
        val crop = map["crop"] == "true"
        val cards = (map["cards"] ?: DEFAULT).split(",").map { it.substringBefore(":") to it.substringAfter(":", "normal") }
        val cache = File(map["data"] ?: File(System.getProperty("user.home"), ".cache/mastertool-studio").path, "names").apply { mkdirs() }

        val prepared = cards.map { (id, frame) ->
            val file = File(cache, "$id.jpg")
            if (!file.isFile) file.writeBytes(URI.create("https://images.ygoprodeck.com/images/cards/$id.jpg").toURL().readBytes())
            Prepared(id, frame, file.readBytes())
        }

        val pad = 24
        val label = 28
        val shownH = if (crop) (cardH * 0.16f).roundToInt() else cardH
        val width = pad + 3 * (cardW + pad)
        val height = pad + label + prepared.size * (shownH + pad)
        runBlocking(Dispatchers.Swing) {
            val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                Sheet(prepared, cardW, cardH, shownH, pad, label, feel)
            }
            try {
                val png = scene.render(0L).encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
                File(out, "$name.png").writeBytes(png.bytes)
                println("[names] $name.png  ${width}x$height")
            } finally {
                scene.close()
            }
        }
        kotlin.system.exitProcess(0)
    }

    private const val DEFAULT =
        "14558127:effect,89631139:normal,44508094:synchro,83764718:spell,40605147:trap,84013237:xyz,1861629:link,16178681:effect_pendulum"
}

private class Prepared(val id: String, val frameType: String, bytes: ByteArray) {
    val image: ImageBitmap
    /** The name bar's letters, as an alpha mask the size of the bar in the source image. */
    val letters: ImageBitmap
    val outline: ImageBitmap

    init {
        val skia = Image.makeFromEncoded(bytes)
        image = skia.toComposeImageBitmap()
        val bitmap = Bitmap.makeFromImage(skia)
        val x0 = (NameInk.LEFT * skia.width).roundToInt()
        val x1 = (NameInk.RIGHT * skia.width).roundToInt()
        val y0 = (NameInk.TOP * skia.height).roundToInt()
        val y1 = (NameInk.BOTTOM * skia.height).roundToInt()
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h) { i -> bitmap.getColor(x0 + i % w, y0 + i / w) }
        val mask = NameInk.mask(pixels, w, h, NameInk.lightText(frameType))
        // An outline a couple of source pixels wide: the mask grown (the inverse,
        // eroded) — ink round a foil letter, the way a foil name is printed.
        val r = max(1, (skia.width / 400f).roundToInt())
        val grown = NameInk.erode(FloatArray(mask.size) { 1f - mask[it] }, w, h, r).let { e -> FloatArray(e.size) { 1f - e[it] } }
        letters = alpha(mask, w, h)
        outline = alpha(grown, w, h)
    }

    private fun alpha(mask: FloatArray, w: Int, h: Int): ImageBitmap {
        val bytes = ByteArray(w * h * 4)
        mask.forEachIndexed { i, a ->
            val v = (a * 255f).roundToInt().coerceIn(0, 255).toByte()
            // Premultiplied white at alpha a.
            bytes[i * 4] = v; bytes[i * 4 + 1] = v; bytes[i * 4 + 2] = v; bytes[i * 4 + 3] = v
        }
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        return Image.makeRaster(info, bytes, w * 4).toComposeImageBitmap()
    }
}

@Composable
private fun Sheet(cards: List<Prepared>, cardW: Int, cardH: Int, shownH: Int, pad: Int, label: Int, feel: Offset) {
    val density = LocalDensity.current
    fun px(v: Int) = with(density) { v.toDp() }
    val text = TextStyle(fontSize = 13.sp, color = Color.Black)
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.padding(px(pad)), verticalArrangement = Arrangement.spacedBy(px(pad))) {
            Row(horizontalArrangement = Arrangement.spacedBy(px(pad))) {
                listOf("AS IT IS", "FOIL LETTERS", "FOIL LETTERS, INK OUTLINE").forEach {
                    BasicText(it, Modifier.size(px(cardW), px(label - pad / 2)), style = text)
                }
            }
            cards.forEach { card ->
                Row(horizontalArrangement = Arrangement.spacedBy(px(pad))) {
                    listOf(0, 1, 2).forEach { variant ->
                        Box(Modifier.size(px(cardW), px(shownH)).background(Color(0xFFEEEEEE))) {
                            Canvas(Modifier.size(px(cardW), px(cardH))) {
                                drawImage(card.image, dstSize = IntSize(cardW, cardH), filterQuality = FilterQuality.High)
                                drawFoil("holo", feel, ArtFrame.of(card.frameType))
                                if (variant == 2) drawOutline(card.outline)
                                if (variant > 0) drawName(card.letters, feel)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.nameBar() = Rect(
    NameInk.LEFT * size.width,
    NameInk.TOP * size.height,
    NameInk.RIGHT * size.width,
    NameInk.BOTTOM * size.height,
)

/** Ink under the letters, a hair wider than them. */
private fun DrawScope.drawOutline(outline: ImageBitmap) {
    val bar = nameBar()
    drawImage(
        outline,
        dstOffset = IntOffset(bar.left.roundToInt(), bar.top.roundToInt()),
        dstSize = IntSize(bar.width.roundToInt(), bar.height.roundToInt()),
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF111111), BlendMode.SrcIn),
        filterQuality = FilterQuality.High,
    )
}

/** The stamp, only where [mask] says there is a letter. */
private fun DrawScope.drawName(mask: ImageBitmap, feel: Offset) {
    val bar = Rect(
        NameInk.LEFT * size.width,
        NameInk.TOP * size.height,
        NameInk.RIGHT * size.width,
        NameInk.BOTTOM * size.height,
    )
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bar, Paint())
        with(Holo) { drawHoloSheet(bar, feel) }
        drawImage(
            mask,
            dstOffset = IntOffset(bar.left.roundToInt(), bar.top.roundToInt()),
            dstSize = IntSize(bar.width.roundToInt(), bar.height.roundToInt()),
            blendMode = BlendMode.DstIn,
            filterQuality = FilterQuality.High,
        )
        canvas.restore()
    }
}
