package com.kaiharimoto.neue.kit

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
    /** Why it is disabled, when that is not obvious: the family cursor's caption over it. */
    reason: String? = null,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
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
            .cursorPointer(label = label, showsWords = true, enabled = enabled, reason = reason)
            .muClickable(enabled = enabled, interactionSource = source, onClick = onClick)
            .explainsWhenDisabled(enabled, reason)
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

/** A square icon-only button, ghost by default. Always give it a [label] (the cursor's caption) and a tooltip. */
@Composable
fun IconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    enabled: Boolean = true,
    toggled: Boolean = false,
    variant: BtnVariant = BtnVariant.GHOST,
    /** What it does, in a word or two: the family cursor captions an icon button with it (its `aria-label`). */
    label: String? = null,
    /** Why it is disabled, when that is not obvious. */
    reason: String? = null,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
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
            .cursorPointer(label = label, enabled = enabled, reason = reason)
            .muClickable(enabled = enabled, interactionSource = source, onClick = onClick)
            .explainsWhenDisabled(enabled, reason),
        contentAlignment = Alignment.Center,
    ) {
        MuIcon(icon, animatedColor(fg), Modifier.size(if (size >= 36.dp) 16.dp else 14.dp))
    }
}

/** A micro-caps text link: `Show log`, `Start over`, `All decks →`. */
@Composable
fun MicroLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Mu.colors.ink45) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    // A link that acts is a 32dp box to a finger (touch swarm, rec 18: TouchMetrics.LINK).
    val touch = LocalTouchFirst.current
    Box(
        modifier
            .let { if (touch) it.heightIn(min = com.kaiharimoto.mastertool.core.input.TouchMetrics.LINK.dp) else it }
            .hoverable(source)
            .cursorPointer(showsWords = true)
            .muClickable(interactionSource = source, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Micro(text, color = animatedColor(if (hovered) Mu.colors.ink else color))
    }
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
    /**
     * The soft keyboard's action key (touch swarm, rec 9). It always ends editing —
     * the keyboard goes and the field lets go — whatever it is labelled; a search
     * field's reads Search and only hides the keyboard, since its results are live.
     * [onSubmit] is a hardware Enter's, as on the desk.
     */
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    /** A password: shown as dots, and the keyboard offers no suggestions (sync's WebDAV, 1.0.68). */
    secret: Boolean = false,
) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val line = animatedColor(if (focused || hovered) c.ink else c.ink25)
    val style = (textStyle ?: if (mono) MuType.mono(f, if (dense) 11.sp else 13.sp) else if (dense) MuType.help(f) else MuType.body(f))
        .copy(color = c.ink)
    // The field keeps its own selection; the text is the caller's. A text changed from
    // outside (a cleared search) puts the caret at its end.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(value)
    Box(
        modifier
            .height(if (dense) 28.dp else 36.dp)
            // Three clicks select everything (kai, 1.0.14: "so I can delete it and search
            // for the next card"). Counted on the way down and never consumed, so the
            // field's own click and double-click are untouched; the whole line is selected
            // a frame after the third release, once the field has placed its own caret.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var count = 0
                    var lastAt = 0L
                    var lastPos = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (event.type == PointerEventType.Press && event.isPrimaryPress) {
                            val near = (change.position - lastPos).getDistance() < TRIPLE_SLOP
                            count = if (change.uptimeMillis - lastAt < TRIPLE_MS && near) count + 1 else 1
                            lastAt = change.uptimeMillis
                            lastPos = change.position
                        } else if (event.type == PointerEventType.Release && count >= 3) {
                            count = 0
                            scope.launch {
                                withFrameNanos { }
                                val text = latest
                                field = TextFieldValue(text, TextRange(0, text.length))
                            }
                        }
                    }
                }
            }
            .cursor(CursorMode.TEXT, fontSize = style.fontSize, focused = focused)
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
            value = shown,
            onValueChange = {
                field = it
                if (it.text != value) onValueChange(it.text)
            },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(c.ink),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(
                imeAction = imeAction,
                keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else keyboardType,
                autoCorrectEnabled = !secret,
            ),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardActions = KeyboardActions(onAny = {
                focusManager.clearFocus()
                keyboard?.hide()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    val enter = e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter
                    if (onSubmit != null && enter && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                }
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .reportsTextFocus()
                .onFocusChanged { onFocusChange(it.isFocused) },
        )
    }
}

