package com.kaiharimoto.neue.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.SectionTitle
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * `04 Stats`: what the deck is made of, as ruled tables with ink bars (§12) —
 * no axes, no legends, no colour series. A second series would be weight or a
 * second panel; there is only ever one here.
 */
@Composable
fun StatsPage(state: DeckBuilderState) {
    val stats = state.statistics
    Column(Modifier.fillMaxSize()) {
        PageHeader(4, "Stats", "${state.statsSection.displayName} deck · ${stats.sectionSize} cards") {
            Segmented(state.statsSection, DeckSection.entries, { it.displayName }, { state.statsSection = it }, small = true)
        }
        if (stats.sectionSize == 0) {
            EmptyState("Empty.", "The ${state.statsSection.displayName.lowercase()} deck has no cards in it yet.")
            return@Column
        }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(32.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle(1, "Kind")
                        Table(
                            listOf("Monster" to stats.monsters, "Spell" to stats.spells, "Trap" to stats.traps)
                                .filter { it.second > 0 },
                            stats.sectionSize,
                        )
                        if (stats.unknownCards > 0) Small("${stats.unknownCards} not in the card pool", Modifier.padding(top = 8.dp), color = Mu.colors.ink45)
                    }
                    Column(Modifier.weight(1f)) {
                        SectionTitle(2, "Level and rank")
                        LevelBars(stats.byLevel)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle(3, "Attribute")
                        Table(
                            stats.byAttribute.filterKeys { it != Attribute.UNKNOWN }.entries
                                .sortedByDescending { it.value }
                                .map { it.key.name.lowercase().replaceFirstChar(Char::uppercase) to it.value },
                            stats.sectionSize,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        SectionTitle(4, "Type")
                        Table(stats.byRace.entries.sortedByDescending { it.value }.map { it.key to it.value }, stats.sectionSize)
                    }
                }
                Column {
                    SectionTitle(5, "Archetype")
                    Table(stats.byArchetype.entries.sortedByDescending { it.value }.map { it.key to it.value }, stats.sectionSize)
                    if (stats.byArchetype.isEmpty()) Small("No archetypes.", color = Mu.colors.ink45)
                }
            }
            ScrollbarFor(scroll)
        }
    }
}

@Composable
private fun Table(rows: List<Pair<String, Int>>, total: Int) {
    val c = Mu.colors
    rows.forEach { (label, n) ->
        Row(
            Modifier.fillMaxWidth()
                .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RowText(label, Modifier.weight(1f))
            Box(Modifier.width(160.dp).height(3.dp).background(c.ink12)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((n.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)).background(c.ink))
            }
            Mono(n.toString(), Modifier.width(40.dp), color = c.ink, size = 12.sp, align = TextAlign.End)
        }
    }
}

/** Ink bars on a 2px gap, labels in 9px micro above, the tallest level at full height (§12). */
@Composable
private fun LevelBars(byLevel: Map<Int, Int>) {
    val c = Mu.colors
    if (byLevel.isEmpty()) {
        Small("No monsters with a level.", Modifier.padding(top = 8.dp), color = c.ink45)
        return
    }
    val levels = (1..12).toList()
    val max = byLevel.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            levels.forEach { level ->
                Mono((byLevel[level] ?: 0).takeIf { it > 0 }?.toString() ?: "", Modifier.weight(1f), size = 9.sp, align = TextAlign.Center)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 4.dp)) {
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (levels.size - 1)) / levels.size
            levels.forEachIndexed { i, level ->
                val n = byLevel[level] ?: 0
                val h = size.height * n / max
                val x = i * (w + gap)
                drawRect(c.ink12, Offset(x, size.height - 1.dp.toPx()), Size(w, 1.dp.toPx()))
                if (n > 0) drawRect(c.ink, Offset(x, size.height - h), Size(w, h))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            levels.forEach { Mono(it.toString(), Modifier.weight(1f), size = 9.sp, align = TextAlign.Center) }
        }
    }
}
