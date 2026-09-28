package com.kaiharimoto.mastertool.ui.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import coil3.compose.AsyncImage
import com.kaiharimoto.mastertool.core.board.BoardCard
import com.kaiharimoto.mastertool.core.board.CarryHeight
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DragOrigin
import com.kaiharimoto.mastertool.core.board.DropIntent
import com.kaiharimoto.mastertool.core.board.MatPoint
import com.kaiharimoto.mastertool.core.board.PlacedCard
import com.kaiharimoto.mastertool.core.board.PlayField
import com.kaiharimoto.mastertool.core.board.toPixels
import com.kaiharimoto.mastertool.core.layout.BoardLayout
import com.kaiharimoto.mastertool.core.layout.FanSpread
import com.kaiharimoto.mastertool.core.layout.HandFan
import com.kaiharimoto.mastertool.core.layout.HandRow
import com.kaiharimoto.mastertool.core.layout.BoardLayouter
import com.kaiharimoto.mastertool.core.layout.BoardSlot
import com.kaiharimoto.mastertool.core.layout.Slot
import com.kaiharimoto.mastertool.core.layout.CameraRig
import com.kaiharimoto.mastertool.core.layout.MatControl
import com.kaiharimoto.mastertool.core.layout.MatControls
import com.kaiharimoto.mastertool.core.layout.PileFan
import com.kaiharimoto.mastertool.core.layout.StageSeat
import com.kaiharimoto.mastertool.core.layout.planeFor
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.motion.Pose3
import com.kaiharimoto.mastertool.core.motion.Settle
import com.kaiharimoto.mastertool.core.motion.SpringSpec
import com.kaiharimoto.mastertool.core.motion.Vec2
import com.kaiharimoto.mastertool.core.motion.Vec3
import com.kaiharimoto.mastertool.core.haptics.Haptic
import com.kaiharimoto.mastertool.core.perf.FrameProbe
import com.kaiharimoto.mastertool.core.scene.DeskClock
import com.kaiharimoto.mastertool.core.scene.DeskLight
import com.kaiharimoto.mastertool.core.scene.Scene
import com.kaiharimoto.mastertool.core.tune.CameraTune
import com.kaiharimoto.mastertool.core.tune.StageTuning
import com.kaiharimoto.mastertool.core.tune.HandTune
import androidx.compose.ui.platform.LocalWindowInfo
import com.kaiharimoto.mastertool.core.tune.TuningSurface
import com.kaiharimoto.mastertool.core.layout.Turns
import com.kaiharimoto.mastertool.core.render.Defocus
import com.kaiharimoto.mastertool.core.tune.FocusTune
import com.kaiharimoto.mastertool.core.scene.Scenery
import com.kaiharimoto.mastertool.core.render.CardSolid
import com.kaiharimoto.mastertool.core.render.CardStock
import com.kaiharimoto.mastertool.core.render.Homography
import com.kaiharimoto.mastertool.core.render.Rot3
import com.kaiharimoto.mastertool.core.render.StageRig
import com.kaiharimoto.mastertool.ui.components.CARD_ASPECT_RATIO
import com.kaiharimoto.mastertool.ui.components.CardBack
import com.kaiharimoto.mastertool.ui.components.LocalCardBack
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckLayoutState
import com.kaiharimoto.mastertool.core.input.ShortcutAction
import com.kaiharimoto.mastertool.core.input.ShortcutContext
import com.kaiharimoto.mastertool.core.input.ShortcutLayer
import com.kaiharimoto.mastertool.ui.fx.DeviceTilt
import com.kaiharimoto.mastertool.ui.fx.LocalFeedback
import com.kaiharimoto.mastertool.ui.fx.SoundEffect
import com.kaiharimoto.mastertool.ui.fx.localHour
import com.kaiharimoto.mastertool.ui.fx.rememberRefreshHz
import com.kaiharimoto.mastertool.ui.input.ShortcutHost
import com.kaiharimoto.mastertool.ui.theme.FOIL_REST
import com.kaiharimoto.mastertool.ui.theme.LocalPrismaticCards
import com.kaiharimoto.mastertool.ui.theme.MasterToolPalette
import com.kaiharimoto.mastertool.ui.theme.drawPrismaticInset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

/**
 * How far a carried card rises off the mat, as a share of its own height.
 *
 * Raised along with the tilt and the lens. What tells you a card is off the
 * table is not how much bigger it got — that is a few per cent at any sane
 * camera distance — it is how far its shadow has walked out from under it, and
 * that is linear in this number.
 */
// val now lives in `StageTuning.DEFAULT` — see `core/tune/StageTuning.kt`.

/** A card raised to be *read* comes closer than one being slid. */
// val now lives in `StageTuning.DEFAULT` — see `core/tune/StageTuning.kt`.

/**
 * How far a spread-out pile floats over the board it is covering.
 *
 * Lower than a card in the hand and higher than one resting, which is exactly
 * what it is: a spread held over the table while you look through it. The lift
 * is doing real work rather than decorating — it is what gives every card in the
 * fan a cast shadow, and those shadows are the only thing separating the fan
 * from the board underneath. The alternative was dimming the board, and a
 * translucent wash over content is the second line of §12.
 */
// val now lives in `StageTuning.DEFAULT` — see `core/tune/StageTuning.kt`.

/**
 * How far a hand card leans back, so hand and table read as two objects.
 *
 * A hand that is *standing up* off the table is the difference between five
 * cards laid out on a mat and five cards being held, and it is the cheapest
 * three-dimensionality on the whole stage.
 *
 * But **it has to pivot on the card's bottom edge, not its centre**, and getting
 * that wrong shipped. A rotation about the centre keeps the centre where it is
 * and swings the *near edge down*: at twenty-four degrees that put the bottom of
 * every hand card a fifth of a card height underneath the felt. Two things then
 * go wrong at once, and both of them are visible from across a room. The card is
 * buried in the table — which nobody could see while the mat was black, and
 * everybody could see the moment the mat became a surface. And its shadow turns
 * inside out: `Shadows.cast` slides each corner along the light until it reaches
 * the table, and for a corner already *below* the table that distance is
 * negative, so half the shadow quad folds back through itself.
 *
 * The third thing is subtler and is the reason the fix is a fix rather than a
 * patch. A card leaned about its centre does not look tilted. It looks
 * *compressed* — foreshortened by nine per cent and otherwise unchanged, because
 * nothing about it has moved relative to the surface it is on. Pivot it on the
 * bottom edge instead and the bottom stays welded to the felt while the top
 * lifts a third of a card height into the air, its shadow lies down behind it,
 * and it reads as a card standing in a hand. See [handLiftOf].
 */
// val now lives in `StageTuning.DEFAULT` — see `core/tune/StageTuning.kt`.

/**
 * The lift that turns the hand's lean from a rotation about the centre into a
 * rotation about the bottom edge.
 *
 * Pure trigonometry rather than a number somebody liked: raising the centre by
 * `(h/2)·sin θ` puts the near edge back on the felt exactly, for any lean and
 * any card size.
 *
 * This used to carry a paragraph saying the drawn card sits a little higher on
 * the glass than the box that grabs it, and that the offset "still lies well
 * inside its own footprint". It was true at the lean and lift the app shipped
 * with and false at kai's — minus 32 degrees against minus 24, and a lift
 * multiplier of 1.6 — where the offset reached 23 per cent of a card and made
 * the top of every hand card dead to touch, with the live band 76 screen pixels
 * *below* what you could see. A comment is not a guard, and a slider can
 * invalidate one without touching the file it is in.
 *
 * The hit test no longer takes anybody's word for it: `MatInput.handQuad` builds
 * the same four corners the renderer draws and asks whether the finger is inside
 * them. The geometry answers for itself, at any lean.
 */
internal fun handLiftOf(
    cardHeight: Float,
    bodyDepth: Float,
    hand: HandTune = StageTuning.DEFAULT.hand,
): Float {
    val lean = abs(hand.leanDegrees) * (PI.toFloat() / 180f)
    // The face's own half-height, plus the body hanging behind it — a card is a
    // slab and it is the *lowest point of the slab* that has to clear the felt,
    // not the lowest point of the printed side. Leaving the second term out
    // leaves one corner of the body a few pixels under the table, which is the
    // whole bug again in miniature and would fold one corner of the shadow.
    // And a multiplier over the top, floored at one by `StageKnobs` rather than
    // here, because a tuning that could go *below* the solved value is the same
    // shipped bug again: the near corner goes under the felt and `Shadows.cast`
    // folds half the quad back through itself.
    return (cardHeight / 2f * sin(lean) + bodyDepth * cos(lean)) * hand.liftFactor
}

/**
 * A peeked card comes off the table and turns to face the reader.
 *
 * Further than a carried card, because this one is being *read* rather than
 * moved, and it reveals its face even if it is set — which is the whole
 * question a held finger is asking. Nothing about the field changes, so it
 * goes back down exactly as it was.
 */
// val now lives in `StageTuning.DEFAULT` — see `core/tune/StageTuning.kt`.
// val now lives in `StageTuning.DEFAULT` — see `core/tune/StageTuning.kt`.

private val TOP_BAR = 44.dp

/** The hidden way in to the frame readout. `ChibiLogo`'s window, and its count. */
private const val READOUT_TAPS = 3
private const val READOUT_TAP_WINDOW_MS = 600L

/**
 * How often the desk asks what time it is, when it has been left to the clock.
 *
 * Ten minutes, which is six wake-ups an hour against a rule — nothing idles —
 * that is about the frame loop. A dusk the app sleeps through is a setting that
 * silently does not work, and the alternative to a slow timer is re-reading a
 * clock on every recomposition of a screen that recomposes for other reasons.
 */
private const val DUSK_POLL_MS = 10 * 60 * 1000L

/** What the bar's room button says, which is also the whole cycle written down. */
private fun roomLabel(scene: Scene, light: DeskLight): String = when (scene) {
    Scene.MINIMAL -> Scene.MINIMAL.displayName
    Scene.DESK -> "${Scene.DESK.displayName} · ${light.displayName}"
}

/** Long enough to read "To the graveyard", short enough not to become scenery. */
private const val ANNOUNCEMENT_MILLIS = 1_600L

/**
 * The play stage: a deck, a table, and nothing telling you what you may do.
 *
 * This is the fishbowl grown up. The goldfish screen dealt cards onto a
 * perspective surface and let you look at them; here you pick them up, put them
 * anywhere, turn them over, stack them, and sweep them into the graveyard —
 * with the rules of the game living entirely in your head, which is what a
 * table is.
 *
 * **One plane, and one invariant: draw order is depth.** The room first, then
 * every card in turn, each painting its own shadow, its own white edges and its
 * own picture before the next one is touched.
 *
 * That invariant used to be asserted rather than arranged. Nothing was sorted
 * by depth at all — the mat's cards were ordered by raw mat-space y, which
 * stops meaning anything the moment the table can turn, and the hand and the
 * four piles were appended afterwards in the order somebody typed them. The
 * graveyard is one row further from the camera than the deck and was painted
 * over it. And because the passes were *global* — every shadow, then every
 * edge, then every picture — no card could ever be occluded by a pile's wall,
 * whatever the order. Both are gone: `ordered` sorts the whole stage by
 * projected depth, and a card is one thing to paint.
 *
 * There used to be a second layer above this one, flat, holding whatever a hand
 * was carrying. It worked because there was only ever one camera angle, so
 * "flat" and "square to the reader" were the same thing and a card in the air
 * could live in the screen's frame while the felt lived in the mat's. A camera
 * that turns ends that — a carried card set in defence would stay square to the
 * glass while the table turned under it, and its own shadow would slide out
 * from beneath it. So there is one frame now. Height is carried by
 * `StagePlane.flatten`, which rewrites a point *with* a height as the point on
 * the felt that will look like it once the camera has run; that is what puts
 * the top card of a deck on top of the deck, and what keeps the card in your
 * hand turning with the table it left.
 *
 * Motion is one `withFrameNanos` loop over plain lists, with each card's pose
 * read inside its own `graphicsLayer`. Poses live in [StageCard] objects held by
 * the screen rather than remembered in composables, because a card picked up
 * changes parent — mat to air — which destroys and recreates its composable,
 * and a pose that reset at the moment of pickup would be a pose that reset on
 * the one frame it must not.
 *
 * **Everything with a height is real, and none of it is a 3D engine.** Cards
 * are solids in `core/render`: they have a thickness, a normal, an orientation
 * you can ask questions of, a light they respond to and a shadow they throw by
 * having each of their corners projected onto the felt. All of that arithmetic
 * lives in core where it is tested, and it reaches the screen through exactly
 * two things: the mat's own `graphicsLayer`, which is one real
 * perspective-correct divide applied to everything at once, and a canvas for
 * the geometry a layer cannot hold. `StagePlane.flatten` is the join: it turns
 * a point with a height into the point on the mat that will look like it once
 * the plane's own transform has run, so shadows, pile edges, card thickness —
 * *and each card's own picture* — can all be drawn by a canvas that only speaks
 * two dimensions.
 *
 * A card's picture is the newest member of that list and the one that was
 * wrong. It used to have a `graphicsLayer` of its own, with its own three
 * angles and its own divide, and a layer's divide is forced through the layer's
 * centre and then pasted flat into its parent — so a leaned card came out as a
 * picture lying *in* the table a few pixels away from its own drawn edges.
 * `Homography` replaces the four channels with the eight the map actually has;
 * see [StagedCard].
 */
