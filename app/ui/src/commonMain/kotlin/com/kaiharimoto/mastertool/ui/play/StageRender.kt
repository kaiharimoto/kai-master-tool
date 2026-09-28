package com.kaiharimoto.mastertool.ui.play

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.layout.BoardLayout
import com.kaiharimoto.mastertool.core.layout.BoardSlot
import com.kaiharimoto.mastertool.core.layout.MatControls
import com.kaiharimoto.mastertool.core.layout.Slot
import com.kaiharimoto.mastertool.core.layout.StagePlane
import com.kaiharimoto.mastertool.core.board.PlayField
import com.kaiharimoto.mastertool.core.motion.Pose3
import com.kaiharimoto.mastertool.core.motion.Vec2
import com.kaiharimoto.mastertool.core.motion.Vec3
import com.kaiharimoto.mastertool.core.render.CardMaterial
import com.kaiharimoto.mastertool.core.render.CardSolid
import com.kaiharimoto.mastertool.ui.gpu.StageShader
import com.kaiharimoto.mastertool.core.render.Outset
import com.kaiharimoto.mastertool.core.render.LightPool
import com.kaiharimoto.mastertool.core.render.Rot3
import com.kaiharimoto.mastertool.core.render.Shading
import com.kaiharimoto.mastertool.core.render.Shadows
import com.kaiharimoto.mastertool.core.render.StageRig
import com.kaiharimoto.mastertool.core.render.Tone
import com.kaiharimoto.mastertool.core.scene.Scene
import com.kaiharimoto.mastertool.core.scene.Scenery
import com.kaiharimoto.mastertool.ui.theme.MasterToolPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Everything the play stage paints that is not a card's own picture.
 *
 * The whole file is a renderer and nothing in it decides anything: the
 * geometry, the light and the material all come out of `core/render`, already
 * solved and already tested, and what happens here is that floats become
 * paint. That split is the same one `DeckFit` and `BoardLayouter` established
 * — the arithmetic that can be wrong lives where it can be held to a test, and
 * the composable is left with no opinions to have.
 */

/** Steps in a soft shadow. Five is where another one stops being visible. */
private const val SHADOW_STEPS = 5

/** The one radius every card on the stage is cut to. §8 of the handbook. */
internal val CardCornerRadius = 4.dp

/**
 * The playmat's outline: everything the board occupies, plus its border.
 *
 * Solved in core beside the room it is a hole in. It was a pair of file-private
 * functions here, over a pair of file-private margins, and the moment the desk
 * needed to know where the felt stopped, a renderer was the only place that
 * answer existed. See `Scenery` for the numbers and the argument for them.
 */
internal fun matSurface(layout: BoardLayout): Slot = Scenery.mat(layout)

/**
 * The playmat: a pool of light, a fall-off into the corners, and the zones.
 *
 * A true-black table cannot receive a dark shadow — there is nothing there to
 * take away — so the mat has to carry some light before anything resting on it
 * can remove any. The pool is placed *by the key light* rather than at the
 * centre of the table: a room lit from one side does not have its brightest
 * spot in the middle, and the moment the pool and the shadows disagree about
 * where the lamp is, the table stops being a place.
 *
 * The mat covers the hand's band as well as the field, which it did not before.
 * A hand fanned out on bare void beneath a mat that stopped short of it was the
 * one place on this stage where the cards were demonstrably resting on nothing.
 */
