package com.kaiharimoto.mastertool.ui.theme

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// The card foil's two hues — the original tool's `#ff69b4` and `#00ffff`. They are
// `MasterToolPalette.FoilPink` and `FoilCyan` on the tablet; here so the foil needs
// nothing from the tablet's theme (`:builder`, which Neue and the tablet share).
private val FOIL_PINK = Color(0xFFFF69B4)
private val FOIL_CYAN = Color(0xFF00FFFF)

/**
 * The original tool's card border, and it is a **foil** rather than a fringe.
 *
 * ## What it is
 *
 * `legacy/kai master tool.html:27676` drew it on every card in the deck grid,
 * and this is that effect rather than an interpretation of it: a ring inset four
 * per cent of the card's width, filled with a linear gradient of white → hot
 * pink → cyan → white at an angle that follows the pointer, plus a shimmer over
 * the art in the same two hues. The angle arithmetic is the legacy driver's
 * (`:18862`) verbatim — 135° at rest, swung ±25° across and ∓15° down — because
 * the *rate* is what makes it read as light on a surface being tilted rather
 * than as a rotating decoration, and that rate was tuned by somebody looking at
 * it.
 *
 * ## Why it is not [prismaticBorder]
 *
 * That one sweeps the six-hue ramp all the way round the element and turns on a
 * timer. It means "this is the card you asked for" — a reveal, a search hit —
 * and it is drawn *outside* the card, behind it, so it reads as a glow around
 * something. This is the opposite in every one of those respects: two hues, not
 * six; inside the card, over the art; and it moves only when a hand does. The
 * two coexist because they say different things, and `CardTile` lets the
 * highlight win when a card is wearing both.
 *
 * ## The shimmer, and why it is skipped without a pointer
 *
 * `mix-blend-mode: color-dodge` in the original, [BlendMode.ColorDodge] here.
 * Dodge on a dark surface is nearly a no-op and on a bright one it blows out
 * fast, which is exactly what a foil does. It is drawn only when [highlight] is
 * non-null — a card nobody is touching has no highlight to place, and a shimmer
 * parked in the middle of every card in a ninety-card grid is a smudge.
 *
 * @param angleDegrees the gradient's CSS angle: 0 is up, 90 is right.
 * @param highlight where the pointer is, normalised to −1..1 from the centre,
 *   or null when nothing is on the card.
 */
fun DrawScope.drawPrismaticInset(
    angleDegrees: Float,
    cornerRadiusPx: Float,
    /** Ring thickness, as a fraction of the card's width — the CSS `padding: 4%`. */
    thickness: Float = FOIL_INSET,
    highlight: Offset? = null,
    alpha: Float = 1f,
) {
    if (alpha <= 0.001f || size.minDimension <= 2f) return

    val ring = (size.width * thickness).coerceAtLeast(1f)

    // The gradient line, in CSS's convention: 0° points up, angles run
    // clockwise. Screen y is down, so "up" is (0, −1) and "right" is (1, 0).
    val radians = angleDegrees * (PI.toFloat() / 180f)
    val dx = sin(radians)
    val dy = -cos(radians)
    // How long the gradient has to be to cover the box at this angle. The
    // standard projection of the two half-extents onto the gradient's own
    // direction — short of it, the last stop lands before the far corner and
    // the ring goes flat white in one corner at some angles and not others.
    val span = abs(size.width * dx) + abs(size.height * dy)
    val centre = Offset(size.width / 2f, size.height / 2f)
    val start = Offset(centre.x - dx * span / 2f, centre.y - dy * span / 2f)
    val end = Offset(centre.x + dx * span / 2f, centre.y + dy * span / 2f)

    drawRoundRect(
        brush = Brush.linearGradient(
            0f to Color.White,
            0.28f to FOIL_PINK,
            0.65f to FOIL_CYAN,
            1f to Color.White,
            start = start,
            end = end,
        ),
        topLeft = Offset(ring / 2f, ring / 2f),
        size = Size(
            (size.width - ring).coerceAtLeast(0f),
            (size.height - ring).coerceAtLeast(0f),
        ),
        // The stroke's own centreline is half a ring inside the edge, so the
        // corner it traces is that much tighter than the card's.
        cornerRadius = CornerRadius((cornerRadiusPx - ring / 2f).coerceAtLeast(0f)),
        style = Stroke(ring),
        alpha = alpha,
    )

    if (highlight == null) return
    val at = Offset(
        size.width * (0.5f + highlight.x * 0.5f),
        size.height * (0.5f + highlight.y * 0.5f),
    )
    drawRoundRect(
        brush = Brush.radialGradient(
            0f to FOIL_PINK.copy(alpha = 0.09f * alpha),
            0.4f to FOIL_CYAN.copy(alpha = 0.09f * alpha),
            0.7f to FOIL_CYAN.copy(alpha = 0.06f * alpha),
            1f to Color.Transparent,
            center = at,
            radius = size.maxDimension * 0.75f,
        ),
        topLeft = Offset(ring, ring),
        size = Size(
            (size.width - ring * 2f).coerceAtLeast(0f),
            (size.height - ring * 2f).coerceAtLeast(0f),
        ),
        cornerRadius = CornerRadius((cornerRadiusPx - ring).coerceAtLeast(0f)),
        blendMode = BlendMode.ColorDodge,
    )
}

/**
 * Where the gradient points, for a card the pointer is [feel] across.
 *
 * The legacy driver's own arithmetic. Across matters more than down (25 against
 * 15) because a card is taller than it is wide, so an equal weighting makes the
 * long axis feel sluggish.
 */
fun foilAngleFor(feel: Offset): Float = FOIL_REST + feel.x * 25f - feel.y * 15f

/** The angle a card at rest wears: down and to the right, as the original did. */
const val FOIL_REST = 135f

/** The ring's thickness as a fraction of the card's width. The CSS `padding: 4%`. */
const val FOIL_INSET = 0.04f
