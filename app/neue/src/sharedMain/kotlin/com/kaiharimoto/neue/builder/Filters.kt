package com.kaiharimoto.neue.builder

import com.kaiharimoto.mastertool.core.input.TouchMetrics
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.collectIsHotAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.search.CardFilter
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.search.CardProperties
import com.kaiharimoto.mastertool.core.search.CardSort
import com.kaiharimoto.mastertool.core.search.EffectKind
import com.kaiharimoto.mastertool.core.search.LINK_ARROWS
import com.kaiharimoto.mastertool.core.search.MonsterAbility
import com.kaiharimoto.mastertool.core.search.MonsterFrame
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.theme.Mu

/**
 * Every way to narrow the card pool (kai, 1.0.19: "the card database filters need
 * to be more advanced, refer to … duelingbook, master duel, and neuron"). Their
 * common ground, one facet a row:
 *
 * - **Order** — best match, name, ATK, DEF, level — and a turn-around;
 * - **Card** — monster, spell, trap; main or extra deck;
 * - **Monster type** — Normal, Effect, Ritual, Fusion, Synchro, Xyz, Pendulum, Link —
 *   and **Ability** — Tuner, Flip, Gemini, Spirit, Toon, Union;
 * - **Attribute**, **Type** (the monster's), **Property** (the Spell's or Trap's);
 * - **Level or rank**, **Link rating**, **Scale**, and the **Link arrows** as the
 *   card's own compass, every arrow chosen required;
 * - **ATK** and **DEF** between two numbers;
 * - **Effect** — Neuron's and Master Duel's categories, read off the text
 *   (`EffectKinds`), every one chosen required;
 * - **Archetype**, found by typing; and the **Banlist**.
 *
 * Facets that cannot apply are not shown: pick Spell and the monster rows go.
 * The panel owns no state — it is handed a filter and hands back the next one —
 * so the pool and the search pop-out use the same one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPanel(filter: CardFilter, onChange: (CardFilter) -> Unit, index: CardIndex, modifier: Modifier = Modifier) {
    val f = filter
    fun <T> Set<T>.toggle(item: T) = if (item in this) this - item else this + item
    val monsters = f.categories.isEmpty() || CardCategory.MONSTER in f.categories
    val spellsTraps = f.categories.isEmpty() || CardCategory.SPELL in f.categories || CardCategory.TRAP in f.categories
    val races = remember(index) {
        index.cards.asSequence().filter { it.category == CardCategory.MONSTER }.mapNotNull { it.race?.takeIf(String::isNotBlank) }.distinct().sorted().toList()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Facet("Order") {
            Segmented(f.sort, CardSort.entries, { it.label }, { onChange(f.copy(sort = it)) }, small = true)
            Chip(if (f.reverse) "↑ Reversed" else "↓", f.reverse) { onChange(f.copy(reverse = !f.reverse)) }
        }
        Facet("Card") {
            listOf(CardCategory.MONSTER to "Monster", CardCategory.SPELL to "Spell", CardCategory.TRAP to "Trap").forEach { (value, label) ->
                Chip(label, value in f.categories) { onChange(f.copy(categories = f.categories.toggle(value))) }
            }
            Chip("Main deck", f.extraDeckOnly == false) { onChange(f.copy(extraDeckOnly = if (f.extraDeckOnly == false) null else false)) }
            Chip("Extra deck", f.extraDeckOnly == true) { onChange(f.copy(extraDeckOnly = if (f.extraDeckOnly == true) null else true)) }
        }
        if (monsters) {
            Facet("Monster type") {
                MonsterFrame.entries.forEach { frame -> Chip(frame.label, frame in f.frames) { onChange(f.copy(frames = f.frames.toggle(frame))) } }
            }
            Facet("Ability") {
                MonsterAbility.entries.forEach { a -> Chip(a.label, a in f.abilities) { onChange(f.copy(abilities = f.abilities.toggle(a))) } }
            }
            Facet("Attribute") {
                Attribute.entries.filter { it != Attribute.UNKNOWN }.forEach { a ->
                    Chip(a.name.lowercase().replaceFirstChar { it.uppercase() }, a in f.attributes) { onChange(f.copy(attributes = f.attributes.toggle(a))) }
                }
            }
            Facet("Type") {
                races.forEach { race -> Chip(race, race in f.races) { onChange(f.copy(races = f.races.toggle(race))) } }
            }
        }
        if (spellsTraps) {
            Facet("Spell and trap property") {
                CardProperties.ALL.forEach { p -> Chip(p, p in f.properties) { onChange(f.copy(properties = f.properties.toggle(p))) } }
            }
        }
        if (monsters) {
            Facet("Level or rank") {
                (0..13).forEach { level -> Chip(level.toString(), level in f.levels) { onChange(f.copy(levels = f.levels.toggle(level))) } }
            }
            Facet("Link rating") {
                (1..6).forEach { n -> Chip(n.toString(), n in f.linkRatings) { onChange(f.copy(linkRatings = f.linkRatings.toggle(n))) } }
            }
            Facet("Pendulum scale") {
                (0..13).forEach { n -> Chip(n.toString(), n in f.scales) { onChange(f.copy(scales = f.scales.toggle(n))) } }
            }
            Facet("Link arrows · every one chosen") {
                ArrowCompass(f.linkArrows) { onChange(f.copy(linkArrows = f.linkArrows.toggle(it))) }
            }
            Facet("ATK") { Range(f.atkRange) { onChange(f.copy(atkRange = it)) } }
            Facet("DEF") { Range(f.defRange) { onChange(f.copy(defRange = it)) } }
        }
        Facet("Effect · every one chosen") {
            EffectKind.entries.forEach { kind -> Chip(kind.label, kind in f.effects) { onChange(f.copy(effects = f.effects.toggle(kind))) } }
        }
        Facet("Archetype") { Archetypes(f.archetypes, index) { onChange(f.copy(archetypes = it)) } }
        Facet("Banlist") {
            listOf(BanStatus.FORBIDDEN to "Forbidden", BanStatus.LIMITED to "Limited", BanStatus.SEMI_LIMITED to "Semi-limited", BanStatus.UNLIMITED to "Unlimited").forEach { (s, label) ->
                Chip(label, s in f.banStatuses) { onChange(f.copy(banStatuses = f.banStatuses.toggle(s))) }
            }
        }
        if (f.isActive) MicroLink("Clear ${f.activeFacetCount} filter${if (f.activeFacetCount == 1) "" else "s"}", { onChange(f.cleared()) }, color = Mu.colors.ink)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facet(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro(label, color = Mu.colors.ink45)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(chipGap()), verticalArrangement = Arrangement.spacedBy(chipGap())) { content() }
    }
}

/** Chips 12dp apart both ways for a finger, 4 on the desk (touch swarm, rec 18). */
@Composable
private fun chipGap() = if (LocalTouchFirst.current) TouchMetrics.CHIP_GAP.dp else 4.dp

