package com.kaiharimoto.neue.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import com.kaiharimoto.mastertool.core.motion.Tilt

/**
 * How the phone is tipped (kai, v1.3.6), −1..1 on each axis about how it is being held
 * (`TiltFilter`), for the foil to follow: null where there is no sensor, where it is
 * turned off, and on the desk, where the pointer is the light. Read it in a draw
 * phase — it changes many times a second, and a card should redraw its light, never
 * recompose.
 */
val LocalTilt = staticCompositionLocalOf<State<Tilt?>?> { null }

/**
 * The phone's tilt itself, for the full-screen card (`Showcase`) alone (1.0.92): [LocalTilt] stands still for the
 * cards under it while it covers them, since a light nobody can see is fifty redraws a second of every card.
 */
val LocalDeviceTilt = staticCompositionLocalOf<State<Tilt?>?> { null }

/** The platform's tilt, listened to only while [on] and while the app is in front. */
@Composable
expect fun rememberDeviceTilt(on: Boolean): State<Tilt?>
