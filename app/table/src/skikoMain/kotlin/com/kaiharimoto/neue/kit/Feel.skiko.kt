package com.kaiharimoto.neue.kit

import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.core.haptics.Haptic

@Composable
actual fun rememberFeel(): (Haptic) -> Unit = {}
