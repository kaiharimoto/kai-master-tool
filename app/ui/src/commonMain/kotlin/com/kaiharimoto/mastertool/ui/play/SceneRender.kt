package com.kaiharimoto.mastertool.ui.play

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.kaiharimoto.mastertool.core.layout.StagePlane
import com.kaiharimoto.mastertool.core.motion.Vec3
import com.kaiharimoto.mastertool.core.render.CardSolid
import com.kaiharimoto.mastertool.core.render.Face
import com.kaiharimoto.mastertool.core.render.FaceWash
import com.kaiharimoto.mastertool.core.render.Lit
import com.kaiharimoto.mastertool.core.render.StageLighting
import com.kaiharimoto.mastertool.core.render.StageRig
import com.kaiharimoto.mastertool.core.render.Tone
import com.kaiharimoto.mastertool.core.render.LightPool
import com.kaiharimoto.mastertool.core.scene.Scene
import com.kaiharimoto.mastertool.core.scene.ScenePainter
import com.kaiharimoto.mastertool.core.scene.ScenePiece
import com.kaiharimoto.mastertool.core.scene.reachOf
import com.kaiharimoto.mastertool.core.scene.Surface
import com.kaiharimoto.mastertool.core.scene.TimeOfDay
import com.kaiharimoto.mastertool.ui.gpu.StageShader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.pow
import com.kaiharimoto.mastertool.core.scene.RoomShadow
import com.kaiharimoto.mastertool.core.render.Outset
import com.kaiharimoto.mastertool.core.motion.Vec2

/**
 * The room: the furniture the mat is lying on, and what is behind it.
 *
 * Everything here turns a solved scene into paint and has no opinions of its
 * own, which is the same split `DeckFit` and `BoardLayouter` established.
 * `core/scene` says where the desk is and how tall the wall stands;
 * `core/render` says how the light lands on each face; this file knows which
 * hex value a piece of wood is and nothing else.
 *
 * ## Why the look is a parameter and not snapshot state
 *
 * [StageCameraState.eye] is `mutableStateOf` and the KDoc there explains why:
 * it changes sixty times a second while the table turns, and a draw that does
 * not subscribe to it silently stops re-running — which is how every specular
 * pool on the stage once froze mid-orbit.
 *
 * A [StageLook] is the opposite case. It changes when the user picks a
 * different room, or when the hour crosses dusk, and on no other frame ever. So
 * it travels as an ordinary parameter: when it changes, the composables holding
 * it recompose once and their draw lambdas are rebuilt, which is exactly the
 * right amount of work for a thing that happens twice a day.
 */

// ---- what a room is made of ------------------------------------------------------

/** Card stock, seen edge-on. Warm, because bleached white card does not exist. */
internal val CardStockColour = Color(0xFFE8E3D8)

/** The seam between two cards in a pile: a shadow, not a line somebody drew. */
internal val CardSeamColour = Color(0xFF16130E)

/**
 * Every colour and every light the stage is drawn with, for one room.
 *
 * One value rather than a scattering of file-private constants, because the
 * moment there are two rooms every one of those constants is a place the two
 * can disagree. It carries the rig as well as the palette for the same reason
 * `StageLighting`'s own KDoc gives: a surface lit by one preset beside a surface
 * lit by another is the single failure the rig exists to make impossible, and
 * the cheapest way to guarantee it is for there to be one object to pass.
 *
 * The colours belong to the *scene* and the light belongs to the *hour*. A desk
 * is the same wood at midnight as at noon — what changes is the lamp — and
 * getting that the wrong way round is how a renderer ends up with a day palette
 * and a night palette that have to be kept in step by hand.
 */
