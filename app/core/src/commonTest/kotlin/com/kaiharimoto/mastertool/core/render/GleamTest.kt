package com.kaiharimoto.mastertool.core.render

import com.kaiharimoto.mastertool.core.motion.Pose3
import com.kaiharimoto.mastertool.core.motion.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three things the room gained when it stopped being Lambert and nothing.
 *
 * A highlight that is not in [StageRig.lit], a fixture that is not evenly
 * bright, and a facet that is shaded as the curve it stands for. Each of them is
 * a *new* term rather than a change to an old one, which is the whole reason
 * `GoldenStageTest` is untouched by all three — and each of them shipped a
 * defect first that a picture caught and no test would have.
 */
class GleamTest {

    private val eyeAt = Vec3(800f, 1400f, 900f)

    /**
     * The gloss the Millennium Puzzle shipped with, kept here after the prop was
     * deleted because the defect it caught is a fact about [StageRig.gleam] and
     * not about the puzzle: a sharpness this tight against a source twenty-two
     * degrees across blows a flat face out to white. Nothing in the room is this
     * sharp any more, which is exactly why the exemplar has to be written down
     * somewhere rather than inferred from whatever is left.
     */
    private val sharpMetal = StageRig.Gloss(sharpness = 36f, strength = 0.62f)

    private fun facingUp(at: Vec3 = Vec3(800f, 400f, 100f), size: Float = 60f) = Face(
        corners = listOf(
            Vec3(at.x - size, at.y - size, at.z),
            Vec3(at.x + size, at.y - size, at.z),
            Vec3(at.x + size, at.y + size, at.z),
            Vec3(at.x - size, at.y + size, at.z),
        ),
        normal = Vec3(0f, 0f, 1f),
    )

    // ---- the highlight -------------------------------------------------------------

    @Test
    fun aMatteSurfaceCostsOneComparisonAndAllocatesNothing() {
        // Every surface in the room except the brass is `Gloss.None`, and the whole
        // of `Scene.MINIMAL` is. A term that returned an object for those would
        // be a per-face allocation on every frame of the stage this app is.
        assertNull(
            StageRig.gleam(facingUp(), eyeAt, StageLighting.Minimal, StageRig.Gloss.None),
        )
    }

    @Test
    fun aFaceTurnedAwayFromTheLampHasNoHighlightOnIt() {
        val away = Face(
            corners = facingUp().corners.reversed(),
            normal = -StageLighting.DeskNight.key.toLight,
        )
        assertNull(
            StageRig.gleam(away, eyeAt, StageLighting.DeskNight, sharpMetal),
        )
    }

    /**
     * The one that shipped wrong, and the arithmetic that fixed it.
     *
     * A highlight is the *source* reflected, so a large source makes a broad
     * weak one and a small source makes a tight bright one — from the same
     * material. Without that, [sharpMetal]'s own sharpness of thirty-six was
     * applied to a window twenty-two degrees across, and a flat top face came
     * out as a **white rectangle**: a large flat surface under a directional
     * lamp has one alignment over the whole of it, so it either all blows out
     * or none of it does.
     */
    @Test
    fun aWindowMakesABroaderWeakerHighlightThanABulb() {
        val face = facingUp()
        val broad = StageLighting.Minimal.copy(
            key = StageLighting.Minimal.key.copy(
                direction = Vec3(0f, 0.35f, -0.94f).normalised(),
                radius = 400f,
                distance = 1000f,
            ),
        )
        val tight = broad.copy(key = broad.key.copy(radius = 20f, distance = 1000f))

        val soft = assertNotNull(StageRig.gleam(face, eyeAt, broad, sharpMetal))
        val hard = assertNotNull(StageRig.gleam(face, eyeAt, tight, sharpMetal))

        val softPeak = soft.stops.maxOf { it.amount }
        val hardPeak = hard.stops.maxOf { it.amount }
        assertTrue(
            hardPeak > softPeak * 2f,
            "a bulb ($hardPeak) is not much brighter than a window ($softPeak)",
        )
    }

    @Test
    fun theHighlightIsAGradientAcrossAFacetAndNotOneNumberForIt() {
        // A facet is not a point. A correct value evaluated at its centre comes
        // out as one flat tone stepping to another at the seam, which on a
        // twenty-sided lamp base is a starburst — the same defect
        // `docs/classic/LOOP.md` iteration 9 found one level up, on the wall.
        val curved = Turned.solid(
            pose = Pose3(position = Vec3(800f, 400f, 0f)),
            profile = listOf(Ring(60f, 0f), Ring(20f, 90f)),
            sides = 20,
        )
        val lit = StageLighting.DeskNight.copy(
            key = StageLighting.DeskNight.key.copy(
                position = Vec3(820f, 380f, 500f),
                radius = 40f,
            ),
        )
        val graded = curved.mapNotNull { face ->
            StageRig.gleam(face, eyeAt, lit, StageRig.Gloss.Brass)
        }
        assertTrue(graded.isNotEmpty(), "nothing on a turned brass solid catches the lamp")
        assertTrue(
            graded.any { it.stops.maxOf { stop -> stop.amount } - it.stops.minOf { stop -> stop.amount } > 1e-3f },
            "every facet came back one flat value, which is the starburst again",
        )
    }

