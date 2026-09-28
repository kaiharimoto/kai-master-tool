package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.motion.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A point after projection: where it lands on screen, and how big it got. */
data class Projected(val x: Float, val y: Float, val scale: Float, val depth: Float)

/**
 * A point with a height, rewritten as a point on the mat that will look like it.
 *
 * [scale] is the correction that goes with it: the mat's own projection will
 * size whatever is drawn here by however much it sizes that part of the felt,
 * which is not the same as how much a thing standing off the felt should grow.
 * Multiply by this and it is.
 */
data class Flattened(val x: Float, val y: Float, val scale: Float)

/**
 * The tilted surface the play stage is drawn on, as arithmetic rather than a guess.
 *
 * One transform described twice. The parent `graphicsLayer` applies it to
 * everything resting on the mat, and [project] applies the identical maths to
 * anything that has left the mat and lives on the flat overlay above it. That
 * the two agree exactly at z = 0 is what lets a card lift off the table without
 * a seam at the moment it is picked up — and it is a property a test can hold
 * this to, rather than an intention someone had once.
 *
 * Compose's `rotationX` is a right-handed rotation about +X with +Y pointing
 * *down* and +Z toward the viewer, so a positive tilt brings the bottom edge
 * forward. Lift is along the plane's **normal** rather than straight at the
 * camera, because that is what taking a card off a table does — and it gives
 * the up-the-screen parallax for free instead of as a second effect.
 *
 * ## Turning the table
 *
 * [yawDegrees] spins the mat about its own normal — the table turning on the
 * spot — and it is applied **before** the tilt. That ordering is the whole
 * reason this file is still a page long instead of a matrix library.
 *
 * A camera orbiting about the *world's* vertical would compose the other way
 * round, and then [unproject] could no longer be solved in closed form: it
 * would need a ray cast at a plane, which is fine until you remember that
 * [flatten] is defined as projection composed with its own inverse, so an
 * approximate inverse tears every pile edge, card thickness and airborne shadow
 * at the same moment. Spin-then-tilt keeps the inverse exact, because spinning
 * is a rigid rotation you simply undo.
 *
 * And it costs nothing to draw. Compose builds its layer transform as
 * `Rx · Ry · Rz`, which turns a point about Z *first* and about X last — which
 * is precisely spin-then-tilt. So the mat's own `graphicsLayer` reproduces this
 * projection exactly with `rotationZ = -yaw`, `rotationX = tilt` and no third
 * angle at all. Compose's order was already the order we wanted; `Rot3` was
 * written to match it for the card renderer, and it pays a second time here.
 */
