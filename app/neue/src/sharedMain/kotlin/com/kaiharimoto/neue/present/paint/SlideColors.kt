package com.kaiharimoto.neue.present.paint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.Hsb
import com.kaiharimoto.mastertool.core.present.SlideColor
import com.kaiharimoto.mastertool.core.present.Theme
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.LocalOverlays
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/**
 * Choosing a colour for something on a slide (1.0.70): the theme's own colours first, by
 * token, so a change of theme recolours them; then a spread of common ones; then any colour,
 * picked on a square of shades and a strip of hues or typed as hex. With [SlidePaint], the
 * one place outside the card foil a colour is drawn as a choice.
 */

/** Ready colours beyond the theme's: a spread a creator reaches for. */
private val SPREAD = listOf(
    "#000000", "#3D3D3D", "#7A7A7A", "#B5B5B5", "#E6E6E6", "#FFFFFF",
    "#E5383B", "#F77F00", "#FCBF49", "#2A9D8F", "#3A86FF", "#8338EC",
    "#FF006E", "#06D6A0", "#118AB2", "#FFD166", "#8D5524", "#00B140",
)

/** A swatch showing [value] that opens the picker; [none] offers no colour at all. */
@Composable
fun ColorField(value: String?, theme: Theme, onChange: (String?) -> Unit, modifier: Modifier = Modifier, none: Boolean = false, label: String = "Colour") {
    val overlays = LocalOverlays.current
    var at by remember { mutableStateOf(Offset.Zero) }
    val shown = SlideColor.argb(value, theme)?.let { Color(it) }
    val c = Mu.colors
    Row(
        modifier
            .height(32.dp)
            .onGloballyPositioned { at = it.positionInWindow() }
            .border(1.dp, c.ink25)
            .cursorPointer(label = label)
            .muClickable {
                overlays?.show(Offset(at.x, at.y + 34f), onDismiss = {}) {
                    ColorPanel(value, theme, none, { onChange(it) }, { overlays.dismiss() })
                }
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Swatch(shown, 18)
        Small(describe(value), maxLines = 1)
    }
}

private fun describe(value: String?): String = when {
    value == null -> "None"
    value.startsWith("@") -> Theme.tokenName(value.drop(1))
    else -> value.uppercase()
}

@Composable
private fun Swatch(color: Color?, size: Int, selected: Boolean = false) {
    val c = Mu.colors
    Canvas(Modifier.size(size.dp).border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.ink25)) {
        if (color == null) {
            drawRect(Color.White)
            drawLine(Color.Black.copy(alpha = 0.5f), Offset(0f, this.size.height), Offset(this.size.width, 0f), 1.5f)
        } else {
            // A checker under a see-through colour, so an alpha reads.
            if (color.alpha < 1f) {
                val q = this.size.width / 4f
                for (i in 0 until 4) for (j in 0 until 4) drawRect(if ((i + j) % 2 == 0) Color.White else Color.Black.copy(alpha = 0.15f), Offset(i * q, j * q), Size(q, q))
            }
            drawRect(color)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPanel(value: String?, theme: Theme, none: Boolean, onChange: (String?) -> Unit, close: () -> Unit) {
    val c = Mu.colors
    var current by remember { mutableStateOf(value) }
    fun pick(v: String?) {
        current = v
        onChange(v)
    }
    val argb = SlideColor.argb(current, theme) ?: 0xFF000000
    val hsb = remember(argb) { Hsb.fromRgb(((argb shr 16) and 0xFF).toInt(), ((argb shr 8) and 0xFF).toInt(), (argb and 0xFF).toInt()) }
    var hue by remember { mutableStateOf(hsb[0]) }
    var sat by remember { mutableStateOf(hsb[1]) }
    var bri by remember { mutableStateOf(hsb[2]) }
    fun fromPicker() {
        val rgb = Hsb.toRgb(hue, sat, bri)
        pick(SlideColor.toHex(0xFF000000 or (rgb.toLong() and 0xFFFFFF)))
    }
    Column(
        Modifier.width(264.dp).background(c.paper).border(1.dp, c.ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Micro("Theme", color = c.ink70)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (none) Box(Modifier.cursorPointer(label = "No colour").muClickable { pick(null) }) { Swatch(null, 24, current == null) }
            Theme.TOKENS.forEach { t ->
                val token = "@$t"
                Box(Modifier.cursorPointer(label = Theme.tokenName(t)).muClickable { pick(token) }) {
                    Swatch(SlideColor.argb(token, theme)?.let { Color(it) }, 24, current == token)
                }
            }
        }
        Micro("Colours", color = c.ink70)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SPREAD.forEach { hex ->
                Box(Modifier.cursorPointer(label = hex).muClickable { pick(hex) }) {
                    Swatch(SlideColor.hex(hex)?.let { Color(it) }, 24, current.equals(hex, ignoreCase = true))
                }
            }
        }
        Micro("Any colour", color = c.ink70)
        // Saturation across, brightness down, at the hue below.
        val pure = Color(0xFF000000.toInt() or Hsb.toRgb(hue, 1f, 1f))
        Canvas(
            Modifier.fillMaxWidth().height(120.dp)
                .cursorPointer(label = "Shade")
                .pointerInput(Unit) {
                    fun set(o: Offset) {
                        sat = (o.x / size.width).coerceIn(0f, 1f)
                        bri = 1f - (o.y / size.height).coerceIn(0f, 1f)
                        fromPicker()
                    }
                    detectTapGestures { set(it) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        sat = (change.position.x / size.width).coerceIn(0f, 1f)
                        bri = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        fromPicker()
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val x = sat * size.width
            val y = (1f - bri) * size.height
            drawCircle(Color.White, 7f, Offset(x, y), style = DrawStroke(2.5f))
            drawCircle(Color.Black, 9f, Offset(x, y), style = DrawStroke(1f))
        }
        val hues = (0..6).map { Color(0xFF000000.toInt() or Hsb.toRgb(it / 6f, 1f, 1f)) }
        Canvas(
            Modifier.fillMaxWidth().height(16.dp)
                .cursorPointer(label = "Hue")
                .pointerInput(Unit) { detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 0.999f); fromPicker() } }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        hue = (change.position.x / size.width).coerceIn(0f, 0.999f)
                        fromPicker()
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(hues))
            val x = hue * size.width
            drawRect(Color.Black, Offset(x - 2f, 0f), Size(4f, size.height))
        }
        var hex by remember(current) { mutableStateOf(current?.takeUnless { it.startsWith("@") } ?: SlideColor.toHex(argb)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuInput(hex, { v -> hex = v; SlideColor.hex(v)?.let { pick(SlideColor.toHex(it)) } }, Modifier.weight(1f), placeholder = "#RRGGBB", mono = true, dense = true)
            MicroLink("Done", close)
        }
    }
}

/** A theme's look in a small square: background, a title bar and its accents. */
@Composable
fun ThemeSwatch(theme: Theme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    Column(modifier.hoverable(source).cursorPointer(label = theme.name).muClickable(interactionSource = source, onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(54.dp).border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.ink25)) {
            fun col(t: String) = Color(SlideColor.argb("@$t", theme) ?: 0xFF000000)
            val bgTo = theme.backgroundTo?.let { SlideColor.hex(it) }?.let { Color(it) }
            if (bgTo != null) drawRect(Brush.linearGradient(listOf(col("bg"), bgTo))) else drawRect(col("bg"))
            drawRect(col("text"), Offset(size.width * 0.1f, size.height * 0.22f), Size(size.width * 0.5f, size.height * 0.12f))
            drawRect(col("muted"), Offset(size.width * 0.1f, size.height * 0.44f), Size(size.width * 0.35f, size.height * 0.07f))
            listOf("accent", "accent2", "accent3", "accent4").forEachIndexed { i, t ->
                drawRect(col(t), Offset(size.width * (0.1f + i * 0.13f), size.height * 0.68f), Size(size.width * 0.1f, size.height * 0.14f))
            }
        }
        Small(theme.name, maxLines = 1)
    }
}