@Immutable
internal data class StageLook(
    val scene: Scene,
    val time: TimeOfDay,
    val lighting: StageLighting,
    /** The furniture the playmat is lying on. */
    val table: Color,
    /** What is behind the desk. Dark, out of focus, present. */
    val wall: Color,
    /** What the desk is standing on. */
    val floor: Color,
    /** The window pane, when the light behind it is not doing the talking. */
    val glass: Color,
    /**
     * The top of the sky, where the bottom of it is [glass].
     *
     * Two colours rather than one because a flat fill is not a view of
     * anything: a sky is deep overhead and pale where it meets the distance,
     * and that single gradient is most of what stops an opening in a wall from
     * reading as a lightbox. Painted between the head and the sill of the pane
     * itself, so it turns with the room.
     */
    val sky: Color,
    /** The painted joinery around the pane. */
    val frame: Color,
    /** The lamp's cloth, from outside. */
    val shade: Color,
    /**
     * The inside of that cloth.
     *
     * Bright, and deliberately brighter than anything else in the room. It is
     * the one surface here that is *emissive* at night, and emission arrives as
     * the base colour itself because `Tone` can darken a colour and can never
     * brighten it — so a dim hex here is a lamp that cannot be switched on. By
     * day it is shaded like everything else and comes back dark, because the
     * inside of a shade is the one place in a sunlit room that sees no window.
     */
    val glow: Color,
    /** The lamp's metal: its foot, its stem, its finial. */
    val brass: Color,
    /** The playmat itself. Near enough to ink that the cards still own the screen. */
    val mat: Color,
    /**
     * What a shadow takes the mat down to — never black.
     *
     * A shadow darker than the surface behind it is not a shadow, it is a hole.
     * `docs/classic/AAA.md` #18 says what it should be instead and says it better: a
     * shadow on lit felt is the felt, darker and a little cooler.
     */
    val shadow: Color,
    /**
     * How bright the reflection of the lamp is on the felt, at its centre and
     * at its edge.
     *
     * Renamed from `poolCore`/`poolEdge` and **not renumbered**: they are the
     * strength of an additive highlight and always were. What changes is only
     * where that highlight goes — it used to be placed from the key's
     * *direction*, which cannot move, and it is now the mirror image of a lamp
     * that has a place, so it slides down the felt as you sit down.
     */
    val sheenCore: Float,
    val sheenEdge: Float,
    /**
     * How far the sheen spreads, and how hard the mat darkens toward its
     * corners, for a room whose key has no place.
     *
     * Only the unplaced path reads these. Where there is a real lamp both are
     * derived — the spread from the felt's roughness and the source's size, the
     * darkening from the attenuation itself — which is twelve hand-tuned numbers
     * across three rooms collapsing into the geometry.
     */
    val poolReach: Float,
    val falloff: Float,
) {
    /**
     * The colour of the light itself, for the one thing on the mat that is a
     * reflection of the lamp rather than a surface under it.
     *
     * A pool of lamplight on a rubber mat is warm because the lamp is. Taking it
     * from the rig rather than from a hex value is what stops the felt and the
     * cards disagreeing about what colour the room is lit by.
     */
    val poolColour: Color get() = Color.White.shaded(Lit(1f, lighting.key.warmth))

    fun colourOf(surface: Surface): Color = when (surface) {
        Surface.TABLE -> table
        Surface.WALL -> wall
        Surface.FLOOR -> floor
        Surface.GLASS -> glass
        Surface.FRAME -> frame
        Surface.SHADE -> shade
        Surface.GLOW -> glow
        Surface.BRASS -> brass
    }

    companion object {

        /**
         * The palette for a room, and the rig it is lit by.
         *
         * The rig is handed in rather than looked up, because a placed lamp's
         * position is in mat pixels and this object has never been told how big
         * the board is. `Scenery` solves the room and its lighting together and
         * passes both here, so the two cannot end up describing different
         * tables.
         */
        fun of(scene: Scene, time: TimeOfDay, lighting: StageLighting): StageLook = when (scene) {
            Scene.MINIMAL -> Minimal
            Scene.DESK -> when (time) {
                TimeOfDay.DAY -> DeskDay
                TimeOfDay.NIGHT -> DeskNight
            }
        }.copy(lighting = lighting)

        /**
         * The stage as it has always been, to the hex value.
         *
         * These numbers were file-private constants in `StageRender.kt` and are
         * unchanged. The argument for the two pool alphas, kept because it is
         * still true and still unfinished: they were chosen against solids that
         * came back up to a fifth darker than they now do, before the shading
         * stopped multiplying an sRGB encoding, so the pool is currently a touch
         * dim for the objects standing in it. Re-tuning it is a look-at-the-
         * tablet judgement rather than a correctness fix.
         */
        private val Minimal = StageLook(
            scene = Scene.MINIMAL,
            time = TimeOfDay.NIGHT,
            lighting = StageLighting.Minimal,
            table = Color(0xFF141519),
            wall = Color(0xFF141519),
            floor = Color(0xFF0D0B09),
            glass = Color(0xFFDCE4EA),
            sky = Color(0xFFDCE4EA),
            frame = Color(0xFF141519),
            shade = Color(0xFFE4DAC6),
            glow = Color(0xFFFFF2DC),
            brass = Color(0xFFB08A45),
            mat = Color(0xFF0A0A0E),
            shadow = Color(0xFF04060A),
            sheenCore = 0.062f,
            sheenEdge = 0.022f,
            poolReach = 0.95f,
            falloff = 0.55f,
        )

        /**
         * The desk in daylight: the same wood, under a window.
         *
         * The wood is dark, and that is the one place this scene argues with
         * itself. A real desk in a bright room is a large light surface, and a
         * large light surface beside a deck of cards is the anti-pattern
         * `docs/classic/DESIGN.md` §12 lists first — chrome that competes with the
         * cards. So the stock is a dark walnut rather than a pine, the light
         * does the brightening, and the mat stays the darkest thing in frame.
         *
         * The pool is broad and shallow because that is what a window makes.
         * Daylight arrives from the whole sky and every wall in the room, so
         * there is barely a hotspot and barely a vignette; the mat is nearly
         * evenly lit, and the shadows are what carry the shape instead.
         */
        private val DeskDay = StageLook(
            scene = Scene.DESK,
            time = TimeOfDay.DAY,
            lighting = StageLighting.DeskDay,
            table = Color(0xFF2E2419),
            // Paint, not void. This was `#191B22` — the colour of the space
            // behind the desk — chosen when the wall was a six-pixel hairline
            // and nobody could see it. `Scenery.ROOM_ABOVE` made it a fifth of
            // the screen, and at that size a near-black wall is not a dim room,
            // it is a hole in one. Rendered it lands near luminance 65 against a
            // window at 223 and a lit card at 141: darker than either, which is
            // the handbook's rule, and clearly a surface, which is the point.
            wall = Color(0xFF4B463D),
            // Boards, not void — and this is the wall's own correction arriving
            // one release later at the surface nobody could see either. It was
            // `#0D0B09`, an albedo of thirteen out of 255, which is not a dark
            // floor: it is the absence of one, and everything the shading does
            // is a multiply, so no light in the room could ever have found it.
            // It went unnoticed for as long as the walls stood in the air and
            // the desk hid what was under them. Now they reach it, the room is
            // closed, and the floor is a lit wedge in the bottom corner of the
            // picture at any yaw.
            //
            // Under the desk top rather than over it. A floor further from every
            // light in the room than the desk is *should* come back darker, and
            // it does — that is the rig's job. What it may not be is a colour
            // the rig has nothing to work with.
            floor = Color(0xFF2A2118),
            glass = Color(0xFFDCE4EA),
            sky = Color(0xFF8CB0D4),
            // Gloss paint on softwood, and the brightest thing in the room after
            // the sky it surrounds. A window frame is nearly always the lightest
            // object in a photograph of a window, because it is the one surface
            // angled to catch what is coming through.
            frame = Color(0xFF6E675B),
            shade = Color(0xFFE4DAC6),
            glow = Color(0xFFFFF2DC),
            // Lacquered brass, dark on purpose: everything the shading does is a
            // multiply, so a base colour is the brightest that surface will ever
            // be. Bright, it is a yellow stick beside the cards at every angle;
            // dark, it is nearly the wood until the highlight lands on it.
            brass = Color(0xFFB08A45),
            mat = Color(0xFF0C0C11),
            shadow = Color(0xFF07080E),
            sheenCore = 0.058f,
            sheenEdge = 0.032f,
            poolReach = 1.05f,
            falloff = 0.34f,
        )

        /**
         * The same desk at night, with one lamp on it.
         *
         * Identical wood and identical wall — a room does not repaint itself at
         * dusk — and everything that differs is light. The pool is small, hot
         * and hard-edged because a bulb a foot above a table is very nearly a
         * point source, and the fall-off is the strongest in the app because
         * past the lamp's reach there is genuinely nothing lighting the desk.
         *
         * That fall-off is doing the work the ambient is not allowed to do. See
         * [StageLighting]: dropping the rig's ambient to buy darkness would
         * break the single-veil approximation that shades card art, so the
         * darkness is bought outside the cards instead — which is also the
         * handbook's own preference, since a card is supposed to be the
         * brightest object in the frame.
         */
        private val DeskNight = StageLook(
            scene = Scene.DESK,
            time = TimeOfDay.NIGHT,
            lighting = StageLighting.DeskNight,
            table = Color(0xFF2E2419),
            // The same paint as by day. A room does not repaint itself at dusk
            // and the whole difference between the two presets is light — which
            // for this wall means the lamp standing a card away from it, and the
            // fall-off from there into the far corner.
            wall = Color(0xFF4B463D),
            // The same boards. A room does not relay its floor at dusk either.
            floor = Color(0xFF2A2118),
            glass = Color(0xFFDCE4EA),
            // Both ends of the night sky come back at two per cent, so the
            // gradient is a formality; what it buys is that the pane is never
            // *flat*, which is the one thing a dark window must not be.
            sky = Color(0xFFB6C6DC),
            frame = Color(0xFF6E675B),
            shade = Color(0xFFE4DAC6),
            glow = Color(0xFFFFF2DC),
            // The same brass. A room does not repaint its fittings at dusk
            // either, and the whole difference between the two is the lamp —
            // which is a point source close by, so this is the one surface in
            // the app that gets a real moving highlight rather than a wash.
            brass = Color(0xFFB08A45),
            mat = Color(0xFF0C0C11),
            shadow = Color(0xFF05060B),
            sheenCore = 0.115f,
            sheenEdge = 0.020f,
            poolReach = 0.62f,
            falloff = 0.74f,
        )
    }
}