    // ---- the fixture ---------------------------------------------------------------

    @Test
    fun aLitShadeIsBrightestWhereTheBulbIsAndNotAllOver() {
        val standing = Face(
            corners = listOf(
                Vec3(700f, 400f, 100f), Vec3(800f, 400f, 100f),
                Vec3(800f, 400f, 200f), Vec3(700f, 400f, 200f),
            ),
            normal = Vec3(0f, -1f, 0f),
        )
        val wash = assertNotNull(StageRig.spill(standing, Lit(1f, 0.9f)))
        assertTrue(wash.from.z < wash.to.z, "the spill runs down the face rather than up it")
        assertTrue(
            wash.stops.first().amount > wash.stops.last().amount,
            "the top of the shade is not dimmer than the bottom",
        )
    }

    @Test
    fun aFaceWithNoHeightHasNothingToGradeOver() {
        // The rim and the underside of a shade, which are right to be flat.
        assertNull(StageRig.spill(facingUp(), Lit(1f, 0.9f)))
    }

    // ---- the curve -----------------------------------------------------------------

    /**
     * The claim `Face.smooth` exists for: two facets that share an edge agree
     * about the light *on* that edge, so the shading runs across the seam
     * instead of stepping at it.
     */
    @Test
    fun twoFacetsOfOneTurnAgreeAboutTheLightWhereTheyMeet() {
        val sides = 16
        val faces = Turned.solid(
            pose = Pose3(position = Vec3(800f, 400f, 0f)),
            profile = listOf(Ring(70f, 0f), Ring(70f, 120f)),
            sides = sides,
            capBottom = false,
            capTop = false,
        )
        val lit = StageLighting.DeskNight.copy(
            key = StageLighting.DeskNight.key.copy(
                position = Vec3(1100f, 300f, 400f),
                radius = 30f,
            ),
        )

        // What the renderer will actually paint at a point: the gradient's own
        // linear interpolation, read where the two facets touch. Asking whether
        // the *endpoints* match is a weaker and wronger question — a facet may
        // legitimately grade up its own height instead of round the turn, and
        // then neither of its ends is on the shared edge at all.
        fun readAt(wash: FaceWash, at: Vec3): Float {
            val axis = wash.to - wash.from
            val span = axis dot axis
            val along = if (span <= 1e-6f) 0f else ((at - wash.from) dot axis) / span
            val t = along.coerceIn(0f, 1f) * (wash.stops.size - 1)
            val low = t.toInt().coerceAtMost(wash.stops.size - 1)
            val high = (low + 1).coerceAtMost(wash.stops.size - 1)
            val f = t - low
            return wash.stops[low].amount * (1f - f) + wash.stops[high].amount * f
        }

        var compared = 0
        repeat(sides) { side ->
            val here = faces[side]
            val there = faces[(side + 1) % sides]
            val a = assertNotNull(StageRig.wash(here, Vec3.Toward, lit), "no wash on facet $side")
            val b = assertNotNull(StageRig.wash(there, Vec3.Toward, lit), "no wash on facet ${side + 1}")

            // Facet `side`'s corners are low@side, low@next, high@next, high@side,
            // so the edge it shares with its neighbour is the radial one at
            // `next` — corners 1 and 2 here, and corners 0 and 3 over there.
            val seam = (here.corners[1] + here.corners[2]) * 0.5f
            val gap = abs(readAt(a, seam) - readAt(b, seam))
            assertTrue(gap < 2e-3f, "facets $side and ${side + 1} disagree by $gap at their seam")
            compared++
        }
        assertTrue(compared == sides, "nothing was compared")
    }

    @Test
    fun aFlatFaceIsShadedExactlyAsItAlwaysWas() {
        // The inert half, and the reason `GoldenStageTest` did not move: a face
        // with no smooth normals answers `normalAt` with its own, everywhere.
        val flat = facingUp()
        assertTrue(flat.smooth == null)
        flat.corners.indices.forEach { index ->
            assertTrue(flat.normalAt(index) == flat.normal, "corner $index invented a normal")
        }
    }
}
