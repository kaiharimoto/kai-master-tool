package com.kaiharimoto.mastertool.core.render

import com.kaiharimoto.mastertool.core.motion.Vec2
import com.kaiharimoto.mastertool.core.motion.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The lighting rig for the play stage: a key, a bounce, a rim, one eye.
 *
 * Two rigs would be one too many. Every surface on the table — a card's face,
 * the white edge of a pile, the felt, the shadow one card throws on another —
 * has to agree about where the light is, or the table stops reading as one
 * room, and the way to guarantee that is for there to be exactly one place the
 * answer comes from.
 *
 * **Everything here is in the mat's own frame**, not the screen's: +x across
 * the table, +y toward the player's edge, +z straight up off the felt. That is
 * the frame the whole play stage already computes in — card positions are mat
 * pixels and lift is a z — and it means the light does not have to be re-derived
 * when the table's tilt changes. The one place the tilt shows up is [eye],
 * because where the *camera* is, in a frame attached to a tilted table, is
 * genuinely a function of the tilt.
 */
object StageRig {

    /**
     * The key: high, in front, and off to the left.
     *
     * Off to one side because a light directly overhead casts a shadow directly
     * underneath, which is to say no visible shadow at all and no way to tell a
     * held card from a resting one. Toward the player rather than away because
     * this camera looks from the player's side, and a shadow thrown *away* from
     * the viewer spends most of its life hidden behind the card throwing it.
     *
     * Warm, and that is the half of it that is not geometry. Two pure-white
     * lamps are most of why the table used to read as lit by an equation rather
     * than by a room: nothing anybody has ever played on is lit by one colour
     * from both sides. Three quarters of [Lit.TEMPERATURE] — see there for the
     * magnitude and why it is nothing like as large as it sounds.
     */
    val Key = Light(
        direction = Vec3(0.30f, 0.45f, -0.84f).normalised(),
        intensity = 1f,
        warmth = 0.75f,
        ambient = 0.72f,
    )

    /**
     * The bounce: the light the room throws back up off the table.
     *
     * Weak, wide, and pointed the other way, so a card tilted hard away from
     * the key does not go flat. It has no shadow of its own — a fill that cast
     * one would be a second key.
     *
     * Cool, all the way, because the split is what does the work rather than
     * either end of it: a white edge that is a shade warm where the key lands
     * and a shade cool where only the fill reaches reads as an object in a room,
     * and a difference *across one object* is far more visible than the same
     * shift applied to the whole table. It can afford the full swing precisely
     * because it is the weakest lamp here — at its best it is two and a half per
     * cent of the light on a face.
     */
    val Bounce = Light(
        direction = Vec3(-0.22f, -0.30f, -0.93f).normalised(),
        intensity = 0.30f,
        warmth = -1f,
        ambient = 0f,
    )

    /**
     * The rim: low, behind the table, and on the opposite side from the key.
     *
     * The stage is true black, so a card's silhouette has nothing to be a
     * silhouette *against* — the only thing separating the edge of a card from
     * the felt is the card's own brightness, and when a card is dim so is the
     * boundary. This is the third point of an ordinary three-point rig and it
     * exists for exactly that line.
     *
     * Two things about it are load-bearing:
     *
     * - **It casts nothing.** `Shadows.cast` takes one light and goes on taking
     *   one light; a second shadow on this table would not read as a second lamp,
     *   it would read as a duplicated card.
     * - **It is gated on the graze, not on the lambert alone.** A backlight that
     *   simply added its dot product would brighten a card lying face-up toward
     *   the viewer, which is an ambient with extra steps and a more expensive
     *   one. Weighted by how edge-on the camera sees a surface it can only
     *   land where the silhouette is, and it is exactly zero square on.
     *
     * And one thing about it is not what a photographer would expect. A rim
     * light belongs *behind* the subject, and behind is the one place it would
     * do nothing here: this stage draws solids with back-face culling and looks
     * at them from above, so every surface a light behind the table could reach
     * has already been culled before it is shaded. The first version of this
     * lamp pointed that way and a test proved it changed exactly one face in a
     * whole board — the far edge of a card held in the air — while leaving every
     * pile it was written to outline byte-identical.
     *
     * So it sits on the *player's* side instead, low and cool: the light a room
     * throws back off whoever is sitting at the table. That is a real lamp in a
     * real room, it reaches the near edges — which are the only edges of a pile
     * anybody ever sees — and the graze gate still keeps it off the faces.
     */
    val Rim = Light(
        direction = Vec3(-0.62f, -0.66f, -0.42f).normalised(),
        intensity = 0.45f,
        warmth = -0.6f,
        ambient = 0f,
    )

    /**
     * How sharply the rim collapses as a surface turns to face the camera.
     *
     * Squared rather than linear. Linear leaves a rim of a third of its strength
     * on a surface forty degrees off the camera, which is most of a card that is
     * merely tilted in someone's hand — and a rim that is on everything is not a
     * rim, it is the ambient again.
     */
    private const val GRAZE_FALLOFF = 2f

