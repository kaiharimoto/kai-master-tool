package com.kaiharimoto.neue.zen

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kaiharimoto.mastertool.core.layout.GardenRect
import com.kaiharimoto.mastertool.core.layout.RakeProgram
import com.kaiharimoto.mastertool.core.layout.Samon
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * The rake garden, drawn straight to PNG files: the real `Garden`, without the
 * app around it, which takes a second a frame where the studio takes a minute a
 * still (it composes the whole builder for every one). Both tests do nothing
 * unless asked, so CI runs them as no-ops.
 *
 * ```
 * # stills at chosen garden times: width, height, seed, ink (0/1), out dir, times
 * GARDEN="1920,1080,7,0,/tmp/g,8;30;128" ./gradlew :neue:jvmTest --tests '*GardenReel.render*' --rerun
 * # a reel of five compositions, each raked then swept, with a labels.txt for captions
 * GARDEN_REEL="1280,720,0,/tmp/reel,5" ./gradlew :neue:jvmTest --tests '*GardenReel.reel*' --rerun
 * ```
 *
 * The deck is stood in for by the rectangle it occupies at 1920 x 1080, scaled.
 */
class GardenReel {
    private fun stoneFor(w: Int): GardenRect {
        val s = w / 1920f
        return GardenRect(474f * s, 108f * s, 1446f * s, 974f * s)
    }

    private fun renderAll(w: Int, h: Int, seed: Int, ink: Boolean, out: File, times: List<Float>) {
        out.mkdirs()
        val pool = Executors.newFixedThreadPool(4)
        val chunks = times.withIndex().groupBy { it.index % 4 }
        chunks.values.forEach { chunk ->
            pool.submit {
                val garden = Garden()
                garden.prepare(w.toFloat(), h.toFloat(), stoneFor(w), seed)
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
        pool.awaitTermination(2, TimeUnit.HOURS)
    }

    @Test
    fun render() {
        val spec = System.getenv("GARDEN") ?: return
        // GARDEN="w,h,seed,ink,out,t1;t2;..."
        val parts = spec.split(",")
        renderAll(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3] == "1", File(parts[4]), parts[5].split(";").map { it.toFloat() })
    }

    @Test
    fun reel() {
        val spec = System.getenv("GARDEN_REEL") ?: return
        // GARDEN_REEL="w,h,ink,out,cycles"
        val parts = spec.split(",")
        val w = parts[0].toInt(); val h = parts[1].toInt(); val ink = parts[2] == "1"; val out = File(parts[3]); val cycles = parts[4].toInt()
        val kinds = Samon.entries.filter { it != Samon.CHOKUSEN }.toSet()
        val seed = (1..5000).first { s ->
            val p = RakeProgram(w.toFloat(), h.toFloat(), stoneFor(w), s)
            (0 until cycles).map { p.composition(it).samon }.toSet().size == minOf(cycles, kinds.size) && p.composition(0).samon == Samon.MIZUMON
        }
        val program = RakeProgram(w.toFloat(), h.toFloat(), stoneFor(w), seed)
        val sweep = program.grain.sweepTime(w.toFloat()) + (System.getenv("GARDEN_REEL_PAD")?.toFloat() ?: 0f)
        val times = mutableListOf<Float>()
        val labels = mutableListOf<String>()
        var t = 0f
        fun span(from: Float, to: Float, frames: Int, label: String) {
            for (i in 0 until frames) { times += from + (to - from) * i / frames; labels += label }
        }
        span(0f, RakeProgram.OPENING, 8, "straight|1")
        t = RakeProgram.OPENING
        for (n in 0 until cycles) {
            val c = program.composition(n)
            val name = "%02d %s".format(n + 1, c.samon.name)
            val rakeFrames = 150
            span(t, t + c.duration, rakeFrames, "$name|%.0f".format(c.duration * 30 / rakeFrames))
            t += c.duration
            span(t, t + RakeProgram.HOLD, 45, "$name|%.0f".format(RakeProgram.HOLD * 30 / 45))
            t += RakeProgram.HOLD
            val sweepFrames = if (n == 0) (sweep * 30).toInt() else (sweep * 15).toInt()
            span(t, t + sweep, sweepFrames, "SWEEP|%.0f".format(sweep * 30 / sweepFrames))
            t += sweep
            span(t, t + RakeProgram.REST, 12, "CHOKUSEN|%.0f".format(RakeProgram.REST * 30 / 12))
            t += RakeProgram.REST
        }
        out.mkdirs()
        // The caption is what the program is doing at that frame, not what the reel meant to show.
        val order = (0 until cycles).associate { program.composition(it).samon to it }
        val said = times.mapIndexed { i, at ->
            val f = program.at(at)
            val step = ((times.getOrNull(i + 1) ?: (at + (at - times[i - 1]))) - at) * 30f
            val top = f.top
            val what = when {
                top?.samon == Samon.CHOKUSEN -> "SWEEP"
                top != null -> "%02d %s".format(order.getValue(top.samon) + 1, top.samon.name)
                f.base.samon != Samon.CHOKUSEN -> "%02d %s".format(order.getValue(f.base.samon) + 1, f.base.samon.name)
                else -> "CHOKUSEN"
            }
            "$what|%.0f".format(step)
        }
        File(out, "labels.txt").writeText(said.joinToString("\n") + "\n")
        if (System.getenv("GARDEN_REEL_LABELS_ONLY") != null) return
        println("[reel] seed $seed, ${times.size} frames, ${t}s of garden: " + (0 until cycles).joinToString { program.composition(it).samon.name })
        renderAll(w, h, seed, ink, File(out, "frames"), times)
    }
}
