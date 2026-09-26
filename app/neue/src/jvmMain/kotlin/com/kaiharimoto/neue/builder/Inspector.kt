package com.kaiharimoto.neue.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.DeckEditor
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.search.CardFilter
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.zen.zenDeep
import com.kaiharimoto.neue.zen.zenQuiet
import com.kaiharimoto.neue.kit.Badge
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Stat
import com.kaiharimoto.neue.kit.Stepper
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.percent
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * The card under the pointer, or the one last clicked — the desktop's answer
 * to the tablet's long-press sheet. A large display can afford to keep it open,
 * so reading a card costs a hover rather than a gesture.
 */
@Composable
fun Inspector(state: DeckBuilderState, neue: NeueState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val card = neue.inspected
    Box(modifier.zenDeep()) {
        if (card == null) {
            Column(Modifier.zenQuiet().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.kaiharimoto.neue.kit.MuText("Nothing here.", style = MuType.h1(LocalMuFonts.current))
                Body("Point at a card to read it. Click one to keep it here.", color = c.ink70)
            }
            return@Box
        }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            NeueCard(
                card = card,
                modifier = Modifier.fillMaxWidth().aspectRatio(CARD_RATIO),
                format = state.format,
                foil = neue.prefs.foil,
            )
            // In zen the card stays a moment longer than what is written about it.
            Column(Modifier.zenQuiet(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                CardFacts(card, state)
                HRule(color = c.ink)
                Copies(card, state)
                HRule()
                SelectionContainer {
                    Body(card.description.ifBlank { "No card text." }, color = c.ink)
                }
            }
        }
        Box(Modifier.matchParentSize().zenQuiet()) { ScrollbarFor(scroll) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardFacts(card: Card, state: DeckBuilderState) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        H2(card.name, maxLines = 3)
        Micro(card.type, color = c.ink45, maxLines = 2)
        if (card.category == CardCategory.MONSTER) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(top = 4.dp)) {
                card.level?.let { Stat(if (card.frameType.contains("xyz")) "Rank" else "Level", it.toString()) }
                card.linkValue?.let { Stat("Link", it.toString()) }
                Stat("ATK", card.atk?.toString() ?: "?")
                if (card.linkValue == null) Stat("DEF", card.def?.toString() ?: "?")
                card.pendulumScale?.let { Stat("Scale", it.toString()) }
            }
        }
        // A facet is a question: click it to search the pool by it.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            card.race?.let { race -> Tag(race, false, { state.onFilterChange(CardFilter(races = setOf(race), format = state.format)) }) }
            if (card.category == CardCategory.MONSTER) {
                val attribute = card.attribute
                Tag(attribute.name.lowercase().replaceFirstChar { it.uppercase() }, false, {
                    state.onFilterChange(CardFilter(attributes = setOf(attribute), format = state.format))
                })
            }
            card.archetype?.let { archetype -> Tag(archetype, false, { state.onFilterChange(CardFilter(archetypes = setOf(archetype), format = state.format)) }) }
        }
        val ban = card.banStatus(state.format)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(state.format.name)
            when (ban) {
                BanStatus.UNLIMITED -> Mono("Unlimited", color = c.ink45)
                BanStatus.FORBIDDEN -> Badge("✕ Forbidden", inverted = true)
                BanStatus.LIMITED -> Badge("Limited · 1", inverted = true)
                BanStatus.SEMI_LIMITED -> Badge("Semi-limited · 2", inverted = true)
            }
        }
    }
}

/** Copies in each section it can go in, and what they buy: the chance of opening one. */
@Composable
private fun Copies(card: Card, state: DeckBuilderState) {
    val c = Mu.colors
    val home = card.requiredSection()
    val limit = DeckEditor.copyLimit(card, state.format)
    val total = state.copiesInDeck(card.id)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro("In the deck", Modifier.weight(1f))
            Mono("$total of $limit", color = if (total > limit) c.ink else c.ink45)
        }
        listOf(home, DeckSection.SIDE).forEach { section ->
            val count = state.copiesIn(card.id, section)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowText("${section.displayName} deck", Modifier.weight(1f))
                Stepper(
                    count = count,
                    onChange = { state.setCount(card, section, it) },
                    max = (count + (limit - total)).coerceAtLeast(count).coerceAtMost(3),
                )
            }
        }
        val inMain = state.copiesIn(card.id, DeckSection.MAIN)
        val size = state.deck.main.size
        if (home == DeckSection.MAIN && inMain > 0 && size > 0) {
            Help(
                "Opening hand in $size cards · ${percent(state.mainStatistics.openingHandOdds(inMain, 5))} going first · " +
                    "${percent(state.mainStatistics.openingHandOdds(inMain, 6))} going second",
                color = c.ink70,
            )
        }
        val groups = state.groups.ordered()
        if (groups.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Micro("Group", Modifier.weight(1f))
                val current = state.groups.groupOf(card.id)
                Box(Modifier.background(current?.let { id -> state.groups.byId(id) }?.let { GroupMarkers.hue(it.color) } ?: c.paper).padding(start = 4.dp)) {
                    MuSelect(
                        value = current,
                        options = listOf<String?>(null) + groups.map { it.id },
                        label = { id -> id?.let { state.groups.byId(it)?.name } ?: "No group" },
                        onSelect = { state.assignCardToGroup(card.id, it) },
                        small = true,
                        modifier = Modifier.padding(0.dp),
                    )
                }
            }
        }
    }
}
