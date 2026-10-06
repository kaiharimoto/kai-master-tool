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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.withTransform
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyEye
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFaces
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFrame
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyLips
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMarks
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMood
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMoods
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMouth
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyPack
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyParts
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyRig
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyWarp
import com.kaiharimoto.mastertool.core.ai.chessy.LayerPose
import com.kaiharimoto.mastertool.core.ai.chessy.Pic
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
) {
    var assets by remember { mutableStateOf(ChessyAssets.loaded) }
    LaunchedEffect(Unit) { if (assets == null) assets = ChessyAssets.load() }
    val rig = remember(still) { ChessyRig(seed = (System.nanoTime() % 100_000).toInt(), still = still) }
    val body = remember(still) { ChessyMarks(still) }
    val tick = remember { mutableIntStateOf(0) }
    val showing by rememberUpdatedState(expression)
    val speaking by rememberUpdatedState(talking)
    val look by rememberUpdatedState(pointer)
    val centre = remember { FloatArray(3) }
    LaunchedEffect(rig) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1e6f).coerceIn(0f, 64f)
                last = now
                val at = look()
                val w = centre[2]
                val ax = if (at != null && w > 0f) ((at.x - centre[0]) / (w * 1.5f)).coerceIn(-1f, 1f) else null
                val ay = if (at != null && w > 0f) ((at.y - centre[1]) / (w * 1.5f)).coerceIn(-1f, 1f) else null
                body.show(showing)
                body.step(dt / 1000f)
                rig.step(dt, ax, ay, speaking, blinks = ChessyMoods.of(showing).blinks)
                tick.intValue++
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
        drawChessy(a, rig.frame, ChessyMoods.of(showing), body, head = size.value < HEAD_BELOW_DP)
    }
}

/**
 * Where Chessy has no room to be read (a 28 dp spot in a bar), her initial in a square, in ink: kai wants her face
 * never drawn too small to read, so a small spot names her instead.
 */
@Composable
fun ChessyTag(name: String, size: Dp, modifier: Modifier = Modifier) {
    val c = com.kaiharimoto.neue.theme.Mu.colors
    androidx.compose.foundation.layout.Box(
        modifier.size(size).border(1.dp, c.ink).semantics { contentDescription = name },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        com.kaiharimoto.neue.kit.Mono(name.take(1).uppercase(), color = c.ink, size = (size.value * .42f).sp)
    }
}

/**
 * Chessy in Ai's place (kai): provided at the app's root while the assistant is she ([com.kaiharimoto.mastertool.core.prefs.AiPrefs.persona]),
 * with whether she is talking (a reply streaming). Ai's live face reads it and draws her instead; null is Ai.
 */
class ChessyLook(val talking: () -> Boolean)

val LocalChessy = androidx.compose.runtime.staticCompositionLocalOf<ChessyLook?> { null }

/** Chessy's pack and pictures, read once for the app's lifetime. */
class ChessyAssets(val pack: ChessyPack, val parts: ChessyParts?, val images: Map<String, ImageBitmap>) {
    private val meshes = HashMap<Pic, Mesh>()
    internal fun mesh(p: Pic): Mesh = meshes.getOrPut(p) { Mesh(p) }

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
internal class Mesh(val pic: Pic) {
    val nx = maxOf(2, ceil(pic.w / CELL).toInt())
    val ny = maxOf(2, ceil(pic.h / CELL).toInt())
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
        /** Sheet pixels a cell spans: fine enough for the turn's curve, few enough for the bar's glyph. */
        const val CELL = 24f
    }
}

private val pose = LayerPose()

/**
 * Her head on the sheet, ears to the tips of her hair and the bell under her chin (left, top, right, bottom): what a
 * small avatar shows, everything of her inside its box so nothing hangs over what is below it.
 */
private val HEAD = floatArrayOf(17f, 80f, 1262f, 1660f)

/** Below this she is drawn as her head alone. */
private const val HEAD_BELOW_DP = 150f

/**
 * Chessy as one frame shows her wearing [mood], fitted to the canvas: back to front, her face's parts over the Grin her
 * face layer wears, [body]'s lean and marks round her (the studio draws set poses with it; a null [body] stands still).
 */
