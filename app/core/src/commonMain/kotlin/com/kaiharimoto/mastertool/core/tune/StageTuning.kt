package com.kaiharimoto.mastertool.core.tune

import com.kaiharimoto.mastertool.core.layout.CameraEnvelope
import com.kaiharimoto.mastertool.core.layout.CameraPose
import com.kaiharimoto.mastertool.core.layout.StagePlane
import com.kaiharimoto.mastertool.core.layout.StageSeat
import com.kaiharimoto.mastertool.core.scene.Scenery
import kotlinx.serialization.Serializable

/**
 * The numbers the play stage is tuned by, as a document that can be moved.
 *
 * ## Why this exists at all
 *
 * Every value in here used to be a `private const val` next to the code that
 * read it, which is the right place for a number nobody is arguing about. These
 * are numbers somebody *is* arguing about — the angle of the camera, the lean of
 * a hand, how far a card comes off the felt to be read — and the only honest way
 * to settle that argument is to move the slider on the device it will be judged
 * on. A constant cannot be moved on a tablet. This can.
 *
 * ## The three rules that make it safe
 *
 * **[DEFAULT] is one room somebody chose, written down.** It began as "what
 * shipped, to the bit" — the same numbers the constants beside the code had —
 * which is what let the document land in a release before the panel did. It is
 * now kai's own tuning, exported off the tablet and pasted in whole, and the
 * constants it shadowed have been deleted rather than left to disagree with it.
 * `StageTuningTest` still pins every number, for the same reason: an export is
 * only a *diff* if there is one agreed document to diff against.
 *
 * Two things follow from a default that moves, and both are in the release
 * notes rather than only here. `PreferencesRepository` serialises with
 * `encodeDefaults = true`, so anybody who has ever opened the panel has every
 * field explicitly on disk and **nothing about their stage changes**. What
 * changes is a fresh install — and the panel's own **Reset**, which now returns
 * to this room rather than to the one the app was born in.
 *
 * **Nothing in here re-solves a layout.** Every field is read either at draw
 * time, by the seat solver, or by the *scene* — and none of those three is the
 * board. The reason is one line in `MatInput`: the mat is a single
 * `pointerInput(layout)`, so re-solving the layout tears down the gesture
 * arbiter's event stream mid-gesture. A slider that re-solves is a slider that
 * can kill the drag which is moving it.
 *
 * [RoomTune] looks like it should be barred by that and is not, which is worth
 * stating rather than leaving to be rediscovered. The desk, the wall, the window
 * and the lamp are read *inside* `Scenery.of`, which the play screen remembers
 * against the solved layout rather than as part of solving it — so moving them
 * rebuilds the room and leaves the board, the hit boxes and the live gesture
 * exactly where they were. The number that genuinely does re-solve is
 * `Scenery.ROOM_ABOVE`, which decides how much height the board declines to use;
 * it stays out, and [StageReference] carries it read-only.
 *
 * **Every field is clamped on the way in and on the way out**, against
 * [StageKnobs] — the same bounds the sliders offer, from the same place, so the
 * two cannot drift. [sanitised] runs on load and on save like the rest of
 * `UiPreferences`, because a non-finite float reaching `StagePlane`'s
 * trigonometry is a stage that is broken *after a restart*, with no cause
 * visible and no way back.
 *
 * ## What it deliberately is not
 *
 * It is not a scene graph and it is not a settings screen. It is a short list of
 * numbers with a defensible default each, and the panel that drives it is an
 * instrument rather than a feature — which is why none of it appears in
 * `ShortcutTable` or `MatGuide`.
 */
@Serializable
data class StageTuning(
    val camera: CameraTune = CameraTune(),
    val hand: HandTune = HandTune(),
    val cards: CardTune = CardTune(),
    val focus: FocusTune = FocusTune(),
    val room: RoomTune = RoomTune(),
) {
    /**
     * Every field forced inside the range its own slider offers.
     *
     * Driven by [StageKnobs] rather than written out again, which is the only
     * arrangement where a knob cannot be added to the panel and forgotten here.
     */
    fun sanitised(): StageTuning =
        StageKnobs.ALL.fold(this) { document, knob -> knob.set(document, knob.get(document)) }

    /** True when nothing has been moved, so the stage can skip every override. */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = StageTuning()
    }
}