internal fun DrawScope.drawFelt(
    layout: BoardLayout,
    stage: StagePlane,
    eye: Vec3,
    look: StageLook,
    pool: LightPool?,
    /**
     * The cloth, if this platform can run one. Null is the mat that shipped.
     *
     * Compiled once by the screen and handed down, because compiling inside a
     * draw would make the weave the most expensive thing on the stage.
     */
    weave: StageShader? = null,
) {
    val mat = matSurface(layout)
    val span = max(mat.width, mat.height)
    val key = look.lighting.key

    // ---- whether there is a playmat at all -----------------------------------
    //
    // In the desk room there is not, and that is kai's call rather than an
    // optimisation: *the desk is the playing surface*. A black rubber mat laid
    // over a wooden desk is a second surface hiding the first, and the room is
    // the thing this stage is now trying to be.
    //
    // So everything a playmat brought with it goes with it — its fill, the pool
    // of light drawn *on* it, and the vignette that kept that pool from reading
    // as a grey rectangle. All three were describing a mat. What is left is the
    // zones, which are now routed into wood rather than pressed into felt, and
    // the desk's own shading, which `drawScene` already solved against the same
    // rig.
    //
    // The minimal stage keeps its slab. It has no desk under it, and cards
    // resting on nothing is not the same idea.
    val onDesk = look.scene == Scene.DESK
    if (!onDesk) {

    val corner = CornerRadius(layout.cardWidth * 0.16f)
    val matTopLeft = Offset(mat.left, mat.top)
    val matSize = Size(mat.width, mat.height)

    // The mat itself, as a surface rather than as an absence.
    //
    // Under a lamp that has a real place it is drawn as the pool instead: the
    // same rubber, lit by the rig that lights everything else on the stage
    // rather than by a gradient positioned by hand. That is the whole of what
    // this release does to the felt — the mat has never been told a lamp exists,
    // and it was the one surface here that could disagree with the shadows on it.
    if (pool == null) {
        drawRoundRect(color = look.mat, topLeft = matTopLeft, size = matSize, cornerRadius = corner)
    } else {
        drawRoundRect(
            brush = feltPool(pool, look.mat),
            topLeft = matTopLeft,
            size = matSize,
            cornerRadius = corner,
        )
    }

    // The cloth over the top, in overlay, whose identity is mid grey — so this
    // adds thread-by-thread shading to whatever the mat already is and can
    // never blank it. Clipped to the mat's own rounded rectangle, or the weave
    // would run out over the desk.
    if (weave != null) {
        clipPath(Path().apply { addRoundRect(RoundRect(Rect(matTopLeft, matSize), corner)) }) {
            drawRect(
                brush = FeltWeave.brushFor(
                    shader = weave,
                    origin = mat.left to mat.top,
                    cardWidth = layout.cardWidth,
                    key = key.direction,
                    density = stage.jacobian(mat.centerX, mat.centerY),
                ),
                topLeft = matTopLeft,
                size = matSize,
                blendMode = BlendMode.Overlay,
            )
        }
    }

    // Where the highlight goes.
    //
    // With no lamp to reflect, upstream of the light's *travel* — the shipped
    // answer, and the best a direction can give, since a direction has no place
    // and so the highlight cannot move when the head does.
    //
    // With a lamp that has a place, the lamp's own mirror image chased down to
    // the felt. That is what a highlight is, and the difference does not need
    // pointing at: it slides a quarter of the mat's depth toward you between the
    // overhead seat and the player's chair.
    val eyeAt = stage.eyePoint(eye)
    val mirror = StageRig.sheen(key, eyeAt)
    val highlight = if (mirror != null) {
        Offset(mirror.x, mirror.y)
    } else {
        Offset(
            x = mat.centerX - key.direction.x * mat.width * 0.55f,
            y = mat.centerY - key.direction.y * mat.height * 0.55f,
        )
    }
    val reach = if (mirror != null) {
        StageRig.sheenRadius(key, eyeAt, FELT_ROUGHNESS)
    } else {
        span * look.poolReach
    }

    // The alphas were picked by eye against the old, darker solids: a fully
    // facing pile edge used to come back at 0.66 and now comes back at 0.79,
    // because the shading it goes through stopped multiplying an sRGB encoding
    // (see `Tone`). So the minimal room's highlight is still a touch dim for the
    // objects standing in it. Re-tuning it is a look-at-the-tablet judgement
    // rather than a correctness fix, and it *cannot* ride in now: the minimal
    // stage coming out pixel-for-pixel is this release's acceptance criterion.
    //
    // It is the colour of the lamp rather than white, which is the same rule a
    // card's specular obeys: a highlight is a reflection of the light source and
    // not of the room, so it is the one place a temperature arrives undiluted.
    val lamp = look.poolColour
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(
                lamp.copy(alpha = look.sheenCore),
                lamp.copy(alpha = look.sheenEdge),
                Color.Transparent,
            ),
            center = highlight,
            radius = reach,
        ),
        topLeft = matTopLeft,
        size = matSize,
        cornerRadius = corner,
    )

    // And the fall-off, which is what stops the highlight from reading as a
    // rectangle of grey rather than as light landing on something.
    //
    // Only where there is no lamp. Where there is one the fall-off *is* the
    // attenuation and has already been drawn; a second vignette centred on the
    // mat would be darkening the table from a place no light comes from, which
    // is exactly the disagreement this release exists to end.
    if (pool == null) {
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, MasterToolPalette.Ink.copy(alpha = look.falloff)),
                center = Offset(mat.centerX, mat.centerY),
                radius = span * 0.78f,
            ),
            topLeft = matTopLeft,
            size = matSize,
            cornerRadius = corner,
        )
    }

    }

    layout.slots.forEach { (slot, rect) ->
        val pile = slot !is BoardSlot.Zone
        // Two lines, a hair apart: the cut edge of a groove in the mat, lit on
        // its far side and dark on its near one. The same trick as the card
        // hairline, and the reason the zones read as pressed into the felt
        // rather than printed on it.
        val inset = rect.width * 0.006f
        drawRoundRect(
            color = MasterToolPalette.Ink.copy(alpha = 0.55f),
            topLeft = Offset(rect.left, rect.top + inset),
            size = Size(rect.width, rect.height),
            cornerRadius = CornerRadius(rect.width * 0.05f),
            style = Stroke(width = rect.width * 0.008f),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = if (pile) 0.11f else 0.065f),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            cornerRadius = CornerRadius(rect.width * 0.05f),
            style = Stroke(width = rect.width * 0.008f),
        )
    }
}