// ---- the two helpers every surface on the stage goes through -----------------------

/**
 * A colour under a light, which is a multiply and not an alpha.
 *
 * Fading toward transparent is what a *thinner* object does; getting darker is
 * what an unlit one does, and on a black stage the two happen to look similar
 * until something is drawn behind them, at which point only one of them is
 * still right.
 *
 * The multiply goes through [Tone] because a `Color`'s channels are sRGB, and
 * multiplying an encoding is not multiplying light: raw, a surface at half
 * illumination comes back nearer a quarter as bright, and every shaded edge on
 * the table slides toward the same muddy grey instead of staying its own
 * colour.
 *
 * Three channels rather than one, because the rig's lamps have temperatures and
 * a lamp's colour only exists once it has landed on something. [Lit] works out
 * the three multipliers itself so that nothing here — and nothing in any other
 * renderer — can invent its own mapping from a warmth to a colour.
 */
internal fun Color.shaded(lit: Lit): Color = Color(
    Tone.shade(red, lit.red),
    Tone.shade(green, lit.green),
    Tone.shade(blue, lit.blue),
    alpha,
)

/**
 * The faces of a solid you can actually see, in the order they must be painted.
 *
 * Two jobs and one traversal, because they are the same question asked twice.
 * [CardSolid.visible] answers *whether* a face is toward the camera, and it is
 * asked with the camera's **position** — `StagePlane.eyePoint` — rather than
 * with the direction the whole stage is lit from, because the projection those
 * faces are about to go through divides by distance and a direction is a lens
 * infinitely far away. That disagreement is what used to leave the deck with no
 * side walls to see it through.
 *
 * Then the order. A slab's walls were painted in the order the geometry happens
 * to list them — back, far, right, near, left — and near a corner-on view that
 * paints the far wall over the near one. Sorting by the depth of each face's own
 * centre is the painter's algorithm applied to six quads, which is cheap enough
 * to be beneath discussion and is the only thing that makes a solid look solid
 * from a corner.
 */
