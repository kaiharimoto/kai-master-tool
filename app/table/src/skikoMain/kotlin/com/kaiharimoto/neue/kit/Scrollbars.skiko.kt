package com.kaiharimoto.neue.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuColors

/** The family scrollbar (§5): 8px, square, ink-25 thumb, ink on hover, no track. */
fun muScrollbarStyle(c: MuColors) = ScrollbarStyle(
    minimalHeight = 24.dp,
    thickness = 8.dp,
    shape = RectangleShape,
    hoverDurationMillis = 120,
    unhoverColor = c.ink25,
    hoverColor = c.ink,
)

@Composable
actual fun BoxScope.ScrollbarFor(state: ScrollState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp), style = muScrollbarStyle(Mu.colors))
}

@Composable
actual fun BoxScope.ScrollbarFor(state: LazyListState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp), style = muScrollbarStyle(Mu.colors))
}

@Composable
actual fun BoxScope.ScrollbarFor(state: LazyGridState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp), style = muScrollbarStyle(Mu.colors))
}

