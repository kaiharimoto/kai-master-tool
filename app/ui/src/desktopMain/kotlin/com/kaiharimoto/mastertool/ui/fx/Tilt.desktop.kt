package com.kaiharimoto.mastertool.ui.fx

import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.core.motion.HeadTilt

/**
 * A monitor does not get tilted, and `:studio` must not be able to notice one.
 *
 * Empty rather than "returns zero", and the difference matters: this produces no
 * sample at all, so `HeadSway` never takes a reference and never leaves its
 * settled state, so the frame loop is never asked to write a plane. `:studio`
 * draws the real `PlayScreen` on this target, and two runs of it being
 * bit-identical is the loop's only instrument (`docs/classic/LOOP.md` §3). A seam that
 * *could* report something would put that guarantee in a runtime condition
 * rather than in the type system.
 *
 * If a desktop ever wants this — a Surface, a convertible — it wants a different
 * expectation than "the tablet is being held", and it can have its own.
 */
@Composable
actual fun DeviceTilt(enabled: Boolean, onTilt: (HeadTilt) -> Unit) = Unit
