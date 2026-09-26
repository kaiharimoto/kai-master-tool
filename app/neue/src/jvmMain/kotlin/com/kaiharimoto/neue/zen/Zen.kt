package com.kaiharimoto.neue.zen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import com.kaiharimoto.mastertool.core.motion.ZenStage

/**
 * Zen mode, as the builder sees it: two amounts and a clock.
 *
 * - [quiet] goes 0 → 1 three seconds after the last input: everything that is
 *   not a card fades (strips, rules, search, the inspector's text).
 * - [deep] goes 0 → 1 at ten seconds: the pool and the inspector go entirely,
 *   the deck moves to the middle and floats, and the garden is raked.
 * - [time] runs while [deep] is above zero, for the float and the garden.
 *
 * Every reader reads them inside a `graphicsLayer` or a draw block, so the
 * whole of zen is redrawing and never recomposing — the same recipe as the
 * deck's lean.
 */
class ZenLayer {
    var quiet by mutableFloatStateOf(0f)
    var deep by mutableFloatStateOf(0f)
    var time by mutableFloatStateOf(0f)

    /** The garden's choice of compositions; null draws a fresh one each zen. The studio fixes it. */
    var gardenSeed: Int? = null

    /** How the garden is raked and lit. Plain: the garden reads it as it draws. */
    var gardenLook: GardenLook = GardenLook()

    /** The studio's: paint the garden plain white (true) or black (false), to lift the deck off it. */
    var gardenMatte by mutableStateOf<Boolean?>(null)

    /** Where the pointer is in the window while immersive, for the deck to turn toward in zen. */
    var pointer by mutableStateOf<Offset?>(null)

    /** The window, in pixels, which the deck is centred in. Plain: only layers read it. */
    var window: Size = Size.Zero

    /** Where the deck's cards are, at rest, in window pixels; and where they go in zen. */
    var deck: Rect = Rect.Zero

    val stage: ZenStage
        get() = ZenStage.of(deck.left, deck.top, deck.width, deck.height, window.width, window.height)

    /** The deck's rectangle once it has come to the middle: the stone the garden is raked around. */
    val deckInZen: Rect
        get() {
            val s = stage
            val w = deck.width * s.scale
            val h = deck.height * s.scale
            return Rect(window.width / 2f - w / 2f, window.height / 2f - h / 2f, window.width / 2f + w / 2f, window.height / 2f + h / 2f)
        }

    companion object {
        /** Zen that never happens: outside immersive mode, and in the studio. */
        val NONE = ZenLayer()
    }
}

val LocalZen = staticCompositionLocalOf { ZenLayer.NONE }

/** Fades with the first stage of zen: chrome — anything that is not a card. */
@Composable
fun Modifier.zenQuiet(): Modifier {
    val zen = LocalZen.current
    return graphicsLayer { alpha = (1f - zen.quiet).coerceIn(0f, 1f) }
}

/** Fades with the second: the pool and the inspector, cards and all. */
@Composable
fun Modifier.zenDeep(): Modifier {
    val zen = LocalZen.current
    return graphicsLayer { alpha = (1f - zen.deep).coerceIn(0f, 1f) }
}