// ---- shadows ----------------------------------------------------------------------

/**
 * What a card takes out of the light under it.
 *
 * Drawn as a handful of nested copies rather than blurred, because there is no
 * blur available inside a `DrawScope` on both platforms and a shadow this soft
 * does not need one: five steps is where a sixth stops being visible. The step
 * alpha is solved so the copies *compose* to the alpha asked for rather than
 * summing past it, which is the difference between a soft shadow and a smudge.
 */
internal fun DrawScope.drawCardShadow(
    pose: Pose3,
    width: Float,
    height: Float,
    cardHeight: Float,
    /**
     * How deep the body under this face is, so a pile shadows from the felt.
     *
     * Without it a deck is shadowed as its top card, which is forty cards up in
     * the air: the shadow walks out from under the deck by the deck's own
     * height, softens as though it were being held, and loses the contact
     * darkness that is the only thing saying it is *resting*. The deck then
     * reads as a card hovering beside two floating white edges. See
     * [Shadows.cast].
     */
    bodyDepth: Float = 0f,
    /**
     * Where the camera is, for the rim term inside `StageRig.occlusion`.
     *
     * A shadow's darkness is a fact about the surface receiving it, and the
     * rim light this stage carries is gated on the graze — so how much of the
     * felt's light a card removes depends, slightly, on where you are standing.
     */
    eye: Vec3 = Vec3.Toward,
    look: StageLook,
) {
    // `shadow.alpha` is **coverage** — how much of the shadow is there — and
    // how dark that comes out is a separate question with a separate answer.
    // `StageRig.occlusion` is that answer: the share of the felt's light that
    // was arriving straight from the key, and therefore the share a card
    // standing in front of it takes away.
    //
    // Splitting the two is what stops a shadow being a colour. The old single
    // number was 0.66 whatever the room was doing, while the ambient it does
    // not occlude is 0.72 in Minimal and 0.55 at night — so the same constant
    // was a hole punched through a lit surface in one room and roughly right in
    // another. Asked properly it is about a fifth, and a shadow becomes the
    // felt seen darker rather than a shape laid over it.
    //
    // Cast from the room's own key, which is what makes changing the hour a
    // change of *room* rather than of colour: every shadow on the board swings
    // to the other side of the thing throwing it, because the lamp did.
    // Still one light. A second shadow would not read as a second lamp, it would
    // read as a duplicated card.
    val shadow = Shadows.cast(pose, width, height, look.lighting.key, cardHeight, bodyDepth = bodyDepth)
        ?: return

    // The felt is flat, so the surface receiving this is the one the rig
    // already answers about: normal straight up, at the point the shadow lands.
    val landed = shadow.corners.firstOrNull()
    val darkest = StageRig.occlusion(SURFACE_UP, eye, look.lighting, landed)
    val alpha = shadow.alpha * darkest
    if (alpha <= 0.004f) return

    val flat = shadow.corners.map { Vec2(it.x, it.y) }

    // A hard shadow does not need five copies to look hard, and most of the
    // shadows on this table are hard — everything resting on the felt. The
    // handful that are soft are the ones in someone's hand, which is also the
    // handful anyone is looking at.
    val rings = when {
        shadow.spread < cardHeight * 0.04f -> 2
        shadow.spread < cardHeight * 0.12f -> 3
        else -> SHADOW_STEPS
    }
    val step = 1f - (1f - alpha).pow(1f / rings)

    // A real penumbra straddles the geometric edge: it begins a little *inside*
    // it and ends the same distance outside. Feathering only outward — which is
    // what this did — leaves the solid core exactly as wide as the shadow, so a
    // soft shadow reads as a hard one wearing a halo, and under a source the
    // size of a window that halo is forty-five pixels of it.
    //
    // One sign buys the right shape. `umbra` is zero for a light with no stated
    // size, which is every light the minimal stage has, so those shadows come
    // out as the pixels they always were.
    // Measured against the nearest *edge*, which is what an inset has to clear.
    // It used to be measured against the nearest corner — on a card that is the
    // half-diagonal, 52.1 where the short half-axis is 29.5, so the guard was
    // letting the umbra eat one and a half times the width it was protecting.
    val inset = min(shadow.umbra, Outset.inradius(flat) * 0.9f)

    // Outermost first, so the overlaps build toward the core.
    for (ring in rings downTo 1) {
        val grow = -inset + (inset + shadow.spread) * ring / rings
        drawPath(
            path = pathOf(Outset.of(flat, grow)),
            color = look.shadow.copy(alpha = step),
        )
    }

    // The tight darkness of actual contact, which is a different phenomenon
    // from the cast shadow and has to let go far faster: it belongs under the
    // card's own footprint, not under where the light throws it.
    if (shadow.contact > 0.02f) {
        val footprint = CardSolid
            .face(pose.copy(position = Vec3(pose.position.x, pose.position.y, 0f)), width, height)
            .map { Vec2(it.x, it.y) }
        drawPath(
            path = pathOf(Outset.of(footprint, cardHeight * 0.012f)),
            color = look.shadow.copy(alpha = 0.5f * shadow.contact),
        )
    }
}