internal fun List<Face>.facingTheCamera(stage: StagePlane, eye: Vec3): List<Face> =
    CardSolid.visible(this, stage.eyePoint(eye))
        .sortedBy { stage.project(it.centre).depth }

// ---- the room ----------------------------------------------------------------------

/**
 * Everything past the felt, painted from furthest to nearest.
 *
 * ## The sort is per object first, per face second
 *
 * Faces inside one box are ordered by the depth of their own centres, which is
 * what `facingTheCamera` has always done and is right for a convex solid.
 * *Between* boxes that is not enough, and the wall is the counter-example that
 * proves it: it stands behind the desk and its front face reaches up into the
 * air, so the centre of that face is nearer the camera than the middle of the
 * desk top even though every part of the wall is further away in the room. Sort
 * by face centres alone and the wall paints over the desk.
 *
 * So a box is ordered by the closest point it reaches — the largest depth of its
 * eight corners — which is the ordinary painter's rule for solids that do not
 * interpenetrate, and none of them do. It costs eight projections per piece,
 * for a room that is eighteen.
 *
 * ## Why the top face is not special-cased
 *
 * `drawTable` used to skip the face whose normal pointed straight up and draw a
 * flat `drawRect` for it afterwards, on the true observation that `flatten` is
 * the identity at z = 0. That was an optimisation for the one solid that was
 * guaranteed to be lying flat, and the room is full of solids that are not. The
 * general path costs four flattens that happen to be identities and removes the
 * only place the table could have been drawn by different arithmetic than
 * everything standing on it.
 */
