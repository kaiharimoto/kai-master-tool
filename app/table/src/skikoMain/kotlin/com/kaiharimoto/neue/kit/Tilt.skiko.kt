package com.kaiharimoto.neue.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.kaiharimoto.mastertool.core.motion.Tilt

/** A desk has no tilt: the pointer is the light. */
@Composable
actual fun rememberDeviceTilt(on: Boolean): State<Tilt?> = remember { mutableStateOf<Tilt?>(null) }
