package com.kaiharimoto.mastertool.core.render

import com.kaiharimoto.mastertool.core.layout.BoardLayout
import com.kaiharimoto.mastertool.core.layout.BoardLayouter
import com.kaiharimoto.mastertool.core.motion.Vec3
import com.kaiharimoto.mastertool.core.scene.Scene
import com.kaiharimoto.mastertool.core.scene.Scenery
import com.kaiharimoto.mastertool.core.scene.TimeOfDay
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * That the room may fall into the dark and the table may not.
 *
 * The whole of this is one seam: `StageRig.room` and `StageRig.face` are the
 * same arithmetic except for what happens to the ambient, and which of the two
 * a surface gets is the difference between a night that reads as night and a
 * card nobody can read.
 */
class RoomLightTest {

    private val surfaceWidth = 1600f
    private val surfaceHeight = 856f
    private val layout: BoardLayout = BoardLayouter.solve(
        width = surfaceWidth,
        height = surfaceHeight,
        aspectRatio = 59f / 86f,
        perspectiveGrowth = 1.2f,
        roomAbove = Scenery.ROOM_ABOVE,
    )

    private fun rig(time: TimeOfDay) = Scenery.lightingFor(Scene.DESK, time, layout)

    private val night = rig(TimeOfDay.NIGHT)
    private val day = rig(TimeOfDay.DAY)
    private val lamp = night.key.position!!

    /** A patch of wall standing at [x], facing into the room. */
    private fun wallAt(x: Float) = Face(
        corners = listOf(
            Vec3(x - 20f, 0f, 200f), Vec3(x + 20f, 0f, 200f),
            Vec3(x + 20f, 0f, 160f), Vec3(x - 20f, 0f, 160f),
        ),
        normal = Vec3(0f, 1f, 0f),
    )

    // ---- a lamp with no place changes nothing ----------------------------------------

    @Test
    fun daylightAndTheMinimalStageAreShadedByExactlyTheArithmeticTheyAlwaysWere() {
        // Not "close to": the same value. Every rig in this app without a placed
        // key has to be untouched by this, `GoldenStageTest`'s included, and an
        // equality with no tolerance is the only claim that says so.
        listOf(day, StageLighting.Minimal).forEach { lighting ->
            listOf(-400f, 200f, 800f, 1500f).forEach { x ->
                val face = wallAt(x)
                assertEquals(
                    StageRig.face(face, Vec3.Toward, lighting),
                    StageRig.room(face, Vec3.Toward, lighting),
                    "a placeless key dimmed the room at x = $x",
                )
            }
        }
        assertEquals(1f, day.key.roomReach(Vec3(0f, 0f, 0f)))
        assertSame(day.key.toLight, day.key.toLightFrom(Vec3(0f, 0f, 0f)))
    }

    // ---- and a lamp with one lets the far corner go ----------------------------------

    @Test
    fun theNightWallIsDimmerTheFurtherItIsFromTheLamp() {
        // The defect this exists for. Before it, the wall came back 67 by day
        // and 68 at night, because the only thing separating the two rooms was
        // an ambient of 0.76 against 0.55 and sRGB flattens that to nine levels.
        val near = StageRig.room(wallAt(lamp.x), Vec3.Toward, night).amount
        val far = StageRig.room(wallAt(lamp.x - 1400f), Vec3.Toward, night).amount

        assertTrue(far < near * 0.7f, "wall fell only from $near to $far")
    }

