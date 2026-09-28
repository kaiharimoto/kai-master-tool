package com.kaiharimoto.mastertool.core.scene

import com.kaiharimoto.mastertool.core.layout.BoardLayout
import com.kaiharimoto.mastertool.core.layout.BoardLayouter
import com.kaiharimoto.mastertool.core.layout.Slot
import com.kaiharimoto.mastertool.core.motion.Pose3
import com.kaiharimoto.mastertool.core.motion.Vec2
import com.kaiharimoto.mastertool.core.motion.Vec3
import com.kaiharimoto.mastertool.core.render.Face
import com.kaiharimoto.mastertool.core.render.Light
import com.kaiharimoto.mastertool.core.render.Lit
import com.kaiharimoto.mastertool.core.render.Ring
import com.kaiharimoto.mastertool.core.render.StageLighting
import com.kaiharimoto.mastertool.core.render.StageRig
import com.kaiharimoto.mastertool.core.render.Turned
import com.kaiharimoto.mastertool.core.tune.RoomTune
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * Which room the table is in.
 *
 * A preference rather than a constant, on exactly the argument `docs/classic/AAA.md`
 * #67 makes: rubber playmats are matte and a bare table is not, and which one
 * you are playing on is the user's decision rather than the renderer's.
 *
 * [MINIMAL] is the stage `docs/classic/DESIGN.md` describes and it does not change. The
 * desk scenes are a *different contract*, written down in that handbook's
 * "Scenes" section: they may hold objects that are there because they are nice
 * rather than because they are needed, which is decoration, which minimal mode
 * bans. What survives in both is the rule that nothing idles — a room that
 * breathes when nobody is touching it is an engine with nothing to say.
 */
@Serializable
enum class Scene {
    MINIMAL,
    DESK,
    ;

    val displayName: String
        get() = when (this) {
            MINIMAL -> "Minimal"
            DESK -> "Desk"
        }
}

/** Which lamp is on in the desk scene, or whether to let the clock decide. */
@Serializable
enum class DeskLight {
    AUTO,
    DAY,
    NIGHT,
    ;

    val displayName: String
        get() = when (this) {
            AUTO -> "Auto"
            DAY -> "Day"
            NIGHT -> "Night"
        }
}

/** The answer [DeskLight] resolves to: a window, or a lamp. */
enum class TimeOfDay { DAY, NIGHT }

/**
 * What time it is, as far as a room is concerned.
 *
 * A pure function of a setting and an hour so that it can be tested without a
 * clock, which is the only reason it is a separate thing from reading the time.
 * The caller supplies the hour; this decides what it means.
 */
object DeskClock {

    /** The first hour of daylight, and the first hour after it. */
    const val DAWN = 7
    const val DUSK = 19

    /**
     * @param hour the local hour, 0..23. Anything outside that is wrapped rather
     *   than rejected: a scene is not worth an exception, and a platform that
     *   hands back a nonsense hour should still get a lit room.
     */
    fun resolve(setting: DeskLight, hour: Int): TimeOfDay = when (setting) {
        DeskLight.DAY -> TimeOfDay.DAY
        DeskLight.NIGHT -> TimeOfDay.NIGHT
        DeskLight.AUTO -> {
            val local = ((hour % 24) + 24) % 24
            if (local in DAWN until DUSK) TimeOfDay.DAY else TimeOfDay.NIGHT
        }
    }
}

/** What a piece of the room is made of, as far as the renderer is concerned. */
enum class Surface {
    /** The table the mat is lying on: the minimal stage's slab, or the desk. */
    TABLE,

    /** The wall the desk is pushed against, and the jambs the window leaves in it. */
    WALL,

    /** What the desk is standing on. Dark, and mostly out of frame. */
    FLOOR,

    /** The window pane: sky by day, near enough to nothing at night. */
    GLASS,

    /**
     * The joinery around that pane: two stiles, two rails and a pair of bars.
     *
     * Its own surface rather than [WALL] because painted timber and painted
     * plaster are the one pair of materials in this room a person can tell
     * apart at a glance, and because it is the whole reason the opening reads
     * as a window rather than as a bright rectangle somebody cut in a wall.
     */
    FRAME,

    /** The cloth of the lampshade, seen from outside. */
    SHADE,

    /**
     * The inside of that cloth, which is a different surface and not a darker
     * one.
     *
     * By day it is the one part of the room in shadow at noon — a shade's inside
     * sees no window. At night it is the brightest thing on the stage, because
     * it is what the bulb is actually shining on. The same hex value does both,
     * which is the point: it is a *bright* warm white that daylight barely
     * reaches and lamplight is emitted straight off, so the two hours differ by
     * light and not by paint, exactly as `StageLook` insists everything else in
     * this room does.
     */
    GLOW,

    /**
     * Lacquered brass: the lamp's foot, its stem, its finial.
     *
     * Its own surface rather than [SHADE] for the reason [FRAME] is not [WALL] —
     * a lamp is two materials, cloth and metal, and drawing both in one cream
     * was most of why the thing read as folded card. Brass is also the only
     * surface in the room whose highlight is worth computing.
     */
    BRASS,
    ;

    /**
     * How this surface mirrors the light, which is the half of "what a thing is
     * made of" the room has never had.
     *
     * Here rather than in the renderer for the reason the whole enum exists: a
     * finish is a fact about a material, and the alternative is a `when` in
     * whatever happens to be drawing, which is where two of them start
     * disagreeing. `StageRig.Gloss` carries the argument for the numbers.
     */
    val gloss: StageRig.Gloss
        get() = when (this) {
            BRASS -> StageRig.Gloss.Brass
            FRAME -> StageRig.Gloss.Paint
            // Wood is lacquered and already has a highlight of its own, from a
            // shader that knows where its grain runs; a second one computed off
            // the flat normal would sit in a different place and argue with it.
            TABLE, WALL, FLOOR, GLASS, SHADE, GLOW -> StageRig.Gloss.None
        }
}

/**
 * One object in the room: a shape, and what it is made of.
 *
 * [name] is for tests and for reading a failure, not for the renderer — which
 * asks only [surface] and [box]. It is a plain string rather than an id type
 * because nothing looks a piece up; the room is drawn whole, every frame, in
 * the order the camera puts it in.
 */
