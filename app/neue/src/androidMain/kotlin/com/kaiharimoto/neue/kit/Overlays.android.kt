package com.kaiharimoto.neue.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PlatformTip(tooltip: @Composable () -> Unit, modifier: Modifier, above: Boolean, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}

@Composable
internal actual fun ProvideTextMenus(content: @Composable () -> Unit) = content()