    @Test
    fun theNightRoomIsDarkWhereTheLampIsNotAndKeepsItsLightWhereItIs() {
        // Two claims that only make sense together, and the first version of
        // this asserted the wrong half. Away from the lamp the night room has to
        // be *much* darker than the day room — that is the defect, and a fall
        // that only shows in the far corner is the one that shipped. But beside
        // the lamp night must keep most of what it had, or the whole thing is a
        // dimmer switch and the room has not fallen away at all.
        //
        // Stated as the *ratio between the two ends* rather than as night
        // out-shining day at one point, which is what it said before. That
        // crossing was a fact about where the lamp stood: it sat a quarter of
        // the way down the mat, so its light met this patch of wall nearly
        // square on. kai's stands an eighth of the way down and much further
        // out, and the same patch now sees it at fifty-five degrees off the
        // normal — so night beside the lamp is 0.76 against day's 0.87 and the
        // old assertion failed while nothing about the seam it guards had
        // changed. What it guards is that night is not a colour grade, and a
        // near end that keeps six times the fraction the far end keeps says so
        // without pinning the lamp to one corner of the desk.
        val away = Vec3.Toward
        val overThere = wallAt(lamp.x - 1400f)
        val beside = wallAt(lamp.x)

        assertTrue(
            StageRig.room(overThere, away, night).amount <
                StageRig.room(overThere, away, day).amount * 0.3f,
            "the far wall at night is ${StageRig.room(overThere, away, night).amount} " +
                "against ${StageRig.room(overThere, away, day).amount} by day",
        )
        val farKeeps = StageRig.room(overThere, away, night).amount /
            StageRig.room(overThere, away, day).amount
        val nearKeeps = StageRig.room(beside, away, night).amount /
            StageRig.room(beside, away, day).amount
        assertTrue(
            nearKeeps > farKeeps * 3f,
            "night dimmed the room uniformly: the near wall kept $nearKeeps and the far $farKeeps",
        )
    }

    @Test
    fun theRoomFallsAwayMonotonicallyRatherThanInSteps() {
        // A wall that dims in bands is a wall with a gradient bug in it.
        val amounts = (0..12).map { step ->
            StageRig.room(wallAt(lamp.x - step * 120f), Vec3.Toward, night).amount
        }

        amounts.zipWithNext { nearer, further ->
            assertTrue(further <= nearer + 1e-5f, "the room brightened with distance: $amounts")
        }
        assertTrue(amounts.first() > amounts.last())
    }

    @Test
    fun nothingInTheRoomEverGoesOut() {
        // The floor is the difference between a dim room and a void, and the
        // void is what `Scenery.ROOM_ABOVE` was spent on removing.
        val milesAway = StageRig.room(wallAt(lamp.x - 40000f), Vec3.Toward, night).amount

        assertTrue(milesAway > 0.05f, "the far wall went out at $milesAway")
        assertTrue(
            milesAway >= night.key.ambient * Light.ROOM_FAR * 0.99f,
            "the far wall fell below the floor at $milesAway",
        )
        // The floor is on the ambient, so what a surface actually keeps is that
        // plus whatever of the three lamps still reaches it — never less.
        assertTrue(milesAway < 0.25f, "the far wall is still lit at $milesAway")
    }

    @Test
    fun bounceFallsOffMoreGentlyThanTheBeamItCameFrom() {
        // The square root, stated as a claim rather than as a line of code: at
        // the distance where the direct beam has lost nine tenths, the bounce
        // has lost about two thirds. Scattered light that fell like a beam would
        // give a wall that is black a card's width from the lamp.
        val at = generateSequence(0f) { it + 20f }
            .first { night.key.attenuation(Vec3(lamp.x - it, 0f, 180f)) < 0.1f }
        val point = Vec3(lamp.x - at, 0f, 180f)

        val beam = night.key.attenuation(point)
        val bounce = night.key.roomReach(point)
        assertTrue(bounce > beam * 2f, "bounce $bounce against beam $beam")
        assertTrue(bounce < 1f)
    }

    // ---- across a face, not just at its middle ---------------------------------------