fun DrawScope.drawChessy(a: ChessyAssets, f: ChessyFrame, mood: ChessyMood, body: ChessyMarks? = null, head: Boolean = false) {
    val pack = a.pack
    // her whole figure, or (small, the bar's and the composer's) her head alone: ears to chin
    val l0 = if (head) HEAD[0] else 0f
    val t0 = if (head) HEAD[1] else 0f
    val bw = if (head) HEAD[2] - HEAD[0] else pack.w.toFloat()
    val bh = if (head) HEAD[3] - HEAD[1] else pack.h.toFloat()
    val s = minOf(size.width / bw, size.height / bh)
    val ox = (size.width - bw * s) / 2f - l0 * s
    val oy = (size.height - bh * s) / 2f - t0 * s
    val neckX = pack.sphere.cx
    val neckY = pack.sphere.cy + pack.sphere.ry * .95f
    val parts = a.parts
    // without the mood parts she shows the nearest whole sheet face
    val face = if (parts == null) ChessyFaces.ofMood(mood) else ChessyFaces.GRIN
    fun pic(p: Pic?, layerId: String, alpha: Float = 1f, tune: (LayerPose.() -> Unit)? = null) {
        p ?: return
        val img = a.images[p.file] ?: return
        val m = a.mesh(p)
        ChessyWarp.pose(pack, layerId, p, f, if (layerId == "tongue") ChessyFaces.TONGUE else face, pose)
        if (layerId == "ear-l") pose.rot -= mood.ears * DEG
        if (layerId == "ear-r") pose.rot += mood.ears * DEG
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
    fun smile() = pic(P.cline, "features") { sizeX = pack.closedCx; sizeK = pack.closedLength; sizeDy = pack.closedDy }
    fun eye(e: ChessyEye, left: Boolean) {
        val pick = { sides: com.kaiharimoto.mastertool.core.ai.chessy.Sides? -> sides?.let { if (left) it.l else it.r } }
        when (e) {
            ChessyEye.SLY -> Unit
            ChessyEye.WIDE -> pic(pick(parts?.eyes?.get(ChessyFaces.FANGS)), "features")
            ChessyEye.SHUT -> pic(pick(parts?.eyes?.get(ChessyFaces.TONGUE)), "features")
            ChessyEye.CLOSED -> pic(pick(parts?.lids?.get(ChessyFaces.GRIN)), "features")
        }
        if (f.blink && mood.blinks) pic(pick(parts?.lids?.get(mood.lidOf(e))), "features")
    }
    fun brow(left: Boolean) {
        val sides = parts?.brows?.get(mood.brows) ?: return
        val b = if (left) sides.l else sides.r
        pic(b, "brows") {
            // each brow turns about its outer end: the anger's inner ends down, the worry's up
            rot = (if (left) 1f else -1f) * mood.browTilt * DEG
            rcx = if (left) b.x.toFloat() else (b.x + b.w).toFloat()
            rcy = b.y + b.h / 2f
            sizeDy = if (left) mood.browLiftL else mood.browLiftR
        }
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
                        if (f.blink && face != ChessyFaces.TONGUE) pic(P.blinkBy[face] ?: P.blink, "features")
                        continue
                    }
                    // the mouth: talking, the Grin's closed and open mouths; else the mood's own
                    when (f.mouth) {
                        ChessyMouth.OPEN -> { pic(P.closedBy[ChessyFaces.GRIN], "features"); pic(P.openBy[ChessyFaces.GRIN], "features") }
                        ChessyMouth.CLOSED -> { pic(P.closedBy[ChessyFaces.GRIN], "features"); smile() }
                        ChessyMouth.OWN -> when (mood.lips) {
                            ChessyLips.GRIN -> Unit
                            ChessyLips.FANGS -> pic(parts.mouths[ChessyFaces.FANGS], "features")
                            ChessyLips.TONGUE -> pic(parts.mouths[ChessyFaces.TONGUE], "features")
                            ChessyLips.SMILE -> { pic(P.closedBy[ChessyFaces.GRIN], "features"); smile() }
                            ChessyLips.FROWN -> { pic(P.closedBy[ChessyFaces.GRIN], "features"); pic(parts.frown, "features") }
                        }
                    }
                    eye(mood.eyeL, left = true)
                    eye(mood.eyeR, left = false)
                }
                "tongue" -> if (f.mouth == ChessyMouth.OWN && (if (parts == null) face == ChessyFaces.TONGUE else mood.lips == ChessyLips.TONGUE)) {
                    pic(pack.faces[ChessyFaces.TONGUE]?.tongue, "tongue")
                }
                "brows" -> if (parts == null) pic(fc.brows, "brows") else { brow(left = true); brow(left = false) }
            }
        }
    }
    if (body == null) {
        drawn()
        return
    }
    // Ai's body language on her: a hop, a lean about her neck, a squash; and her marks, riding her head
    val px = ox + neckX * s
    val py = oy + neckY * s
    withTransform({
        translate(body.bx * s, body.by * s)
        rotate(body.rot, Offset(px, py))
        scale(body.sx, body.sy, Offset(px, py))
    }) {
        drawn()
        withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) { marks(body.fx, s) }
    }
    withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) { marks(body.top, s) }
}

private const val DEG = (PI / 180).toFloat()