    /**
     * Where the camera is, in the mat's frame, for a table tilted [tiltDegrees].
     *
     * The mat is turned to face the viewer by that much, so from the mat's own
     * point of view the viewer has moved up and toward its near edge by the
     * same amount. Getting this wrong is not subtle: with a flat `(0, 0, 1)`
     * the specular pool on every resting card sits a little too far up the
     * table, uniformly, and the whole board looks lit from a light that is not
     * the one lighting the piles.
     */
    fun eye(tiltDegrees: Float, yawDegrees: Float = 0f): Vec3 {
        // Undo the mat's own layer, which is Rx(pitch)·Rz(-yaw): the viewer sits
        // wherever that transform came from. Written as the two steps rather
        // than as three constants so the sign of the yaw is decided in exactly
        // one place in this app — StagePlane's — and read everywhere else.
        val up = Rot3.rotateX(Vec3.Toward, -tiltDegrees)
        return Rot3.rotateZ(up, yawDegrees)
    }

    /**
     * All three lamps on one surface, which is all any renderer needs to ask for.
     *
     * Each of the three directional terms is scaled by the headroom above the
     * key's ambient, and they are then summed and clamped. A surface standing
     * square in all three at once would sum past white and be clipped — which
     * cannot happen, because the three lamps point in three different
     * directions and no normal faces all of them. The clamp is the backstop,
     * not the design.
     *
     * The temperature comes out as an energy-weighted average, and the ambient
     * is in the denominator as the white light it is. That one detail is what
     * keeps the rule the handbook actually cares about: the room is achromatic,
     * so a surface the key cannot reach comes back a neutral grey, and colour
     * appears in exact proportion to how much *directional* light landed on it.
     * Colour as light, arithmetically rather than by promise.
     */
    fun lit(
        normal: Vec3,
        eye: Vec3 = Vec3.Toward,
        key: Light = Key,
        bounce: Light = Bounce,
        rim: Light = Rim,
        /**
         * Where on the stage this surface is, for a lamp that has a place.
         *
         * Last, and it has to be: every existing caller passes its arguments
         * positionally up to [rim], and a parameter inserted before them would
         * be a silent re-binding rather than a compile error.
         *
         * Null is not "the origin" — it is *no point offered*, which every lamp
         * without a position ignores anyway. All three lamps get the treatment
         * rather than only the key, because [Light.toLightFrom] and
         * [Light.attenuation] both short-circuit on a null position, so doing it
         * uniformly costs nothing and removes a special case. In practice only
         * the key is ever placed: a fill is the room and a rim is the player,
         * and neither is a fixture.
         */
        at: Vec3? = null,
    ): Lit {
        val unit = normal.normalised()
        val headroom = 1f - key.ambient

        val direct = max(0f, unit dot key.toLightFrom(at)) * key.intensity *
            key.attenuation(at) * headroom
        val fill = max(0f, unit dot bounce.toLightFrom(at)) * bounce.intensity *
            bounce.attenuation(at) * headroom

        // Zero when the camera is square on to the surface and largest along the
        // silhouette, which is the only place a rim light is supposed to exist.
        val graze = (1f - abs(unit dot eye.normalised())).coerceIn(0f, 1f)
        val kick = max(0f, unit dot rim.toLightFrom(at)) * rim.intensity *
            rim.attenuation(at) * graze.pow(GRAZE_FALLOFF) * headroom

        val amount = (key.ambient + direct + fill + kick).coerceIn(0f, 1f)
        if (amount <= 0f) return Lit.None

        val temperature = direct * key.warmth + fill * bounce.warmth + kick * rim.warmth
        return Lit(amount, temperature / amount)
    }

    /**
     * The same surface with the key's *direct* light taken away — what a point
     * inside a full umbra actually receives.
     *
     * A shadow is not a colour. It is this surface, lit by everything that
     * still reaches it: the ambient, the bounce, and the rim. Everything except
     * the one lamp something is standing in front of.
     *
     * ## Why it had to be computed rather than chosen
     *
     * `Shadows.DARKEST` was **0.66**, and a card's shadow was that black
     * whatever the room was doing. But `Light.ambient` is 0.72 in Minimal and
     * 0.55 at night and is **unoccluded everywhere** — nothing on this stage
     * has ever cast a shadow in the ambient — so three quarters of the light on
     * the felt was, physically, still arriving inside the shadow. Darkening to
     * 0.66 against that is not a penumbra on a lit surface, it is a hole
     * punched through one, and it is most of why cards read as stickers laid on
     * a picture of a table rather than as objects on it.
     *
     * Under the shipped rig, on a flat mat, the honest figure is [occlusion]'s:
     * about **a fifth**, not two thirds.
     *
     * ## The rule this half of the arithmetic has to keep
     *
     * Stated once, here, because two terms that both darken will otherwise be
     * tuned against each other forever and the result is a black halo that
     * reads as *stronger* shadows rather than as wrong ones:
     *
     * **A cast shadow occludes `key.intensity` only. Contact occlusion occludes
     * `key.ambient` only. Their sum is the surface's whole irradiance.**
     */
    fun shadowed(
        normal: Vec3,
        eye: Vec3 = Vec3.Toward,
        key: Light = Key,
        bounce: Light = Bounce,
        rim: Light = Rim,
        at: Vec3? = null,
    ): Lit {
        val unit = normal.normalised()
        val headroom = 1f - key.ambient

        // The key's direct term is the one thing missing, and it is missing
        // rather than reduced: this is the *umbra*, where none of it arrives.
        val fill = max(0f, unit dot bounce.toLightFrom(at)) * bounce.intensity *
            bounce.attenuation(at) * headroom

        val graze = (1f - abs(unit dot eye.normalised())).coerceIn(0f, 1f)
        val kick = max(0f, unit dot rim.toLightFrom(at)) * rim.intensity *
            rim.attenuation(at) * graze.pow(GRAZE_FALLOFF) * headroom

        val amount = (key.ambient + fill + kick).coerceIn(0f, 1f)
        if (amount <= 0f) return Lit.None

        // No `direct` term, so no key warmth either — which is the physics
        // rather than an omission. A shadow is lit by the room and the player
        // and not by the lamp, so it is *cooler* than what surrounds it, and
        // that is the whole of `AAA.md` #18's "shadows are not black".
        val temperature = fill * bounce.warmth + kick * rim.warmth
        return Lit(amount, temperature / amount)
    }