    @Test
    fun aWallWideEnoughToNeedAGradientGetsOne() {
        // The half of this that is actually visible. `room` shades a face once,
        // at its centre, and the wall behind the desk is fifteen hundred pixels
        // wide — so the correct arithmetic came out as one flat grey stepping to
        // another at a seam, and the night room looked exactly as it had.
        val wall = Scenery.of(Scene.DESK, TimeOfDay.NIGHT, layout, surfaceWidth, surfaceHeight)
            .pieces.first { it.name == "wall right" }
        val front = wall.box.faces().first { it.normal.y > 0.99f }

        val wash = StageRig.wash(front, Vec3.Toward, night, samples = 9)
        assertTrue(wash != null, "the widest piece in the room is shaded flat")
        val ends = wash!!.stops.map { it.amount }
        assertTrue(
            ends.max() - ends.min() > 1e-3f,
            "the wash runs from ${ends.first()} to ${ends.last()}",
        )
        // And it runs the way the room does: this is the wall the lamp stands
        // in front of, so it is brightest opposite the lamp and falls away in
        // both directions. It used to be asserted monotone, which was true of a
        // wall that stopped 1.12 desk-widths out and stopped short of the pool;
        // the room stands on the floor's own edge now, the wall runs past the
        // lamp, and a pool with an end on it was the artefact.
        val lamp = night.key.position!!
        val peak = ends.indexOf(ends.max())
        val at = wash.from + (wash.to - wash.from) * (peak / (ends.size - 1f))
        assertTrue(
            abs(at.x - lamp.x) < layout.cardWidth,
            "the pool is at ${at.x} and the lamp at ${lamp.x}",
        )
        assertTrue(peak > 0 && peak < ends.lastIndex, "the pool is at an end of the wall: $ends")
    }

    @Test
    fun aWallWithNoLampInFrontOfItFallsOffAllTheWayAcross() {
        // The other half of the claim above, and what keeps it from being
        // satisfied by noise: the wall on the far side of the room has the lamp
        // off its end, so there is no peak to find and the grade runs one way.
        val wall = Scenery.of(Scene.DESK, TimeOfDay.NIGHT, layout, surfaceWidth, surfaceHeight)
            .pieces.first { it.name == "wall left" }
        val front = wall.box.faces().first { it.normal.y > 0.99f }

        val ends = StageRig.wash(front, Vec3.Toward, night, samples = 9)!!.stops.map { it.amount }
        val rising = ends.last() > ends.first()
        ends.zipWithNext { a, b ->
            assertTrue(
                if (rising) b >= a - 1e-5f else b <= a + 1e-5f,
                "the wash is not monotone: $ends",
            )
        }
    }

    @Test
    fun theWashPicksTheAxisTheLightActuallyRunsAlong() {
        // A quad has two, and which one depends on where the lamp is standing.
        // A wall beside a lamp varies across; the same wall under one varies up.
        val wall = Scenery.of(Scene.DESK, TimeOfDay.NIGHT, layout, surfaceWidth, surfaceHeight)
            .pieces.first { it.name == "wall right" }
        val front = wall.box.faces().first { it.normal.y > 0.99f }
        val wash = StageRig.wash(front, Vec3.Toward, night)!!

        // The two ends are on the face, and on opposite sides of its middle.
        val middle = (wash.from + wash.to) * 0.5f
        assertTrue((middle - front.centre).length < 1f, "the wash is not centred on the face")
        assertTrue((wash.to - wash.from).length > 1f, "the wash has no length")
    }

    @Test
    fun daylightAllocatesNoGradientAtAll() {
        // Null is the common case and is the point: with no placed lamp every
        // sample is identical, so the caller keeps its solid fill and every
        // daylit and minimal surface stays exactly as it was.
        listOf(day, StageLighting.Minimal).forEach { lighting ->
            Scenery.of(Scene.DESK, TimeOfDay.DAY, layout, surfaceWidth, surfaceHeight)
                .pieces.forEach { piece ->
                    piece.box.faces().forEach { face ->
                        assertTrue(
                            StageRig.wash(face, Vec3.Toward, lighting) == null,
                            "${piece.name} asked for a gradient under a placeless lamp",
                        )
                    }
                }
        }
    }

    @Test
    fun aFaceTooSmallToShowOneIsLeftAlone() {
        // A brush costs an allocation every frame, and below the banding
        // threshold it buys a gradient nobody can see. The same face, twice: a
        // hand's width of wall varies enough to draw and a fingernail does not.
        //
        // Measured against a *synthetic* pair rather than against a piece of the
        // room, because the first version of this asked it of a glazing bar on
        // the assumption that a bar is small — a bar is eight pixels thick and a
        // hundred and seventy long, and over a hundred and seventy the light
        // genuinely does vary. Square on both axes for the same reason: the
        // wash tries both, and a sliver forty pixels tall is not small.
        fun wallOf(side: Float) = Face(
            corners = listOf(
                Vec3(lamp.x - side, 0f, 200f), Vec3(lamp.x, 0f, 200f),
                Vec3(lamp.x, 0f, 200f - side), Vec3(lamp.x - side, 0f, 200f - side),
            ),
            normal = Vec3(0f, 1f, 0f),
        )

        assertTrue(StageRig.wash(wallOf(6f), Vec3.Toward, night) == null, "a sliver asked for one")
        assertTrue(StageRig.wash(wallOf(900f), Vec3.Toward, night) != null, "a wall was left flat")
    }

