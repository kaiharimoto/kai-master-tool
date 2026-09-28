package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.motion.SpringSpec
import com.kaiharimoto.mastertool.core.motion.SpringValue
import com.kaiharimoto.mastertool.core.motion.Springs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the camera is: two angles, how far back, what through, and what at.
 *
 * It was three numbers and it said so at length. *"There is no pan — the camera
 * always looks at the middle of the table — because a movable target means the
 * vanishing point stops being the centre of the layer, and then the mat's
 * `graphicsLayer` needs a `transformOrigin` and [StagePlane.unproject] needs an
 * off-axis inverse."* Half of that was right and it is the cheap half: the mat's
 * layer does need a `transformOrigin`, and it is two lines.
 *
 * The other half was a description of a **different pan**. Sliding the finished
 * picture sideways does move the vanishing point off the middle of the glass,
 * and that would need the off-axis inverse. Moving what the camera *looks at*
 * does not: the divide stays centred on the pivot and the pivot stays drawn in
 * the middle, so [StagePlane.unprojectAt] is unchanged but for which point it
 * adds back at the end. The inverse stays closed form, and so does [StagePlane.flatten],
 * which is the property everything with a height on this stage is drawn through.
 *
 * kai asked for *"complete freedom and control"*, which is not something a
 * camera bolted to the middle of one table has.
 *
 * [distance], [panX] and [panY] are multiples of the stage's governing dimension
 * rather than numbers of pixels, exactly as [StagePlane.forStage]'s lens is, so
 * a pose means the same thing on a phone and on a desk monitor.
 */
data class CameraPose(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = StagePlane.TILT,
    val distance: Float = HOME_DISTANCE,
    /**
     * The focal length, as a multiple of the one this stage has always used.
     *
     * ## What it is
     *
     * A **magnification**, and nothing else. It multiplies `zoom` and
     * `cameraDistance` by the same amount, which leaves the angle the table
     * subtends — `zoom · extent / cameraDistance` — exactly where it was. So the
     * board is drawn bigger or smaller and the perspective across it does not
     * move a pixel. That is what a longer lens does: it magnifies, it does not
     * restage.
     *
     * [distance] remains the other half and remains a *position*: stepping back
     * both flattens the perspective and makes the table smaller, which is what
     * walking backwards does. The two are now the pair a photographer expects —
     * where you stand decides the perspective, the lens decides how much of it
     * fills the frame.
     *
     * ## The version that shipped first, and why it was wrong
     *
     * v1.2.38 put the lens on `cameraDistance` alone. That pins the framing and
     * moves the perspective, which is a **dolly zoom** — a real and useful
     * control, and not a focal length. It also meant both dials changed the
     * perspective, so tuning one appeared to undo the other; moving the distance
     * from 1.45 to 2.0 took the field of view from 36 degrees to 26 with the
     * lens number sitting still. kai's word for it was that it reset.
     *
     * ## And the half of that bug which survived the fix
     *
     * Putting the lens on both terms made *this* dial honest and left the other
     * one carrying a fault of exactly the same shape, for another five releases:
     * `cameraDistance` still had [distance] on it. So the focal length moved
     * whenever the camera did. On a 1600-wide stage the field of view ran from 34
     * degrees at the back of the envelope to 77 at the front — a fifty-seven
     * millimetre lens to a twenty-one — and the keystone across the table swung
     * as `1/distance²` where a real camera swings as `1/distance`. Every push-in
     * was a dolly zoom; so was every *tilt*, because the pitch moves
     * [CameraEnvelope.minDistanceAt], which moved the distance, which moved the
     * lens. kai's word for that one was that it did not behave like a real
     * camera, and it did not.
     *
     * `planeFor` now takes [distance] off the focal length entirely. Where the
     * two dials sit is unchanged — this one magnifies, that one moves you — and
     * the difference is that the second one has stopped doing both.
     *
     * ## What it costs, which is less than the dolly zoom did
     *
     * `perspectiveGrowth` no longer moves with it — both terms scale, so the
     * ratio the board was solved against is untouched — which retires the
     * warning that a baked-in lens under 0.947 would turn `StagePlaneTest` red.
     * [CameraEnvelope.minDistanceAt] loses its lens factor for the same reason:
     * zooming cannot bring the table closer to a camera that has not moved.
     *
     * What it does cost is framing. Past `1.0` the board is magnified and can
     * run off the edges of the screen, exactly as a real zoom crops. Nothing
     * catches it, and that is the deal: `CameraFit` moves the *distance*, and
     * calling it here would re-couple the two dials that this exists to separate.
     *
     * ## Last, and defaulted to one, and that is load-bearing twice
     *
     * At `1f` every byte of every projection is what it was, so this landed in a
     * release ahead of anything that moves it and `GoldenStageTest` never
     * noticed — the same move `CardSolid.slab`'s trailing `backScale` made.
     *
     * And it is last because [CameraRig.step] and [CameraRig.nudge] used to
     * build a pose *positionally*. A field inserted before [distance] would have
     * been a silent re-binding rather than a compile error. Both use `copy` now:
     * without it the lens reset to one on every frame the camera sprang or was
     * dragged, so a tuned lens survived exactly until anybody touched the table.
     */
    val lens: Float = HOME_LENS,
    /**
     * What the camera is aimed at, as an offset from the middle of the mat.
     *
     * ## What moves
     *
     * The **target**, not the picture. The camera orbits this point, is
     * [distance] from this point, and draws this point in the middle of the
     * glass — so panning walks the eye across the room rather than sliding a
     * finished image, and every angle you were looking from you are still
     * looking from.
     *
     * ## Why the units are these
     *
     * Multiples of the governing dimension, like [distance], because a pan of
     * "half a stage to the left" has to mean the same thing on a tablet and on a
     * monitor. Mat pixels would mean a saved pose framed one thing on the device
     * it was saved on and something else everywhere else.
     *
     * ## Last, and defaulted to nothing, and that is load-bearing twice
     *
     * At zero every byte of every projection is what it was, so this landed in a
     * release ahead of anything that moves it and `GoldenStageTest` never
     * noticed — the same move [lens] and `CardSolid.slab`'s trailing `backScale`
     * made.
     *
     * And it is last because [CameraRig.step] and [CameraRig.nudge] once built a
     * pose *positionally*, and a field inserted before [distance] would have been
     * a silent re-binding rather than a compile error. Both use `copy` now; the
     * ordering rule survives them because the next person will not know that.
     */
    val panX: Float = 0f,
    val panY: Float = 0f,
    /**
     * Where the lens is aimed on the glass, as an offset from its middle.
     *
     * ## The third pan, and the one that makes this a chair
     *
     * [panX] moves what the camera **looks at**. This moves where that lands in
     * the **picture** — a view camera's rise and fall, and the thing a
     * photographer reaches for when a building is taller than the frame.
     *
     * Sit at a desk and your eye level is above the middle of what you see: the
     * table is in front of you and below you, and the wall behind it runs up out
     * of frame. This stage could not say that. The point the camera was aimed at
     * was drawn dead centre, so the only way to bring the horizon onto the glass
     * was to pitch until [StagePlane.horizonY] walked down into frame, and by
     * then the table is nearly edge-on and a card is a line. Two different wants
     * on one number, and this is the second one.
     *
     * It is also the other way to give the room somewhere to be. `ROOM_ABOVE`
     * buys the wall and the window their screen area by making the *board*
     * smaller — a fifth of every card, on every device, at every seat, because
     * aiming was not on the table. This costs no card size at all.
     *
     * ## What it does not do, twice
     *
     * It does not move the camera, so it does not move
     * [CameraEnvelope.minDistanceAt] — the same cancellation [lens] gets, and
     * for the same reason. And it does not move `StagePlane.eyePoint`, so no
     * highlight, shadow or specular pool on this table shifts when you aim the
     * lens. A shift is a fact about the picture and not about the room.
     *
     * What it *does* cost is framing, exactly as [lens] does: aim far enough and
     * the board leaves the glass. `CameraFit` reads `project` so it sees this
     * and dollies back, but only on the seat buttons.
     *
     * ## Units, and why they are these
     *
     * Multiples of the governing dimension, like [distance] and [panX], because
     * "a tenth of a stage up" has to mean the same thing on a tablet and a
     * monitor. Pixels would mean a saved pose framed one thing on the device it
     * was saved on and something else everywhere else.
     *
     * ## Last, and defaulted to nothing, and that is load-bearing twice
     *
     * At zero every byte of every projection is what it was, so this landed in a
     * release ahead of anything that moves it and `GoldenStageTest` never
     * noticed — the same move [lens], [panX] and `CardSolid.slab`'s trailing
     * `backScale` made.
     *
     * And it is last because [CameraRig.step], [CameraRig.nudge] and
     * [CameraRig.glide] once built a pose *positionally*, and a field inserted
     * before [distance] would have been a silent re-binding rather than a
     * compile error — the lens reset to one on every frame the camera sprang for
     * exactly that reason. All three use `copy` now; the ordering rule survives
     * them because the next person will not know that.
     */
    val shiftX: Float = 0f,
    val shiftY: Float = 0f,
) {
    companion object {
        /** The lens the stage has always used. See [StagePlane.forStage]. */
        const val HOME_DISTANCE = 1.45f

        /** The focal length everything in this app was tuned at. */
        const val HOME_LENS = 1f

        val Home = CameraPose()
    }
}

