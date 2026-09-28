package com.kaiharimoto.mastertool.core.render

import com.kaiharimoto.mastertool.core.motion.Pose3
import com.kaiharimoto.mastertool.core.motion.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShadingTest {

    private val key = StageRig.Key
    private val gloss = CardMaterial.Gloss

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-3f, note: String = "") {
        assertTrue(abs(expected - actual) < tolerance, "$note expected $expected, was $actual")
    }

    // ---- the rig -------------------------------------------------------------------

    @Test
    fun theKeyIsAboveTheTableAndOffToOneSide() {
        // Directly overhead casts its shadow directly underneath, which is the
        // same as casting none — and then nothing on the table looks lifted.
        assertTrue(key.direction.z < -0.5f, "the key points down at the felt")
        assertTrue(abs(key.direction.x) > 0.1f, "and not straight down")
        assertClose(1f, key.direction.length, note = "the key is a unit direction:")
        assertClose(1f, key.toLight.length, note = "and so is the way back to it:")
    }

    @Test
    fun theWayBackToTheLightIsTheWayTheLightCame() {
        assertClose(-key.direction.x, key.toLight.x)
        assertClose(-key.direction.z, key.toLight.z)
    }

    @Test
    fun theEyeRidesUpAsTheTableLaysBack() {
        // Seen from the mat's own frame, tilting the table away is the same as
        // the viewer standing up — and this is the number the specular pool on
        // every resting card is placed from.
        val flat = StageRig.eye(0f)
        val tilted = StageRig.eye(15f)

        assertClose(0f, flat.y, note = "a flat table is looked at square on:")
        assertClose(1f, flat.z)
        assertTrue(tilted.y > 0.2f, "the eye moves toward the near edge, was ${tilted.y}")
        assertTrue(tilted.z > 0.9f, "but is still mostly straight on, was ${tilted.z}")
        assertClose(1f, tilted.length, note = "the eye is a unit direction:")
    }

    // ---- diffuse -----------------------------------------------------------------

    @Test
    fun aSurfaceFacingTheLightIsBrighterThanOneFacingAway() {
        val toward = Shading.lit(key.toLight, key)
        val away = Shading.lit(-key.toLight, key)

        assertClose(1f, toward, note = "square into the key is full brightness:")
        assertClose(key.ambient, away, note = "and facing away leaves the ambient:")
        assertTrue(toward > away)
    }

    @Test
    fun nothingEverGoesFullyBlack() {
        // This is a black stage. A card is the brightest object in the frame,
        // and a card that dims to nothing because it tilted reads as a bug
        // rather than as shading.
        listOf(
            Vec3(0f, 0f, 1f),
            Vec3(0f, 0f, -1f),
            Vec3(1f, 0f, 0f),
            Vec3(-1f, -1f, -1f),
        ).forEach { normal ->
            val lit = Shading.lit(normal, key)
            assertTrue(lit >= key.ambient - 1e-4f, "went darker than ambient at $normal: $lit")
            assertTrue(lit <= 1f, "went brighter than white at $normal: $lit")
        }
    }

    @Test
    fun theBounceKeepsACardTurnedFromTheKeyOffTheFloor() {
        // Turned hard away from the key but still looking at the viewer, which
        // is the case that matters: a card angled out of the light is the one
        // the fill exists for.
        val turnedAway = Rot3.normal(Pose3(rotY = 50f))
        val keyOnly = Shading.lit(turnedAway, key)
        val both = StageRig.lit(turnedAway).amount

        assertTrue(both > keyOnly, "the fill should do something: $both vs $keyOnly")
        assertTrue(both <= 1f)
    }

    @Test
    fun aSurfaceFacingIntoTheTableGetsNothingFromAnyOfTheLights() {
        // All three lamps are above the felt, so the underside of anything is on
        // ambient alone. Correct, and worth pinning down — a fill that lit the
        // bottom of a card would be a light coming up through the table.
        val downward = Vec3(0f, 0f, -1f)

        assertClose(key.ambient, StageRig.lit(downward).amount, note = "the underside:")
    }

    // ---- the highlight -----------------------------------------------------------

    @Test
    fun aFlatCardsHighlightSitsOnTheSideTheLightIsOn() {
        // The key comes from up and to the left, so the pool does too.
        val shade = Shading.of(Pose3(), gloss, key)

        assertTrue(shade.hotspot.x < 0.5f, "left of centre, was ${shade.hotspot.x}")
        assertTrue(shade.hotspot.y < 0.5f, "above centre, was ${shade.hotspot.y}")
    }

    @Test
    fun tiltingTheCardWalksTheHighlightAcrossIt() {
        // The single perception the whole material model exists to produce: a
        // highlight that *moves* rather than a brightness that changes. It is
        // why you tilt a real card to read the small print.
        val level = Shading.of(Pose3(), gloss, key).hotspot
        val turnedAway = Shading.of(Pose3(rotY = 18f), gloss, key).hotspot
        val turnedToward = Shading.of(Pose3(rotY = -18f), gloss, key).hotspot

        assertTrue(turnedAway.x < level.x, "pushing the right edge back slides it left")
        assertTrue(turnedToward.x > level.x, "and pulling it forward slides it right")
        assertTrue(
            abs(turnedToward.x - turnedAway.x) > 0.25f,
            "and it should be a real journey, not a nudge",
        )
    }

    @Test
    fun theHighlightIsAllowedToLeaveTheCard() {
        // Clamping it to the border would pin a bright pool to the edge of a
        // card that should simply have stopped catching the light.
        val steep = Shading.of(Pose3(rotY = -55f), gloss, key)

        assertTrue(steep.hotspot.x > 1f, "it should be off the edge by now: ${steep.hotspot.x}")
        assertTrue(steep.hotspot.x.isFinite(), "and still a number")
    }

    @Test
    fun aCardTurnedAwayFromTheLightHasNoHighlightAtAll() {
        // Facing the felt: lambert is zero, so the specular term has to be too,
        // or the back of the card glows.
        val shade = Shading.of(Pose3(rotX = 100f), gloss, key)

        assertEquals(0f, shade.specular)
    }

    /**
     * The brightest the material ever gets, found by turning the card until it
     * gets there — rather than by picking one angle and hoping it is near the
     * mirror direction. Foil's highlight is *tighter* as well as brighter, so
     * at an arbitrary angle it can legitimately be the dimmer of the two, and a
     * test that fixed the angle would be asserting the wrong thing.
     */
    private fun peakSpecular(material: CardMaterial): Float =
        (-60..60 step 3).maxOf { turn ->
            (-60..60 step 3).maxOf { tilt ->
                Shading.of(
                    Pose3(rotX = tilt.toFloat(), rotY = turn.toFloat()),
                    material,
                    key,
                ).specular
            }
        }

    @Test
    fun foilCatchesMoreLightThanCardStockAndASleeveCatchesLeast() {
        val foil = peakSpecular(CardMaterial.Foil)
        val card = peakSpecular(CardMaterial.Gloss)
        val sleeve = peakSpecular(CardMaterial.Sleeve)

        assertTrue(foil > card, "foil $foil vs gloss $card")
        assertTrue(card > sleeve, "gloss $card vs sleeve $sleeve")
    }

    @Test
    fun foilsHighlightIsTighterThanCardStocksAsWellAsBrighter() {
        // Which is what makes it read as metallic rather than as a brighter
        // piece of paper: the pool is smaller, so it sweeps rather than glows.
        val across = { material: CardMaterial ->
            (-60..60 step 2).count { turn ->
                Shading.of(Pose3(rotY = turn.toFloat()), material, key).specular > 0.02f
            }
        }

        assertTrue(
            across(CardMaterial.Foil) < across(CardMaterial.Gloss),
            "foil ${across(CardMaterial.Foil)} vs gloss ${across(CardMaterial.Gloss)}",
        )
    }

    @Test
    fun onlyFoilSplitsTheHighlightIntoColour() {
        // The app's colour rule, kept: colour on a card face is light, and it
        // lives inside the specular term or nowhere.
        assertEquals(0f, CardMaterial.Gloss.iridescence)
        assertEquals(0f, CardMaterial.Sleeve.iridescence)
        assertTrue(CardMaterial.Foil.iridescence > 0.5f)
    }

    // ---- which side you are looking at ------------------------------------------

    @Test
    fun aSetCardIsLitAsTheBackItIsShowing() {
        // Not as a face nobody can see. Two cards at the same angle, one set,
        // must not have their highlights in the same place.
        val faceUp = Shading.of(Pose3(rotX = -20f), gloss, key)
        val faceDown = Shading.of(Pose3(rotX = -20f, rotY = 180f), CardMaterial.Sleeve, key)

        assertTrue(faceUp.facing > 0f, "face up is looking at us")
        assertTrue(faceDown.facing < 0f, "and set is not")
        assertTrue(faceDown.diffuse > 0f, "but it is still lit")
    }

    @Test
    fun theHighlightOnASetCardWalksTheRightWay() {
        // Mirrored along with the card. Without that, turning a set card over
        // sends its highlight the opposite way from a face-up one, and the two
        // stop looking like the same object.
        val level = Shading.of(Pose3(rotY = 180f), CardMaterial.Sleeve, key).hotspot
        val turned = Shading.of(Pose3(rotY = 180f + 18f), CardMaterial.Sleeve, key).hotspot

        assertTrue(turned.x < level.x, "was ${turned.x} against ${level.x}")
    }

    // ---- the rim ---------------------------------------------------------------------

    @Test
    fun theRimLightsUpAsACardGoesEdgeOn() {
        val square = Shading.of(Pose3(), gloss, key).fresnel
        val steep = Shading.of(Pose3(rotY = 70f), gloss, key).fresnel

        assertClose(0f, square, note = "a card facing you has no rim:")
        assertTrue(steep > 0.1f, "and one nearly edge-on has plenty: $steep")
    }

    // ---- the edges of a solid ----------------------------------------------------------

    @Test
    fun anEdgeTurnedAwayIsNeverReachedByTheRigAtAll() {
        // This used to say that the rig returns nothing for a face pointing
        // away, which was a back-face cull done twice — once by the culler and
        // once by the lamps — and the second copy asked the cheaper, wronger
        // question. Two culls that disagree is one cull too many, so the rig
        // stopped having an opinion and this says the same thing where it is
        // actually decided: the far edge never gets as far as being lit.
        val faces = CardSolid.slab(Pose3(), 100f, 145f, depth = 30f)
        val far = faces.first { it.normal.y < -0.5f }
        val eyeAt = Vec3.Zero + StageRig.eye(15f) * 1450f

        assertTrue(
            far !in CardSolid.visible(faces, eyeAt),
            "the edge on the far side of a pile is culled before it is painted",
        )
    }

    @Test
    fun theNearEdgeOfAPileIsLitByTheFillBecauseTheKeyCannotReachIt() {
        // The key comes from the player's side, so the one edge of a pile that
        // is ever visible is the one facing away from it. Without the bounce
        // that band is flat ambient, and a deck's white edge is not flat
        // ambient in any room anyone has played in.
        val faces = CardSolid.slab(Pose3(), 100f, 145f, depth = 30f)
        val near = faces.first { it.normal.y > 0.5f }

        val withFill = StageRig.face(near, StageRig.eye(15f)).amount
        val keyOnly = Shading.lit(near.normal, key)

        assertTrue(withFill > keyOnly, "$withFill should beat $keyOnly")
        assertTrue(withFill <= 1f)
    }

    // ---- separating a card from the felt -----------------------------------------------

    /** The rim lamp switched off, for measuring what it was contributing. */
    private val dark = Light(StageRig.Rim.direction, intensity = 0f, ambient = 0f)

    @Test
    fun theRimReachesTheOneEdgeOfAPileAnybodyEverSees() {
        // The claim the first version of this test could not make, and the
        // reason it had to be rewritten. A rim light belongs behind the subject;
        // behind is the one place it does nothing here, because this stage culls
        // back faces and looks down at the table, so every surface a lamp behind
        // could reach has already been thrown away before it is shaded. The lamp
        // pointed that way for a while and a golden proved it changed exactly
        // one face in a whole board — the far edge of a card held in the air —
        // while leaving every pile it was written to outline byte-identical.
        //
        // The old assertion could not have caught that: it only asked that the
        // lamp travel toward the player, which the key does too.
        val rim = StageRig.Rim
        val nearEdge = Vec3(0f, 1f, 0f)

        assertTrue(
            nearEdge dot rim.toLight > 0.4f,
            "the rim cannot reach a pile's near edge, was ${nearEdge dot rim.toLight}",
        )
        assertTrue(
            nearEdge dot key.toLight < 0f,
            "and it is only worth having because the key cannot",
        )
        assertTrue(rim.direction.x * key.direction.x < 0f, "from the key's other side")
        assertTrue(
            abs(rim.direction.z) < abs(key.direction.z),
            "lower than the key, was ${rim.direction.z}",
        )
        assertTrue(rim.intensity < key.intensity * 0.5f, "and weak, was ${rim.intensity}")
        assertClose(1f, rim.direction.length, note = "the rim is a unit direction:")
    }

    @Test
    fun theRimVanishesAsASurfaceTurnsToFaceTheCamera() {
        // The one thing that would make the third lamp worthless: a backlight
        // that simply added its own dot product would lift a card lying face-up
        // toward the camera, which is an ambient with extra steps and a more
        // expensive one.
        //
        // Measured against a dimmed key on purpose. A card square into the full
        // key is already at white, so the clamp alone hides the difference and a
        // test run at full strength would pass with no gate in the rig at all.
        val dim = key.copy(intensity = 0.25f)

        // One card turning from face-on to nearly edge-on, toward the side the
        // rim lamp stands on.
        val turning = listOf(
            Vec3(0f, 0f, 1f),
            Vec3(0.34f, 0f, 0.94f),
            Vec3(0.71f, 0f, 0.71f),
            Vec3(0.94f, 0f, 0.34f),
            Vec3(1f, 0f, 0.02f),
        )
        val gains = turning.map { normal ->
            StageRig.lit(normal, Vec3.Toward, key = dim).amount -
                StageRig.lit(normal, Vec3.Toward, key = dim, rim = dark).amount
        }

        assertEquals(0f, gains.first(), "square on the rim should contribute nothing whatever")
        assertEquals(gains.sorted(), gains, "and only grow toward the silhouette: $gains")
        assertTrue(gains.last() > 0.02f, "arriving at a real line of light: ${gains.last()}")
    }

    @Test
    fun theRimLandsOnTheSilhouetteEdgeOfARealSolid() {
        // What the stage was missing, on a card rather than on a chosen normal.
        // A side edge the camera sees at a graze is drawn as a hairline, and a
        // hairline of flat ambient grey on a true-black stage is a hairline
        // nobody can see — which is why a dim card's outline used to dissolve
        // into the felt. Seen from round the side of the table, because that is
        // where a flat card's side edge has any width on the glass at all.
        val eye = StageRig.eye(15f, 270f)
        val silhouette = CardSolid.slab(Pose3(), 100f, 145f, depth = 30f)
            .first { it.normal.x > 0.5f }

        val withRim = StageRig.face(silhouette, eye).amount
        val without = StageRig.lit(silhouette.normal, eye, rim = dark).amount

        assertTrue(withRim > without + 0.02f, "a line of light, was $withRim against $without")
        assertTrue(withRim <= 1f, "and never brighter than white, was $withRim")
    }

    // ---- colour temperature --------------------------------------------------------

    @Test
    fun theKeyIsWarmAndTheFillIsCool() {
        assertTrue(key.warmth > 0f, "the key, was ${key.warmth}")
        assertTrue(StageRig.Bounce.warmth < 0f, "the fill, was ${StageRig.Bounce.warmth}")
    }

    @Test
    fun aSurfaceNoDirectionalLightReachesStaysAchromatic() {
        // The handbook's rule, arithmetically rather than by promise: the table
        // is achromatic by identity and colour is light, so the ambient — which
        // is the room, and the room is white — sits in the denominator of the
        // mix and a face standing in nothing else comes back neutral.
        val underside = Vec3(0f, 0f, -1f)

        assertClose(0f, StageRig.lit(underside).warmth, note = "the underside:")
    }

    @Test
    fun oneWhiteEdgeIsWarmWhereTheKeyLandsAndCoolWhereOnlyTheFillReaches() {
        // The split across a single object, which is the point of having two
        // temperatures rather than one warmer lamp. A difference *within* one
        // pile edge reads at a size the same shift applied to the whole table
        // never would — and the whole table shifting is the failure mode here.
        val intoKey = StageRig.lit(key.toLight)
        val faces = CardSolid.slab(Pose3(), 100f, 145f, depth = 30f)
        val near = StageRig.face(faces.first { it.normal.y > 0.5f }, StageRig.eye(15f))

        assertTrue(intoKey.warmth > 0.1f, "into the key, was ${intoKey.warmth}")
        assertTrue(near.warmth < 0f, "the near edge the key cannot reach, was ${near.warmth}")
    }

    @Test
    fun theWarmestLightOnTheStageIsStillAWhite() {
        // The magnitude, pinned, because "a light's temperature is light" is a
        // licence only up to the point where the table looks orange — past that
        // it is decoration, which is not allowed anywhere. Read out through Tone
        // because the number that matters is the one a screen is handed: the
        // encoding curve is steep near white, so the sixteen per cent of blue
        // *light* the key filters away is a far smaller step in the channel.
        val lamp = Lit(1f, key.warmth)
        val split = Tone.shade(1f, lamp.red) - Tone.shade(1f, lamp.blue)

        assertTrue(split > 2f / 255f, "too subtle to be light at all: $split")
        assertTrue(split < 24f / 255f, "that is an orange lamp rather than a warm one: $split")
    }

    @Test
    fun aLampFiltersLightOutRatherThanInventingIt() {
        // Which is why a temperature can never clip. The brightest surfaces on
        // this table already sit near the top of the range, so a warm lamp that
        // *added* red would have nowhere to put it and would quietly go white
        // again exactly where it is most visible.
        listOf(-1f, -0.4f, 0f, 0.75f, 1f).forEach { warmth ->
            val lamp = Lit(1f, warmth)

            listOf("red" to lamp.red, "green" to lamp.green, "blue" to lamp.blue)
                .forEach { (channel, value) ->
                    assertTrue(value <= 1f, "$channel at warmth $warmth was $value")
                    assertTrue(value > 1f - 2f * Lit.TEMPERATURE, "$channel at warmth $warmth was $value")
                }
        }
        assertClose(1f, Lit(1f, 0f).blue, note = "a white lamp filters nothing:")
    }

    @Test
    fun theHighlightIsTheColourOfTheLampAndNotOfTheRoom() {
        // A specular highlight is a mirror image of the source, so it takes the
        // lamp's colour whole rather than diluted by the ambient the way every
        // diffuse term on this stage is. It is also the only place a temperature
        // can reach a card's face at all: the diffuse arrives there as a black
        // veil, and a coloured wash over card art is the anti-pattern.
        val shade = Shading.of(Pose3(), gloss, key)

        assertEquals(key.warmth, shade.lamp.warmth, "the pool should be the key's own colour")
        assertClose(1f, shade.lamp.amount, note = "at full strength, the specular being the amount:")
        assertTrue(shade.lamp.blue < shade.lamp.red, "and warm means less blue, was ${shade.lamp.blue}")
    }

    // ---- the light reaches every stock, in every room -------------------------

    @Test
    fun everyStockCatchesTheLightInEveryRoomItIsSeenIn() {
        // `docs/classic/LOOP.md` carried *"nothing on a card catches the light"* as an
        // impression from iteration 0, with a note that it was worth measuring
        // before assuming it needed to be stronger. Measured, it was worse than
        // an impression: a foil lying flat came to **0.0005** under the day rig
        // and 0.0012 at night, against [Shading.FAINTEST], so the material with
        // the highest specular constant on the table was the only one with no
        // highlight at all — sixty-four times dimmer than a sleeve, because a
        // 44th power of a typical 0.855 alignment is one part in a thousand.
        //
        // This is the claim that stops it coming back, and it is deliberately
        // over every room rather than over the one the stage opens in: a card
        // is lit by whichever rig it is under, and the day key is the oblique
        // one that found this.
        val flat = Pose3(position = Vec3(1480f, 900f, 0f))
        val eye = StageRig.eye(58f, 0f)

        listOf(
            "Minimal" to StageLighting.Minimal.key,
            "DeskDay" to StageLighting.DeskDay.key,
            "DeskNight" to StageLighting.DeskNight.key,
        ).forEach { (room, key) ->
            listOf(
                "Gloss" to CardMaterial.Gloss,
                "Foil" to CardMaterial.Foil,
                "Sleeve" to CardMaterial.Sleeve,
            ).forEach { (stock, material) ->
                val lit = Shading.of(flat, material, key, eye).specular
                assertTrue(
                    lit > Shading.FAINTEST,
                    "$stock lying flat in $room is $lit, under the threshold it has to clear to be drawn",
                )
            }
        }
    }

    @Test
    fun theCoatIsWhatKeepsAFlatCardLitAndTheFinishIsWhatMakesItFlash() {
        // Two lobes doing two jobs, and the test is that neither can be dropped.
        //
        // Take the varnish away and the finish alone cannot hold a flat foil
        // above the threshold under the day rig — which is exactly the state
        // this arrived in. Take the finish away and the flash goes: the whole
        // difference between a foil and a matte is what happens when the angle
        // comes good, and a coat is too broad to say it.
        val flat = Pose3(position = Vec3(1480f, 900f, 0f))
        val eye = StageRig.eye(58f, 0f)
        val day = StageLighting.DeskDay.key

        val bare = Shading.of(flat, CardMaterial.Foil.copy(coat = 0f), day, eye).specular
        assertTrue(bare < Shading.FAINTEST, "a bare finish should be the state this fixed, was $bare")
        assertTrue(
            Shading.of(flat, CardMaterial.Foil, day, eye).specular > Shading.FAINTEST,
            "and the coat is what lifts it",
        )

        // Square-on, where the finish is everything: a foil out-flashes a matte
        // by more than their coats differ, or the finishes are not materials.
        val square = Shading.of(Pose3(), CardMaterial.Foil, key).specular
        val matte = Shading.of(Pose3(), CardMaterial.Sleeve, key).specular
        assertTrue(square > matte, "a foil should out-flash a sleeve square on: $square vs $matte")
    }

    @Test
    fun aTighterFinishConcentratesTheLightRatherThanLosingIt() {
        // The bug underneath the measurement, stated as a property. Raising a
        // number below one to a higher power can only ever remove light, so
        // without the `(n + 8) / 8pi` normalisation every increase in
        // `shininess` was quietly a reduction in how much of the lamp came
        // back — "shinier" meant "dimmer", which is not what the word means.
        //
        // Asked at the one geometry where a lobe is at its actual peak: a lamp
        // straight overhead and an eye straight above it, so the half-vector is
        // the normal and the alignment is exactly one. Anywhere else a tighter
        // lobe *is* dimmer — that is what a tighter lobe means, and it is
        // precisely why the coat above has to exist.
        val overhead = Light(direction = Vec3(0f, 0f, -1f), intensity = 1f, ambient = 0f)
        val straightDown = Vec3(0f, 0f, 1f)
        val tight = CardMaterial.Gloss.copy(shininess = 60f, coat = 0f)
        val slack = CardMaterial.Gloss.copy(shininess = 6f, coat = 0f)

        val peakTight = Shading.of(Pose3(), tight, overhead, straightDown).specular
        val peakSlack = Shading.of(Pose3(), slack, overhead, straightDown).specular

        assertTrue(
            peakTight > peakSlack,
            "a tighter finish came back dimmer at its own peak: $peakTight against $peakSlack",
        )
    }

    // ---- a ruled finish answers with a streak ---------------------------------

    @Test
    fun onlyAFoilStretchesItsHighlightAndItStretchesByItsOwnExponents() {
        // `docs/classic/AAA.md` #21: a foil is not a shinier card, it is a combed one,
        // and what says so is the *shape* of the pool rather than its size.
        //
        // The number is not chosen. Splitting one `shininess` about the
        // anisotropy gives the two exponents a ruled surface really has, and a
        // Blinn-Phong lobe's half-width goes as 1/sqrt(n) — so the streak is
        // sqrt(across / along). Foil at 44 and 0.8 is sqrt(79.2 / 8.8) = 3.
        assertClose(
            3f,
            Shading.of(Pose3(), CardMaterial.Foil, key).streak,
            note = "a foil's pool should be three times longer than it is wide:",
        )

        listOf(CardMaterial.Gloss, CardMaterial.Sleeve).forEach { flat ->
            assertEquals(
                1f,
                Shading.of(Pose3(), flat, key).streak,
                "$flat has no grooves and must keep a round pool",
            )
        }
    }

    @Test
    fun theStreakChangesTheShapeOfTheHighlightAndNothingAboutItsStrength() {
        // `docs/classic/DESIGN.md` §7 is that the pool *moves* rather than brightens,
        // and an anisotropic lobe that also got brighter would be that
        // anti-pattern wearing a physics argument. It is also what keeps
        // `GoldenStageTest` still: it records diff, spec, rim and hot, and this
        // change may touch none of them.
        val pose = Pose3()
        val streaked = Shading.of(pose, CardMaterial.Foil, key)
        val round = Shading.of(pose, CardMaterial.Foil.copy(anisotropy = 0f), key)

        assertEquals(round.specular, streaked.specular, "the streak brightened the pool")
        assertEquals(round.diffuse, streaked.diffuse, "the streak moved the diffuse")
        assertEquals(round.fresnel, streaked.fresnel, "the streak moved the rim")
        assertEquals(round.hotspot, streaked.hotspot, "the streak moved the pool")
    }

    @Test
    fun aStreakIsNeverDrawnLongerThanTheCardItIsOn() {
        // The ratio runs away as a finish approaches a true grating, and a
        // highlight longer than the card is a bar across the art rather than a
        // highlight. At the reference 104-pixel card, six is already most of
        // the width.
        val grating = CardMaterial.Foil.copy(anisotropy = 0.999f)

        val streak = Shading.of(Pose3(), grating, key).streak

        assertTrue(streak <= 6f, "a near-grating drew a streak of $streak")
        assertTrue(streak > 1f, "and it is still a streak")
    }
}
