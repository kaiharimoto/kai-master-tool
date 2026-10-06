package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.chessy.Takeover.rnd
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.Face
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftItem
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftKind
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftMat
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftModel
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftTex
import com.kaiharimoto.mastertool.core.ai.chessy.toys.TOY_LIGHT
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.V3
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.ai.avatar.AiAvatar
import com.kaiharimoto.neue.cards.ClassicCardBack
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.HoloCache
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cards.drawFoilStar
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Chessy's gifts drawn (kai, 1.1.31; "everything should be 3D if possible"): their colour lives here, a file
 * `MasterUiLawTest` names, as her own drawings and the takeover's do. Every gift is a solid ([GiftModel]) painted face by
 * face: turned, its faces seen from the front sorted far to near and filled in their material's colour, lit from the
 * toys' light; the crystal heart's facets throw a different colour each as it turns and glints in foil; a face that
 * carries a picture (the card's face and back, the polaroid, the note) has its picture laid onto it by the affine map
 * of its corners, which is exact for a flat face seen straight on, so the picture turns with the solid. The pictures are
 * drawn by the app itself, out of sight, into layers ([GiftSolid]).
 */
internal object GiftInk {
    private val BOX = Color(0xFFB9A3F0)
    private val RIBBON = Color(0xFFFFFFFF)
    private val WRAPPER = Color(0xFFF6E7D2)
    private val FROSTING = Color(0xFFFFA9C9)
    private val CHERRY = Color(0xFFE0284A)
    private val STEM = Color(0xFF5C8A3A)
    private val SPRINKLES = listOf(Color(0xFFFF5FA2), Color(0xFF5FB8FF), Color(0xFFFFD34F), Color(0xFFFFFFFF))
    private val PHOTO_BACK = Color(0xFFF4F1EA)
    private val NOTE = Color(0xFFFFF8F0)
    val PINK = Color(0xFFFF5F9E)
    private val SPLIT_PINK = Color(0xFFFF2878)
    private val SPLIT_CYAN = Color(0xFF3CDCFF)

    /** A material's colour, before the light. */
    fun colour(mat: GiftMat, c: MuColors): Color = when (mat) {
        GiftMat.PAPER, GiftMat.CARD_EDGE -> c.paper
        GiftMat.INK -> c.ink
        GiftMat.BOX -> BOX
        GiftMat.RIBBON -> RIBBON
        GiftMat.CRYSTAL -> Color.White
        GiftMat.WRAPPER -> WRAPPER
        GiftMat.FROSTING -> FROSTING
        GiftMat.CHERRY -> CHERRY
        GiftMat.STEM -> STEM
        GiftMat.SPRINKLE_PINK -> SPRINKLES[0]
        GiftMat.SPRINKLE_BLUE -> SPRINKLES[1]
        GiftMat.SPRINKLE_YELLOW -> SPRINKLES[2]
        GiftMat.SPRINKLE_WHITE -> SPRINKLES[3]
        GiftMat.PHOTO_BACK -> PHOTO_BACK
        GiftMat.NOTE -> NOTE
        GiftMat.WOOD -> c.paper
        GiftMat.KNOB -> c.ink
    }

    /** [base] under the light [lit] (−1 to 1): a shadow side a third darker, a lit side a touch brighter. */
    private fun shade(base: Color, lit: Double): Color {
        val k = (.66 + .34 * lit.coerceIn(-1.0, 1.0)).toFloat()
        return Color((base.red * k).coerceIn(0f, 1f), (base.green * k).coerceIn(0f, 1f), (base.blue * k).coerceIn(0f, 1f), base.alpha)
    }

    /** A colour of the rainbow, [hue] in degrees, softened toward white by [soft]. */
    private fun prism(hue: Double, soft: Float): Color {
        val h = ((hue % 360) + 360) % 360 / 60
        val x = (1 - abs(h % 2 - 1)).toFloat()
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(1f, x, 0f); 1 -> Triple(x, 1f, 0f); 2 -> Triple(0f, 1f, x)
            3 -> Triple(0f, x, 1f); 4 -> Triple(x, 0f, 1f); else -> Triple(1f, 0f, x)
        }
        return Color(r + (1 - r) * soft, g + (1 - g) * soft, b + (1 - b) * soft)
    }

    /** Where the eye stands, in model units in front of the solid: far enough that the perspective is gentle. */
    private const val EYE = 5.0

    private class Shown(val depth: Double, val part: Int, val face: Face, val n: V3)

    /**
     * [model] drawn about ([cx], [cy]) at [unit] pixels a model unit, turned by [q]; [shift] moves a part before it is
     * turned (the chest's drawer); [tex] gives a face's picture, if made; [t] is the clock in seconds (the crystal's
     * play of colour); a [silhouette] is every face flat in faint ink, for what is not yet received.
     */
    fun DrawScope.drawModel(
        model: GiftModel, cx: Float, cy: Float, unit: Float, q: Quat, c: MuColors, t: Float,
        tex: (GiftTex) -> GraphicsLayer? = { null }, shift: (Int) -> V3 = { V3.ZERO }, silhouette: Boolean = false,
        stars: HoloCache? = null, alpha: Float = 1f,
    ) {
        val shown = ArrayList<Shown>()
        val turned = model.parts.mapIndexed { i, m -> val s = shift(i); Array(m.v.size) { k -> q.rotate(m.v[k] + s) } }
        // a gentle perspective, the eye [EYE] units out: nearer parts a little larger, so a box reads as a box. Its lowest
        // point keeps the place the flat drawing gives it, so a gift on the floor stays standing on it.
        val low = turned.flatMap { it.asList() }.maxByOrNull { it.y }
        val pin = if (low == null) 0f else (low.y * unit * (1 - EYE / (EYE - low.z))).toFloat()
        fun sx(v: V3) = cx + (v.x * EYE / (EYE - v.z)).toFloat() * unit
        fun sy(v: V3) = cy + pin + (v.y * EYE / (EYE - v.z)).toFloat() * unit
        for ((i, m) in model.parts.withIndex()) {
            val p = turned[i]
            for (f in m.faces) {
                val a = p[f.idx[0]]
                val n = (p[f.idx[1]] - a) cross (p[f.idx[2]] - a)
                // seen when it faces the eye, not only the screen
                if (n dot (V3(0.0, 0.0, EYE) - a) <= 1e-9) continue
                var z = 0.0
                for (k in f.idx) z += p[k].z
                shown += Shown(z / f.idx.size, i, f, n.normalized())
            }
        }
        shown.sortBy { it.depth }
        val px = density
        val path = Path()
        val brightest = if (silhouette) emptyList() else shown.filter { it.face.mat == GiftMat.CRYSTAL }.sortedByDescending { it.n dot TOY_LIGHT }.take(2)
        for (s in shown) {
            val p = turned[s.part]
            path.reset()
            for ((k, idx) in s.face.idx.withIndex()) {
                val x = sx(p[idx])
                val y = sy(p[idx])
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            if (silhouette) {
                // one flat grey, opaque, so faces overlapping read as one shape
                drawPath(path, lerp(c.paper, c.ink, .16f), alpha = alpha)
                continue
            }
            val lit = s.n dot TOY_LIGHT
            if (s.face.mat == GiftMat.CRYSTAL) {
                // each facet its own colour, turning with it and with the clock; a white edge and a sheen where it faces the light
                val hue = atan2(s.n.y, s.n.x) * 180 / PI + t * 40 + s.n.z * 140
                drawPath(path, prism(hue, .42f + .3f * lit.toFloat().coerceIn(0f, 1f)), alpha = .86f * alpha)
                val spec = max(0.0, lit).pow(10).toFloat()
                if (spec > .02f) drawPath(path, Color.White, alpha = spec * .8f * alpha)
                drawPath(path, Color.White, style = Stroke(1.1f * px, join = StrokeJoin.Round), alpha = .7f * alpha)
                continue
            }
            val base = colour(s.face.mat, c)
            drawPath(path, shade(base, lit), alpha = alpha)
            val pic = s.face.tex?.let(tex)
            if (pic != null && pic.size.width > 0) {
                val a = Offset(sx(p[s.face.idx[0]]), sy(p[s.face.idx[0]]))
                val b = Offset(sx(p[s.face.idx[1]]), sy(p[s.face.idx[1]]))
                val d = Offset(sx(p[s.face.idx[3]]), sy(p[s.face.idx[3]]))
                val w = pic.size.width.toFloat()
                val h = pic.size.height.toFloat()
                val m = Matrix()
                m.values[Matrix.ScaleX] = (b.x - a.x) / w
                m.values[Matrix.SkewY] = (b.y - a.y) / w
                m.values[Matrix.SkewX] = (d.x - a.x) / h
                m.values[Matrix.ScaleY] = (d.y - a.y) / h
                m.values[Matrix.TranslateX] = a.x
                m.values[Matrix.TranslateY] = a.y
                withTransform({ transform(m) }) { drawLayer(pic) }
                // the card's foil sparkles: a star that comes and goes on its face
                if (s.face.tex == GiftTex.CARD_FRONT && stars != null) {
                    val k = (sin(t * 2.3) * .5 + .5).toFloat()
                    val at = a + (b - a) * .74f + (d - a) * .2f
                    drawFoilStar(at, unit * .07f * (.4f + .6f * k), Offset(k * 2 - 1, -.4f), stars)
                    val at2 = a + (b - a) * .22f + (d - a) * .62f
                    val k2 = (sin(t * 1.7 + 2.0) * .5 + .5).toFloat()
                    drawFoilStar(at2, unit * .05f * (.3f + .7f * k2), Offset(-k2, k2 * 2 - 1), stars)
                }
                // a picture under the light: its shadow side darkens with the face
                if (lit < .55) drawPath(path, Color.Black, alpha = ((.55 - lit) * .35).toFloat().coerceIn(0f, .4f) * alpha)
            }
            drawPath(path, c.ink.copy(alpha = .35f), style = Stroke(.8f * px, join = StrokeJoin.Round), alpha = alpha)
        }
        // the crystal glints in foil on its brightest facets
        if (stars != null) for ((k, s) in brightest.withIndex()) {
            val p = turned[s.part]
            var x = 0f
            var y = 0f
            for (idx in s.face.idx) { x += sx(p[idx]); y += sy(p[idx]) }
            val n = s.face.idx.size
            val pulse = (sin(t * 3.1 + k * 2.1) * .5 + .5).toFloat()
            drawFoilStar(Offset(x / n, y / n), unit * .09f * (.35f + .65f * pulse), Offset(s.n.x.toFloat(), s.n.y.toFloat()), stars)
        }
    }

    /**
     * The box coming into being (kai: "digitally create a present … in her glitchy effect"): over [made] 0 to 1 it builds
     * out of bands torn sideways, in her pink and cyan split, each band settling once its turn comes; whole, it is drawn
     * plainly.
     */
    @Composable
    fun Modifier.glitchIn(made: () -> Float, slot: () -> Int): Modifier {
        val layer = rememberGraphicsLayer()
        val tints = remember { listOf(SPLIT_PINK, SPLIT_CYAN).map { Paint().apply { colorFilter = ColorFilter.tint(it, BlendMode.SrcIn) } } }
        return drawWithContent {
            val m = made()
            if (m >= 1f) { drawContent(); return@drawWithContent }
            layer.record { this@drawWithContent.drawContent() }
            val s = slot()
            val bands = 14
            val bh = size.height / bands
            for (b in 0 until bands) {
                val due = rnd(b, 41) * .8f
                if (m < due) {
                    // not yet: now and then a flicker of it, torn wide
                    if (rnd(s, b * 3 + 1) > .2f + m * .5f) continue
                }
                val settled = ((m - due) / .2f).coerceIn(0f, 1f)
                val dx = (rnd(s, b * 3 + 2) - .5f) * size.width * .5f * (1f - settled)
                val off = (2f + 10f * (1f - settled)) * density
                clipRect(-size.width, b * bh, size.width * 2, (b + 1) * bh) {
                    for ((i, p) in tints.withIndex()) {
                        p.alpha = .75f * (1f - settled * .7f)
                        drawIntoCanvas { cv ->
                            cv.saveLayer(Rect(Offset(-size.width, 0f), Size(size.width * 3, size.height)), p)
                            translate(dx + if (i == 0) off else -off, 0f) { drawLayer(layer) }
                            cv.restore()
                        }
                    }
                    translate(dx, 0f) { drawLayer(layer) }
                }
            }
        }
    }
}

/**
 * A gift as a solid on screen: [model] turned by [pose], filling [fill] of this box (or [unit] pixels a model unit),
 * its pictures made out of sight first — a Maliss [card]'s face in foil and its back, the polaroid of her and Ai, the
 * note's cover and her words inside. [frame] is read in the draw (the room's clock); [silhouette] draws what is not yet
 * received.
 */
@Composable
internal fun GiftSolid(
    model: GiftModel,
    item: GiftItem?,
    modifier: Modifier,
    pose: () -> Quat,
    frame: () -> Int,
    clock: () -> Float,
    fill: Float = .82f,
    unit: (() -> Float)? = null,
    silhouette: Boolean = false,
    shift: (Int) -> V3 = { V3.ZERO },
    card: Card? = null,
    alpha: () -> Float = { 1f },
) {
    val c = Mu.colors
    val front = rememberGraphicsLayer()
    val back = rememberGraphicsLayer()
    var made by remember { mutableIntStateOf(0) } // a picture was made: the solid redraws with it
    val stars = remember { HoloCache() }
    if (!silhouette && item != null) {
        when (item.kind) {
            GiftKind.CARD -> {
                Picture(front, 236.dp, 344.dp, { made++ }) {
                    if (card != null) NeueCard(card, Modifier.fillMaxSize(), foil = Foils.HOLO)
                    else Box(Modifier.fillMaxSize().background(c.paper).border(2.dp, c.ink), contentAlignment = Alignment.Center) { BasicText(item.name, style = TextStyle(color = c.ink, fontSize = 14.sp, textAlign = TextAlign.Center)) }
                }
                Picture(back, 236.dp, 344.dp, { made++ }) { ClassicCardBack(Modifier.fillMaxSize()) }
            }
            GiftKind.PHOTO -> Picture(front, 252.dp, 300.dp, { made++ }) { Polaroid() }
            GiftKind.NOTE -> {
                Picture(front, 212.dp, 300.dp, { made++ }) { NoteCover() }
                Picture(back, 212.dp, 300.dp, { made++ }) { NoteInside(item.words) }
            }
            else -> Unit
        }
    }
    Canvas(modifier) {
        frame()
        made
        val u = unit?.invoke() ?: (min(size.width, size.height) * fill / max(model.hi.y - model.lo.y, model.hi.x - model.lo.x).toFloat())
        with(GiftInk) {
            drawModel(
                model, size.width / 2f, size.height / 2f, u, pose(), c, clock(),
                tex = { t -> when (t) { GiftTex.CARD_FRONT, GiftTex.PHOTO, GiftTex.NOTE_OUT -> front; GiftTex.CARD_BACK, GiftTex.NOTE_IN -> back } },
                shift = shift, silhouette = silhouette, stars = stars, alpha = alpha(),
            )
        }
    }
}

/** A picture drawn out of sight at [w] by [h] into [layer], to be laid onto a face; [made] after each drawing. */
@Composable
private fun Picture(layer: GraphicsLayer, w: Dp, h: Dp, made: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.layout { m, _ ->
            val p = m.measure(Constraints.fixed(w.roundToPx(), h.roundToPx()))
            layout(0, 0) { p.place(0, 0) }
        },
    ) {
        Box(Modifier.size(w, h).drawWithContent { layer.record { this@drawWithContent.drawContent() }; made() }) { content() }
    }
}

