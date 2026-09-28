package com.kaiharimoto.mastertool.core.render

import com.kaiharimoto.mastertool.core.motion.Pose3
import com.kaiharimoto.mastertool.core.motion.Vec3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One station of a turned profile: how wide the solid is there, and how high
 * above the pose's own origin that width sits.
 *
 * A profile is a list of these, bottom to top. The numbers are taken as given —
 * a profile handed back to front comes out inside out, with every normal
 * inverted, which is loud enough not to need a guard, exactly as
 * `SceneBox.standing` argues for its own six.
 *
 * [radius] of zero is a point rather than a mistake: it is how a cone gets its
 * apex, and it is handled exactly rather than approximately. See [Turned.solid].
 */
data class Ring(val radius: Float, val height: Float)

/**
 * A solid of revolution — the second shape this app knows how to make.
 *
 * ## Why there needs to be one
 *
 * Until now `core/render` had exactly two mesh constructors: [CardSolid.face],
 * which is one quad, and [CardSolid.slab], which is six. Everything in the room
 * was therefore a box, and everything on the table was a card. That is the
 * right pair of primitives for a card table and it is the wrong pair for the
 * things standing beside one: a lamp's foot is round, its stem is round, its
 * shade is a cone, and the ring a pendant hangs from is a ring. Drawn as boxes
 * they read as what they are, which is placeholders.
 *
 * A lathe is the one machine that makes all four. Give it a silhouette and an
 * axis and it gives back the solid, and a lamp base, a stem, a shade, a finial
 * and a bail are all one function called five times.
 *
 * ## Three things about it that are the design
 *
 * **It is genuinely posed.** [CardSolid.slab] hangs a solid's body straight down
 * the *stage's* z whatever the pose is pointing at, because that is a fact about
 * the felt a card is resting on rather than about the card. Every vertex here
 * goes through [Rot3.place] instead, so this shape may be turned about any axis
 * without shearing. `docs/classic/DESIGN.md` §11 says a tumble "would buy a posed box in
 * core with its own eight corners, and nothing has yet needed one" — this is
 * that box, arriving for a different reason. Nothing tips anything yet, and the
 * handbook clause is the place to argue it when something wants to.
 *
 * **A facet is not the cone it approximates, and the difference is one cosine.**
 * The quad between two rings is planar, and its exact normal is *not* the
 * analytic normal of the cone at the middle of the segment: the radial half of
 * it is short by `cos(π / sides)`, because the two corner angles both sit half a
 * segment off the middle and a chord is shorter than the arc it spans. At twenty
 * sides that is 0.7°, which nobody can see on a lampshade and which is free to
 * get right, so it is got right. Solving for the normal instead of crossing two
 * edges is also what keeps an apex exact — see below.
 *
 * **A cap is one convex polygon, not a fan.** [Face.corners] is an unbounded
 * list and the whole drawing path — [CardSolid.visible], the depth sort, the
 * walk into a `Path` — never asks how many there are. A twenty-sided disc is one
 * face and one fill, where a triangle fan would be twenty of each and would show
 * every seam of the fan as a hairline the moment anything was drawn over it.
 */
object Turned {

    /**
     * How far a segment's middle may fall short of the circle it stands for,
     * in mat pixels.
     *
     * Half a pixel, because that is the point at which the flat stops being
     * something anybody could see rather than something that is arithmetically
     * absent. It is compared against a *sagitta* — the gap at the middle of the
     * chord — rather than against an angle, because how round a thing needs to
     * be is a question about how big it is on screen and not about how many
     * degrees it spans.
     */
    const val SMOOTH = 0.5f

    /**
     * Six because a lamp's stem is seven pixels across and a hexagon is already
     * more circle than that can carry; thirty-two because past it the extra
     * `Path`s cost more than the roundness buys, and because a thirty-two-sided
     * ring at the largest radius anything in this room has is already inside a
     * fifth of a pixel.
     */
    const val MIN_SIDES = 6
    const val MAX_SIDES = 32