data class ScenePiece(
    val name: String,
    val surface: Surface,
    val box: SceneBox,
    /**
     * Light this piece *emits*, rather than light it receives. Null for anything
     * that is only a surface.
     *
     * A [Lit] because that type already means exactly "an amount of light, and
     * what colour it was", and because the room's two fixtures disagree about
     * colour: the shade at night is tungsten and the pane by day is sky.
     *
     * A renderer handed one of these does not call the rig at all, and that is
     * the physics rather than a shortcut — an emissive surface's brightness does
     * not depend on what is lighting the room it is in. It is also the only way
     * to draw one: `Tone.shade` can darken a colour and can never brighten it,
     * so radiance has to arrive as the base colour itself.
     */
    val emission: Lit? = null,
    /**
     * The shape this piece really is, when a box is not it.
     *
     * Trailing and null by default, so every piece written before this existed
     * goes on being drawn from `box.faces()` and is bit-identical — the same
     * inert-default discipline `CardSolid.slab`'s `backScale = 1f` and
     * `Light.position = null` already keep, and for the same reason: this file
     * is upstream of a golden.
     *
     * [box] does not stop mattering when this is set. It goes on being what the
     * room is sorted by, what `SceneryTest` measures, and what has to clear the
     * mat — because `ScenePainter` needs a separating axis and a bounding box
     * supplies one exactly as well as the real solid would, while being a shape
     * the painter can reason about at all. What the mesh may not do is leave the
     * box: a piece drawn outside the volume it was sorted by is a piece that can
     * be painted in the wrong order, and `SceneryTest` holds it.
     *
     * The real rule is the painter's algorithm rather than convexity: the
     * renderer orders a piece's faces by the depth of their own centres, so the
     * faces the camera can *see* have to come out in the right order that way. A
     * convex solid satisfies that for free and is the safe answer — a lamp is
     * four pieces rather than one for exactly this reason, since its cove and
     * its overhanging shade are not.
     *
     * A shell is the one exception in the room, and it earns it by argument
     * rather than by inspection: back-face culling removes the outer far side
     * and the inner near side of a cone before the sort ever sees them, so what
     * is left is the inside of the far half, then the rim, then the outside of
     * the near half, and no pair of those can be got wrong. See
     * [Scenery.lampShade]. Anything else that is not convex wants the same
     * paragraph written for it before it goes here.
     */
    val mesh: List<Face>? = null,
    /**
     * The faces of this piece that are made of something else.
     *
     * One object, two materials, and the alternative is two pieces — which the
     * room cannot have, because the inside and the outside of a lampshade have
     * the same bounding box on every axis and `SceneryTest` rightly refuses two
     * pieces that share a volume.
     *
     * Drawn **before** [mesh], and that is an ordering claim rather than a
     * convenience. Everything a lining can be is the far side of a shell seen
     * through its own opening: back-face culling has already removed the near
     * half of it, so every face left is behind every face of the shell around
     * it, from any seat the camera can reach.
     */
    val lining: Lining? = null,
)

/** Some of a piece's faces, made of something else. See [ScenePiece.lining]. */
data class Lining(
    val surface: Surface,
    val faces: List<Face>,
    val emission: Lit? = null,
)

/**
 * Everything past the felt, solved.
 *
 * A list, in no particular order — the renderer sorts it by projected depth,
 * because which piece is in front of which is a fact about where the camera is
 * and this is computed before the camera has been consulted.
 */
data class SceneModel(
    val pieces: List<ScenePiece>,
    /**
     * The rig this room is lit by.
     *
     * Here rather than beside the preset because a lamp with a *place* has that
     * place in mat pixels, and mat pixels are a fact about the board's layout.
     * A rig and the room it lights are solved together or they are solved
     * against different tables.
     */
    val lighting: StageLighting = StageLighting.Minimal,
) {
    /**
     * Everything at or below the table top, and everything standing on it.
     *
     * The felt is drawn between the two, because the felt lies *on* the desk and
     * *under* the lamp, and a single pass could only ever put it on one side.
     * Well defined because no piece of this room straddles z = 0 — which is a
     * test, not a hope.
     */
    val ground: List<ScenePiece> get() = pieces.filter { it.box.max.z <= 0f }
    val standing: List<ScenePiece> get() = pieces.filter { it.box.max.z > 0f }

    /**
     * What the room throws on its own desk top, solved with the furniture.
     *
     * Everything standing on the desk, and only onto the desk: `AAA.md` #61d's
     * "as a set" is a promise about one surface at a time, and the surface every
     * fixture on this stage is touching is z = 0. A shadow does not bend over an
     * edge onto a second receiver unless it is cast against that one too, which
     * is a thing to add when there is a second thing standing somewhere else.
     *
     * Painted between [ground] and [standing] — after the desk it lands on and
     * before the lamp that throws it — which is the same seam the felt already
     * uses and for the same reason.
     */
    val deskShadows: List<RoomShadow>
        get() {
            // The desk is the ground piece whose top *is* the surface, and the
            // biggest of those — finding it by geometry rather than by name
            // means a renamed piece cannot silently stop casting. The area is
            // not a tie-break for tidiness: the walls reach the floor now, so
            // the three skirts share the desk's own top edge exactly, and the
            // desk beats each of them by a factor of forty.
            val desk = ground
                .filter { it.box.max.z >= ground.maxOf { other -> other.box.max.z } }
                .maxByOrNull { (it.box.max.x - it.box.min.x) * (it.box.max.y - it.box.min.y) }
                ?: return emptyList()
            return RoomShadows.onSurface(standing, lighting.key, desk.box)
        }

    companion object {
        val Empty = SceneModel(emptyList())
    }
}

/**
 * The room, solved in core the way `DeckFit` and `BoardLayouter` are.
 *
 * ## The one rule every piece obeys
 *
 * **Nothing in here overlaps the mat.** Not a preference — the reason the play
 * stage can hold a room at all without the foundation-sized work in
 * `docs/classic/AAA.md` #92 and #93. Cards are one composable each, sorted into
 * `PlayScreen`'s `ordered` list; the room is painted in the single canvas
 * underneath all of them. Those are two orderings, and the only thing that
 * keeps them from contradicting each other is that no object in the second one
 * can ever need to be in front of an object in the first. A mug on the felt
 * would need exactly that, the first time a card was carried past it.
 *
 * So the felt is the boundary: everything the room contains stands beyond it,
 * on the desk around the mat, and [outsideTheMat] holds it to that in a test.
 * When a retained scene and a real depth sort exist, this rule can be dropped
 * and not before.
 *
 * ## How big anything is
 *
 * Every number below is in card widths or card heights, never pixels, for the
 * same reason `BoardLayouter` works that way: the card is the one object on
 * this stage whose size the user can judge, so it is the only honest ruler. A
 * desk measured in pixels is a desk that changes size when the window does.
 */
object Scenery {

    // ---- the minimal stage, unchanged ---------------------------------------------

    /**
     * How far the playmat runs past the cards, and the table past the mat, in
     * card widths.
     *
     * Moved here from the renderer without changing either number. They are
     * geometry, they were solved once by eye and argued for in
     * `docs/classic/DESIGN.md` §10, and the composable that used to hold them had no
     * business being the only place they existed. The argument, kept: a table
     * margin of a whole card width put a broad grey border round every side of
     * the mat, and on a stage whose premise is sharp white on true black that is
     * a large light rectangle competing with the cards. What the table is for is
     * the *edge* — the moment the camera comes down and a solid side swings into
     * view — and a narrow reveal does the whole job.
     */
    const val MAT_MARGIN = 0.22f
    const val TABLE_MARGIN = 0.38f

    /** How thick the minimal stage's table is, in card widths. A lip, not a plinth. */
    const val TABLE_THICKNESS = 0.17f

    /** The playmat's outline: everything the board occupies, plus its border. */
    fun mat(layout: BoardLayout): Slot =
        layout.bounds.inflated(layout.cardWidth * MAT_MARGIN)

    /** The minimal stage's table top. */
    fun table(layout: BoardLayout): Slot =
        mat(layout).inflated(layout.cardWidth * TABLE_MARGIN)

    // ---- the desk -----------------------------------------------------------------
    //
    // **The room's own numbers are `RoomTune`'s defaults and nowhere else.**
    // Eight constants used to sit here — the desk's depth and span, the window's
    // four, and the lamp's two — as "the record of what shipped", read by
    // nothing. They were a second copy of the room, and a copy nobody compiles
    // against is a copy that goes quietly wrong: by the time kai's tuning became
    // the default, every one of them disagreed with the room the app actually
    // draws, and one of them carried a *measurement* — that a window on this
    // wall is either low or invisible — which had stopped being true when the
    // board learned to decline a fifth of the stage (`ROOM_ABOVE`). What is
    // still here is what the code reads: thicknesses, overhangs, ceilings, and
    // the lamp's own profile.