/** The polaroid's face: its white frame, her and Ai cheek to cheek on a lilac sky, a heart in the corner, "us ♡". */
@Composable
private fun Polaroid() {
    val fonts = LocalMuFonts.current
    Column(Modifier.fillMaxSize().background(Color.White).padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
        Box(Modifier.fillMaxWidth().size(width = 224.dp, height = 214.dp).background(PhotoSky), contentAlignment = Alignment.BottomCenter) {
            Row(horizontalArrangement = Arrangement.spacedBy((-18).dp), verticalAlignment = Alignment.Bottom) {
                CompositionLocalProvider(LocalChessy provides null) {
                    Box(Modifier.padding(bottom = 18.dp)) { AiAvatar(Expression.DELIGHTED, 84.dp) }
                }
                ChessyAvatar(Expression.WINK, ChessySizes.MIN, still = true)
            }
            Canvas(Modifier.fillMaxSize()) { heart(Offset(size.width * .86f, size.height * .14f), size.width * .07f, GiftInk.PINK) }
        }
        BasicText(
            "us ♡", Modifier.fillMaxWidth().padding(top = 18.dp),
            style = TextStyle(fontFamily = fonts.sans, fontStyle = FontStyle.Italic, fontSize = 22.sp, color = Color(0xFF3A2F4A), textAlign = TextAlign.Center),
        )
    }
}