/**
 * A closed path through [corners].
 *
 * All this does now is walk the points. Growing a shape used to happen here, by
 * pushing every corner away from the shape's centre — which is not an offset,
 * and gave every card a penumbra half again as wide above and below as at its
 * sides. That arithmetic is `Outset` in core now, where it is tested.
 */
private fun pathOf(corners: List<Vec2>): Path {
    val path = Path()
    corners.forEachIndexed { index, corner ->
        if (index == 0) path.moveTo(corner.x, corner.y) else path.lineTo(corner.x, corner.y)
    }
    path.close()
    return path
}

// ---- solids -------------------------------------------------------------------------

/**
 * The white edge of a card, a stack or a deck.
 *
 * This is the thing whose absence made a pile read as several rectangles
 * printed on the felt: cards are objects, objects have sides, and at any angle
 * the table is seen from you can see them. The faces come from `CardSolid`
 * already back-face-culled, and every corner is flattened separately — the top
 * of a deck is genuinely further from the felt than its base, and drawing the
 * two ends at the same place is what a fake stack looks like.
 *
 * The printed face and the back are both skipped, which is one test rather than
 * two: the back's normal is the printed one negated, so the absolute value of
 * the dot product catches both. That is also what guarantees everything reaching
 * the seam code below is a *side* quad, whose corners run front, front, back,
 * back — the ordering the ruling depends on.
 */