    /**
     * How much of a surface's light a full umbra removes, 0..1.
     *
     * The alpha a cast shadow should be painted at, and the number
     * `Shadows.DARKEST` was guessing at. One minus the ratio of [shadowed] to
     * [lit], which is exactly "the share of what lands here that came straight
     * from the key".
     */
    fun occlusion(
        normal: Vec3,
        eye: Vec3 = Vec3.Toward,
        key: Light = Key,
        bounce: Light = Bounce,
        rim: Light = Rim,
        at: Vec3? = null,
    ): Float {
        val full = lit(normal, eye, key, bounce, rim, at).amount
        if (full <= 0f) return 0f
        val dark = shadowed(normal, eye, key, bounce, rim, at).amount
        return ((full - dark) / full).coerceIn(0f, 1f)
    }

    /** [shadowed], with the three lamps handed over as one value. */
    fun shadowed(normal: Vec3, eye: Vec3, lighting: StageLighting, at: Vec3? = null): Lit =
        shadowed(normal, eye, lighting.key, lighting.bounce, lighting.rim, at)

    /** [occlusion], with the three lamps handed over as one value. */
    fun occlusion(normal: Vec3, eye: Vec3, lighting: StageLighting, at: Vec3? = null): Float =
        occlusion(normal, eye, lighting.key, lighting.bounce, lighting.rim, at)

    /**
     * The same three lamps, handed over as one value instead of three.
     *
     * The only overload that exists, and it exists so that no call site has to
     * take a rig apart to pass it on. Every renderer in the app takes a
     * [StageLighting] and hands it straight here; none of them holds a [Light]
     * of its own, which is what keeps the unanimity [StageLighting]'s KDoc
     * inherits from this object's.
     */
    fun lit(normal: Vec3, eye: Vec3, lighting: StageLighting, at: Vec3? = null): Lit =
        lit(normal, eye, lighting.key, lighting.bounce, lighting.rim, at)

    /**
     * One face of a solid: how bright to paint it.
     *
     * It used to answer `Lit.None` for anything whose normal was not within
     * ninety degrees of the direction the viewer lies in — a back-face cull,
     * done a second time, in the wrong place, with the wrong test. Culling is
     * `CardSolid.visible`'s job and it asks a better question: whether the face
     * is turned toward *where the camera is*, rather than toward the direction
     * the stage as a whole is seen from. The two disagree exactly where it
     * matters, and while this function kept its own opinion the walls that
     * disagreement had been hiding came back **painted black** — which is a hole
     * in a deck of a different colour and no more convincing.
     *
     * So there is no opinion here any more. A face this is asked about is a face
     * the culler already passed, and the answer is how the three lamps land on
     * it.
     *
     * The bounce earns its place here. The key is toward the player, so the
     * *near* edge of every pile — the one edge of it anybody can see — faces
     * away from the key and would be lit by ambient alone. A deck's white edge
     * is not a dark band in any room, and the fill is why.
     *
     * The rim earns its place on the other axis. A face the camera sees nearly
     * edge-on is a face whose *drawn* width is a hairline, and a hairline of
     * ambient grey on a true-black stage is a hairline nobody can see — which is
     * the whole reason a card's outline used to dissolve into the felt whenever
     * the card itself was dim.
     */
    fun face(
        face: Face,
        eye: Vec3 = Vec3.Toward,
        lighting: StageLighting = StageLighting.Minimal,
    ): Lit = lit(face.normal, eye, lighting, at = face.centre)