@Composable
fun PlayScreen(
    state: DeckBuilderState,
    prefs: DeckLayoutState,
    onBack: () -> Unit,
    /**
     * The shuffle, when somebody needs to know it in advance.
     *
     * Null — the app's own answer — is a fresh shuffle every time the table is
     * opened, because a demo that dealt the same five cards to everybody you
     * showed it to would look broken. `:studio` passes a number instead, and
     * that is the whole of why this parameter exists: two pictures of two
     * different hands are not a before and an after, and a loop that judges a
     * change to the light against a different deal is a loop measuring noise.
     */
    seed: Long? = null,
) {
    val deck = state.deck
    val play = remember(deck.main, deck.extra, seed) {
        PlayState(deck.main, deck.extra, seed ?: Random.nextLong()) { id ->
            // The one question the table asks about a card's identity: which way
            // up a set copy of it lies. Null for a passcode the index has never
            // heard of, which `SetPosition` answers as a spell rather than by
            // guessing at a defence position for a card it cannot name.
            state.index.byId(id)?.category?.let { it == CardCategory.MONSTER }
        }
    }
    val cards = remember(deck.main, deck.extra) { mutableMapOf<Int, StageCard>() }
    // Above the shortcut host, because three of the shortcuts are seats. It
    // needs no surface to exist — `sync` tells it one as soon as there is a box.
    val camera = remember { StageCameraState(CameraRig(seat = StageSeat.TABLE)) }
    val feedback = LocalFeedback.current
    val back = LocalCardBack.current
    var menuFor by remember { mutableStateOf<DragOrigin?>(null) }

    // What is open in the reader, if anything. A `DragOrigin` and not a card,
    // so the panel is looking at the same thing the finger was — and so it can
    // be re-resolved against the live field rather than holding a snapshot of a
    // card that has since been sent to the graveyard.
    var reading by remember { mutableStateOf<DragOrigin?>(null) }
    var guide by remember { mutableStateOf(false) }

    // ---- the numbers a person has moved -------------------------------------
    //
    // Read as a plain value rather than watched: it is a preference, it changes
    // when a slider moves, and everything downstream of it is either a
    // `remember` key or something read inside a draw.
    val tune = prefs.preferences.stageTuning
    var tuner by remember { mutableStateOf(false) }


    // The camera opens where the tuning says, which is what "make it the
    // default" means for a camera — and moves under a slider, which is what
    // makes the slider worth dragging. `placeAt` rather than `aimAt`: the stage
    // should be *at* the pose, not springing toward it.
    //
    // A `SideEffect` and not a `LaunchedEffect`, and that distinction cost a
    // debugging round. A `LaunchedEffect` body is dispatched on a coroutine, so
    // *which frame* it lands on depends on the scheduler — and `:studio` pumps
    // frames by hand, so two runs of the identical tuning came back 77.7% of
    // pixels apart, the camera having been placed before the shot in one and
    // after it in the other. Determinism is the whole reason the studio can be
    // trusted to say what a change did. `SideEffect` runs synchronously after a
    // successful composition, so it cannot straddle a frame.
    //
    // The holder is a plain array rather than snapshot state on purpose: it is
    // written from inside the effect, and a `mutableStateOf` written there
    // schedules another recomposition for a value nothing reads.
    //
    // **Seeded null, and it used to be seeded with the defaults.** That looked
    // like a harmless way to say "nothing has been placed yet" and it was not:
    // on a fresh install the stored tuning *is* `StageTuning.DEFAULT`, so the
    // guard below compared the defaults against themselves, found them equal,
    // and never placed anything at all. The camera then stayed where the rig was
    // constructed — `CameraRig(seat = StageSeat.TABLE)` — so the seat the stage
    // opened at was Table, and every number in `CameraTune`'s defaults was dead
    // on exactly the devices that had never been tuned. Iteration 21 made kai's
    // own export those defaults; this is why it did not change what he opens on.
    //
    // Null is the only seed that cannot collide with a real tuning.
    val placed = remember { arrayOf<CameraTune?>(null) }
    SideEffect {
        // Only when the *tuning* moves. Comparing against the live rig instead
        // would re-place the camera on every frame of an orbit and the table
        // would fight the finger.
        if (placed[0] != tune.camera) {
            placed[0] = tune.camera
            // The limit before the pose it limits, and in that order: `placeAt`
            // clamps through the envelope, so writing the two the other way
            // round would put every pose one drag behind its own floor.
            camera.rig.envelope = tune.camera.envelope()
            camera.placeAt(tune.camera.pose())
        }
    }

    // ---- which room the table is in -----------------------------------------
    //
    // Two preferences and a clock, resolved to an hour. The room and its palette
    // are solved further down, beside the board layout, because a placed lamp's
    // position is in mat pixels and this does not know how big the board is yet.
    //
    // Both travel as ordinary parameters rather than as snapshot state, unlike
    // the camera's `eye` beside them, and `SceneRender.kt` carries the reason:
    // the eye changes sixty times a second and these change twice a day.
    val scene = prefs.preferences.scene
    val deskLight = prefs.preferences.deskLight

    // Whether the felt answers a finger at all. Read the same plain way, and
    // handed to the pilot below rather than to the arbiter: what is under a
    // finger is a question about the board, and so is whether the board is
    // willing to be asked.
    val cameraTouch = prefs.preferences.cameraTouch

    // And whether the tablet's own tilt moves the camera a degree or two.
    // `DeviceTilt` unregisters the sensor when this is false, so an install
    // that never turns it on never listens to anything.
    val headSway = prefs.preferences.headSway
    DeviceTilt(enabled = headSway) { camera.sway.onTilt(it) }
    // Taking the posture the device is in *now* as the zero, every time it is
    // switched on. Without it, turning it on while holding a tablet at the angle
    // people actually hold tablets would swing the camera to its limit and leave
    // it there, which reads as the feature being broken rather than as it being
    // on. `HeadSway.restart` says the same thing at greater length.
    LaunchedEffect(headSway) { camera.sway.restart() }

    // The hour, re-read on a slow timer rather than watched. This is not an
    // idle animation — it is a preference that happens to have a clock behind
    // it — and a room that stayed in daylight because the app was already open
    // at dusk would be the setting quietly not working.
    var hour by remember { mutableStateOf(localHour()) }
    LaunchedEffect(scene, deskLight) {
        if (scene != Scene.DESK || deskLight != DeskLight.AUTO) return@LaunchedEffect
        while (true) {
            delay(DUSK_POLL_MS)
            hour = localHour()
        }
    }
    val time = remember(deskLight, hour) { DeskClock.resolve(deskLight, hour) }

    // What the tuning was done on, so an exported number can be sanity-checked
    // against the screen that produced it. Metadata only — nothing derives from
    // it — which is why it is allowed to be a composable read rather than
    // something threaded through the geometry.
    val tuningSurface = TuningSurface(
        widthPx = LocalWindowInfo.current.containerSize.width,
        heightPx = LocalWindowInfo.current.containerSize.height,
        density = LocalDensity.current.density,
        scene = scene.name,
        light = time.name,
        seat = Turns.seatAt(camera.pose)?.label ?: "free",
    )

    /** Minimal, then the desk on the clock, then the two the clock cannot argue with. */
    val cycleScene: () -> Unit = {
        prefs.update {
            when {
                it.scene == Scene.MINIMAL -> it.copy(scene = Scene.DESK, deskLight = DeskLight.AUTO)
                it.deskLight == DeskLight.AUTO -> it.copy(deskLight = DeskLight.DAY)
                it.deskLight == DeskLight.DAY -> it.copy(deskLight = DeskLight.NIGHT)
                else -> it.copy(scene = Scene.MINIMAL)
            }
        }
    }

    // The instrument, and the one thing that reads it.
    //
    // Off by default and behind a gesture nobody finds by accident, because it
    // is not a feature — it is the answer to "is this actually fast", which
    // every claim about the stage has so far had to be argued rather than
    // measured. The rate comes from the panel rather than from an assumption:
    // 16.7ms is a comfortable frame at sixty hertz and a missed one at a
    // hundred and twenty, so a probe told the wrong number reports health on a
    // stage dropping half its frames.
    val refreshHz = rememberRefreshHz()
    val probe = remember(refreshHz) { FrameProbe(refreshHz = refreshHz) }
    var readout by remember { mutableStateOf(false) }
    // Written by the frame loop only while the overlay is up. `FrameProbe`'s
    // fields are deliberately plain, so nothing about the probe invalidates
    // anything — which is right for the ninety-nine per cent of the time the
    // overlay is off, and means the overlay has to be given a clock of its own.
    // Read inside a draw lambda and nowhere else, the way `EasterEgg` reads its
    // tick: it re-runs one draw, not a composition.
    var probeTick by remember { mutableStateOf(0L) }

    // The pointer half of the standing rule that every gesture ships with both
    // idioms. Only the actions that need no card selected are here: a keyboard
    // has no way to say "this one" on a table where cards are anywhere rather
    // than in numbered zones, so anything about a particular card stays on the
    // card — touched, or through its menu.
    ShortcutHost(
        context = ShortcutContext(topLayer = ShortcutLayer.PLAY),
        onAction = { action ->
            when (action) {
                ShortcutAction.PLAY_DRAW ->
                    if (play.move { it.draw() }) feedback.play(SoundEffect.DEAL, Haptic.DEAL)
                ShortcutAction.PLAY_SHUFFLE ->
                    if (play.shuffle(BoardSlot.Deck)) {
                        feedback.play(SoundEffect.SHUFFLE, Haptic.SHUFFLE)
                    }
                // The keyboard half of the fan. It names the deck because a
                // keyboard has no way to say "this pile" — the same reason every
                // other play shortcut is about the table rather than about a
                // card — and the deck is the pile anybody wanting a key for this
                // is reaching for.
                ShortcutAction.PLAY_SEARCH_DECK ->
                    if (play.fanned != null) {
                        if (play.closeFan()) feedback.play(SoundEffect.SHUFFLE, Haptic.SHUFFLE)
                    } else if (play.field.deck.isNotEmpty()) {
                        play.fan(DragOrigin.Pile(BoardSlot.Deck, 0))
                        feedback.play(SoundEffect.SLIDE, Haptic.SLIDE)
                    }
                ShortcutAction.PLAY_NEW_HAND -> play.restart()
                ShortcutAction.PLAY_NEXT_PHASE -> play.move { it.nextPhase() }
                ShortcutAction.PLAY_END_TURN -> play.move { it.endTurn() }
                ShortcutAction.UNDO -> play.undo()
                ShortcutAction.REDO -> play.redo()
                ShortcutAction.PLAY_SEAT_OVERHEAD -> camera.sitAt(StageSeat.OVERHEAD)
                ShortcutAction.PLAY_SEAT_TABLE -> camera.sitAt(StageSeat.TABLE)
                ShortcutAction.PLAY_SEAT_SEATED -> camera.sitAt(StageSeat.SEATED)
                ShortcutAction.PLAY_SEAT_POV -> camera.sitAt(StageSeat.POV)
                ShortcutAction.PLAY_HEAD_SWAY ->
                    prefs.update { it.copy(headSway = !it.headSway) }
                ShortcutAction.PLAY_SCENE -> cycleScene()
                ShortcutAction.PLAY_CAMERA_TOUCH ->
                    prefs.update { it.copy(cameraTouch = !it.cameraTouch) }
                ShortcutAction.PLAY_GUIDE -> guide = !guide
                // Outward one layer at a time, and the guide is the outermost
                // thing over the table: Esc with it open should close it, not
                // walk out of the stage and lose the board.
                ShortcutAction.DISMISS -> when {
                    guide -> guide = false
                    // Above the tuner and below the guide. The reader is a modal
                    // over the table with a scrim of its own, so it is the first
                    // thing Esc should take away — and it is the only one of
                    // these a person opens by *holding still*, which means it is
                    // the one most often open by accident.
                    reading != null -> reading = null
                    // Below the guide and above everything on the mat: the panel
                    // is over the table but the guide is over both.
                    tuner -> tuner = false
                    menuFor != null -> menuFor = null
                    // A spread pile is a layer over the table too, and Esc with
                    // one open should square it up rather than walk out of the
                    // stage and lose the board.
                    play.fanned != null ->
                        if (play.closeFan()) feedback.play(SoundEffect.SHUFFLE, Haptic.SHUFFLE)
                    else -> onBack()
                }
                else -> Unit
            }
        },
    ) {
    Box(Modifier.fillMaxSize().background(MasterToolPalette.Ink)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = TOP_BAR)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }

            SideEffect { camera.sync(widthPx, heightPx) }

            val layout = remember(widthPx, heightPx, scene) {
                BoardLayouter.solve(
                    width = widthPx,
                    height = heightPx,
                    aspectRatio = CARD_ASPECT_RATIO,
                    // The desk is in a room and the room has to go somewhere.
                    // Minimal has no room, so it gets the whole stage and is
                    // bit-identical to every build before this one — which is
                    // the control the desk is judged against.
                    roomAbove = when (scene) {
                        Scene.DESK -> Scenery.ROOM_ABOVE
                        Scene.MINIMAL -> 0f
                    },
                    // The seat the stage opens at, and deliberately not the live
                    // camera. Handing the fitter a growth that changes every
                    // frame re-solves the whole board every frame: every card
                    // resizes, all sixty seats re-target, and `fits` can flip
                    // below MIN_CARD_WIDTH mid-gesture and unmount the stage.
                    // Handing it the *worst* growth the camera could ever reach
                    // is no better — that is the mat's diagonal, and it would
                    // cost a fifth of every card forever to buy an angle that
                    // might never be used. So the layout is solved once, for
                    // this seat, and the camera is free to be anywhere else.
                    //
                    // It used to say "the camera obeys it: CameraFit dollies
                    // back instead", and that is no longer true and was the
                    // whole of kai's "it locks me out from getting closer". The
                    // fitter runs when somebody *presses a seat* now — see
                    // `StageCameraState.sitAt` — which is the one time being put
                    // back where the board fits is what was asked for.
                    perspectiveGrowth = StageSeat.TABLE.pose
                        .planeFor(widthPx, heightPx)
                        .perspectiveGrowth,
                )
            }

            // What a seat press has to put back on the glass. Assigned rather
            // than passed, because `PlayTopBar` is outside this `BoxWithConstraints`
            // and the alternative is threading a `Slot` through a bar that takes
            // nine parameters already.
            SideEffect { camera.field = layout.field }

            // Solved once per room and per surface, never per frame. The same
            // discipline the board layout above obeys, and for the same reason:
            // this is geometry, and geometry that is re-derived while the camera
            // moves is geometry that can change under a gesture.
            //
            // The room's tuning is a key, and that is what makes it tunable at
            // all. `docs/classic/TUNING.md` bars anything that re-solves the *layout*,
            // because the mat is one `pointerInput(layout)` and a re-solve tears
            // the gesture arbiter's event stream down mid-drag. This is one
            // `remember` below that: the layout above is untouched, so the board,
            // the hit boxes and the gesture in flight all stay exactly where they
            // were while the desk moves.
            // Compiled once for the life of the screen, like the felt and the grain.
    // Null below API 33, on a refusing driver, or on a typo — and null here is
    // simply the ungraded picture.
    val lens = remember { StageLens.compile() }

    val scenery = remember(scene, time, layout, widthPx, heightPx, tune.room) {
                Scenery.of(scene, time, layout, widthPx, heightPx, tune.room)
            }
            // The palette and the rig, together. The rig is solved beside the
            // room rather than looked up from the scene, because a placed lamp's
            // position is in mat pixels and mat pixels move when the board
            // resizes — so this changes twice a day *and* on a resize, which is
            // still nothing like per frame.
            val look = remember(scene, time, scenery) {
                StageLook.of(scene, time, scenery.lighting)
            }

            if (!layout.fits) {
                Text(
                    "Not enough room to lay a table out here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                return@BoxWithConstraints
            }

            // `tune` is in the key deliberately. A card knob that is not here does
            // nothing until the user next touches the board, which reads as the
            // slider being broken rather than as a missing key.
            val seats = remember(
                play.field, layout, play.carries, play.peeking, play.fanned,
                play.declared, tune,
            ) {
                // The camera is read as a plain field, not as observable state:
                // this must not re-run sixty times a second while the table is
                // turning. The cost is that a card already held up does not
                // re-face if the camera moves under it, which is a moment when
                // nobody is moving the camera.
                seatsFor(
                    field = play.field,
                    layout = layout,
                    carries = play.carried,
                    peeking = play.peeking,
                    fanned = play.fanned,
                    declared = play.declared?.id,
                    facingReader = Rot3.facingViewer(
                        camera.pose.pitchDegrees,
                        camera.pose.yawDegrees,
                    ),
                    tune = tune,
                    hand = play.handRow,
                    spread = play.fanned?.let { fanOf(play, layout, it) },
                )
            }

            // Painter's order: far to near, and it is the only thing on this
            // stage that says what is in front of what.
            //
            // It is sorted **here** rather than in `seatsFor` because the answer
            // depends on where the camera is and the seats do not. `seatsFor` is
            // remembered against the field, and rightly — turning the table does
            // not move a card. What turning the table does change is which cards
            // are behind which, and asking `seatsFor` that question would re-solve
            // sixty seats on every frame of an orbit.
            //
            // `derivedStateOf` is the whole of the trick and the reason this is
            // affordable. The calculation reads `camera.plane`, so it re-runs on
            // every frame the camera moves — sixty projections and a sort, which
            // is nothing — but a *structural* policy means the composition is only
            // invalidated on the frames the resulting order actually differs. Over
            // a full sweep round the table that is a handful of frames out of
            // hundreds, and on every other frame this costs one comparison.
            //
            // Depth rather than mat-space y, which is what it used to be. `y` is
            // the right answer only while the table faces you: spin it half a turn
            // and the far edge is the near one, and every card on the board is
            // sorted backwards. `StagePlane.project` already computes the number
            // that is true at every angle, and until now nothing read it.
            val ordered by remember(seats, layout, camera) {
                derivedStateOf(structuralEqualityPolicy()) {
                    val plane = camera.plane
                    val quantum = layout.field.height * DEPTH_QUANTUM
                    // One depth for the whole hand, taken at the band it is
                    // fanned along. Every card in it then ties, and the tie is
                    // broken by the position in the fan.
                    val handDepth = quantised(
                        plane.project(layout.hand.centerX, layout.hand.centerY, 0f).depth,
                        quantum,
                    )
                    seats.sortedWith(
                        compareBy(
                            // Three bands, and the order between them is not up
                            // for arithmetic. A spread pile is held over the
                            // board, and whatever is in your hand is between you
                            // and both — a card lifted over the far edge of the
                            // table is still the one you are holding.
                            { if (it.carried) 2 else if (it.fanned) 1 else 0 },
                            // Depth within a band, except inside a fan, where
                            // every card is at one height and the order is the
                            // one the spread was laid out in. Asking the
                            // projection instead is what made the overlap
                            // reshuffle itself every time the table turned.
                            // Depth within a band, except inside a fan or a
                            // hand — where every card is at one height and the
                            // order is the one they are held in. Asking the
                            // projection instead is what made a spread reshuffle
                            // itself every time the table turned, and the hand
                            // was left doing exactly that: its cards differ in
                            // x, so the moment the table yaws their depths
                            // separate and the fan reorders under your hands.
                            {
                                when {
                                    it.fanned -> 0
                                    it.inHand -> handDepth
                                    else -> quantised(plane.project(it.pose.position).depth, quantum)
                                }
                            },
                            // Then the spread's own order, or — for two cards in
                            // the same place on the mat — recency, which is the
                            // reason `mat` is ordered the way it is.
                            { it.order },
                        ),
                    )
                }
            }

            // What the last release did, said once and then withdrawn. Left up,
            // it stops being news and starts being furniture that lies about
            // what just happened.
            play.announcement?.let { said ->
                LaunchedEffect(said) {
                    delay(ANNOUNCEMENT_MILLIS)
                    if (play.announcement == said) play.announcement = null
                }
            }

            // A declaration says the card's *name*, which is the whole content
            // of "I am using this" and the one thing the sound cannot carry.
            // Keyed on the count rather than on the card, so declaring the same
            // card twice in a chain is two announcements — which is exactly what
            // a chain is, and what a bare id could never have shown.
            play.declared?.let { said ->
                LaunchedEffect(said.count) {
                    play.announcement = state.index.byId(said.cardId)?.name
                    delay(DECLARE_MILLIS)
                    if (play.declared?.count == said.count) play.clearDeclaration()
                }
            }

            // The playmat's cloth. Compiled once for the life of the screen —
            // it costs milliseconds and the result is immutable — and null on
            // any platform that has no runtime shader, which is every Android
            // below 33 and any driver that refuses. Null draws the mat that
            // shipped, which is the whole contract of the seam.
            val weave = remember { FeltWeave.compile() }
            val wood = remember { WoodGrain.compile() }

            // Both clocks report here, so a gesture the frame loop decides acts
            // on the same card the press landed on.
            val pilot = remember(play, feedback) {
                MatPilot(play, feedback, layout, tune)
            }
            SideEffect {
                pilot.layout = layout
                // Refreshed, never captured and never in the gesture loop's key:
                // the hand's hit boxes have to move with the hand, and re-keying
                // `pointerInput` is what tears the arbiter's event stream.
                pilot.tune = tune
                // Asked before it opens rather than discovered when it draws.
                // A `DropdownMenu` with nothing in it is not nothing — it is a
                // three-pixel box that appears, sits there, and has to be
                // dismissed, which is a worse answer than the gesture having
                // done nothing at all.
                pilot.onMenu = { if (hasMenu(it)) menuFor = it }
                pilot.onRead = { reading = it }
                pilot.camera = camera
                // Refreshed here rather than captured, for the reason above and
                // for one more: it is a preference, so it changes under a live
                // arbiter every time the button on the bar is pressed.
                pilot.cameraTouch = cameraTouch
            }

            // Where a card the stage has never seen before comes from. Placing
            // it at its destination instead means a drawn card materialises in
            // the hand already there, which reads as a redraw of the screen
            // rather than as a card being dealt — and the opening five, all new
            // at once, are the first thing anyone sees.
            // Keyed on the fanned pile as well, because a pile spread out to be
            // searched is forty cards the stage has never seen, and every one of
            // them should come *out of that pile* — which is the spread, and is
            // worth watching.
            val dealFrom = remember(layout, play.fanned, play.field) {
                // Whatever is spread out is where its cards come from: a pile's
                // square, or the card a stack is spread out from under. A card
                // the stage has never seen before flies out of the thing it came
                // out of, which for a search is the spread itself and is worth
                // watching.
                val from = when (val what = play.fanned) {
                    is DragOrigin.Pile -> layout[what.pile]
                    is DragOrigin.Mat -> play.field.placed(what.id)
                        ?.let { layout.toPixels(it.at) }
                        ?.let { (x, y) -> Slot(x, y, 0f, 0f) }
                    else -> layout[BoardSlot.Deck]
                }
                Pose3(
                    position = Vec3(
                        from?.centerX ?: layout.field.centerX,
                        from?.centerY ?: layout.field.centerY,
                        0f,
                    ),
                    rotY = 180f,
                )
            }

            // Forget every card that no longer has a seat, and that is a bug fix
            // rather than housekeeping.
            //
            // `cards` had no removal of any kind, so a `StageCard` outlived
            // every seat it ever had. Tap the deck and all forty of its cards
            // get a seat at a `PileFan` coordinate — and a fan is by
            // construction a cloud of poses centred on `layout.field`, the
            // middle of the board. Close it and thirty-nine of them lose their
            // seat: not drawn any more, but still in this map, still stepped
            // every frame, **still parked in the middle of the board**. The next
            // time one of them reappears — revealed as the new top of the deck,
            // sent to the graveyard, played to the mat — `getOrPut` finds the
            // stale object, `dealFrom` is never consulted, and the card springs
            // in from the centre of the screen. kai's word for it was "an
            // invisible fan", and that is exactly what it was.
            //
            // Pruning is safe precisely because a seatless card is not drawn
            // (`StagedCard` returns early on a missing entry), so removing one
            // mid-flight cannot be seen. What it buys is that the next
            // appearance is built fresh at `dealFrom` — which with no fan open
            // is the deck, so a revealed top card simply *is* there and a card
            // sent to the graveyard travels from the deck, which is where it
            // came from.
            cards.keys.retainAll(seats.mapTo(mutableSetOf()) { it.id })

            // Aim every card at where it now belongs. Done outside the frame
            // loop because it only changes when the board does.
            seats.forEach { seat ->
                val card = cards.getOrPut(seat.id) {
                    StageCard(seat.id).also { it.placeAt(seat.bornAt ?: dealFrom) }
                }
                card.pinned = seat.pinned
                card.cardWidth = seat.width
                card.aimAt(seat.pose)
            }

            // ---- the one loop -------------------------------------------------
            //
            // Keyed on the two things it drives, and that is a bug fix rather
            // than a precaution. `cards` and `pilot` are both remembered
            // *against the deck* — a different deck is a different table, with
            // different instance ids — while this loop used to be keyed on
            // `Unit`. So the deck changing under an open stage left the loop
            // holding the previous map: every card in the new one stayed parked
            // at the pose it was dealt from, the five in the opening hand
            // included, stacked invisibly on the deck they came out of. The
            // camera kept moving throughout, because it is remembered against
            // nothing and the loop still had the live one — which is exactly why
            // this read as "the hand is missing" rather than as "the stage is
            // frozen".
            //
            // It never appeared in ordinary use: you pick a deck and *then* open
            // the table, so the first composition already had the right map. It
            // appears the moment anything hands this screen a deck while it is
            // already up.
            LaunchedEffect(cards, pilot) {
                var previous = 0L
                while (true) {
                    withFrameNanos { now ->
                        val dt = if (previous == 0L) 0f else (now - previous) / 1_000_000_000f
                        previous = now
                        // A finger held perfectly still produces no pointer
                        // events, so the long press has to be driven from here.
                        pilot.tick(now / 1_000_000L)
                        val step = dt.coerceAtMost(0.05f)
                        // Counted rather than sized: `step` already answers "did
                        // this one move", and the load a frame's timings belong
                        // to is the things that were moving in it, not the sixty
                        // that were parked.
                        var moving = 0
                        cards.values.forEach { if (it.step(SpringSpec.Bouncy, step)) moving++ }
                        // Nothing in the room itself moves — see `docs/classic/DESIGN.md`
                        // §11, which grants the desk scenes decoration and refuses
                        // them ambience. The only springs on this stage belong to
                        // the cards and to the camera, and both are here.
                        // Snappy rather than Bouncy: a card overshooting reads as
                        // weight, and a whole table overshooting reads as a lurch.
                        if (camera.rig.step(SpringSpec.Snappy, step)) {
                            camera.sync(widthPx, heightPx)
                            moving++
                        }
                        // And the tablet's own tilt, which is a third clock on
                        // this stage and obeys the same rule as the other two:
                        // it reports whether it moved, and a frame nothing moved
                        // in writes no plane. A device lying still returns false
                        // forever, which is what keeps "nothing idles" true of a
                        // feature that is driven by a sensor.
                        if (camera.sway.step(step)) {
                            camera.sync(widthPx, heightPx)
                            moving++
                        }
                        probe.sample(now, moving)
                        if (readout) probeTick = now
                    }
                }
            }

            // ---- one plane, and everything on it ---------------------------------
            //
            // There used to be a second layer above this one, flat, holding
            // whatever a hand was carrying, with its position projected by hand
            // so it left the plane without a seam. It worked because there was
            // only ever one camera angle: "flat" and "square to the reader" were
            // the same thing, so a card in the air could be drawn in the screen's
            // frame and a card on the felt in the mat's, and nobody had to say
            // which was which.
            //
            // A camera that turns ends that. In two frames a carried card set in
            // defence stays square to the glass while every card under it turns
            // with the table, and its own shadow — drawn in here, because a
            // shadow is on the table even when the thing casting it is not —
            // slides out from under it. So the second frame is gone: everything
            // is in the mat's, `flatten` carries the height, and the card in
            // your hand is turned by the same camera as the felt it left.
            // ---- the camera the room is seen through -----------------------------
            //
            // One layer above the mat and never the mat's own: that one carries
            // the yaw, the tilt and the camera distance, so a vignette drawn on
            // it is an ellipse whose centre walks off the optical axis as the
            // table turns. `docs/classic/DESIGN.md` §6 has the rule and the cost — one
            // full-screen composite, affordable exactly once.
            //
            // Desk scenes only. `Scene.MINIMAL` is the handbook's stage and
            // arrives ungraded, which is also what every device below API 33
            // gets, because `compileStageEffect` answers null and this whole
            // modifier becomes the identity.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = if (scene == Scene.MINIMAL) {
                        null
                    } else {
                        StageLens.effectFor(lens, widthPx, heightPx, camera.plane.cameraDistance)
                    }
                },
            ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The projection, said the other way round. `StagePlane`
                        // spins the mat about its own normal and then lays it
                        // back; Compose builds `Rx · Ry · Rz`, which turns a
                        // point about Z first and about X last — so these three
                        // lines *are* that projection, and the agreement is
                        // structural rather than a coincidence to maintain.
                        val plane = camera.plane
                        rotationZ = -plane.yawDegrees
                        rotationX = plane.tiltDegrees
                        scaleX = plane.zoom
                        scaleY = plane.zoom
                        cameraDistance = plane.cameraDistance / this.density

                        // And where the camera is aimed, which is the other half
                        // of that agreement now that it can be aimed anywhere.
                        //
                        // `transformOrigin` is the pivot everything above turns
                        // about *and* the point the perspective divide is centred
                        // on — which is exactly what `StagePlane.targetX` means.
                        // The translation then slides that pivot to the middle of
                        // the glass, and it is applied outside the pivoted
                        // transform on both platforms, so the composite is
                        // `subtract the target, turn, divide, add the screen
                        // centre` — `project`, term for term.
                        //
                        // This is what `CameraPose`'s KDoc used to refuse a pan
                        // over. It is two lines, and it is the cheap half; the
                        // expensive half it also predicted — an off-axis inverse
                        // in `unproject` — never arrived, because moving what the
                        // camera looks at leaves the vanishing point in the
                        // middle of the screen where the closed form needs it.
                        if (size.width > 0f && size.height > 0f) {
                            transformOrigin = TransformOrigin(
                                plane.targetX / size.width,
                                plane.targetY / size.height,
                            )
                            // …to wherever the lens is aimed, which is the
                            // middle of the glass until somebody shifts it.
                            // `axisX` rather than `centreX` is the whole of the
                            // lens shift on this side of the seam: Compose
                            // applies the translation *outside* the pivoted
                            // transform on both platforms, so moving it moves
                            // where the pivot lands and nothing else — which is
                            // exactly what `StagePlane.project` does with the
                            // same two numbers.
                            translationX = plane.axisX - plane.targetX
                            translationY = plane.axisY - plane.targetY
                        }
                    },
            ) {
                // The felt, what is about to happen to it, and the shadow and
                // the white edge of everything resting on it.
                // The room, and what is about to happen in it. Everything under
                // every card, and the only thing on the stage that is not part
                // of one.
                Canvas(Modifier.fillMaxSize()) {
                    // The room, then the mat lying on it, then the room again —
                    // because the felt is *on* the desk and *under* the lamp,
                    // and one pass could only ever put it on one side. Nothing
                    // in a `SceneModel` straddles the table top, which is what
                    // makes that split well defined rather than a guess.
                    //
                    // The pool is solved here rather than carried on the scene
                    // because it depends on where the camera is, and the camera
                    // moves. It is one bisection and nine evaluations of the
                    // same function that shades every card — a few hundred
                    // floating-point operations against a frame budget of eight
                    // thousand microseconds — and it is null for every room with
                    // no lamp in it, which is every room but one.
                    val pool = StageRig.pool(look.lighting, camera.eye)
                    drawScene(
                        scenery.ground, camera.plane, camera.eye, look, pool,
                        wood = wood,
                        grainOrigin = layout.field.left to layout.field.top,
                        grainPitch = layout.cardWidth,
                    )
                    drawFelt(layout, camera.plane, camera.eye, look, pool, weave)
                    // Between the desk and the lamp, which is where a shadow on
                    // the desk belongs: after the surface it lands on, before
                    // the fixture that throws it. `AAA.md` #61d.
                    drawRoomShadows(scenery.deskShadows, camera.plane, camera.eye, look)
                    drawScene(scenery.standing, camera.plane, camera.eye, look)
                    drawMatControls(layout, play.field)
                    play.carried.forEach {
                        drawIndicator(it, play.field, layout, tune.hand.stepFraction, play.handRow)
                    }
                }

                // Then one card at a time, each drawing its own shadow, its own
                // body and its own face, in that order, as a unit.
                //
                // It used to be four passes — every shadow, then every white
                // edge, then every card's picture, then the same three again for
                // whatever was in the air — and the reason that was wrong is not
                // subtle once it is written down: **no card's picture could ever
                // be behind any pile's wall**, because all the walls were painted
                // before any of the pictures. The graveyard sits one row further
                // from the camera than the deck, and its top card drew straight
                // over the deck standing in front of it.
                //
                // A card, its body and its shadow are one object, so they are one
                // thing to paint, and then the parent's order really is the whole
                // depth sort — which is what this file has claimed all along.
                ordered.forEach { seat ->
                    // Keyed, because this list *reorders*. Unkeyed, Compose
                    // matches children by position, so the frame a card
                    // overtakes another every node from there on is handed a
                    // different card — which throws away exactly the image
                    // caches and layer state the reorder was cheap because of.
                    key(seat.id) {
                        StagedCard(seat, cards, state, back, density, camera, look, tune.focus)
                    }
                }
            }
            }

            // Outside the graded layer on purpose: it draws nothing, and a
            // gesture arbiter inside an offscreen composite is a gesture arbiter
            // whose coordinates have been through a pass it knows nothing about.
            MatInput(pilot = pilot, layout = layout, camera = camera)

            menuFor?.let { origin ->
                CardActions(play, origin, layout, onDismiss = { menuFor = null })
            }
        }

        // Over the stage and under the bar, in the outer box: Compose hit-tests
        // siblings in reverse, so the scrim receives pointers before `MatInput`
        // and a tap that closes the reader cannot also land on the table behind
        // it. The bar stays reachable, which is deliberate — undo is the first
        // thing you want after reading a card and finding out you were wrong.
        reading?.let { origin ->
            val card = play.field.cardAt(origin)?.let { state.index.byId(it.cardId) }
            // Resolved every recomposition rather than captured, so a card that
            // has left the table takes its reader with it instead of leaving a
            // panel describing a card that is no longer there. The closing is an
            // effect and not a write during composition, which would schedule a
            // recomposition for a value this pass has already read.
            if (card != null) {
                CardReader(card) { reading = null }
            } else {
                LaunchedEffect(origin) { reading = null }
            }
        }

        PlayTopBar(
            play = play,
            camera = camera,
            onBack = onBack,
            onGuide = { guide = true },
            onReadout = { readout = !readout },
            onTuner = { tuner = !tuner },
            room = roomLabel(scene, deskLight),
            onRoom = cycleScene,
            cameraTouch = cameraTouch,
            onCameraTouch = { prefs.update { it.copy(cameraTouch = !it.cameraTouch) } },
            headSway = headSway,
            onHeadSway = { prefs.update { it.copy(headSway = !it.headSway) } },
        )

        if (readout) {
            FrameReadout(probe, { probeTick }, Modifier.align(Alignment.TopStart))
        }

        // After the bar and before the guide, in the *outer* box. Compose
        // hit-tests siblings in reverse order, so this receives pointers before
        // `MatInput` — which is what stops a slider drag from orbiting the table
        // underneath it. Inside `BoxWithConstraints` it would be under the mat's
        // own `pointerInput` and every drag would fight the arbiter.
        if (tuner) {
            StageTuner(
                tuning = tune,
                // Read-modify-write against the *live* document rather than the
                // one the panel was composed with. A slider's gesture coroutine
                // outlives its composition — `StageTuner`'s own note has the
                // whole story — so a callback that carried a finished document
                // carried a stale one, and every other knob went back to where
                // it had been when the panel opened.
                onTune = { knob, value ->
                    prefs.update(debounce = true) {
                        it.copy(stageTuning = knob.set(it.stageTuning, value))
                    }
                },
                onReplace = { next -> prefs.update(debounce = true) { it.copy(stageTuning = next) } },
                camera = camera,
                surface = tuningSurface,
                onClose = { tuner = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = TOP_BAR),
            )
        }

        // Over the bar as well as the table, because it is answering a question
        // about both of them.
        if (guide) PlayGuide(onDismiss = { guide = false })
    }
    }
}

