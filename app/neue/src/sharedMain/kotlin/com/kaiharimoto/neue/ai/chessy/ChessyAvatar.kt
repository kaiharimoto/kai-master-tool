package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.withTransform
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyEye
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFaces
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFit
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFrame
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyLips
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMarks
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMood
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMoodBlend
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMoods
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMouth
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyPack
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyParts
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyRig
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyWarp
import com.kaiharimoto.mastertool.core.ai.chessy.LayerPose
import com.kaiharimoto.mastertool.core.ai.chessy.Pic
import com.kaiharimoto.mastertool.core.ai.chessy.SpeechText
import com.kaiharimoto.neue.ai.chessy.ChessyInk.marks
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.res.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.ceil

/**
 * Chessy (kai, 2026-10): the cat girl who hacks her way in and takes Ai's place, drawn live from the pictures kai
 * approved in the mockup. Every layer is a mesh bent by `core/ai/chessy`'s [ChessyRig] and [ChessyWarp]: her head
 * turns in depth toward [pointer] (window pixels; null, she looks about), her hair, bows and bell swing after it, she
 * breathes, blinks, twitches an ear, and talks (Flap) while [talking].
 *
 * [expression] is the mood Ai's tracker picked; she wears it in her own parts ([ChessyMoods]) with Ai's body language
 * and marks round her ([ChessyMarks]). Her pictures are her colour, as card art's is; her marks' colours are
 * `ChessyInk`'s. One frame loop steps the rigs and bumps a counter read in the draw, so nothing recomposes as she moves.
 */
@Composable
fun ChessyAvatar(
    expression: Expression,
    size: Dp,
    modifier: Modifier = Modifier,
    talking: Boolean = false,
    pointer: () -> Offset? = { null },
    still: Boolean = false,
    rigHook: (ChessyRig) -> Unit = {},
    spoken: (() -> String)? = null,
) {
    var assets by remember { mutableStateOf(ChessyAssets.loaded) }
    LaunchedEffect(Unit) { if (assets == null) assets = ChessyAssets.load() }
    val rig = remember(still) { ChessyRig(seed = (System.nanoTime() % 100_000).toInt(), still = still) }
    val body = remember(still) { ChessyMarks(still) }
    val blend = remember(still) { ChessyMoodBlend(still) }
    val words = remember { SpeechText() }
    val tick = remember { mutableIntStateOf(0) }
    val showing by rememberUpdatedState(expression)
    val speaking by rememberUpdatedState(talking)
    val look by rememberUpdatedState(pointer)
    val hook by rememberUpdatedState(rigHook)
    val saying by rememberUpdatedState(spoken)
    val centre = remember { FloatArray(3) }
    val foils = remember { ChessyFoils() }
    LaunchedEffect(rig) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1e6f).coerceIn(0f, ChessyRig.MAX_STEP)
                last = now
                body.show(showing)
                body.step(dt / 1000f)
                // a new mood arrives behind a blink, as a good rig hides its swaps (not the first, nor a face with its eyes shut)
                val was = blend.showing
                blend.show(showing)
                if (was != null && was != showing) rig.blinkNow()
                blend.step(dt / 1000f)
                // a mood turned inward, or with its eyes shut, looks where it is going, not at the pointer
                val mood = blend.to
                val at = if (mood.follows) look() else null
                val w = centre[2]
                val ax = if (at != null && w > 0f) ((at.x - centre[0]) / (w * 1.5f)).coerceIn(-1f, 1f) else null
                val ay = if (at != null && w > 0f) ((at.y - centre[1]) / (w * 1.5f)).coerceIn(-1f, 1f) else null
                hook(rig)
                // her Flap follows the words she is saying, read here in the frame loop, never in composition
                val text = saying
                if (text != null) words.feed(if (speaking) text() else "")
                rig.step(
                    dt, ax, ay, speaking,
                    blinks = mood.blinks, blinkRate = mood.blinkRate, restX = mood.restX, restY = mood.restY,
                    breathPeriod = mood.breathPeriod, breathDepth = mood.breathDepth,
                    bodyX = body.bx, bodyY = body.by, words = if (text != null) words else null,
                )
                tick.intValue++
            }
            // calm (only her drift and breath moving): a step every ~65 ms instead of every frame, asleep every ~115; her
            // motion is computed from the real time between steps, so it is the same motion, drawn less often. On the desk
            // every frame she asks for repaints the whole window, so this is the window's rest as much as hers.
            if (!rig.frame.lively && !speaking && !body.busy && !blend.busy) {
                kotlinx.coroutines.delay(if (showing == Expression.SLEEPING) SLEEP_STEP_MS else CALM_STEP_MS)
            }
        }
    }
    Canvas(
        modifier
            .size(size)
            .graphicsLayer()
            .semantics { contentDescription = "Chessy" }
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                centre[0] = p.x + c.size.width / 2f
                centre[1] = p.y + c.size.height * .45f
                centre[2] = c.size.width.toFloat()
            },
    ) {
        tick.intValue
        val a = assets ?: return@Canvas
        drawChessy(a, rig.frame, blend.to, body, head = size.value < ChessyFit.HEAD_BELOW_DP, foils = foils, blend = blend)
    }
}

