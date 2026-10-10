package com.kaiharimoto.neue.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.siding.SideCoverage
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * The Side Deck across the field (Phase G, G.6; mockup E): each side card's share of the field it comes in against, going
 * first and second, the copies the plans ask for against the copies held, the dead slots, the cards moved against most of
 * the field (candidates to swap between Main and Side), the field with no plan, and any plan that leaves no legal deck.
 */
@Composable
internal fun CoveragePanel(
    coverage: SideCoverage,
    state: DeckBuilderState,
    phone: Boolean,
    onWrite: (String) -> Unit,
    modifier: Modifier,
) {
    val c = Mu.colors
    fun name(id: CardId) = state.index.byId(id)?.name ?: "#${id.value}"
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            H2("Side Deck coverage")
            Help(
                if (coverage.equalWeights) "No deck of the web has a share yet, so every matchup weighs alike. Give the decks their shares on Format."
                else "By each opponent's share of the field: how much of the room each side card is brought in against, from every saved plan.",
            )
            // The headline: dead copies, the field with no plan, and any plan that leaves no legal deck.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                coverage.deadWords()?.let { Small("$it.", color = c.ink) }
                if (coverage.unplanned.isNotEmpty()) {
                    Small(
                        "No plan against ${coverage.unplanned.joinToString()}: ${SideCoverage.pct(coverage.unplannedFirst)} of the field going first, ${SideCoverage.pct(coverage.unplannedSecond)} going second.",
                        color = c.ink,
                    )
                }
                if (coverage.illegal.isEmpty()) Small("Every saved plan leaves a legal deck: card for card, the Main Deck 40 to 60 (§VII.C).", color = c.ink70)
                coverage.illegal.forEach { p -> Small("✕ ${p.opponent}, ${p.turn.title.lowercase()}: ${p.words}.", color = c.ink) }
            }
            // A row per side card: its face, held and asked for, and a bar per turn of the field it meets.
            Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Micro("Side card", Modifier.weight(1f), color = c.ink45)
                    Micro(if (phone) "Held" else "Held · asked", Modifier.width(if (phone) 76.dp else 104.dp), color = c.ink45)
                    Micro(if (phone) "1st" else "Going first", Modifier.width(if (phone) 64.dp else 140.dp), color = c.ink45)
                    Micro(if (phone) "2nd" else "Going second", Modifier.width(if (phone) 64.dp else 140.dp), color = c.ink45)
                }
                coverage.cards.forEach { s ->
                    key(s.card.value) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.index.byId(s.card)?.let { NeueCard(it, Modifier.size(22.dp, 32.dp), foil = "off", dimmed = s.needed == 0) }
                                Column(Modifier.weight(1f)) {
                                    RowText(name(s.card), color = if (s.needed == 0) c.ink45 else c.ink, maxLines = 1)
                                    if (!phone && s.against.isNotEmpty()) Small(s.against.joinToString(" · "), color = c.ink45, maxLines = 1)
                                }
                            }
                            Column(Modifier.width(if (phone) 76.dp else 104.dp)) {
                                Mono("${s.held} · ${s.needed}", color = c.ink)
                                when {
                                    s.short > 0 -> Small("${s.short} short", color = c.ink)
                                    s.dead > 0 -> Small("${s.dead} dead", color = c.ink45)
                                }
                            }
                            ShareBar(s.first, Modifier.width(if (phone) 64.dp else 140.dp))
                            ShareBar(s.second, Modifier.width(if (phone) 64.dp else 140.dp))
                        }
                    }
                }
            }
            if (coverage.outAgainstMost.isNotEmpty() || coverage.inAgainstMost.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Micro("Moved against most of the field", color = c.ink70)
                    coverage.outAgainstMost.forEach { m ->
                        Small("Out ${m.turn.title.lowercase()} against ${SideCoverage.pct(m.share)}: ${name(m.card)} — a candidate for the Side Deck.", color = c.ink)
                    }
                    coverage.inAgainstMost.forEach { m ->
                        Small("In ${m.turn.title.lowercase()} against ${SideCoverage.pct(m.share)}: ${name(m.card)} — a candidate for the Main Deck.", color = c.ink)
                    }
                }
            }
            if (coverage.unplanned.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    coverage.unplanned.take(4).forEach { n -> MuButton("Plan $n", { onWrite(n) }, variant = BtnVariant.GHOST, size = BtnSize.SM) }
                }
            }
            Help("A card is \"asked\" for as many copies as any one plan brings in; copies past that come in against nothing. Turn: ${Turn.FIRST.title} and ${Turn.SECOND.title.lowercase()} are your turns in games 2 and 3.")
        }
        ScrollbarFor(scroll)
    }
}

/** A share of the field, 0–100 %, as a bar in ink with its number. */
@Composable
private fun ShareBar(share: Double, modifier: Modifier) {
    val c = Mu.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).drawBehind {
                drawRect(c.ink06)
                drawRect(if (share > 0) c.ink else c.ink25, Offset.Zero, Size(size.width * share.toFloat().coerceIn(0f, 1f), size.height))
            },
        )
        Mono(if (share > 0) SideCoverage.pct(share) else "--", color = if (share > 0) c.ink70 else c.ink45)
    }
}
