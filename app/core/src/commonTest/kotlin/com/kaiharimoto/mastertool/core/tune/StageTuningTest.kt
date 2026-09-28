package com.kaiharimoto.mastertool.core.tune

import com.kaiharimoto.mastertool.core.layout.CameraEnvelope
import com.kaiharimoto.mastertool.core.layout.CameraPose
import com.kaiharimoto.mastertool.core.layout.StageSeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That the tuning document is safe to hand a slider and safe to read off disk.
 *
 * The claims fall in three groups, and all three are about the same worry: this
 * is the one object in the app whose values arrive from a finger and a file
 * rather than from a programmer, so every guard it has is the only guard there
 * is.
 */
class StageTuningTest {

    // ---- the default is what shipped ---------------------------------------------------

    @Test
    fun theDefaultCameraIsAPoseOfItsOwnAndNoLongerASeat() {
        // It *was* `StageSeat.TABLE.pose`, which is what let the document land
        // in a release before the panel did. It is now kai's own opening pose,
        // and the two are deliberately separate objects: the seats are where the
        // `1 · 2 · 3 · 4` buttons take you and `BoardLayouter` is solved against
        // TABLE, so re-posing that seat to make this one derive from it would
        // cost about a sixth of every card's size on every device.
        //
        // What is still asserted is the part that matters — the opening pose is
        // a seat's *shape*, a legal `CameraPose`, and it is inside the envelope
        // its own close limit asks for.
        val pose = StageTuning.DEFAULT.camera.pose()
        assertTrue(StageTuning.DEFAULT.isDefault)
        assertTrue(pose != StageSeat.TABLE.pose, "decoupled in name only")
        // On the surface it was tuned on. A pose is only legal against a stage:
        // `minDistanceAt` solves the floor from the corner of the mat furthest
        // from where the camera is aimed, so a narrower stage has a further
        // floor — which is why the sweep below exists as well as this.
        assertEquals(
            pose,
            StageTuning.DEFAULT.camera.envelope().clamp(pose, 2960f, 1848f),
            "the stage opens at a pose its own envelope would move",
        )
    }

    @Test
    fun theStageOpensAtThePovSeatAndNotMerelyNearIt() {
        // kai asked for the play stage to feel like a first-person game, and the
        // seat that is one already existed — argued, measured and tested — on a
        // button nobody presses before they have already seen the table from
        // standing. So the opening pose *is* that seat rather than a second set
        // of numbers resembling it, and this is what stops the two drifting: `4`
        // takes you back exactly where you started rather than near it.
        assertEquals(
            StageSeat.POV.pose,
            StageTuning.DEFAULT.camera.pose(),
            "the opening pose and the seat it is named after have come apart",
        )
    }

    @Test
    fun theOpeningPoseIsLegalOnEveryScreenTheAppRunsOn() {
        // The test that was missing, and its absence is why the naive version of
        // the seat change was wrong twice over.
        //
        // The test above asks the envelope about **one** surface, kai's tablet.
        // That is not enough, because `minDistanceAt` divides by
        // `governing = max(height, width * 0.55)`, so on a wide short phone the
        // governing dimension comes off the *width* and the whole scale changes.
        // Solving `minDistanceAt(58) <= 1.33` needs 0.614 on 16:10 but 0.647 on
        // a Pixel 8, 0.650 on an S24 and 0.662 on `phone-small` — so a clearance
        // chosen on the reference stage alone fails on three of these seven. And
        // the clearance that shipped before this seat did was worse: at 41.5
        // degrees and 0.59 the floor on `phone-small` is 1.1663 against a
        // distance of 1.1446, so that one box had never opened where the other
        // six did, silently, because a clamp does not report.
        //
        // Only the aspect ratio matters — `reach` and `governing` both scale
        // linearly with the surface — so densities are left out rather than
        // forgotten, and these are the dp boxes from `docs/classic/DEVICES.md`.
        val boxes = listOf(
            "tab-s11" to (1480f to 924f),
            "tab-a9" to (1280f to 800f),
            "fold-open" to (1004f to 804f),
            "pixel8" to (914f to 411f),
            "s24" to (780f to 360f),
            "phone-small" to (640f to 360f),
            "desktop" to (1600f to 1000f),
        )
        val tune = StageTuning.DEFAULT.camera
        val pose = tune.pose()

        boxes.forEach { (name, size) ->
            val (width, height) = size
            assertEquals(
                pose,
                tune.envelope().clamp(pose, width, height),
                "the opening pose is corrected on $name, which then opens somewhere no other device does",
            )
        }
    }