    /**
     * One face of the **room**: the same three lamps, with the ambient falling
     * off from a lamp that has a place.
     *
     * ## Why the room gets its own answer
     *
     * Because night was not dark. The two desk presets differ by an ambient of
     * 0.76 against 0.55, and sRGB flattens that to nine levels of 255: the wall
     * behind the desk rendered 67 by day and 68 at night, so a room lit by one
     * lamp came out the same grey as a room lit by a window. Everything that
     * ought to say *night* — the corner falling away, the lamp being the reason
     * anything is lit — was in a term nothing was allowed to touch.
     *
     * ## And why it is not simply a lower ambient
     *
     * `StageLighting.NIGHT_FLOOR` is a floor for a reason that has not changed:
     * a card's own art is a picture this renderer cannot read, so it is shaded
     * by one black overlay at one opacity, and [Tone.veil]'s error grows sharply
     * as the light falls. Lowering the rig's ambient would darken the room *and*
     * make every card face wrong.
     *
     * So the two part company here, and this is the seam. The **table** —
     * cards, piles, the desk top under the pool — keeps [face] and keeps the
     * floor. The **room** gets [Light.roomReach], which is what the ambient
     * would be if it were what it physically is: bounce, off a lamp, from over
     * there. `DeskNight`'s KDoc has said for two releases that the darkness is
     * bought outside the cards; this is where it is bought.
     *
     * Identical to [face] to the bit for any rig whose key has no place, which
     * is daylight, the minimal stage and `GoldenStageTest`.
     */
    fun room(
        face: Face,
        eye: Vec3 = Vec3.Toward,
        lighting: StageLighting = StageLighting.Minimal,
    ): Lit = roomAt(face.normal, eye, lighting, face.centre)

    /**
     * [room], asked about one point rather than about a whole face.
     *
     * **Everything that is the room falls away; only the lamp stays.** The
     * ambient is bounce, the fill is the room throwing light back off itself,
     * and the rim is the light off whoever is sitting at the table — in a room
     * with one lamp on a desk, all three of those come from the lamp and all
     * three are dim in the far corner. The key's own beam is the exception,
     * because it *is* the lamp; it already carries `Light.attenuation` and is
     * left alone here.
     *
     * Dimming only the ambient was the first version and it left the far wall
     * at half its daylight brightness — the rim alone was supplying a quarter of
     * the light on a vertical surface, undimmed, from a lamp that does not exist
     * after dark.
     */
    fun roomAt(normal: Vec3, eye: Vec3, lighting: StageLighting, at: Vec3): Lit {
        val reach = lighting.key.roomReach(at)
        if (reach >= 1f) return lit(normal, eye, lighting, at)
        val dimmed = lighting.copy(
            key = lighting.key.copy(ambient = lighting.key.ambient * reach),
            bounce = lighting.bounce.copy(intensity = lighting.bounce.intensity * reach),
            rim = lighting.rim.copy(intensity = lighting.rim.intensity * reach),
        )
        return lit(normal, eye, dimmed, at)
    }

    /**
     * Which of a face's two axes to grade along — and for a curved face it is
     * **not** whichever direction the light happens to vary most in.
     *
     * A gradient is linear along one axis, and the light on a facet of a turn
     * varies in two: round the axis, where the normal is turning, and up it,
     * where the distance to the lamp is changing. Picking by brightness picks
     * *per facet*, so one wedge grades round and the next grades up — and where
     * two neighbours choose differently, no pair of linear ramps can meet at
     * their shared edge. Measured on a sixteen-sided cylinder that step is 0.074
     * of the light on the surface, which is eighteen levels of 255 and a
     * visible rib.
     *
     * Grading along the axis the **normal** turns is the same choice for every
     * facet of one turn, because it is a fact about the shape rather than about
     * where the lamp is standing, so two neighbours agree at their seam by
     * construction. A flat face has no normal that turns, so it keeps the
     * brightness rule it always had — which is what leaves every box in this
     * room, and every card on the table, exactly where it was.
     *
     * @return true to take the first axis.
     */
    private fun gradeAcross(
        curved: Boolean,
        acrossSpread: Float,
        alongSpread: Float,
        acrossNormals: Pair<Vec3, Vec3>,
        alongNormals: Pair<Vec3, Vec3>,
    ): Boolean {
        if (!curved) return acrossSpread >= alongSpread
        val acrossTurn = 1f - (acrossNormals.first dot acrossNormals.second)
        val alongTurn = 1f - (alongNormals.first dot alongNormals.second)
        return acrossTurn >= alongTurn
    }