/**
 * Where the camera is allowed to be.
 *
 * ## What this used to be, and what it is now
 *
 * It used to hold three limits of taste and one of arithmetic, and the three
 * were argued for at length: pitch had a floor because a table seen head-on has
 * no depth cues, a ceiling because a card's printed text keystones past about
 * sixty degrees, and a flat distance floor because nobody needs to be that
 * close. Every one of those was a defensible answer to a question kai has since
 * answered differently — *"there shouldn't be a limit I want complete freedom
 * and control"* — so they are gone or opened right out.
 *
 * A card's text does keystone at eighty degrees. That is now something you can
 * see happening and pull back from, rather than something the tool refuses on
 * your behalf.
 *
 * ## The two that are not taste
 *
 * - **[clearance], and the floor it solves.** Past the lens plane
 *   [StagePlane.project] clamps rather than dividing by zero, and a clamp cannot
 *   be inverted — so `unproject` stops agreeing with `project`, and with it
 *   `flatten`, and with that every pile edge, card thickness and airborne
 *   shadow. This is arithmetic, it is the one wall left, and [minDistanceAt] is
 *   where it stands.
 * - **There is no roll, ever.** Not because it is hard — it is one more angle —
 *   but because a horizon that tips is the single most reliable way to make
 *   somebody feel ill, and a table has a horizon.
 */
