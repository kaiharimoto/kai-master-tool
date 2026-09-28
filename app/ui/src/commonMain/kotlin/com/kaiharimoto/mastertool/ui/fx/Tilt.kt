package com.kaiharimoto.mastertool.ui.fx

import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.core.motion.HeadTilt

/**
 * How the device is being held, sampled while [enabled] is true.
 *
 * A platform seam rather than a dependency, for the same reason [localHour] is:
 * `core` deliberately has no platform source sets — it compiles and its tests
 * run anywhere, including an environment with no Android SDK — so the one impure
 * question head sway asks is asked here, and `HeadSway` stays a pure function of
 * numbers a test can supply.
 *
 * ## What each platform does, and why the desktop one is not a stub
 *
 * Android reads `TYPE_GAME_ROTATION_VECTOR`. Desktop returns **nothing, ever**,
 * and that is a decision rather than a gap: `:studio` runs the real `PlayScreen`
 * headlessly on the desktop target, and two runs of it being bit-identical is
 * the loop's whole eye (`docs/classic/LOOP.md` §3). A sensor that reported anything at
 * all there would end that, so the guarantee is structural — there is no code
 * path on desktop that can produce a sample.
 *
 * ## It calls back rather than returning a value
 *
 * A `HeadTilt` returned from a composable would be snapshot state read in a
 * composable body, which recomposes the board — sixty cards — every time a wrist
 * moves, and re-keys the `pointerInput` that is holding whatever gesture is in
 * progress. That is the failure `StageCameraState` spends thirty lines
 * forbidding, and it would arrive here through the one thing on the stage that
 * updates without anybody touching it. So samples are pushed into a plain object
 * and read inside a `graphicsLayer`, exactly as the camera's own pose is.
 *
 * @param enabled whether to listen at all. False unregisters, because a sensor
 *   left registered is a battery cost for a feature that is off — and this one
 *   ships off.
 * @param onTilt called on the main thread with each reading.
 */
@Composable
expect fun DeviceTilt(enabled: Boolean, onTilt: (HeadTilt) -> Unit)
