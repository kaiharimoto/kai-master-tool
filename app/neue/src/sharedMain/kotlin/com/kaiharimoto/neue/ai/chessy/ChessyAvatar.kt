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
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFrame
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMouth
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyPack
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyRig
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyWarp
import com.kaiharimoto.mastertool.core.ai.chessy.LayerPose
import com.kaiharimoto.mastertool.core.ai.chessy.Pic
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.res.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

/**
 * Chessy (kai, 2026-10): the cat girl who hacks her way in and takes Ai's place, drawn live from the pictures kai
 * approved in the mockup. Every layer is a mesh bent by `core/ai/chessy`'s [ChessyRig] and [ChessyWarp]: her head
 * turns in depth toward [pointer] (window pixels; null, she looks about), her hair, bows and bell swing after it, she
 * breathes, blinks, twitches an ear, and talks (Flap) while [talking].
 *
 * [face] is one of her three: "grin", "fangs", "tongue". Colour is hers, as card art's is (`MasterUiLawTest` names
 * this package). One frame loop steps the rig and bumps a counter read in the draw, so nothing recomposes as she moves.
 */
@Composable
fun ChessyAvatar(
    face: String,
    size: Dp,
    modifier: Modifier = Modifier,
    talking: Boolean = false,
    pointer: () -> Offset? = { null },
    still: Boolean = false,
) {
    var assets by remember { mutableStateOf(ChessyAssets.loaded) }
    LaunchedEffect(Unit) { if (assets == null) assets = ChessyAssets.load() }
    val rig = remember(still) { ChessyRig(seed = (System.nanoTime() % 100_000).toInt(), still = still) }
    val tick = remember { mutableIntStateOf(0) }
    val showing by rememberUpdatedState(face)
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
                rig.step(dt, ax, ay, speaking, blinks = showing != "tongue")
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
        drawChessy(a, rig.frame, showing, head = size.value < HEAD_BELOW_DP)
    }
}

/**
 * Chessy in Ai's place (kai): provided at the app's root while the assistant is she ([com.kaiharimoto.mastertool.core.prefs.AiPrefs.persona]),
 * with whether she is talking (a reply streaming). Ai's live face reads it and draws her instead; null is Ai.
 */
class ChessyLook(val talking: () -> Boolean)

val LocalChessy = androidx.compose.runtime.staticCompositionLocalOf<ChessyLook?> { null }

/** Chessy's pack and pictures, read once for the app's lifetime. */
class ChessyAssets(val pack: ChessyPack, val images: Map<String, ImageBitmap>) {
    private val meshes = HashMap<Pic, Mesh>()
    internal fun mesh(p: Pic): Mesh = meshes.getOrPut(p) { Mesh(p) }

    companion object {
        var loaded: ChessyAssets? = null
            private set
        private val lock = Mutex()

        suspend fun load(): ChessyAssets? = lock.withLock {
            loaded ?: runCatching {
                val pack = ChessyPack.read(Res.readBytes("${ChessyPack.DIR}/chessy.json").decodeToString())
                val images = pack.files.associateWith { f -> decodePicture(Res.readBytes("${ChessyPack.DIR}/$f"))!! }
                ChessyAssets(pack, images)
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

/** Her head on the sheet, ears to chin (left, top, right, bottom): what a small avatar shows. */
private val HEAD = floatArrayOf(110f, 70f, 1210f, 1335f)

/** Below this she is drawn as her head alone. */
private const val HEAD_BELOW_DP = 80f

/** Chessy as one frame shows her, fitted to the canvas: back to front, a face's parts after its features (the studio draws set poses with it). */
fun DrawScope.drawChessy(a: ChessyAssets, f: ChessyFrame, face: String, head: Boolean = false) {
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
    fun pic(p: Pic?, layerId: String, alpha: Float = 1f, sized: Boolean = false) {
        p ?: return
        val img = a.images[p.file] ?: return
        val m = a.mesh(p)
        ChessyWarp.pose(pack, layerId, p, f, face, pose)
        if (sized) { pose.sizeX = pack.closedCx; pose.sizeK = pack.closedLength; pose.sizeDy = pack.closedDy }
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
    val fc = pack.faces[face] ?: pack.faces.values.first()
    val P = pack.parts
    for (l in pack.layers) {
        if (!l.perface) {
            pic(l.pic, l.id)
            if (f.rimMix > 0f && l.rim != null) pic(l.rim, l.id, f.rimMix)
            continue
        }
        when (l.id) {
            "features" -> {
                pic(fc.features, "features")
                // the mouth: this face's own closed and open mouths over its own skin
                when (f.mouth) {
                    ChessyMouth.OPEN -> { pic(P.closedBy[face], "features"); pic(P.openBy[face] ?: P.talk.firstOrNull(), "features") }
                    ChessyMouth.CLOSED -> { pic(P.closedBy[face] ?: P.closed, "features"); pic(P.cline, "features", sized = true) }
                    ChessyMouth.OWN -> Unit
                }
                if (f.blink && face != "tongue") pic(P.blinkBy[face] ?: P.blink, "features")
            }
            "tongue" -> if (f.mouth == ChessyMouth.OWN) pic(fc.tongue, "tongue")
            "brows" -> pic(fc.brows, "brows")
        }
    }
}
