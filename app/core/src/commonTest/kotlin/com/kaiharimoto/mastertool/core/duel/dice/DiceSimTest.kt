package com.kaiharimoto.mastertool.core.duel.dice

import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The opening roll's dice (1.0.87): real physics, deterministic, at rest flat and apart, showing the stamped values. */
class DiceSimTest {

    private val throws: List<DiceThrow> = (0 until 120).map { DiceThrow.random(Random(it * 31 + 7)) } +
        // Drags: slow drops, hard flicks sideways, a fling straight into a corner, a throw let go against a wall.
        listOf(
            DiceThrow.fromDrag(V3(10.0, 7.0), listOf(Quat.IDENTITY, Quat.IDENTITY), V3(0.0, 0.0), 0.0),
            DiceThrow.fromDrag(V3(3.0, 6.0), listOf(Quat.IDENTITY, Quat.IDENTITY), V3(60.0, -10.0), 3.0),
            DiceThrow.fromDrag(V3(17.0, 2.0), listOf(Quat.IDENTITY, Quat.IDENTITY), V3(40.0, -40.0), -9.0),
            DiceThrow.fromDrag(V3(0.0, 4.0), listOf(Quat.IDENTITY, Quat.IDENTITY), V3(-30.0, 0.0), 1.0),
            DiceThrow.fromDrag(V3(10.0, 20.0), listOf(Quat.IDENTITY, Quat.IDENTITY), V3(2.0, -25.0), 0.5),
        )

    private val runs by lazy { throws.map { DiceSim.run(it) } }

    @Test
    fun theSameThrowGivesTheSameFrames() {
        throws.take(10).forEach { t -> assertEquals(DiceSim.run(t), DiceSim.run(t)) }
    }

    @Test
    fun aThrowReadBackFromTheLogIsTheSameThrow() {
        val json = Json { encodeDefaults = false }
        throws.take(10).forEach { t ->
            val back = json.decodeFromString(DiceThrow.serializer(), json.encodeToString(DiceThrow.serializer(), t))
            assertEquals(t, back)
            assertEquals(DiceSim.run(t).rest, DiceSim.run(back).rest)
        }
    }

    @Test
    fun theDiceComeToRestFlatWithinTheTimeCap() {
        val settled = runs.count { it.settled }
        assertTrue(settled >= runs.size * 95 / 100, "Only $settled of ${runs.size} came to rest by themselves")
        runs.forEach { r ->
            assertTrue(r.duration <= DiceSim.MAX_TIME + 0.5, "Ran ${r.duration}s")
            r.rest.forEach { p ->
                assertTrue(DieFaces.tilt(p.q) < 1e-9, "Not flat: ${DieFaces.tilt(p.q)}")
                assertEquals(0.5, p.p.z, 1e-12)
            }
        }
        val mean = runs.sumOf { it.duration } / runs.size
        assertTrue(mean in 0.6..3.5, "A throw takes ${mean}s on average")
    }

    @Test
    fun theyNeverFallThroughTheTableOrLeaveTheField() {
        val corners = buildList { for (x in listOf(-0.5, 0.5)) for (y in listOf(-0.5, 0.5)) for (z in listOf(-0.5, 0.5)) add(V3(x, y, z)) }
        val tol = 0.08
        runs.forEachIndexed { i, r ->
            r.frames.forEach { f ->
                f.dice.forEach { d ->
                    corners.forEach { c ->
                        val at = d.p + d.q.rotate(c)
                        assertTrue(at.z >= -tol, "Throw $i: a corner at z ${at.z} at ${f.t}s")
                        assertTrue(at.x >= -tol && at.x <= DiceSim.ARENA_W + tol, "Throw $i: a corner at x ${at.x}")
                        assertTrue(at.y >= -tol && at.y <= DiceSim.ARENA_D + tol, "Throw $i: a corner at y ${at.y}")
                    }
                }
            }
        }
    }

    @Test
    fun theTwoDiceNeverPassThroughEachOther() {
        runs.forEachIndexed { i, r ->
            r.frames.forEach { f ->
                val d = (f.dice[0].p - f.dice[1].p).length
                assertTrue(d >= 0.9, "Throw $i: centres ${d} apart at ${f.t}s")
            }
            assertNull(DiceSim.overlap(r.rest[0], r.rest[1]), "Throw $i: the dice overlap at rest")
        }
    }

    @Test
    fun thereAreTwentyFourProperDice() {
        val proper = DieFaces.PROPER
        assertEquals(24, proper.size)
        assertEquals(24, proper.toSet().size)
        fun hand(l: List<Int>): Double {
            val e = (1..3).map { v -> DieFaces.NORMALS[l.indexOf(v)] }
            return e[0] dot (e[1] cross e[2])
        }
        val handedness = hand(DieFaces.STANDARD)
        proper.forEach { l ->
            assertEquals((1..6).toList(), l.sorted())
            (0 until 6).forEach { f -> assertEquals(7, l[f] + l[DieFaces.opposite(f)]) }
            assertEquals(handedness, hand(l))
        }
        // A mirror image is not a proper die.
        val mirrored = DieFaces.STANDARD.toMutableList().also { it[DieFaces.PX] = 5; it[DieFaces.NX] = 2 }
        assertTrue(!DieFaces.proper(mirrored))
    }

    @Test
    fun relabellingPutsTheStampedValueOnTopOfAProperDie() {
        for (up in 0 until 6) for (v in 1..6) for (variety in 0 until 4) {
            val l = DieFaces.relabel(up, v, seen = setOf(DieFaces.PZ, DieFaces.NY), variety = variety)
            assertEquals(v, l[up])
            assertTrue(DieFaces.proper(l))
        }
        // Every run, every value: the face physics left on top reads the value.
        runs.take(30).forEach { r ->
            for (v in 1..6) r.rest.forEachIndexed { d, pose ->
                val l = DieFaces.relabel(r.up[d], v)
                assertEquals(v, l[DieFaces.upFace(pose.q)])
            }
        }
    }

    @Test
    fun relabellingKeepsWhatWasInView() {
        // The value already on the face that ends up on top, with nothing else asked: the die is left as it was.
        assertEquals(DieFaces.STANDARD, DieFaces.relabel(DieFaces.PZ, 1, DieFaces.STANDARD, setOf(DieFaces.PZ, DieFaces.NY, DieFaces.PX)))
        val kept = DieFaces.relabel(DieFaces.PX, 4, DieFaces.STANDARD, setOf(DieFaces.PZ))
        assertEquals(4, kept[DieFaces.PX])
        assertTrue(abs(kept[DieFaces.PZ] - DieFaces.STANDARD[DieFaces.PZ]) == 0 || DieFaces.PROPER.none { it[DieFaces.PX] == 4 && it[DieFaces.PZ] == 1 })
    }

    @Test
    fun theUpFaceIsReadOffTheTurn() {
        assertEquals(DieFaces.PZ, DieFaces.upFace(Quat.IDENTITY))
        // Half a turn about x: the bottom face is on top.
        assertEquals(DieFaces.NZ, DieFaces.upFace(Quat(0.0, 1.0, 0.0, 0.0)))
        val quarter = Quat(kotlin.math.sqrt(0.5), kotlin.math.sqrt(0.5), 0.0, 0.0)
        // A quarter turn about x takes +y up.
        assertEquals(DieFaces.PY, DieFaces.upFace(quarter))
    }
}