data class CameraEnvelope(
    /**
     * Flat overhead is allowed now, and it is safe rather than merely permitted.
     *
     * At a pitch of nothing the projection is orthographic — every scale is one —
     * and [StagePlane.unprojectAt]'s divisor is `cameraDistance`, which is the
     * furthest from vanishing it ever gets. What is lost is the depth cues, and
     * losing them at the top of the range is a picture you chose rather than a
     * bug: the pile heights flatten, which is exactly what looking straight down
     * at a table does.
     */
    val minPitch: Float = 0f,
    /**
     * And down to eighty, which is where the horizon arrives on the glass.
     *
     * Ninety is the one angle that genuinely cannot be drawn: the table is edge
     * on, every card is a line, and [StagePlane.horizonY] runs through the middle
     * of the screen. Eighty keeps it in the upper quarter and leaves the table a
     * table. Card text is badly keystoned well before here and that is now the
     * user's call — see the class note.
     */
    val maxPitch: Float = 80f,
    /**
     * And the flat floor stops binding.
     *
     * It was 0.8, which is a number about taste sitting in front of a number
     * about arithmetic: [minDistanceAt]'s answer was below it at every pitch
     * under about fifty degrees, so the wall anybody actually hit was this one
     * and the solved one never got a say. A twentieth is far inside the mat's own
     * lens plane at every angle, so from here on the floor is always the solved
     * one — which is the point.
     */
    val minDistance: Float = 0.05f,
    /** And back far enough to see the room the desk is standing in. */
    val maxDistance: Float = 6f,
    /**
     * And how far the focal length may travel, in multiples of the shipped one.
     *
     * Longer than 2 is an orthographic diagram, which is a fine thing to look at
     * and not a thing to sit at. The wide end is 0.6 — about twenty millimetres
     * on the dial's own scale — and it is a magnification rather than a field of
     * view, so nothing about it can bend a card at the far edge. What it costs
     * is that the board is drawn small; what stops that being a fisheye is that
     * the lens cancels out of the perspective entirely. See [CameraPose.lens].
     */
    val minLens: Float = 0.6f,
    val maxLens: Float = 2f,
    /**
     * How far toward the lens the nearest corner of the mat may travel.
     *
     * The one number [minDistanceAt] is solved against, and **the only limit on
     * this camera that is not a matter of taste**. The keystone across the table
     * goes as `1 / (1 − this)`: a half caps it at two to one, 0.9 at ten to one,
     * and 0.99 at a hundred — which is a camera with its nose against the near
     * edge of the table, and about as free as arithmetic gets.
     *
     * **It must stay below one.** At one the corner *is* the lens:
     * [StagePlane.project] clamps rather than dividing by zero, and a clamp
     * cannot be inverted, so `unproject` stops agreeing with `project` and every
     * pile edge and airborne shadow goes with it. That is not a stylistic limit,
     * and it is why the knob that offers this stops a hundredth short.
     *
     * It shipped at a half, then 0.68, and is 0.9 now. Raising it only ever
     * *lowers* a floor, so no pose that was ever legal has become illegal and no
     * seat has moved. The default moved because freedom is the thing being asked
     * for and a default is what almost everybody gets — a knob three menus deep
     * is not an answer to "I want complete control".
     *
     * **And it does not go below what shipped**, which is a limit that had to be
     * measured rather than guessed. Tightening it pushes every floor *out*, and
     * with the old square-root floor 0.42 put thirty-four degrees past 1.34 —
     * which is [StageSeat.SEATED], so a seat on the bar became a place the
     * envelope refused to let you sit. The floor is linear now and every floor is
     * lower, so a half has a great deal of room under it and still buys nothing
     * anybody wants. Everything this knob is for is in the other direction.
     */
    val clearance: Float = DEFAULT_CLEARANCE,
    /**
     * How far the camera may be aimed away from the middle of the mat.
     *
     * A leash rather than a policy. Nothing goes wrong at a pan of forty — the
     * projection is as exact out there as it is anywhere — you simply cannot see
     * the table any more, and the way back is the seat buttons, which zero this.
     * Two stage-heights in any direction puts the whole board comfortably off the
     * glass already, so what this is really guarding is a flick of inertia
     * integrating for a few seconds into a number with an exponent on it.
     */
    val maxPan: Float = 2f,
    /**
     * And how far the lens may be aimed off the middle of the glass.
     *
     * A leash rather than a policy, like [maxPan], and a shorter one because a
     * shift costs framing immediately: at 0.5 the point the camera is aimed at
     * is half a stage-height off centre, which on a sixteen-by-ten stage puts it
     * at the very edge of the picture and half the board past it. Nothing goes
     * wrong out there — [StagePlane.project] and its inverse are as exact at a
     * shift of ten as at nothing — you simply cannot see the table, and the way
     * back is the seat buttons, which zero it.
     *
     * Six tenths, so there is somewhere past the useful range to stand and look
     * at what is beyond it, and so a flick of a knob cannot integrate into a
     * number with an exponent on it.
     */
    val maxShift: Float = 0.6f,
) {
    /**
     * The nearest pose inside the envelope. Yaw is free — a table turns all the way.
     *
     * The surface has to be passed in because the floor on [CameraPose.distance]
     * is not a constant: see [minDistanceAt].
     */
    fun clamp(pose: CameraPose, width: Float = 0f, height: Float = 0f): CameraPose {
        val pitch = pose.pitchDegrees.coerceIn(minPitch, maxPitch)
        val lens = pose.lens.coerceIn(minLens, max(minLens, maxLens))
        // The pan is held first, because the floor is solved against the corner
        // furthest from wherever the camera is aimed — pan away from the middle
        // and a corner of the mat gets further off, so the floor moves out. Clamp
        // them the other way round and the floor answers about a pan that is
        // about to be thrown away.
        val panX = pose.panX.holdTo(maxPan)
        val panY = pose.panY.holdTo(maxPan)
        val floor = minDistanceAt(pitch, width, height, panX, panY)
        return pose.copy(
            pitchDegrees = pitch,
            distance = pose.distance.coerceIn(floor, max(floor, maxDistance)),
            lens = lens,
            panX = panX,
            panY = panY,
            // Held after the floor rather than before it, and unlike the pan
            // that is not an ordering that matters: a shift moves the picture
            // and not the eye, so no corner of the mat gets any closer to the
            // lens and the floor cannot be a function of it. Made finite by the
            // same helper for the same reason — this one also arrives through
            // stored JSON a person can edit.
            shiftX = pose.shiftX.holdTo(maxShift),
            shiftY = pose.shiftY.holdTo(maxShift),
        )
    }

    /**
     * [this] held to ±[limit], and made finite on the way.
     *
     * `coerceIn` passes a `NaN` straight through — every comparison against it is
     * false — and a `NaN` pan poisons a target, a floor, a pose and every pile
     * edge downstream of all four without anything throwing. That is not
     * hypothetical: it is what [safeClearance] was written for after a test found
     * exactly that, and a pan reaches the arithmetic through the same stored JSON
     * a person can edit.
     */
    private fun Float.holdTo(limit: Float): Float =
        if (isFinite()) coerceIn(-limit, limit) else 0f

    /**
     * How close the camera may come at this pitch before the table reaches it.
     *
     * A single minimum distance is wrong, and wrong in a way that only shows up
     * at the extremes — which is where a test found it. Depth on this stage is
     * `y·sin(pitch)`, so the further round the table is laid back the further
     * the far corner travels toward the lens; at fifty-eight degrees and the old
     * flat floor of 0.95, the corner of a sixteen-by-ten mat crossed the camera
     * plane entirely. The projection survives that (it clamps rather than
     * dividing by zero) but a clamp cannot be inverted, so `unproject` silently
     * stopped agreeing with `project` — and with it `flatten`, and with that
     * every pile edge and airborne shadow, at one pose and not the others.
     *
     * So the floor is solved rather than chosen. Require the corner furthest from
     * the camera's own target to sit no more than [clearance] of the way to the
     * lens:
     *
     * ```
     * reach · zoom · sin(pitch) ≤ clearance · cameraDistance
     * ```
     *
     * and substitute what `planeFor` builds — `zoom = focal / (distance · G)` and
     * `cameraDistance = focal` — and the focal length falls out of both sides:
     *
     * ```
     * distance ≥ reach · sin(pitch) / (clearance · G)
     * ```
     *
     * It says something sensible in plain terms too: you may come close while you
     * are looking down at the table, and you must step back as you get low. That
     * is also true of a real table.
     *
     * ## It used to be a square root, and that was the old lens showing through
     *
     * `cameraDistance` carried [CameraPose.distance] until the release this
     * comment was rewritten in, so the distance appeared on *both* sides of the
     * constraint and the floor came out in `distance²`. The square root of a
     * number below one is larger than the number, which is why every floor has
     * dropped: at twenty-one degrees on a 1600×1000 stage the answer goes from
     * 0.85 to 0.38, and the flat [minDistance] that used to sit in front of it at
     * 0.8 is now a twentieth. Nothing about the guarantee changed — the corner is
     * still exactly [clearance] of the way to the lens at the floor — only the
     * projection it is guarding stopped moving the goalposts.
     *
     * ## The lens does not appear here, and that is a result rather than an
     * oversight
     *
     * A focal length multiplies `zoom` and `cameraDistance` by the same amount,
     * so it cancels exactly, as the derivation above shows it doing. Zooming does
     * not move the camera, so it cannot bring the table any closer to it. An
     * earlier draft did carry a lens factor, because the first version of
     * `planeFor` put the focal length on `cameraDistance` alone. That was a dolly
     * zoom wearing a lens's name, and the floor moving with it was one of the
     * several things it got wrong.
     */
    fun minDistanceAt(
        pitchDegrees: Float,
        width: Float,
        height: Float,
        panX: Float = 0f,
        panY: Float = 0f,
    ): Float {
        val governing = max(height, width * 0.55f)
        if (governing <= 0f) return minDistance

        // The corner furthest from where the camera is aimed, rather than the
        // half-diagonal. They are the same number until somebody pans, and after
        // that the half-diagonal is an under-estimate — which is the direction
        // that lets a corner cross the lens.
        val reach = reachFrom(width, height, panX, panY, governing)
        val toward = reach *
            sin(pitchDegrees.coerceIn(0f, 90f) * (PI.toFloat() / 180f))
        return max(minDistance, toward / (safeClearance() * governing))
    }

    /** How far the furthest corner of the surface is from the camera's target. */
    private fun reachFrom(
        width: Float,
        height: Float,
        panX: Float,
        panY: Float,
        governing: Float,
    ): Float {
        val offX = abs(if (panX.isFinite()) panX else 0f) * governing
        val offY = abs(if (panY.isFinite()) panY else 0f) * governing
        // The furthest corner from a point inside a rectangle is the one
        // diagonally opposite it, so each axis contributes its half plus however
        // far the target has walked the other way. Absolute values because which
        // corner wins flips with the sign and the answer does not.
        val alongX = width / 2f + offX
        val alongY = height / 2f + offY
        return sqrt(alongX * alongX + alongY * alongY)
    }

    /**
     * [clearance], held to its ends and made finite.
     *
     * Enforced here rather than trusted from the caller because this one reaches
     * the arithmetic through a stored preference, and the tuning round-trips
     * through JSON a person edits. A one in this field is a projection that
     * cannot be inverted; a `NaN` is worse, because `coerceIn` passes it
     * straight through — every comparison against it is false — and it then
     * poisons a floor, a pose, and every pile edge downstream of both, without
     * anything throwing. That last one is not hypothetical: it is what the test
     * for this found on the first run.
     */
    fun safeClearance(): Float =
        if (clearance.isFinite()) clearance.coerceIn(MIN_CLEARANCE, MAX_CLEARANCE) else DEFAULT_CLEARANCE

    companion object {
        /**
         * What [clearance] ships at, and the ends the panel and the arithmetic
         * both hold it to. See the field for why neither end is arbitrary.
         *
         * The top end is 0.99 rather than 0.95 because 0.95 was a margin of
         * comfort in front of a wall, and the wall is the number one. A hundredth
         * short of it is where the arithmetic actually stops; anything further
         * back is somebody deciding on kai's behalf how close is too close, which
         * is the thing this release exists to stop doing.
         */
        const val DEFAULT_CLEARANCE = 0.9f
        const val MIN_CLEARANCE = 0.5f
        const val MAX_CLEARANCE = 0.99f
    }
}