/**
 * Where the stage opens, and through what.
 *
 * This is **not** the live camera — the rig owns that, and orbiting the table
 * does not write here. It is the seat the stage opens at, which is what "make it
 * the default" means for a camera. The panel writes both: the rig, so you can
 * see it, and this, so it is still there tomorrow.
 */
@Serializable
data class CameraTune(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 58f,
    val distance: Float = 1.33f,
    /** See [CameraPose.lens]: a focal length, with the subject pinned. */
    val lens: Float = 1f,
    /**
     * How close the camera may sit. See [CameraEnvelope.clearance].
     *
     * The one field here that is not part of a pose. It is a limit rather than a
     * position — where the *other* four may go — which is exactly why it belongs
     * on the panel: [distance] was reported as a slider that stops working, and
     * what it stops at is this.
     */
    val clearance: Float = 0.9f,
    /** Where the camera is aimed. See [CameraPose.panX]. */
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** And where that lands in the picture. See [CameraPose.shiftX]. */
    val shiftX: Float = 0f,
    val shiftY: Float = 0.13f,
) {
    /** Named, not positional: [CameraPose] has grown a field three times now. */
    fun pose(): CameraPose = CameraPose(
        yawDegrees = yawDegrees,
        pitchDegrees = pitchDegrees,
        distance = distance,
        lens = lens,
        panX = panX,
        panY = panY,
        shiftX = shiftX,
        shiftY = shiftY,
    )

    /** The envelope this document asks for, which is the shipped one but closer. */
    fun envelope(): CameraEnvelope = CameraEnvelope(clearance = clearance)

    /**
     * This document wearing [pose]'s four numbers, and keeping its own fifth.
     *
     * What **Read camera** does. A pose has no [clearance] — it is not somewhere
     * the camera is — so building one of these from a pose alone would put the
     * limit back to shipped every time somebody read a seat they liked. Which is
     * the same shape of bug as a knob resetting its neighbour, arriving through
     * a button instead of a slider.
     */
    fun reading(pose: CameraPose) = copy(
        yawDegrees = pose.yawDegrees,
        pitchDegrees = pose.pitchDegrees,
        distance = pose.distance,
        lens = pose.lens,
        panX = pose.panX,
        panY = pose.panY,
        shiftX = pose.shiftX,
        shiftY = pose.shiftY,
    )

    companion object {
        fun of(pose: CameraPose) = CameraTune().reading(pose)
    }
}

/**
 * How a card in the hand is held.
 *
 * Two of these are one mechanism wearing two names, and `handLiftOf`'s KDoc has
 * the reason: the lean must pivot on the card's bottom edge, and the lift that
 * achieves it is `(h/2)·sin θ` — trigonometry, not taste. So [leanDegrees] is
 * free and [liftFactor] is a **multiplier on the solved value, floored at one**.
 * Below the solved value the bottom corner of every hand card goes under the
 * felt, and `Shadows.cast` slides a corner that is already below the surface, so
 * half the shadow quad folds back through itself. That shipped once.
 */
@Serializable
data class HandTune(
    /** How far a hand card leans back. Negative leans away from the viewer. */
    val leanDegrees: Float = -32f,
    /** A multiplier on the lift the lean solves for. Never below one. */
    val liftFactor: Float = 1.6f,
    /** The gap between hand cards, as a fraction of a card's width. */
    val stepFraction: Float = 0.74f,
    /** How far a card raised to be read comes up, as a ratio of [CardTune.carryLift]. */
    val liftRatio: Float = 2.18f,
)

/** How far things come off the felt, and how big they get when they do. */
@Serializable
data class CardTune(
    /**
     * The master lift: how far a carried card floats, in card heights.
     *
     * What tells you a card is off the table is not how much bigger it got —
     * that is a few per cent at any sane camera distance — it is how far its
     * shadow has walked out from under it, and that is linear in this.
     */
    val carryLift: Float = 0.55f,
    /** A spread pile, as a ratio of [carryLift]: over the board, under a hand. */
    val fanLiftRatio: Float = 0.79f,
    /** A card held up to be read, in card heights. Further than one being slid. */
    val peekLift: Float = 1.36f,
    /** And how much bigger it gets while it is up there. */
    val peekScale: Float = 1.88f,
)