internal fun DrawScope.drawScene(
    pieces: List<ScenePiece>,
    stage: StagePlane,
    eye: Vec3,
    look: StageLook,
    pool: LightPool? = null,
    /** The desk's timber, if this platform has a runtime shader. */
    wood: StageShader? = null,
    grainOrigin: Pair<Float, Float> = 0f to 0f,
    grainPitch: Float = 1f,
) {
    if (pieces.isEmpty()) return
    val eyeAt = stage.eyePoint(eye)

    ScenePainter
        .order(pieces, eyeAt) { box -> stage.reachOf(box) }
        .forEach { piece ->
            // The inside of a shell, first. `ScenePiece.lining` carries the
            // argument for why before rather than interleaved: culling has
            // already removed the near half of it, so every face left is behind
            // every face of the shell around it.
            piece.lining?.let { lining ->
                drawSolid(
                    faces = lining.faces,
                    stage = stage,
                    eyeAt = eyeAt,
                    eye = eye,
                    look = look,
                    colour = look.colourOf(lining.surface),
                    surface = lining.surface,
                    emission = lining.emission,
                    pool = pool,
                )
            }
            drawSolid(
                faces = piece.mesh ?: piece.box.faces(),
                stage = stage,
                eyeAt = eyeAt,
                eye = eye,
                look = look,
                colour = look.colourOf(piece.surface),
                surface = piece.surface,
                emission = piece.emission,
                pool = pool,
                wood = wood,
                grainOrigin = grainOrigin,
                grainPitch = grainPitch,
            )
        }
}

/**
 * How wide a facet is drawn past its own edge, in pixels.
 *
 * A seam, and it is antialiasing rather than geometry. Two faces that share an
 * edge each cover about half of the pixels along it, and a half over a half is
 * three quarters — so every shared edge in a solid comes out as a hairline of
 * the background showing through. On a box it is four faint lines nobody
 * mentioned in two years. On a turned lamp it is twenty-six of them radiating
 * out of the middle of the shade, and the object reads as its own wireframe.
 *
 * The fix is to let each face paint a pixel past itself: stroke the same path
 * with the same paint, so the pixels along the edge are covered twice by
 * whichever face wants them and the seam has nowhere to be. It grows the
 * silhouette by half a pixel, which is the price and is not a visible one.
 */
private val Seam = Stroke(width = 1f)

/**
 * And why the minimal stage does not get it.
 *
 * Closing the seam also grows a solid's own outline by half a pixel, which on
 * `Scene.MINIMAL` — one slab, four seams nobody has ever seen — is a change to
 * the handbook's stage in exchange for nothing. `docs/classic/LOOP.md`'s mandate is not
 * a preference about that: minimal is the **control** the room is judged
 * against, and iteration 7's own note is that it "comes out bit-identical,
 * which is the mandate's requirement and is checked rather than assumed". A
 * quarter of one per cent of its pixels moving by nine levels is still moving.
 */
private fun StageLook.closesSeams(): Boolean = scene != Scene.MINIMAL

/** Fill, and then close the seam — which is one operation and never one call. */
private fun DrawScope.facet(path: Path, colour: Color, look: StageLook) {
    drawPath(path, colour)
    if (look.closesSeams()) drawPath(path, colour, style = Seam)
}

private fun DrawScope.facet(
    path: Path,
    brush: Brush,
    look: StageLook,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    drawPath(path, brush, blendMode = blendMode)
    if (look.closesSeams()) drawPath(path, brush, style = Seam, blendMode = blendMode)
}

/**
 * One solid, painted: back-face culled, depth-sorted among its own faces, and
 * shaded a face at a time.
 *
 * Shared by the furniture and the prop deliberately. Every rule in here was
 * learned the hard way once — which faces to skip near the lens, why an emissive
 * surface is not shaded, why the light pool goes only on a face that is both
 * level and at z = 0 — and a second copy of it is a second place for those to be
 * re-learned.
 */