/**
 * The three places you can sit.
 *
 * Named poses rather than free flight, and that is a stance rather than a
 * shortcut. A camera you can put anywhere is a camera you have to put
 * somewhere, every time, and the honest observation about free-fly cameras is
 * that they spend most of their life somewhere slightly wrong on the way back
 * to somewhere right. Orbit freely by all means — but there is always one key
 * that puts the table back where it reads best.
 */
enum class StageSeat(val label: String, val pose: CameraPose) {
    /**
     * Nearly overhead, nearly orthographic: the reading seat. Not *flat* — a
     * board with no depth at all loses the pile heights, which are how you count
     * a graveyard at a glance.
     */
    OVERHEAD("Overhead", CameraPose(yawDegrees = 0f, pitchDegrees = 5f, distance = 1.62f)),

    /** The stage as it has always been. The one everything else was tuned at. */
    TABLE("Table", CameraPose(yawDegrees = 0f, pitchDegrees = StagePlane.TILT, distance = 1.45f)),

    /**
     * Low and close, from the player's own chair. Cards keystone here and that
     * is the point: this is the seat for watching a card land, not for reading
     * the board.
     */
    SEATED("Seated", CameraPose(yawDegrees = 0f, pitchDegrees = 34f, distance = 1.34f)),

    /**
     * Your own eye level, and the first seat here that is not a reading seat.
     *
     * ## What the other three have in common
     *
     * [tiltDegrees][StagePlane.tiltDegrees] is the angle off the table's
     * *normal*, so the elevation above the felt is ninety minus it. [OVERHEAD]
     * looks down at eighty-five degrees, [TABLE] at sixty-nine, and [SEATED] —
     * whose own note calls it "from the player's own chair" — at fifty-six. A
     * person sitting at a desk is nowhere near fifty-six degrees above it. All
     * three are somebody standing over a table, and they differ in how far over.
     *
     * At fifty-eight the elevation is thirty-two, which is a head at a desk, and
     * everything follows from that one number: the horizon arrives just off the
     * top of the glass instead of two stage-heights above it, the wall stops
     * being a strip, and the room goes from a fifth of the picture to about
     * half.
     *
     * ## Why the distance is what it is, and it was solved
     *
     * The keystone across the table is a function of the distance as a multiple
     * of [CameraEnvelope.minDistanceAt]'s floor, and — because that floor is
     * linear in `sin(pitch)` — it is the *same* multiple at every pitch. So a
     * low seat need not distort more than a high one; it has to step back
     * proportionally. One and a half times the floor at fifty-eight degrees is
     * 1.33, which puts a card on the glass at **exactly the width [SEATED]
     * draws it**, measured on the reference stage and on a Tab S11.
     *
     * What it does not keep is the card's *height*. A card lying on a table is
     * foreshortened by `cos(pitch)`, so at fifty-eight it is drawn about a third
     * shorter than at thirty-four. That is not a defect and it is not tunable —
     * it is what looking at a table from a chair does — and it is the reason
     * this is a fourth seat rather than a change to the third. kai's answer to
     * it is that a hold already opens the card at full size
     * (`ui/play/CardReader.kt`), so the seat you *read* from and the seat you
     * *sit* in do not have to be the same seat.
     *
     * ## And the shift, which is the only reason this seat fits at all
     *
     * A low seat draws the board high in the frame, and the room it is supposed
     * to be showing you is above that. The shift is what puts the board back
     * down, and how far it may go is **measured against `layout.bounds` rather
     * than `layout.field`** — because a seat you play from with your hand off
     * the bottom of the screen is not a seat, and `CameraFit` will not catch it:
     * a seat press fits the field, and the hand band is deliberately not in it.
     *
     * The limit is 0.149 on a sixteen-by-ten stage and on a Tab S11, and 0.139
     * on a wide phone — where the governing dimension comes off the *width*, so
     * the whole scale is different and the answer had to be measured rather than
     * assumed to follow. Thirteen hundredths is under all three.
     * `CameraShiftTest.theHeadSeatIsAPlaceYouCanPlayFrom` sweeps them.
     *
     * A tall portrait stage does not fit at this distance at all, at any shift,
     * and is left to `CameraFit`: the seat press dollies back and you get the
     * chair from further away. This app is landscape and that is the graceful
     * end of it, not a case worth moving the seat for.
     */
    POV("POV", CameraPose(yawDegrees = 0f, pitchDegrees = 58f, distance = 1.33f, shiftY = 0.13f)),
}

