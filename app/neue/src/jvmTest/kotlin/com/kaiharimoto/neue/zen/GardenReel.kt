package com.kaiharimoto.neue.zen

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kaiharimoto.mastertool.core.layout.SpiralGarden
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * The garden, drawn straight to PNG files: the real `Garden`, without the app
 * around it, which takes a second a frame where the studio takes a minute a still
 * (it composes the whole builder for every one). Both tests do nothing unless
 * asked, so CI runs them as no-ops.
 *
 * ```
 * # stills at chosen garden times: width, height, ink (0/1), out dir, times
 * GARDEN="1920,1080,0,/tmp/g,8;30;33" ./gradlew :neue:jvmTest --tests '*GardenReel.render*' --rerun
 * # a reel: width, height, ink, out dir, seconds, seconds per frame — with a labels.txt for captions
 * GARDEN_REEL="1280,720,0,/tmp/reel,90,0.1" ./gradlew :neue:jvmTest --tests '*GardenReel.reel*' --rerun
 * ```
 *
 * The spirals are centred where the deck would be at 1920 x 1080, scaled.
 */
class GardenReel {
    private fun renderAll(w: Int, h: Int, ink: Boolean, out: File, times: List<Float>) {
        out.mkdirs()
        val pool = Executors.newFixedThreadPool(4)
        times.withIndex().groupBy { it.index % 4 }.values.forEach { chunk ->
            pool.submit {
                val garden = Garden()
                garden.prepare(w.toFloat(), h.toFloat(), 958f * w / 1920f, 539f * h / 1080f)
                chunk.forEach { (i, t) ->
                    val bmp = ImageBitmap(w, h)
                    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
                        garden.draw(this, t, ink)
                    }
                    val png = Image.makeFromBitmap(bmp.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
                    File(out, "%04d.png".format(i)).writeBytes(png.bytes)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(3, TimeUnit.HOURS)
    }

    @Test
    fun render() {
        val spec = System.getenv("GARDEN") ?: return
        val parts = spec.split(",")
        renderAll(parts[0].toInt(), parts[1].toInt(), parts[2] == "1", File(parts[3]), parts[4].split(";").map { it.toFloat() })
    }

    @Test
    fun reel() {
        val spec = System.getenv("GARDEN_REEL") ?: return
        val parts = spec.split(",")
        val w = parts[0].toInt(); val h = parts[1].toInt(); val ink = parts[2] == "1"; val out = File(parts[3])
        val seconds = parts[4].toFloat(); val step = parts[5].toFloat()
        val g = SpiralGarden(w.toFloat(), h.toFloat(), 958f * w / 1920f, 539f * h / 1080f)
        val times = generateSequence(0f) { it + step }.takeWhile { it < seconds }.toList()
        out.mkdirs()
        File(out, "labels.txt").writeText(
            times.joinToString("\n", postfix = "\n") { t ->
                val l = g.layer(g.at(t).layer)
                "%02d %d arms %s|%.0f".format(l.index + 1, l.arms, if (l.hand > 0) "clockwise" else "anticlockwise", step * 30f)
            },
        )
        println("[reel] ${times.size} frames, a layer every ${g.layerTime}s")
        renderAll(w, h, ink, File(out, "frames"), times)
    }
}