/** A facet's value: the kit's Tag, a size down on the desk, since a facet row holds a dozen; a finger's full size. */
@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val touch = LocalTouchFirst.current
    Tag(label, on, onClick, if (touch) Modifier else Modifier.height(24.dp), caption = if (on) "Clear" else "Filter")
}

/**
 * The link arrows as they stand round a Link card: eight squares round an empty
 * middle, each an arrow glyph, inverted while chosen.
 */
@Composable
private fun ArrowCompass(chosen: Set<String>, onToggle: (String) -> Unit) {
    val glyphs = mapOf(
        "Top-Left" to "↖", "Top" to "↑", "Top-Right" to "↗", "Left" to "←",
        "Right" to "→", "Bottom-Left" to "↙", "Bottom" to "↓", "Bottom-Right" to "↘",
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(LINK_ARROWS.subList(0, 3), listOf(LINK_ARROWS[3], "", LINK_ARROWS[4]), LINK_ARROWS.subList(5, 8)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { arrow ->
                    if (arrow.isEmpty()) {
                        Box(Modifier.size(26.dp).border(1.dp, Mu.colors.ink12))
                    } else {
                        ArrowSquare(glyphs.getValue(arrow), arrow in chosen) { onToggle(arrow) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrowSquare(glyph: String, on: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Box(
        Modifier
            .size(26.dp)
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
            .border(1.dp, if (on || hovered) c.ink else c.ink25)
            .hoverable(source)
            .cursorPointer(caption = if (on) "Clear" else "Filter")
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Mono(glyph, color = if (on) c.paper else c.ink)
    }
}

/**
 * Between two numbers, either left blank for no bound. The fields keep what was
 * typed while it is typed; the filter gets the range once both halves read.
 */
@Composable
private fun Range(range: IntRange?, onChange: (IntRange?) -> Unit) {
    var low by remember { mutableStateOf(range?.first?.takeIf { it > 0 }?.toString().orEmpty()) }
    var high by remember { mutableStateOf(range?.last?.takeIf { it < MAX_STAT }?.toString().orEmpty()) }
    // A filter cleared from outside clears the fields.
    LaunchedEffect(range) {
        if (range == null) {
            low = ""
            high = ""
        }
    }
    fun push() {
        val a = low.trim().toIntOrNull()
        val b = high.trim().toIntOrNull()
        onChange(if (a == null && b == null) null else (a ?: 0)..(b ?: MAX_STAT))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MuInput(low, { low = it.filter(Char::isDigit).take(5); push() }, Modifier.width(72.dp), placeholder = "From", mono = true, dense = true, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
        Mono("–", color = Mu.colors.ink45)
        MuInput(high, { high = it.filter(Char::isDigit).take(5); push() }, Modifier.width(72.dp), placeholder = "To", mono = true, dense = true, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
    }
}

private const val MAX_STAT = 99_999

/** Archetypes found by typing: the chosen ones as tags, and up to a dozen that match what is typed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Archetypes(chosen: Set<String>, index: CardIndex, onChange: (Set<String>) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val matches = remember(typed, index) {
        val q = typed.trim()
        if (q.isEmpty()) emptyList() else index.archetypes.filter { it.contains(q, ignoreCase = true) }.sortedBy { !it.startsWith(q, ignoreCase = true) }.take(12)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MuInput(typed, { typed = it }, Modifier.width(220.dp), placeholder = "Type an archetype", dense = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(chipGap()), verticalArrangement = Arrangement.spacedBy(chipGap())) {
            chosen.sorted().forEach { a -> Chip(a, true) { onChange(chosen - a) } }
            matches.filter { it !in chosen }.forEach { a -> Chip(a, false) { onChange(chosen + a) } }
        }
    }
}
