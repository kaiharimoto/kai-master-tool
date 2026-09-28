package com.kaiharimoto.neue.builder

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.GroupReport
import com.kaiharimoto.mastertool.core.deck.GroupStat
import com.kaiharimoto.mastertool.core.deck.GroupStats
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.percent
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import kotlinx.coroutines.delay

/** One slide: a title, and a bar per group with the number it stands for. */
private class Slide(val title: String, val note: String, val rows: (GroupReport) -> List<Bar>)

private class Bar(val label: String, val color: Color?, val share: Float, val value: String, val parts: List<Float>? = null)

/**
 * The deck's groups in numbers, as slides (kai, 1.0.18: "data analysis visuals
 * in a box that changes like slides, with an auto play slide feature that can be
 * toggled"). Five views of `GroupStats`, a bar per group in its own colour — the
 * group markers are the colour this app is allowed — turning every [TURN_MS] when
 * autoplay is on and the pointer is not resting on them.
 */
@Composable
fun GroupSlides(state: DeckBuilderState, neue: NeueState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val report = remember(state.deck, state.groups, state.index) { GroupStats.of(state.deck, state.groups, state.index::byId) }
    if (report.groups.isEmpty()) return
    val slides = remember { SLIDES }
    var at by remember { mutableIntStateOf(0) }
    val source = remember { MutableInteractionSource() }
    val resting by source.collectIsHoveredAsState()
    val auto = neue.prefs.slidesAutoplay
    LaunchedEffect(auto, at, resting) {
        if (!auto || resting) return@LaunchedEffect
        delay(TURN_MS)
        at = (at + 1) % slides.size
    }
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, c.ink25)
            .hoverable(source)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Micro(slides[at].title, Modifier.weight(1f), color = c.ink)
            Mono("${at + 1}/${slides.size}", color = c.ink70)
            IconButton(Icons.ChevronLeft, { at = (at - 1 + slides.size) % slides.size }, size = 24.dp, label = "Previous")
            IconButton(Icons.ChevronRight, { at = (at + 1) % slides.size }, size = 24.dp, label = "Next")
            AutoToggle(auto) { neue.update { it.copy(slidesAutoplay = !it.slidesAutoplay) } }
        }
        Crossfade(at, animationSpec = tween(MuMotion.SLOW, easing = MuMotion.ease), label = "slide") { i ->
            val slide = slides[i]
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                slide.rows(report).forEach { BarRow(it) }
                Small(slide.note, color = c.ink45)
            }
        }
    }
}

@Composable
private fun BarRow(bar: Bar) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Small(bar.label, Modifier.weight(1f), color = c.ink, maxLines = 1)
            Mono(bar.value, color = c.ink70)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(c.ink06)) {
            val parts = bar.parts
            if (parts == null) {
                Box(Modifier.fillMaxWidth(bar.share.coerceIn(0f, 1f)).height(6.dp).background(bar.color ?: c.ink25))
            } else {
                // Monsters, spells, traps: the same ink at three weights, as the Type lens draws them.
                Row(Modifier.fillMaxWidth(bar.share.coerceIn(0f, 1f))) {
                    val total = parts.sum().coerceAtLeast(0.0001f)
                    parts.forEachIndexed { k, part ->
                        if (part > 0f) Box(Modifier.weight(part / total).height(6.dp).background(c.ink.copy(alpha = TYPE_ALPHA[k])))
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoToggle(on: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .height(20.dp)
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
            .border(1.dp, c.ink)
            .hoverable(source)
            .cursorPointer(caption = if (on) "Stop" else "Play")
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Micro("Auto", color = if (on) c.paper else c.ink)
    }
}

private val TYPE_ALPHA = listOf(1f, 0.55f, 0.25f)

/** How long a slide stands before the next, with autoplay on. */
private const val TURN_MS = 6_000L

private fun hueOf(s: GroupStat) = GroupMarkers.hue(s.color)

private val SLIDES = listOf(
    Slide("Share of the main deck", "Ungrouped is what no group claims.") { r ->
        val n = r.mainSize.coerceAtLeast(1)
        r.groups.map { Bar(it.name, hueOf(it), it.main.toFloat() / n, "${it.main} · ${percent(it.main.toDouble() / n)}") } +
            Bar("Ungrouped", null, r.ungroupedMain.toFloat() / n, "${r.ungroupedMain} · ${percent(r.ungroupedMain.toDouble() / n)}")
    },
    Slide("Opening hand", "The chance of at least one in five cards.") { r ->
        r.groups.map { Bar(it.name, hueOf(it), it.opening.toFloat(), percent(it.opening)) }
    },
    Slide("Expected in a hand", "How many of each a five-card hand holds, on average.") { r ->
        val most = r.groups.maxOfOrNull { it.expected }?.toFloat()?.coerceAtLeast(1f) ?: 1f
        r.groups.map { Bar(it.name, hueOf(it), it.expected.toFloat() / most, "%.2f".format(it.expected)) }
    },
    Slide("Card types", "Monsters, spells and traps, darkest first.") { r ->
        val most = r.groups.maxOfOrNull { it.main }?.coerceAtLeast(1) ?: 1
        r.groups.map {
            Bar(it.name, null, it.main.toFloat() / most, "${it.monsters} · ${it.spells} · ${it.traps}", listOf(it.monsters.toFloat(), it.spells.toFloat(), it.traps.toFloat()))
        }
    },
    Slide("Across the deck", "Main, extra and side.") { r ->
        val most = r.groups.maxOfOrNull { it.main + it.extra + it.side }?.coerceAtLeast(1) ?: 1
        r.groups.map { Bar(it.name, hueOf(it), (it.main + it.extra + it.side).toFloat() / most, "${it.main} · ${it.extra} · ${it.side}") }
    },
)