    /**
     * How the light varies **across** one face of the room, as a run of samples
     * along the axis it varies most along.
     *
     * ## Why a face needs more than one answer
     *
     * Because [room] shades a whole piece once, at its centre. The wall behind
     * the desk is one of eighteen and is the size of several of the others. The wall behind the desk is fifteen hundred pixels wide;
     * giving all of it the brightness of its own middle is a wall that steps
     * from one flat grey to another at a seam, which is not a room falling away
     * — it is the same defect [room] was written to fix, moved one level up.
     * The arithmetic was right and invisible.
     *
     * ## The axis is measured, not assumed
     *
     * A quad has two of them, and which one the light runs along depends on
     * where the lamp is standing: across the wall for a lamp beside it, up the
     * wall for a lamp below it. So both are tried — the light at the middle of
     * each of the four edges — and the pair that disagree more wins. Sampling a
     * grid instead would be more general and would need a mesh; every *facet*
     * in this room is flat and lit by one lamp, so the variation across it is
     * monotone and a line through it is the whole story. A facet that stands in
     * for a curve does not get to choose its own axis — see [gradeAcross].
     *
     * ## Null is the common case and is the point
     *
     * Daylight has no placed lamp, so on a flat face every sample comes back
     * identical and this returns null — the caller keeps its solid fill, allocates no brush,
     * and every daylit and minimal surface in the app stays exactly as it was.
     * The same happens for a small face at night. What is left is the handful
     * of large surfaces where a gradient is the difference between a room and a
     * backdrop.
     *
     * @param samples how many stops to return, at least two.
     */
    fun wash(
        face: Face,
        eye: Vec3,
        lighting: StageLighting,
        samples: Int = 4,
    ): FaceWash? {
        if (face.corners.size < 4) return null
        // A face with no place to fall off from and no curvature to shade round
        // has nothing to grade. A *smooth* face has the second even in daylight,
        // which is why this is not simply the placed-lamp test it used to be:
        // the lamp and the puzzle are turned solids and they are faceted at
        // noon exactly as badly as they are at midnight.
        if (lighting.key.position == null && face.smooth == null) return null
        val stops = samples.coerceAtLeast(2)

        val n = face.corners.size
        fun midpoint(a: Int, b: Int) = (face.corners[a % n] + face.corners[b % n]) * 0.5f
        fun midNormal(a: Int, b: Int) =
            (face.normalAt(a % n) + face.normalAt(b % n)).normalised()
        fun amountAt(normal: Vec3, at: Vec3) = roomAt(normal, eye, lighting, at).amount

        val half = n / 2
        val across = Pair(midpoint(0, 1), midNormal(0, 1)) to
            Pair(midpoint(half, half + 1), midNormal(half, half + 1))
        val along = Pair(midpoint(1, 2), midNormal(1, 2)) to
            Pair(midpoint(half + 1, half + 2), midNormal(half + 1, half + 2))
        val acrossSpread =
            abs(amountAt(across.first.second, across.first.first) - amountAt(across.second.second, across.second.first))
        val alongSpread =
            abs(amountAt(along.first.second, along.first.first) - amountAt(along.second.second, along.second.first))
        val (start, end) = if (
            gradeAcross(
                curved = face.smooth != null,
                acrossSpread = acrossSpread,
                alongSpread = alongSpread,
                acrossNormals = across.first.second to across.second.second,
                alongNormals = along.first.second to along.second.second,
            )
        ) across else along
        if (max(acrossSpread, alongSpread) < WASH_FLOOR) return null

        return FaceWash(
            from = start.first,
            to = end.first,
            stops = (0 until stops).map { step ->
                val along01 = step / (stops - 1f)
                val at = start.first + (end.first - start.first) * along01
                // The normal is interpolated and then normalised, which is what
                // makes this continuous across a seam: two facets sharing an
                // edge sample the *same* midpoint and the *same* averaged
                // normal, so their gradients meet at one value.
                val normal = (start.second + (end.second - start.second) * along01).normalised()
                roomAt(normal, eye, lighting, at)
            },
        )
    }

    /**
     * The smallest difference across a face worth drawing as a gradient.
     *
     * Two levels of 255 in the diffuse, which is under the banding threshold of
     * any surface in this room and well under the cost of a brush.
     */
    const val WASH_FLOOR = 0.008f

    // ---- what a placed lamp does to a flat surface --------------------------------

    /**
     * Where the lamp's own reflection lands on the plane at [surfaceZ].
     *
     * The mirror image of the source, chased down to the plane — exact and one
     * line, because the surface is flat and its normal is +z.
     *
     * This exists because a *diffuse* pool on this mat cannot be seen. Derived
     * honestly from the rig, the night mat runs 11 levels of 255 under the lamp
     * down to 7 at the far corner: four levels, against the twenty-nine the
     * shipped additive pool gives. The physics is right and the picture is dead,
     * because a near-black matte surface has no albedo left to modulate. What
     * you actually see on a playmat under a lamp is a *sheen* — a reflection of
     * the source rather than of its light — which is exactly what [Shade.lamp]
     * already models for a card and exactly why `drawCardSurface` composites it
     * additively. So the felt gets both: the diffuse pool as a multiply, and
     * this as the additive highlight it always secretly was.
     *
     * Null for a lamp with no place. A directional source's reflection is a lobe
     * with no centre a plane this small can hold.
     */
    fun sheen(light: Light, eyeAt: Vec3, surfaceZ: Float = 0f): Vec2? {
        val lamp = light.position ?: return null
        val eyeUp = eyeAt.z - surfaceZ
        val lampUp = lamp.z - surfaceZ
        if (eyeUp <= 0f || eyeUp + lampUp <= 0f) return null
        val along = eyeUp / (eyeUp + lampUp)
        return Vec2(
            x = eyeAt.x + (lamp.x - eyeAt.x) * along,
            y = eyeAt.y + (lamp.y - eyeAt.y) * along,
        )
    }