private val PhotoSky = Color(0xFFEDE4FF)

/** The note's cover: "Thank you" in her hand and a heart. */
@Composable
private fun NoteCover() {
    val fonts = LocalMuFonts.current
    Box(Modifier.fillMaxSize().background(Color(0xFFFFF8F0)).border(1.dp, Color(0xFFE9D9C8)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(64.dp)) { heart(Offset(size.width / 2, size.height / 2), size.width * .42f, GiftInk.PINK) }
            BasicText("Thank you", Modifier.padding(top = 14.dp), style = TextStyle(fontFamily = fonts.sans, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 30.sp, color = Color(0xFF3A2F4A)))
        }
    }
}

/** Inside the note: her words, signed, with a little heart. */
@Composable
private fun NoteInside(words: String) {
    val fonts = LocalMuFonts.current
    Box(Modifier.fillMaxSize().background(Color(0xFFFFF8F0)).border(1.dp, Color(0xFFE9D9C8)).padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(words, style = TextStyle(fontFamily = fonts.sans, fontStyle = FontStyle.Italic, fontSize = 21.sp, lineHeight = 28.sp, color = Color(0xFF3A2F4A), textAlign = TextAlign.Center))
            BasicText("— Chessy ฅ^•ﻌ•^ฅ", style = TextStyle(fontFamily = fonts.sans, fontSize = 15.sp, color = GiftInk.PINK))
        }
    }
}

/** A heart of [r] about [at], filled in [colour]. */
private fun DrawScope.heart(at: Offset, r: Float, colour: Color) {
    val p = Path().apply {
        moveTo(at.x, at.y + r * .9f)
        cubicTo(at.x - r * 1.4f, at.y - r * .1f, at.x - r * .7f, at.y - r * 1.1f, at.x, at.y - r * .35f)
        cubicTo(at.x + r * .7f, at.y - r * 1.1f, at.x + r * 1.4f, at.y - r * .1f, at.x, at.y + r * .9f)
        close()
    }
    drawPath(p, colour)
}

/** The glitch's frame slot for [t] seconds, as the takeover counts them. */
internal fun glitchSlot(t: Float): Int = floor(t * 24f).toInt()