/** One card, and everything the stage needs to know about drawing it. */
private data class Seat(
    val id: Int,
    val card: BoardCard,
    val pose: Pose3,
    val faceUp: Boolean,
    /** Off the mat, so it draws on the flat layer above rather than the plane. */
    val carried: Boolean,
    /**
     * Position assigned from the finger rather than sprung toward it.
     *
     * Not the same question as [carried], which is only about which layer draws
     * it. A peeked card is in the air and nobody is holding it, so it should
     * *rise*; pinning it would make it jump.
     */
    val pinned: Boolean = false,
    /** How many cards are here. One card is a card; more than one is a solid. */
    val depth: Int,
    val materials: Int,
    val counters: Int,
    val width: Float,
    val height: Float,
    /**
     * Part of a spread-out pile, which is one object and paints in one piece.
     *
     * Its own flag rather than a depth, because a fan is *coplanar*: forty cards
     * lying at one height, overlapping, in an order fixed when the hand laid them
     * out. Projected depth cannot recover that order — inside a row every card is
     * the same distance away until the table turns, and then the depths fan out
     * sideways and the overlap reshuffles itself as you orbit, which is exactly
     * what it did.
     */
    val fanned: Boolean = false,
    /** Held up to be read rather than played, which changes the art it loads. */
    val magnified: Boolean = false,
    /**
     * One of the five in your hand.
     *
     * A hand is a fan, and a fan's overlap is decided by the order the cards
     * are held in — not by which of them happens to be a millimetre nearer the
     * camera. Sorting it by projected depth like a card lying on the table gets
     * both halves wrong: see the comparator.
     */
    val inHand: Boolean = false,
    /**
     * The tiebreak when two seats are the same distance away.
     *
     * The instance id for a card on the mat, which is the order it was played
     * and is what "bring to front" moves. For a card in a fan it is the position
     * in the spread, so the overlap reads left to right whatever the camera does.
     */
    val order: Int = id,
    /**
     * Where a card the stage has never seen before should *start*, when it
     * should simply be there rather than fly in from `dealFrom`.
     *
     * Null for everything that is genuinely arriving — a card dealt, a card
     * revealed out of a spread — because travelling from the deck is the right
     * animation for those and is the whole reason `dealFrom` exists.
     *
     * Set on the pile seats, which are the one place a card can be *revealed
     * without moving*: lift the top card off the graveyard and the card under it
     * gets a seat for the first time, standing exactly where it already was. Let
     * it spring from `dealFrom` and lifting one card makes the graveyard's next
     * card fly in from the deck's square.
     */
    val bornAt: Pose3? = null,
) {

    /**
     * What is on and under this card, in one short string, or null for nothing.
     *
     * Three different facts about a card and one place to read them, because
     * they compete for the same corner and used to resolve it by *hiding one*:
     * the badge showed counters, or materials if there were no counters, and
     * said nothing at all about a card being the top of a stack.
     *
     * That last omission is the one that mattered. An Xyz monster's whole
     * identity is what it is made of, and the number of cards underneath one was
     * the single most important thing about it that the table would not tell
     * you — you could not see it, and until the fan you could not reach it
     * either. `▤` is what is resting under this card, `◈` what is attached to it
     * as material, `●` counters on it.
     *
     * It stays inside the card's own bounds, which is the constraint that rules
     * out the alternative — drawing the buried cards peeking out from under the
     * near edge. Peeking cards read beautifully for a stack of two and turn a
     * monster zone into a smear at five, and five is an ordinary Xyz.
     */
    val tally: String?
        get() {
            val parts = buildList {
                if (depth > 1) add("▤${depth - 1}")
                if (materials > 0) add("◈$materials")
                if (counters > 0) add("●$counters")
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
    /**
     * How far the body of this card, or this pile, hangs below its printed face.
     *
     * The pose already sits on top of it — a card resting on a deck is at the
     * *top* of the deck — so this is what the renderer extrudes downward to
     * reach the felt.
     */
    val solid: Float get() = CardSolid.pileDepth(depth, width)
}

/**
 * Every card that is visible, and where it belongs.
 *
 * Ordered back to front by depth on the mat, with recency breaking ties —
 * `PlayField.mat` is ordered by recency alone, which is right for two cards in
 * the same place and wrong for two at different depths, because on a tilted
 * plane a card played early near the front must still occlude one played later
 * further back.
 */
private fun seatsFor(
    field: PlayField,
    layout: BoardLayout,
    /**
     * Everything in the air, which is up to ten cards — one per finger.
     *
     * A list rather than one card, and every reader below asks it *which* carry
     * is about the seat it is building rather than assuming there is one. The
     * bug that shape exists to prevent is the ordinary one: the right hand's
     * card drawn at the left hand's position, which looks exactly like a card
     * teleporting and would be blamed on the projection.
     */
    carries: List<Carry> = emptyList(),
    peeking: DragOrigin? = null,
    /** What has been spread out to be searched, if anything. */
    fanned: DragOrigin? = null,
    /**
     * The instance whose effect was just declared, which rises a little.
     *
     * A bump rather than a glow, because the stage's poses are already springs:
     * setting a card's z and taking it away again a third of a second later is
     * a whole animation, costs one field, and reads as the card being picked out
     * of the row the way a hand lifts the card it is talking about. A glow would
     * be a decoration with a timer behind it, which is the handbook's first
     * anti-pattern and a second clock to keep in step.
     */
    declared: Int? = null,
    /**
     * The three angles that, inside the mat's own layer, come out square to the
     * viewer. Passed in rather than computed here because it is a fact about
     * where the camera is, and this function is about where the cards are.
     */
    facingReader: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    /**
     * The numbers a person has moved. Defaulted to what shipped, so every
     * caller that has no opinion gets the stage it always had.
     */
    tune: StageTuning = StageTuning.DEFAULT,
    /**
     * The hand as a row of *places*: no place for a card in the air, one held
     * open where a card is landing. `PlayState.handRow` is the one that is real;
     * the default here is a plain hand, for a caller with nothing in the air.
     */
    hand: HandRow = HandRow.of(field.hand.size),
    /**
     * The open spread, already parted for whatever is being carried over it.
     *
     * Passed in rather than solved here, because the hit test has to get the
     * identical answer and `MatInput.fanOf` is where that answer lives. Null
     * when nothing is spread out.
     */
    spread: FanSpread? = null,
): List<Seat> {
    val seats = mutableListOf<Seat>()
    val cardWidth = layout.cardWidth
    val cardHeight = layout.cardHeight
    // In mat pixels, and from `CarryHeight` rather than multiplied out here.
    // These are the numbers the *input* side needs too — a drop lands where the
    // card is drawn, so the offset between the finger and the card is a function
    // of exactly these — and two files each doing their own arithmetic on the
    // same two knobs is the drift `MatPilot.fanLift`'s KDoc already warned about
    // for the one of the three they happened to share.
    // Which physical cards are off the table right now, by identity. Every
    // reader below that used to ask "is this seat's *index* the one being
    // carried" asks this instead: an index is a memory of the board at the lift
    // and the board moves under it, so with two hands the answers drifted apart
    // — a pile drawing the wrong card, a hand hiding the wrong slot.
    val airborne = carries.mapNotNullTo(mutableSetOf()) {
        it.holding.takeIf { held -> held != PlayState.NO_CARD }
    }
    val carryLift = CarryHeight.mat(cardHeight, tune)
    val handLift = CarryHeight.hand(cardHeight, tune)
    val fanLift = CarryHeight.fan(cardHeight, tune)

    fun poseAt(
        at: MatPoint,
        z: Float,
        turned: Boolean,
        faceUp: Boolean,
        lean: Float = 0f,
        // How square it came to rest. `Square` for anything a finger is holding,
        // because a finger is exact and a carried card that drifted a degree off
        // the point it was grabbed at would read as lag.
        landing: Settle.Landing = Settle.Landing.Square,
    ): Pose3 {
        val (x, y) = layout.toPixels(at)
        return Pose3(
            position = Vec3(
                x + landing.slipX * cardWidth,
                y + landing.slipY * cardWidth,
                z,
            ),
            rotX = lean,
            // One truth about which face shows: half a turn means the back.
            rotY = if (faceUp) 0f else 180f,
            rotZ = (if (turned) -90f else 0f) + landing.turnDegrees,
            scale = 1f,
        )
    }

    field.mat.forEach { placed ->
        val carry = carries.firstOrNull { it.id == placed.id }
        val carrying = carry != null
        // In the air, the carry owns which face shows: it is what the card will
        // land as, so turning it over is visible before it is committed.
        val faceUp = if (carry != null) !carry.faceDown else placed.faceUp
        seats += Seat(
            id = placed.id,
            card = placed.card,
            pose = poseAt(
                at = carry?.at ?: placed.at,
                // Resting on whatever is under it, which for a stack is the
                // rest of the stack: a card on a pile of four is four cards
                // off the felt, and its shadow is cast from up there.
                z = if (carry != null) {
                    carryLift
                } else {
                    CardSolid.pileDepth(placed.depth, cardWidth) +
                        if (placed.id == declared) cardHeight * DECLARE_LIFT else 0f
                },
                // The carry's own solved position, not its raw twist: a card
                // being *set* lies sideways because it is a monster going
                // into a monster zone, and nobody twisted anything to say so.
                turned = carry?.turned ?: placed.turned,
                faceUp = faceUp,
                landing = if (carrying) {
                    Settle.Landing.Square
                } else {
                    Settle.of(placed.id, Settle.Care.PLACED)
                },
            ),
            faceUp = faceUp,
            carried = carrying,
            pinned = carrying,
            depth = placed.depth,
            materials = placed.card.materials.size,
            counters = placed.card.counters,
            width = cardWidth,
            height = cardHeight,
        )
    }

    // Deliberately not sorted here any more. This function knows where the
    // cards are and nothing about where the camera is, and which card is in
    // front of which is a question about both — so the whole list is ordered by
    // projected depth at the call site, hand and piles included. It used to sort
    // the mat cards alone, by raw mat-space y, before the hand and the four piles
    // were even appended: those went in last, in a hard-coded order, and the
    // graveyard therefore painted over the deck standing in front of it.

    // The hand, fanned along the band the solver set aside — as a *row of
    // places* rather than as a list of cards, so that the card in the air has no
    // place and the place a card is landing in is really open. See `HandRow`:
    // the row was drawn one way and measured another, and the gap the caret
    // named was half a step from the gap the card went into.
    field.hand.forEachIndexed { index, card ->
        val carry = carries.firstOrNull { it.holding == card.instanceId }
        val carrying = carry != null
        val place = hand.placeOf(index)
        val at = HandFan.pointFor(
            layout,
            // A carried card keeps its own place for the pose it is *leaving*,
            // which nothing reads — `carry.at` wins below — but a place must be
            // a number, and the row no longer has one for it.
            place ?: 0,
            hand.size.coerceAtLeast(1),
            tune.hand.stepFraction,
        )
        // A card in hand is always face-up to its owner; one turned over on the
        // way out of it is being set.
        val faceUp = carry?.faceDown != true
        seats += Seat(
            id = card.instanceId,
            card = card,
            pose = poseAt(
                at = carry?.at ?: at,
                // Not zero. A leaned card pivots on its bottom edge, and this
                // is what buys that — see [handLiftOf].
                z = if (carry != null) {
                    handLift
                } else {
                    handLiftOf(cardHeight, CardSolid.pileDepth(1, cardWidth), tune.hand) +
                        if (card.instanceId == declared) cardHeight * DECLARE_LIFT else 0f
                },
                turned = carry?.turned == true,
                faceUp = faceUp,
                lean = if (carrying) 0f else tune.hand.leanDegrees,
                landing = if (carrying) {
                    Settle.Landing.Square
                } else {
                    Settle.of(card.instanceId, Settle.Care.PLACED)
                },
            ),
            faceUp = faceUp,
            carried = carrying,
            pinned = carrying,
            depth = 1,
            materials = 0,
            counters = 0,
            width = cardWidth,
            height = cardHeight,
            inHand = true,
            // The position in the fan, so the overlap reads left to right. The
            // default is the *instance* id, which for a freshly dealt hand is
            // the shuffle — which is why the third card was sitting on top of
            // the fourth for no reason anybody could see.
            order = place ?: index,
        )
    }

    // The tops of the piles, so a stack is a card and a number the way a stack is
    // — except the one that has been spread out, whose cards are all on the
    // table below and would otherwise be drawn twice.
    listOf(
        BoardSlot.Deck to field.deck,
        BoardSlot.ExtraDeck to field.extraDeck,
        BoardSlot.Graveyard to field.graveyard,
        BoardSlot.Banished to field.banished,
    ).forEach { (slot, pile) ->
        if (fanned == DragOrigin.Pile(slot, 0)) return@forEach
        // The pile **minus whatever is in the air**. Nothing commits until the
        // release, so the field still holds a carried card in its pile — and the
        // carry block below gives that card a seat of its own. Both ran, so
        // dragging the top card off any pile built two seats with one instance
        // id: one lying on the mat as a `pile.size`-thick solid, one in the air.
        // `ordered` held both and `StagedCard` composed the same card twice.
        //
        // Skipping the pile outright was how that collision was settled first,
        // and it is kai's second report: *a pile is its top card's seat*, drawn
        // `pile.size` thick, so not drawing it takes the whole graveyard off the
        // table for the length of the drag. What is drawn instead is the rest of
        // the pile — the next card's face, one card thinner — which has an
        // instance id of its own and so cannot collide with anything.
        val resting = if (airborne.isEmpty()) pile else pile.filterNot { it.instanceId in airborne }
        val top = resting.firstOrNull() ?: return@forEach
        val rect = layout[slot] ?: return@forEach
        val faceUp = slot == BoardSlot.Graveyard || slot == BoardSlot.Banished
        // A deck is tapped square against the table and a graveyard is swept
        // into a heap, and drawing both at the same tidiness is most of what
        // makes a board look printed. Which pile it is *is* how much care was
        // taken putting the card there.
        val landing = Settle.of(
            top.instanceId,
            if (faceUp) Settle.Care.THROWN else Settle.Care.SQUARED,
        )
        val pose = Pose3(
            // On top of its own pile, which is the whole reason a deck looks
            // like a deck rather than like a card with a number.
            position = Vec3(
                rect.centerX + landing.slipX * cardWidth,
                rect.centerY + landing.slipY * cardWidth,
                CardSolid.pileDepth(resting.size, cardWidth),
            ),
            rotY = if (faceUp) 0f else 180f,
            rotZ = landing.turnDegrees,
        )
        seats += Seat(
            id = top.instanceId,
            card = top,
            pose = pose,
            // Born where it stands. Lifting the top card off the graveyard gives
            // the card under it a seat for the first time, and it was already
            // lying there — without this it would be minted at `dealFrom` and
            // fly in from the deck's square to a place it never left.
            bornAt = pose,
            faceUp = faceUp,
            carried = false,
            depth = resting.size,
            materials = 0,
            counters = 0,
            width = cardWidth,
            height = cardHeight,
        )
    }

    // A card being carried out of a pile has no seat of its own yet — it is
    // still in the pile as far as the field is concerned — so it gets one here,
    // or dragging out of the graveyard would carry something invisible.
    carries.forEach { carry ->
        val from = carry.from
        if (from !is DragOrigin.Pile && from !is DragOrigin.Buried) return@forEach
        // By identity rather than by `from.index`. The index is a memory of the
        // board at the lift, so the instant the other hand commits, this card
        // would visibly *become a different card* in mid-air — the rendering
        // half of the same staleness `PlayField.rebase` answers at the release.
        val held = when (from) {
            is DragOrigin.Pile -> field.pile(from.pile)
            is DragOrigin.Buried -> field.under(from.under)
            else -> null
        }?.firstOrNull { it.instanceId == carry.holding } ?: return@forEach

        val faceUp = !carry.faceDown
        seats += Seat(
            id = held.instanceId,
            card = held,
            pose = poseAt(carry.at, carryLift, carry.turned, faceUp),
            faceUp = faceUp,
            carried = true,
            pinned = true,
            depth = 1,
            materials = 0,
            counters = 0,
            width = cardWidth,
            height = cardHeight,
        )
    }

    // The pile that has been spread out, laid across the board so a card can be
    // taken out of the middle of it.
    //
    // Face-up, all of it, because that is what a search *is* — and lifted off
    // the felt, so it reads as a spread being held over the board rather than as
    // sixty cards that have somehow been played. The lift is what gives every
    // one of them a shadow, and the shadows are what keep the board underneath
    // legible without a wash over it.
    //
    // The order is the pile's own order, unchanged. For the graveyard and the
    // banished pile that order is a fact about the game; for the deck it is the
    // shuffle, and seeing it is the whole point of searching — which is why
    // closing the deck's fan shuffles it again.
    if (fanned != null) {
        val cards = field.fanOf(fanned)
        // Parted for whatever is being carried over it — kai's *"the fan needs
        // to dynamically move and make space for wherever the hovered card is
        // going"*. Solved by the same `MatInput.fanOf` the hit test calls, so
        // which card you are pointing at cannot disagree with which card you can
        // see, however far the spread has moved aside.
        val laid = spread ?: PileFan.spread(cards.size, layout.field, cardWidth, cardHeight)
        laid.cards.forEach { fan ->
            val card = cards.getOrNull(fan.index) ?: return@forEach
            // Any hand holding it, not only the first: with two hands in a
            // spread, checking one carry would draw the other's card twice —
            // once floating under the finger and once still lying in the fan.
            if (carries.any { it.holding == card.instanceId }) return@forEach
            seats += Seat(
                id = card.instanceId,
                card = card,
                pose = Pose3(position = Vec3(fan.x, fan.y, fanLift)),
                faceUp = true,
                carried = false,
                pinned = false,
                depth = 1,
                materials = card.materials.size,
                counters = card.counters,
                width = cardWidth,
                height = cardHeight,
                fanned = true,
                order = fan.index,
            )
        }
    }

    // The peek, applied last as a change to one seat rather than threaded
    // through every branch above: it moves nothing and owns nothing, it only
    // says that one card is being read right now.
    val peeked = peeking?.let { seatIdOf(field, it) }
    if (peeked != null) {
        val index = seats.indexOfFirst { it.id == peeked }
        if (index >= 0) {
            val seat = seats[index]
            seats[index] = seat.copy(
                pose = seat.pose.copy(
                    position = Vec3(
                        seat.pose.position.x,
                        seat.pose.position.y,
                        cardHeight * tune.cards.peekLift,
                    ),
                    // Square to the reader and face up, whichever way it was
                    // lying. Being unable to check your own set card is the
                    // thing a held finger is asking about — and "the reader" is
                    // now wherever the camera is, so this is whatever undoes it
                    // rather than a pair of zeroes.
                    rotX = facingReader.first,
                    rotY = facingReader.second,
                    rotZ = facingReader.third,
                    scale = tune.cards.peekScale,
                ),
                faceUp = true,
                carried = true,
                pinned = false,
                // One card, held up to be read — not the pile it came off. Left
                // as the pile's depth it would draw a deck's worth of body
                // behind a card floating in the air, and shadow from the base of
                // a pile that is still lying on the table.
                depth = 1,
                magnified = true,
            )
        }
    }

    return seats
}

/** Which card on the stage a grabbed origin refers to. */
private fun seatIdOf(field: PlayField, origin: DragOrigin): Int? = when (origin) {
    is DragOrigin.Mat -> origin.id
    is DragOrigin.Hand -> field.hand.getOrNull(origin.index)?.instanceId
    is DragOrigin.Pile -> field.pile(origin.pile).getOrNull(origin.index)?.instanceId
    is DragOrigin.Buried -> field.under(origin.under).getOrNull(origin.index)?.instanceId
}

/**
 * How coarsely depth is measured before recency takes over, as a share of the
 * table's height.
 *
 * Two jobs, and the second one is the reason it is not simply zero. It keeps two
 * cards *at the same place* ordered by when they were played rather than by the
 * last bit of a sine — which is what `mat`'s own recency order is for. And it
 * keeps the paint order still: an unquantised sort re-orders on any wobble, and
 * every re-order is a recomposition of the whole stage.
 *
 * Finer than the two per cent of mat-space y it replaces, because depth is a
 * shorter ruler than y — the tilt multiplies every height on this table by
 * `sin θ`, about a third — so the same fraction would have been three times as
 * coarse a bucket as the one it replaced.
 */
private const val DEPTH_QUANTUM = 0.006f

/**
 * How far a declared card rises, as a share of its own height.
 *
 * A fifth, which at the table seat is a few pixels of screen once `sin(tilt)`
 * has taken its cut — the exchange rate every height on this stage pays. Enough
 * that the card separates from the row and its shadow moves; not so much that a
 * board of declarations looks like it is coming apart.
 */
private const val DECLARE_LIFT = 0.2f

/** How long the card stays up. Long enough to see, short enough to keep tapping through. */
private const val DECLARE_MILLIS = 420L

/** Quantised so a small wobble cannot reshuffle the whole paint order. */
private fun quantised(depth: Float, quantum: Float): Int =
    if (quantum <= 0f) 0 else (depth / quantum).toInt()

/**
 * Where the card in the air is going to land, drawn on the mat under it.
 *
 * The indicator and the outcome are the same value — `DropTargets` decided it
 * once and both the highlight and the release read that decision — so the table
 * cannot promise one thing and do another.
 */
private fun DrawScope.drawIndicator(
    carry: Carry?,
    field: PlayField,
    layout: BoardLayout,
    handStep: Float,
    /** The hand as it is drawn, so the opening this points at is the real one. */
    hand: HandRow,
) {
    val intent = carry?.intent ?: return

    val accent = MasterToolPalette.AccentBright
    fun ring(rect: Slot, alpha: Float, weight: Float = 0.035f) {
        drawRoundRect(
            color = accent.copy(alpha = alpha),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(rect.width * 0.05f),
            style = Stroke(width = rect.width * weight),
        )
    }

    /** The footprint a card would occupy centred on a mat point. */
    fun footprint(at: MatPoint): Slot {
        val (x, y) = layout.toPixels(at)
        return Slot(
            left = x - layout.cardWidth / 2f,
            top = y - layout.cardHeight / 2f,
            width = layout.cardWidth,
            height = layout.cardHeight,
        )
    }

    fun onCard(id: Int, alpha: Float, weight: Float) {
        field.placed(id)?.let { ring(footprint(it.at), alpha, weight) }
    }

    when (intent) {
        is DropIntent.Zone -> layout[intent.slot]?.let { ring(it, 0.85f) }
        DropIntent.Graveyard -> layout[BoardSlot.Graveyard]?.let { ring(it, 0.85f) }
        DropIntent.Banish -> layout[BoardSlot.Banished]?.let { ring(it, 0.85f) }
        DropIntent.Deck -> layout[BoardSlot.Deck]?.let { ring(it, 0.85f) }
        DropIntent.ExtraDeck -> layout[BoardSlot.ExtraDeck]?.let { ring(it, 0.85f) }

        // The hand says *where* in the hand, and it now says it by being open
        // there. The row holds a real place for the card — `HandRow` — so the
        // honest drawing is the footprint of that place rather than a caret
        // pointing between two cards that never moved.
        //
        // It used to be a caret, and the caret had to reconstruct which place it
        // meant: back out of the full hand's numbering, into a row of
        // `count - 1`, minus half a step. Three reconstructions of a row that
        // the renderer was drawing differently, which is exactly how it came to
        // sit half a step from the gap the card went into.
        is DropIntent.Hand -> {
            val place = hand.openingFor(intent.at, field.hand.size)
            if (place != null) {
                val x = HandFan.centreOf(layout.hand, layout.cardWidth, place, hand.size, handStep)
                ring(
                    Slot(
                        left = x - layout.cardWidth / 2f,
                        top = layout.hand.centerY - layout.cardHeight / 2f,
                        width = layout.cardWidth,
                        height = layout.cardHeight,
                    ),
                    0.85f,
                )
            } else {
                // No place held open — the row has not caught up with the intent
                // yet, which is one frame at most. A band-wide hint beats
                // nothing at all and beats a caret drawn in the wrong gap.
                ring(layout.hand, 0.35f, 0.02f)
            }
        }

        // The freeform three, which are the only ones with no box already drawn
        // on the felt to light up. Stacking rings the card being landed on;
        // attaching rings it thinner, because the card is going underneath;
        // free just shows the footprint, so "nowhere in particular" still looks
        // like a decision rather than a dead gesture.
        is DropIntent.Stack -> onCard(intent.onto, 0.85f, 0.035f)
        is DropIntent.Attach -> onCard(intent.onto, 0.7f, 0.012f)
        is DropIntent.Free -> ring(footprint(intent.at), 0.28f, 0.02f)

        // Back into the gap it came out of. Drawn where the gap is rather than
        // nowhere, which is what this used to be: "Cancel" was unreachable, so
        // an indicator for it would have been an indicator for nothing. It is a
        // destination now, and a destination the user cannot see is one they can
        // only find by accident.
        DropIntent.Cancel -> carry.home?.let { ring(footprint(it.at), 0.55f, 0.02f) }
    }
}

/**
 * One card on the stage.
 *
 * Both faces are always composed, each setting its own alpha inside its own
 * `graphicsLayer`. Picking a face by reading the angle in the composable body
 * would recompose the card on every frame of a flip, which is the thing the
 * whole one-loop pattern exists to avoid — and you cannot branch inside a
 * `graphicsLayer` block, because it is a draw lambda, not composition.
 *
 * ## Why the card's picture is not drawn by a `graphicsLayer`
 *
 * It was, and that was the bug the user could see: a hand card's *picture* lay
 * flat in the table while its *edges* — drawn by the canvas from the same
 * card's true corners — stood up and tilted, a few pixels away from it.
 *
 * A layer offers three Euler angles and a `cameraDistance`, and it spends them
 * the wrong way. It is itself a projective *2-D* map, its divide forced through
 * the card's own centre, and whatever it produces is pasted flat at z = 0 into
 * its parent — so the card's z is consumed before the mat's projection ever
 * sees it. Four parameters, and the map wanted is a homography with eight.
 *
 * So the card is drawn through the map that takes its rectangle to its four
 * real corners, and those corners come from
 * `CardSolid.face` → `StagePlane.flatten` — *the identical call `drawSolidEdges`
 * makes*. Both then take the mat layer's single divide, so the picture and the
 * geometry agree structurally instead of being tuned until they nearly did.
 * The composition of two projective maps is projective, which is why this is
 * exact in the card's interior and not only at its corners.
 */
@Composable
private fun StagedCard(
    seat: Seat,
    cards: MutableMap<Int, StageCard>,
    state: DeckBuilderState,
    back: com.kaiharimoto.mastertool.ui.components.CardBackChoice,
    density: androidx.compose.ui.unit.Density,
    camera: StageCameraState,
    look: StageLook,
    /** How far this card is from the focus plane, once it is drawn. See `Defocus`. */
    focus: FocusTune,
) {
    val motion = cards[seat.id] ?: return
    val art = state.index.byId(seat.card.cardId)

    // One scratch matrix per card, rewritten by every frame's draw. Remembered
    // rather than allocated because sixty cards minting a FloatArray(16) each
    // frame is a hundred kilobytes a second of garbage for a value that lives
    // for one draw call. It reads nothing, so it never invalidates anything.
    val values = remember { FloatArray(16) }

    with(density) {
        Box(
            Modifier
                .size(seat.width.toDp(), seat.height.toDp())
                .drawWithContent {
                    // Every moving value is read *here*, in a draw lambda, for
                    // the same reason `CardFace` reads the pose in its own: a
                    // card that took the pose or the plane as an argument would
                    // recompose whenever either twitched, and sixty of those is
                    // the frame budget. See `StageCameraState`'s KDoc.
                    val pose = motion.pose
                    val plane = camera.plane

                    // A card, its body and the shadow it throws are one object,
                    // so they are painted as one — shadow, then the white edges
                    // of the stock, then the picture, all before the next card
                    // is touched. Drawn here rather than on a canvas of their
                    // own because a canvas can only come wholly before or wholly
                    // after sixty composables, and either way something ends up
                    // in front of a thing it is behind.
                    //
                    // The coordinates work out because this box is laid out at
                    // the mat's own origin and moved by the matrix below rather
                    // than by the layout — so a draw scope here *is* the mat's
                    // frame, which is the frame `Shadows.cast` and `CardSolid`
                    // already speak.
                    drawCardShadow(
                        pose, seat.width, seat.height, seat.height, seat.solid,
                        camera.eye, look,
                    )
                    drawSolidEdges(
                        pose, seat.width, seat.height, seat.solid,
                        plane, camera.eye, look, seat.depth,
                    )

                    // One frame, one answer. Every card on this stage — resting
                    // on the felt, sitting on top of a deck, or held in the air
                    // — is drawn inside the mat's own layer, so its height is
                    // folded into points on the felt that will *look* like that
                    // height once the camera has run. `Rot3.place` carries the
                    // card's own scale, so the peek needs no separate term, and
                    // a per-vertex flatten carries `Flattened.scale` too.
                    val quad = CardSolid.face(pose, seat.width, seat.height).map { corner ->
                        val flat = plane.flatten(corner)
                        Vec2(flat.x, flat.y)
                    }

                    // A card the eye is exactly level with presents no area,
                    // and a quad with no area has no map — so this is the same
                    // guard the renderer already applies to a solid's faces,
                    // asked here before a division rather than after one.
                    if (CardSolid.flatArea(quad) < CardSolid.MIN_DRAWN_AREA) return@drawWithContent

                    // And exactly edge-on, where four collinear corners
                    // determine no map at all — a state every card passes
                    // through twice on every flip.
                    val map = Homography.rectToQuad(seat.width, seat.height, quad)
                        ?: return@drawWithContent

                    map.writeComposeMatrix(values)
                    withTransform({ transform(Matrix(values)) }) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            CardFace(
                art = art,
                faceUp = true,
                back = back,
                motion = motion,
                camera = camera,
                look = look,
                magnified = seat.magnified,
                focus = focus,
            )
            CardFace(
                art = art,
                faceUp = false,
                back = back,
                motion = motion,
                camera = camera,
                look = look,
                focus = focus,
            )

            seat.tally?.let { tally ->
                Badge(
                    text = tally,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .graphicsLayer {
                            // The same correction the back of the card makes for
                            // itself in [CardFace], which the badge never got —
                            // so the deck has been printing its own count
                            // backwards, and the count of a face-down stack with
                            // it. `▤34` came out as its own mirror image on the
                            // one number a player checks most.
                            //
                            // Read live rather than from `seat.faceUp`, because a
                            // card *turning over* is the case that matters: the
                            // glyphs have to flip on the same frame the printed
                            // side does, which is the frame the card passes
                            // through edge-on and neither side is visible.
                            val turn = motion.pose.rotY * (PI.toFloat() / 180f)
                            if (cos(turn) <= 0f) scaleX = -1f
                        },
                )
            }
        }
    }
}

/**
 * One side of a card.
 *
 * Its own layer, whose alpha is a function of the turn — so a flip swaps face
 * for back at exactly ninety degrees, where the card is edge-on and neither is
 * visible anyway, without composition being involved at all.
 */
@Composable
private fun CardFace(
    art: Card?,
    faceUp: Boolean,
    back: com.kaiharimoto.mastertool.ui.components.CardBackChoice,
    motion: StageCard,
    camera: StageCameraState,
    look: StageLook,
    /** Held up to be read, so it is worth fetching the art at full size. */
    magnified: Boolean = false,
    /** How far the focus plane is, for the haze. See `Defocus`. */
    focus: FocusTune = FocusTune(),
) {
    // Two surfaces, two materials. The printed side is card stock — foiled if
    // this is an extra-deck frame — and the other side is the back of a sleeve,
    // which is the quietest thing on the table. Each side owns its own, so a
    // card turning over changes what the light does to it at the same moment it
    // changes what you can see.
    val material = remember(art, faceUp) { CardStock.of(art, faceUp) }

    // The sleeve's foil edge, the same one the builder's tiles wear — and the
    // *printed side only*. A foil is what a sleeve does to the face of a card;
    // on the back it is a rainbow outline round something that is meant to be
    // anonymous, and since the deck, the graveyard and every set card are backs,
    // it turned every pile in the room into a neon rectangle.
    //
    // One condition rather than a branch inside the draw, because this composable
    // *is* one side: `StagedCard` calls it twice, and the two are separated by
    // the alpha in the layer below rather than by anything read at draw time.
    //
    // Read in composition rather than per frame: it changes when somebody opens
    // a menu.
    val foiled = LocalPrismaticCards.current && faceUp

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val turn = motion.pose.rotY * (PI.toFloat() / 180f)
                val facing = cos(turn)
                alpha = if (faceUp) {
                    if (facing > 0f) 1f else 0f
                } else {
                    if (facing > 0f) 0f else 1f
                }
                // The back is drawn mirrored, or it would read as reversed once
                // the card has turned to show it. A negative scale rather than
                // a half turn: identical output, and one fewer knob on this
                // stage that carries a z and a perspective divide with it.
                if (!faceUp) scaleX = -1f
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(CardCornerRadius))
                .background(MasterToolPalette.SurfaceRaised),
        ) {
            if (faceUp) {
                if (art != null) {
                    Text(
                        art.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(2.dp),
                    )
                    AsyncImage(
                        // The thumbnail is right for a card lying on the table
                        // and visibly soft at nearly twice the size, which is
                        // exactly when someone is trying to read it.
                        model = if (magnified) {
                            art.imageUrl ?: art.imageUrlSmall
                        } else {
                            art.imageUrlSmall ?: art.imageUrl
                        },
                        contentDescription = art.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                CardBack(Modifier.fillMaxSize(), back.imageUrl)
            }

            // The light, over the picture and inside the card's own clip, so
            // the pool and the rim follow the cut corners. The pose is read
            // *here*, in a draw lambda, which is what keeps a card catching the
            // light through a whole flip without recomposing anything.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawCardSurface(
                        pose = motion.pose,
                        material = material,
                        eye = camera.eye,
                        look = look,
                        radiusPx = CardCornerRadius.toPx(),
                        // Read *here*, in the draw lambda, so it subscribes to
                        // the plane's snapshot state and re-draws when the
                        // camera moves. Taken from the sorted seat list instead
                        // it would freeze mid-orbit: that list is a
                        // `derivedStateOf` with a structural policy, so it
                        // re-runs only when the resulting *order* changes —
                        // which is the exact bug `StageCameraState` records
                        // about the specular pool.
                        haze = if (look.scene == Scene.MINIMAL) {
                            0f
                        } else {
                            Defocus.hazeAt(
                                depth = camera.plane.project(motion.pose.position).depth,
                                cameraDistance = camera.plane.cameraDistance,
                                // The board's own half-depth, so the focus
                                // plane spans the table rather than a fixed
                                // fraction of the camera distance — which is
                                // between four and thirty-seven times the
                                // table, depending on the seat, and is why
                                // this dial did nothing anybody could find.
                                reach = camera.plane.depthReach,
                                focus = focus.depth,
                                fNumber = focus.fNumber,
                                strength = focus.strength,
                            )
                        },
                    )

                    // And the foil, last, so it sits over the light the way a
                    // sleeve sits over a card.
                    //
                    // Its angle comes from **where the card is**, not from a
                    // pointer — a card on this table has no pointer on it — and
                    // that is what makes it read as light rather than as an
                    // outline. Drawn at one angle it was exactly that: sixty
                    // cards each wearing the identical pink-left, cyan-right
                    // band, which is a border with a gradient in it and not a
                    // room. Swung by the card's place on the mat, the hand fans
                    // through the spectrum, a card carried across the table
                    // sweeps its own, and every one of them agrees about which
                    // way the light is — because they are all being told the
                    // same thing about where they are standing.
                    //
                    // The other two terms are the card and the room: [rotZ] is
                    // what a hand does to a sleeve, and the yaw at a third rate
                    // is the table turning under it — enough that walking round
                    // changes the picture, little enough that it does not spin.
                    if (foiled) {
                        val plane = camera.plane
                        val across = (motion.pose.position.x - plane.centreX) / plane.width
                        val down = (motion.pose.position.y - plane.centreY) / plane.height
                        drawPrismaticInset(
                            angleDegrees = FOIL_REST +
                                motion.pose.rotZ -
                                plane.yawDegrees / 3f +
                                across * 90f -
                                down * 55f,
                            cornerRadiusPx = CardCornerRadius.toPx(),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MasterToolPalette.Ink.copy(alpha = 0.82f))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = MasterToolPalette.Text,
            maxLines = 1,
        )
    }
}

/**
 * The frame clock, on the glass.
 *
 * A `Canvas` rather than a `Text`, and the whole reason is the thing it is
 * measuring. A `Text` whose string changes every frame recomposes every frame,
 * which is a cost the instrument would be adding to the very number it prints —
 * an ammeter wired through itself. Drawing it instead invalidates one draw and
 * touches no composition, which is the rule [StageCard] and `EasterEgg` already
 * follow for everything that moves at frame rate.
 *
 * It still allocates: [FrameProbe.readout] builds a string and the measurer lays
 * it out, once a frame, and only while this is on screen. That is the price of
 * the UI layer having no arithmetic in it, and it is paid by a debug overlay
 * rather than by the stage.
 */
@Composable
private fun FrameReadout(probe: FrameProbe, tick: () -> Long, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = MasterToolPalette.Text)
    Canvas(
        modifier
            .padding(start = 10.dp, top = TOP_BAR + 6.dp)
            .size(width = 340.dp, height = 15.dp),
    ) {
        // The read that subscribes this draw to the frame loop. Its value is
        // never used — it is the clock, not the reading.
        if (tick() == Long.MIN_VALUE) return@Canvas
        val laid = measurer.measure(probe.readout(), style)
        val width = laid.size.width.toFloat()
        val height = laid.size.height.toFloat()
        drawRect(
            color = MasterToolPalette.Ink.copy(alpha = 0.78f),
            topLeft = Offset(-3f, 0f),
            size = Size(width + 6f, height),
        )
        drawText(laid)
    }
}

@Composable
private fun PlayTopBar(
    play: PlayState,
    camera: StageCameraState,
    onBack: () -> Unit,
    onGuide: () -> Unit,
    onReadout: () -> Unit,
    /**
     * The tuning panel, on a **long press** of the same number.
     *
     * Not a fourth and fifth tap: a five-tap passes through the three-tap on the
     * way up, so it would open the frame readout every time on its way to the
     * panel. A long press does not collide with a count at all, and the number
     * is still the only element in this bar that is pure output.
     *
     * And not a button. This row is a hand-rolled `Row` whose `BAR_FITS_AT` was
     * measured against the buttons it already has; a sixteenth one either moves
     * that number or clips "New hand" on a phone.
     */
    onTuner: () -> Unit,
    room: String,
    onRoom: () -> Unit,
    /** Whether the felt currently answers a finger — see `UiPreferences.cameraTouch`. */
    cameraTouch: Boolean,
    onCameraTouch: () -> Unit,
    headSway: Boolean,
    onHeadSway: () -> Unit,
) {
    val feedback = LocalFeedback.current

    // ---- narrow screens ------------------------------------------------------
    //
    // This bar was a fixed `Row` with a `weight(1f)` spacer in the middle of it,
    // which on the tablet it was designed against looks exactly right and on a
    // phone silently throws half the controls off the end of the screen. At
    // 640dp — an ordinary phone in landscape — everything after "Seated" is
    // gone: the phase, the turn, Draw, Shuffle, Undo, Redo and New hand. The
    // stage renders perfectly and cannot be played.
    //
    // A `Row` cannot both hug the right edge and scroll: a weighted child needs
    // a finite width and a scroll container hands it infinity. So there are two
    // arrangements and the width picks one. Wide enough, and it is the bar that
    // shipped, to the pixel — the weighted spacer and all. Too narrow, and the
    // spacer goes and the row scrolls, so nothing is ever unreachable.
    //
    // [BAR_FITS_AT] is measured rather than guessed; see its KDoc.
    BoxWithConstraints(Modifier.fillMaxWidth().height(TOP_BAR).background(MasterToolPalette.Ink)) {
        val roomy = maxWidth >= BAR_FITS_AT
        val scroll = rememberScrollState()
        Row(
            Modifier
                .fillMaxWidth()
                .height(TOP_BAR)
                .then(if (roomy) Modifier else Modifier.horizontalScroll(scroll))
            // Chrome stands on Ink, and until now this bar stood on whatever the
            // room happened to be putting behind it.
            //
            // The stage's box is padded down by [TOP_BAR], so the room ought not
            // to reach up here at all — but the mat's `graphicsLayer` is a
            // rotation and a rotation does not clip, so everything at the back
            // of the room projects up past the box it was drawn in and lands on
            // this strip. Most of the time that is the wall, which is dark, and
            // nobody noticed. The window is not dark. Its pane came out behind
            // seven of these controls at about 1.5:1, and the life-point total
            // sat half inside its left edge.
            //
            // Measured rather than guessed, and the measurement is the reason
            // this is a ground rather than a re-framing: at 1600x1000 the pane
            // occupies rows 0..36 and the bar is 44 tall, so **every row of the
            // window is inside the chrome** and none of it was ever visible as a
            // window. Behind it the wall itself gets 6px of open air at the
            // table seat and 25 at the other two. What to do about a room whose
            // back wall is a hairline is a question about the stage's framing
            // and belongs to kai; what a bar does about a bright thing behind it
            // is not, and is this.
            //
            // Opaque, not a scrim: a translucent wash over content is the second
            // line of the handbook's anti-patterns, and a bar that is 82% opaque
            // is a bar that fails on the one background it needed to survive.
            // It lives on the box now, so it covers the width even when the row
            // inside it is scrolled.
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BarButton("← Deck", onClick = onBack)
        // Second from the left, where a thing you need in the first minute
        // belongs. The table has no affordances on it by design, so this button
        // is the entire discoverability budget of every gesture on the stage —
        // it is not going at the far end of a row of fifteen.
        BarButton("? Guide", onClick = onGuide)
        Divider()
        // Three taps on the life-point *number* opens the frame readout.
        //
        // The number is the one thing in this bar that is pure output — every
        // other element here already means something when you touch it — which
        // is what makes it the only place a hidden gesture can go without taking
        // a real one away. `ChibiLogo` established the idiom and this is the
        // same window and the same count.
        //
        // Deliberately **not** in `ShortcutTable`, and that is the one house
        // rule this knowingly steps around. The table is the help sheet, so
        // anything in it is documented to the user by construction; a debug
        // instrument that advertises itself in the help sheet is not hidden, and
        // an instrument is not a feature that owes anybody both idioms.
        var taps by remember { mutableStateOf(0) }
        LaunchedEffect(taps) {
            if (taps == 0) return@LaunchedEffect
            if (taps >= READOUT_TAPS) {
                taps = 0
                onReadout()
                return@LaunchedEffect
            }
            delay(READOUT_TAP_WINDOW_MS)
            taps = 0
        }
        Text(
            play.field.lifePoints.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (play.field.lifePoints <= 0) MasterToolPalette.Danger else Color.Unspecified,
            // A raw tap detector rather than `combinedClickable`, and that is
            // not a style preference. `combinedClickable` makes its node
            // focusable, and adding a newly-focusable node to this bar ate the
            // **first** key event the stage received afterwards — which in
            // `:studio` meant the first shot of every run silently came back at
            // the table seat whatever seat it had asked for. Two identical runs
            // then differ, and the loop's whole claim is that they do not.
            // `detectTapGestures` touches no focus and gives both idioms.
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = { onTuner() }) { taps++ }
            },
        )
        listOf(-1000, -500, -100, 100).forEach { delta ->
            BarButton(if (delta > 0) "+$delta" else "$delta") {
                play.move { it.adjustLifePoints(delta) }
            }
        }
        Divider()
        // The seats, as the touch idiom for the camera. A drag on bare felt
        // turns the table and a key puts it back, but neither of those is
        // discoverable, and a row of four words is.
        //
        // Four rather than three since the POV chair, and the last of them is
        // not like the other three: those are places to *read* the board from
        // and it is a place to *be* at it. The bar does not say so — the row is
        // in descending order of how far above the table you are, so it says it
        // by where it sits.
        StageSeat.entries.forEach { seat ->
            BarButton(seat.label) { camera.sitAt(seat) }
        }
        // Whether the felt answers a finger, in the seat group because it is a
        // fact about the camera and this is where the camera lives on the bar.
        //
        // A latch rather than a menu item: it is the one setting on this screen
        // somebody flips *mid-game*, when a hand resting on the tablet has just
        // swung the board, and a thing you reach for in that moment cannot be
        // two taps inside a dropdown. The glyph carries the state on its own —
        // ⦿ is an open eye's worth of "the felt is live", ⦻ is it crossed out.
        BarButton(if (cameraTouch) "Camera ⦿" else "Camera ⦻", onClick = onCameraTouch)
        // And whether the tablet's own tilt does, beside it because they are the
        // same kind of question — which of the two things that can move the
        // camera without being asked is switched on. Both ship off.
        BarButton(if (headSway) "Tilt ⦿" else "Tilt ⦻", onClick = onHeadSway)
        // Beside the seats, because choosing a room and choosing where to sit
        // are the same kind of act — neither is about a card — and because one
        // button that cycles is what the theme setting already does with three
        // values. A submenu for four would be a menu to open, read and dismiss
        // in order to change something the whole screen previews instantly.
        BarButton(room, onClick = onRoom)
        Divider()
        BarButton(play.field.phase.label) { play.move { it.nextPhase() } }
        BarButton("Turn ${play.field.turn} ⟳") { play.move { it.endTurn() } }

        // Pushes the right-hand group to the far edge when there is an edge to
        // push it to. Under a scroll container there is not — a weighted child
        // needs a finite width — so a narrow bar gets an ordinary gap instead.
        if (roomy) Box(Modifier.weight(1f)) else Spacer(Modifier.width(20.dp))

        play.announcement?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                color = MasterToolPalette.AccentBright,
            )
        }
        BarButton("Draw") {
            if (play.move { it.draw() }) feedback.play(SoundEffect.DEAL, Haptic.DEAL)
        }
        BarButton("Shuffle") {
            if (play.shuffle(BoardSlot.Deck)) feedback.play(SoundEffect.SHUFFLE, Haptic.SHUFFLE)
        }
        BarButton("Undo", enabled = play.canUndo) { play.undo() }
        BarButton("Redo", enabled = play.canRedo) { play.redo() }
        BarButton("New hand") { play.restart() }
        }
    }
}

