package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.chessy.Box as Spot
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyCrewPlan
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyPlace
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyPoint
import com.kaiharimoto.mastertool.core.ai.chessy.Side
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How small Chessy is ever drawn (kai: "a minimum size that's substantial enough to be able to read her expressions"). */
object ChessySizes {
    val MIN = 104.dp

    /** In the chat box, where she lives. */
    val chat = 132.dp
    val chatPhone = 112.dp

    /** A copy pointing at something. */
    val copy = 112.dp

    /** The empty chat's hello. */
    val greeting = 168.dp
}

/**
 * The places on screen her copies go to, by name ([ChessyPoint]), in window pixels: kept by the composables that
 * draw them (`Modifier.chessySpot`) and read only when a copy is sent, so nothing recomposes as they move.
 */
object ChessySpots {
    internal val boxes = HashMap<String, Spot>()

    fun of(name: String): Spot? = boxes[name]
}

private class SpotNode(var name: String) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val r = coordinates.boundsInWindow()
        if (r.width > 0f && r.height > 0f) ChessySpots.boxes[name] = Spot(r.left, r.top, r.right, r.bottom)
    }

    override fun onDetach() {
        ChessySpots.boxes.remove(name)
    }
}

private data class SpotElement(val name: String) : ModifierNodeElement<SpotNode>() {
    override fun create() = SpotNode(name)
    override fun update(node: SpotNode) {
        if (node.name != name) ChessySpots.boxes.remove(node.name)
        node.name = name
    }
}

/** This composable is the place named [name] for Chessy's copies to point at. */
fun Modifier.chessySpot(name: String): Modifier = this then SpotElement(name)

/**
 * Her copies at work (kai, 2026-10): one per tool that touches something on screen, blinked in beside it, looking at
 * it, saying what she does in a box and then what came of it. Kept for the app's lifetime in `AiState`; drawn by
 * [ChessyCrewLayer] over the window.
 */
class ChessyCrew {
    class Copy(val id: Int, val target: Spot, val at: Spot, val side: Side, val came: Long, say: String) {
        var say by mutableStateOf(say)
        var mood by mutableStateOf(Expression.WORKING)
        var goes by mutableStateOf<Long?>(null)
    }

    val copies = mutableStateListOf<Copy>()
    private var next = 1

    /** The window and a copy's size, in pixels: the layer says, as it is laid out. */
    internal var window: Spot? = null
    internal var sizePx = 0f
    internal var gapPx = 0f

    private fun now() = System.nanoTime() / 1_000_000L

    /**
     * Sends a copy to where [tool] works, saying [line]; null when its work has no place on screen (or none is drawn
     * now), and the one in the chat box says it alone.
     */
    fun act(tool: String, input: JsonObject?, line: String): Int? {
        val window = window ?: return null
        if (sizePx <= 0f) return null
        // a spot folded away (the index rail, out of the window) is no place to point
        val target = ChessyPoint.spotsFor(tool, input).firstNotNullOfOrNull { name -> ChessySpots.of(name)?.takeIf { it.overlaps(window) } } ?: return null
        val t = now()
        // the oldest still here blinks out to make room
        val here = copies.filter { it.goes == null || it.goes!! > t }
        if (here.size >= ChessyCrewPlan.MAX) here.minByOrNull { it.came }?.let { it.goes = minOf(it.goes ?: Long.MAX_VALUE, t + ChessyCrewPlan.BLINK_MS) }
        val taken = here.map { it.at } + listOfNotNull(ChessySpots.of(CHAT), ChessySpots.of(PANEL))
        val placed = ChessyPlace.place(target, window, sizePx, gapPx, taken)
        val copy = Copy(next++, target, placed.at, placed.side, t, line)
        copies += copy
        return copy.id
    }

    /** What came of copy [id]'s work: her last words, a face for it, and she leaves a little later. */
    fun done(id: Int, summary: String, failed: Boolean) {
        val c = copies.firstOrNull { it.id == id } ?: return
        c.say = summary.ifBlank { c.say }
        c.mood = if (failed) Expression.OOPS else Expression.DONE
        c.goes = ChessyCrewPlan.leaves(now())
    }

    /** Copies that have left are let go; true while any is still to be drawn. */
    internal fun sweep(t: Long): Boolean {
        copies.removeAll { it.goes != null && t >= it.goes!! }
        return copies.isNotEmpty()
    }

    internal fun clock() = now()

    companion object {
        /** The chat box's own Chessy and the panel she lives in: copies stand clear of both. */
        const val CHAT = "chessy.chat"
        const val PANEL = "chessy.panel"
    }
}

/**
 * Chessy's copies over the window: each blinks in (a picture closing down to a line and opening again, not a
 * journey: she teleports), looks at what she works on, frames it with crop marks and a line from her to it, and says
 * what she is doing in a square box. Passes every press through. Asleep when no copy is out.
 */