    /**
     * How far past the mat the wall stands, once a person has moved things.
     *
     * kai's coupling: *"extending the table from the front should push the wall
     * with the window back"*. So the depth the desk gains toward the player is
     * added here as well, and the room grows rather than the desk becoming a
     * long shelf in front of a wall that stayed where it was.
     *
     * **Floored, and the floor is load-bearing.** The two knobs subtract, so a
     * shallow desk under a near wall drives this negative — and a wall at or in
     * front of the mat's far edge stands *over the cards*, which is the one rule
     * `docs/classic/DESIGN.md` §11 says nothing in a scene may break: the room is painted
     * beneath every card, so a piece that reaches over the mat is a piece the
     * cards are drawn through.
     */
    fun wallAt(room: RoomTune = RoomTune()): Float =
        (room.wallBack + room.deskDepth - 0.9f).coerceAtLeast(WALL_MIN_BACK)

    /** The nearest the wall may come to the mat's far edge, in card widths. */
    const val WALL_MIN_BACK = 0.12f

    /**
     * How much of the stage's height the board declines to use, so that there
     * is somewhere for the room to be.
     *
     * Pass to `BoardLayouter.solve`'s `roomAbove` for [Scene.DESK] and nothing
     * for [Scene.MINIMAL], which has no room to see.
     *
     * This is the number that makes the second half of the list in
     * `docs/classic/LOOP.md` reachable at all. Everything in this room is placed
     * relative to the mat and scaled by a card, so the wall's base landed
     * wherever the board's far edge landed — which was the top of the screen —
     * and the wall, the window and the sky behind it were geometry nobody could
     * ever see. There is no way to show them that does not start with the board
     * being smaller than the stage.
     *
     * A fifth, and it is a fifth for a reason rather than by taste. The board is
     * height-constrained on every device this ships to, so the reserve comes
     * straight off the card: a fifth is exactly a fifth, 107dp of card down to
     * 85dp on the tablet. What it buys on that tablet is 176dp of screen above
     * the desk's far edge, which is the first framing in which the window is a
     * window rather than a hole nobody can see. Below about a tenth the wall is
     * a lip again; above about a quarter the cards are smaller than the deck
     * builder draws them, which is where a bigger room has started costing the
     * game.
     *
     * The narrowest phone pays the same fifth and can afford it — 115px of card
     * down to 92px, against a [BoardLayouter.MIN_CARD_WIDTH] of 28.
     */
    const val ROOM_ABOVE = 0.20f

    /** How thick the desk top is, in card widths. Furniture, not a lip. */
    const val DESK_THICKNESS = 0.5f

    /**
     * How high the wall stands, in card *heights*.
     *
     * Bounded, and the bound is the reason the number is written down rather
     * than tuned in a composable. Everything with a z reaches the screen through
     * `StagePlane.project`, whose scale is `distance / (distance - depth)` — so
     * height does not merely grow, it grows *faster* the taller it gets, and a
     * wall tall enough to reach the camera's own plane would divide by nothing.
     * Four card heights is comfortably inside that everywhere in
     * `CameraEnvelope`; [WALL_CEILING] is where a test stops it.
     *
     * **It was 3.2 until the POV seat.** The wall's job is to be the back of the
     * picture, and how much picture there is above the mat is a function of how
     * low you are sitting: from the three seats that look *down* at the table
     * the wall's top edge is off the top of the glass by a hundred pixels or
     * more, and from POV — 32 degrees above the felt — it landed at y = 29 on an
     * 856-pixel stage, leaving a strip of void along the top of the screen.
     * `theRoomFillsThePictureFromEverySeat` did not catch it for two releases
     * because its own layout was solved without [ROOM_ABOVE]: a board that does
     * not decline a fifth of the stage has bigger cards, and a wall measured in
     * card heights was a fifth taller in the test than in the app.
     */
    const val WALL_HEIGHT = 3.9f

    /** The tallest a piece of this room may stand, in card heights. */
    const val WALL_CEILING = 4f

    /** How thick the wall is. It only shows as a lip along the desk's far edge. */
    const val WALL_THICKNESS = 0.6f

    /**
     * How far the walls stand past the ends of the desk, in card widths.
     *
     * The room's plan, and there is exactly one of it: the floor is this
     * rectangle and the walls stand on its edges. It was two numbers — a wall
     * 1.12 times the desk's own span and a floor eight card widths past it —
     * and two numbers for one room is a room with a gap in it. The wall ended
     * 530 pixels inside the floor, so past the wall's end you were looking over
     * the floor's far edge into nothing.
     */
    const val ROOM_MARGIN = 8.0f

    // ---- the room below the desk ---------------------------------------------------

    /** How far the desk top stands off the floor, in card widths — about 73cm. */
    const val DESK_STAND = 12.4f

    /** How thick the floor is. Only its top face is ever seen. */
    const val FLOOR_THICKNESS = 0.4f

    /**
     * How far the room runs past the desk's near edge, in card widths.
     *
     * Measured rather than chosen: at a quarter turn the bottom corner of the
     * picture looks past the near end of the room, and the hole there closes
     * at eight and does not get smaller at sixteen. Half a metre of floor on
     * the player's side of the desk, which is where the chair is.
     */
    const val ROOM_DEPTH = 8.0f

    /**
     * How far the floor runs past the walls, in card widths.
     *
     * A skirting board's worth, so that a wall standing on the floor's edge has
     * floor under the whole of its thickness rather than exactly to its inside
     * face. Everything else about the room's size is [ROOM_MARGIN].
     */
    const val FLOOR_MARGIN = 0.5f

    // ---- the window ------------------------------------------------------------------

    /**
     * How big the sky is, and how far off, in card widths.
     *
     * Only ever divided into one another, to give the source an angular size:
     * 21.7 degrees, which is what a window looks like from a desk beside it and
     * about three times the lamp's. That ratio is the whole reason day and night
     * read as two rooms rather than two colour grades — at the height a card is
     * carried at, daylight's shadow edge is two and a half times softer.
     *
     * The window keeps its **direction** and gains no position. See
     * [lightingFor] for why, which is a fact about this wall rather than a
     * preference.
     */
    const val SKY_RADIUS = 12.9f
    const val SKY_DISTANCE = 34.0f

    // ---- the joinery around the opening ----------------------------------------------

    /**
     * How wide the two stiles are, in card widths, and how deep the two rails
     * are, in card heights.
     *
     * They stand **outside** the opening rather than overlapping it, which is
     * both how a window is actually built and the only arrangement that costs
     * the glass nothing: this window is 264 mat pixels wide and every pixel of
     * it was hard won.
     */
    const val FRAME_STILE = 0.18f
    const val FRAME_RAIL = 0.10f

    /**
     * How far the frame and the bars stand proud of the wall, in card widths.
     *
     * Proud rather than set back in a reveal, for the reason the pane's own
     * comment gives: anything pushed back in y projects straight off the top of
     * the picture, and a reveal shows twelve pixels at the table seat and
     * nothing from overhead. Standing it forward puts the same edge where the
     * camera can always see it.
     *
     * The frame may not interpenetrate the wall, and this is what keeps it from
     * doing so — it starts at the wall's front face and goes out. `ScenePainter`
     * sorts boxes by the nearest point each reaches, which is the painter's rule
     * for solids that **do not share volume**; two boxes that do have no correct
     * order and will swap as the camera turns.
     */
    const val FRAME_PROUD = 0.12f