    @Test
    fun readingACameraTakesItsPoseAndKeepsTheLimitAPoseDoesNotCarry() {
        // What **Read camera** does, and the reason it is not `CameraTune.of`.
        // A `CameraPose` has four numbers; this document has five, and the fifth
        // is how close the camera may come rather than where it is. Building the
        // document from the pose alone would have put the close limit back to
        // shipped every time somebody read a seat they had orbited to — the same
        // shape of fault as a slider resetting its neighbour, arriving through a
        // button instead.
        val tuned = StageTuning.DEFAULT.camera.copy(clearance = 0.9f)
        val orbited = CameraPose(yawDegrees = 33f, pitchDegrees = 41f, distance = 1.1f, lens = 0.8f)

        val read = tuned.reading(orbited)

        assertEquals(orbited, read.pose())
        assertEquals(0.9f, read.clearance)
        assertEquals(0.9f, read.envelope().clearance)
    }

    @Test
    fun theDefaultIsTheRoomKaiTuned() {
        // Typed out rather than derived, deliberately: this test is the record
        // of the stage the app opens at, and a version of it that read the same
        // fields the document does would agree with itself and with nothing
        // else. It used to be the record of what the *constants* had; those
        // constants were a second copy of the room that nothing compiled
        // against, and they are gone — so this is now the only place the numbers
        // are written twice, which is the whole job of the test.
        val d = StageTuning.DEFAULT
        assertEquals(-32f, d.hand.leanDegrees)
        assertEquals(1.6f, d.hand.liftFactor)
        assertEquals(0.74f, d.hand.stepFraction)
        assertEquals(2.18f, d.hand.liftRatio)
        assertEquals(0.55f, d.cards.carryLift)
        assertEquals(0.79f, d.cards.fanLiftRatio)
        assertEquals(1.36f, d.cards.peekLift)
        assertEquals(1.88f, d.cards.peekScale)
        assertEquals(0f, d.camera.yawDegrees)
        assertEquals(58f, d.camera.pitchDegrees)
        assertEquals(1.33f, d.camera.distance)
        assertEquals(1f, d.camera.lens)
        assertEquals(0.9f, d.camera.clearance)
        assertEquals(0f, d.camera.panX)
        assertEquals(0f, d.camera.panY)
        assertEquals(0f, d.camera.shiftX)
        assertEquals(0.13f, d.camera.shiftY)
        assertEquals(0.05f, d.room.deskDepth)
        assertEquals(1.4f, d.room.deskSpan)
        assertEquals(2f, d.room.wallBack)
        assertEquals(-3.55f, d.room.lampOut)
        assertEquals(0.12f, d.room.lampAlong)
        assertEquals(2.02f, d.room.lampScale)
        assertEquals(2.94f, d.room.lampMast)
        assertEquals(6.35f, d.room.windowSpan)
        assertEquals(0.21f, d.room.windowAt)
        assertEquals(0.68f, d.room.windowSill)
        assertEquals(3.2f, d.room.windowHead)
        // And the one thing that must be off, or the tool changes the picture
        // before anybody has touched it. kai left it there on purpose after
        // seeing it work: defocus is a photographic effect on a stage whose
        // whole job is that you can read the cards.
        assertEquals(0f, d.focus.strength)
    }

    @Test
    fun sanitisingWhatShippedChangesNothing() {
        // It runs on load and on save, so a default that its own clamp would
        // move is a document that rewrites itself on first launch.
        assertEquals(StageTuning.DEFAULT, StageTuning.DEFAULT.sanitised())
    }

    // ---- every knob is well formed ------------------------------------------------------

    @Test
    fun everyKnobsDefaultSitsInsideItsOwnRange() {
        // A slider that opens outside its own track snaps the moment it is
        // touched, and the user sees a value they did not set.
        StageKnobs.ALL.forEach { knob ->
            val value = knob.get(StageTuning.DEFAULT)
            assertTrue(
                value in knob.min..knob.max,
                "${knob.path} ships at $value, outside ${knob.min}..${knob.max}",
            )
            assertTrue(knob.max > knob.min, "${knob.path} has an empty range")
            assertTrue(knob.step > 0f && knob.step <= knob.max - knob.min, "${knob.path} step")
        }
    }

