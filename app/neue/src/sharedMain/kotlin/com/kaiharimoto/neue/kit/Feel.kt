package com.kaiharimoto.neue.kit

import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.core.haptics.Haptic

/**
 * Plays one word of the haptic vocabulary (touch swarm, rec 13) through the
 * platform's own touch feedback: no permission, and the system's switch for it
 * is honoured. The desk has no actuator, and plays nothing.
 */
@Composable
expect fun rememberFeel(): (Haptic) -> Unit
