package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.chessy.Takeover
import com.kaiharimoto.mastertool.core.ai.chessy.Takeover.rnd
import com.kaiharimoto.neue.theme.LocalMuFonts
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The takeover's colour (kai, 2026-10): the one moment the app breaks every Master UI rule on purpose, so its colour,
 * gradients and glitches live here, a file `MasterUiLawTest` names. The live app ([GraphicsLayer], drawn as it is)
 * slips and tears with its colours split, static crawls over it, a red glow breathes in from the edges, the breach
 * alert is red on black, and her heads pop in with their colours split. What Ai has won back is never touched.
 */
internal object TakeoverInk {
    val ALARM = Color(0xFFE0242B)
    private val ALARM_DARK = Color(0xFF0B0809)
    private val ALARM_TEXT = Color(0xFFFFDCDC)
    private val ALARM_SOFT = Color(0xFFFF9A9A)
    private val NIGHT = Color(0xFF0D0A10)
    private val BLACK = Color(0xFF000000)
    private val SPLIT_PINK = Color(0xFFFF2878)
    private val SPLIT_CYAN = Color(0xFF3CDCFF)

    /** The app's red, green and blue alone, each added over black: three of them offset is the colour split. */
    private val channels = listOf(Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF)).map {
        Paint().apply { colorFilter = ColorFilter.lighting(it, BLACK); blendMode = BlendMode.Plus }
    }
    private val overlay = Paint().apply { blendMode = BlendMode.Overlay }

    /** A tile of static, made once: grey grains two pixels wide. */
    fun noise(): ImageBitmap {
        val n = 128
        val img = ImageBitmap(n * 2, n * 2)
        val canvas = androidx.compose.ui.graphics.Canvas(img)
        val p = Paint()
        val r = kotlin.random.Random(11)
        for (y in 0 until n) for (x in 0 until n) {
            val v = r.nextFloat()
            p.color = Color(v, v, v, 1f)
            canvas.drawRect(x * 2f, y * 2f, x * 2f + 2f, y * 2f + 2f, p)
        }
        return img
    }

    /** [layer] drawn with its colours split by [split] pixels and moved by [dx], at [alpha]. */
    private fun DrawScope.splitApp(layer: GraphicsLayer, dx: Float, split: Float, alpha: Float = 1f) {
        for ((i, paint) in channels.withIndex()) {
            paint.alpha = alpha
            drawIntoCanvas { c ->
                c.saveLayer(Rect(Offset.Zero, size), paint)
                translate(dx + split * (1 - i), 0f) { drawLayer(layer) }
                c.restore()
            }
        }
    }

    /**
     * Everything the takeover does to the window at [t], kept inside [dirty] (the part Ai has not won back) and out of
     * [patch] (the corner she notices first). [kd] is how far things are thrown: a 1920-wide window is 1.
     */
    fun DrawScope.glitch(layer: GraphicsLayer, t: Float, noise: ImageBitmap, dirty: Rect, patch: Rect?, kd: Float, still: Boolean) {
        val w = size.width
        val h = size.height
        clipRect(dirty.left, dirty.top, dirty.right, dirty.bottom) {
            if (patch != null) clipRect(patch.left, patch.top, patch.right, patch.bottom, ClipOp.Difference) { all(layer, t, noise, w, h, kd, still) }
            else all(layer, t, noise, w, h, kd, still)
        }
    }

    private fun DrawScope.all(layer: GraphicsLayer, t: Float, noise: ImageBitmap, w: Float, h: Float, kd: Float, still: Boolean) {
        val g = if (still) 0f else Takeover.glitch(t)
        val slot = floor(t * 14f).toInt()
        // parts of the app slip sideways with their colours split
        val regions = (g * 5f + if (g > 0f && rnd(slot, 1) < g) 1f else 0f).roundToInt()
        for (i in 0 until regions) {
            if (rnd(slot, 10 + i) > .55f + g * .45f) continue
            val x0 = rnd(slot, 20 + i) * w * .75f
            val rw = (.15f + rnd(slot, 25 + i) * .35f) * w
            val y0 = rnd(slot, 27 + i) * h * .85f
            val rh = (.1f + rnd(slot, 29 + i) * .3f) * h
            val slices = 2 + floor(rnd(slot, 30 + i) * 6f * max(g, .3f)).toInt()
            for (s in 0 until slices) {
                val sy = y0 + rnd(slot, 40 + i * 9 + s) * rh
                val sh = (4f + rnd(slot, 50 + i * 9 + s) * 34f) * kd
                val dx = (rnd(slot, 60 + i * 9 + s) - .5f) * 90f * g * kd
                clipRect(x0, sy, x0 + rw, sy + sh) {
                    drawRect(BLACK)
                    splitApp(layer, dx, (3f + 9f * g) * kd)
                }
            }
        }
        // the chaos, and her knock-back: the whole window tears
        val tear = if (still) 0f else Takeover.tear(t)
        if (tear > 0f) {
            val off = (6f + 12f * rnd(slot, 2)) * tear * kd
            drawRect(BLACK, alpha = .55f * tear)
            splitApp(layer, 0f, off, .7f * tear)
            for (b in 0 until 16) {
                val bw = min(w * .9f, (60f + rnd(slot, 100 + b) * 360f) * kd)
                val bh = (8f + rnd(slot, 120 + b) * 70f) * kd
                val sx = rnd(slot, 140 + b) * (w - bw)
                val sy = rnd(slot, 160 + b) * (h - bh)
                val tx = sx + (rnd(slot, 180 + b) - .5f) * 240f * kd * tear
                val ty = sy + (rnd(slot, 200 + b) - .5f) * 40f * kd * tear
                clipRect(tx, ty, tx + bw, ty + bh) { translate(tx - sx, ty - sy) { drawLayer(layer) } }
            }
            for (s in 0 until 6) {
                val y = rnd(slot, 220 + s) * h
                val dx = (rnd(slot, 240 + s) - .5f) * 300f * kd * tear
                clipRect(0f, y, w, y + 3f * kd) { translate(dx, 0f) { drawLayer(layer) } }
            }
        }
        // static
        val st = if (still) 0f else Takeover.static(t)
        if (st > 0f) {
            overlay.alpha = st
            val ox = -rnd(slot, 3) * noise.width
            val oy = -rnd(slot, 4) * noise.height
            drawIntoCanvas { c ->
                var y = oy
                while (y < h) {
                    var x = ox
                    while (x < w) { c.drawImage(noise, Offset(x, y), overlay); x += noise.width }
                    y += noise.height
                }
            }
        }
        // the red glow, breathing in from the edges
        val glow = if (still) .75f * Takeover.glow(t).coerceAtMost(.7f) else Takeover.glow(t)
        if (glow > 0f) {
            val edge = min(w, h) * .32f
            val clear = ALARM.copy(alpha = 0f)
            val on = ALARM.copy(alpha = glow)
            drawRect(Brush.verticalGradient(listOf(on, clear), 0f, edge), Offset.Zero, Size(w, edge))
            drawRect(Brush.verticalGradient(listOf(clear, on), h - edge, h), Offset(0f, h - edge), Size(w, edge))
            drawRect(Brush.horizontalGradient(listOf(on, clear), 0f, edge), Offset.Zero, Size(edge, h))
            drawRect(Brush.horizontalGradient(listOf(clear, on), w - edge, w), Offset(w - edge, 0f), Size(edge, h))
            drawRect(ALARM, alpha = glow * .12f)
        }
        // the snap's dark, and the dim behind her
        val dark = Takeover.dark(t)
        if (dark > 0f) drawRect(NIGHT, alpha = dark)
    }

    /**
     * The breach alert (2.4–9 s): the one official-looking thing on screen, red on black, its bar climbing with stalls
     * and jumps. [t] is read where it is drawn or where its words change.
     */
    @Composable
    fun BreachAlert(t: () -> Float, modifier: Modifier = Modifier) {
        val fonts = LocalMuFonts.current
        val step by remember { derivedStateOf { Takeover.breachStep(t()) } }
        val line by remember { derivedStateOf { Takeover.breachLine(t()) } }
        val pct by remember { derivedStateOf { Takeover.breach(t()).toInt() } }
        val mono = TextStyle(fontFamily = fonts.mono, fontSize = 12.sp, lineHeight = 16.sp, color = ALARM_TEXT)
        Column(
            modifier.background(ALARM_DARK).border(2.dp, ALARM).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText("⚠ Security breach".uppercase(), style = mono.copy(color = ALARM, fontWeight = FontWeight.Bold, letterSpacing = .14.em))
            BasicText(line, style = mono)
            Box(
                Modifier.fillMaxWidth().height(10.dp).border(1.dp, ALARM).drawBehind {
                    drawRect(ALARM, size = Size(size.width * Takeover.breach(t()) / 100f, size.height))
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(step, style = mono.copy(color = ALARM_SOFT))
                BasicText("${if (t() >= Takeover.CHAOS_AT) 100 else pct}%", style = mono.copy(color = ALARM_SOFT))
            }
        }
    }

    /**
     * One of her heads in the chaos: drawn once, then again in pink and cyan either side of it, slipping wide and
     * torn into a band while it glitches in or out.
     */
    @Composable
    fun Modifier.headSplit(glitching: () -> Boolean, slot: () -> Int, salt: Int): Modifier {
        val layer = rememberGraphicsLayer()
        val tints = remember { listOf(SPLIT_PINK, SPLIT_CYAN).map { Paint().apply { colorFilter = ColorFilter.tint(it, BlendMode.SrcIn) } } }
        return drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            val g = glitching()
            val s = slot()
            val off = (if (g) 3f + rnd(s, salt) * 6f else 2f) * density
            for ((i, p) in tints.withIndex()) {
                p.alpha = if (g) .85f else .5f
                drawIntoCanvas { c ->
                    c.saveLayer(Rect(Offset.Zero, size).inflate(off * 2), p)
                    translate(if (i == 0) off else -off, 0f) { drawLayer(layer) }
                    c.restore()
                }
            }
            if (g && rnd(s, salt + 2) < .5f) {
                val a = rnd(s, salt + 3) * .7f * size.height
                val band = (.12f + rnd(s, salt + 4) * .4f) * size.height
                clipRect(0f, a, size.width, a + band) { drawLayer(layer) }
            } else {
                drawLayer(layer)
            }
        }
    }
}
