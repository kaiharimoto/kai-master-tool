package com.kaiharimoto.neue.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.theme.MuType

private fun <T> fast() = tween<T>(MuMotion.FAST, easing = MuMotion.ease)

@Composable
fun animatedColor(target: Color) = animateColorAsState(target, fast(), label = "mu").value

enum class BtnVariant { PRIMARY, SECONDARY, SUBTLE, GHOST }

enum class BtnSize(val height: Dp, val padding: Dp, val text: TextUnit, val icon: Dp) {
    SM(32.dp, 12.dp, 11.sp, 14.dp),
    MD(36.dp, 16.dp, 12.sp, 16.dp),
    LG(48.dp, 24.dp, 13.sp, 16.dp),
}

/**
 * Button (§6). Labels are micro caps; an icon leads, an arrow trails. There is
 * no danger variant: destruction is a plain button labelled `Delete`, or a
 * menu item with `✕`.
 */
@Composable
fun MuButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.SECONDARY,
    size: BtnSize = BtnSize.MD,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    arrow: Boolean = false,
    toggled: Boolean = false,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val hot = (hovered && enabled) || toggled
    val (bg, fg, edge) = when (variant) {
        BtnVariant.PRIMARY -> if (hot) Triple(c.paper, c.ink, c.ink) else Triple(c.ink, c.paper, c.ink)
        BtnVariant.SECONDARY -> if (hot) Triple(c.ink, c.paper, c.ink) else Triple(c.paper, c.ink, c.ink)
        BtnVariant.SUBTLE -> if (hot) Triple(c.ink, c.paper, c.ink) else Triple(c.paper, c.ink, c.ink25)
        BtnVariant.GHOST -> when {
            toggled -> Triple(c.ink, c.paper, Color.Transparent)
            hovered && enabled -> Triple(c.ink06, c.ink, Color.Transparent)
            else -> Triple(Color.Transparent, c.ink70, Color.Transparent)
        }
    }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.3f)
            .height(size.height)
            .background(animatedColor(bg))
            .border(1.dp, animatedColor(edge))
            .hoverable(source, enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = size.padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val tint = animatedColor(fg)
        if (icon != null) MuIcon(icon, tint, Modifier.size(size.icon))
        Micro(label, color = tint, size = size.text)
        if (arrow) MuText("→", style = MuType.micro(LocalMuFonts.current, size.text), color = tint)
    }
}

/** A square icon-only button, ghost by default. Always give it a tooltip. */
@Composable
fun IconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    enabled: Boolean = true,
    toggled: Boolean = false,
    variant: BtnVariant = BtnVariant.GHOST,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val (bg, fg, edge) = when {
        toggled -> Triple(c.ink, c.paper, c.ink)
        variant == BtnVariant.SECONDARY && hovered && enabled -> Triple(c.ink, c.paper, c.ink)
        variant == BtnVariant.SECONDARY -> Triple(c.paper, c.ink, c.ink)
        hovered && enabled -> Triple(c.ink06, c.ink, Color.Transparent)
        else -> Triple(Color.Transparent, c.ink70, Color.Transparent)
    }
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.3f)
            .background(animatedColor(bg))
            .border(1.dp, animatedColor(edge))
            .hoverable(source, enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MuIcon(icon, animatedColor(fg), Modifier.size(if (size >= 36.dp) 16.dp else 14.dp))
    }
}

/** A micro-caps text link: `Show log`, `Start over`, `All decks →`. */
@Composable
fun MicroLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Mu.colors.ink45) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Micro(
        text,
        modifier
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        color = animatedColor(if (hovered) Mu.colors.ink else color),
    )
}

/**
 * Single-line input: underline only, never a box, never a leading icon (§6).
 * Border goes to ink on focus.
 */
@Composable
fun MuInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    dense: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onSubmit: (() -> Unit)? = null,
    textStyle: TextStyle? = null,
) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val line = animatedColor(if (focused || hovered) c.ink else c.ink25)
    val style = (textStyle ?: if (mono) MuType.mono(f, if (dense) 11.sp else 13.sp) else if (dense) MuType.help(f) else MuType.body(f))
        .copy(color = c.ink)
    Box(
        modifier
            .height(if (dense) 28.dp else 36.dp)
            .hoverable(source)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            MuText(placeholder, style = style, color = c.ink45, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(c.ink),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged { onFocusChange(it.isFocused) },
        )
    }
}

/** Select (§6): a bordered trigger with a `▼` caret, a ruled popup, the highlighted row inverted. */
@Composable
fun <T> MuSelect(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val c = Mu.colors
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var widthPx by remember { mutableStateOf(0) }
    val height = if (small) 32.dp else 36.dp
    val density = LocalDensity.current
    Box(modifier) {
        Row(
            Modifier
                .height(height)
                .onSizeChanged { widthPx = it.width }
                .background(c.paper)
                .border(1.dp, animatedColor(if (open || hovered) c.ink else c.ink25))
                .hoverable(source)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null) { open = !open }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowText(label(value), Modifier.weight(1f))
            MuText("▼", style = MuType.help(LocalMuFonts.current).copy(fontSize = 10.sp), color = c.ink70)
        }
        if (open) {
            Popup(
                offset = IntOffset(0, with(density) { height.roundToPx() } - 1),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(with(density) { widthPx.toDp() }.coerceAtLeast(144.dp))
                        .background(c.paper)
                        .border(1.dp, c.ink),
                ) {
                    options.forEachIndexed { i, option ->
                        MenuRow(
                            text = label(option),
                            selected = option == value,
                            last = i == options.lastIndex,
                            onClick = { open = false; onSelect(option) },
                        )
                    }
                }
            }
        }
    }
}