/**
 * Depth of field, as far as this renderer can honestly go.
 *
 * **It is not a blur and it does not become one by being turned up.** No blur
 * primitive is reachable here: `BlurEffect` is API 31 against a `minSdk` of 26
 * and degrades to a silent no-op below it, and a `renderEffect` per card is the
 * one shape of change `docs/classic/PHOTOREAL.md` measured and called fatal — an
 * offscreen layer and a tile flush per card, per frame.
 *
 * It is also worth far less than it sounds. Measured on this board at
 * 1600x1000: the playing area's whole depth span is 55 mat pixels at the reading
 * seat and 425 at the seated one, so a physically-tuned circle of confusion
 * comes out at **zero pixels overhead and under three seated**, on a card 102
 * pixels wide. A render-pass architecture to move three pixels is not a trade.
 *
 * What defocus destroys first is *micro-contrast*, long before sharpness, and at
 * two or three pixels of blur that is the whole of the visible effect. So this
 * is a contrast falloff with depth: the far half of the board loses its bite,
 * whites come toward the felt, the frame lines around distant art go grey, and
 * the board reads as receding. Every edge stays razor sharp; there is no bokeh
 * and there will not be one without a layer.
 *
 * **Off by default**, because it is a look rather than a correction, and because
 * an instrument that changes the picture before you have touched it is an
 * instrument you cannot read.
 */
@Serializable
data class FocusTune(
    /**
     * Where the plane of focus sits across the board's visible depth: −1 at its
     * far edge, 0 in the middle, +1 at the near edge.
     *
     * Normalised rather than in pixels because that span triples between the
     * reading seat and the seated one, so a focus distance in pixels would mean
     * a different thing at every seat.
     */
    val depth: Float = -1f,
    /**
     * The aperture, as a label. Smaller is shallower.
     *
     * Exported *beside* the gradient it maps to, so that changing the mapping
     * later can never silently retune a saved preset.
     */
    val fNumber: Float = 2f,
    /**
     * How strong the falloff is at full defocus, as an alpha.
     *
     * Zero is off and is the default. The range reaches half now rather than a
     * quarter, and that is a consequence of the recalibration rather than a
     * change of taste: full defocus used to be somewhere past the far end of the
     * table, so the strongest thing the dial could actually put on a card was
     * about a tenth of its own number. It means what it says now, and the top of
     * the range is deliberately past useful — a knob you cannot push into
     * "obviously too much" is a knob whose right value you cannot find.
     */
    val strength: Float = 0f,
)

/**
 * The room the desk scenes are in: the table, the wall behind it, the window in
 * the wall and the lamp standing on it.
 *
 * ## Why these and not the other forty
 *
 * `Scenery` holds something like forty numbers and most of them are joinery —
 * the width of a glazing bar, how far a frame stands proud of the wall, the roll
 * on the shade's rim. Those were solved by looking at a picture once and have
 * stayed solved. These eleven are the ones kai named, and they share a property:
 * they are about *where the furniture is*, which is a question a person can only
 * answer sitting at the tablet with the room in front of them.
 *
 * **Every default here is the constant that shipped**, so `StageTuning.DEFAULT`
 * is still what shipped and `SceneryTest` and `GoldenStageTest` do not move.
 *
 * ## The one coupling, and why it is not two sliders
 *
 * kai's brief was that *"extending the table from the front should push the wall
 * with the window back"*. So [deskDepth] does both: the desk grows toward the
 * player and the wall's plane retreats by the same amount, and the room gets
 * deeper rather than the desk getting longer in front of a wall that stayed put.
 * [wallBack] is then an independent offset on top of it, for the case where the
 * wall wants moving on its own.
 *
 * The derived distance is floored rather than trusted — see `Scenery.wallAt` —
 * because the two knobs can subtract, and a wall at or in front of the mat's far
 * edge would stand over the cards, which is the one thing `docs/classic/DESIGN.md` §11
 * says nothing in a scene may do.
 */
