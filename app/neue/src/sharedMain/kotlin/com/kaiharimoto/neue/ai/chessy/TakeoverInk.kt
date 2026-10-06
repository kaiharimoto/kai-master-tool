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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
 * alert is red on black, warning windows pile up red and see-through with caution signs (kai's reference), her heads
 * pop in with their colours split, and once Ai is back the frame it holds her in glitches at its edges with what it is
 * containing. What Ai has won back is never touched.
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
    private val PIXEL = Color(0xFFF4F0FF)

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

    // ---- the warning windows ------------------------------------------------------------------------------------------

    /**
     * The warning windows shown at [t] (kai's reference: red translucent windows, caution signs and symbols, white
     * pixels breaking off them): a dark red glass, a bright red rim and title bar, a sign, a line of mono, scanlines. In
     * their first and last moments they slip sideways and flicker. [mono] is the app's mono face.
     */
    fun DrawScope.warnings(t: Float, measurer: TextMeasurer, mono: FontFamily) {
        val w = size.width
        val h = size.height
        val side = min(w, h)
        val fr = floor(t * 30f).toInt()
        val px = 1.dp.toPx()
        for ((i, wn) in Takeover.WARNINGS.withIndex()) {
            if (!Takeover.warningShown(wn, t)) continue
            val glitching = Takeover.warningGlitching(wn, t)
            if (glitching && rnd(fr, i * 3 + 1) < .35f) continue
            val ww = wn.size * side * (if (w > h) 1.15f else 1.45f)
            val wh = ww * .56f
            val jx = if (glitching) (rnd(fr, i * 3 + 2) - .5f) * ww * .3f else 0f
            val x = (wn.x * w - ww / 2 + jx).coerceIn(-ww * .1f, w - ww * .9f)
            val y = (wn.y * h - wh / 2).coerceIn(0f, h - wh)
            translate(x, y) { warning(wn, i, ww, wh, measurer, mono, px, fr) }
            // white pixels breaking off its corners
            for (b in 0 until 3) {
                if (rnd(fr / 3, i * 11 + b) < .45f) continue
                val bx = x + (if (b % 2 == 0) -1f else 1f) * rnd(i, 70 + b) * ww * .18f + (if (b % 2 == 0) 0f else ww)
                val by = y + rnd(i, 80 + b) * wh
                val bw = (6f + rnd(fr / 3, 90 + b) * 22f) * px
                drawRect(PIXEL, Offset(bx, by), Size(bw, bw * (.35f + rnd(i, 95 + b) * .4f)), alpha = .85f)
            }
        }
    }

    private fun DrawScope.warning(wn: Takeover.Warning, i: Int, ww: Float, wh: Float, measurer: TextMeasurer, mono: FontFamily, px: Float, fr: Int) {
        val bar = wh * .2f
        // the glass, the rim, the title bar
        drawRect(ALARM_DARK, Offset.Zero, Size(ww, wh), alpha = .42f)
        drawRect(ALARM, Offset.Zero, Size(ww, wh), alpha = .2f)
        drawRect(ALARM, Offset.Zero, Size(ww, bar), alpha = .78f)
        drawRect(ALARM, Offset.Zero, Size(ww, wh), style = Stroke(2f * px))
        drawRect(ALARM_SOFT, Offset(2f * px, 2f * px), Size(ww - 4f * px, wh - 4f * px), style = Stroke(px), alpha = .35f)
        // scanlines over the glass
        var sy = bar + 2f * px
        while (sy < wh - px) { drawRect(BLACK, Offset(px, sy), Size(ww - 2f * px, px), alpha = .16f); sy += 4f * px }
        // the title and its close box
        val titleSize = (bar * .52f).toSp()
        drawText(measurer, wn.title, Offset(bar * .3f, bar * .2f), TextStyle(fontFamily = mono, fontSize = titleSize, fontWeight = FontWeight.Bold, color = ALARM_TEXT, letterSpacing = .1.em), TextOverflow.Clip, false, 1, Size(ww - bar * 1.6f, bar))
        val cx = ww - bar * .65f
        val cy = bar / 2
        val k = bar * .2f
        drawLine(ALARM_TEXT, Offset(cx - k, cy - k), Offset(cx + k, cy + k), 1.5f * px)
        drawLine(ALARM_TEXT, Offset(cx - k, cy + k), Offset(cx + k, cy - k), 1.5f * px)
        // the sign
        val body = wh - bar
        val sg = body * .62f
        val sx = bar * .35f
        val sTop = bar + (body - sg) / 2 - body * .06f
        translate(sx, sTop) { sign(wn.sign, sg, px) }
        // the words, and a row of blocks under them
        val tx = sx + sg + bar * .45f
        val textSize = (body * .16f).toSp()
        drawText(measurer, wn.text, Offset(tx, bar + body * .22f), TextStyle(fontFamily = mono, fontSize = textSize, lineHeight = textSize * 1.2f, color = ALARM_TEXT), TextOverflow.Clip, true, 2, Size(ww - tx - bar * .3f, body * .45f))
        val blocks = 8
        val bw = (ww - tx - bar * .4f) / blocks
        for (b in 0 until blocks) {
            val on = rnd(fr / 4, i * 17 + b) < .55f
            drawRect(if (on) ALARM else ALARM_SOFT, Offset(tx + b * bw, bar + body * .74f), Size(bw * .7f, body * .09f), alpha = if (on) .9f else .3f)
        }
    }

    /** A sign of side [s]: a caution triangle, a no-entry cross, a padlock, a cat's head, hazard stripes. */
    private fun DrawScope.sign(sign: Takeover.Sign, s: Float, px: Float) {
        val line = max(2f * px, s * .07f)
        when (sign) {
            Takeover.Sign.CAUTION -> {
                val p = Path().apply { moveTo(s / 2, s * .06f); lineTo(s * .97f, s * .92f); lineTo(s * .03f, s * .92f); close() }
                drawPath(p, ALARM, alpha = .25f)
                drawPath(p, ALARM_TEXT, style = Stroke(line))
                drawRect(ALARM_TEXT, Offset(s / 2 - line / 2, s * .34f), Size(line, s * .3f))
                drawRect(ALARM_TEXT, Offset(s / 2 - line / 2, s * .72f), Size(line, line))
            }
            Takeover.Sign.DENIED -> {
                drawCircle(ALARM, s * .44f, Offset(s / 2, s / 2), alpha = .25f)
                drawCircle(ALARM_TEXT, s * .44f, Offset(s / 2, s / 2), style = Stroke(line))
                drawLine(ALARM_TEXT, Offset(s * .3f, s * .3f), Offset(s * .7f, s * .7f), line, StrokeCap.Square)
                drawLine(ALARM_TEXT, Offset(s * .3f, s * .7f), Offset(s * .7f, s * .3f), line, StrokeCap.Square)
            }
            Takeover.Sign.LOCK -> {
                drawArc(ALARM_TEXT, 180f, 180f, false, Offset(s * .27f, s * .1f), Size(s * .46f, s * .5f), style = Stroke(line))
                drawRect(ALARM_TEXT, Offset(s * .27f, s * .35f), Size(line, s * .1f))
                drawRect(ALARM_TEXT, Offset(s * .73f - line, s * .35f), Size(line, s * .1f))
                drawRect(ALARM, Offset(s * .16f, s * .44f), Size(s * .68f, s * .5f), alpha = .3f)
                drawRect(ALARM_TEXT, Offset(s * .16f, s * .44f), Size(s * .68f, s * .5f), style = Stroke(line))
                drawRect(ALARM_TEXT, Offset(s / 2 - line / 2, s * .6f), Size(line, s * .18f))
            }
            Takeover.Sign.CAT -> {
                // her head as a hazard symbol: a circle with two ears, and an X for each eye
                val ears = Path().apply {
                    moveTo(s * .14f, s * .42f); lineTo(s * .18f, s * .04f); lineTo(s * .44f, s * .24f)
                    moveTo(s * .86f, s * .42f); lineTo(s * .82f, s * .04f); lineTo(s * .56f, s * .24f)
                }
                drawPath(ears, ALARM_TEXT, style = Stroke(line))
                drawCircle(ALARM, s * .38f, Offset(s / 2, s * .56f), alpha = .25f)
                drawCircle(ALARM_TEXT, s * .38f, Offset(s / 2, s * .56f), style = Stroke(line))
                for (ex in listOf(.36f, .64f)) {
                    val k = s * .07f
                    drawLine(ALARM_TEXT, Offset(s * ex - k, s * .5f - k), Offset(s * ex + k, s * .5f + k), line * .8f)
                    drawLine(ALARM_TEXT, Offset(s * ex - k, s * .5f + k), Offset(s * ex + k, s * .5f - k), line * .8f)
                }
                drawLine(ALARM_TEXT, Offset(s * .42f, s * .72f), Offset(s * .58f, s * .72f), line * .8f)
            }
            Takeover.Sign.STRIPES -> {
                clipRect(0f, s * .1f, s, s * .9f) {
                    var x = -s
                    while (x < s) {
                        val p = Path().apply { moveTo(x, s * .9f); lineTo(x + s * .8f, s * .1f); lineTo(x + s * 1.05f, s * .1f); lineTo(x + s * .25f, s * .9f); close() }
                        drawPath(p, ALARM_TEXT, alpha = .85f)
                        x += s * .5f
                    }
                }
                drawRect(ALARM_TEXT, Offset(0f, s * .1f), Size(s, s * .8f), style = Stroke(line))
            }
        }
    }

    // ---- Ai's frame round her ------------------------------------------------------------------------------------------

    /**
     * Ai's frame round her once she is pushed into the corner (kai: "have the border of Chessy's have a glitchy effect
     * so it's like Ai is containing the glitches"): Master UI's square, paper under ink with crop marks at its corners,
     * closing in on her as [Takeover.contained] rises. Inside, she tears and splits in bands that never cross the
     * frame; at the frame, the edges jitter apart in pink and cyan and pixels break off it and are held there, hardest
     * as it closes and with each of her shoves ([Takeover.frameGlitch]).
     */
    @Composable
    fun Modifier.contained(t: () -> Float, paper: Color, ink: Color): Modifier {
        val layer = rememberGraphicsLayer()
        return drawWithContent {
            val now = t()
            val c = Takeover.contained(now)
            if (c <= 0f) { drawContent(); return@drawWithContent }
            layer.record { this@drawWithContent.drawContent() }
            val g = Takeover.frameGlitch(now)
            val slot = floor(now * 24f).toInt()
            val px = 1.dp.toPx()
            val frame = Rect(Offset.Zero, size).inflate(size.width * (.05f + .6f * (1f - c)))
            // her, held inside: torn and split in bands, never past the frame
            clipRect(frame.left, frame.top, frame.right, frame.bottom) {
                drawLayer(layer)
                val bands = (g * 6f).roundToInt()
                for (b in 0 until bands) {
                    val by = frame.top + rnd(slot, 300 + b) * frame.height
                    val bh = (.03f + rnd(slot, 310 + b) * .1f) * frame.height
                    val dx = (rnd(slot, 320 + b) - .5f) * size.width * .16f * g
                    clipRect(frame.left, by, frame.right, by + bh) {
                        drawRect(BLACK, frame.topLeft, frame.size, alpha = .35f * g)
                        translate(dx, 0f) { drawLayer(layer) }
                    }
                }
            }
            // the frame: its four sides in pieces, each jittered apart by the glitch, with pink and cyan beside them
            val lw = 2f * px
            val jit = 7f * px * g
            fun side(a: Offset, b: Offset, n: Int, salt: Int) {
                for (k in 0 until n) {
                    val u0 = k / n.toFloat()
                    val u1 = (k + 1) / n.toFloat()
                    val p0 = a + (b - a) * u0
                    val p1 = a + (b - a) * u1
                    val horiz = a.y == b.y
                    val off = (rnd(slot, salt + k) - .5f) * 2f * jit
                    val d = if (horiz) Offset(0f, off) else Offset(off, 0f)
                    if (g > .2f && rnd(slot, salt + 40 + k) < g * .5f) {
                        val s = (2f + 4f * g) * px
                        val sd = if (horiz) Offset(s, 0f) else Offset(0f, s)
                        drawLine(SPLIT_PINK, p0 + d + sd, p1 + d + sd, lw, alpha = c * .9f)
                        drawLine(SPLIT_CYAN, p0 + d - sd, p1 + d - sd, lw, alpha = c * .9f)
                    }
                    drawLine(paper, p0 + d, p1 + d, lw * 2.5f, alpha = c)
                    drawLine(ink, p0 + d, p1 + d, lw, alpha = c)
                }
            }
            val (l, tp, r, bt) = listOf(frame.left, frame.top, frame.right, frame.bottom)
            side(Offset(l, tp), Offset(r, tp), 9, 400)
            side(Offset(r, tp), Offset(r, bt), 9, 420)
            side(Offset(r, bt), Offset(l, bt), 9, 440)
            side(Offset(l, bt), Offset(l, tp), 9, 460)
            // Master UI's crop marks just outside the corners: Ai's hand
            val m = 12f * px
            val o = 5f * px
            for ((cx, cy, sx, sy) in listOf(listOf(l, tp, -1f, -1f), listOf(r, tp, 1f, -1f), listOf(r, bt, 1f, 1f), listOf(l, bt, -1f, 1f))) {
                drawLine(ink, Offset(cx + sx * o, cy + sy * o), Offset(cx + sx * (o + m), cy + sy * o), lw, alpha = c)
                drawLine(ink, Offset(cx + sx * o, cy + sy * o), Offset(cx + sx * o, cy + sy * (o + m)), lw, alpha = c)
            }
            // pixels breaking off the frame, held at its edge
            val bits = (g * 14f).roundToInt()
            for (k in 0 until bits) {
                val e = (rnd(slot, 500 + k) * 4f).toInt()
                val u = rnd(slot, 510 + k)
                val out = (2f + rnd(slot, 520 + k) * 12f * g) * px
                val bw = (3f + rnd(slot, 530 + k) * 10f) * px
                val p = when (e) {
                    0 -> Offset(l + u * frame.width, tp - out)
                    1 -> Offset(r + out - bw, tp + u * frame.height)
                    2 -> Offset(l + u * frame.width, bt + out - bw * .4f)
                    else -> Offset(l - out, tp + u * frame.height)
                }
                val col = when (k % 3) { 0 -> SPLIT_PINK; 1 -> SPLIT_CYAN; else -> PIXEL }
                drawRect(col, p, Size(bw, bw * .45f), alpha = c * .9f)
            }
        }
    }
}