/** Her two runs of marks' foil brushes, kept with her between frames ([MarkFoils]). */
class ChessyFoils {
    val fx = MarkFoils()
    val top = MarkFoils()
}

/**
 * Between calm steps (the performance pass): with a frame's own wait, about fifteen a second. Her drift is at most some
 * seven screen pixels a second at the chat box's size, so a step moves her under half a pixel, which nobody sees as a step.
 */
private const val CALM_STEP_MS = 50L

/** Between steps while she sleeps (only her breath moves): about eight a second. */
private const val SLEEP_STEP_MS = 100L

/**
 * Where Chessy has no room to be read (a 28 dp spot in a bar): kai wants her face never drawn too small to read, so a
 * small spot shows her ears mark instead, centred in it.
 */
@Composable
fun ChessyTag(name: String, size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.size(size), contentAlignment = androidx.compose.ui.Alignment.Center) {
        ChessyMark(size * .62f, name = name)
    }
}

/**
 * Chessy in Ai's place (kai): provided at the app's root while the assistant is she ([com.kaiharimoto.mastertool.core.prefs.AiPrefs.persona]),
 * with whether she is talking (a reply streaming) and what she is saying (the reply so far, which her Flap follows).
 * Ai's live face reads it and draws her instead; null is Ai.
 */
class ChessyLook(val talking: () -> Boolean, val spoken: () -> String = { "" })

val LocalChessy = androidx.compose.runtime.staticCompositionLocalOf<ChessyLook?> { null }

/** Chessy's pack and pictures, read once for the app's lifetime. */
class ChessyAssets(val pack: ChessyPack, val parts: ChessyParts?, val images: Map<String, ImageBitmap>) {
    private val meshes = HashMap<Pair<Pic, Float>, Mesh>()

    /** [p]'s grid with cells of [cell] sheet pixels, made once per size of cell. */
    internal fun mesh(p: Pic, cell: Float = Mesh.CELL): Mesh = meshes.getOrPut(p to cell) { Mesh(p, cell) }

    companion object {
        var loaded: ChessyAssets? = null
            private set
        private val lock = Mutex()

        suspend fun load(): ChessyAssets? = lock.withLock {
            loaded ?: runCatching {
                val pack = ChessyPack.read(Res.readBytes("${ChessyPack.DIR}/chessy.json").decodeToString())
                // her mood parts; without them she still wears the Grin, the Fangs and the Tongue whole
                val parts = runCatching { ChessyParts.read(Res.readBytes("${ChessyPack.DIR}/moods.json").decodeToString()) }.getOrNull()
                val files = pack.files + parts?.files.orEmpty()
                val images = files.associateWith { f -> decodePicture(Res.readBytes("${ChessyPack.DIR}/$f"))!! }
                ChessyAssets(pack, parts, images)
            }.getOrNull().also { loaded = it }
        }
    }
}