internal fun DrawScope.drawSolidEdges(
    pose: Pose3,
    width: Float,
    height: Float,
    depth: Float,
    stage: StagePlane,
    eye: Vec3,
    look: StageLook,
    /** How many cards are in this body, so its side can be ruled into that many. */
    layers: Int = 1,
) {
    if (depth <= 0.4f) return

    val printed = Rot3.normal(pose)
    // A single card is square with itself; a pile is not, and the slouch is what
    // says so from directly overhead, where its height has gone.
    val lean = if (layers > 1) CardSolid.pileLean(depth, width) else Vec3.Zero

    CardSolid.slab(pose, width, height, depth, lean)
        .facingTheCamera(stage, eye)
        .forEach { face ->
        // Everything except the printed face, which the card's own composable
        // is about to draw over the top of this anyway.
        if (abs(face.normal dot printed) > 0.99f) return@forEach

        // The face's four corners come round as: two on the printed face, then
        // the same two on the back. So 0→3 and 1→2 are the two edges that run
        // down through the body, which is the axis the cards are stacked along.
        val flat = face.corners.map { stage.flatten(it) }

        // A face the eye is level with presents no area, and card stock at full
        // brightness over no area is a bright white line lying across whatever
        // is behind it. `visible` cannot catch this — the normal says the face
        // is comfortably toward us — so it is caught here, where the projection
        // has already had its say. See `CardSolid.MIN_DRAWN_AREA`.
        if (CardSolid.flatArea(flat.map { Vec2(it.x, it.y) }) < CardSolid.MIN_DRAWN_AREA) {
            return@forEach
        }

        val path = Path()
        flat.forEachIndexed { index, at ->
            if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
        }
        path.close()
        drawPath(path, CardStockColour.shaded(StageRig.face(face, eye, look.lighting)))

        // And the seams. A pile's side is not a white band — it is thirty cards
        // seen end-on, and the dark lines between them are most of what says so.
        // Without them a deck reads as a solid block of stock, which is a
        // difference you can see instantly and cannot name until it is fixed:
        // the block is a *ramp*, and a ramp has no count you can read off it.
        val down = Offset(flat[3].x - flat[0].x, flat[3].y - flat[0].y).getDistance()
        val seams = CardSolid.layerLines(layers, down)
        if (seams == 0) return@forEach

        val seam = CardSeamColour.shaded(StageRig.face(face, eye, look.lighting))
        for (line in 1..seams) {
            val t = line.toFloat() / (seams + 1)
            drawLine(
                color = seam,
                start = Offset(
                    flat[0].x + (flat[3].x - flat[0].x) * t,
                    flat[0].y + (flat[3].y - flat[0].y) * t,
                ),
                end = Offset(
                    flat[1].x + (flat[2].x - flat[1].x) * t,
                    flat[1].y + (flat[2].y - flat[1].y) * t,
                ),
                strokeWidth = 1f,
            )
        }
    }
}

