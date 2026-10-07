package com.kaiharimoto.neue.kit

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

@Composable
actual fun softKeyboardVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0