@Serializable
data class RoomTune(
    /** How far the desk reaches toward the player, in card widths. Pushes the wall. */
    val deskDepth: Float = 0.05f,
    /** How wide the desk is, as a share of the stage. Over one runs off both sides. */
    val deskSpan: Float = 1.4f,
    /** How far past the mat the wall stands, in card widths, before [deskDepth]. */
    val wallBack: Float = 2f,
    /** How far right of the mat the lamp stands, in card widths. */
    val lampOut: Float = -3.55f,
    /** And how far down it, as a fraction of the mat's height from the top. */
    val lampAlong: Float = 0.12f,
    /**
     * How stout the lamp is: a multiplier on every radius and on the base's own
     * height. Not on [lampMast], which is the other half of the shape.
     */
    val lampScale: Float = 2.02f,
    /**
     * How tall the lamp is drawn, in card widths, to the top of its shade.
     *
     * The pole length, in kai's words. It is **not** the height of the light —
     * that one is solved from the shipped night key's own ratio and is not a
     * matter of taste (see `Scenery.lampHeight`). An honest desk lamp is off the
     * top of the picture; this is the compressed one you can see.
     */
    val lampMast: Float = 2.94f,
    /** How wide the opening in the wall is, in card widths. */
    val windowSpan: Float = 6.35f,
    /** Where its centre is, as a fraction of the mat's width from the mat's left. */
    val windowAt: Float = 0.21f,
    /** How high its sill and its head are, in card *heights* above the desk. */
    val windowSill: Float = 0.68f,
    val windowHead: Float = 3.2f,
)

/**
 * One number a person can move: where it lives, what it is called, and how far
 * it may go.
 *
 * Data rather than a screen, for the same reason `ShortcutTable` and `MatGuide`
 * are data. The panel renders this list, [StageTuning.sanitised] clamps against
 * this list, and the exporter names its fields off this list — so a knob cannot
 * be offered by a slider that the document will not accept, or accepted by a
 * document that the export forgets.
 */
class Knob(
    /** Which heading it appears under. */
    val group: String,
    /** Its JSON path, e.g. `camera.lens`. Also its stable id. */
    val path: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    /** Shown after the value: `°`, `x`, `cards`. Empty for a bare number. */
    val unit: String,
    /** One line on the panel saying what moving it does. */
    val note: String,
    val get: (StageTuning) -> Float,
    private val write: (StageTuning, Float) -> StageTuning,
) {
    /** Writes [value] in, clamped and made finite. Never throws and never stores NaN. */
    fun set(document: StageTuning, value: Float): StageTuning =
        write(document, if (value.isFinite()) value.coerceIn(min, max) else get(StageTuning.DEFAULT))

    /** Where in `min..max` a value sits, for a slider that speaks in fractions. */
    fun fractionOf(value: Float): Float =
        if (max <= min) 0f else ((value - min) / (max - min)).coerceIn(0f, 1f)

    /** The value at [fraction] of the way along, snapped to [step]. */
    fun valueAt(fraction: Float): Float {
        val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
        if (step <= 0f) return raw
        return (kotlin.math.round(raw / step) * step).coerceIn(min, max)
    }
}

/**
 * Every knob the panel offers, in the order it offers them.
 *
 * Kept short on purpose. The play stage has something like two hundred numbers
 * in it and almost all of them are load-bearing for a named test; these are the
 * ones a person can look at a picture and have an opinion about.
 */
object StageKnobs {

    const val CAMERA = "Camera"
    const val FOCUS = "Focus"
    const val HAND = "Hand"
    const val CARDS = "Cards"
    const val ROOM = "Room"