private fun DrawScope.drawSolid(
    faces: List<Face>,
    stage: StagePlane,
    eyeAt: Vec3,
    eye: Vec3,
    look: StageLook,
    colour: Color,
    surface: Surface,
    emission: Lit?,
    pool: LightPool?,
    wood: StageShader? = null,
    grainOrigin: Pair<Float, Float> = 0f to 0f,
    grainPitch: Float = 1f,
) {
    CardSolid.visible(faces, eyeAt)
        .sortedBy { stage.project(it.centre).depth }
        .forEach { face ->
            // Past the lens, the projection stops being a projection: it clamps
            // rather than dividing by zero, so the quad flies a few hundred
            // thousand pixels away instead of vanishing, and a stripe of desk
            // crosses the picture. Nothing in the room reaches it at any legal
            // pose today; `StagePlane.reaches` carries the argument and
            // `SceneryTest` carries the measurement.
            if (face.corners.any { !stage.reaches(it) }) return@forEach

            val path = Path()
            face.corners.forEachIndexed { index, corner ->
                val flat = stage.flatten(corner)
                if (index == 0) path.moveTo(flat.x, flat.y) else path.lineTo(flat.x, flat.y)
            }
            path.close()

            if (emission != null && surface == Surface.GLASS && face.normal.z < 0.99f) {
                // The one emissive face in the room that is a *view* rather than
                // a fixture. Its two ends are the same arithmetic as the one
                // above, evaluated at the head and at the sill; only the
                // interpolation between two correct colours is an approximation,
                // which is the same bargain `poolBrush` strikes.
                //
                // The upward-facing top of the pane's own box is excluded, since
                // a sky drawn across a sliver seen edge-on is a bright line.
                facet(path, skyBrush(face, stage, look, emission), look)
            } else if (emission != null) {
                // A fixture is not shaded, and that is the physics rather than a
                // shortcut: how bright a lit lampshade is does not depend on what
                // is lighting the room it is in. It is also the only way to draw
                // one at all — `Tone` can darken a colour and can never brighten
                // it, so radiance has to arrive as the base colour itself.
                //
                // Not evenly bright, though. The bulb sits low inside the shade,
                // so the cloth is brightest near the rim and falls away toward
                // the opening; drawn flat it is a cream shape with no light in
                // it. `StageRig.spill` returns null for the faces with no height
                // to grade over — the rim, the underside — and those are right
                // to be flat.
                val spill = StageRig.spill(face, emission)
                if (spill == null) {
                    facet(path, colour.shaded(emission), look)
                } else {
                    facet(path, washBrush(spill, stage, colour), look)
                }
            } else if (surface == Surface.TABLE) {
                // The desk keeps the ordinary rig, because the pool below is
                // already the lamp's answer for this surface. Handing it the
                // room's falloff as well would darken the wood twice for the
                // same lamp, and the seam between the two would land exactly
                // where the pool's edge is.
                facet(path, colour.shaded(StageRig.face(face, eye, look.lighting)), look)
            } else {
                val wash = StageRig.wash(face, eye, look.lighting)
                if (wash == null) {
                    facet(path, colour.shaded(StageRig.room(face, eye, look.lighting)), look)
                } else {
                    facet(path, washBrush(wash, stage, colour), look)
                }
            }

            // And the lamp mirrored *in* it, which is the one term the room's
            // rig does not have: `StageRig.lit` is ambient plus lambert plus a
            // graze-gated rim and there is no specular anywhere in it, so brass
            // and cloth have always differed in colour and in nothing else.
            //
            // Additive, and beside the rig rather than inside it. Inside, it
            // would move all three of `GoldenStageTest`'s dumps, and
            // `docs/classic/LOOP.md` §3 forbids re-recording a golden and changing
            // behaviour in one release. Beside, it is the same shape as the
            // pool and the wash, it cannot darken anything, and a bug in it
            // cannot blank a surface.
            if (emission == null) {
                StageRig.gleam(face, eyeAt, look.lighting, surface.gloss)?.let { shine ->
                    val brush = if (shine.stops.size < 2) {
                        SolidColor(Color.White.shaded(shine.stops.first()))
                    } else {
                        washBrush(shine, stage, Color.White)
                    }
                    // Filled and **not** stroked, unlike every other pass here.
                    // Closing a seam means painting its pixels twice, which is
                    // free when the paint replaces what is under it and is a
                    // doubling when the paint adds to it — so the seamed
                    // version of this drew a bright spoke along every edge of
                    // the lamp's foot and turned the highlight into a starburst
                    // for the second time in one afternoon. There is nothing to
                    // close here anyway: the fill underneath already did.
                    drawPath(path, brush, blendMode = BlendMode.Plus)
                }
            }

            // And the light the lamp throws on it — for a surface facing straight
            // up **and lying at z = 0**, which is the desk top and nothing else.
            //
            // The plane matters as much as the normal. The pool is drawn as a
            // circle in the mat's own coordinates, which is the right shape only
            // because `StagePlane.flatten` is the identity at z = 0; on the
            // floor, thirteen hundred pixels down, the path has been flattened
            // and the gradient has not, so the two would disagree about where the
            // lamp is by more than the pool is wide. The floor is also in the
            // desk's shadow, which is the physical version of the same answer.
            if (
                pool != null &&
                emission == null &&
                face.normal.z > 0.99f &&
                abs(face.centre.z) < 0.5f
            ) {
                facet(path, poolBrush(pool, colour), look)
            }

            // And the timber itself. Same test as the pool — facing straight up
            // and lying at z = 0 is the desk top and nothing else — because the
            // grain is drawn in the mat's own coordinates and `flatten` is the
            // identity only there.
            //
            // After the pool rather than before it: the pool decides how much
            // light this patch of desk is under, and the grain modulates
            // whatever that came to. The other order would have the figure
            // fixed and the light sliding over it, which is a photograph of a
            // desk rather than a desk.
            if (
                wood != null &&
                emission == null &&
                face.normal.z > 0.99f &&
                abs(face.centre.z) < 0.5f
            ) {
                drawPath(
                    path,
                    WoodGrain.brushFor(
                        shader = wood,
                        origin = grainOrigin,
                        cardWidth = grainPitch,
                        key = look.lighting.key.direction,
                        eye = eye,
                        density = stage.jacobian(face.centre.x, face.centre.y),
                    ),
                    blendMode = BlendMode.Overlay,
                )
            }
        }
}

