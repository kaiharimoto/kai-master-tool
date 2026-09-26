package com.kaiharimoto.neue.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Master UI, in Compose.
 *
 * `/home/user/Master-UI/kit/MASTER-UI.md` is the specification and this is a
 * translation of §2 of it, not an interpretation: two fills, the ink ramp as
 * alphas of the one ink, one easing, three durations. Dark is not a second
 * palette — it is [MuColors.of] with paper and ink swapped, which is the whole
 * of law 7.
 *
 * Nothing in `neue/` may name a colour that is not black, white or an alpha of
 * one of them. `MasterUiLawTest` reads the sources and fails the build on one.
 * The two exceptions — card foil and the deck's group markers — live in files
 * the test names, because kai granted exactly those two.
 */
@Immutable
data class MuColors(
    val paper: Color,
    val ink: Color,
    /** Secondary text, labels. */
    val ink70: Color,
    /** Meta, hints, numerals, placeholders, field borders at rest. .60 on paper, .50 on ink, for contrast. */
    val ink45: Color,
    /** Field borders, scrollbar thumb. */
    val ink25: Color,
    /** Hairlines between rows, progress track. */
    val ink12: Color,
    /** Hover wash, skeleton base. */
    val ink06: Color,
    /** Behind dialogs and drawers: paper at 85%, never a dark scrim. */
    val overlay: Color,
    val isInk: Boolean,
) {
    companion object {
        fun of(ink: Boolean): MuColors {
            val p = if (ink) Color.Black else Color.White
            val i = if (ink) Color.White else Color.Black
            return MuColors(
                paper = p,
                ink = i,
                ink70 = i.copy(alpha = 0.70f),
                ink45 = i.copy(alpha = if (ink) 0.50f else 0.60f),
                ink25 = i.copy(alpha = 0.25f),
                ink12 = i.copy(alpha = 0.12f),
                ink06 = i.copy(alpha = 0.06f),
                overlay = p.copy(alpha = 0.85f),
                isInk = ink,
            )
        }

        val Paper = of(ink = false)
        val Ink = of(ink = true)
    }
}

/** The spacing scale. There is no spacing outside it (§4). */
object MuSpace {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s12 = 48.dp
    val s16 = 64.dp
}

/** One easing, three durations (§7). Nothing springs, bounces or scales. */
object MuMotion {
    val ease: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val FAST = 120
    const val BASE = 180
    const val SLOW = 320
    const val PAGE = 100
    const val PANEL = 220
}

/** Shell dimensions (§4). */
object MuShell {
    val top = 40.dp
    val rail = 232.dp
    val railRow = 56.dp
    val footer = 56.dp
}

val LocalMu = staticCompositionLocalOf { MuColors.Paper }

/** Whatever text a component draws unless it says otherwise; set by [MuTheme] and by inverted surfaces. */
val LocalMuText = staticCompositionLocalOf { TextStyle.Default }

object Mu {
    val colors: MuColors
        @Composable get() = LocalMu.current
}

@Composable
fun MuTheme(ink: Boolean, content: @Composable () -> Unit) {
    val colors = MuColors.of(ink)
    val type = MuType.rememberFamilies()
    CompositionLocalProvider(
        LocalMu provides colors,
        LocalMuFonts provides type,
        LocalMuText provides MuType.body(type).copy(color = colors.ink),
        // Selection is inversion (§5): ink on paper, whichever way round.
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.ink,
            backgroundColor = colors.ink.copy(alpha = 0.25f),
        ),
        // No ripple, no press state that is not a colour change (§7).
        LocalIndication provides NoIndication,
        content = content,
    )
}

/**
 * The surface a component sits on, turned over: ink block, paper text.
 *
 * Emphasis in this system is inversion (law 4), and anything inside an
 * inverted row must read its colours from here rather than from the theme, or
 * a selected row carries ink text on an ink block.
 */
@Composable
fun Inverted(on: Boolean = true, content: @Composable () -> Unit) {
    if (!on) return content()
    val base = Mu.colors
    val flipped = MuColors.of(!base.isInk)
    CompositionLocalProvider(
        LocalMu provides flipped,
        LocalMuText provides LocalMuText.current.copy(color = flipped.ink),
        content = content,
    )
}