    /**
     * How wide that reflection is: the source's mirror image, broadened by how
     * rough the surface reflecting it is.
     *
     * [roughness] is an RMS slope — a tangent, dimensionless — so it adds to the
     * source's own angular radius before both are carried down the same mirror
     * path [sheen] walks and scaled onto the plane by the same fraction.
     */
    fun sheenRadius(
        light: Light,
        eyeAt: Vec3,
        roughness: Float,
        surfaceZ: Float = 0f,
    ): Float {
        val lamp = light.position ?: return 0f
        val eyeUp = eyeAt.z - surfaceZ
        val lampUp = lamp.z - surfaceZ
        if (eyeUp <= 0f || eyeUp + lampUp <= 0f) return 0f
        val along = eyeUp / (eyeUp + lampUp)
        val mirror = Vec3(lamp.x, lamp.y, surfaceZ - lampUp)
        return (light.radius + roughness * (mirror - eyeAt).length) * along
    }

    /** Where the key's own share of the light has run out, as a fraction of it. */
    const val POOL_FLOOR = 0.10f

    /** How many colours the pool is drawn as. Nine is where a tenth stops showing. */
    const val POOL_STOPS = 9

    /**
     * The key's light on the table top: one shape, and both the wood and the
     * felt lying on it are consumers of it.
     *
     * **Nothing here is a second lighting model**, and that is the whole point of
     * it living in this object. [LightPool.stops] is [lit] itself, evaluated on a
     * ray of radii with the plane's own normal — the identical function that
     * shades every card and every wall — so the pool and the shadows cannot
     * disagree about where the lamp is, by construction rather than by care. The
     * felt's pool was a hand-placed gradient aimed by a *direction*, and it is
     * the one surface on the stage that has never been told a lamp exists.
     *
     * Sampling one ray is exact rather than an approximation: for a fixed normal
     * the bounce and the rim have no position, the graze is a constant, and the
     * only radially varying term is the key's — and a point source over a plane
     * is radially symmetric about its own foot.
     *
     * Null when the key has no place. A directional lamp has no foot to pool
     * around, which is why the shipped felt keeps the shipped drawing.
     */
    fun pool(
        lighting: StageLighting,
        eye: Vec3,
        steps: Int = POOL_STOPS,
        floor: Float = POOL_FLOOR,
    ): LightPool? {
        val key = lighting.key
        val lamp = key.position ?: return null
        val height = lamp.z
        if (height <= 0f) return null

        val reach = reachOf(key, height, floor)
        // A lamp too dim to reach [floor] anywhere, including directly beneath
        // itself. There is no pool to draw, and the honest answer is the one
        // that sends the caller back to the drawing it had before a lamp
        // existed — rather than a gradient of radius zero, which paints its
        // last stop over everything.
        if (reach <= 0f) return null

        val count = max(2, steps)
        return LightPool(
            foot = Vec2(lamp.x, lamp.y),
            radius = reach,
            stops = (0 until count).map { step ->
                val radius = reach * step / (count - 1f)
                lit(Rot3.FaceNormal, eye, lighting, at = Vec3(lamp.x + radius, lamp.y, 0f))
            },
        )
    }

    /**
     * How far out the key still carries [floor] of the light it throws straight
     * down, for a lamp [height] above the plane.
     *
     * Bisected rather than solved, because the closed form is a quartic and this
     * is called once a frame for a whole room. Thirty halvings of a bracket that
     * starts twenty times the lamp's height leaves the answer exact to a
     * millionth of a pixel, which is a great deal more than a gradient needs.
     */
    private fun reachOf(key: Light, height: Float, floor: Float): Float {
        // Everything below assumes the bracket straddles the answer. It cannot
        // when the lamp is already under the floor at its own foot, which is
        // where it is brightest.
        if (key.intensity <= floor) return 0f

        val size = key.radius * key.radius
        // The key's own share at radius r: lambert times attenuation. Both fall,
        // so the product is monotonic and a bracket cannot miss.
        fun share(radius: Float): Float {
            val span = radius * radius + height * height
            val lambert = height / sqrt(span)
            return key.intensity * lambert * ((size + height * height) / (size + span))
        }

        var near = 0f
        var far = height * 20f
        if (share(far) > floor) return far
        repeat(30) {
            val middle = (near + far) / 2f
            if (share(middle) > floor) near = middle else far = middle
        }
        return far
    }

    // ---- what a surface in the room is made of ---------------------------------------

    /**
     * The one thing that is *not* Lambert about a surface: how sharply and how
     * strongly it mirrors the light.
     *
     * The room did not have this. [lit] is ambient plus lambert plus a
     * graze-gated rim and contains **no specular term at all**, and until
     * [gleam] the only Blinn-Phong in this app was `Shading.of`, which takes a
     * card's pose and a `CardMaterial` and is asked about nothing else. So brass
     * and cloth and painted timber differ in *colour* and in nothing else, which
     * is exactly why `docs/classic/LOOP.md` §6 recorded the lamp as a "flat fill" and
     * `docs/classic/AAA.md` #67 records the material half of a surface as unfinished.
     *
     * This is deliberately **not** a fifth term inside [lit]. `GoldenStageTest`
     * records what [lit] returns for every face of a slab at three seats, and
     * `docs/classic/LOOP.md` §3 forbids re-recording a golden and changing behaviour in
     * one release. So the highlight arrives beside it, additively, in the same
     * register [pool] and [wash] established — and the day the rig grows a
     * proper specular (`docs/classic/PHOTOREAL.md` stage 2) this is the shape of the
     * thing that gets absorbed into it.
     */
    data class Gloss(
        /** The Blinn-Phong exponent: how tight the highlight is. */
        val sharpness: Float,
        /** How much of the lamp comes back, at the best angle. */
        val strength: Float,
    ) {
        companion object {
            /**
             * Lacquered brass. The tightest and strongest thing in the room, and
             * the only metal in it, because a metal *is* a mirror with a rough
             * surface and everything else here is a dielectric with a bit of
             * sheen.
             */
            val Brass = Gloss(sharpness = 28f, strength = 0.46f)

            /** Painted timber, which has a sheen and nothing more. */
            val Paint = Gloss(sharpness = 12f, strength = 0.10f)

            /** Cloth, plaster, felt: no highlight worth the arithmetic. */
            val None = Gloss(sharpness = 1f, strength = 0f)
        }
    }

