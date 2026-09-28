package com.kaiharimoto.mastertool.studio

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType

/**
 * Pointing at the table, for a harness that until now could only type at it.
 *
 * [Keys] was the whole of the studio's hand, and that turned out to bound what
 * the loop could ever look at. Two of the things `docs/classic/LOOP.md` §6 has been
 * asking for are the same missing mechanism: **a foil cannot be photographed**,
 * because `CardStock.of` foils face-up extra-deck cards and nothing in the
 * shortcut table opens the extra deck — spreading a pile is a tap; and
 * **nothing can be seen mid-orbit**, because turning the table is a drag on the
 * felt. Neither is reachable from a keyboard, and neither should be given a
 * fake shortcut: `Keys.of` deliberately refuses to name keys the app does not
 * bind, on the argument that a harness which can name a key nothing listens for
 * is a harness whose script fails silently.
 *
 * So the studio grows a finger instead, and it is a real one — the events go in
 * through `sendPointerEvent`, reach `MatInput`'s single `pointerInput(layout)`
 * exactly as a tablet's would, and are arbitrated by `MatDesk` like anybody
 * else's. The studio still cannot reach a state a player could not.
 *
 * ## Why the positions are fractions of the glass
 *
 * A tap is aimed at a *place on the screen*, not at a slot. The studio could
 * instead re-solve `BoardLayouter` and `StagePlane` and project the extra deck's
 * own rectangle — but that is a second copy of the camera, and it would have to
 * agree with a pose the screen may have reached through a seat press and
 * `CameraFit`. A second copy that has to agree is the shape of bug this
 * repository keeps finding; there is no version of it that stays honest.
 *
 * Fractions cannot drift, because they are not derived from anything. What they
 * cost is that they have to be *found by looking*, which is the loop's own
 * method anyway: shoot, see where the deck is, aim there, shoot again.
 */
internal object Pointer {

    /**
     * A press and a release at one point, with the table given time in between.
     *
     * The frames are not decoration. `MatPilot` reports to two clocks — pointer
     * events and the frame loop — and a press that is released in the same
     * frame never gets a tick, so a gesture that resolves on the loop (a peek,
     * the two-finger menu) computes correctly and then vanishes. That exact bug
     * is written up in `CLAUDE.md`; this is the harness end of it.
     */
    suspend fun tap(
        scene: ImageComposeScene,
        clock: FrameClock,
        x: Float,
        y: Float,
        holdFrames: Int = 8,
        settleFrames: Int = 60,
    ) {
        val at = Offset(x, y)
        scene.sendPointerEvent(PointerEventType.Press, at)
        clock.run(holdFrames)
        scene.sendPointerEvent(PointerEventType.Release, at)
        clock.run(settleFrames)
    }

    /**
     * A press, a swept drag, and a release — which on the felt is an orbit.
     *
     * Swept rather than jumped, because a single enormous move is not what any
     * hand does and is not what the arbiter is tuned against: `MatDesk` decides
     * what a gesture *is* from the first few events, and a camera flick's rate
     * comes out of the last ones. [steps] is the resolution of that sweep.
     *
     * Note that letting go of a turn does not stop it — the table coasts — so
     * the settle after the release is doing real work rather than padding.
     */
    suspend fun drag(
        scene: ImageComposeScene,
        clock: FrameClock,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int = 12,
        settleFrames: Int = 90,
    ) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(fromX, fromY))
        clock.run(2)
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            scene.sendPointerEvent(
                PointerEventType.Move,
                Offset(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t),
            )
            clock.run(2)
        }
        scene.sendPointerEvent(PointerEventType.Release, Offset(toX, toY))
        clock.run(settleFrames)
    }
}
