package com.kaiharimoto.neue.kit

import com.kaiharimoto.neue.cursor.cursorPointer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuColors

/** Badge (§6): micro caps in a 20px frame. Inverted is the only emphasis a badge gets. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, inverted: Boolean = false) {
    val c = Mu.colors
    Box(
        modifier
            .height(20.dp)
            .background(if (inverted) c.ink else Color.Transparent)
            .border(1.dp, if (inverted) c.ink else c.ink25)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Micro(text, color = if (inverted) c.paper else c.ink70)
    }
}

/** Tag (§6): a selectable square chip, inverted when on. */
@Composable
fun Tag(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: String? = null, caption: String? = null) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier
            .height(28.dp)
            .background(animatedColor(if (selected) c.ink else Color.Transparent))
            .border(1.dp, animatedColor(if (selected || hovered) c.ink else c.ink25))
            .hoverable(source)
            .cursorPointer(caption = caption, showsWords = true)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Small(text, color = if (selected) c.paper else if (hovered) c.ink else c.ink70, maxLines = 1)
        if (count != null) Mono(count, color = if (selected) c.paper.copy(alpha = 0.6f) else c.ink45)
    }
}

/** Kbd (§6): `Ctrl K` in mono 10 inside a hairline frame. */
@Composable
fun Kbd(text: String, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Box(modifier.border(1.dp, c.ink25).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Mono(text, color = c.ink70, size = 10.sp)
    }
}

@Composable
fun HRule(modifier: Modifier = Modifier, strong: Boolean = false, color: Color = if (strong) Mu.colors.ink else Mu.colors.ink12) {
    Box(modifier.fillMaxWidth().height(if (strong) 2.dp else 1.dp).background(color))
}

@Composable
fun VRule(modifier: Modifier = Modifier, color: Color = Mu.colors.ink) {
    Box(modifier.fillMaxHeight().width(1.dp).background(color))
}

/** Progress (§6): a 3px track, ink fill; indeterminate is the live hatch. */
@Composable
fun Progress(fraction: Float?, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Box(modifier.fillMaxWidth().height(3.dp).background(c.ink12).clipToBounds()) {
        if (fraction == null) {
            Hatch(Modifier.fillMaxSize(), live = true)
        } else {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(c.ink))
        }
    }
}

/** Meter (§6): discrete cells, on = ink. */
@Composable
fun Meter(on: Int, cells: Int, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Row(modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(cells) { i ->
            Box(Modifier.weight(1f).fillMaxHeight().background(if (i < on) c.ink else Color.Transparent).border(1.dp, c.ink))
        }
    }
}

/**
 * The 45° hatch (§5): 1px ink lines on an 8px period. Static means placeholder
 * or missing; live drifts 16px every 0.6s and means pending.
 */
@Composable
fun Hatch(modifier: Modifier = Modifier, live: Boolean = false, color: Color = Mu.colors.ink) {
    val shift = if (live) {
        val t = rememberInfiniteTransition(label = "hatch")
        t.animateFloat(0f, 16f, infiniteRepeatable(tween(600, easing = LinearEasing)), label = "drift").value
    } else {
        0f
    }
    Canvas(modifier.clipToBounds()) { drawHatch(color, shift.dp.toPx()) }
}

fun DrawScope.drawHatch(color: Color, shift: Float = 0f, period: Float = 8.dp.toPx(), stroke: Float = 1.dp.toPx()) {
    clipRect {
        val h = size.height
        var x = -h - period + (shift % period)
        while (x < size.width + period) {
            drawLine(color, Offset(x, h), Offset(x + h, 0f), stroke)
            x += period
        }
    }
}

/** The 6px square that breathes while something runs (§7). Never on text. */
@Composable
fun Breathe(modifier: Modifier = Modifier, running: Boolean = true, color: Color = Mu.colors.ink) {
    val alpha = if (running) {
        val t = rememberInfiniteTransition(label = "breathe")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1200, easing = com.kaiharimoto.neue.theme.MuMotion.ease), RepeatMode.Reverse), label = "a").value
    } else {
        1f
    }
    Box(modifier.size(6.dp).background(color.copy(alpha = color.alpha * alpha)))
}

/** Empty state (§6): a display sentence with a full stop, one line under it, an optional action. */
@Composable
fun EmptyState(title: String, line: String, modifier: Modifier = Modifier, boxed: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = Mu.colors
    Column(
        modifier
            .let { if (boxed) it.border(1.dp, c.ink).padding(40.dp) else it.padding(horizontal = 32.dp, vertical = 64.dp) },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MuText(title, Modifier.widthIn(max = 672.dp), com.kaiharimoto.neue.theme.MuType.display(com.kaiharimoto.neue.theme.LocalMuFonts.current))
        Body(line, Modifier.widthIn(max = 448.dp), color = c.ink70)
        if (action != null) Box(Modifier.padding(top = 8.dp)) { action() }
    }
}

/** A titled section: numeral + h2 over a structural rule, a control on the right. */
@Composable
fun SectionTitle(n: Int?, title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = Mu.colors
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (n != null) Numeral(n, Modifier.padding(bottom = 3.dp))
        H2(title, Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A micro-caps strip heading a list: `Main deck … 40`. */
@Composable
fun Strip(label: String, modifier: Modifier = Modifier, dense: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = Mu.colors
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = if (dense) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Micro(label, Modifier.weight(1f), color = c.ink70)
        trailing?.invoke()
    }
}

/** A stat: micro label over a 14px value. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro(label, color = Mu.colors.ink45)
        Mono(value, color = Mu.colors.ink, size = 14.sp)
    }
}

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
fun BoxScope.ScrollbarFor(state: ScrollState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp), style = muScrollbarStyle(Mu.colors))
}

@Composable
fun BoxScope.ScrollbarFor(state: LazyListState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp), style = muScrollbarStyle(Mu.colors))
}

@Composable
fun BoxScope.ScrollbarFor(state: LazyGridState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp), style = muScrollbarStyle(Mu.colors))
}

/** Square, always: used where an API insists on a shape. */
val Square = GenericShape { size, _ -> addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }

@Composable
fun Pad(all: Dp, content: @Composable () -> Unit) = Box(Modifier.padding(all)) { content() }