/** Segmented control (§6), for two to five exclusive options. */
@Composable
fun <T> Segmented(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val c = Mu.colors
    Row(modifier.height(if (small) 28.dp else 36.dp).border(1.dp, c.ink)) {
        options.forEachIndexed { i, option ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink))
            val selected = option == value
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(animatedColor(if (selected) c.ink else if (hovered) c.ink06 else Color.Transparent))
                    .hoverable(source)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = source, indication = null) { onSelect(option) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Micro(label(option), color = if (selected) c.paper else c.ink, size = if (small) 10.sp else 11.sp)
            }
        }
    }
}

/** Switch (§6): a square thumb in a ruled track, 120 ms. */
@Composable
fun MuSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val x by animateDpAsState(if (checked) 20.dp else 2.dp, fast(), label = "thumb")
    Box(
        modifier
            .size(36.dp, 18.dp)
            .background(animatedColor(if (checked) c.ink else c.paper))
            .border(1.dp, c.ink)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(12.dp)
                .background(if (checked) c.paper else c.ink),
        )
    }
}

/** Checkbox (§6): a 14px square, filled when checked, no tick glyph. */
@Composable
fun MuCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Box(
        modifier
            .size(14.dp)
            .background(if (checked) c.ink else c.paper)
            .border(1.dp, c.ink)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(!checked) },
    )
}

/**
 * Slider (§6): a 1px track, ink up to the value, a 10px square thumb. Always
 * shown with a mono readout, which the caller places.
 */
@Composable
fun MuSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    val c = Mu.colors
    var widthPx by remember { mutableStateOf(1) }
    val span = range.endInclusive - range.start
    fun snap(v: Float): Float {
        val clamped = v.coerceIn(range.start, range.endInclusive)
        if (steps <= 0) return clamped
        val step = span / (steps + 1)
        return range.start + kotlin.math.round((clamped - range.start) / step) * step
    }
    fun at(x: Float) = snap(range.start + (x / widthPx.coerceAtLeast(1)) * span)
    val fraction = if (span > 0f) ((value - range.start) / span).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .height(20.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(range, steps) { detectTapGestures { onChange(at(it.x)) } }
            .pointerInput(range, steps) {
                detectDragGestures(onDragStart = { onChange(at(it.x)) }) { change, _ ->
                    change.consume()
                    onChange(at(change.position.x))
                }
            }
            .drawBehind {
                val y = size.height / 2f
                val split = size.width * fraction
                drawLine(c.ink25, Offset(split, y), Offset(size.width, y), 1.dp.toPx())
                drawLine(c.ink, Offset(0f, y), Offset(split, y), 1.dp.toPx())
                val t = 10.dp.toPx()
                val left = (split - t / 2f).coerceIn(0f, size.width - t)
                drawRect(c.paper, Offset(left - 1.dp.toPx(), y - t / 2f - 1.dp.toPx()), androidx.compose.ui.geometry.Size(t + 2.dp.toPx(), t + 2.dp.toPx()))
                drawRect(c.ink, Offset(left, y - t / 2f), androidx.compose.ui.geometry.Size(t, t))
            },
    )
}

/** `−  3  +`, for copies and hand sizes. */
@Composable
fun Stepper(
    count: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = 3,
) {
    Row(modifier.border(1.dp, Mu.colors.ink25), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.Minus, { onChange(count - 1) }, enabled = count > min)
        Mono(count.toString(), Modifier.widthIn(min = 24.dp), color = Mu.colors.ink, size = 13.sp, align = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(Icons.Plus, { onChange(count + 1) }, enabled = count < max)
    }
}

/** Tabs (§6): micro caps over a hairline, the active one underlined in 2px ink. */
@Composable
fun <T> MuTabs(value: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Row(
        modifier.drawBehind {
            drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        },
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        options.forEach { option ->
            val active = option == value
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Box(
                Modifier
                    .hoverable(source)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = source, indication = null) { onSelect(option) }
                    .drawBehind {
                        if (active) drawRect(c.ink, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                    }
                    .padding(bottom = 8.dp),
            ) {
                Micro(label(option), color = animatedColor(if (active || hovered) c.ink else c.ink45))
            }
        }
    }
}

/** A popup or select row: hairline below, inverted while highlighted. */
@Composable
fun MenuRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    last: Boolean = false,
    hint: String? = null,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Inverted(hovered && enabled) {
        val inner = Mu.colors
        Row(
            modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.3f)
                .background(if (hovered && enabled) inner.paper else Color.Transparent)
                .hoverable(source, enabled)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
                .drawBehind {
                    if (!last) drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) MuIcon(icon, inner.ink, Modifier.size(14.dp))
            MuText(
                if (danger) "✕ $text" else text,
                Modifier.weight(1f),
                MuType.row(LocalMuFonts.current).let {
                    if (selected || danger) it.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) else it
                },
                color = inner.ink,
                maxLines = 1,
            )
            if (hint != null) Mono(hint, color = inner.ink45)
        }
    }
}

/** A focusable no-op, so a click on empty chrome takes focus off a text field. */
fun Modifier.focusSink(): Modifier = this.focusable()

@Composable
fun Gap(width: Dp = 0.dp, height: Dp = 0.dp) = Spacer(Modifier.width(width).height(height))

/** Keeps a caller's focus request from racing composition. */
@Composable
fun RequestFocusOnce(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Micro(text, Modifier.weight(1f))
        if (hint != null) Mono(hint)
    }
}

@Composable
fun MinWidth(min: Dp, content: @Composable () -> Unit) = Box(Modifier.defaultMinSize(minWidth = min)) { content() }