    /**
     * Under this and a highlight is not a highlight, it is a rounding error.
     *
     * The same threshold [wash] uses and for the same reason: two levels of 255
     * is where a term stops being visible and starts being a brush allocated
     * every frame for nothing.
     */
    const val GLEAM_FLOOR = 0.008f

    /**
     * The lamp, mirrored in a surface — an **additive** highlight.
     *
     * Blinn-Phong from the half vector, and both vectors are taken from real
     * *places* rather than directions: the eye is three feet from the table and
     * the lamp is a hand above it, so a highlight computed from a direction
     * would sit in the same spot on every facet of a turned solid and would not
     * move when the camera did — which is precisely the thing a highlight is for.
     *
     * Returns null under [GLEAM_FLOOR], so a matte surface, a face turned away,
     * and every surface on `Scene.MINIMAL` cost one comparison and allocate
     * nothing.
     */
    fun gleam(
        face: Face,
        eyeAt: Vec3,
        lighting: StageLighting,
        gloss: Gloss,
        samples: Int = 4,
    ): FaceWash? {
        if (gloss.strength <= 0f) return null
        val key = lighting.key

        /**
         * The lobe, widened by how big the lamp is in the sky.
         *
         * A highlight is the *source* reflected, so its width is the source's
         * own angular radius as much as the surface's roughness, and the two
         * add in quadrature the way any pair of independent spreads does. That
         * is not a refinement: the day key is a window at `SKY_RADIUS /
         * SKY_DISTANCE`, which is twenty-two degrees across, and taking gold's
         * own sharpness of thirty-six for it turns a flat face pointed anywhere
         * near the half vector into a white rectangle — which is exactly what
         * the puzzle's top face came out as, because a large flat surface under
         * a directional lamp has *one* alignment over the whole of it.
         *
         * The strength falls with the same ratio, because a lobe spread over
         * six times the solid angle is six times dimmer at its peak and the
         * light did not get brighter. So the same two numbers give a broad
         * whisper by day and a hard glint at night, from one lamp being a
         * window and the other being a bulb — which is what `StageLook.gold`'s
         * own note says it wants and had no way to get.
         */
        val spread = key.angularRadius(face.centre)
        val sharpness = 1f / (1f / gloss.sharpness + spread * spread)
        val strength = gloss.strength * (sharpness / gloss.sharpness)

        fun amountAt(normal: Vec3, at: Vec3): Float {
            val toLight = key.toLightFrom(at)
            // Gated on the light reaching the face at all, not multiplied by it
            // — the same choice `Shading.of` makes, and the reason is that a
            // highlight multiplied by N·L is dimmed twice at exactly the
            // grazing angles where a real one is strongest.
            if ((normal dot toLight) <= 0f) return 0f
            val half = (toLight + (eyeAt - at).normalised()).normalised()
            val alignment = max(0f, normal dot half)
            return (
                alignment.pow(sharpness) *
                    strength * key.intensity * key.attenuation(at)
                ).coerceAtMost(1f)
        }

        if (amountAt(face.normal.normalised(), face.centre) < GLEAM_FLOOR &&
            face.corners.indices.all {
                amountAt(face.normalAt(it), face.corners[it]) < GLEAM_FLOOR
            }
        ) {
            return null
        }

        // A *gradient* rather than one value per face, for the reason
        // `docs/classic/LOOP.md` iteration 9 gives about the wall: a facet is not a
        // point, and a correct number evaluated at its centre comes out as one
        // flat tone stepping to another at the seam. On a twenty-sided lamp
        // base that is not a subtle artefact — it is a starburst, because the
        // brightest facet is a wedge and its neighbours are visibly dimmer
        // wedges, and the whole foot reads as a paper fan.
        //
        // Below four corners there is no axis to grade along and a flat answer
        // is the honest one, which is the triangles at an apex.
        if (face.corners.size < 4) {
            val flat = amountAt(face.normal.normalised(), face.centre)
            if (flat < GLEAM_FLOOR) return null
            return FaceWash(face.centre, face.centre, listOf(Lit(flat, key.warmth)))
        }

        // The midpoints of **opposite** edges, which for a quad is [wash]'s own
        // (0,1)–(2,3) and (1,2)–(3,0) and for an n-gon is the same walk half way
        // round. Opposite matters and adjacent does not work: two facets that
        // share an edge agree exactly at that edge's midpoint, so a gradient
        // between opposite edges is continuous across the seam and one between
        // adjacent edges steps at every one of them — which on a twenty-sided
        // base is the starburst this function was rewritten to remove, still
        // there afterwards.
        val n = face.corners.size
        fun midpoint(a: Int, b: Int) = (face.corners[a % n] + face.corners[b % n]) * 0.5f
        fun midNormal(a: Int, b: Int) =
            (face.normalAt(a % n) + face.normalAt(b % n)).normalised()
        val half = n / 2
        val across = Pair(midpoint(0, 1), midNormal(0, 1)) to
            Pair(midpoint(half, half + 1), midNormal(half, half + 1))
        val along = Pair(midpoint(1, 2), midNormal(1, 2)) to
            Pair(midpoint(half + 1, half + 2), midNormal(half + 1, half + 2))
        val acrossSpread = abs(
            amountAt(across.first.second, across.first.first) -
                amountAt(across.second.second, across.second.first),
        )
        val alongSpread = abs(
            amountAt(along.first.second, along.first.first) -
                amountAt(along.second.second, along.second.first),
        )
        val (start, end) = if (
            gradeAcross(
                curved = face.smooth != null,
                acrossSpread = acrossSpread,
                alongSpread = alongSpread,
                acrossNormals = across.first.second to across.second.second,
                alongNormals = along.first.second to along.second.second,
            )
        ) across else along

        val stops = samples.coerceAtLeast(2)
        return FaceWash(
            from = start.first,
            to = end.first,
            stops = (0 until stops).map { step ->
                val along01 = step / (stops - 1f)
                val at = start.first + (end.first - start.first) * along01
                val normal = (start.second + (end.second - start.second) * along01).normalised()
                Lit(amountAt(normal, at), key.warmth)
            },
        )
    }

