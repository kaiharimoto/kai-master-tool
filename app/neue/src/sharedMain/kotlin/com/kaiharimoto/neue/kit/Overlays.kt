package com.kaiharimoto.neue.kit

import androidx.compose.foundation.layout.imePadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
@Composable
fun Tip(text: String, modifier: Modifier = Modifier, kbd: String? = null, above: Boolean = false, content: @Composable () -> Unit) {
    PlatformTip(
        tooltip = {
            Inverted {
                Row(
                    Modifier.widthIn(max = 320.dp).background(Mu.colors.paper).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Small(text, color = Mu.colors.ink)
                    if (kbd != null && LocalHardwareKeyboard.current) Mono(kbd, color = Mu.colors.ink70)
                }
            }
        },
        modifier = modifier,
        above = above,
        content = content,
    )
}

/**
 * Where a platform shows a tooltip: on the desktop a hover of 300 ms, under the
 * component (or [above] it); on a touch screen nothing — a long-press there is
 * the card's, and a control's words are its label.
 */
@Composable
internal expect fun PlatformTip(tooltip: @Composable () -> Unit, modifier: Modifier, above: Boolean, content: @Composable () -> Unit)

/** The text fields' own right-click menu, where a platform has one (the desktop's, drawn as a family menu). */
@Composable
internal expect fun ProvideTextMenus(content: @Composable () -> Unit)

/** One entry in a context menu. A null [onClick] is a group label. [reason] says why a disabled one is. */
data class MenuEntry(
    val label: String,
    val hint: String? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
    val reason: String? = null,
    val onClick: (() -> Unit)? = null,
)

/** A menu open at a point in the window, one at a time. */
data class MenuSpec(val at: Offset, val entries: List<MenuEntry>)

/**
 * Something anchored above the page but inside the window's own layer: a
 * select's list. A Compose `Popup` draws above everything in the window, the
 * family cursor included, and the family cursor must stay over the kit's own
 * menus (`CURSOR.md`, Rules) — so menus open here, and the cursor is drawn after.
 */
class Overlays {
    internal var layer by mutableStateOf<Anchored?>(null)

    fun show(at: Offset, onDismiss: () -> Unit, content: @Composable () -> Unit) {
        layer = Anchored(at, onDismiss, content)
    }

    /** Closes what is open. Returns false when nothing was, so Esc can fall through. */
    fun dismiss(): Boolean {
        val open = layer ?: return false
        layer = null
        open.onDismiss()
        return true
    }

    val isOpen: Boolean get() = layer != null
}

internal class Anchored(val at: Offset, val onDismiss: () -> Unit, val content: @Composable () -> Unit)

val LocalOverlays = staticCompositionLocalOf<Overlays?> { null }

/** The window's anchored layer: whatever [Overlays.show] opened, over the page and under the cursor. */
@Composable
fun OverlayLayer(overlays: Overlays) {
    val open = overlays.layer ?: return
    AnchoredBox(open.at, { overlays.dismiss() }) { open.content() }
}

/**
 * [content] at [at] in the window, kept inside it, over a transparent layer
 * that closes it on a press anywhere else — the press is spent on closing.
 */
@Composable
fun AnchoredBox(at: Offset, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            event.changes.forEach { it.consume() }
                            onDismiss()
                        }
                    }
                }
            },
        )
        Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val placed = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placed.forEach { p ->
                    val x = at.x.toInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                    val y = if (at.y + p.height > constraints.maxHeight) (constraints.maxHeight - p.height).coerceAtLeast(0) else at.y.toInt()
                    p.place(x, y)
                }
            }
        }
    }
}

/** Menu (§6): 1px ink frame, min 208, rows invert when highlighted, `✕` for destruction. */
@Composable
fun MenuLayer(spec: MenuSpec?, onDismiss: () -> Unit) {
    if (spec == null) return
    // In the window's own layer rather than a Popup, so the family cursor is drawn over it.
    // Esc reaches it through the window's key handler (NeueState.dismissTop).
    AnchoredBox(spec.at, onDismiss) { MenuColumn(spec.entries, onDismiss) }
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
                    reason = entry.reason,
                    last = true,
                    onClick = { onDismiss(); click() },
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
            .muClickable(onClick = onDismiss)
            // A dialog's field stays above the soft keyboard (touch swarm, rec 10).
            .imePadding(),
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
                IconButton(Icons.X, onDismiss, size = 24.dp, label = "Close")
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
                .muClickable(onClick = onDismiss),
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
                        IconButton(Icons.X, onDismiss, size = 24.dp, label = "Close")
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