/** A picture's grid: where each vertex samples it, the triangles, and room for where they land each frame. */
internal class Mesh(val pic: Pic, cell: Float = CELL) {
    val nx = maxOf(2, ceil(pic.w / cell).toInt())
    val ny = maxOf(2, ceil(pic.h / cell).toInt())
    val count = (nx + 1) * (ny + 1)
    val texs = FloatArray(count * 2)
    val sheet = FloatArray(count * 2)
    val positions = FloatArray(count * 2)
    val colors = IntArray(count)
    val indices = ShortArray(nx * ny * 6)

    init {
        var v = 0
        for (j in 0..ny) for (i in 0..nx) {
            val u = pic.w * i / nx.toFloat()
            val t = pic.h * j / ny.toFloat()
            texs[v * 2] = u; texs[v * 2 + 1] = t
            sheet[v * 2] = pic.x + u; sheet[v * 2 + 1] = pic.y + t
            v++
        }
        var k = 0
        for (j in 0 until ny) for (i in 0 until nx) {
            val a = j * (nx + 1) + i
            indices[k++] = a.toShort(); indices[k++] = (a + 1).toShort(); indices[k++] = (a + nx + 1).toShort()
            indices[k++] = (a + 1).toShort(); indices[k++] = (a + nx + 2).toShort(); indices[k++] = (a + nx + 1).toShort()
        }
    }

    companion object {
        /** Sheet pixels a cell spans at its finest: fine enough for the turn's curve drawn large. */
        const val CELL = 24f

        /**
         * The cell for a drawing at [scale] screen pixels per sheet pixel (the performance pass): about [ON_SCREEN] screen
         * pixels a side, never finer than [CELL], in a few steps so the grids are shared. Her 132 dp in the chat box is a
         * grid some fifteen times smaller than her whole sheet's, and the warp's curve is still under a pixel.
         */
        fun cellFor(scale: Float): Float {
            val want = if (scale > 0f) ON_SCREEN / scale else CELL
            return STEPS.lastOrNull { it <= want } ?: CELL
        }

        const val ON_SCREEN = 8f
        private val STEPS = floatArrayOf(CELL, 32f, 48f, 64f, 96f)
    }
}

private val pose = LayerPose()


/**
 * Chessy as one frame shows her wearing [mood], fitted to the canvas: back to front, her face's parts over the Grin her
 * face layer wears, [body]'s lean and marks round her (the studio draws set poses with it; a null [body] stands still).
 * [blend], when given, is her way from one mood to the next: the old mood's parts fading under the new one's, her brows
 * and ears where its springs have them (a null [blend] wears [mood] whole).
 */