    /**
     * How many sides a circle of this radius needs before it stops having any.
     *
     * Derived rather than chosen. A regular polygon of `n` sides inscribed in a
     * circle of radius `r` misses it by `r · (1 − cos(π / n))` at the middle of
     * each chord, so the smallest honest `n` is `π / acos(1 − SMOOTH / r)`.
     * Below the clamp for anything under a pixel wide, above it for nothing this
     * room contains.
     *
     * **Always even, and that is structural rather than tidy.** A polygon with
     * an even number of sides is centrally symmetric — vertex `k` and vertex
     * `k + n/2` are opposite one another through the axis — so its bounding box
     * is centred on the axis it was turned about. With an odd count it is not,
     * by up to `r · (1 − cos(π / n))` on one side only. Everything the room does
     * with a piece is done to its **box**: `ScenePainter` sorts by it,
     * `SceneryTest` measures the lamp's own against the place its light comes
     * from, and a fixture whose box has quietly slid a pixel off its foot is a
     * fixture that is lit from somewhere it is not standing. Rounding up costs
     * at most one side and removes the whole question.
     */
    fun sides(radius: Float): Int {
        if (radius <= SMOOTH) return MIN_SIDES
        val cosine = (1f - SMOOTH / radius).coerceIn(-1f, 1f)
        val angle = acos(cosine)
        if (angle <= 0f) return MAX_SIDES
        val wanted = ceil(PI.toFloat() / angle).toInt()
        val even = wanted + (wanted % 2)
        return even.coerceIn(MIN_SIDES, MAX_SIDES)
    }

    /** The sides a whole profile needs, which is what its widest ring needs. */
    fun sidesFor(profile: List<Ring>): Int =
        sides(profile.maxOfOrNull { it.radius } ?: 0f)