/**
 * The width the play stage's bar needs to show every control at once.
 *
 * Measured, not chosen: at 914dp the row clips inside "Shuffle" and at 640dp it
 * clips after "Seated". Rounded up from where the last button's right edge
 * lands so there is a little air, and deliberately generous — being wrong on the
 * roomy side costs a scrollbar nobody needs, and being wrong on the narrow side
 * costs somebody the New hand button.
 *
 * A tablet in landscape is far past it and takes the arrangement that shipped.
 *
 * Raised by 84dp when the camera latch joined the seat group: "Camera ⦻" is nine
 * characters at 11sp plus this bar's 18dp of horizontal padding and its 6dp gap.
 * Anything added to this row has to pay the same toll — the alternative is that
 * the last button silently falls off a phone, which is what this constant exists
 * to have caught once already.
 */
private val BAR_FITS_AT = 1264.dp

@Composable
private fun Divider() {
    Box(
        Modifier
            .width(1.dp)
            .height(18.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
internal fun BarButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(2.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            maxLines = 1,
        )
    }
}

/** Whether a point in mat pixels is inside a rectangle, with a little grace. */
internal fun Slot.holds(x: Float, y: Float, grace: Float = 0f): Boolean =
    x >= left - grace && x <= right + grace && y >= top - grace && y <= bottom + grace


/**
 * Whether [CardActions] would have anything to put in a menu about this.
 *
 * The one place that answer is decided, so the check and the menu cannot drift
 * apart into a gesture that opens an empty box.
 */
private fun hasMenu(origin: DragOrigin): Boolean = when (origin) {
    is DragOrigin.Mat -> true
    // The deck's menu is the deck's. Every other pile has nothing to offer that
    // holding it or dragging off it does not already do better.
    is DragOrigin.Pile -> origin.pile == BoardSlot.Deck
    is DragOrigin.Hand -> false
    // A card in a spread pile is somewhere you are passing through, not somewhere
    // it lives. Everything the menu could offer it — the graveyard, banished, the
    // hand — is a place on the table it can be dragged to instead, which is one
    // gesture rather than two and is the argument `docs/classic/TABLE.md` §6 makes
    // against the drawer in the first place.
    is DragOrigin.Buried -> false
}

/**
 * Everything a card can be told to do that is not a movement.
 *
 * Behind the two-finger hold, which keeps every one-finger gesture purely about
 * where a card is. The pointer idiom is a right-click on the same card, and the
 * keyboard one is the shortcut table — three producers, one menu.
 */
@Composable
private fun CardActions(
    play: PlayState,
    origin: DragOrigin,
    layout: BoardLayout,
    onDismiss: () -> Unit,
) {
    val id = (origin as? DragOrigin.Mat)?.id

    androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        // A lambda rather than a local `fun`, because a local function cannot be
        // @Composable and DropdownMenuItem is one. Every entry below dismisses
        // first and acts second, so the menu never outlives the card it is about.
        val item: @Composable (String, () -> Unit) -> Unit = { label, action ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(label) },
                onClick = { onDismiss(); action() },
            )
        }

        if (id != null) {
            val placed = play.field.placed(id)
            // "Set" above "Turn over", because it is the one people reach for
            // and because the guide has been pointing at the wrong one of the
            // two since the set gesture shipped. They are different verbs: this
            // asks the zone how the card should end up, that one turns it over
            // exactly where it lies.
            item("Set") { play.setDown(id, layout) }
            item("Turn over") { play.move { it.flip(id) } }
            item("Rotate") { play.move { it.rotate(id) } }
            item("Bring to front") { play.move { it.bringToFront(id) } }
            androidx.compose.material3.HorizontalDivider()
            item("To graveyard") { play.move { it.toGraveyard(id) } }
            item("Banish") { play.move { it.toBanish(id) } }
            item("Banish face-down") { play.move { it.toBanish(id, faceDown = true) } }
            item("To hand") { play.move { it.toHand(id) } }
            item("To deck top") { play.move { it.toDeckTop(id) } }
            item("To deck bottom") { play.move { it.toDeckBottom(id) } }
            androidx.compose.material3.HorizontalDivider()
            if ((placed?.depth ?: 1) > 1) {
                item("Take the top card off") {
                    play.move { field -> field.unstack(id, placed!!.at.let { it.copy(x = it.x + 0.06f) }) }
                }
            }
            if ((placed?.card?.materials?.size ?: 0) > 0) {
                item("Detach a material") { play.move { it.detachMaterial(id) } }
            }
            item("Add a counter") { play.move { it.addCounter(id, 1) } }
            if ((placed?.card?.counters ?: 0) > 0) {
                item("Remove a counter") { play.move { it.addCounter(id, -1) } }
            }
        } else if (origin is DragOrigin.Pile) {
            item("Shuffle the deck") { play.shuffle(BoardSlot.Deck) }
            item("Draw") { play.move { it.draw() } }
        }
    }
}