// ---- one card's surface ----------------------------------------------------------------

/**
 * What the light does to one card, drawn over its art inside its own layer.
 *
 * Four things, in the order light does them: the face darkens as it turns from
 * the key, a specular pool sits wherever a mirror at this angle would send the
 * lamp, foil splits that pool into the prismatic ramp, and the cut edge picks
 * up a rim that brightens as the card goes edge-on.
 *
 * The pool is the one that matters. A card getting *brighter* when you tilt it
 * is a brightness animation; a pool of light that slides across the face is
 * the reason you tilt a real card to read the small print, and it is the whole
 * difference between a rectangle with a picture on it and a thing made of
 * something.
 */
internal fun DrawScope.drawCardSurface(
    pose: Pose3,
    material: CardMaterial,
    eye: Vec3,
    look: StageLook,
    radiusPx: Float,
    /**
     * How much bite this card gives up for being away from the focus plane.
     *
     * Zero everywhere until somebody turns the dial, and zero on
     * `Scene.MINIMAL` whatever the dial says — the caller decides both, because
     * the depth is a fact about where the camera is and this function is about
     * what the lamp is doing. See `Defocus`, and `docs/classic/DESIGN.md` §7 for why
     * this is allowed to argue with "the brightness does not".
     */
    haze: Float = 0f,
) {
    val shade = Shading.of(pose, material, look.lighting.key, eye)
    val radius = CornerRadius(radiusPx)

    // The face darkens by the same law the edges do, but a card's art is a
    // picture this cannot read, so the shading has to arrive as a veil laid
    // over it. Its opacity is solved in core rather than taken as `1 - diffuse`
    // — alpha composites in the encoding just as a raw multiply does, and a
    // face left uncorrected beside an edge that was corrected is worse than
    // neither, because then the two disagree about where the lamp is. One alpha
    // cannot be exact for every channel of a picture, and `Tone.MID_TONE` says
    // which way it misses — dark art comes back a little bright — and how much
    // worse that gets if the rig's ambient is ever brought down.
    val veil = Tone.veil(shade.diffuse)
    if (veil > 0.004f) {
        drawRoundRect(color = Color.Black.copy(alpha = veil), cornerRadius = radius)
    }

    // Distance, as the only thing it is allowed to take: contrast.
    //
    // Mid grey rather than black or white, and that is the whole of what makes
    // it atmosphere rather than a dimmer. A wash of grey pulls the whites down
    // and the blacks *up* by the same alpha, so nothing gets darker on average
    // and the card simply stops having as much to say — which is what looking
    // at something through air does. Black here would be a card fading out,
    // which is the animation `docs/classic/DESIGN.md` §7 refuses.
    //
    // Over the veil, because the veil is the light on the card and this is the
    // air in front of it, and under the specular, because a highlight seen
    // through haze is still a highlight.
    if (haze > 0.004f) {
        drawRoundRect(
            color = HAZE.copy(alpha = haze.coerceIn(0f, 1f)),
            cornerRadius = radius,
        )
    }

    if (shade.specular > Shading.FAINTEST) {
        val hotspot = Offset(shade.hotspot.x * size.width, shade.hotspot.y * size.height)
        // A highlight is a reflection of the lamp rather than of the room, so
        // it is the one place a temperature arrives undiluted. Everywhere else
        // on this table the rig's white ambient washes most of it back out.
        val lamp = Color.White.shaded(shade.lamp)

        val pool = Brush.radialGradient(
            colors = listOf(
                lamp.copy(alpha = shade.specular),
                lamp.copy(alpha = shade.specular * 0.3f),
                Color.Transparent,
            ),
            center = hotspot,
            radius = size.maxDimension * 0.8f,
        )

        // A round pool everywhere but foil, and on foil an ellipse — which is
        // the whole of `docs/classic/AAA.md` #21 at the drawing end. The stretch is
        // along the draw scope's own x, and that is already the card's width,
        // because this runs inside the homography the card is drawn through:
        // the card's rotation reaches the screen out there, so nothing in here
        // needs an angle and none is carried.
        //
        // Scaling about the hotspot rather than about the card means the pool
        // stretches where it *is* instead of sliding as it grows. The rounded
        // rect goes wide with it and off the card, which costs nothing: the
        // gradient's last stop is transparent, and transparent under Plus is
        // exactly nothing.
        if (shade.streak > 1.001f) {
            withTransform({ scale(shade.streak, 1f, pivot = hotspot) }) {
                drawRoundRect(brush = pool, cornerRadius = radius, blendMode = BlendMode.Plus)
            }
        } else {
            drawRoundRect(brush = pool, cornerRadius = radius, blendMode = BlendMode.Plus)
        }

        // Foil. The ramp is thrown along the axis the pool is travelling on,
        // centred on the pool itself, so the colour sweeps with the tilt and
        // dies with the highlight — colour as light, never as decoration.
        if (material.iridescence > 0f) {
            val away = Offset(shade.hotspot.x - 0.5f, shade.hotspot.y - 0.5f)
            val distance = away.getDistance()
            val axis = if (distance < 0.01f) Offset(1f, 0f) else away / distance
            val reach = size.maxDimension * 0.55f
            val strength = shade.specular * material.iridescence * 0.55f
            val ramp = MasterToolPalette.Prism

            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.26f to ramp[0].copy(alpha = strength),
                        0.38f to ramp[1].copy(alpha = strength),
                        0.5f to ramp[2].copy(alpha = strength),
                        0.62f to ramp[3].copy(alpha = strength),
                        0.74f to ramp[4].copy(alpha = strength),
                        1f to Color.Transparent,
                    ),
                    start = hotspot - axis * reach,
                    end = hotspot + axis * reach,
                ),
                cornerRadius = radius,
                blendMode = BlendMode.Plus,
            )
        }
    }

    // The cut edge. Always faintly there, because a card has one; brighter as
    // the card turns away, because that is when you are looking along it.
    val rim = 0.09f + shade.fresnel * 0.7f
    val hairline = max(1f, size.minDimension * 0.006f)
    drawRoundRect(
        color = Color.White.copy(alpha = rim),
        topLeft = Offset(hairline / 2f, hairline / 2f),
        size = Size(size.width - hairline, size.height - hairline),
        cornerRadius = radius,
        style = Stroke(hairline),
    )
}