data class StagePlane(
    val width: Float,
    val height: Float,
    val tiltDegrees: Float = TILT,
    val cameraDistance: Float,
    /** How far the table has been turned on the spot, about its own normal. */
    val yawDegrees: Float = 0f,
    /**
     * How large the table is drawn, before any perspective.
     *
     * Separate from [cameraDistance], and the separation is not pedantry — it
     * is the thing that is easy to get wrong. In this projection (and in
     * Compose's, which it has to match) `cameraDistance` sets how *strongly*
     * perspective bites, and nothing else: a point at the centre of the plane
     * projects at scale one however far back the camera is. So pulling the
     * camera away does not make the table smaller, and a "dolly out until it
     * fits" built on `cameraDistance` alone converges on nothing at all.
     *
     * A real camera moving back does both at once, which is why [CameraPose]
     * ties them together — but they are two numbers, and the plane is the wrong
     * place to hide that.
     *
     * Applied to the mat's coordinates *before* the spin and the tilt, because
     * that is where Compose applies `scaleX`/`scaleY`: its layer transform is
     * `Rx · Ry · Rz · S`, so the point is scaled first and turned afterwards.
     */
    val zoom: Float = 1f,
    /**
     * The point on the mat the camera is aimed at, which used to be the middle.
     *
     * ## Two roles that were one number
     *
     * [centreX] plays two parts in this projection and they were the same value
     * for as long as there was no pan. It is the **mat** point everything is
     * measured from — the pivot the spin and the tilt turn about, and the point
     * the perspective divide is centred on — and it is also the **screen** point
     * that pivot lands on. Splitting them is the whole of panning: subtract the
     * target, add the screen centre.
     *
     * ## Why this does not need the off-axis inverse it was said to need
     *
     * [CameraPose]'s own documentation refused a target on the grounds that
     * *"a movable target means the vanishing point stops being the centre of the
     * layer, and then … [unproject] needs an off-axis inverse."* That is true of
     * the other pan — sliding the finished picture sideways, which moves the
     * vanishing point off the glass's middle. It is not true of this one. Moving
     * what the camera *looks at* leaves the vanishing point exactly where it was,
     * at the centre of the screen, because the divide is still centred on the
     * pivot and the pivot is still drawn in the middle. Every line of
     * [unprojectAt] is unchanged but its last two: it adds these back instead of
     * the screen's centre.
     *
     * So the inverse stays closed form, and [flatten] — which is projection
     * composed with its own inverse, and which every pile edge, card thickness
     * and airborne shadow is drawn through — stays exact.
     *
     * ## Trailing and defaulted, which is load-bearing
     *
     * At the middle of the mat every byte of every projection is what it was, so
     * this landed in a release ahead of anything that moves it and
     * `GoldenStageTest` never noticed — the same move [CameraPose.lens] and
     * `CardSolid.slab`'s trailing `backScale` made. Defaulted *from* [width] and
     * [height], which Kotlin allows because they are declared before it.
     */
    val targetX: Float = width / 2f,
    val targetY: Float = height / 2f,
    /**
     * Where the optical axis meets the glass — the principal point, and the
     * thing that lets this be a seat rather than a turntable.
     *
     * Named for the axis rather than for the screen because `unproject` and
     * `unprojectAt` already take a screen x and a screen y, and a property that
     * shadowed their parameters would be read by the compiler one way and by a
     * person the other.
     *
     * ## The two jobs [centreX] was doing
     *
     * There were three points in this projection and only two names for them.
     * [targetX] is the **mat** point everything is measured from — the pivot the
     * spin and the tilt turn about, and the point the perspective divide is
     * centred on. [centreX] was both the middle of the glass *and* the place
     * that pivot lands. Splitting the second job out is a **lens shift**: the
     * rise and fall a view camera has, and on this stage the difference between
     * a table on a turntable and a desk in front of somebody.
     *
     * ## Why a person at a desk needs it
     *
     * Sit at a table and your eye level — the horizon — is *above* the middle of
     * what you see, because you are looking down at the thing in front of you.
     * With the pivot nailed to the middle of the glass the only way to bring the
     * horizon onto the screen is to pitch the camera until [horizonY] walks down
     * into frame, and by then the table is nearly edge-on and every card is a
     * line. Those are two different wants and they were one number.
     *
     * It also decides what the room gets. `Scenery.ROOM_ABOVE` buys the wall and
     * the window somewhere to be by making the *board* smaller, on every device
     * at every seat, because aiming was not available. This is the other lever,
     * and it costs no card size at all.
     *
     * ## Why it is cheap, where the pan it resembles was not
     *
     * It is a constant screen offset applied **after** the divide, so the inverse
     * is one subtraction. [unprojectAt] keeps its closed form, and so therefore
     * does [flatten] — which every pile edge, card thickness and airborne shadow
     * on this stage is drawn through. `CameraPose`'s KDoc records a refusal of a
     * pan on the grounds that a movable vanishing point needs an off-axis
     * inverse; the vanishing point genuinely does move here, and the inverse
     * genuinely is off axis, and it is still two lines, because moving the
     * *image* is affine and moving the *eye* is not.
     *
     * It does not move [CameraEnvelope.minDistanceAt] either, and that is a
     * result rather than an oversight: shifting the lens does not move the
     * camera, so it cannot bring the table any closer to it. Exactly the
     * cancellation `CameraPose.lens` gets, for exactly the same reason.
     *
     * ## Trailing and defaulted, which is load-bearing
     *
     * At the middle of the glass every byte of every projection is what it was,
     * so this landed in a release ahead of anything that moves it and
     * `GoldenStageTest` never noticed — the same move [targetX],
     * `CameraPose.lens` and `CardSolid.slab`'s trailing `backScale` made.
     */
    val axisX: Float = width / 2f,
    val axisY: Float = height / 2f,
) {
    private val theta = tiltDegrees * (PI.toFloat() / 180f)
    private val sinTilt = sin(theta)
    private val cosTilt = cos(theta)

    private val phi = yawDegrees * (PI.toFloat() / 180f)
    private val sinYaw = sin(phi)
    private val cosYaw = cos(phi)

    /**
     * The middle of the glass, and **only** that.
     *
     * It used to be two things — the middle of the glass and the place the
     * camera's target lands — and the projection read it for the second job.
     * That is [axisX] now. Nothing in the projection reads these any more; they
     * are here for callers that genuinely mean the centre of the surface, and a
     * caller that means "where the camera is pointing" wants [axisX] instead.
     */
    val centreX: Float get() = width / 2f
    val centreY: Float get() = height / 2f

    /**
     * The spin, and its undo. Kept as two private helpers rather than inlined so
     * that the only place the sign of the yaw is decided is here — the layer
     * that has to agree with it says `rotationZ = -yawDegrees` and nothing else
     * in the app needs an opinion.
     */
    private fun spinX(x: Float, y: Float) = x * cosYaw + y * sinYaw
    private fun spinY(x: Float, y: Float) = -x * sinYaw + y * cosYaw

    /** Where a point on the plane lands, raised [z] along the plane's normal. */
    fun project(x: Float, y: Float, z: Float = 0f): Projected {
        // Measured from the point the camera is aimed at, and landed on the
        // optical axis. Both are the middle until somebody pans or shifts, and
        // they are two different middles: `targetX` is a point on the mat,
        // `axisX` is a point on the glass.
        val atX = (x - targetX) * zoom
        val atY = (y - targetY) * zoom
        val up = z * zoom
        val localX = spinX(atX, atY)
        val localY = spinY(atX, atY)
        val depth = localY * sinTilt + up * cosTilt   // toward the viewer
        val flat = localY * cosTilt - up * sinTilt    // down the screen
        val scale = cameraDistance / max(cameraDistance - depth, MIN_GAP)

        return Projected(
            x = axisX + localX * scale,
            y = axisY + flat * scale,
            scale = scale,
            depth = depth,
        )
    }

    fun project(point: Vec3): Projected = project(point.x, point.y, point.z)

    /**
     * How much the screen moves when a point on the mat moves — the projection's
     * derivative, exactly, at [x], [y].
     *
     * ## What it is for
     *
     * Anything drawn *per pixel* on the mat needs to know how big a mat pixel is
     * on the glass, and the answer is not one number: the plane is tilted, so a
     * step across the table and a step away from you cover different distances
     * on screen, and the ratio changes with depth because of the divide. A
     * procedural surface that ignores that is a surface that aliases on its far
     * half and crawls when the camera turns — which is `AAA.md` #60 broken by
     * accident, and it is exactly what the felt's first weave did.
     *
     * Returned as the four partials of `(sx, sy)` against `(x, y)`, which is
     * everything a caller can want: `hypot(dxdx, dydx)` is how far a step in mat
     * x travels on the glass, the determinant is the area ratio (and so the
     * level of detail, `AAA.md` #97), and the two together give the anisotropy.
     *
     * ## Why it is exact rather than fitted
     *
     * [project] at `z = 0` is closed form in the four trigonometric values this
     * class already caches, so the derivative is closed form too. A finite
     * difference would have been three lines shorter and would have needed a
     * step size — one more number nobody could defend, wrong in a different
     * direction at every zoom.
     */
    fun jacobian(x: Float, y: Float): PlaneJacobian {
        val atX = (x - targetX) * zoom
        val atY = (y - targetY) * zoom
        val localX = spinX(atX, atY)
        val localY = spinY(atX, atY)

        // The spin's own derivative, which is constant: a rotation composed
        // with a uniform scale.
        val lxdx = zoom * cosYaw
        val lxdy = zoom * sinYaw
        val lydx = -zoom * sinYaw
        val lydy = zoom * cosYaw

        val depth = localY * sinTilt
        val gap = cameraDistance - depth
        val scale = cameraDistance / max(gap, MIN_GAP)

        // Past the clamp the divide has stopped varying, so the perspective term
        // contributes nothing and the map is locally affine. Saying so here is
        // what stops a point behind the lens producing an infinite derivative.
        val clamped = gap <= MIN_GAP
        val dScaleDx = if (clamped) 0f else cameraDistance / (gap * gap) * sinTilt * lydx
        val dScaleDy = if (clamped) 0f else cameraDistance / (gap * gap) * sinTilt * lydy

        return PlaneJacobian(
            dxdx = lxdx * scale + localX * dScaleDx,
            dxdy = lxdy * scale + localX * dScaleDy,
            dydx = cosTilt * (lydx * scale + localY * dScaleDx),
            dydy = cosTilt * (lydy * scale + localY * dScaleDy),
        )
    }

    /**
     * A screen point back onto the mat, at z = 0.
     *
     * Closed form rather than iterated. Projection gives
     * `screenY = y·cosθ · d/(d − y·sinθ)`, which rearranges to
     * `y = screenY·d / (d·cosθ + screenY·sinθ)`.
     *
     * The freeform drag needs this because the card being dragged lives on the
     * flat overlay while the finger arrives in root coordinates — and it means
     * the screen never has to take Compose's word for the inverse of a layer
     * matrix it cannot inspect.
     *
     * The yaw costs one extra step and no accuracy: the tilt is undone exactly
     * as it always was, in the spun frame, and then the spin itself is undone by
     * turning back the other way. A rigid rotation is the one kind of transform
     * whose inverse is free.
     */
    fun unproject(screenX: Float, screenY: Float): Vec3 = unprojectAt(screenX, screenY, 0f)

    /**
     * A screen point back onto the plane at height [z] — [project]'s full inverse.
     *
     * ## What it answers
     *
     * [unproject] asks where on the *felt* a finger is. This asks where on the
     * plane a hand's height above the felt it is, which is a different point and
     * is the one that matters whenever the thing being pointed at is not lying
     * down: a card in a spread pile, a card in the hand, anything at all with a
     * z. The two agree only at `z = 0`, where this reduces to [unproject] term
     * for term — `StagePlaneTest` pins that, because a second copy of this
     * algebra is a second thing to keep true.
     *
     * ## The algebra
     *
     * With `u = z·zoom` and `A = d − u·cosθ`, [project] gives
     * `flat′ = (y·cosθ − u·sinθ)·d / (A − y·sinθ)`, which rearranges to
     *
     * ```
     * y = (flat′·A + d·u·sinθ) / (d·cosθ + flat′·sinθ)
     * ```
     *
     * — the same shape [unproject] has always had, with the two terms the height
     * contributes kept rather than dropped. Everything after it is unchanged: the
     * scale follows from the depth, x divides by it, and the spin and the zoom
     * come off exactly as before.
     *
     * The denominator vanishes at exactly one screen row — the horizon of this
     * plane, where a ray parallel to it never lands. Guarded rather than solved:
     * a finger there is pointing at infinity and the honest answer is "the point
     * the camera is aimed at", not a number with six digits in it. [above] is how
     * a caller asks *before* getting that answer, and the hit test now does.
     */
    fun unprojectAt(screenX: Float, screenY: Float, z: Float): Vec3 {
        // Measured from the optical axis, which is where [project] put the
        // pivot. This is the whole of the "off-axis inverse" a lens shift was
        // said to cost: one subtraction, of the same two numbers projection
        // added. It stays closed form because a shift moves the *image* and not
        // the *eye* — the divide is still centred on the pivot, the pivot is
        // still drawn at one known place, and only that place has moved.
        val localX = screenX - axisX
        val localY = screenY - axisY
        val up = z * zoom
        val near = cameraDistance - up * cosTilt

        val divisor = cameraDistance * cosTilt + localY * sinTilt
        if (abs(divisor) < MIN_GAP) return Vec3(targetX, targetY, z)

        val onPlane = (localY * near + cameraDistance * up * sinTilt) / divisor
        val scale = cameraDistance / max(near - onPlane * sinTilt, MIN_GAP)
        val spunX = localX / scale
        val safeZoom = if (abs(zoom) < 1e-4f) 1e-4f else zoom

        return Vec3(
            x = targetX + (spunX * cosYaw - onPlane * sinYaw) / safeZoom,
            y = targetY + (spunX * sinYaw + onPlane * cosYaw) / safeZoom,
            z = z,
        )
    }

    /**
     * The screen row where this plane's horizon is, or null if it is not on the
     * glass at all.
     *
     * [unprojectAt]'s divisor is `d·cosθ + localY·sinθ`, so it vanishes at
     * `localY = −d·cosθ / sinθ` — one row, above the middle, and further above it
     * the flatter the table is laid. At a tilt of nothing it is infinitely far up
     * and there is no horizon, which is what a table seen from directly overhead
     * should say.
     *
     * ## Why anything needs it now
     *
     * It has always been there and never mattered, because the camera could not
     * pitch past fifty-eight degrees and at fifty-eight the row sits about four
     * hundred pixels above the top of the screen. Opening the envelope to eighty
     * brings it *onto* the glass — at eighty on a thousand-pixel stage it is a
     * quarter of the way down from the top — and everything above it is sky.
     *
     * A finger up there is pointing at nothing. [unprojectAt] answers with the
     * camera's own target, which is a defensible number and a terrible hit test:
     * every tap on the wall would grab whatever is in the middle of the table. So
     * the hit test asks this first and treats a yes as a miss, which then falls
     * through to `claimForCamera` — pointing at the sky moves the camera, which
     * is what pointing at anything that is not a card already does.
     */
    val horizonY: Float?
        get() {
            if (abs(sinTilt) < 1e-6f) return null
            return axisY - cameraDistance * cosTilt / sinTilt
        }

    /**
     * Whether [screenY] is above this plane's horizon — pointing at no table at all.
     *
     * A hair below the row itself rather than at it, using the same [MIN_GAP] the
     * inverse guards with, so the answer here and the guard there cannot disagree
     * about the one row between them.
     */
    fun above(screenY: Float): Boolean {
        if (sinTilt <= 0f) return false
        val divisor = cameraDistance * cosTilt + (screenY - axisY) * sinTilt
        return divisor < MIN_GAP
    }

    /**
     * [screenY], moved down to the nearest row that is looking at the table.
     *
     * ## What it is for
     *
     * Every finger on this stage arrives as a screen point and is turned into a
     * mat point by [unproject]. Above the horizon there is no mat point, and
     * [unprojectAt] answers with the camera's target — which is a defensible
     * number and precisely the wrong one: a tap on the wall would grab whatever
     * is in the middle of the board.
     *
     * ## Why it clamps rather than refusing
     *
     * A sentinel "nothing here" is right for a press and wrong for a *drag*. A
     * card carried up toward the top of the screen would reach the horizon and
     * teleport, which is a worse bug than the one being fixed. Clamping instead
     * makes both cases fall out: a press in the sky lands tens of thousands of
     * mat pixels up the table, which is off every card and every pile, so the hit
     * test finds nothing and the gesture goes to the camera — pressing on nothing
     * moves the camera, which is what pressing on nothing already did. And a card
     * dragged that way slides to the far edge and stops, which is what dragging
     * something toward the horizon should look like.
     *
     * The row it clamps to is the one where [unprojectAt]'s divisor reaches
     * [MIN_GAP], so this and that guard cannot disagree about where the sky
     * begins — the same reason [above] is written against the divisor rather
     * than against [horizonY].
     */
    fun belowHorizon(screenY: Float): Float {
        if (sinTilt <= 0f) return screenY
        val lowest = axisY + (MIN_GAP - cameraDistance * cosTilt) / sinTilt
        return max(screenY, lowest)
    }

    /**
     * The point at height [z] that *appears* where ([x], [y]) on the felt does —
     * the exact inverse of [flatten].
     *
     * ## Why anything needs it
     *
     * [flatten] is how everything with a height reaches the screen: a card in the
     * air is drawn at the felt point its own corners project to, so the mat's one
     * transform puts it where the height says. That is a one-way street, and the
     * hit test is what falls off the end of it. A finger arrives as a screen
     * point, [unproject] turns it into a felt point, and a felt point compared
     * against a *raised* card's mat coordinates is comparing two different places.
     *
     * It is not a small error. A spread pile floats at about half a card height;
     * at the table seat that is tens of mat pixels, against a fan whose cards may
     * sit a third of a card width apart. The card you got was reliably not the
     * card you pointed at, and `docs/classic/TABLE.md` §4's whole feature was the worse
     * for it.
     *
     * ## What it does and does not promise
     *
     * Exact for anything **flat and at one height** — which is what a fanned card
     * is, and what a card lying on a pile is. A *leaned* card is not: the hand's
     * cards pivot on their bottom edge, so their footprint is a quad at two
     * heights rather than a rectangle at one, and this answers about the height
     * you name. Close, and not the same claim; the hand is deliberately still hit
     * tested on the felt.
     *
     * At `z = 0` it is the identity, which is the property worth having: geometry
     * that touches the mat is pointed at exactly where it was computed.
     */
    fun raise(x: Float, y: Float, z: Float): Vec3 {
        if (z == 0f) return Vec3(x, y, 0f)
        val screen = project(x, y, 0f)
        return unprojectAt(screen.x, screen.y, z)
    }

    /** How much bigger something gets by rising [z] off the mat at the centre. */
    fun liftScale(z: Float): Float =
        cameraDistance / max(cameraDistance - z * zoom * cosTilt, MIN_GAP)

    /**
     * Where the camera actually is, in the mat's own coordinates.
     *
     * [direction] is the way the viewer lies from the table — `StageRig.eye`,
     * which is the one place the tilt and the yaw are turned into a heading, and
     * is passed in rather than recomputed so this file keeps its promise that
     * the sign of the yaw is decided in exactly one place.
     *
     * This is the camera the projection has always had and never said out loud.
     * [project] divides by `cameraDistance − depth`, which is a lens at a
     * *point*; everything that asked where the viewer was got a *direction*
     * instead, and a direction is a camera infinitely far away. The two agree
     * about the middle of the table and disagree by more than twenty degrees at
     * the right-hand column, which is where the deck lives.
     *
     * The zoom divides because it is applied to the mat's coordinates before the
     * spin and the tilt: making the table twice the size, seen from the same
     * lens, is the same picture as leaving it alone and standing half as far
     * back — so in the mat's own frame the camera comes half as far.
     */
    fun eyePoint(direction: Vec3): Vec3 =
        // From the point the camera is aimed at, which is what it is a distance
        // *from*. Panning walks the eye across the room with the target rather
        // than swinging it about a middle it is no longer looking at.
        Vec3(targetX, targetY, 0f) + direction * (cameraDistance / max(zoom, MIN_ZOOM))

    /**
     * Where to draw a point *on the mat* so that it appears to be [z] above it.
     *
     * The mat is one `graphicsLayer`, and everything drawn inside it gets the
     * plane's projection applied on the way out. That is exactly what you want
     * for the felt and for cards lying on it, and exactly what you cannot use
     * for anything with a height: a shadow thrown by a card in the air, the
     * white edge of a pile standing off the table, a card's own thickness. All
     * of those are geometry *at a z*, drawn by a canvas that has no z.
     *
     * So: project the point properly, then ask where on the flat mat that
     * screen position came from. The plane's own transform then undoes the
     * second step and the point lands where the first step put it. Both halves
     * are already here and exact, so this is not an approximation of the
     * projection — it is the projection, run through a canvas that only speaks
     * two dimensions.
     *
     * At z = 0 it is the identity, which is the property worth holding on to:
     * geometry that touches the mat is drawn exactly where it was computed.
     *
     * Position is not the whole answer, so [Flattened.scale] comes with it. The
     * plane will size anything drawn at the flattened point by however much it
     * sizes the *felt* there, and a card sitting on top of a forty-card deck is
     * closer to the camera than the felt under it by rather more than that. For
     * a polygon whose every vertex has been flattened the correction is already
     * baked in and can be ignored; for a whole card drawn at one point it is
     * the difference between a deck and a picture of one.
     */
    fun flatten(x: Float, y: Float, z: Float): Flattened {
        if (z == 0f) return Flattened(x, y, 1f)

        val raised = project(x, y, z)
        val onPlane = unproject(raised.x, raised.y)
        val asFelt = project(onPlane.x, onPlane.y, 0f).scale

        return Flattened(
            x = onPlane.x,
            y = onPlane.y,
            scale = if (asFelt <= MIN_GAP / cameraDistance) 1f else raised.scale / asFelt,
        )
    }

    fun flatten(point: Vec3): Flattened = flatten(point.x, point.y, point.z)

    /**
     * Whether a point is far enough short of the lens to be drawn honestly.
     *
     * [project] divides by `cameraDistance - depth`, and it already refuses to
     * divide by nothing: the gap is clamped at one pixel, so a point level with
     * the lens does not crash, it flies to a few hundred thousand pixels away
     * and takes its whole quad with it. That is worse than a crash in one
     * specific way — it draws.
     *
     * Nothing on this stage reaches it today. The tallest thing in the room is
     * the wall, and at the closest legal pose in [CameraEnvelope] — four degrees
     * of pitch at eight tenths of the distance — its top corner comes back at
     * 1.23 times the camera's own distance, which is past the lens; the quad
     * then lands entirely above the glass and nothing is painted. So this is
     * insurance against the day a piece of room is a little taller or the
     * envelope opens a little wider, and the test that goes with it carries the
     * measurement rather than merely asserting the guard exists.
     *
     * Raising the room's height ceiling is the wrong instrument for the same
     * problem: the danger is a function of the *pose*, and the ceiling is a
     * budget.
     *
     * ## The day the envelope did open wider
     *
     * [SAFE_DEPTH] was a half for as long as [CameraEnvelope]'s own clearance
     * was, and the two being the same number was a coincidence that read as a
     * design. When the clearance became a preference and its default went to
     * 0.68, a *legal* mat corner — one the envelope had just solved a floor to
     * permit — was suddenly past this guard. The mat is not the casualty; it is
     * not drawn through here. The desk is: it reaches further toward the viewer
     * than the mat does by construction, so the one production caller
     * (`SceneRender`, culling room faces) would have quietly stopped painting
     * the table you are sitting at, at exactly the seats the release was widening
     * to reach.
     *
     * So it is nine tenths now, which is what it should always have been: this
     * is the guard against a quad flying to a few hundred thousand pixels, and
     * that is a fact about `MIN_GAP` and the number one, not about taste. Room
     * geometry genuinely close to the lens is now drawn genuinely large, and a
     * camera that has been walked inside the desk is allowed to look like it.
     */
    fun reaches(point: Vec3, clearance: Float = SAFE_DEPTH): Boolean =
        // A plane with no surface behind it yet has a camera distance of zero,
        // which would make this refuse every point on the stage. There is no
        // lens to cross before there is a camera, and the first composition of
        // the play screen builds exactly such a plane.
        cameraDistance <= 0f || project(point).depth < cameraDistance * clearance

    /**
     * What the nearest corner grows to, which is what [BoardLayouter.solve] wants.
     *
     * The projection reporting on itself, so the fitter and the renderer cannot
     * disagree about how much room the tilt costs. Before this it was a
     * constant someone derived in a comment and would eventually have forgotten
     * to change alongside the tilt.
     *
     * Every corner rather than the bottom edge, because once the table can turn
     * there is no such thing as *the* near edge — at forty-five degrees of yaw
     * the nearest thing to the camera is a corner, and a growth measured down
     * the middle would under-report it and let the table overhang. At yaw zero
     * the two answers are identical, which is why this did not need saying
     * before and why changing it moved no existing number.
     */
    val perspectiveGrowth: Float
        get() {
            var worst = 1f
            for (x in 0..1) {
                for (y in 0..1) {
                    val scale = project(x * width, y * height).scale
                    if (scale > worst) worst = scale
                }
            }
            return worst
        }

    /**
     * How deep the stage is, from the middle out, in units of [cameraDistance].
     *
     * The projection reporting on itself again, for the same reason
     * [perspectiveGrowth] does: `Projected.depth` is measured against
     * `cameraDistance` and nothing else on this stage knows how much of that
     * range the *table* actually occupies. Which is fine until something has to
     * be calibrated against it, and then a constant is a guess.
     *
     * `Defocus` was that guess. It put the focus plane anywhere within half the
     * camera distance either way, and the board's own half-span is **0.027
     * overhead, 0.124 at the table seat and 0.209 seated** on a sixteen-by-ten
     * stage — so between three quarters and nineteen twentieths of the focus
     * knob's travel moved a plane that was already past every card on the table,
     * and the dial read as broken because it very nearly was. `docs/classic/LOOP.md`:
     * *a constraint nobody has re-derived is a constraint that has started
     * guessing.*
     *
     * Every corner rather than the near edge, because once the table can turn
     * there is no such thing as *the* far corner — at forty-five degrees of yaw
     * it is a corner rather than an edge, and once it can be aimed the middle of
     * the glass is not the middle of the mat either. At yaw zero and no pan the
     * two answers agree, which is why this reads the corners and does not
     * pretend to be `height · sin(tilt) / 2`.
     */
    val depthReach: Float
        get() {
            if (cameraDistance <= 0f) return 0f
            var worst = 0f
            for (x in 0..1) {
                for (y in 0..1) {
                    val at = abs(project(x * width, y * height).depth) / cameraDistance
                    if (at > worst) worst = at
                }
            }
            return worst
        }

    companion object {
        /**
         * How far the table is laid back from the camera.
         *
         * Raised twice now, from eleven to fifteen and from fifteen to
         * twenty-one, and both times for the same reason. Everything with a
         * height on this stage — a pile's edge, a lifted card's parallax, the
         * gap between a card and its shadow — projects to `z·sin θ`, so the
         * tilt is the exchange rate between every height the renderer computes
         * and the pixels anybody sees. At eleven degrees a forty-card deck
         * stood a pixel and a half proud of the felt; at fifteen it stood two,
         * which is a table you can measure and not one you can sit at.
         *
         * Twenty-one buys another forty per cent of that for about three per
         * cent of card size, and it is still a long way short of the angle where
         * a card's own printed text starts to keystone — the seated seat is at
         * thirty-four and is legible. It is deliberately the *default*, not the
         * limit: the camera can be anywhere between four and fifty-eight
         * degrees, and this is only the angle the table opens at.
         */
        const val TILT = 21f

        /**
         * How much of the way to the lens a point may be and still be drawn.
         *
         * **It must stay above [CameraEnvelope.MAX_CLEARANCE], and that coupling
         * has now failed twice.** The clearance is how far toward the lens the
         * envelope lets the *mat's* corner come; the desk reaches further toward
         * you than the mat does by construction, so if this is not comfortably
         * past the clearance, the one caller — `SceneRender`, culling room faces
         * — stops painting the table you are sitting at, at exactly the seats the
         * envelope has just been widened to reach.
         *
         * It was a half, which was a *style* judgement — "nobody is reading a
         * surface at double size" — sitting in the one place that is supposed to
         * hold nothing but arithmetic, and it happened to equal the clearance,
         * which read as a design. Raising the clearance to 0.68 broke that and it
         * went to nine tenths. Raising the clearance again — to 0.9 by default and
         * 0.99 at the knob's stop, because kai asked for the close limit to stop
         * being a limit — broke it a second time, in precisely the same way and
         * against precisely the same comment.
         *
         * So it is 0.995 now, and `StagePlaneTest.theCullIsAlwaysPastWhereTheEnvelopeCanPutTheTable`
         * is the tripwire rather than this paragraph. What it is really guarding
         * is `MIN_GAP`: at the closest pose the envelope allows, the gap is still
         * several pixels, so the divide has not clamped and a quad drawn here is
         * merely very large rather than nonsense. A camera walked inside the desk
         * is allowed to look like it.
         */
        const val SAFE_DEPTH = 0.995f

        private const val MIN_GAP = 1f

        /** A zoom of nothing is not a camera; keep [eyePoint] out of infinity. */
        private const val MIN_ZOOM = 1e-4f

        /**
         * The lens, solved from the growth wanted at the near edge:
         * `s = d / (d − (h/2)·sinθ)`.
         *
         * Shortened along with the tilt. A camera nearly two screen-heights
         * back is an almost orthographic one, which is a fine choice for a
         * diagram and the wrong one for a table you are sitting at: it is what
         * made a card raised into the hand grow by four per cent and read as
         * having not moved. At `1.45·h` and the default tilt the near edge
         * grows about fourteen per cent, the fitter is told so, and a lifted
         * card is visibly closer than the felt it left.
         *
         * Taking the larger of height and a share of width keeps an ultrawide
         * window from getting a violent keystone at its left and right edges —
         * on a very wide stage the height is no longer the thing setting the
         * field of view.
         */
        fun forStage(width: Float, height: Float, tilt: Float = TILT) = StagePlane(
            width = width,
            height = height,
            tiltDegrees = tilt,
            cameraDistance = 1.45f * max(height, width * 0.55f),
        )
    }
}

/**
 * The projection's derivative at a point: how far the glass moves per unit of mat.
 *
 * Four partials rather than a matrix type, because `:core` has no matrix type and
 * inventing one for a single caller is how a geometry library starts.
 */
data class PlaneJacobian(
    val dxdx: Float,
    val dxdy: Float,
    val dydx: Float,
    val dydy: Float,
) {
    /** How far a step of one mat unit along x travels on the glass. */
    val alongX: Float get() = kotlin.math.sqrt(dxdx * dxdx + dydx * dydx)

    /** The same along y. Different from [alongX] whenever the plane is tilted. */
    val alongY: Float get() = kotlin.math.sqrt(dxdy * dxdy + dydy * dydy)

    /**
     * Screen area per unit of mat area. The level-of-detail number (`AAA.md` #97),
     * and the honest thing to compare a texture's frequency against.
     */
    val areaScale: Float get() = kotlin.math.abs(dxdx * dydy - dxdy * dydx)
}