fun DrawScope.drawChessy(
    a: ChessyAssets,
    f: ChessyFrame,
    mood: ChessyMood,
    body: ChessyMarks? = null,
    head: Boolean = false,
    foils: ChessyFoils? = null,
    blend: ChessyMoodBlend? = null,
) {
    val pack = a.pack
    // her whole figure, or (small, the bar's and the composer's) her head alone: ears to chin
    val fit = ChessyFit.of(size.width, size.height, head)
    val s = fit[0]
    val ox = fit[1]
    val oy = fit[2]
    val cell = Mesh.cellFor(s)
    val neckX = pack.sphere.cx
    val neckY = pack.sphere.cy + pack.sphere.ry * .95f
    val parts = a.parts
    // the moods drawn: the one she is in, and the one she is leaving while it fades
    val to = blend?.to ?: mood
    val from = blend?.from ?: mood
    val toA = blend?.toAlpha ?: 1f
    val fromA = if (blend == null || from === to) 0f else blend.fromAlpha
    val ears = blend?.ears ?: mood.ears
    val browTilt = blend?.browTilt ?: mood.browTilt
    val browLiftL = blend?.browLiftL ?: mood.browLiftL
    val browLiftR = blend?.browLiftR ?: mood.browLiftR
    // without the mood parts she shows the nearest whole sheet face
    val face = if (parts == null) ChessyFaces.ofMood(mood) else ChessyFaces.GRIN
    fun pic(p: Pic?, layerId: String, alpha: Float = 1f, tune: (LayerPose.() -> Unit)? = null) {
        p ?: return
        if (alpha <= 0f) return
        val img = a.images[p.file] ?: return
        val m = a.mesh(p, cell)
        ChessyWarp.pose(pack, layerId, p, f, if (layerId == "tongue") ChessyFaces.TONGUE else face, pose)
        if (layerId == "ear-l") pose.rot -= ears * DEG
        if (layerId == "ear-r") pose.rot += ears * DEG
        tune?.invoke(pose)
        for (v in 0 until m.count) {
            val x = m.sheet[v * 2]
            val y = m.sheet[v * 2 + 1]
            ChessyWarp.place(x, y, pose, f, pack.sphere, neckX, neckY, m.positions, v * 2)
            m.positions[v * 2] = ox + m.positions[v * 2] * s
            m.positions[v * 2 + 1] = oy + m.positions[v * 2 + 1] * s
            val l = (ChessyWarp.shade(x, y, f, pack.sphere) * 255f).toInt().coerceIn(0, 255)
            m.colors[v] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
        drawMesh(img, m.positions, m.texs, m.colors, m.indices, m.count, alpha)
    }
    val P = pack.parts
    fun smile(alpha: Float = 1f) = pic(P.cline, "features", alpha) { sizeX = pack.closedCx; sizeK = pack.closedLength; sizeDy = pack.closedDy }
    fun side(sides: com.kaiharimoto.mastertool.core.ai.chessy.Sides?, left: Boolean) = sides?.let { if (left) it.l else it.r }
    fun eye(e: ChessyEye, left: Boolean, alpha: Float) = when (e) {
        ChessyEye.SLY -> Unit
        ChessyEye.WIDE -> pic(side(parts?.eyes?.get(ChessyFaces.FANGS), left), "features", alpha)
        ChessyEye.SHUT -> pic(side(parts?.eyes?.get(ChessyFaces.TONGUE), left), "features", alpha)
        ChessyEye.CLOSED -> pic(side(parts?.lids?.get(ChessyFaces.GRIN), left), "features", alpha)
    }
    fun lid(e: ChessyEye, left: Boolean) {
        // the blink's lid: shut at once, opened over its last moments (its alpha), only on the mood she is in
        if (f.blink && to.blinks) pic(side(parts?.lids?.get(to.lidOf(e)), left), "features", f.lidAlpha)
    }
    fun lips(m: ChessyMood, alpha: Float) {
        if (parts == null) return
        when (m.lips) {
            ChessyLips.GRIN -> Unit
            ChessyLips.FANGS -> pic(parts.mouths[ChessyFaces.FANGS], "features", alpha)
            ChessyLips.TONGUE -> pic(parts.mouths[ChessyFaces.TONGUE], "features", alpha)
            ChessyLips.SMILE -> { pic(P.closedBy[ChessyFaces.GRIN], "features", alpha); smile(alpha) }
            ChessyLips.FROWN -> { pic(P.closedBy[ChessyFaces.GRIN], "features", alpha); pic(parts.frown, "features", alpha) }
        }
    }
    fun brow(m: ChessyMood, left: Boolean, alpha: Float) {
        val sides = parts?.brows?.get(m.brows) ?: return
        val b = if (left) sides.l else sides.r
        pic(b, "brows", alpha) {
            // each brow turns about its outer end: the anger's inner ends down, the worry's up
            rot = (if (left) 1f else -1f) * browTilt * DEG
            rcx = if (left) b.x.toFloat() else (b.x + b.w).toFloat()
            rcy = b.y + b.h / 2f
            sizeDy = if (left) browLiftL else browLiftR
        }
    }
    fun tongue(m: ChessyMood, alpha: Float) {
        if (m.lips == ChessyLips.TONGUE) pic(pack.faces[ChessyFaces.TONGUE]?.tongue, "tongue", alpha)
    }
    fun drawn() {
        val fc = pack.faces[face] ?: pack.faces.values.first()
        for (l in pack.layers) {
            if (!l.perface) {
                pic(l.pic, l.id)
                if (f.rimMix > 0f && l.rim != null) pic(l.rim, l.id, f.rimMix)
                continue
            }
            when (l.id) {
                "features" -> {
                    pic(fc.features, "features")
                    if (parts == null) {
                        when (f.mouth) {
                            ChessyMouth.OPEN -> { pic(P.closedBy[face], "features"); pic(P.openBy[face] ?: P.talk.firstOrNull(), "features") }
                            ChessyMouth.CLOSED -> { pic(P.closedBy[face] ?: P.closed, "features"); smile() }
                            ChessyMouth.OWN -> Unit
                        }
                        if (f.blink && face != ChessyFaces.TONGUE) pic(P.blinkBy[face] ?: P.blink, "features", f.lidAlpha)
                        continue
                    }
                    // the eyes first, then the mouth, so a mouth always wins where the two meet; the blink last. While a
                    // mood fades the old one's parts are drawn under the new one's (one of the two is always whole)
                    if (fromA > 0f) { eye(from.eyeL, left = true, fromA); eye(from.eyeR, left = false, fromA) }
                    eye(to.eyeL, left = true, toA)
                    eye(to.eyeR, left = false, toA)
                    // the mouth: talking, the Grin's closed and open mouths; else the mood's own
                    when (f.mouth) {
                        ChessyMouth.OPEN -> { pic(P.closedBy[ChessyFaces.GRIN], "features"); pic(P.openBy[ChessyFaces.GRIN], "features") }
                        ChessyMouth.CLOSED -> { pic(P.closedBy[ChessyFaces.GRIN], "features"); smile() }
                        ChessyMouth.OWN -> { if (fromA > 0f) lips(from, fromA); lips(to, toA) }
                    }
                    lid(to.eyeL, left = true)
                    lid(to.eyeR, left = false)
                }
                "tongue" -> if (f.mouth == ChessyMouth.OWN) {
                    if (parts == null) { if (face == ChessyFaces.TONGUE) pic(pack.faces[ChessyFaces.TONGUE]?.tongue, "tongue") } else {
                        if (fromA > 0f) tongue(from, fromA)
                        tongue(to, toA)
                    }
                }
                "brows" -> if (parts == null) pic(fc.brows, "brows") else {
                    // brows from another face fade as the parts do; the same face's brows simply travel
                    val same = fromA <= 0f || from.brows == to.brows
                    if (!same) { brow(from, left = true, fromA); brow(from, left = false, fromA) }
                    brow(to, left = true, if (same) 1f else toA)
                    brow(to, left = false, if (same) 1f else toA)
                }
            }
        }
    }
    if (body == null) {
        drawn()
        return
    }
    // the foil on her marks catches a light that drifts slowly round them
    val clock = System.nanoTime() / 1e9
    val light = Offset((kotlin.math.sin(clock * .6) * .7).toFloat(), (kotlin.math.cos(clock * .45) * .7).toFloat())
    // Ai's body language on her: a hop, a lean about her neck, a squash; and her marks, riding her head
    val px = ox + neckX * s
    val py = oy + neckY * s
    withTransform({
        translate(body.bx * s, body.by * s)
        rotate(body.rot, Offset(px, py))
        scale(body.sx, body.sy, Offset(px, py))
    }) {
        drawn()
        marks(body.fx, s, ox, oy, light, foils?.fx)
    }
    marks(body.top, s, ox, oy, light, foils?.top)
}

private const val DEG = (PI / 180).toFloat()