/**
 * Keeping the table on the screen.
 *
 * There are two ways to guarantee that, and picking the wrong one costs a fifth
 * of every card on the board.
 *
 * The obvious way is to hand [BoardLayouter] the worst growth the camera could
 * ever produce, so the layout is small enough to survive any angle. But the
 * worst case is the mat's *diagonal* facing the camera, and on a sixteen-by-ten
 * stage that is nearly twice the distance the bottom edge is — so every card
 * would be drawn about twenty per cent smaller, permanently, to buy an angle
 * that might never be used. On a tool whose entire purpose is reading cards
 * that is the wrong trade, and it also quietly breaks the house rule: the
 * layout is *solved*, and a layout solved against a hypothetical is negotiated.
 *
 * So it is inverted. The layout is solved once, for the seat the stage opens at,
 * and the **camera** is what gives: [fit] takes a pose the user asked for and
 * returns the nearest one that keeps the board's own corners on the glass,
 * dollying back as far as it must. Turning the table forty-five degrees steps
 * you back a little, which is also what it should look like.
 */
object CameraFit {

    /**
     * How far outside the surface a corner may stray before it counts as off.
     *
     * A hair of tolerance, and it is not slop: the felt is allowed to run under
     * the edge of the screen the way a real mat runs off the edge of a real
     * table. What must not happen is a zone or a pile leaving the glass.
     *
     * **Which is a statement about what you hand in as [bounds], not about this
     * number.** The play stage passes `layout.field` — the three rows of zones,
     * every pile among them — and deliberately not `layout.bounds`, which adds
     * the hand band along the bottom edge of the stage. The hand is the first
     * thing a push-in costs, and treating that as a reason to refuse capped the
     * camera at 1.47 when the envelope would have allowed 1.05. See
     * `MatInput.settle`.
     */
    private const val OVERHANG = 0.02f

    /**
     * Iterations of the search for a distance that fits.
     *
     * Solved by bisection rather than algebra, and on purpose. The constraint is
     * eight inequalities — four corners against four screen edges — each of
     * which is a different rational function of the distance, and a closed form
     * for the binding one would be a page of case analysis that has to be redone
     * the first time the envelope changes. Bisection is obviously correct,
     * costs sixteen projections of four points, and runs when the user lets go
     * of a gesture rather than every frame.
     */
    private const val STEPS = 16

    /** Whether every corner of [bounds] lands on the glass under [plane]. */
    fun holds(plane: StagePlane, bounds: Slot): Boolean {
        val marginX = plane.width * OVERHANG
        val marginY = plane.height * OVERHANG
        for (x in 0..1) {
            for (y in 0..1) {
                val corner = plane.project(
                    if (x == 0) bounds.left else bounds.right,
                    if (y == 0) bounds.top else bounds.bottom,
                )
                if (corner.x < -marginX || corner.x > plane.width + marginX) return false
                if (corner.y < -marginY || corner.y > plane.height + marginY) return false
            }
        }
        return true
    }

    /**
     * The nearest pose to [wanted] that keeps [bounds] on screen.
     *
     * The angles are honoured exactly — if you asked to look at the table from
     * the side you get to look at it from the side — and only the distance
     * moves, because dollying back is the one correction that cannot make the
     * answer worse. Pulling the camera away shrinks the projected board
     * monotonically toward the shape the layout was solved for, which is the
     * property that makes a bisection valid at all and is worth stating: at
     * [CameraEnvelope.maxDistance] the projection is nearly orthographic and the
     * board is very nearly the rectangle the fitter drew.
     */
    fun fit(
        wanted: CameraPose,
        bounds: Slot,
        envelope: CameraEnvelope,
        surfaceWidth: Float,
        surfaceHeight: Float,
        plane: (CameraPose) -> StagePlane,
    ): CameraPose {
        val safe = envelope.clamp(wanted, surfaceWidth, surfaceHeight)
        if (holds(plane(safe), bounds)) return safe

        var near = safe.distance
        var far = envelope.maxDistance
        if (!holds(plane(safe.copy(distance = far)), bounds)) {
            // Even the back of the envelope cannot contain it. Give the user the
            // furthest seat rather than refusing: a board that overhangs slightly
            // is legible, and a camera that silently ignores a gesture is not.
            return safe.copy(distance = far)
        }

        repeat(STEPS) {
            val middle = (near + far) / 2f
            if (holds(plane(safe.copy(distance = middle)), bounds)) far = middle else near = middle
        }
        return safe.copy(distance = far)
    }
}

/**
 * Turning by the short way round.
 *
 * Yaw is an angle on a circle and a spring is not: told to go from 350° to 10°
 * a spring takes the 340° route, and the table spins most of the way round to
 * arrive somewhere it was almost already at. So targets are rewritten into the
 * representative nearest the value the camera currently holds, and yaw is
 * allowed to wander outside 0..360 as far as it likes — nothing reads it except
 * a sine and a cosine, and both are periodic.
 */
object Turns {