    /**
     * The glazing bars: how thick, and how far forward of the wall.
     *
     * Set back from the frame's own face, because that is where a bar sits and
     * because the small step between the two is most of what says this is
     * joinery rather than a grid drawn on the glass.
     */
    const val FRAME_BAR = 0.075f
    const val FRAME_BAR_PROUD = 0.055f

    // ---- the lamp --------------------------------------------------------------------

    /**
     * How big the **source** is, in card widths: a 14cm shade.
     *
     * **Not scaled by [RoomTune.lampScale], and that is the whole of it.** It
     * was, for exactly as long as the room had knobs, on the reasonable-sounding
     * ground that a lamp drawn twice as big is twice as big. But the drawn lamp
     * is already dishonest on purpose — [lampHeight] solves where its light
     * comes from and `RoomTune.lampMast` decides how tall a thing you can see
     * standing under it, at about two-fifths of that — so `lampScale` is a
     * decision about the picture, and this is a fact about the physics. Letting
     * the first set the second meant kai's stouter lamp cast *daylight*: at
     * `lampScale` 2.02 the source's angular radius reached 1.48 times the
     * window's own instead of a third of it, and `CardShadowTest`'s two claims
     * about a night that is a room rather than a colour grade both went red.
     *
     * If night should ever have softer shadows, that is its own knob, and the
     * honest one to put on the panel is this number.
     */
    const val LAMP_RADIUS = 1.2f

    /**
     * The shade, as a lathe turns one: how wide it is at the rim, how wide at
     * the opening it leaves for the finial, and how far down from `RoomTune.lampMast`
     * its underside sits.
     *
     * A cone rather than a cuboid, and the numbers are a shade's rather than a
     * box's. An empire shade's opening is a little over half its rim — narrower
     * than that is a coolie and reads as a hat, wider is a drum and reads as a
     * tin. The rim rolls: [LAMP_SHADE_ROLL] is how far the wire hoop at the
     * bottom stands proud of the cone above it, and it is a fortieth of a card,
     * which is one pixel of silhouette at the reference size and is the whole
     * difference between a lampshade and a paper cone.
     */
    const val LAMP_SHADE_RIM = 0.62f
    const val LAMP_SHADE_TOP = 0.34f
    const val LAMP_SHADE_THICK = 0.58f
    const val LAMP_SHADE_ROLL = 0.025f

    /**
     * How thick the cloth is, in card widths.
     *
     * Three pixels. It is what turns the shade from a cone into a shell with an
     * opening you can see into — see [lampShade] — and it is the only dimension
     * of the lamp that is a real measurement of a real object rather than a
     * proportion of one.
     */
    const val LAMP_SHADE_WALL = 0.03f

    /**
     * Which band of [lampShade]'s six is the inside of the cloth.
     *
     * The profile runs: rim, roll, cone foot, opening, across the opening, down
     * the inside, and back under the bottom. Band four is the one that comes
     * back down, which is the surface the bulb is shining on. A named constant
     * beside the profile rather than a four in the middle of the room, because
     * the two have to be changed together and a silent disagreement between them
     * is a lampshade lit on its outside and dark within.
     */
    const val LAMP_SHADE_INSIDE = 4

    /**
     * The finial: the bead that screws down over the harp and holds the shade
     * on, standing above `RoomTune.lampMast`.
     *
     * It is four pixels of brass and it is worth its piece, because it is the
     * only thing in the lamp's silhouette that is not a body of the lamp — a
     * shade with nothing on top of it reads as a funnel balanced on a stick.
     */
    const val LAMP_FINIAL = 0.16f
    const val LAMP_FINIAL_WIDE = 0.075f

    /**
     * The stem's width at the foot and at the neck, and the collar it carries.
     *
     * Two numbers because a turned stem tapers — a column of one width is a
     * dowel, and the eye reads the taper before it reads anything else about
     * the object. The collar is where a harp would be fixed, and it is there
     * for the same reason the finial is: it breaks a long plain run.
     */
    const val LAMP_MAST = 0.07f
    const val LAMP_MAST_NECK = 0.052f
    const val LAMP_COLLAR = 0.095f

    /**
     * The base: how wide it stands, how thick its rim is, and how high the cove
     * above it rises before it becomes the stem.
     *
     * A weighted foot rather than a tile. [LAMP_BASE] is unchanged, so the
     * footprint the room was arranged around has not moved.
     */
    const val LAMP_BASE = 0.38f
    const val LAMP_BASE_THICK = 0.045f
    const val LAMP_BASE_COVE = 0.155f

    /**
     * Where the lamp stands on the desk, in mat pixels.
     *
     * **It may not stand on the playing surface**, and that is a clamp rather
     * than a range on the slider, because the mat's width in card widths depends
     * on the board and a static range could not promise it on every device.
     * `docs/classic/DESIGN.md` §11: the room is painted beneath every card, so anything
     * of it that reaches over the felt is a thing the cards are drawn *through*.
     *
     * The clamp is by its own widest radius and toward whichever side of the mat
     * it was already nearer, so pulling the slider left past the board sets it
     * down on the left of the desk rather than sliding it across the table.
     *
     * **And it may not stand in the wall**, which is the same rule pointing the
     * other way and arrived later. The lamp is placed as a fraction of the mat's
     * depth from its far edge, so a shade wide enough reaches *behind* that edge
     * — kai's is 1.3 card widths deep and reaches 0.42 of one past it — while
     * the wall may come as close to that edge as [WALL_MIN_BACK], which is 0.12.
     * The two ranges overlap, and where they do the wall and the shade share a
     * volume, which is the one thing `ScenePainter` has no correct answer for.
     * Furniture yields to the room rather than the other way about: the wall
     * stands where the knobs put it and the lamp steps forward off it.
     */
    fun lampFoot(layout: BoardLayout, room: RoomTune = RoomTune()): Vec2 {
        val mat = mat(layout)
        val card = layout.cardWidth
        // Measured off the profiles rather than off the two constants that
        // usually win, because "usually" is how the rolled hoop above the shade's
        // rim — twenty-five thousandths of a card wider than the rim itself — got
        // over the felt while a hand-written reach said it could not.
        val reach = maxOf(
            Turned.widest(lampBase(layout, room)),
            Turned.widest(lampStem(layout, room)),
            Turned.widest(lampShade(layout, room)),
            Turned.widest(lampFinial(layout, room)),
        )
        val wanted = mat.right + card * room.lampOut
        val x = when {
            wanted >= mat.right -> max(wanted, mat.right + reach)
            wanted <= mat.left -> minOf(wanted, mat.left - reach)
            wanted - mat.left < mat.right - wanted -> mat.left - reach
            else -> mat.right + reach
        }
        // A hair in front of the wall's own face rather than flush against it,
        // because the two sides of that equality are reached by different
        // arithmetic — `y - reach` against `face` — and one ulp of disagreement
        // is a shared volume. Furniture does not touch the wall anyway.
        val clear = (mat.top - card * wallAt(room)) + reach + card * LAMP_OFF_WALL
        return Vec2(x = x, y = max(mat.top + mat.height * room.lampAlong, clear))
    }