/**
 * The room's own light across one face, as a gradient.
 *
 * The counterpart of [poolBrush] for a surface that is not the table, and the
 * same bargain: every stop is the surface's own colour under a real `Lit`, so
 * nothing composites a wash over a colour it does not know, and only the
 * interpolation between two correct colours is an approximation.
 *
 * Both ends are flattened rather than projected, because `flatten` is what the
 * path being filled was built with — a gradient aimed at the projected point
 * and a polygon drawn at the flattened one would slide apart the moment the
 * face left z = 0, which for the wall is always.
 */
private fun washBrush(wash: FaceWash, stage: StagePlane, surface: Color): Brush {
    val from = stage.flatten(wash.from)
    val to = stage.flatten(wash.to)
    return Brush.linearGradient(
        colorStops = wash.stops.mapIndexed { index, lit ->
            (index / (wash.stops.size - 1f)) to surface.shaded(lit)
        }.toTypedArray(),
        start = Offset(from.x, from.y),
        end = Offset(to.x, to.y),
    )
}

/**
 * What is on the other side of the glass, as a gradient up the pane.
 *
 * Not a view of anything in particular, and deliberately not: the sun cannot
 * appear in this window and that was measured rather than assumed. The room's
 * vertical axis is drawn compressed — a wall four and a half card widths tall
 * standing under an eye eleven and a half card widths up — so a ray traced back
 * along the day key crosses this wall's plane about 2 700 pixels above a head
 * that is at 232, at every seat the camera can reach. Bringing the sun's disc
 * into the opening would need light arriving from *below* the eye. So what the
 * window shows is sky, graded, and the sun stays outside the frame where the
 * arithmetic puts it. `docs/classic/LOOP.md` iteration 8 carries the numbers.
 *
 * The axis is the pane's own: the midpoint of its bottom edge to the midpoint of
 * its top, both flattened, so the gradient turns with the room and needs no
 * screen-space assumption at all. Which corners those are is read off the face's
 * z rather than off its winding, because the winding is a property of which face
 * of a box this is and this has to be right for whichever one the camera sees.
 */