    /** The representative of [target] nearest to [current], in degrees. */
    fun nearest(current: Float, target: Float): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        // Half a turn is genuinely ambiguous — both ways are the same distance —
        // so it is settled here rather than left to the sign of a modulo, which
        // would send an identical gesture two different ways on two platforms.
        if (delta <= -180f) delta += 360f
        return current + delta
    }

    /** [degrees] folded to (-180, 180]. For display, never for springing. */
    fun signed(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * The seat this pose is closest to, or null if it is not close to any.
     *
     * The readout uses it, and so does the detent: coming to rest within a few
     * degrees of a named seat should *say* so, because a camera you cannot name
     * the position of is a camera you cannot get back to.
     */
    fun seatAt(pose: CameraPose, tolerance: Float = 2.5f): StageSeat? = StageSeat.entries
        .firstOrNull {
            abs(signed(pose.yawDegrees - it.pose.yawDegrees)) <= tolerance &&
                abs(pose.pitchDegrees - it.pose.pitchDegrees) <= tolerance &&
                abs(pose.distance - it.pose.distance) <= 0.04f &&
                // The pan *is* compared, where the lens is not, and the two are
                // different kinds of thing. A seat is a place, and a camera aimed
                // half a table away from the middle is not sitting at it however
                // right the three angles are. A hundredth of a stage is a couple
                // of pixels of slack for a float that has been through a spring.
                abs(pose.panX - it.pose.panX) <= 0.01f &&
                abs(pose.panY - it.pose.panY) <= 0.01f &&
                // And the shift is compared, on the pan's side of the line
                // rather than the lens's. Where the lens is aimed is part of
                // where you are sitting — a seat that put the horizon a third
                // of the way down is not the seat you are in once you have
                // aimed it back at the middle, however right the three angles
                // are. The lens is the other kind of thing: you do not change
                // focal length by sitting somewhere else.
                abs(pose.shiftX - it.pose.shiftX) <= 0.01f &&
                abs(pose.shiftY - it.pose.shiftY) <= 0.01f
            // [CameraPose.lens] is deliberately **not** compared. A seat is a
            // chair, not a chair and a lens: you do not change focal length by
            // sitting somewhere else, and a readout that refused to name the
            // table seat because the lens had been tuned would be reporting on
            // the wrong thing. [CameraRig.aimAt] carries the lens across for the
            // same reason.
        }

    /** How far round the table you are, for a readout: 0, 45, 90 … */
    fun bearing(pose: CameraPose): Int {
        val folded = signed(pose.yawDegrees).roundToInt()
        return if (folded < 0) folded + 360 else folded
    }
}

/**
 * The camera in motion: a pose that springs toward another one.
 *
 * Held as plain fields rather than snapshot state, and stepped from the play
 * stage's one `withFrameNanos` loop, for the same reason `StageCard` is: this
 * changes sixty times a second and the only things that read it are a
 * `graphicsLayer` and a draw lambda. A camera in snapshot state recomposes the
 * board — sixty cards, every frame — and re-keys the pointer input, which
 * cancels whatever gesture is being made. Both of those are quiet failures that
 * read as physics bugs.
 *
 * The spring itself is the app's own, so a camera move has exactly the weight a
 * card move does. That is not decoration: two objects on one screen easing on
 * two different curves is the thing that makes a scene read as assembled rather
 * than as a place.
 */