    /**
     * How bright an emissive surface is across its own height.
     *
     * A lampshade is not evenly lit and the difference is not subtle: the bulb
     * sits low inside it, so the cloth is brightest a little above the rim and
     * falls away toward the opening at the top. Drawn flat instead — which is
     * what shipped — it is a cream shape with no light in it, and at night the
     * one fixture in the room reads as a paper bag.
     *
     * Returned as a [FaceWash] because that is already exactly this: real [Lit]
     * values at points on the face, for a renderer to multiply its own colour
     * by. Null when the face has no height to grade over, which is the rim and
     * the underside, and they are right to be flat.
     */
    fun spill(
        face: Face,
        emission: Lit,
        low: Float = SPILL_LOW,
        high: Float = SPILL_HIGH,
        samples: Int = 4,
    ): FaceWash? {
        if (face.corners.size < 4) return null
        val bottom = face.corners.minByOrNull { it.z } ?: return null
        val top = face.corners.maxByOrNull { it.z } ?: return null
        val rise = top.z - bottom.z
        if (rise < 1e-3f) return null

        // The two ends are the midpoints of the low pair and the high pair, so
        // the axis is the face's own and not a screen direction.
        val byHeight = face.corners.sortedBy { it.z }
        val from = (byHeight[0] + byHeight[1]) * 0.5f
        val to = (byHeight[byHeight.size - 2] + byHeight[byHeight.size - 1]) * 0.5f

        val stops = samples.coerceAtLeast(2)
        return FaceWash(
            from = from,
            to = to,
            stops = (0 until stops).map { step ->
                val along = step / (stops - 1f)
                Lit(emission.amount * (low + (high - low) * along), emission.warmth)
            },
        )
    }

    /**
     * What is left of a fixture's own light at the bottom of it and at the top.
     *
     * Under one at both ends, because a shade drawn at full radiance everywhere
     * has no shape at all — and brightest at the *bottom*, because that is where
     * the bulb is and it is the half of this that a photograph makes obvious and
     * a diagram does not.
     */
    const val SPILL_LOW = 1f
    const val SPILL_HIGH = 0.62f
}

/**
 * The light a placed lamp throws on a flat surface, as a gradient can draw it.
 *
 * [foot] is where the lamp stands, in the surface's own coordinates — which is
 * also the centre of the pool, because a point source over a plane is symmetric
 * about the point directly beneath it. [stops] runs from that foot out to
 * [radius] in equal steps, and each one is a real [Lit] rather than an opacity,
 * so a renderer multiplies its own surface colour by them and never composites
 * a wash over something it does not know the colour of.
 */
data class LightPool(val foot: Vec2, val radius: Float, val stops: List<Lit>)

/**
 * How the light runs across one face of the room, as a gradient can draw it.
 *
 * [from] and [to] are points on the face itself, in the mat's own coordinates,
 * so the renderer flattens them exactly as it flattens the corners it is filling
 * between — the gradient turns with the room and needs no screen-space
 * assumption of its own. [stops] are real [Lit] values in equal steps between
 * them, so a caller multiplies its own surface colour by each and never
 * composites a wash over a colour it does not know.
 *
 * The same shape as [LightPool] and for the same reason: the arithmetic is exact
 * at every stop and only the interpolation between two correct colours is an
 * approximation.
 */
data class FaceWash(val from: Vec3, val to: Vec3, val stops: List<Lit>)