    /** How far in front of the wall the lamp stands at the closest, in card widths. */
    const val LAMP_OFF_WALL = 0.01f

    /**
     * How high the lamp's light is, in mat pixels.
     *
     * **Solved, not chosen**, and the reference quantity is the preset that
     * already ships. The ratio of horizontal to vertical in
     * `StageLighting.DeskNight.key.direction` is 0.854, and that ratio *is* how
     * long a night shadow is per unit of height. So the lamp stands at whatever
     * height makes the ray from it **to the middle of the table** have exactly
     * that ratio.
     *
     * What that guarantees is one point, and the claim is worth stating no
     * larger than it is: a card at the centre of the mat throws the shadow it
     * throws today, in length and in direction. Everywhere else both change —
     * the azimuth swings about fifteen degrees, and a card near the lamp throws
     * a markedly shorter shadow than one across the table from it. That is not
     * a cost of the anchoring, it *is* the feature: a lamp with a place throws
     * shadows that point away from it, and a direction cannot. What the
     * anchoring buys is that the middle of the board — where most of a game
     * happens — is not suddenly lit from a different height than it shipped
     * with, so the change reads as the room gaining a lamp rather than as
     * somebody having moved the light.
     *
     * It comes out at 704 pixels — about 38cm above the desk, which is a desk
     * lamp. That the honest number and the shipped preset agree this well is the
     * argument for solving it rather than typing one.
     */
    fun lampHeight(layout: BoardLayout, room: RoomTune = RoomTune()): Float {
        val mat = mat(layout)
        val foot = lampFoot(layout, room)
        val shipped = StageLighting.DeskNight.key.direction.normalised()
        val ratio = sqrt(shipped.x * shipped.x + shipped.y * shipped.y) / abs(shipped.z)
        if (ratio <= 1e-4f) return layout.cardWidth * room.lampMast
        // Never below the shade it is meant to be inside. Moving the lamp to the
        // middle of the table sends the horizontal distance to zero and the
        // solved height with it, which is a bulb buried in its own foot.
        return max(
            hypot(mat.centerX - foot.x, mat.centerY - foot.y) / ratio,
            shadeTop(layout, room) * 0.75f,
        )
    }

    // ---- the lamp, as four things a lathe made ---------------------------------------

    /**
     * The four silhouettes the lamp is turned from, bottom to top.
     *
     * Four rather than one because `ScenePiece.mesh` may only hold a **convex**
     * solid: the renderer orders a piece's own faces by the depth of their
     * centres, which is the painter's algorithm and is exactly right for a
     * convex body and quietly wrong for anything else. A lamp is not convex —
     * the cove above its foot faces back toward the middle of the object, and
     * the underside of the shade overhangs the stem entirely — so it is four
     * convex pieces stacked in z, which is also what makes `ScenePainter`'s
     * separating axis answer for them: z separates every pair.
     *
     * They meet at exact shared heights ([baseTop], [neckTop], [shadeTop]) for
     * the same reason the wall's four pieces tile the opening exactly: a joint
     * is two surfaces at one number, and two numbers that ought to be equal and
     * are computed twice are a seam that opens at some board size nobody tried.
     */
    fun baseTop(layout: BoardLayout, room: RoomTune = RoomTune()): Float =
        layout.cardWidth * LAMP_BASE_COVE * room.lampScale

    /**
     * Where the stem ends and the shade begins.
     *
     * Floored just above [baseTop], because the two knobs can meet: a short mast
     * under a stout shade puts the shade's underside below the top of its own
     * foot, and a turned solid whose profile runs backwards is a stem drawn
     * inside out. The floor is a hair rather than zero so the stem keeps a
     * height for `Turned` to build bands over.
     */
    fun neckTop(layout: BoardLayout, room: RoomTune = RoomTune()): Float =
        max(
            layout.cardWidth * (room.lampMast - LAMP_SHADE_THICK * room.lampScale),
            baseTop(layout, room) + layout.cardWidth * 0.02f,
        )

    fun shadeTop(layout: BoardLayout, room: RoomTune = RoomTune()): Float =
        max(
            layout.cardWidth * room.lampMast,
            neckTop(layout, room) + layout.cardWidth * 0.02f,
        )

    fun lampBase(layout: BoardLayout, room: RoomTune = RoomTune()): List<Ring> {
        val card = layout.cardWidth * room.lampScale
        return listOf(
            // A foot with a chamfer under it, so it sits on the desk on a line
            // rather than on its whole face — which is what stops the join
            // between a round object and a flat one reading as a sticker.
            Ring(card * LAMP_BASE * 0.93f, 0f),
            Ring(card * LAMP_BASE, card * LAMP_BASE_THICK * 0.4f),
            Ring(card * LAMP_BASE, card * LAMP_BASE_THICK),
            Ring(card * LAMP_BASE * 0.78f, card * LAMP_BASE_THICK * 1.9f),
            Ring(card * LAMP_MAST * 1.7f, baseTop(layout, room)),
        )
    }

    fun lampStem(layout: BoardLayout, room: RoomTune = RoomTune()): List<Ring> {
        val card = layout.cardWidth * room.lampScale
        val neck = neckTop(layout, room)
        val foot = baseTop(layout, room)
        val run = neck - foot
        return listOf(
            Ring(card * LAMP_MAST, foot),
            Ring(card * LAMP_MAST_NECK, foot + run * 0.86f),
            Ring(card * LAMP_COLLAR, foot + run * 0.91f),
            Ring(card * LAMP_MAST_NECK * 0.9f, neck),
        )
    }

    /**
     * The shade, and it is a **shell**: up the outside, across the opening, and
     * back down the inside.
     *
     * Turned as a solid first, which is simpler and was wrong for one reason
     * that only a picture could have found. A solid frustum has a lid, and a lid
     * at this camera is a large bright disc sitting in the middle of the rim —
     * so the object came out as a drum with a plate on it rather than as a cone.
     * A real shade has a hole there, and what you see through the hole is the
     * far half of its own **inside**, which is a darker surface than the outside
     * by day and the brightest thing in the room at night.
     *
     * A shell is not convex, and `ScenePiece.mesh`'s rule is the painter's
     * algorithm rather than convexity as such: the faces the camera can see have
     * to sort correctly by the depth of their own centres. They do here, and the
     * reason is the shape rather than luck — back-face culling removes the outer
     * far side and the inner near side outright, and what is left is the inner
     * *far* surface, then the rim, then the outer *near* surface, in that order
     * from any seat in the envelope. There is no pair left to get wrong.
     */
    fun lampShade(layout: BoardLayout, room: RoomTune = RoomTune()): List<Ring> {
        val card = layout.cardWidth * room.lampScale
        val bottom = neckTop(layout, room)
        val top = shadeTop(layout, room)
        val run = top - bottom
        val wall = card * LAMP_SHADE_WALL
        return listOf(
            // Up the outside: the rim, the hoop it is rolled over, the cone.
            Ring(card * LAMP_SHADE_RIM, bottom),
            Ring(card * (LAMP_SHADE_RIM + LAMP_SHADE_ROLL), bottom + run * 0.05f),
            Ring(card * LAMP_SHADE_RIM, bottom + run * 0.12f),
            Ring(card * LAMP_SHADE_TOP, top),
            // Over the opening and straight back down the inside.
            Ring(card * LAMP_SHADE_TOP - wall, top),
            Ring(card * LAMP_SHADE_RIM - wall, bottom),
        )
    }