    @Test
    fun everyKnobReadsBackWhatItWrites() {
        // get/set are two lambdas per knob and nothing but this stops one of
        // them pointing at the wrong field — a copy-paste error that shows up as
        // one slider moving another.
        StageKnobs.ALL.forEach { knob ->
            val target = knob.valueAt(0.73f)
            val written = knob.set(StageTuning.DEFAULT, target)
            assertEquals(target, knob.get(written), "${knob.path} did not read back")
            // And it moved nothing else.
            StageKnobs.ALL.filter { it.path != knob.path }.forEach { other ->
                assertEquals(
                    other.get(StageTuning.DEFAULT),
                    other.get(written),
                    "setting ${knob.path} also moved ${other.path}",
                )
            }
        }
    }

    @Test
    fun everyKnobHasItsOwnPathAndAReadableNote() {
        val paths = StageKnobs.ALL.map { it.path }
        assertEquals(paths.size, paths.toSet().size, "two knobs share a path: $paths")
        StageKnobs.ALL.forEach {
            assertTrue(it.label.isNotBlank(), "${it.path} has no label")
            assertTrue(it.note.length > 20, "${it.path}'s note says nothing: '${it.note}'")
            assertTrue(it.group in StageKnobs.GROUPS, "${it.path} is in no group")
        }
        // Every group has something in it, or the panel draws an empty heading.
        StageKnobs.GROUPS.forEach { assertTrue(StageKnobs.of(it).isNotEmpty(), "$it is empty") }
    }

    @Test
    fun theCameraKnobsDoNotOfferAPoseTheEnvelopeWouldRefuse() {
        // A slider that reaches past the envelope reads as broken: you drag it,
        // the clamp pulls it back, and nothing you do moves it further.
        val envelope = CameraEnvelope()
        val pitch = StageKnobs.ALL.first { it.path == "camera.pitchDegrees" }
        val lens = StageKnobs.ALL.first { it.path == "camera.lens" }

        assertEquals(envelope.minPitch, pitch.min)
        assertEquals(envelope.maxPitch, pitch.max)
        assertEquals(envelope.minLens, lens.min)
        assertEquals(envelope.maxLens, lens.max)
    }

    // ---- and nothing hostile survives the door -------------------------------------------