    // ---- and the table keeps its floor -----------------------------------------------

    @Test
    fun theTableIsNotDimmedWithTheRoom() {
        // The seam. A card's own art is shaded by one black overlay whose error
        // grows as the light falls, which is why `NIGHT_FLOOR` exists — so the
        // path cards are shaded through must not have moved.
        val onTheFarSide = Face(
            corners = listOf(
                Vec3(100f, 300f, 0f), Vec3(200f, 300f, 0f),
                Vec3(200f, 400f, 0f), Vec3(100f, 400f, 0f),
            ),
            normal = Vec3(0f, 0f, 1f),
        )

        assertTrue(
            StageRig.face(onTheFarSide, Vec3.Toward, night).amount >= StageLighting.NIGHT_FLOOR,
            "the table fell below the night floor",
        )
        assertTrue(
            StageRig.room(onTheFarSide, Vec3.Toward, night).amount <
                StageRig.face(onTheFarSide, Vec3.Toward, night).amount,
            "the room is not dimmer than the table at the same point",
        )
    }

    // ---- what a shadow actually removes ---------------------------------------

    @Test
    fun aFullUmbraRemovesAboutAFifthOfTheLightAndNotTwoThirds() {
        // The number `Shadows.DARKEST` was guessing at, computed.
        //
        // It was 0.66, chosen, and a card's shadow was that black whatever the
        // room was doing — while `Light.ambient` is 0.72 in Minimal and is
        // unoccluded everywhere, because nothing on this stage has ever cast a
        // shadow in the ambient. So three quarters of the light on the felt was
        // still physically arriving inside the shadow, and darkening to 0.66
        // against that is a hole punched through a lit surface rather than a
        // shadow on one. It is most of why cards read as stickers.
        val up = Vec3(0f, 0f, 1f)

        val removed = StageRig.occlusion(up, Vec3.Toward)

        assertTrue(
            removed in 0.15f..0.30f,
            "a full umbra on the mat should remove about a fifth of the light, was $removed",
        )
    }

    @Test
    fun aShadowIsTheSurfaceUnderEverythingExceptTheOneLampItBlocks() {
        // The definitional claim, and the one that keeps this from drifting
        // back into being a colour: shadowed plus what the key sends directly
        // is the whole of lit, exactly.
        val up = Vec3(0f, 0f, 1f)

        listOf(StageLighting.Minimal, StageLighting.DeskDay, StageLighting.DeskNight).forEach { rig ->
            val full = StageRig.lit(up, Vec3.Toward, rig).amount
            val dark = StageRig.shadowed(up, Vec3.Toward, rig).amount

            assertTrue(dark < full, "a shadow should be darker than what surrounds it, in $rig")
            assertTrue(dark > 0f, "and it should not be black: $rig gave $dark")
            assertTrue(
                kotlin.math.abs(
                    StageRig.occlusion(up, Vec3.Toward, rig) - (full - dark) / full,
                ) < 1e-5f,
                "occlusion is the share of the light the key was sending, in $rig",
            )
        }
    }

    @Test
    fun aShadowIsCoolerThanWhatSurroundsItRatherThanJustDarker() {
        // `docs/classic/AAA.md` #18. With the key's direct term gone its warmth goes
        // with it, so what is left is the bounce and the player's rim — which
        // is the physics rather than a colour anybody chose.
        val up = Vec3(0f, 0f, 1f)

        val full = StageRig.lit(up, Vec3.Toward)
        val dark = StageRig.shadowed(up, Vec3.Toward)

        assertTrue(
            dark.warmth < full.warmth,
            "a shadow should lose the key's warmth: ${dark.warmth} against ${full.warmth}",
        )
    }
}