    fun lampFinial(layout: BoardLayout, room: RoomTune = RoomTune()): List<Ring> {
        val card = layout.cardWidth * room.lampScale
        val foot = shadeTop(layout, room)
        val rise = card * LAMP_FINIAL
        return listOf(
            Ring(card * LAMP_FINIAL_WIDE * 0.45f, foot),
            Ring(card * LAMP_FINIAL_WIDE, foot + rise * 0.42f),
            Ring(card * LAMP_FINIAL_WIDE * 0.72f, foot + rise * 0.74f),
            Ring(card * LAMP_FINIAL_WIDE * 0.20f, foot + rise),
        )
    }

    /** The lamp, as a point of light. */
    fun lamp(layout: BoardLayout, room: RoomTune = RoomTune()): Vec3 {
        val foot = lampFoot(layout, room)
        return Vec3(foot.x, foot.y, lampHeight(layout, room))
    }

    /**
     * The room, for a scene, an hour and a board.
     *
     * @param surfaceWidth the stage's own width in pixels. The desk is sized
     *   against the *surface* rather than against the mat because its job is to
     *   run off the sides of the picture, and how much picture there is is not
     *   something the board knows.
     */
    fun of(
        scene: Scene,
        time: TimeOfDay,
        layout: BoardLayout,
        surfaceWidth: Float,
        surfaceHeight: Float,
        /**
         * Where the furniture is, if somebody has moved it.
         *
         * Trailing and defaulted, so every existing caller and every test asks
         * for the room that shipped. It reaches [Scene.MINIMAL] not at all: the
         * handbook's stage is one slab and has nothing in it to tune.
         */
        room: RoomTune = RoomTune(),
    ): SceneModel = when (scene) {
        Scene.MINIMAL -> minimal(layout)
        Scene.DESK -> desk(time, layout, surfaceWidth, surfaceHeight, room)
    }

    /**
     * The minimal stage as a scene: one slab, exactly the one that already ships.
     *
     * Expressed this way so there is one renderer rather than two. The geometry
     * is unchanged and a test holds this box to producing the same six faces
     * `CardSolid.slab` did for it, which is what makes the unification safe to
     * make rather than merely tidy.
     */
    fun minimal(layout: BoardLayout): SceneModel {
        val table = table(layout)
        return SceneModel(
            pieces = listOf(
                ScenePiece(
                    name = "table",
                    surface = Surface.TABLE,
                    box = SceneBox.standing(
                        left = table.left,
                        top = table.top,
                        right = table.right,
                        bottom = table.bottom,
                        floor = -layout.cardWidth * TABLE_THICKNESS,
                        ceiling = 0f,
                    ),
                ),
            ),
            lighting = StageLighting.Minimal,
        )
    }

