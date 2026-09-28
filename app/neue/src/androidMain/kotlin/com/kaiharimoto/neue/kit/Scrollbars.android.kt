package com.kaiharimoto.neue.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable

// A finger scrolls what it touches; Android draws no bar, and neither does Neue there.
@Composable
actual fun BoxScope.ScrollbarFor(state: ScrollState) = Unit

@Composable
actual fun BoxScope.ScrollbarFor(state: LazyListState) = Unit

@Composable
actual fun BoxScope.ScrollbarFor(state: LazyGridState) = Unit
