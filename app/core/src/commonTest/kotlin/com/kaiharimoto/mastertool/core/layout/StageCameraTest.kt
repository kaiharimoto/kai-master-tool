package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.motion.SpringSpec
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The camera, and the two properties every other thing on the stage rests on.
 *
 * A projection that cannot be inverted exactly takes the pile edges, the card
 * thickness and every airborne shadow with it, because `flatten` is the
 * projection composed with its own inverse. So the round-trip and the seam at
 * z = 0 are checked over a grid of poses rather than at one — the old tests
 * checked them at the only pose there was, which is no longer a reassuring
 * thing to have checked.
 */
class StageCameraTest {

    private val width = 1600f
    private val height = 1000f

    /**
     * Poses worth caring about: the seats, the corners of the envelope, and the
     * yaws where a different edge of the table is nearest the camera.
     */
    private val envelope = CameraEnvelope()

    private val poses: List<CameraPose> = buildList {
        StageSeat.entries.forEach { add(it.pose) }
        listOf(0f, 17f, 45f, 90f, 137f, 180f, 233f, 315f, -62f).forEach { yaw ->
            add(CameraPose(yaw, 15f, 1.45f))
            add(CameraPose(yaw, 4f, 2.6f))
            // Through the envelope, because a pose the rig would refuse is not a
            // pose the renderer will ever be handed — and asking the projection
            // to invert one is asking it about a table behind the camera.
            add(envelope.clamp(CameraPose(yaw, 58f, 0.5f), width, height))
        }
    }

