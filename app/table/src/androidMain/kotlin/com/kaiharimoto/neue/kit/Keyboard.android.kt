package com.kaiharimoto.neue.kit

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/**
 * Whether the soft keyboard is up. Derived (the red team, 2026-10): the inset is animated, and read straight
 * it recomposed the whole shell on every frame of the keyboard's slide; this changes twice a slide.
 */
@Composable
actual fun softKeyboardVisible(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val open by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    return open
}