    val ALL: List<Knob> = listOf(
        Knob(
            CAMERA, "camera.yawDegrees", "Yaw", -180f, 180f, 1f, "°",
            "Round the table. Zero is square on.",
            { it.camera.yawDegrees }, { d, v -> d.copy(camera = d.camera.copy(yawDegrees = v)) },
        ),
        Knob(
            CAMERA, "camera.pitchDegrees", "Pitch",
            CameraEnvelope().minPitch, CameraEnvelope().maxPitch, 0.5f, "°",
            "How far the table is laid back. Zero is flat overhead; eighty is level enough to put the horizon on screen, and card text keystones long before it.",
            { it.camera.pitchDegrees }, { d, v -> d.copy(camera = d.camera.copy(pitchDegrees = v)) },
        ),
        Knob(
            CAMERA, "camera.distance", "Distance",
            CameraEnvelope().minDistance, CameraEnvelope().maxDistance, 0.01f, "x",
            "Where you stand, and the only dial that changes the perspective. It is held out by Close limit, and the lower the pitch the further out that holds you.",
            { it.camera.distance }, { d, v -> d.copy(camera = d.camera.copy(distance = v)) },
        ),
        Knob(
            CAMERA, "camera.lens", "Focal length", CameraEnvelope().minLens, CameraEnvelope().maxLens, 0.02f, "x",
            "Magnification. The board gets bigger; the perspective does not move, and neither does how close you may sit. Past 1.0 it can crop, as a zoom does.",
            { it.camera.lens }, { d, v -> d.copy(camera = d.camera.copy(lens = v)) },
        ),
        Knob(
            CAMERA, "camera.panX", "Pan across", -CameraEnvelope().maxPan, CameraEnvelope().maxPan, 0.01f, "x",
            "What the camera is aimed at, across the table. The camera orbits this point and draws it in the middle of the screen; a seat button puts it back.",
            { it.camera.panX }, { d, v -> d.copy(camera = d.camera.copy(panX = v)) },
        ),
        Knob(
            CAMERA, "camera.panY", "Pan along", -CameraEnvelope().maxPan, CameraEnvelope().maxPan, 0.01f, "x",
            "The same, up and down the table. Aiming away from the middle puts a corner further off, so it holds Distance out a little.",
            { it.camera.panY }, { d, v -> d.copy(camera = d.camera.copy(panY = v)) },
        ),
        Knob(
            CAMERA, "camera.shiftX", "Aim across", -CameraEnvelope().maxShift, CameraEnvelope().maxShift, 0.01f, "x",
            "Where what the camera is aimed at lands in the picture, across the glass. A lens shift, not a pan: the camera does not move, so nothing about the light or how close you may sit changes.",
            { it.camera.shiftX }, { d, v -> d.copy(camera = d.camera.copy(shiftX = v)) },
        ),
        Knob(
            CAMERA, "camera.shiftY", "Aim up the glass", -CameraEnvelope().maxShift, CameraEnvelope().maxShift, 0.01f, "x",
            "The same, down the glass. Positive slides the board down and gives the room the top of the picture — but the board already fills the stage, so what it costs is the hand along the bottom.",
            { it.camera.shiftY }, { d, v -> d.copy(camera = d.camera.copy(shiftY = v)) },
        ),
        Knob(
            CAMERA, "camera.clearance", "Close limit",
            CameraEnvelope.MIN_CLEARANCE, CameraEnvelope.MAX_CLEARANCE, 0.01f, "",
            "How far toward the lens the near corner of the table may come, and the only limit left on this camera that is not taste. One is the corner touching the lens, where the projection stops being invertible, so it stops a hundredth short.",
            { it.camera.clearance }, { d, v -> d.copy(camera = d.camera.copy(clearance = v)) },
        ),

        Knob(
            FOCUS, "focus.strength", "Defocus", 0f, 0.5f, 0.005f, "",
            "A contrast falloff with depth, not a blur. Zero is off, and it is always off in the Minimal room. Past about 0.3 the far cards stop being readable.",
            { it.focus.strength }, { d, v -> d.copy(focus = d.focus.copy(strength = v)) },
        ),
        Knob(
            FOCUS, "focus.depth", "Focus plane", -1f, 1f, 0.02f, "",
            "Where it is sharp, across the board's depth. Minus one is the far edge, plus one the near.",
            { it.focus.depth }, { d, v -> d.copy(focus = d.focus.copy(depth = v)) },
        ),
        Knob(
            FOCUS, "focus.fNumber", "Aperture", 2f, 22f, 0.5f, "f/",
            "How fast it falls away from the focus plane. Smaller is shallower.",
            { it.focus.fNumber }, { d, v -> d.copy(focus = d.focus.copy(fNumber = v)) },
        ),

        Knob(
            HAND, "hand.leanDegrees", "Lean", -45f, 0f, 1f, "°",
            "How far a hand card stands up off the felt. It pivots on its bottom edge.",
            { it.hand.leanDegrees }, { d, v -> d.copy(hand = d.hand.copy(leanDegrees = v)) },
        ),
        Knob(
            HAND, "hand.liftFactor", "Lift", 1f, 1.6f, 0.02f, "x",
            "A multiplier on the lift the lean solves for. One is exactly on the felt; below it the card sinks into the table.",
            { it.hand.liftFactor }, { d, v -> d.copy(hand = d.hand.copy(liftFactor = v)) },
        ),
        Knob(
            HAND, "hand.stepFraction", "Spacing", 0.3f, 1.1f, 0.01f, "cards",
            "The gap between hand cards, in card widths. Below about a half they overlap.",
            { it.hand.stepFraction }, { d, v -> d.copy(hand = d.hand.copy(stepFraction = v)) },
        ),
        Knob(
            HAND, "hand.liftRatio", "Read height", 0.6f, 2.6f, 0.02f, "x",
            "How far a card raised to be read comes up, against a carried one.",
            { it.hand.liftRatio }, { d, v -> d.copy(hand = d.hand.copy(liftRatio = v)) },
        ),

        Knob(
            CARDS, "cards.carryLift", "Carry height", 0.2f, 1.2f, 0.01f, "cards",
            "How far a card floats while it is being moved. Its shadow walks out linearly with this.",
            { it.cards.carryLift }, { d, v -> d.copy(cards = d.cards.copy(carryLift = v)) },
        ),
        Knob(
            CARDS, "cards.fanLiftRatio", "Spread height", 0.3f, 1.4f, 0.01f, "x",
            "How far a searched pile floats over the board, against a carried card.",
            { it.cards.fanLiftRatio }, { d, v -> d.copy(cards = d.cards.copy(fanLiftRatio = v)) },
        ),
        Knob(
            CARDS, "cards.peekLift", "Peek height", 0.6f, 2.4f, 0.02f, "cards",
            "How far a held card comes off the table to be read.",
            { it.cards.peekLift }, { d, v -> d.copy(cards = d.cards.copy(peekLift = v)) },
        ),
        Knob(
            CARDS, "cards.peekScale", "Peek size", 1f, 2.6f, 0.02f, "x",
            "And how much bigger it gets while it is up there.",
            { it.cards.peekScale }, { d, v -> d.copy(cards = d.cards.copy(peekScale = v)) },
        ),

        // The room, at the widest ranges the geometry survives rather than the
        // narrowest that stay tasteful. kai asked for maximum ranges, and an
        // instrument whose slider stops before the answer is worse than none:
        // the point of moving a number on the device is to find out where it
        // stops working, and a knob that will not go there cannot tell you.
        Knob(
            ROOM, "room.deskDepth", "Table depth", 0f, 6f, 0.05f, "cards",
            "How far the desk reaches toward you — and the wall goes back by the same amount.",
            { it.room.deskDepth }, { d, v -> d.copy(room = d.room.copy(deskDepth = v)) },
        ),
        Knob(
            ROOM, "room.deskSpan", "Table width", 0.8f, 4f, 0.05f, "x",
            "As a share of the screen. Under one and both ends of the desk are in frame.",
            { it.room.deskSpan }, { d, v -> d.copy(room = d.room.copy(deskSpan = v)) },
        ),
        Knob(
            ROOM, "room.wallBack", "Wall distance", 0.1f, 8f, 0.05f, "cards",
            "How far the wall stands past the mat, before the table depth pushes it further.",
            { it.room.wallBack }, { d, v -> d.copy(room = d.room.copy(wallBack = v)) },
        ),
        Knob(
            ROOM, "room.windowSpan", "Window width", 0.5f, 12f, 0.05f, "cards",
            "How wide the hole in the wall is. Wider than the wall and it is clamped to it.",
            { it.room.windowSpan }, { d, v -> d.copy(room = d.room.copy(windowSpan = v)) },
        ),
        Knob(
            ROOM, "room.windowAt", "Window across", -0.6f, 1.6f, 0.01f, "",
            "Where its centre sits, across the mat. Zero is the mat's left edge, one its right.",
            { it.room.windowAt }, { d, v -> d.copy(room = d.room.copy(windowAt = v)) },
        ),
        Knob(
            ROOM, "room.windowSill", "Window sill", 0f, 3f, 0.01f, "tall",
            "How high the bottom of the window is, in card heights above the desk.",
            { it.room.windowSill }, { d, v -> d.copy(room = d.room.copy(windowSill = v)) },
        ),
        Knob(
            ROOM, "room.windowHead", "Window head", 0.1f, 3.2f, 0.01f, "tall",
            "And the top. It cannot pass the wall, and it cannot go under the sill.",
            { it.room.windowHead }, { d, v -> d.copy(room = d.room.copy(windowHead = v)) },
        ),
        Knob(
            // Measured from the mat's *right* edge, so reaching the left means
            // crossing the whole board: about nine card widths on a landscape
            // tablet, and more on a taller stage. The range was -4 to 6, which
            // could not express the thing its own note promised — kai's -3.55 is
            // a value that reads as "well to the left" and lands, clamped, on the
            // right of the mat, because `lampFoot` will not let it stand over the
            // felt. Widened rather than re-centred on the nearer edge: that would
            // be a better axis and it would also move every stored value,
            // including his, on the next launch.
            ROOM, "room.lampOut", "Lamp across", -14f, 6f, 0.05f, "cards",
            "How far right of the mat the lamp stands. Past about -9 it crosses to the left.",
            { it.room.lampOut }, { d, v -> d.copy(room = d.room.copy(lampOut = v)) },
        ),
        Knob(
            ROOM, "room.lampAlong", "Lamp along", -0.5f, 1.5f, 0.01f, "",
            "And how far down the mat. Zero is level with its far edge, one with the near.",
            { it.room.lampAlong }, { d, v -> d.copy(room = d.room.copy(lampAlong = v)) },
        ),
        Knob(
            ROOM, "room.lampScale", "Lamp size", 0.2f, 3f, 0.02f, "x",
            "How stout it is: every radius at once. The mast has its own slider.",
            { it.room.lampScale }, { d, v -> d.copy(room = d.room.copy(lampScale = v)) },
        ),
        Knob(
            ROOM, "room.lampMast", "Lamp height", 0.6f, 5f, 0.02f, "cards",
            "The pole. Where the shade's top is drawn — not where the light is, which is solved.",
            { it.room.lampMast }, { d, v -> d.copy(room = d.room.copy(lampMast = v)) },
        ),
    )