    /**
     * The desk, the wall it is pushed against with a window in it, the lamp
     * standing on it, and the floor underneath.
     *
     * Eighteen pieces against a budget of twenty, and **the same eighteen at every hour** —
     * a room does not repaint itself at dusk. What the hour changes is which
     * fixture is emitting and which rig is lighting the rest, which is the whole
     * of the difference between a scene and a colour grade.
     *
     * Two joints are worth knowing about, because both were bugs first:
     *
     * - **The wall stands *on* the desk and the desk runs *under* the wall.**
     *   The wall used to have a skirt down to the desk's underside, and that
     *   skirt is hidden by the desk top from every angle *and painted over it*,
     *   because the wall's tall front-top corner outruns the desk's near edge
     *   below about twenty-seven degrees of pitch. It ate thirty pixels of the
     *   forty-pixel band of bare wood between the wall and the mat — most of the
     *   thing `docs/classic/AAA.md` #61 asks for. Sitting the wall on z = 0 and running
     *   the desk back under it leaves the two sharing exactly one face.
     * - **The four wall pieces and the pane tile the old single wall exactly.**
     *   No gap and no overlap, which is one line of test and the reason a window
     *   can be a *hole* rather than a bright rectangle stuck on a wall.
     */
    fun desk(
        time: TimeOfDay,
        layout: BoardLayout,
        surfaceWidth: Float,
        surfaceHeight: Float,
        room: RoomTune = RoomTune(),
    ): SceneModel {
        val mat = mat(layout)
        val card = layout.cardWidth
        val tall = layout.cardHeight

        val halfSpan = maxOf(surfaceWidth, mat.width) * room.deskSpan / 2f
        val left = mat.centerX - halfSpan
        val right = mat.centerX + halfSpan
        // Where the wall's face stands, and where the desk's far edge is behind it.
        val face = mat.top - card * wallAt(room)
        val back = face - card * WALL_THICKNESS
        // Past the bottom of the picture as well as past the mat, and the
        // difference matters on a screen the board does not fill: an edge that
        // stops at the last card is a border round the board, and an edge that
        // stops beyond the glass is a table you are sitting at.
        val near = maxOf(mat.bottom, surfaceHeight) + card * room.deskDepth

        // The room's plan: one rectangle, which the floor tiles and the three
        // walls stand on the edges of. See [ROOM_MARGIN].
        val wallOverhang = halfSpan + card * ROOM_MARGIN
        val wallLeft = mat.centerX - wallOverhang
        val wallRight = mat.centerX + wallOverhang
        val wallTop = tall * WALL_HEIGHT
        // And where the floor is, which is a desk's height below the desk.
        val underfoot = -card * DESK_STAND
        // The room's near end: past the desk, because you are sitting there.
        // A wall that stops where the desk stops leaves the bottom corner of
        // the picture empty from about forty degrees of yaw, and the room only
        // stops falling off the near corner at [ROOM_DEPTH].
        val front = near + card * ROOM_DEPTH

        // The joinery's own two dimensions, needed here because the sill has to
        // leave the bottom rail somewhere to sit. See FRAME_PROUD.
        val stile = card * FRAME_STILE
        val rail = tall * FRAME_RAIL

        // The opening, clamped into the wall it is a hole in. Every clamp here
        // is the difference between a tuned window and a broken room, and each
        // one is a pair of boxes that would otherwise share volume — which is
        // the one thing `ScenePainter` has no correct answer for:
        //
        // - wider than the wall, and the four pieces around it cannot tile it;
        // - past the wall's own top, and the header comes out inside out;
        // - a sill under the bottom rail's own height, and that rail hangs below
        //   the desk top and into the desk;
        // - an opening shorter than a glazing bar is thick, and the horizontal
        //   bar reaches through the head rail above it.
        val openingHalf = (card * room.windowSpan / 2f).coerceAtMost(wallOverhang * 0.98f)
        val openingAt = (mat.left + mat.width * room.windowAt)
            .coerceIn(wallLeft + openingHalf, wallRight - openingHalf)
        val openingLeft = openingAt - openingHalf
        val openingRight = openingAt + openingHalf
        //
        // The sill is settled first and the head is settled against it, because
        // the two knobs can cross: asked the other way round, a head at its own
        // minimum leaves nowhere for a sill to be and the clamps chase each
        // other into a window with no height at all.
        val leastOpen = card * FRAME_BAR * 1.2f
        val ceilingForSill = max(rail, wallTop - rail - leastOpen)
        val sill = (tall * room.windowSill).coerceIn(rail, ceilingForSill)
        val head = (tall * room.windowHead)
            .coerceIn(sill + leastOpen, max(sill + leastOpen, wallTop - rail))

        val foot = lampFoot(layout, room)
        // The lathe stands at the lamp's foot on the desk, unturned: a body of
        // revolution has nothing a turn about its own axis could do to it, and
        // saying so here is cheaper than a comment on every ring.
        val spindle = Pose3(position = Vec3(foot.x, foot.y, 0f))
        val turnedBase = Turned.solid(spindle, lampBase(layout, room))
        val turnedStem = Turned.solid(spindle, lampStem(layout, room))
        // Closed, because the shade's profile comes back round to where it
        // started: it is a shell rather than a solid, so there is no end left
        // open for a cap to go on. The sides are named rather than defaulted so
        // that the band the lining lives in can be found by index — see
        // [LAMP_SHADE_INSIDE].
        val shadeProfile = lampShade(layout, room)
        val shadeSides = Turned.sidesFor(shadeProfile)
        val wholeShade = Turned.solid(spindle, shadeProfile, sides = shadeSides, closed = true)
        val shadeLining = Turned.band(wholeShade, LAMP_SHADE_INSIDE, shadeSides)
        val turnedShade = wholeShade.filterIndexed { index, _ ->
            index < LAMP_SHADE_INSIDE * shadeSides ||
                index >= (LAMP_SHADE_INSIDE + 1) * shadeSides
        }
        val turnedFinial = Turned.solid(spindle, lampFinial(layout, room))

        fun wall(name: String, l: Float, r: Float, bottom: Float, top: Float) = ScenePiece(
            name = name,
            surface = Surface.WALL,
            box = SceneBox.standing(l, back, r, face, bottom, top),
        )

        /** The same piece of wall, stood somewhere else along the room's depth. */
        fun ScenePiece.fromTo(from: Float, to: Float) = copy(
            box = SceneBox.standing(box.min.x, from, box.max.x, to, box.min.z, box.max.z),
        )

        /**
         * What a wall stands on: the strip between the floor and the desk top.
         *
         * Below the felt, so it is [SceneModel.ground] and is painted before the
         * felt — which is right, because it is behind and below everything the
         * felt carries.
         */
        fun skirt(name: String, l: Float, r: Float, from: Float, to: Float, floor: Float) =
            ScenePiece(
                name = name,
                surface = Surface.WALL,
                box = SceneBox.standing(l, from, r, to, floor, 0f),
            )

        // Everything from here to the bars stands in front of the wall's face
        // and never inside it. See FRAME_PROUD: the paint order is a separating
        // axis, and two boxes that share volume have no correct order at all.
        val proud = card * FRAME_PROUD
        val barHalf = card * FRAME_BAR / 2f
        val barOut = card * FRAME_BAR_PROUD
        val midX = (openingLeft + openingRight) / 2f
        val midZ = (sill + head) / 2f

        fun frame(name: String, l: Float, r: Float, bottom: Float, top: Float, out: Float) =
            ScenePiece(
                name = name,
                surface = Surface.FRAME,
                box = SceneBox.standing(l, face, r, face + out, bottom, top),
            )

        return SceneModel(
            pieces = listOf(
                ScenePiece(
                    name = "floor",
                    surface = Surface.FLOOR,
                    box = SceneBox.standing(
                        left = wallLeft - card * FLOOR_MARGIN,
                        top = back - card * FLOOR_MARGIN,
                        right = wallRight + card * FLOOR_MARGIN,
                        bottom = front,
                        floor = underfoot - card * FLOOR_THICKNESS,
                        ceiling = underfoot,
                    ),
                ),
                ScenePiece(
                    name = "desk",
                    surface = Surface.TABLE,
                    box = SceneBox.standing(
                        left = left,
                        // Up against the wall's face, not under it. It ran back
                        // to the wall's *back* face for as long as the wall
                        // began at the desk top and so had nothing below it to
                        // collide with. Now that the wall reaches the floor,
                        // the strip under it is the skirt's, and two boxes that
                        // share volume have no paint order at all — see the
                        // three skirts below.
                        top = face,
                        right = right,
                        bottom = near,
                        floor = -card * DESK_THICKNESS,
                        ceiling = 0f,
                    ),
                ),
                wall("wall left", wallLeft, openingLeft, 0f, wallTop),
                wall("wall right", openingRight, wallRight, 0f, wallTop),
                // The two returns and the three skirts, which are what makes
                // this a room rather than a flat behind a desk.
                //
                // A back wall alone reads as a flat the moment the camera comes
                // off yaw zero — which it now does, freely, on kai's "complete
                // freedom and control". `theRoomFillsThePictureWhenTheTableIsTurned`
                // measured ten holes in the picture at thirty degrees.
                //
                // Two things had to be true at once. The returns start at the
                // back wall's *front* face and run toward the player, so the
                // three walls meet along one edge each and share no volume —
                // which is what `ScenePainter`'s separating axis needs. And
                // every wall reaches the floor, which none of them did: they
                // stood on z = 0, a desk's height in the air, and the desk hid
                // the void under them only from straight ahead. Turn the table
                // thirty degrees and you were looking under the wall.
                //
                // A wall that reaches the floor is two boxes, because
                // `noPieceOfTheRoomStraddlesTheDeskTop` is what makes `ground`
                // and `standing` a partition and so what gives the felt a place
                // in the paint order. The seam is z = 0 and it is free: the two
                // halves share a face and nothing else.
                wall("wall side left", wallLeft, wallLeft + card * WALL_THICKNESS, 0f, wallTop)
                    .fromTo(face, front),
                wall("wall side right", wallRight - card * WALL_THICKNESS, wallRight, 0f, wallTop)
                    .fromTo(face, front),
                skirt("skirt back", wallLeft, wallRight, back, face, underfoot),
                skirt(
                    "skirt left",
                    wallLeft,
                    wallLeft + card * WALL_THICKNESS,
                    face,
                    front,
                    underfoot,
                ),
                skirt(
                    "skirt right",
                    wallRight - card * WALL_THICKNESS,
                    wallRight,
                    face,
                    front,
                    underfoot,
                ),
                wall("sill", openingLeft, openingRight, 0f, sill),
                wall("header", openingLeft, openingRight, head, wallTop),
                ScenePiece(
                    name = "pane",
                    surface = Surface.GLASS,
                    // Flush with the wall rather than set back in a reveal. Set
                    // back, it shows a twelve-pixel band at the table seat and
                    // nothing at all from overhead, because anything pushed back
                    // in y projects straight off the top of the picture. Flush,
                    // it gets the whole band, and the left jamb's inner face
                    // still reads as the edge of a frame.
                    box = SceneBox.standing(openingLeft, back, openingRight, face, sill, head),
                    emission = paneLight(time),
                ),
                // The stiles run the full height and the rails span between
                // them, which is how a sash is put together and — not by
                // coincidence — the only division of a rectangular frame into
                // four boxes where no two of them share a corner.
                frame("stile left", openingLeft - stile, openingLeft, sill - rail, head + rail, proud),
                frame("stile right", openingRight, openingRight + stile, sill - rail, head + rail, proud),
                frame("head rail", openingLeft, openingRight, head, head + rail, proud),
                frame("bottom rail", openingLeft, openingRight, sill - rail, sill, proud),
                frame("bar upright", midX - barHalf, midX + barHalf, sill, head, barOut),
                frame("bar left", openingLeft, midX - barHalf, midZ - barHalf, midZ + barHalf, barOut),
                frame("bar right", midX + barHalf, openingRight, midZ - barHalf, midZ + barHalf, barOut),
                ScenePiece(
                    name = "lamp base",
                    surface = Surface.BRASS,
                    box = SceneBox.around(turnedBase),
                    mesh = turnedBase,
                ),
                ScenePiece(
                    name = "lamp mast",
                    surface = Surface.BRASS,
                    box = SceneBox.around(turnedStem),
                    mesh = turnedStem,
                ),
                ScenePiece(
                    name = "lamp shade",
                    surface = Surface.SHADE,
                    // The whole shell, so the room goes on sorting against the
                    // shape rather than against the half of it that is cloth.
                    box = SceneBox.around(wholeShade),
                    mesh = turnedShade,
                    emission = shadeLight(time),
                    lining = Lining(
                        surface = Surface.GLOW,
                        faces = shadeLining,
                        // Full radiance inside, where the bulb is, against the
                        // cloth's own [SHADE_THROUGH] on the way out. That
                        // difference is the whole of what says the light is in
                        // there rather than painted on.
                        emission = insideLight(time),
                    ),
                ),
                ScenePiece(
                    name = "lamp finial",
                    surface = Surface.BRASS,
                    box = SceneBox.around(turnedFinial),
                    mesh = turnedFinial,
                ),
            ),
            lighting = lightingFor(Scene.DESK, time, layout, room),
        )
    }