/** Three presses this close together in time, and this close on the screen, select a field's whole line. */
private const val TRIPLE_MS = 500L
private const val TRIPLE_SLOP = 8f

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
    val hovered by source.collectIsHotAsState()
    var widthPx by remember { mutableStateOf(0) }
    val height = if (small) 32.dp else 36.dp
    val density = LocalDensity.current
    val overlays = LocalOverlays.current
    var anchor by remember { mutableStateOf(Offset.Zero) }
    val list: @Composable () -> Unit = {
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
                    onClick = { overlays?.dismiss(); open = false; onSelect(option) },
                )
            }
        }
    }
    Box(modifier) {
        Row(
            Modifier
                .height(height)
                .onSizeChanged { widthPx = it.width }
                .onGloballyPositioned { anchor = it.positionInWindow() }
                .background(c.paper)
                .border(1.dp, animatedColor(if (open || hovered) c.ink else c.ink25))
                .hoverable(source)
                .cursorPointer(showsWords = true)
                .muClickable(interactionSource = source) {
                    if (overlays != null) {
                        // In the window's own layer, under the family cursor.
                        open = true
                        overlays.show(Offset(anchor.x, anchor.y + with(density) { height.toPx() } - 1f), onDismiss = { open = false }, content = list)
                    } else {
                        open = !open
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowText(label(value), Modifier.weight(1f))
            MuText("▼", style = MuType.help(LocalMuFonts.current).copy(fontSize = 10.sp), color = c.ink70)
        }
        if (open && overlays == null) {
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
    /** Stays small on a tablet too: it is spent from the deck's own budget (the lens row, the bar). */
    compact: Boolean = false,
) {
    val c = Mu.colors
    // Outside the deck a finger gets 36dp and 11sp, where ≥ and ≤ looked alike (touch swarm, rec 18).
    val tall = !small || LocalTouchFirst.current && !compact
    Row(modifier.height(if (tall) com.kaiharimoto.mastertool.core.input.TouchMetrics.SEGMENT.dp else 28.dp).border(1.dp, c.ink)) {
        options.forEachIndexed { i, option ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink))
            val selected = option == value
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHotAsState()
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(animatedColor(if (selected) c.ink else if (hovered) c.ink06 else Color.Transparent))
                    .hoverable(source)
                    .cursorPointer(showsWords = true)
                    .muClickable(interactionSource = source) { onSelect(option) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Micro(label(option), color = if (selected) c.paper else c.ink, size = if (tall) 11.sp else 10.sp)
            }
        }
    }
}

/** A boxed word that stays pressed (inverted) while it is on: "Auto zen", "Auto save". */
@Composable
fun WordToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Box(
        Modifier
            .height(28.dp)
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else androidx.compose.ui.graphics.Color.Transparent))
            .border(1.dp, c.ink)
            .hoverable(source)
            .cursorPointer(showsWords = true)
            .muClickable(interactionSource = source, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Micro(label, color = if (on) c.paper else c.ink)
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
            .cursorPointer(caption = if (checked) "Turn off" else "Turn on")
            .muClickable { onChange(!checked) },
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
            .cursorPointer(caption = if (checked) "Clear" else "Tick")
            .muClickable { onChange(!checked) },
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
    /** Its name, for the family cursor's caption. */
    name: String? = null,
    /** Its value as people read it (`3:00`, `72%`), kept in step by the caller. */
    valueText: String? = null,
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
            .cursor(CursorMode.DRAG, caption = name, value = valueText ?: "%.2f".format(value), slider = true)
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
        IconButton(Icons.Minus, { onChange(count - 1) }, enabled = count > min, label = "Remove one", reason = "None to remove")
        Mono(count.toString(), Modifier.widthIn(min = 24.dp), color = Mu.colors.ink, size = 13.sp, align = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(Icons.Plus, { onChange(count + 1) }, enabled = count < max, label = "Add one", reason = "No more allowed")
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
            val hovered by source.collectIsHotAsState()
            Box(
                Modifier
                    .hoverable(source)
                    .cursorPointer(showsWords = true)
                    .muClickable(interactionSource = source) { onSelect(option) }
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
    reason: String? = null,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Inverted(hovered && enabled) {
        val inner = Mu.colors
        Row(
            modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.3f)
                .background(if (hovered && enabled) inner.paper else Color.Transparent)
                .hoverable(source, enabled)
                .cursorPointer(showsWords = true, enabled = enabled, reason = reason)
                .muClickable(enabled = enabled, interactionSource = source, onClick = onClick)
                .drawBehind {
                    if (!last) drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                }
                // A finger's menu row is 44dp: the hold menus are where its actions are (rec 18).
                .padding(horizontal = 12.dp, vertical = if (LocalTouchFirst.current) 13.dp else 8.dp),
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
