package com.kaiharimoto.neue.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.kaiharimoto.neue.theme.Mu

/**
 * A finger scrolls what it touches, and Android draws no bar of its own: a pane that
 * went on below the fold looked finished (touch swarm, rec 28). So a thumb is drawn —
 * 3dp of ink25, square, no track, 2dp in from the right, at least 24dp tall — whenever
 * the content overflows, with no fade. It takes no input: it never competes with the
 * finger's scroll.
 */
@Composable
private fun BoxScope.Thumb(shown: Float, at: Float) {
    if (shown >= 1f) return
    val ink = Mu.colors.ink25
    Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp).width(3.dp)) {
        val height = maxOf(24.dp.toPx(), size.height * shown.coerceIn(0f, 1f))
        val top = (size.height - height) * at.coerceIn(0f, 1f)
        drawRect(ink, Offset(0f, top), Size(size.width, height))
    }
}

@Composable
actual fun BoxScope.ScrollbarFor(state: ScrollState) {
    if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return
    val viewport = state.viewportSize.toFloat()
    Thumb(shown = viewport / (viewport + state.maxValue), at = state.value.toFloat() / state.maxValue)
}

@Composable
actual fun BoxScope.ScrollbarFor(state: LazyListState) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible >= total) return
    Thumb(shown = visible.toFloat() / total, at = state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1))
}

@Composable
actual fun BoxScope.ScrollbarFor(state: LazyGridState) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible >= total) return
    Thumb(shown = visible.toFloat() / total, at = state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1))
}