    /** The knobs of one group, in order. */
    fun of(group: String): List<Knob> = ALL.filter { it.group == group }

    val GROUPS: List<String> = listOf(CAMERA, FOCUS, HAND, CARDS, ROOM)
}

/**
 * The numbers the panel shows and will not let you move.
 *
 * Exported so a paste-back is a complete picture rather than fifteen sliders out
 * of context. Everything here either re-solves a layout, is pinned by a named
 * test, or both — `docs/classic/TUNING.md` says which. They are read straight off the
 * live constants rather than typed again, so this cannot drift from the build it
 * came out of; a reference table that lies is worse than none.
 */
@Serializable
data class StageReference(
    val envelope: EnvelopeReference = EnvelopeReference(),
    val seats: Map<String, CameraTune> =
        StageSeat.entries.associate { it.name to CameraTune.of(it.pose) },
    val tiltDefault: Float = StagePlane.TILT,
    val homeDistance: Float = CameraPose.HOME_DISTANCE,
    val safeDepth: Float = StagePlane.SAFE_DEPTH,
    /**
     * The room's own read-only number, and the one the [RoomTune] knobs are
     * measured against.
     *
     * How much of the stage's height the board declines to use, so there is
     * somewhere for the room to be. It is the one thing about the room that
     * re-solves the *layout*, which is what keeps it off the panel and here
     * instead — a slider on it would re-key `pointerInput(layout)` mid-drag and
     * could take the card width under `BoardLayouter.MIN_CARD_WIDTH`, which
     * unmounts the stage.
     */
    val roomAbove: Float = Scenery.ROOM_ABOVE,
    /** How tall a piece of the room may stand, in card heights. */
    val roomCeiling: Float = Scenery.WALL_CEILING,
)

@Serializable
data class EnvelopeReference(
    val minPitch: Float = CameraEnvelope().minPitch,
    val maxPitch: Float = CameraEnvelope().maxPitch,
    val minDistance: Float = CameraEnvelope().minDistance,
    val maxDistance: Float = CameraEnvelope().maxDistance,
    val minLens: Float = CameraEnvelope().minLens,
    val maxLens: Float = CameraEnvelope().maxLens,
    val clearance: Float = CameraEnvelope().clearance,
    val maxPan: Float = CameraEnvelope().maxPan,
    val maxShift: Float = CameraEnvelope().maxShift,
)