private fun skyBrush(face: Face, stage: StagePlane, look: StageLook, lit: Lit): Brush {
    val byHeight = face.corners.sortedBy { it.z }
    val foot = stage.flatten((byHeight[0] + byHeight[1]) * 0.5f)
    val crown = stage.flatten((byHeight[2] + byHeight[3]) * 0.5f)
    val low = look.glass.shaded(lit)
    val high = look.sky.shaded(lit)
    return Brush.linearGradient(
        // Three stops, weighted low. A sky's brightness falls off fastest just
        // above the haze and then hardly at all, so an even ramp reads as a
        // painted backdrop and a biased one reads as air.
        colorStops = arrayOf(
            0f to low,
            0.38f to lerp(low, high, 0.62f),
            1f to high,
        ),
        start = Offset(foot.x, foot.y),
        end = Offset(crown.x, crown.y),
    )
}

/**
 * The lamp's light on a flat surface, as a gradient.
 *
 * Every stop is the surface's own colour under a real [Lit] rather than a wash
 * of some other colour laid over it, so nothing here composites light over a
 * backdrop it does not know: the arithmetic is exact at each stop and only the
 * interpolation between two correct colours is an approximation.
 *
 * Drawn as a circle in the mat's own coordinates, which is right because
 * `StagePlane.flatten` is the identity at z = 0 — so the ellipse it becomes on
 * screen is the one the camera would have made. That is the same property the
 * shuffle marks rely on, and it is why a pool costs one path rather than a
 * subdivision.
 */
private fun poolBrush(pool: LightPool, surface: Color): Brush = Brush.radialGradient(
    colorStops = pool.stops.mapIndexed { index, lit ->
        (index / (pool.stops.size - 1f)) to surface.shaded(lit)
    }.toTypedArray(),
    center = Offset(pool.foot.x, pool.foot.y),
    radius = pool.radius,
)

/**
 * The shadows the room throws on its own desk, painted with the desk.
 *
 * A decal on a receiver, never a piece: `ScenePainter` sorts the room by a
 * separating axis and there is no depth buffer for anything to disagree with, so
 * a shadow that entered the ordering would be the one thing able to contradict
 * it. This runs between the ground pieces and the standing ones — after the desk
 * it lands on and before the lamp that throws it — which is the seam the felt
 * already uses.
 *
 * Darkened by `StageRig.occlusion` rather than by a chosen alpha, so it is the
 * desk seen with the key taken away rather than a grey shape laid over it. Same
 * arithmetic as a card's shadow, and deliberately: two shadow models on one
 * surface would disagree about the room the moment either was tuned.
 */
internal fun DrawScope.drawRoomShadows(
    shadows: List<RoomShadow>,
    stage: StagePlane,
    eye: Vec3,
    look: StageLook,
) {
    if (shadows.isEmpty()) return

    shadows.forEach { shadow ->
        val flat = shadow.corners.map { corner ->
            val landed = stage.flatten(corner)
            Offset(landed.x, landed.y)
        }
        if (flat.size < 3) return@forEach

        val darkest = StageRig.occlusion(
            SURFACE_UP,
            eye,
            look.lighting,
            shadow.corners.firstOrNull(),
        )
        if (darkest <= 0.004f) return@forEach

        // Feathered the way a card's is: a handful of rings rather than one hard
        // quad, because the lamp has a real angular size and its edge is soft
        // over several mat pixels. Fewer rings than a card gets — this is
        // furniture, it never moves, and nobody is looking at its edge.
        val rings = if (shadow.spread <= 1f) 1 else ROOM_SHADOW_RINGS
        val step = 1f - (1f - darkest).pow(1f / rings)

        for (ring in 0 until rings) {
            val grow = shadow.spread * ring / rings
            val path = Path().apply {
                val grown = if (grow <= 0f) flat.map { Vec2(it.x, it.y) }
                else Outset.of(flat.map { Vec2(it.x, it.y) }, grow)
                moveTo(grown[0].x, grown[0].y)
                grown.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, color = look.shadow.copy(alpha = step))
        }
    }
}

/** The felt's own normal, for the surface every room shadow lands on. */
private val SURFACE_UP = Vec3(0f, 0f, 1f)

/**
 * How many rings a room shadow's penumbra gets.
 *
 * Three rather than the card's five. The lamp does not move, its shadow does not
 * move, and the edge nobody is looking at does not need two more draw calls per
 * frame to be soft enough.
 */
private const val ROOM_SHADOW_RINGS = 3