    private fun plane(pose: CameraPose) = pose.planeFor(width, height)

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f, note: String = "") {
        assertTrue(abs(expected - actual) < tolerance, "$note expected $expected, was $actual")
    }

    // ---- the round trip, at every angle ----------------------------------------

    @Test
    fun aPointOnTheMatComesBackToItselfFromAnywhereTheCameraCanBe() {
        val points = listOf(
            0f to 0f,
            800f to 500f,
            1600f to 1000f,
            120f to 880f,
            1480f to 90f,
        )
        poses.forEach { pose ->
            val stage = plane(pose)
            points.forEach { (x, y) ->
                val screen = stage.project(x, y)
                val back = stage.unproject(screen.x, screen.y)
                assertClose(x, back.x, 0.2f, "x at ($x, $y) from $pose:")
                assertClose(y, back.y, 0.2f, "y at ($x, $y) from $pose:")
            }
        }
    }

    @Test
    fun theCentreOfTheMatIsStillTheOnePointThatDoesNotMove() {
        // Whatever the table is turned to, it turns about its own middle — and
        // that middle is drawn on the optical axis.
        //
        // This used to say `centreX`, and it was right for as long as those were
        // the same number. They are two now: `targetX` is the mat point the spin,
        // the tilt and the divide all turn about, and `axisX` is where that lands
        // on the glass. A seat with a lens shift separates them, and a test that
        // still asked about the middle of the *surface* would be asserting that
        // nobody may aim the lens.
        poses.forEach { pose ->
            val stage = plane(pose)
            val middle = stage.project(stage.targetX, stage.targetY)
            assertClose(stage.axisX, middle.x, note = "x at $pose:")
            assertClose(stage.axisY, middle.y, note = "y at $pose:")
            assertClose(1f, middle.scale, note = "scale at $pose:")
        }
    }

    @Test
    fun somethingTouchingTheMatIsDrawnWhereItWasComputedAtEveryAngle() {
        // The seam. If flatten stops being the identity at z = 0, every card
        // jumps the moment it is picked up — at some angles and not others,
        // which is the worst way for it to be wrong.
        poses.forEach { pose ->
            val stage = plane(pose)
            listOf(0f to 0f, 800f to 500f, 1600f to 1000f, 300f to 900f).forEach { (x, y) ->
                val flat = stage.flatten(x, y, 0f)
                assertClose(x, flat.x, note = "x at ($x, $y) from $pose:")
                assertClose(y, flat.y, note = "y at ($x, $y) from $pose:")
                assertClose(1f, flat.scale, note = "scale at ($x, $y) from $pose:")
            }
        }
    }

    @Test
    fun aFlattenedPointLandsWhereTheRealOneWouldAtEveryAngle() {
        poses.forEach { pose ->
            val stage = plane(pose)
            listOf(Triple(800f, 500f, 60f), Triple(300f, 900f, 25f), Triple(1400f, 120f, 140f))
                .forEach { (x, y, z) ->
                    val wanted = stage.project(x, y, z)
                    val drawn = stage.flatten(x, y, z)
                    val landed = stage.project(drawn.x, drawn.y, 0f)
                    assertClose(wanted.x, landed.x, 0.3f, "x for ($x, $y, $z) from $pose:")
                    assertClose(wanted.y, landed.y, 0.3f, "y for ($x, $y, $z) from $pose:")
                }
        }
    }

    // ---- what turning the table does --------------------------------------------

    @Test
    fun turningTheTableBringsADifferentEdgeForward() {
        // Ninety degrees round, the left edge is where the near edge was.
        val square = plane(CameraPose(yawDegrees = 0f))
        val quarter = plane(CameraPose(yawDegrees = 90f))

        val nearEdge = square.project(width / 2f, height)
        assertTrue(nearEdge.depth > 0f, "the bottom edge should be nearest square on")

        val sameEdgeTurned = quarter.project(width / 2f, height)
        assertTrue(
            sameEdgeTurned.depth < nearEdge.depth,
            "turning should take that edge away, was ${sameEdgeTurned.depth}",
        )
        assertTrue(
            quarter.project(0f, height / 2f).depth > 0f,
            "and bring a side edge forward instead",
        )
    }

    @Test
    fun halfATurnPutsYouOnTheOtherSideOfTheTable() {
        val across = plane(CameraPose(yawDegrees = 180f))
        val far = across.project(width / 2f, 0f)
        val near = across.project(width / 2f, height)

        assertTrue(far.scale > near.scale, "the far edge should now be the near one")
    }

    @Test
    fun aTableSquareOnIsTheTableWeAlreadyHad() {
        // The whole change has to be invisible at the pose everything was tuned
        // at, or every constant on the stage quietly moved.
        val before = StagePlane.forStage(width, height)
        val now = plane(CameraPose(0f, StagePlane.TILT, CameraPose.HOME_DISTANCE))

        assertClose(before.cameraDistance, now.cameraDistance, 0.5f)
        assertClose(before.perspectiveGrowth, now.perspectiveGrowth, 1e-4f)
        listOf(0f to 0f, 800f to 500f, 1600f to 1000f).forEach { (x, y) ->
            assertClose(before.project(x, y).x, now.project(x, y).x, 0.01f, "x at ($x, $y):")
            assertClose(before.project(x, y).y, now.project(x, y).y, 0.01f, "y at ($x, $y):")
        }
    }

    @Test
    fun theGrowthIsMeasuredAtWhicheverCornerIsNearest() {
        // Down the middle is the right answer only while the table is square on.
        // Turned, the nearest thing to the camera is a corner, and a growth that
        // missed it would let the board overhang the glass.
        val turned = plane(CameraPose(yawDegrees = 45f, pitchDegrees = 30f))
        val downTheMiddle = turned.project(turned.centreX, height).scale

        assertTrue(
            turned.perspectiveGrowth >= downTheMiddle,
            "${turned.perspectiveGrowth} should be at least $downTheMiddle",
        )
        assertTrue(turned.perspectiveGrowth > 1f)
    }

    // ---- the envelope ---------------------------------------------------------------

    @Test
    fun theCameraCannotGetUnderTheTableOrFlattenItCompletely() {
        assertEquals(envelope.minPitch, envelope.clamp(CameraPose(pitchDegrees = -40f)).pitchDegrees)
        assertEquals(envelope.maxPitch, envelope.clamp(CameraPose(pitchDegrees = 91f)).pitchDegrees)
        // Half the flat floor rather than a fixed 0.1, which used to be under it
        // and is now well above: the floor is a twentieth, and a test that reads
        // a literal has to be edited every time an end moves.
        assertEquals(
            envelope.minDistance,
            envelope.clamp(CameraPose(distance = envelope.minDistance / 2f)).distance,
        )
        assertEquals(envelope.maxDistance, envelope.clamp(CameraPose(distance = 99f)).distance)
    }

    @Test
    fun aTableTurnsAllTheWayRound() {
        // Yaw is the one axis with no limit, because there is no angle you
        // cannot look at a table from.
        listOf(-720f, -37f, 0f, 199f, 1080f).forEach {
            assertEquals(it, envelope.clamp(CameraPose(yawDegrees = it)).yawDegrees)
        }
    }

    @Test
    fun everySeatIsInsideTheEnvelope() {
        StageSeat.entries.forEach {
            assertEquals(
                it.pose,
                envelope.clamp(it.pose, width, height),
                "$it is not a legal place to sit",
            )
        }
    }

    // ---- how close you may sit, which is now a preference -----------------------------

    /**
     * The measurement behind kai's two reports, kept so the next one has a baseline.
     *
     * The first was *"I can't lower the distance below one point four, and
     * visually it is stuck there."* The panel's slider went down to 0.8 the whole
     * time; the *camera* was being floored, and at the low seat you would sit in
     * to look for distortion the floor was exactly where it was reported to be —
     * 1.37, at the clearance that shipped then.
     *
     * The second was *"there shouldn't be a limit I want complete freedom and
     * control."* Two things moved for it. The floor is **linear** now rather than
     * a square root, because the square root was the camera's own distance
     * appearing on the focal length as well as on the magnification — so the same
     * clearance that used to floor at 1.37 floors at 1.29. And the default
     * clearance went to 0.9, which is what the middle row measures.
     *
     * The surface is a Tab S11-shaped stage rather than this file's 1600×1000,
     * because the number in the first report came off that screen and a floor is
     * a function of the screen it is solved on.
     */
    @Test
    fun theFloorAtALowSeatIsWhereTheReportSaidItWas() {
        val tablet = 2800f to 1607f
        val asItShipped = CameraEnvelope(clearance = 0.5f)

        assertClose(
            1.29f,
            asItShipped.minDistanceAt(40f, tablet.first, tablet.second),
            0.01f,
            "the clearance that was reported as stuck at 1.4:",
        )
        assertClose(
            0.72f,
            CameraEnvelope().minDistanceAt(40f, tablet.first, tablet.second),
            0.01f,
            "and where the shipped clearance now puts it:",
        )
        assertClose(
            0.65f,
            CameraEnvelope(clearance = CameraEnvelope.MAX_CLEARANCE)
                .minDistanceAt(40f, tablet.first, tablet.second),
            0.01f,
            "and how close the knob's own far end reaches:",
        )
    }

    /**
     * The camera can come closer than it could, everywhere anybody sits.
     *
     * The claim kai is owed, and it needs stating precisely rather than broadly.
     * The old floor was `sqrt(HOME_DISTANCE · new)`, and a square root pulls a
     * number *toward one* from either side — so the two forms cross at exactly
     * [CameraPose.HOME_DISTANCE]. Below a floor of 1.45 the new one is lower,
     * which is the whole range a person can reach; above it the new one is
     * higher, and that only happens at a low clearance and a steep pitch, where
     * you were already most of a stage-height back and going further.
     *
     * So this asserts the real shape: strictly closer wherever the floor is
     * inside the seat everything was tuned at, and — separately, so the first
     * half cannot go quietly vacuous — that at the shipped clearance every legal
     * angle *is* inside it.
     */
    @Test
    fun theCameraCanComeCloserThanItCouldEverywhereAnybodySits() {
        listOf(0.5f, 0.68f, 0.9f, CameraEnvelope.MAX_CLEARANCE).forEach { clearance ->
            val at = CameraEnvelope(clearance = clearance)
            var pitch = 0f
            while (pitch <= at.maxPitch) {
                val now = at.minDistanceAt(pitch, width, height)
                // The floor as the square-root form solved it, from the very same
                // reach, so the two differ by the projection and nothing else.
                val before = sqrt(now * CameraPose.HOME_DISTANCE)
                if (now <= CameraPose.HOME_DISTANCE) {
                    assertTrue(
                        now <= before + 1e-4f,
                        "at $pitch degrees and clearance $clearance the floor went from $before to $now",
                    )
                }
                pitch += 2f
            }
        }
    }

    /**
     * The table in `docs/classic/TUNING.md`, cell for cell.
     *
     * A table of numbers in prose with nothing under it is worth what the last
     * one was: the lamp's height read "five to one" for two releases and
     * measured 2.92. This is the one somebody will read to decide whether the
     * close limit is worth moving, so it is the one most worth being true.
     */
    @Test
    fun theWholeFloorTableIsWhatTheDocumentSaysItIs() {
        val tablet = 2800f to 1607f
        //          pitch   at 0.5   at 0.9   at 0.99
        val rows = listOf(
            listOf(21f, 0.72f, 0.40f, 0.36f),
            listOf(34f, 1.12f, 0.62f, 0.57f),
            listOf(40f, 1.29f, 0.72f, 0.65f),
            listOf(80f, 1.98f, 1.10f, 1.00f),
        )
        rows.forEach { row ->
            listOf(0.5f, 0.9f, CameraEnvelope.MAX_CLEARANCE).forEachIndexed { column, clearance ->
                assertClose(
                    row[column + 1],
                    CameraEnvelope(clearance = clearance)
                        .minDistanceAt(row[0], tablet.first, tablet.second),
                    0.006f,
                    "at ${row[0]} degrees and clearance $clearance:",
                )
            }
        }
    }

    @Test
    fun atTheShippedClearanceEveryAngleIsInsideTheSeatEverythingWasTunedAt() {
        var pitch = 0f
        while (pitch <= envelope.maxPitch) {
            assertTrue(
                envelope.minDistanceAt(pitch, width, height) <= CameraPose.HOME_DISTANCE,
                "at $pitch degrees the floor is past the table seat itself",
            )
            pitch += 2f
        }
    }

    @Test
    fun raisingTheClearanceOnlyEverLetsYouCloser() {
        // The property the knob is worth having: it is monotone, so dragging it
        // one way always does one thing. A dial that sometimes reversed would be
        // worse than no dial.
        listOf(4f, 21f, 34f, 40f, 58f).forEach { pitch ->
            var previous = Float.MAX_VALUE
            var clearance = CameraEnvelope.MIN_CLEARANCE
            while (clearance <= CameraEnvelope.MAX_CLEARANCE) {
                val floor = CameraEnvelope(clearance = clearance).minDistanceAt(pitch, width, height)
                assertTrue(
                    floor <= previous + 1e-4f,
                    "at $pitch degrees clearance $clearance floored at $floor, above the $previous before it",
                )
                previous = floor
                clearance += 0.05f
            }
        }
    }

    @Test
    fun aClearanceFromAnywhereAtAllStaysShortOfTheLens() {
        // The document round-trips through JSON a person can edit, and a one in
        // this field is a projection that cannot be inverted. So the guard is in
        // `minDistanceAt` rather than only on the slider that usually feeds it.
        listOf(-5f, 0f, 0.999f, 1f, 4f, Float.NaN).forEach { rubbish ->
            val floor = CameraEnvelope(clearance = rubbish).minDistanceAt(58f, width, height)
            val pose = CameraEnvelope(clearance = rubbish)
                .clamp(CameraPose(45f, 58f, 0.1f), width, height)
            val stage = plane(pose)

            assertTrue(floor.isFinite() && floor > 0f, "clearance $rubbish solved a floor of $floor")
            for (x in 0..1) {
                for (y in 0..1) {
                    assertTrue(
                        stage.project(x * width, y * height).depth < stage.cameraDistance,
                        "clearance $rubbish let a corner reach the lens",
                    )
                }
            }
        }
    }

    @Test
    fun everySeatIsStillLegalAtTheClearanceItShipsAt() {
        // Raising it only lowers floors, so nothing that was a legal seat can
        // have stopped being one — but that is an argument, and this is the
        // check. The seats are what the keyboard and the bar put you at.
        listOf(CameraEnvelope.DEFAULT_CLEARANCE, 0.95f, CameraEnvelope.MAX_CLEARANCE).forEach { c ->
            val at = CameraEnvelope(clearance = c)
            StageSeat.entries.forEach { seat ->
                assertEquals(
                    seat.pose,
                    at.clamp(seat.pose, width, height),
                    "$seat stopped being a legal place to sit at clearance $c",
                )
            }
        }
    }

    /**
     * And at the tightest one the low seat is held out rather than refused.
     *
     * This used to read "at every clearance", and it was true for as long as
     * every seat looked down at the table from more than fifty degrees of
     * elevation. [StageSeat.POV] is at thirty-two, and depth on this stage goes
     * as `sin(pitch)`, so it sits far closer to the lens plane than the other
     * three and is the first seat a tightened close limit can reach.
     *
     * Being reached is the knob working. `clearance` is three menus deep and its
     * whole meaning is *hold me further out*; a seat that ignored it would be a
     * limit with an exception in it. What must not happen — and what
     * `CameraEnvelope.clearance`'s own note is about — is a seat becoming
     * **unreachable**, and it does not: the distance is clamped, so pressing the
     * button still sits you there, a little further back.
     *
     * The other three are unmoved even at the tightest, which is the half of the
     * old claim that survives intact.
     */
    @Test
    fun theTightestCloseLimitHoldsTheLowSeatOutRatherThanRefusingIt() {
        val tight = CameraEnvelope(clearance = CameraEnvelope.MIN_CLEARANCE)
        listOf(StageSeat.OVERHEAD, StageSeat.TABLE, StageSeat.SEATED).forEach { seat ->
            assertEquals(
                seat.pose,
                tight.clamp(seat.pose, width, height),
                "$seat moved at the tightest close limit",
            )
        }

        val held = tight.clamp(StageSeat.POV.pose, width, height)
        assertTrue(
            held.distance > StageSeat.POV.pose.distance,
            "the POV seat was not held out at all, so this test is measuring nothing",
        )
        // Everything else about the seat survives it: you are sitting where you
        // asked to sit, looking where you asked to look, from further back.
        assertEquals(StageSeat.POV.pose.pitchDegrees, held.pitchDegrees)
        assertEquals(StageSeat.POV.pose.shiftY, held.shiftY)
        assertEquals(
            tight.minDistanceAt(StageSeat.POV.pose.pitchDegrees, width, height),
            held.distance,
            "and it is held at the solved floor, not at some other number",
        )
    }

    @Test
    fun theTableNeverReachesTheCamera() {
        // The failure this floor exists for: at a steep pitch the far corner of
        // the mat travels toward the lens until it passes it, the perspective
        // divide clamps rather than exploding, and unproject quietly stops being
        // the inverse of project. Nothing crashes; the shadows just tear.
        listOf(4f, 15f, 34f, 46f, 58f).forEach { pitch ->
            val pose = envelope.clamp(CameraPose(yawDegrees = 45f, pitchDegrees = pitch, distance = 0.1f), width, height)
            val stage = plane(pose)
            for (x in 0..1) {
                for (y in 0..1) {
                    val depth = stage.project(x * width, y * height).depth
                    assertTrue(
                        depth < stage.cameraDistance,
                        "at $pitch degrees a corner reached the lens: $depth of ${stage.cameraDistance}",
                    )
                }
            }
        }
    }

    @Test
    fun youMayComeCloseLookingDownAndMustStepBackLookingAlong() {
        val high = envelope.minDistanceAt(6f, width, height)
        val low = envelope.minDistanceAt(58f, width, height)

        assertTrue(low > high, "the floor should rise with the pitch: $low against $high")
        assertEquals(envelope.minDistance, envelope.minDistanceAt(0f, width, height))
    }

    @Test
    fun theReadingSeatIsFlatterThanThePlayingSeat() {
        assertTrue(StageSeat.OVERHEAD.pose.pitchDegrees < StageSeat.TABLE.pose.pitchDegrees)
        assertTrue(StageSeat.TABLE.pose.pitchDegrees < StageSeat.SEATED.pose.pitchDegrees)
        assertTrue(StageSeat.OVERHEAD.pose.distance > StageSeat.SEATED.pose.distance)
    }

    // ---- keeping the board on the glass ----------------------------------------------

    private val layout = BoardLayouter.solve(
        width = width,
        height = height,
        aspectRatio = 59f / 86f,
        perspectiveGrowth = StagePlane.forStage(width, height).perspectiveGrowth,
    )

    private val board = layout.bounds

    @Test
    fun theSeatTheStageOpensAtNeedsNoCorrectionAtAll() {
        val fitted = CameraFit.fit(StageSeat.TABLE.pose, board, envelope, width, height, ::plane)

        assertEquals(StageSeat.TABLE.pose, fitted, "the home seat should already fit")
    }

    @Test
    fun turningTheTableStepsYouBackFarEnoughToSeeIt() {
        // The whole trade this class exists to make: the cards keep their size
        // and the camera is what gives.
        var stepped = 0

        listOf(20f, 45f, 90f, 135f).forEach { yaw ->
            val wanted = CameraPose(yawDegrees = yaw, pitchDegrees = 40f, distance = envelope.minDistance)
            val fitted = CameraFit.fit(wanted, board, envelope, width, height, ::plane)

            assertEquals(yaw, fitted.yawDegrees, "the angle asked for should be honoured")
            assertEquals(40f, fitted.pitchDegrees, "and so should the pitch")
            assertTrue(
                CameraFit.holds(plane(fitted), board),
                "the board still left the glass at $yaw degrees",
            )
            if (fitted.distance > envelope.minDistanceAt(40f, width, height)) stepped++
        }

        assertTrue(stepped > 0, "no angle needed a correction, so this proves nothing")
    }

    @Test
    fun itNeverDolliesFurtherThanItHasTo() {
        val wanted = CameraPose(yawDegrees = 45f, pitchDegrees = 45f, distance = envelope.minDistance)
        val fitted = CameraFit.fit(wanted, board, envelope, width, height, ::plane)

        assertTrue(CameraFit.holds(plane(fitted), board))
        // A hair nearer and it would not: the answer is on the boundary rather
        // than at some safe distance chosen to avoid thinking about it.
        val nearer = fitted.copy(distance = fitted.distance * 0.97f)
        assertTrue(
            !CameraFit.holds(plane(nearer), board),
            "it stopped ${fitted.distance} short of the boundary",
        )
    }

    /**
     * The measurement behind `MatInput.settle` fitting the field rather than the
     * board, and the reason it is a test rather than a sentence: it is the
     * *difference* between two floors, and either of them could move.
     *
     * kai's report was that the camera stopped getting closer at about 1.42.
     * Fitting `bounds` — which adds the hand band along the bottom edge of the
     * stage — the nearest reachable distance at the table seat is 1.47, against
     * an envelope floor of 1.05. The hand is what a push-in costs first, and the
     * fitter was treating that as a reason to refuse.
     */
    @Test
    fun fittingTheFieldRatherThanTheHandIsWhatLetsTheCameraComeClose() {
        val seat = StageSeat.TABLE.pose
        val floor = envelope.minDistanceAt(seat.pitchDegrees, width, height)

        fun closest(bounds: Slot): Float {
            var distance = floor
            while (distance < envelope.maxDistance) {
                if (CameraFit.holds(plane(seat.copy(distance = distance)), bounds)) return distance
                distance += 0.005f
            }
            return envelope.maxDistance
        }

        val withHand = closest(layout.bounds)
        val fieldOnly = closest(layout.field)

        assertTrue(
            withHand > floor + 0.3f,
            "the hand band used to cost a third of the range; now it costs $withHand vs $floor",
        )
        // The durable half, and the only one that cannot invert: whatever the
        // fitter allows, it allows *more* of it against the field than against
        // the board. The original assertion was `fieldOnly <= floor` — the field
        // alone reaching the envelope's own floor — and it has now been wrong
        // twice for the same reason, which is that it compares two numbers that
        // move independently. It held while the floor sat above what the fitter
        // allows; the 0.68 clearance dropped the floor underneath it and it was
        // rewritten to compare against the floor *as it shipped*; the linear
        // floor dropped that one underneath too. A test that reads "the fitter
        // must never bind" is asserting `CameraFit` does nothing, and a test that
        // reads "it must bind at exactly here" is a recording of two unrelated
        // constants agreeing.
        assertTrue(
            fieldOnly < withHand - 0.2f,
            "letting go of the hand should be worth a real push-in: " +
                "field $fieldOnly against board $withHand",
        )
    }

    @Test
    fun turningStillDolliesBackEvenWithTheHandLetGoOf() {
        // The property `CameraFit` exists for, restated against the bounds it is
        // now actually given: a diagonal really does need more room, and it is
        // not the fitter's refusal to lose the hand that was providing that.
        val square = CameraPose(0f, 24f, envelope.minDistance)
        val turned = CameraPose(45f, 24f, envelope.minDistance)

        val straightOn = CameraFit.fit(square, layout.field, envelope, width, height, ::plane)
        val diagonal = CameraFit.fit(turned, layout.field, envelope, width, height, ::plane)

        assertTrue(
            diagonal.distance > straightOn.distance + 0.2f,
            "turning bought no correction: ${straightOn.distance} against ${diagonal.distance}",
        )
    }

    @Test
    fun aBoardThatCannotFitAtAllStillGivesYouAPicture() {
        // Rather than refusing the gesture, which reads as the camera being
        // broken. The furthest seat is the best answer available.
        val huge = Slot(left = -4000f, top = -4000f, width = 12000f, height = 12000f)
        val fitted = CameraFit.fit(CameraPose(distance = 1f), huge, envelope, width, height, ::plane)

        assertEquals(envelope.maxDistance, fitted.distance)
    }

    // ---- turning by the short way round --------------------------------------------

    @Test
    fun aTableGoingFromThreeFiftyToTenTurnsTwentyDegrees() {
        assertClose(370f, Turns.nearest(350f, 10f), note = "the short way:")
        assertClose(-10f, Turns.nearest(10f, 350f), note = "and back again:")
    }

    @Test
    fun theShortWayIsNeverMoreThanHalfATurn() {
        listOf(0f, 45f, 179f, 181f, 359f, -400f, 1000f).forEach { current ->
            listOf(0f, 90f, 180f, 270f, 359f).forEach { target ->
                val moved = abs(Turns.nearest(current, target) - current)
                assertTrue(moved <= 180.001f, "went $moved from $current to $target")
            }
        }
    }

    @Test
    fun aTurnOfExactlyHalfGoesOneWayRatherThanNowhere() {
        assertClose(180f, Turns.nearest(0f, 180f))
        assertClose(180f, Turns.nearest(0f, -180f))
    }

    @Test
    fun theBearingReadsAsACompassRatherThanAnAccumulatedAngle() {
        assertEquals(0, Turns.bearing(CameraPose(yawDegrees = 720f)))
        assertEquals(90, Turns.bearing(CameraPose(yawDegrees = 450f)))
        assertEquals(270, Turns.bearing(CameraPose(yawDegrees = -90f)))
    }

    @Test
    fun aCameraRestingAtASeatSaysWhichOne() {
        assertEquals(StageSeat.TABLE, Turns.seatAt(StageSeat.TABLE.pose))
        assertEquals(StageSeat.OVERHEAD, Turns.seatAt(StageSeat.OVERHEAD.pose))
        // And one turned away from it does not claim to be there.
        assertNull(Turns.seatAt(StageSeat.TABLE.pose.copy(yawDegrees = 40f)))
        assertNull(Turns.seatAt(StageSeat.TABLE.pose.copy(distance = 2f)))
    }

    // ---- the rig --------------------------------------------------------------------

    private fun settle(rig: CameraRig, seconds: Float = 4f) {
        var t = 0f
        while (t < seconds && rig.step(SpringSpec.Snappy, 1f / 120f)) t += 1f / 120f
    }

    @Test
    fun aCameraAtRestCostsTheFrameLoopNothing() {
        val rig = CameraRig()

        assertTrue(!rig.step(SpringSpec.Snappy, 1f / 120f), "a parked camera should skip")
        assertTrue(!rig.moving)
    }

    @Test
    fun aCameraSentToASeatArrivesAtIt() {
        val rig = CameraRig()
        rig.aimAt(StageSeat.OVERHEAD)
        assertTrue(rig.moving)

        settle(rig)

        assertEquals(StageSeat.OVERHEAD.pose, rig.pose)
        assertTrue(!rig.moving, "it should have parked")
    }

    @Test
    fun aCameraDraggedGoesExactlyWhereItIsPutAndStaysThere() {
        // The same rule a carried card obeys. A spring between a finger and the
        // thing it is moving is lag, and lag is what makes it feel remote.
        val rig = CameraRig()
        rig.nudge(deltaYaw = 30f, deltaPitch = 5f)

        assertClose(30f, rig.pose.yawDegrees)
        assertClose(StagePlane.TILT + 5f, rig.pose.pitchDegrees)
        assertTrue(!rig.step(SpringSpec.Snappy, 1f / 120f), "a dragged camera must not then spring")
    }

    @Test
    fun aCameraDraggedPastTheEnvelopeStopsAtIt() {
        val rig = CameraRig()
        repeat(40) { rig.nudge(deltaYaw = 0f, deltaPitch = 10f) }

        assertEquals(rig.envelope.maxPitch, rig.pose.pitchDegrees)
    }

    @Test
    fun aCameraTakesTheShortWayToASeatOnTheOtherSide() {
        val rig = CameraRig()
        rig.nudge(deltaYaw = 350f, deltaPitch = 0f)
        rig.aimAt(StageSeat.TABLE)

        // Not back through 350 degrees of table.
        assertClose(360f, rig.target.yawDegrees, note = "the target should be the near representative:")
        settle(rig)
        assertClose(360f, rig.pose.yawDegrees, 0.2f)
        assertEquals(0, Turns.bearing(rig.pose), "and it is square on when it gets there")
    }

    @Test
    fun placingTheCameraSomewhereDoesNotAnimateThere() {
        val rig = CameraRig()
        rig.placeAt(StageSeat.SEATED.pose)

        assertEquals(StageSeat.SEATED.pose, rig.pose)
        assertTrue(!rig.moving)
    }

    @Test
    fun aRigOpenedAtASeatIsAtThatSeat() {
        val rig = CameraRig(seat = StageSeat.OVERHEAD)

        assertEquals(StageSeat.OVERHEAD.pose, rig.pose)
        assertEquals(StageSeat.OVERHEAD.pose, rig.target)
    }

    // ---- refusing to fall over -------------------------------------------------------

    @Test
    fun aStageWithNoSizeStillAnswersAtEveryAngle() {
        listOf(0f, 45f, 180f).forEach { yaw ->
            val nothing = CameraPose(yawDegrees = yaw).planeFor(0f, 0f)
            val projected = nothing.project(0f, 0f)
            assertTrue(projected.x.isFinite() && projected.y.isFinite(), "at $yaw degrees")
            assertTrue(nothing.perspectiveGrowth.isFinite())
        }
    }

    @Test
    fun aPointAtTheCameraDoesNotDivideByZeroHoweverTheTableIsTurned() {
        val shallow = StagePlane(1600f, 1000f, tiltDegrees = 15f, cameraDistance = 10f, yawDegrees = 63f)
        val projected = shallow.project(800f, 1000f, 10_000f)

        assertTrue(projected.scale.isFinite(), "scale was ${projected.scale}")
        assertTrue(projected.x.isFinite() && projected.y.isFinite())
    }

    // ---- aiming somewhere other than the middle ---------------------------------------

    /**
     * Poses again, and this time aimed away from the middle of the table.
     *
     * Every pan the envelope allows on both axes, at the seats and at the two
     * angles where the projection is least forgiving — because the whole risk of
     * a movable target is that the inverse stops being exact somewhere nobody
     * looked, and an inexact inverse tears every pile edge, card thickness and
     * airborne shadow rather than failing where it happened.
     */
    private val panned: List<CameraPose> = buildList {
        listOf(-2f, -0.7f, 0f, 0.4f, 2f).forEach { px ->
            listOf(-2f, -0.3f, 0f, 1.1f, 2f).forEach { py ->
                add(envelope.clamp(CameraPose(0f, 21f, 1.45f, panX = px, panY = py), width, height))
                add(envelope.clamp(CameraPose(38f, 5f, 2.6f, panX = px, panY = py), width, height))
                add(envelope.clamp(CameraPose(-117f, 74f, 1f, panX = px, panY = py), width, height))
            }
        }
    }

    @Test
    fun aPointOnTheMatComesBackToItselfFromAnywhereIncludingPannedAway() {
        val points = listOf(0f to 0f, 800f to 500f, 1600f to 1000f, 120f to 880f)
        panned.forEach { pose ->
            val stage = plane(pose)
            points.forEach { (x, y) ->
                val screen = stage.project(x, y)
                val back = stage.unproject(screen.x, screen.y)
                assertClose(x, back.x, 0.3f, "x at ($x, $y) from $pose:")
                assertClose(y, back.y, 0.3f, "y at ($x, $y) from $pose:")
            }
        }
    }

    @Test
    fun somethingTouchingTheMatIsStillDrawnWhereItWasComputedWhenPannedAway() {
        panned.forEach { pose ->
            val stage = plane(pose)
            listOf(0f to 0f, 400f to 900f, 1590f to 30f).forEach { (x, y) ->
                val flat = stage.flatten(x, y, 0f)
                assertEquals(x, flat.x, "flatten moved a resting point at $pose")
                assertEquals(y, flat.y, "flatten moved a resting point at $pose")
                assertEquals(1f, flat.scale)

                val raised = stage.raise(x, y, 0f)
                assertEquals(x, raised.x, "raise moved a resting point at $pose")
                assertEquals(y, raised.y, "raise moved a resting point at $pose")
            }
        }
    }

    /**
     * A card in the air still lands where the height says, off centre.
     *
     * `flatten` is projection composed with its own inverse, so this is the one
     * assertion that catches a target added to one of the two and not the other —
     * which would be invisible at a pan of nothing and wrong everywhere else.
     */
    @Test
    fun aFlattenedPointLandsWhereTheRealOneWouldWhenPannedAway() {
        panned.forEach { pose ->
            val stage = plane(pose)
            listOf(300f to 700f, 1200f to 260f).forEach { (x, y) ->
                listOf(18f, 90f).forEach { z ->
                    val honest = stage.project(x, y, z)
                    val flat = stage.flatten(x, y, z)
                    val drawn = stage.project(flat.x, flat.y, 0f)
                    assertClose(honest.x, drawn.x, 0.4f, "x at $z above ($x, $y) from $pose:")
                    assertClose(honest.y, drawn.y, 0.4f, "y at $z above ($x, $y) from $pose:")
                }
            }
        }
    }

    /**
     * The camera is aimed at the point it is panned to, and draws it in the middle.
     *
     * The definition, and worth pinning because it is what makes this a pan of the
     * *camera* rather than a slide of the picture: the target lands dead centre,
     * so the vanishing point never leaves the middle of the glass and
     * [StagePlane.unprojectAt] never needs the off-axis inverse `CameraPose` once
     * refused a target over.
     */
    @Test
    fun whatTheCameraIsAimedAtIsWhatIsInTheMiddleOfTheScreen() {
        listOf(-1.3f to 0.8f, 0.5f to -1.9f, 0f to 0f).forEach { (px, py) ->
            val pose = envelope.clamp(CameraPose(31f, 26f, 1.6f, panX = px, panY = py), width, height)
            val stage = plane(pose)
            val middle = stage.project(stage.targetX, stage.targetY)
            assertClose(stage.centreX, middle.x, 0.01f, "at $pose:")
            assertClose(stage.centreY, middle.y, 0.01f, "at $pose:")
            assertEquals(1f, middle.scale, "the target is the one point at scale one, at $pose")
        }
    }

    /**
     * Aiming away from the middle holds the camera out, because a corner got further.
     *
     * The floor is solved against the corner furthest from the *target*, not the
     * half-diagonal of the surface, and this is why: pan toward one edge and the
     * opposite corner is further from the lens axis, so it reaches the lens plane
     * sooner. The half-diagonal would under-report it, and an under-reported
     * floor is a corner across the lens, which is the one thing on this stage
     * that cannot be drawn or inverted.
     */
    @Test
    fun aimingAwayFromTheMiddleHoldsYouOutRatherThanLettingACornerCross() {
        val middle = envelope.minDistanceAt(45f, width, height)
        val off = envelope.minDistanceAt(45f, width, height, panX = 1.5f, panY = 1.5f)
        assertTrue(off > middle, "panning away should push the floor out: $middle then $off")

        // And the guarantee itself, everywhere: at the floor the furthest corner
        // is inside the lens, whatever the camera is aimed at.
        panned.forEach { pose ->
            val stage = plane(pose)
            for (x in 0..1) {
                for (y in 0..1) {
                    val depth = stage.project(x * width, y * height).depth
                    assertTrue(
                        depth < stage.cameraDistance,
                        "a corner reached the lens at $pose: $depth of ${stage.cameraDistance}",
                    )
                }
            }
        }
    }

    @Test
    fun aPanFromAnywhereAtAllStaysInsideTheLeash() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1e30f, 400f).forEach { rubbish ->
            val pose = envelope.clamp(CameraPose(panX = rubbish, panY = rubbish), width, height)
            assertTrue(pose.panX.isFinite() && pose.panY.isFinite(), "pan $rubbish survived as $pose")
            assertTrue(abs(pose.panX) <= envelope.maxPan, "pan $rubbish landed at ${pose.panX}")
            assertTrue(pose.distance.isFinite(), "pan $rubbish poisoned the floor")
        }
    }

    @Test
    fun aCameraAimedAwayFromTheMiddleIsNotSittingInASeat() {
        val seat = StageSeat.TABLE.pose
        assertEquals(StageSeat.TABLE, Turns.seatAt(seat))
        assertNull(Turns.seatAt(seat.copy(panX = 0.4f)), "a panned camera named a seat it is not at")
    }

    // ---- the horizon, which is on the glass now -----------------------------------------

    /**
     * Past a certain pitch the table has a visible horizon, and above it is sky.
     *
     * [StagePlane.unprojectAt]'s divisor vanishes on exactly one screen row, and
     * it has always been guarded — the guard returns the camera's own target,
     * which is a defensible number and a terrible hit test. It never mattered
     * while the pitch stopped at fifty-eight, where the row sits hundreds of
     * pixels above the top of the glass. At eighty it is on screen, and every tap
     * on the wall would otherwise grab whatever is in the middle of the table.
     */
    @Test
    fun aTapAboveTheHorizonIsNotAPointOnTheTable() {
        val steep = plane(CameraPose(pitchDegrees = 80f, distance = 1.6f))
        val horizon = steep.horizonY
        assertTrue(horizon != null, "a table laid back to eighty degrees has a horizon")
        assertTrue(horizon!! < steep.centreY, "the horizon is above the middle, was $horizon")
        assertTrue(horizon > 0f && horizon < height, "the horizon should be on the glass, was $horizon")

        assertTrue(steep.above(horizon - 4f), "just above the horizon should be sky")
        assertTrue(!steep.above(horizon + 40f), "well below it is table")
        assertTrue(!steep.above(steep.centreY), "the middle of the screen is never sky")
    }

    /**
     * And a finger up there lands a long way up the table rather than in the middle.
     *
     * The whole point of the clamp. Without it [StagePlane.unprojectAt]'s guard
     * hands back the camera's own target, so a tap on the wall grabs whatever is
     * in the middle of the board — which at eighty degrees of pitch is a quarter
     * of the screen behaving like the centre of it.
     */
    @Test
    fun aFingerInTheSkyLandsOffTheTableRatherThanInTheMiddleOfIt() {
        val steep = plane(CameraPose(pitchDegrees = 80f, distance = 1.6f))
        listOf(0f, 40f, steep.horizonY!! - 1f).forEach { sky ->
            val held = steep.belowHorizon(sky)
            assertTrue(held > sky, "$sky was not held down to the table")
            assertTrue(!steep.above(held), "$sky was held to $held, which is still sky")

            val landed = steep.unproject(steep.centreX, held)
            assertTrue(landed.y.isFinite(), "$sky landed at ${landed.y}")
            assertTrue(
                landed.y < -height,
                "$sky should land far up the table, not on it: ${landed.y}",
            )
        }
        // And a row that is looking at the table is left exactly alone, or every
        // finger on the board would be nudged by the guard meant for one corner
        // of the screen.
        assertEquals(steep.centreY, steep.belowHorizon(steep.centreY))
        assertEquals(height, steep.belowHorizon(height))
    }

    @Test
    fun aTableLookedStraightDownAtHasNoHorizonAtAll() {
        val flat = plane(CameraPose(pitchDegrees = 0f, distance = 1.45f))
        assertNull(flat.horizonY, "a flat table has no horizon to point above")
        assertTrue(!flat.above(-5000f), "nothing is above the horizon of a flat table")
    }

    // ---- letting go of a turn -------------------------------------------------------

    private fun rig(): CameraRig = CameraRig().also {
        it.width = width
        it.height = height
    }

    @Test
    fun aFlickedTableKeepsTurningAndComesToRest() {
        val rig = rig()
        val from = rig.pose.yawDegrees
        rig.coast(yawPerSecond = 220f, pitchPerSecond = 0f)
        assertTrue(rig.moving, "a flick should leave the camera travelling")

        var frames = 0
        while (rig.moving && frames < 2000) {
            rig.step(SpringSpec.Snappy, 1f / 60f)
            frames++
        }

        assertTrue(!rig.moving, "the flick never ran down")
        assertTrue(rig.pose.yawDegrees > from + 30f, "it barely moved: ${rig.pose.yawDegrees}")
        assertTrue(frames < 600, "ten seconds is a table that got away, took $frames frames")
    }

    @Test
    fun aReleaseThatIsNotAFlickDoesNotDrift() {
        val rig = rig()
        rig.coast(yawPerSecond = 4f, pitchPerSecond = 3f)
        assertTrue(!rig.moving, "a slow release should stop where it was let go of")
        assertEquals(StageSeat.TABLE.pose, rig.pose)
    }

    @Test
    fun aCoastingTableStopsDeadAtTheEnvelope() {
        val rig = rig()
        rig.coast(yawPerSecond = 0f, pitchPerSecond = 600f)

        var frames = 0
        while (rig.moving && frames < 2000) {
            rig.step(SpringSpec.Snappy, 1f / 60f)
            frames++
        }

        assertEquals(envelope.maxPitch, rig.pose.pitchDegrees, "it should rest against the ceiling")
        assertTrue(frames < 60, "a coast into a wall should stop at once, took $frames frames")
    }

    @Test
    fun aPressCatchesACoastingTable() {
        val rig = rig()
        rig.coast(yawPerSecond = 400f, pitchPerSecond = 0f)
        repeat(5) { rig.step(SpringSpec.Snappy, 1f / 60f) }

        val caught = rig.pose
        rig.halt()
        assertTrue(!rig.moving, "the press did not catch it")
        assertEquals(caught, rig.pose, "catching it should not move it")
        assertTrue(!rig.step(SpringSpec.Snappy, 1f / 60f), "a caught camera should cost the loop nothing")
    }

    @Test
    fun aFlickRunsDownOverTheSameSecondsAtEveryFrameRate() {
        // Per second, not per frame. A decay applied once a frame runs a flick
        // down twice as fast on a 120Hz tablet, which is the bug this shape of
        // arithmetic always eventually is.
        fun travelled(dt: Float): Float {
            val rig = rig()
            rig.coast(yawPerSecond = 300f, pitchPerSecond = 0f)
            var elapsed = 0f
            while (rig.moving && elapsed < 10f) {
                rig.step(SpringSpec.Snappy, dt)
                elapsed += dt
            }
            return rig.pose.yawDegrees
        }
        assertClose(travelled(1f / 60f), travelled(1f / 120f), 2f, "the same flick at two frame rates:")
    }

    @Test
    fun aSeatPressCancelsACoast() {
        val rig = rig()
        rig.coast(yawPerSecond = 500f, pitchPerSecond = 0f)
        repeat(3) { rig.step(SpringSpec.Snappy, 1f / 60f) }

        rig.aimAt(StageSeat.OVERHEAD)
        var frames = 0
        while (rig.moving && frames < 600) {
            rig.step(SpringSpec.Snappy, 1f / 60f)
            frames++
        }
        assertEquals(StageSeat.OVERHEAD.pose.pitchDegrees, rig.pose.pitchDegrees, "it did not arrive")
        assertClose(0f, Turns.signed(rig.pose.yawDegrees), 0.1f, "the coast kept running under the seat:")
    }
}