    @Test
    fun aNonFiniteValueIsRefusedRatherThanStored() {
        // The one that breaks the stage *after a restart*, with no cause visible
        // and no way back: NaN reaches `StagePlane`'s trigonometry and every
        // trig function downstream returns NaN, so nothing draws and nothing logs.
        StageKnobs.ALL.forEach { knob ->
            listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
                val written = knob.set(StageTuning.DEFAULT, bad)
                assertTrue(
                    knob.get(written).isFinite(),
                    "${knob.path} stored $bad",
                )
                assertEquals(
                    knob.get(StageTuning.DEFAULT),
                    knob.get(written),
                    "${knob.path} did not fall back to what ships",
                )
            }
        }
    }

    @Test
    fun aDocumentFullOfNonsenseSanitisesToSomethingDrawable() {
        val wrecked = StageTuning(
            camera = CameraTune(Float.NaN, 900f, -3f, 0f),
            hand = HandTune(90f, 0.01f, 40f, -2f),
            cards = CardTune(-9f, Float.POSITIVE_INFINITY, 0f, 0f),
            focus = FocusTune(17f, -4f, 8f),
        ).sanitised()

        StageKnobs.ALL.forEach { knob ->
            val value = knob.get(wrecked)
            assertTrue(value.isFinite(), "${knob.path} came back $value")
            assertTrue(value in knob.min..knob.max, "${knob.path} came back $value")
        }
        // And the hand's lift never goes under the felt, which is the one that
        // folds a shadow through itself rather than merely looking wrong.
        assertTrue(wrecked.hand.liftFactor >= 1f)
    }

    // ---- the export ----------------------------------------------------------------------

    @Test
    fun anUntouchedExportSaysNothingChanged() {
        val text = TuningCodec.export(StageTuning.DEFAULT)

        assertTrue(TuningCodec.changedIn(StageTuning.DEFAULT).isEmpty())
        assertTrue(text.contains("\"changed\": []"), text.take(400))
        assertTrue(text.contains("\"version\": 1"))
    }

    @Test
    fun anExportNamesExactlyWhatWasMoved() {
        val tuned = StageTuning.DEFAULT
            .let { StageKnobs.ALL.first { k -> k.path == "camera.lens" }.set(it, 1.34f) }
            .let { StageKnobs.ALL.first { k -> k.path == "hand.leanDegrees" }.set(it, -31f) }

        val changed = TuningCodec.changedIn(tuned)
        assertEquals(2, changed.size, "$changed")
        assertTrue(changed.any { it.startsWith("camera.lens: 1.34") }, "$changed")
        assertTrue(changed.any { it.startsWith("hand.leanDegrees: -31") }, "$changed")
    }

    @Test
    fun anExportReadsBackAsTheSameDocument() {
        // The round trip is the contract with the person pasting it: what they
        // hand back has to mean what the panel showed them.
        val tuned = StageKnobs.ALL.fold(StageTuning.DEFAULT) { d, knob ->
            knob.set(d, knob.valueAt(0.42f))
        }

        val text = TuningCodec.export(tuned, TuningSurface(2960, 1848, 2f, "DESK", "NIGHT", "TABLE"))
        assertEquals(tuned, TuningCodec.parse(text))
    }

    @Test
    fun aBareDocumentPastedOnItsOwnIsAlsoRead() {
        // Somebody will trim the export down to the interesting half. A tool
        // that only accepts its own whole output is a tool that argues.
        val bare = """{"camera":{"lens":1.5},"hand":{"leanDegrees":-30}}"""
        val read = assertNotNull(TuningCodec.parse(bare))

        assertEquals(1.5f, read.camera.lens)
        assertEquals(-30f, read.hand.leanDegrees)
        // Everything unmentioned is what ships, not zero.
        assertEquals(StageTuning.DEFAULT.cards, read.cards)
    }

    @Test
    fun rubbishFromAClipboardIsRefusedRatherThanThrown() {
        listOf("", "   ", "not json", "{", "[1,2,3]").forEach {
            assertNull(TuningCodec.parse(it), "parsed '$it'")
        }
    }

    @Test
    fun aPastedDocumentIsClampedLikeAnyOther() {
        val hostile = """{"camera":{"pitchDegrees":900,"lens":-4},"focus":{"strength":9}}"""
        val read = assertNotNull(TuningCodec.parse(hostile))

        assertEquals(CameraEnvelope().maxPitch, read.camera.pitchDegrees)
        assertEquals(CameraEnvelope().minLens, read.camera.lens)
        // Read off the knob rather than typed, like the two above it. The
        // defocus ceiling moved from a quarter to a half when the falloff was
        // re-derived against the board's own depth, and a literal here would
        // have been a red test with nothing wrong — which is a test people
        // learn to edit without reading.
        assertEquals(
            StageKnobs.ALL.first { it.path == "focus.strength" }.max,
            read.focus.strength,
        )
    }

    @Test
    fun theReferenceBlockIsReadOffTheLiveConstants() {
        // A reference table that lies is worse than no reference table, so it is
        // derived rather than typed. This is the guard against somebody later
        // "tidying" it into literals.
        val reference = StageReference()

        assertEquals(CameraEnvelope().maxPitch, reference.envelope.maxPitch)
        assertEquals(StageSeat.entries.size, reference.seats.size)
        StageSeat.entries.forEach {
            assertEquals(CameraTune.of(it.pose), reference.seats.getValue(it.name))
        }
    }

    @Test
    fun aNumberIsWrittenAsShortAsItCanBe() {
        // `0.62f.toString()` is "0.62" on JVM and a float's true expansion
        // elsewhere; an export full of 0.6200000047683716 reads as precision
        // that is not there.
        assertEquals("0.62", TuningCodec.trim(0.62f))
        assertEquals("-24", TuningCodec.trim(-24f))
        assertEquals("1", TuningCodec.trim(1f))
        assertEquals("1.34", TuningCodec.trim(1.34f))
        assertEquals("0", TuningCodec.trim(Float.NaN))
    }
}