    /**
     * What the window is showing.
     *
     * It always carries an emission and only the amount changes, because a
     * window always shows the outside and the outside at night is very nearly
     * black. Routed through the rig instead, a night pane would come back as a
     * mid-grey rectangle — a lit window in a dark room, with the light behind it.
     */
    private fun paneLight(time: TimeOfDay): Lit = when (time) {
        TimeOfDay.DAY -> Lit(1f, StageLighting.DeskDay.key.warmth)
        TimeOfDay.NIGHT -> Lit(0.02f, -0.85f)
    }

    /**
     * What the lamp is doing.
     *
     * Null by day, which is not the lamp being switched off so much as it being
     * a pale ceramic object in a bright room — shaded like everything else. At
     * night it is the brightest thing in the frame after a card, and its warmth
     * comes off the rig's own key so a fixture and the light it throws cannot be
     * different colours.
     */
    private fun shadeLight(time: TimeOfDay): Lit? = when (time) {
        TimeOfDay.DAY -> null
        TimeOfDay.NIGHT -> Lit(SHADE_THROUGH, StageLighting.DeskNight.key.warmth)
    }

    /**
     * And the same lamp seen from inside the shade, which is not the same
     * amount of light.
     *
     * The cloth is what the bulb is behind, so the outside of it is the light
     * that got *through* — a real linen shade passes something between a third
     * and a half — and the inside is very nearly the source. Drawn at one
     * everywhere, which is what shipped, the shade is a flat cream shape with
     * no light in it and the opening at the top is indistinguishable from the
     * cloth around it. The difference between the two numbers is the entire
     * read.
     */
    private fun insideLight(time: TimeOfDay): Lit? = when (time) {
        TimeOfDay.DAY -> null
        TimeOfDay.NIGHT -> Lit(1f, StageLighting.DeskNight.key.warmth)
    }

    /**
     * How much of the bulb the cloth lets out.
     *
     * Nine tenths, and the first attempt at this was four — the honest
     * transmission of a linen shade, and completely wrong here. Everything the
     * renderer does with a colour is a multiply, so this is a fraction of
     * `0xE4DAC6` and not a fraction of a bulb: at four tenths the one fixture
     * in the room came out as a grey paper bag, dimmer at night than the same
     * cloth is in daylight. The cloth is *lit from inside* and it is the
     * brightest object on the stage; what shapes it is [StageRig.spill] and the
     * inside being brighter still, not this being small.
     */
    const val SHADE_THROUGH = 0.90f

    /**
     * Which rig lights which room, once the board's size is known.
     *
     * The presets in `StageLighting` are pure ratios and directions and stay
     * that way — not one byte of that file changes for this. What is added here
     * is everything measured in **mat pixels**, because a lamp's position and a
     * source's size are lengths, and a length is a fact about the board's layout
     * rather than about a preset.
     *
     * **The lamp gets a place; the window does not**, and that is geometry
     * rather than laziness. Three constructions were tried for a positioned
     * window and all three fail on this wall: a source at the aperture's centre
     * gives a horizontal-to-vertical ratio of 3.4, so a carried card's shadow
     * lands four hundred pixels away instead of eighty; a source pushed back
     * along the shipped ray drifts its direction by seven and a half degrees and
     * moves every daylight shadow in the app; and a source at sky height on the
     * shipped ray crosses this wall's plane at z = 991 against a wall 511 tall —
     * it enters *above* the window rather than through it. There is no position
     * inside this wall that produces daylight. What the window gets instead is
     * its real angular *size*, which is the half of the physics that is visible:
     * daylight's shadows are soft and lamplight's are not.
     */
    fun lightingFor(
        scene: Scene,
        time: TimeOfDay,
        layout: BoardLayout,
        room: RoomTune = RoomTune(),
    ): StageLighting =
        when (scene) {
            Scene.MINIMAL -> StageLighting.Minimal
            Scene.DESK -> when (time) {
                TimeOfDay.DAY -> StageLighting.DeskDay.let { rig ->
                    rig.copy(
                        key = rig.key.copy(
                            radius = layout.cardWidth * SKY_RADIUS,
                            distance = layout.cardWidth * SKY_DISTANCE,
                        ),
                    )
                }
                TimeOfDay.NIGHT -> StageLighting.DeskNight.let { rig ->
                    val mat = mat(layout)
                    rig.copy(
                        key = Light.at(
                            position = lamp(layout, room),
                            aimedAt = Vec3(mat.centerX, mat.centerY, 0f),
                            intensity = rig.key.intensity,
                            warmth = rig.key.warmth,
                            ambient = rig.key.ambient,
                            radius = layout.cardWidth * LAMP_RADIUS,
                        ),
                    )
                }
            }
        }
}

/**
 * Whether this surface is part of the room's shell rather than a thing in it.
 *
 * The shell is the room — the walls, the floor, the window and the joinery
 * around it — and the distinction earns its place by deciding which pieces throw
 * shadows onto the desk (`RoomShadows`). Two of them were bugs before it
 * existed. The **wall** cast a broad dark band across the back of the desk,
 * thrown by the very surface the daylight arrives through: a wall with a window
 * in it is the aperture, not an occluder of its own window. And the **joinery**
 * would throw bars of shadow across the desk, which a real window does and this
 * room cannot have yet — that is `AAA.md` #61c, cut once already because the rig
 * lights the desk from the window's direction so anything drawn on top of it
 * comes out darker than the wood. It needs the day key split into sky and sun.
 *
 * Geometry could not tell these apart. Containment in the desk's footprint
 * catches the wall, which overhangs it, and misses the joinery, which is
 * narrower than the desk. Depth catches neither, because the frame is built
 * *proud* of the wall and stands in front of it. What actually separates a lamp
 * from a window is what it is, so that is what is asked.
 *
 * Exhaustive on purpose: a new [Surface] will not compile until somebody has
 * said which side of this line it falls on.
 */
val Surface.isShell: Boolean
    get() = when (this) {
        Surface.WALL, Surface.FLOOR, Surface.GLASS, Surface.FRAME -> true
        Surface.TABLE, Surface.SHADE, Surface.GLOW, Surface.BRASS -> false
    }