// ---- the two things on the table that are not cards -----------------------------

/** The chip a control is printed on: the felt, lifted just enough to be a thing. */
private val ControlColour = Color(0xFF1A1A21)

/**
 * The shuffle marks, lying on the felt under the deck and the extra deck.
 *
 * A mark rather than a label, and that is not a style choice — the mat is a
 * tilted plane, so a word printed on it keystones, and eight-point type read at
 * twenty-one degrees off square is a smudge. Two arrows crossing is the one
 * glyph that means shuffle in every card game anybody has played, and an arrow
 * survives being seen at an angle in a way a letterform does not.
 *
 * Drawn flat at z = 0, where `StagePlane.flatten` is the identity, so this needs
 * no geometry at all — it turns with the table because the table's own layer
 * turns it, which is the whole point of it being *on* the table.
 *
 * This is the first thing ever drawn on this felt that is not a card, and
 * `docs/classic/DESIGN.md` §10 says there should be nothing. The exception is argued for
 * where the geometry lives, in `MatControls`.
 */
internal fun DrawScope.drawMatControls(layout: BoardLayout, field: PlayField) {
    MatControls.all(layout).forEach { (control, slot) ->
        // A control for a pile that is not there is a button that does nothing,
        // and a button that does nothing is worse than no button.
        if (field.pile(control.pile).size < 2) return@forEach

        val radius = CornerRadius(slot.height * 0.28f)
        drawRoundRect(
            color = ControlColour,
            topLeft = Offset(slot.left, slot.top),
            size = Size(slot.width, slot.height),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = MasterToolPalette.LineLight,
            topLeft = Offset(slot.left, slot.top),
            size = Size(slot.width, slot.height),
            cornerRadius = radius,
            style = Stroke(max(1f, slot.height * 0.045f)),
        )

        drawShuffleMark(slot, MasterToolPalette.TextMuted)
    }
}

