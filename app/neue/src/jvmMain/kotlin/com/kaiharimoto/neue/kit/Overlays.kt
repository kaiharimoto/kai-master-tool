package com.kaiharimoto.neue.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.key.key
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion

/**
 * Tooltip (§6): an ink box, 12px paper text, no arrow, 300 ms. Copy is a short
 * sentence or a shortcut, and tooltips are how a disabled control explains
 * itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, modifier: Modifier = Modifier, kbd: String? = null, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Inverted {
                Row(
                    Modifier.widthIn(max = 320.dp).background(Mu.colors.paper).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Small(text, color = Mu.colors.ink)
                    if (kbd != null) Mono(kbd, color = Mu.colors.ink70)
                }
            }
        },
        modifier = modifier,
        delayMillis = 300,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 20.dp)),
        content = content,
    )
}

/** One entry in a context menu. A null [onClick] is a group label. */
data class MenuEntry(
    val label: String,
    val hint: String? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** A menu open at a point in the window, one at a time. */
data class MenuSpec(val at: Offset, val entries: List<MenuEntry>)

/** Menu (§6): 1px ink frame, min 208, rows invert when highlighted, `✕` for destruction. */
@Composable
fun MenuLayer(spec: MenuSpec?, onDismiss: () -> Unit) {
    if (spec == null) return
    Popup(
        offset = IntOffset(spec.at.x.toInt(), spec.at.y.toInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onPreviewKeyEvent = { event ->
            if (androidx.compose.ui.input.key.Key.Escape == event.key) {
                onDismiss(); true
            } else {
                false
            }
        },
    ) {
        MenuColumn(spec.entries, onDismiss)
    }
}

@Composable
fun MenuColumn(entries: List<MenuEntry>, onDismiss: () -> Unit, modifier: Modifier = Modifier.widthIn(min = 208.dp, max = 320.dp)) {
    val c = Mu.colors
    Column(modifier.background(c.paper).border(1.dp, c.ink)) {
        entries.forEachIndexed { i, entry ->
            if (entry.separatorBefore && i > 0) HRule()
            val click = entry.onClick
            if (click == null) {
                Micro(entry.label, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), color = c.ink45)
            } else {
                MenuRow(
                    text = entry.label,
                    hint = entry.hint,
                    danger = entry.danger,
                    enabled = entry.enabled,
                    last = true,
                    onClick = { onDismiss(); click() },
                )
            }
        }
    }
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

/** Swallows clicks so nothing under an overlay reacts. */
private fun Modifier.blockClicks() = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/**
 * Dialog (§6): paper at 85% behind, never blurred; a 1px ink frame, 24 in. Only
 * destruction asks for confirmation.
 */
@Composable
fun MuDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 512.dp,
    description: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Mu.colors
    Box(
        Modifier.fillMaxSize().background(c.overlay)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier
                .width(width)
                .background(c.paper)
                .border(1.dp, c.ink)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                H2(title, Modifier.weight(1f).padding(end = 32.dp), maxLines = 2)
                IconButton(Icons.X, onDismiss, size = 24.dp)
            }
            if (description != null) RowText(description, Modifier.padding(top = 4.dp), color = c.ink70, maxLines = 4)
            Column(Modifier.padding(top = 20.dp), content = content)
            if (footer != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { footer() }
            }
        }
    }
}

/**
 * Drawer (§6): anchored right, `min(800, 85%)` wide, enters from x+24 with a
 * fade over 180 ms; the backdrop fades in 120 ms. Esc closes it (the window's
 * key handler does that, through `DISMISS`).
 */
@Composable
fun BoxScope.MuDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Mu.colors
    AnimatedVisibility(visible, enter = fadeIn(tween(MuMotion.FAST)), exit = fadeOut(tween(MuMotion.FAST))) {
        Box(
            Modifier.fillMaxSize().background(c.overlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = fadeIn(tween(MuMotion.BASE, easing = MuMotion.ease)) +
            slideInHorizontally(tween(MuMotion.BASE, easing = MuMotion.ease)) { 24 },
        exit = fadeOut(tween(MuMotion.FAST)) + slideOutHorizontally(tween(MuMotion.FAST)) { 24 },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.85f).widthIn(max = 800.dp), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 800.dp)
                    .fillMaxWidth()
                    .background(c.paper)
                    .drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                    .blockClicks(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                        .padding(24.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { header() }
                        IconButton(Icons.X, onDismiss, size = 24.dp)
                    }
                }
                content()
            }
        }
    }
}

/** A toast (§6): an ink box, 13px, bottom-right; success and failure look the same. */
@Composable
fun ToastBox(message: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Inverted {
        val c = Mu.colors
        Row(
            modifier.widthIn(max = 480.dp).background(c.paper).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RowText(message, Modifier.weight(1f, fill = false), color = c.ink, maxLines = 3)
            if (action != null) {
                Inverted { MuButton(action, onAction, size = BtnSize.SM, variant = BtnVariant.SECONDARY) }
            }
        }
    }
}