    /**
     * The solid a profile spun about the pose's own vertical makes.
     *
     * Bands between consecutive rings, then a cap at each end unless that end is
     * already a point or the caller says it is joined to something else. Every
     * normal points out of the solid.
     *
     * **A zero-radius ring is an apex and stays exact.** Its band's quads
     * collapse to triangles — the two corners at that end coincide — and the
     * normal does not move a thousandth, because it was never read off the
     * corners in the first place. That is the same property [CardSolid.slab]
     * gets at `backScale = 0` and it is got here for a better reason: the normal
     * is solved from the profile, which knows the shape, rather than crossed
     * from the geometry, which at an apex has one fewer edge to cross.
     */
    fun solid(
        pose: Pose3,
        profile: List<Ring>,
        sides: Int = sidesFor(profile),
        capBottom: Boolean = true,
        capTop: Boolean = true,
        /**
         * Where the first corner goes, in degrees about the axis.
         *
         * Nothing with twenty sides can tell, and a square can tell about
         * nothing else: at four sides a turn is a pyramid, and whether its flats
         * or its edges face the camera is the entire silhouette. Half a segment
         * — 45° on a square — is the difference between the two.
         */
        phase: Float = 0f,
        /**
         * Whether the last ring joins back to the first.
         *
         * A profile that closes on itself is a **tube**, and the one this room
         * needs is a torus: a small circle traced round an axis it does not
         * touch. There are no caps on a closed profile because there is nothing
         * left open to cap.
         *
         * A torus is not convex, so it may not be a `ScenePiece.mesh` — it is
         * only ever drawn as part of something the renderer already treats as
         * one object, and at the size of a pendant's bail its own far side is a
         * pixel behind its near one.
         */
        closed: Boolean = false,
    ): List<Face> {
        if (profile.size < 2 || sides < 3) return emptyList()

        val step = 2f * PI.toFloat() / sides
        val turn = phase * (PI.toFloat() / 180f)
        // The chord factor: how much shorter the straight line between two
        // corners is than the arc they sit on. It is the whole difference
        // between a facet's normal and the cone's, and it is one cosine.
        val chord = cos(PI.toFloat() / sides)
        val cosines = FloatArray(sides) { cos(it * step + turn) }
        val sines = FloatArray(sides) { sin(it * step + turn) }

        fun at(ring: Ring, side: Int): Vec3 = Rot3.place(
            pose,
            Vec3(ring.radius * cosines[side], ring.radius * sines[side], ring.height),
        )

        val faces = ArrayList<Face>(profile.size * sides + 2)

        val bands = if (closed) profile.size else profile.size - 1
        for (band in 0 until bands) {
            val low = profile[band]
            val high = profile[(band + 1) % profile.size]
            val outRadial = high.height - low.height
            val outVertical = -(high.radius - low.radius) * chord
            val span = sqrt(outRadial * outRadial + outVertical * outVertical)
            // A band with no height and no change of width is not a surface.
            if (span <= 0f) continue
            val radial = outRadial / span
            val vertical = outVertical / span

            for (side in 0 until sides) {
                val next = (side + 1) % sides
                // A ring of no width is one point, so the quad is a triangle.
                // Written out rather than filtered for duplicates: two corners
                // computed from `radius * cos` and `radius * sin` at a zero
                // radius are the same *number* and need not be the same *float*
                // — one of them can be a negative zero, which every equality in
                // Kotlin except `==` on a raw Float disagrees about.
                // Round the loop the way the rest of the app winds a face:
                // along the bottom edge, up the far side, back along the top.
                // That is the order whose right-handed vector area points the
                // same way as the normal above it — which is the convention
                // `CardSolid.face` set and never wrote down, and the one thing
                // a texture mapped onto a facet will care about later. Wound
                // the other way it draws identically, fills identically, and
                // mirrors every future decal on it.
                val corners = when {
                    high.radius <= 0f -> listOf(at(low, side), at(low, next), at(high, side))
                    low.radius <= 0f -> listOf(at(low, side), at(high, next), at(high, side))
                    else -> listOf(
                        at(low, side), at(low, next),
                        at(high, next), at(high, side),
                    )
                }
                // The middle of the segment, where the facet's normal points.
                val middle = (side + 0.5f) * step + turn
                // And the cone's own normal at each corner's own angle, which is
                // the same vector on both sides of every seam. `Face.smooth`
                // says what that buys.
                val here = Rot3.rotate(pose, Vec3(radial * cosines[side], radial * sines[side], vertical))
                val there = Rot3.rotate(pose, Vec3(radial * cosines[next], radial * sines[next], vertical))
                faces += Face(
                    corners = corners,
                    normal = Rot3.rotate(
                        pose,
                        Vec3(radial * cos(middle), radial * sin(middle), vertical),
                    ),
                    smooth = when {
                        high.radius <= 0f -> listOf(here, there, here)
                        low.radius <= 0f -> listOf(here, there, here)
                        else -> listOf(here, there, there, here)
                    },
                )
            }
        }

        if (closed) return faces

        val bottom = profile.first()
        if (capBottom && bottom.radius > 0f) {
            // Reversed, so a cap's corners come round the same way its bands do
            // when seen from outside — which nothing downstream reads, and which
            // is worth being true anyway for whoever prints them next.
            faces += Face(
                corners = (0 until sides).map { at(bottom, it) }.reversed(),
                normal = Rot3.rotate(pose, Vec3(0f, 0f, -1f)),
            )
        }

        val top = profile.last()
        if (capTop && top.radius > 0f) {
            faces += Face(
                corners = (0 until sides).map { at(top, it) },
                normal = Rot3.rotate(pose, Rot3.FaceNormal),
            )
        }

        return faces
    }

    /**
     * The faces of one band, for a caller that needs to treat part of a turn as
     * a different material.
     *
     * [solid] returns bands first, in profile order, `sides` faces at a time,
     * and then at most two caps — so a band is a `subList` and this function is
     * the promise that it will go on being one. The inside of a lampshade is the
     * only caller and it needs exactly this: one band of a six-station shell,
     * drawn as a different surface from the five around it.
     *
     * The [sides] handed in must be the [sides] the solid was turned with, which
     * is why anything doing this passes it explicitly at both ends rather than
     * letting the default work it out twice.
     */
    fun band(faces: List<Face>, index: Int, sides: Int): List<Face> {
        val from = index * sides
        if (from < 0 || from + sides > faces.size) return emptyList()
        return faces.subList(from, from + sides)
    }

    /**
     * Every point the profile reaches, before it is posed.
     *
     * Two numbers rather than a box, because the only thing that ever asks is
     * the room's paint order, which wants a `SceneBox` — a type in `core/scene`,
     * which depends on this package rather than the other way round. `SceneBox`
     * has the factory; this has the measurement.
     */
    fun widest(profile: List<Ring>): Float = profile.maxOfOrNull { it.radius } ?: 0f

    fun lowest(profile: List<Ring>): Float = profile.minOfOrNull { it.height } ?: 0f

    fun highest(profile: List<Ring>): Float = profile.maxOfOrNull { it.height } ?: 0f
}