class CameraRig(
    /**
     * Writable, because one of the limits is now a preference.
     *
     * [CameraEnvelope.clearance] is on the tuning panel, and a panel that could
     * not change it would be a slider that does nothing — which is the fault
     * this release exists to fix, not one to reintroduce. Written from the play
     * screen's tuning `SideEffect`, immediately before the pose it goes with,
     * and nowhere else: a rig whose envelope moves between a clamp and the
     * `CameraFit` that reads the same one would be answering two questions.
     *
     * A plain field on purpose, like the pose beside it. Nothing draws from it —
     * it is read when a pose is clamped and when a gesture settles.
     */
    var envelope: CameraEnvelope = CameraEnvelope(),
    seat: StageSeat = StageSeat.TABLE,
) {
    /**
     * The surface the stage is being drawn on, refreshed by the screen rather
     * than captured, because this object outlives any one composition of it —
     * the same arrangement `MatPilot` uses for its layout, and for the same
     * reason. It is only read when a pose is clamped, never per frame.
     */
    var width: Float = 0f
    var height: Float = 0f

    var pose: CameraPose = seat.pose
        private set

    var target: CameraPose = seat.pose
        private set

    private var vYaw = 0f
    private var vPitch = 0f
    private var vDistance = 0f

    /**
     * And where the camera is aimed, which used to arrive all at once.
     *
     * [step] sprang three of the pose's fields and then, on the frame the three
     * of them settled, assigned `pose = target` — which snapped the other three.
     * Nobody saw it, because every [StageSeat] declares a pan of nothing and the
     * pan is the only one of the three a gesture ever moved, so the jump was
     * always from zero to zero.
     *
     * Giving a seat a **shift** ends that: flying from a seat that aims at the
     * middle to one that aims above it would ease the three angles over half a
     * second and then move the whole picture in one frame. So both pans spring
     * now, and the settle test asks about all five. The lens still does not —
     * it is not somewhere you travel to.
     */
    private var vPanX = 0f
    private var vPanY = 0f
    private var vShiftX = 0f
    private var vShiftY = 0f
    private var parked = true

    /**
     * Whether [step] is running a flick down rather than springing to a seat.
     *
     * Two modes over one set of velocities rather than two rigs, because they are
     * mutually exclusive by construction: [aimAt] gives the camera somewhere to
     * be and [coast] gives it a speed, and asking for either cancels the other.
     * A camera doing both at once is a camera arguing with itself.
     */
    private var coasting = false

    /** True while the camera is still travelling, so the loop can skip it when not. */
    val moving: Boolean get() = !parked

    /** Puts the camera somewhere with no travel — for opening the stage. */
    fun placeAt(pose: CameraPose) {
        val safe = envelope.clamp(pose, width, height)
        this.pose = safe
        halt()
    }

    /** Sends the camera somewhere. Yaw takes the short way round. */
    fun aimAt(pose: CameraPose) {
        val safe = envelope.clamp(pose, width, height)
        val next = safe.copy(yawDegrees = Turns.nearest(this.pose.yawDegrees, safe.yawDegrees))
        // A coast has to be cancelled even when the destination is where the
        // camera was already headed, because "already headed there" is what
        // `target` says and a coasting camera writes its target every frame. The
        // early return is below the mode change for that reason and not by
        // accident.
        coasting = false
        if (next == target) return
        target = next
        parked = false
    }

    /**
     * And to a named seat, **keeping whatever lens is on the camera**.
     *
     * A seat is where you sit. The lens is what you are looking through, and
     * nothing about moving to the other side of a table changes it. Taking the
     * seat's own lens instead would mean that tuning a focal length and then
     * tapping "Seated" silently threw the tuning away — which is exactly the
     * failure a person would read as the tool being broken.
     *
     * Every seat declares [CameraPose.HOME_LENS] today, so this is a no-op until
     * something moves one; `CameraLensTest.everySeatIsStillAtTheShippedLens` is
     * the tripwire for that day.
     */
    fun aimAt(seat: StageSeat) = aimAt(seat.pose.copy(lens = pose.lens))

    /**
     * Moves the camera *by* an amount, for a gesture in progress.
     *
     * Assigned rather than sprung, and that is the same rule a carried card
     * obeys: a spring between a finger and the thing it is moving is lag, and
     * lag on a direct manipulation is what makes it feel like a remote control
     * rather than a hand. The spring is for going *to* a seat, not for being
     * dragged.
     */
    fun nudge(
        deltaYaw: Float,
        deltaPitch: Float,
        dollyBy: Float = 0f,
        deltaPanX: Float = 0f,
        deltaPanY: Float = 0f,
    ) {
        // `copy`, not a fresh pose: a gesture moves some of the things a camera
        // is and not all of them, and building a new one from the arguments to
        // hand quietly reset the rest on every frame of every drag.
        val next = envelope.clamp(
            pose.copy(
                yawDegrees = pose.yawDegrees + deltaYaw,
                pitchDegrees = pose.pitchDegrees + deltaPitch,
                distance = pose.distance * (1f + dollyBy),
                panX = pose.panX + deltaPanX,
                panY = pose.panY + deltaPanY,
            ),
            width,
            height,
        )
        pose = next
        target = next
        halt()
    }

    /**
     * Stop, wherever you are.
     *
     * Called by [nudge] because a finger arriving on a camera that is still
     * travelling has to win, and called on the press for the same reason: a table
     * coasting away from a hand reaching for it is the failure that makes people
     * describe inertia as "it fights me". Catching a flick is the gesture that
     * makes the whole feature safe, and it is this one line.
     */
    fun halt() {
        target = pose
        vYaw = 0f
        vPitch = 0f
        vDistance = 0f
        vPanX = 0f
        vPanY = 0f
        vShiftX = 0f
        vShiftY = 0f
        coasting = false
        parked = true
    }

    /**
     * Let go of a turn and let it run down.
     *
     * `docs/classic/AAA.md` #7: *"Flick it and it coasts to rest on the same damping.
     * This is most of what makes a camera feel like it weighs something."*
     *
     * ## Why this is not the spring already here
     *
     * A spring goes *to* somewhere, and a flick has no destination — it has a
     * speed and a direction and nothing at the end of it. Expressing one as the
     * other means inventing a target from the velocity, which is a guess that
     * gets the overshoot wrong at both ends: a hard flick lands somewhere
     * arbitrary and a gentle one refuses to move at all. So this integrates
     * instead, and the damping is the only thing shared.
     *
     * ## The rates are per second
     *
     * Degrees per second, because the caller measures them from a pointer stream
     * whose frames are not a fixed length, and a per-frame rate silently means
     * something different on a 120Hz tablet than on a 60Hz one. [step] multiplies
     * by its own `dt` for the same reason.
     *
     * Below [COAST_FLOOR] nothing happens at all, which is what stops a slow drag
     * that merely ended from drifting on afterwards — a release is a release, and
     * only a *flick* is a flick.
     */
    fun coast(yawPerSecond: Float, pitchPerSecond: Float) {
        if (!yawPerSecond.isFinite() || !pitchPerSecond.isFinite()) return
        val speed = sqrt(yawPerSecond * yawPerSecond + pitchPerSecond * pitchPerSecond)
        if (speed < COAST_FLOOR) return

        val scale = if (speed > COAST_CEILING) COAST_CEILING / speed else 1f
        vYaw = yawPerSecond * scale
        vPitch = pitchPerSecond * scale
        // A flick is a turn and nothing else. Every other velocity is cleared
        // rather than left, so a coast begun from a spring that had not finished
        // cannot smuggle the rest of that spring's momentum into it.
        vDistance = 0f
        vPanX = 0f
        vPanY = 0f
        vShiftX = 0f
        vShiftY = 0f
        coasting = true
        parked = false
    }

    /** Springs one frame. Returns whether anything moved. */
    fun step(spec: SpringSpec, dt: Float): Boolean {
        if (parked) return false
        if (coasting) return glide(dt)

        val yaw = Springs.step(
            SpringValue(pose.yawDegrees, vYaw),
            target.yawDegrees,
            spec,
            dt,
        )
        val pitch = Springs.step(
            SpringValue(pose.pitchDegrees, vPitch),
            target.pitchDegrees,
            spec,
            dt,
        )
        val distance = Springs.step(
            SpringValue(pose.distance, vDistance),
            target.distance,
            spec,
            dt,
        )

        // And where it is aimed, both on the mat and on the glass. These are
        // the four that used to arrive in one frame at the end of the travel;
        // see [vPanX].
        val panX = Springs.step(SpringValue(pose.panX, vPanX), target.panX, spec, dt)
        val panY = Springs.step(SpringValue(pose.panY, vPanY), target.panY, spec, dt)
        val shiftX = Springs.step(SpringValue(pose.shiftX, vShiftX), target.shiftX, spec, dt)
        val shiftY = Springs.step(SpringValue(pose.shiftY, vShiftY), target.shiftY, spec, dt)

        vYaw = yaw.velocity
        vPitch = pitch.velocity
        vDistance = distance.velocity
        vPanX = panX.velocity
        vPanY = panY.velocity
        vShiftX = shiftX.velocity
        vShiftY = shiftY.velocity
        // Seven springs, eight fields. The lens is not sprung — it is not
        // somewhere you travel to — so it has to be carried rather than rebuilt.
        pose = pose.copy(
            yawDegrees = yaw.value,
            pitchDegrees = pitch.value,
            distance = distance.value,
            panX = panX.value,
            panY = panY.value,
            shiftX = shiftX.value,
            shiftY = shiftY.value,
        )

        val settled = Springs.settled(
            SpringValue(yaw.value, yaw.velocity),
            target.yawDegrees,
            ANGLE_TOLERANCE,
            ANGLE_TOLERANCE,
        ) &&
            Springs.settled(
                SpringValue(pitch.value, pitch.velocity),
                target.pitchDegrees,
                ANGLE_TOLERANCE,
                ANGLE_TOLERANCE,
            ) &&
            Springs.settled(
                SpringValue(distance.value, distance.velocity),
                target.distance,
                DISTANCE_TOLERANCE,
                DISTANCE_TOLERANCE,
            ) &&
            // The same tolerance as the distance, because these are the same
            // kind of number: multiples of the governing dimension, where the
            // distance's thousandth is already a fraction of a pixel.
            aimSettled(panX, target.panX) &&
            aimSettled(panY, target.panY) &&
            aimSettled(shiftX, target.shiftX) &&
            aimSettled(shiftY, target.shiftY)

        if (settled) {
            pose = target
            vYaw = 0f
            vPitch = 0f
            vDistance = 0f
            vPanX = 0f
            vPanY = 0f
            vShiftX = 0f
            vShiftY = 0f
            parked = true
        }
        return true
    }

    private fun aimSettled(value: SpringValue, target: Float): Boolean =
        Springs.settled(value, target, DISTANCE_TOLERANCE, DISTANCE_TOLERANCE)

    /**
     * One frame of a flick running down. Returns whether anything moved.
     *
     * Exponential decay applied as `exp(−λ·dt)` rather than as a fixed factor per
     * frame, so a 120Hz tablet and a 60Hz desktop run the same flick down over
     * the same *seconds*. That is the same reason [coast] takes its rates per
     * second, and it is the bug a per-frame multiplier always eventually is.
     *
     * The pose is clamped through the envelope every step, which is what makes
     * hitting the pitch ceiling stop the coast dead rather than let it grind
     * against the limit for another second: the clamp refuses the movement, and
     * a velocity that is no longer moving anything is spent.
     */
    private fun glide(dt: Float): Boolean {
        if (dt <= 0f) return false

        val before = pose
        val next = envelope.clamp(
            pose.copy(
                yawDegrees = pose.yawDegrees + vYaw * dt,
                pitchDegrees = pose.pitchDegrees + vPitch * dt,
            ),
            width,
            height,
        )
        pose = next
        target = next

        val decay = exp(-COAST_DECAY * dt)
        vYaw *= decay
        vPitch *= decay
        // A clamp that refused the move leaves the axis exactly where it was, and
        // an axis that cannot move has no speed left worth carrying. Without this
        // a flick into the pitch ceiling keeps its velocity, and the *next* flick
        // the other way starts with a second one already in it.
        if (next.pitchDegrees == before.pitchDegrees) vPitch = 0f

        if (sqrt(vYaw * vYaw + vPitch * vPitch) < COAST_FLOOR) halt()
        return true
    }

    private companion object {
        /**
         * A twentieth of a degree, and a thousandth of a stage height.
         *
         * Tighter than a card's, because a card that stops a pixel early stops a
         * pixel early and a camera that stops a pixel early moves *everything* a
         * pixel. The whole board is downstream of these two numbers.
         */
        const val ANGLE_TOLERANCE = 0.05f
        const val DISTANCE_TOLERANCE = 0.001f

        /**
         * Degrees a second below which a release is a release rather than a flick.
         *
         * Twelve, which is a finger crossing about a ninth of the screen in the
         * last second of a drag — comfortably above the jitter of a hand coming
         * to rest on glass, and comfortably below anything anybody would call a
         * throw. It is also the speed the coast stops at, so the same number
         * decides that a flick has begun and that it has finished, and the table
         * cannot come to rest somewhere it would refuse to start from.
         */
        const val COAST_FLOOR = 12f

        /**
         * And a ceiling, because a pointer stream can report a very large number.
         *
         * Nine hundred degrees a second is two and a half turns, which takes about
         * four seconds to run down — long, and still a table you are watching
         * rather than one that has got away. Above it the velocity is scaled back
         * along its own direction rather than clipped per axis, so a hard diagonal
         * flick keeps its heading instead of squaring off against the axes.
         */
        const val COAST_CEILING = 900f

        /**
         * How fast it runs down: `exp(−this · seconds)`.
         *
         * Two and a half, so a flick keeps about eight per cent of its speed after
         * a second and is under the floor shortly after — the same order as the
         * springs the cards use, which is the point. `docs/classic/DESIGN.md` §12's
         * complaint about a scene reading as assembled rather than as a place is
         * about exactly this: two things on one screen easing on two different
         * curves.
         */
        const val COAST_DECAY = 2.5f
    }
}