/**
 * Two arrows crossing, drawn inside [slot].
 *
 * Built from the slot rather than from constants so it is the same mark at every
 * card size the fitter can solve for — the board resizes with the deck, and a
 * glyph in absolute pixels would be a postage stamp on one board and a poster on
 * another.
 */
private fun DrawScope.drawShuffleMark(slot: Slot, colour: Color) {
    val inset = slot.height * 0.28f
    val left = slot.left + inset * 1.4f
    val right = slot.right - inset * 1.4f
    val top = slot.top + inset
    val bottom = slot.bottom - inset
    val stroke = max(1f, slot.height * 0.075f)
    val head = slot.height * 0.16f

    // Two paths that cross in the middle, each ending in an arrowhead — one
    // going down the way, one going up, which is what says "these two swapped".
    listOf(top to bottom, bottom to top).forEach { (from, to) ->
        val path = Path().apply {
            moveTo(left, from)
            cubicTo(
                left + (right - left) * 0.35f, from,
                right - (right - left) * 0.35f, to,
                right, to,
            )
        }
        drawPath(path, colour, style = Stroke(stroke))

        // The head, pointing the way the line arrived.
        val point = Path().apply {
            moveTo(right, to)
            lineTo(right - head, to - head * 0.62f)
            lineTo(right - head, to + head * 0.62f)
            close()
        }
        drawPath(point, colour)
    }
}

/**
 * How rough the playmat is, as an RMS slope.
 *
 * **Solved rather than chosen**, and the reference quantity is the highlight
 * that ships. The night mat's radius today is `max(matWidth, matHeight) × 0.62`,
 * which on the reference stage is 550 pixels; at the table seat the lamp's
 * mirror image lies 2083 pixels from the eye and is scaled onto the felt by
 * 0.629. Solving `(lampRadius + ρ × 2083) × 0.629 = 550` gives ρ = 0.357, and
 * this reproduces 554 — within one per cent of what a player has already seen.
 *
 * So the size of the highlight does not change; only what decides it does. It
 * was a number, and it is now a property of the surface, which is why it can
 * shrink when the camera comes down and a number never could.
 */
/** The felt's own normal: this table is flat and every shadow lands on it. */
private val SURFACE_UP = Vec3(0f, 0f, 1f)

private const val FELT_ROUGHNESS = 0.36f

/**
 * The felt, under a lamp that has a place.
 *
 * The same gradient the wood gets, over the mat's own colour — one shape and two
 * consumers, so the light on the playmat and the light on the desk it is lying
 * on cannot disagree.
 */
private fun feltPool(pool: LightPool, surface: Color): Brush = Brush.radialGradient(
    colorStops = pool.stops.mapIndexed { index, lit ->
        (index / (pool.stops.size - 1f)) to surface.shaded(lit)
    }.toTypedArray(),
    center = Offset(pool.foot.x, pool.foot.y),
    radius = pool.radius,
)

/**
 * The colour distance is seen through.
 *
 * Exactly mid grey — `Tone.MID_TONE` in a colour — so that laying it over a
 * card at any alpha moves the whites and the blacks the same distance toward
 * each other and the mean not at all. A warmer or cooler haze would be a colour
 * grade on the far half of the board, which is the handbook's "colour is
 * meaning or light" being spent on neither.
 */
private val HAZE = Color(0xFF808080)
