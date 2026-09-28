package com.kaiharimoto.mastertool.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The prismatic light system.
 *
 * The identity is sharp white on black, and colour appears as *light*: the
 * chromatic fringing a lens leaves on a bright edge. Two primitives implement
 * it, both plain draws — no shaders, because there is no shader API common to
 * Android and desktop Skia, and a fringe is only ever a few strokes.
 *
 * On black the fringes are drawn additively ([BlendMode.Plus]): where the
 * offset colour passes overlap they sum back toward white, and the colour
 * survives only at the edges — exactly how aberration behaves in a photo.
 * On white the same hues are drawn multiplied ([BlendMode.Multiply]) under a
 * black core: the inversion the light theme is defined by, same colours,
 * flipped compositing.
 *
 * These are deliberately spent only on interactive or highlighted elements.
 * Fringing everything would read as decoration; fringing the thing under your
 * finger reads as light.
 */

/**
 * A chromatic-aberration edge: warm fringe pulled one way, cool fringe the
 * other, achromatic core on top.
 *
 * [dark] flips the compositing, not the colours — pass the current theme's
 * darkness (see [LocalDarkTheme]).
 */
fun Modifier.chromaticEdge(
    dark: Boolean,
    cornerRadius: Dp = 6.dp,
    stroke: Dp = 1.5.dp,
    fringe: Dp = 1.2.dp,
    intensity: Float = 1f,
): Modifier = drawBehind {
    val radius = CornerRadius(cornerRadius.toPx())
    val strokePx = stroke.toPx()
    val shift = fringe.toPx()
    val style = Stroke(strokePx)

    fun ring(color: Color, offset: Offset, blend: BlendMode) {
        drawRoundRect(
            color = color,
            topLeft = offset,
            cornerRadius = radius,
            style = style,
            blendMode = blend,
        )
    }

    if (dark) {
        val blend = BlendMode.Plus
        ring(
            MasterToolPalette.FringeWarm.copy(alpha = 0.85f * intensity),
            Offset(-shift, -shift * 0.4f),
            blend,
        )
        ring(
            MasterToolPalette.FringeCool.copy(alpha = 0.85f * intensity),
            Offset(shift, shift * 0.4f),
            blend,
        )
        ring(Color.White.copy(alpha = 0.9f * intensity), Offset.Zero, BlendMode.SrcOver)
    } else {
        val blend = BlendMode.Multiply
        ring(
            MasterToolPalette.FringeWarm.copy(alpha = 0.55f * intensity),
            Offset(-shift, -shift * 0.4f),
            blend,
        )
        ring(
            MasterToolPalette.FringeCool.copy(alpha = 0.55f * intensity),
            Offset(shift, shift * 0.4f),
            blend,
        )
        ring(Color.Black.copy(alpha = 0.9f * intensity), Offset.Zero, BlendMode.SrcOver)
    }
}

/**
 * A ring of the full prismatic ramp, swept around the element at [angle]
 * degrees. The successor to the legacy tool's `--angle`-driven border.
 *
 * Static at a fixed angle, alive when driven by [animatedPrismAngle] or by a
 * pointer position — the caller owns the motion, this owns the paint.
 */
fun Modifier.prismaticBorder(
    angle: Float,
    cornerRadius: Dp = 6.dp,
    stroke: Dp = 2.dp,
    alpha: Float = 1f,
): Modifier = drawBehind {
    drawPrismRing(angle, cornerRadius.toPx(), stroke.toPx(), alpha)
}

private fun DrawScope.drawPrismRing(
    angle: Float,
    cornerRadiusPx: Float,
    strokePx: Float,
    alpha: Float,
) {
    // Sweep gradients start at 3 o'clock; rotating the *canvas* around the
    // centre is cheaper than rebuilding the brush per frame.
    val ramp = MasterToolPalette.Prism
    val brush = Brush.sweepGradient(colors = ramp + ramp.first())
    rotate(angle) {
        drawRoundRect(
            brush = brush,
            cornerRadius = CornerRadius(cornerRadiusPx),
            style = Stroke(strokePx),
            alpha = alpha,
        )
    }
}


/**
 * Whether cards wear [drawPrismaticInset], as `UiPreferences.prismaticCards`
 * says.
 *
 * A composition local rather than a parameter because the readers are `CardTile`
 * and the play stage's `CardFace`, and both are reached through a dozen call
 * sites that have no business knowing about it — the same argument
 * [LocalDarkTheme] and `LocalCardBack` already make, and the same shape.
 *
 * Static, so toggling it invalidates the subtree once rather than subscribing
 * ninety tiles to a state object. It is read in composition and not in a draw
 * lambda: unlike a pose it changes when a person opens a menu, never per frame.
 *
 * Defaults to true so that anything standing a card up without a provider —
 * `:studio`, a preview — draws what the app draws.
 */
val LocalPrismaticCards = staticCompositionLocalOf { true }

/** A slow, continuous rotation for [prismaticBorder] — one full turn per [periodMs]. */
@Composable
fun animatedPrismAngle(periodMs: Int = 6000): State<Float> =
    rememberInfiniteTransition(label = "prism").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "prismAngle",
    )

/**
 * Text with a chromatic fringe: warm ghost one side, cool ghost the other,
 * true text on top. The wordmark treatment.
 *
 * Plain stacked draws rather than blend modes — text blending has no common
 * API worth the trouble, and at sub-pixel offsets the alpha ghosts read the
 * same. Costs two extra text layouts; use for single short lines only.
 */
@Composable
fun ChromaticText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fringe: Dp = 1.dp,
    fringeAlpha: Float = 0.75f,
) {
    Box(modifier) {
        val shift = fringe
        Text(
            text,
            style = style,
            color = MasterToolPalette.FringeWarm.copy(alpha = fringeAlpha),
            modifier = Modifier.offset(x = -shift, y = shift * 0.3f),
        )
        Text(
            text,
            style = style,
            color = MasterToolPalette.FringeCool.copy(alpha = fringeAlpha),
            modifier = Modifier.offset(x = shift, y = -shift * 0.3f),
        )
        Text(text, style = style, color = color)
    }
}