@Composable
fun ChessyCrewLayer(crew: ChessyCrew) {
    val density = LocalDensity.current
    var clock by remember { mutableLongStateOf(crew.clock()) }
    val out = crew.copies.isNotEmpty()
    LaunchedEffect(out) {
        while (out) {
            withFrameNanos { clock = crew.clock() }
            if (!crew.sweep(clock)) break
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                val r = c.boundsInWindow()
                crew.window = Spot(r.left, r.top, r.right, r.bottom)
            }
            .onSizeChanged {
                crew.sizePx = with(density) { ChessySizes.copy.toPx() }
                crew.gapPx = with(density) { 10.dp.toPx() }
            },
    ) {
        val origin = crew.window
        // never `return@Box` here: an inline lambda with composable calls must not return early (NonLocalReturnTest)
        if (origin != null) Crew(crew, origin, clock)
    }
}

@Composable
private fun Crew(crew: ChessyCrew, origin: Spot, clock: Long) {
    val ink = Mu.colors.ink
    Box(Modifier.fillMaxSize()) {
        // the marks and lines under the copies: what each points at
        Canvas(Modifier.fillMaxSize()) {
            val arm = 10.dp.toPx()
            val w = 1.5.dp.toPx()
            for (c in crew.copies) {
                val k = ChessyCrewPlan.shown(clock, c.came, c.goes)
                if (k <= 0f) continue
                val t = c.target
                val l = t.l - origin.l - 4.dp.toPx()
                val tp = t.t - origin.t - 4.dp.toPx()
                val r = t.r - origin.l + 4.dp.toPx()
                val b = t.b - origin.t + 4.dp.toPx()
                // crop marks at the target's corners
                for ((x, y, dx, dy) in listOf(Quad(l, tp, 1f, 1f), Quad(r, tp, -1f, 1f), Quad(l, b, 1f, -1f), Quad(r, b, -1f, -1f))) {
                    drawLine(ink, Offset(x, y), Offset(x + dx * arm, y), w, alpha = k)
                    drawLine(ink, Offset(x, y), Offset(x, y + dy * arm), w, alpha = k)
                }
                if (c.side == Side.OVER) continue
                // a line from her edge to the target's nearest edge, with a head
                val from = when (c.side) {
                    Side.RIGHT -> Offset(c.at.l, c.at.cy)
                    Side.LEFT -> Offset(c.at.r, c.at.cy)
                    Side.BELOW -> Offset(c.at.cx, c.at.t)
                    else -> Offset(c.at.cx, c.at.b)
                } - Offset(origin.l, origin.t)
                val to = Offset((from.x + origin.l).coerceIn(t.l, t.r), (from.y + origin.t).coerceIn(t.t, t.b)) - Offset(origin.l, origin.t)
                val d = to - from
                val len = sqrt(d.x * d.x + d.y * d.y)
                if (len < 4f) continue
                val u = Offset(d.x / len, d.y / len)
                val tip = to - u * 6.dp.toPx()
                drawLine(ink, from, tip, w, alpha = k)
                val n = Offset(-u.y, u.x) * 4.dp.toPx()
                drawLine(ink, tip, tip - u * 7.dp.toPx() + n, w, alpha = k)
                drawLine(ink, tip, tip - u * 7.dp.toPx() - n, w, alpha = k)
            }
        }
        for (c in crew.copies) androidx.compose.runtime.key(c.id) {
            val k = ChessyCrewPlan.shown(clock, c.came, c.goes)
            val x = (c.at.l - origin.l).roundToInt()
            val y = (c.at.t - origin.t).roundToInt()
            Box(
                Modifier
                    .offset { IntOffset(x, y) }
                    .graphicsLayer {
                        // she teleports: closed to a line and opened again, never a journey across the window
                        alpha = k
                        scaleY = k * k
                        scaleX = 1f + (1f - k) * .5f
                    },
            ) {
                ChessyAvatar(c.mood, ChessySizes.copy, pointer = { Offset(c.target.cx, c.target.cy) })
            }
            Words(c, origin, k)
        }
    }
}

@Composable
private fun Words(c: ChessyCrew.Copy, origin: Spot, k: Float) {
    val colors = Mu.colors
    val density = LocalDensity.current
    var height by remember { mutableStateOf(0) }
    var width by remember { mutableStateOf(0) }
    val gap = with(density) { 6.dp.toPx() }
    val window = origin
    val above = c.at.t - window.t > height + gap
    val x = (c.at.l - window.l).coerceAtMost(window.w - width - gap).coerceAtLeast(0f).roundToInt()
    val y = if (above) (c.at.t - window.t - height - gap).roundToInt() else (c.at.b - window.t + gap).roundToInt()
    Box(
        Modifier
            .offset { IntOffset(x, y) }
            .graphicsLayer { alpha = k }
            .onSizeChanged { height = it.height; width = it.width }
            .widthIn(max = 240.dp)
            .background(colors.paper)
            .border(1.dp, colors.ink)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Small(c.say, color = colors.ink, maxLines = 3)
    }
}

private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
