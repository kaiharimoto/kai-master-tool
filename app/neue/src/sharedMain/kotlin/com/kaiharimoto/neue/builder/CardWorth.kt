package com.kaiharimoto.neue.builder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.meta.StrategyRatios
import com.kaiharimoto.mastertool.core.cards.BanlistWords
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckChange
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.effects.LocalEffectsHolders
import com.kaiharimoto.neue.field.FieldCache
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A card's worth beside its copy count (Phase G, G.4; the red team's D1 and B4, "the 41st card"): Shootout's number per copy
 * in each situation the person's hands have rated, with one more copy's — read from the results on page 09 when they are
 * this deck's — and the Mapper asked the same hands with one more copy. With the questions' −1 / now / +1 above it, the copy
 * count is decided on the odds, a paired run and the person's own judgement side by side.
 */
@Composable
internal fun CardWorth(card: Card, state: DeckBuilderState) {
    val h = LocalEffectsHolders.current ?: return
    val c = Mu.colors
    // How lists like this deck play the card (Phase G, G.5): from the field a field tool last read, never fetched here.
    val field = h.field
    LaunchedEffect(state.deck, field.read) { field.ask(state.deck) }
    val ofField = field.ratios?.takeIf { it.deck == state.deck && it.alike >= FieldCache.ALIKE }?.ratios
    val section = card.requiredSection().takeIf { it != DeckSection.SIDE } ?: DeckSection.MAIN
    val canonical = CardIdentity.canonical(card.id, state.index::byId)
    ofField?.let { r ->
        val row = r.of(canonical, section) ?: r.of(canonical, DeckSection.SIDE)
        Small(
            "Field: " + (row?.let { StrategyRatios.line(it, r.lists) } ?: "in none of ${r.lists} lists") + " like yours (${r.name})" +
                (row?.takeIf { it.section == DeckSection.SIDE && section != DeckSection.SIDE }?.let { ", sided" } ?: ""),
            color = c.ink70,
        )
    }
    // Cards like this one (Phase G, G.7): the pool shows them, only what the rules in force let in.
    val inDeck = (state.deck.main + state.deck.extra + state.deck.side).any { CardIdentity.canonical(it, state.index::byId) == canonical }
    MuButton("Cards like this", { h.neue.showLike(state, listOf(card), "Like ${card.name}", freed = card.takeIf { inDeck }) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
    // Its history on the Forbidden & Limited lists kept here (Phase G, G.7: F4's first half), years only. Read off the frame
    // thread (the first read is a file); the lists are refreshed in the background when due, for the next card read.
    val region = if (state.format == Format.OCG) Format.OCG else Format.TCG
    LaunchedEffect(region) { h.banlists.warm(region) }
    val history by produceState<String?>(null, card.id, region) {
        value = withContext(Dispatchers.IO) { h.banlists.history(region)?.let { BanlistWords.line(it.historyOf(card, state.index::byName)) } }
    }
    history?.let { Small("On the lists: $it", color = c.ink70) }
    if (card.requiredSection() != DeckSection.MAIN) return
    val s = h.shootout
    val r = s.results?.takeIf { s.deckId != null && s.deckId == state.deckId }
    val passcode = canonical.value
    val row = r?.cards?.firstOrNull { it.card == passcode }
    val held = state.deck.main.count { CardIdentity.canonical(it, state.index::byId).value == passcode }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r != null && row != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Micro("Shootout" + (s.bench?.opponentName?.let { " against $it" } ?: ""), Modifier.weight(1f))
                Micro("Per copy", Modifier.width(96.dp), color = c.ink45)
                Micro("1 more", Modifier.width(56.dp), color = c.ink45)
            }
            r.strata.mapNotNull { st -> row.cells[st]?.let { st to it } }.forEach { (stratum, cell) ->
                key(stratum) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Small(ShootoutWords.stratum(stratum), Modifier.weight(1f), color = c.ink, maxLines = 1)
                        if (cell.trials == 0) {
                            Mono("unrated", Modifier.width(152.dp), color = c.ink45)
                        } else {
                            Mono("${ShootoutWords.points(cell.estimate.value)} ±${ShootoutWords.points(cell.estimate.halfWidth95).removePrefix("+")}", Modifier.width(96.dp), color = c.ink)
                            Mono(row.next[stratum]?.let { ShootoutWords.points(it.value) } ?: "", Modifier.width(56.dp), color = c.ink70)
                        }
                    }
                }
            }
            Help("Points of win chance from your Shootout answers, with the 95 % range; 1 more is a further copy in another card's place.", color = c.ink45)
        }
        // The 41st card: the Mapper's paired run with one more copy, the same hands dealt both ways.
        if (held in 1..2) {
            MuButton(
                "One more copy, on the same hands",
                {
                    h.mapper.compareChange(DeckChange(into = card.id.value))
                    h.neue.go(Page.MAPPER)
                },
                variant = BtnVariant.GHOST,
                size = BtnSize.SM,
            )
        }
    }
}