/**
 * The plane this pose describes, for a surface of [width] by [height].
 *
 * The one place [CameraPose.distance] is turned into the two numbers the
 * projection needs, and it ties them together the way a real camera does:
 * stepping back both weakens the perspective and makes the subject smaller.
 * `cameraDistance` alone does only the first, which is the trap — a camera
 * built on it can never make the table fit, because at the centre of the plane
 * every distance projects at scale one.
 *
 * Zoom is the ratio to the seat the stage opens at, so the home pose is
 * exactly `1` and every constant tuned against it still means what it meant.
 */
fun CameraPose.planeFor(width: Float, height: Float): StagePlane {
    val governing = max(height, width * 0.55f)
    val focal = max(lens, MIN_LENS) * CameraPose.HOME_DISTANCE * governing
    return StagePlane(
        width = width,
        height = height,
        tiltDegrees = pitchDegrees,
        // **The focal length, and a function of the lens alone.** `project`
        // divides by `cameraDistance − depth`, and so does Compose: this number
        // *is* the lens, in pixels, and nothing but the lens may be on it.
        //
        // It carried `distance` for as long as there was a camera, and that is
        // the whole of kai's "the perspective seems to shift a lot when moving
        // the camera around". A focal length that moves when you walk is a dolly
        // zoom on every dolly. Measured on a 1600-wide stage it swung from 34
        // degrees of field of view at the back of the envelope to 77 at the
        // front — twenty-one millimetres to fifty-seven — with the lens dial
        // sitting still. It also made *tilting* change the lens, because the
        // pitch moves `minDistanceAt`, which moves the distance, which moved
        // this.
        cameraDistance = focal,
        yawDegrees = yawDegrees,
        // And the magnification is where the walking lives. Stepping back
        // shrinks the subject as `1/distance`, exactly as it does through a real
        // lens you have not touched, and the keystone across the table now goes
        // as `1/distance` rather than as its square.
        zoom = focal / max(distance * governing, MIN_REACH),
        targetX = width / 2f + panX * governing,
        targetY = height / 2f + panY * governing,
        // And where that lands in the picture. The target is a point on the
        // mat and this is a point on the glass; they were one number for as
        // long as the camera had to be pointing at the middle of what it drew.
        axisX = width / 2f + shiftX * governing,
        axisY = height / 2f + shiftY * governing,
    )
}

/**
 * A lens of nothing is a division by nothing. Below anything the envelope allows.
 */
private const val MIN_LENS = 1e-3f

/**
 * And an eye with nowhere to stand is the same division.
 *
 * A *pixel* rather than a thousandth of a stage, because unlike the lens this
 * divides a length: `distance · governing` is how far back the camera really is,
 * and on a stage with no surface yet that product is zero for both reasons at
 * once. The envelope's floor is orders of magnitude above it — this is the guard
 * for the first composition, not a limit anybody can reach.
 */
private const val MIN_REACH = 1f
