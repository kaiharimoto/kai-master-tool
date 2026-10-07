package com.kaiharimoto.neue.kit

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun PlatformTip(tooltip: @Composable () -> Unit, modifier: Modifier, above: Boolean, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = tooltip,
        modifier = modifier,
        delayMillis = 300,
        // Under the component, below the slot where the family cursor writes its caption
        // (5 px frame + 6 px gap + 20 px caption), so the two never sit on each other.
        // [above], for something at the bottom of the window: below it there is no room,
        // and a tip pushed back up onto its own button covers it (the rail's Pin, 1.0.15).
        tooltipPlacement = if (above) {
            TooltipPlacement.ComponentRect(anchor = Alignment.TopStart, alignment = Alignment.TopEnd, offset = DpOffset(0.dp, (-8).dp))
        } else {
            TooltipPlacement.ComponentRect(anchor = Alignment.BottomStart, alignment = Alignment.BottomEnd, offset = DpOffset(0.dp, 36.dp))
        },
        content = content,
    )
}

@Composable
actual fun ProvideTextMenus(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContextMenuRepresentation provides remember { MuContextMenuRepresentation() }, content = content)
}

/**
 * The text fields' own right-click menu (Cut, Copy, Paste, Select all), drawn
 * as a family menu rather than the platform default.
 */
class MuContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status is ContextMenuState.Status.Open) {
            Popup(
                offset = IntOffset(status.rect.left.toInt(), status.rect.bottom.toInt()),
                onDismissRequest = { state.status = ContextMenuState.Status.Closed },
                properties = PopupProperties(focusable = true),
            ) {
                MenuColumn(
                    items().map { MenuEntry(it.label, onClick = it.onClick) },
                    onDismiss = { state.status = ContextMenuState.Status.Closed },
                )
            }
        }
    }
}

