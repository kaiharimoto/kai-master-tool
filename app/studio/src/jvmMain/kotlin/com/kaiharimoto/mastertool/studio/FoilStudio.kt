package com.kaiharimoto.mastertool.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.ui.configureImageLoader
import com.kaiharimoto.neue.cards.drawFoil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * The card foil on its own, drawn by the real `drawFoil` over real card art,
 * at scripted pointer positions — the app's side of the comparison with the
 * Blender renders in `tools/foil/`.
 *
 * `--feel=x,y;x,y;...` draws one card per pointer position in a row (a sheet);
 * `--sweep=N` instead writes N frames of the pointer sweeping left to right and
 * back, for a GIF. `--bg=ink` for the black background. `--cards=id:frame,...`
 * draws one card per entry instead, all at the first pointer position, each with
 * the art frame its frame type prints — the alignment check.
 */
object FoilStudio {
    @JvmStatic
    fun main(args: Array<String>) {
        val map = args.mapNotNull { a -> a.removePrefix("--").split("=", limit = 2).let { if (it.size == 2) it[0] to it[1] else it[0] to "true" } }.toMap()
        val out = File(map["out"] ?: "../shots").apply { mkdirs() }
        val name = map["name"] ?: "foil"
        val cardW = map["card"]?.toInt() ?: 360
        val cardH = (cardW * 86f / 59f).toInt()
        val style = map["style"] ?: "holo"
        val id = map["id"] ?: "89631139"
        val ink = map["bg"] == "ink"
        val pad = 24
        val sweep = map["sweep"]?.toInt()
        val feels: List<Offset> = map["feel"]?.split(";")?.map { it.split(",").let { (x, y) -> Offset(x.toFloat(), y.toFloat()) } }
            ?: listOf(Offset(-1f, -0.4f), Offset(-0.5f, 0f), Offset(0f, 0f), Offset(0.5f, 0f), Offset(1f, 0.4f))
        // Either several pointer positions over one card, or several cards at one position.
        val cards: List<Pair<String, String>> = map["cards"]?.split(",")?.map { it.substringBefore(":") to it.substringAfter(":", "normal") }
            ?: List(feels.size) { id to (map["frame"] ?: "normal") }
        val many = map["cards"] != null
        val count = if (sweep != null) 1 else cards.size
        val width = pad + count * (cardW + pad)
        val height = cardH + pad * 2
        val data = File(map["data"] ?: File(System.getProperty("user.home"), ".cache/mastertool-studio").path)
        configureImageLoader(File(data, "card-art").absolutePath)
        var current by mutableStateOf(if (many) List(cards.size) { feels.first() } else feels)
        runBlocking(Dispatchers.Swing) {
            val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                Sheet(current, cards, cardW, cardH, pad, style, ink)
            }
            try {
                val clock = FrameClock(scene, pauseMillis = 20)
                clock.run(150)
                fun write(file: File) {
                    val png = clock.frame().encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
                    file.writeBytes(png.bytes)
                }
                if (sweep != null) {
                    val dir = File(out, name).apply { mkdirs() }
                    repeat(sweep) { i ->
                        val t = i.toFloat() / sweep
                        val x = if (t < 0.5f) -1f + 4f * t else 3f - 4f * t
                        current = listOf(Offset(x, x * 0.35f))
                        clock.run(2)
                        write(File(dir, "%03d.png".format(i)))
                    }
                    println("[foil] $sweep frames in ${dir.path}")
                } else {
                    write(File(out, "$name.png"))
                    println("[foil] $name.png")
                }
            } finally {
                scene.close()
            }
        }
        kotlin.system.exitProcess(0)
    }
}

@Composable
private fun Sheet(feels: List<Offset>, cards: List<Pair<String, String>>, cardW: Int, cardH: Int, pad: Int, style: String, ink: Boolean) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(if (ink) Color.Black else Color.White)) {
        Row(Modifier.padding(with(density) { pad.toDp() }), horizontalArrangement = Arrangement.spacedBy(with(density) { pad.toDp() })) {
            feels.forEachIndexed { i, feel ->
                val (cardId, frameType) = cards[i.coerceAtMost(cards.lastIndex)]
                val frame = ArtFrame.of(frameType)
                AsyncImage(
                    model = "https://images.ygoprodeck.com/images/cards/$cardId.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(with(density) { cardW.toDp() }, with(density) { cardH.toDp() })
                        .drawWithContent {
                            drawContent()
                            drawFoil(style, feel, frame)
                        },
                )
            }
        }
    }
}
