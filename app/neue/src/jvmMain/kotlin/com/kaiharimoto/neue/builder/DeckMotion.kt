package com.kaiharimoto.neue.builder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import com.kaiharimoto.mastertool.core.motion.DeskLean
import com.kaiharimoto.mastertool.core.motion.LeanField
import com.kaiharimoto.mastertool.core.motion.LeanPose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/**
 * The deck's lean: where the bump under the pointer is, smoothed, in window
 * pixels. Every card reads [poseAt] inside its own `graphicsLayer`, so the
 * whole deck leans without one recomposition — the recipe the play stage uses
 * (bulk state in one place, per-card reads in the layer).
 *
 * It follows the pointer over the deck, and the card in the air while one is
 * being carried, so the deck makes way for a card as it passes over.
 */
class DeckMotion(private val drag: NeueDrag) {
    private val field = LeanField()

    /** The pointer over the deck, in window pixels; null when it is elsewhere. */
    var hover by mutableStateOf<Offset?>(null)

    /** A card's width in pixels: the unit the bump is measured in. Plain, because only the layer reads it. */
    var cardWidth = 100f

    private var x by mutableFloatStateOf(0f)
    private var y by mutableFloatStateOf(0f)
    private var presence by mutableFloatStateOf(0f)

    /** Where the bump is aimed this instant. */
    fun aim(): Offset? = if (drag.held != null) drag.pointer else hover

    fun poseAt(centre: Offset): LeanPose {
        val p = presence
        if (p <= 0f) return LeanPose.REST
        val w = cardWidth.coerceAtLeast(1f)
        return DeskLean.toward((x - centre.x) / w, (y - centre.y) / w, p)
    }

    /** The point the bump is at, while it is present: which card to draw on top. */
    fun point(): Offset? = if (presence > 0.05f) Offset(x, y) else null

    internal suspend fun run() {
        var last = 0L
        while (kotlin.coroutines.coroutineContext.isActive) {
            val aim = aim()
            if (aim != null) field.aim(aim.x, aim.y) else field.release()
            if (field.settled) {
                publish()
                last = 0L
                // Nothing moves until the pointer does: the loop sleeps rather than idles.
                snapshotFlow { aim() }.first { it != aim }
                continue
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                field.step(dt)
                publish()
            }
        }
    }

    private fun publish() {
        x = field.x
        y = field.y
        presence = field.presence
    }
}

@Composable
fun rememberDeckMotion(drag: NeueDrag): DeckMotion {
    val motion = remember(drag) { DeckMotion(drag) }
    LaunchedEffect(motion) { motion.run() }
    return motion
}

/**
 * The card in the air: it trails the pointer by a few frames and leans back
 * against the motion, lifted off the page. Stepped by its own loop while a card
 * is held, and at rest otherwise.
 */
class CarryMotion(private val drag: NeueDrag) {
    private var tx = 0f
    private var ty = 0f
    private var lagX by mutableFloatStateOf(0f)
    private var lagY by mutableFloatStateOf(0f)
    private var raise by mutableFloatStateOf(0f)

    fun pose(cardWidth: Float): LeanPose {
        val w = cardWidth.coerceAtLeast(1f)
        return DeskLean.carried(lagX / w, lagY / w, raise)
    }

    internal suspend fun run() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            snapshotFlow { drag.held }.first { it != null }
            tx = drag.pointer.x
            ty = drag.pointer.y
            raise = 0f
            var last = 0L
            while (drag.held != null) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = now
                    val p = drag.pointer
                    tx = DeskLean.approach(tx, p.x, dt, 0.05f)
                    ty = DeskLean.approach(ty, p.y, dt, 0.05f)
                    lagX = p.x - tx
                    lagY = p.y - ty
                    raise = DeskLean.approach(raise, 1f, dt, 0.04f)
                }
            }
            lagX = 0f
            lagY = 0f
            raise = 0f
        }
    }
}

@Composable
fun rememberCarryMotion(drag: NeueDrag): CarryMotion {
    val motion = remember(drag) { CarryMotion(drag) }
    LaunchedEffect(motion) { motion.run() }
    return motion
}
