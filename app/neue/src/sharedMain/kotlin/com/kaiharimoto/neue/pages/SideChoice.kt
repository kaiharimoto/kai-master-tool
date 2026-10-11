package com.kaiharimoto.neue.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesDeck
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prep.MatchupLedger
import com.kaiharimoto.mastertool.core.siding.SideChooser
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.SideWorth
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.mapper.SeriesResultPanel
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A side deck chosen for [me]'s field (G2.2): the suggestion, and the evidence it rests on, by name. */
internal class SideChoice(
    val suggestion: SideChooser.Suggestion,
    val opponents: List<SideChooser.Opponent>,
    val names: Map<String, String>,
)

/**
 * The evidence for [me] against its web's field (G2.2) — Shootout's judged hands for each matchup and the games logged with
 * their opening hands (the ledger's) — and the side deck chosen from it. Off the frame thread.
 */
internal suspend fun sideChoice(h: NeueHolders, me: StoredDeck): SideChoice? {
    val webs = h.webs
    val state = h.builder
    val web = webs.webOf(me.entry.id) ?: return null
    val others = web.entries.filter { !it.mine && it.deckId != me.entry.id }.mapNotNull { e -> webs.stored(e.deckId)?.let { it to (e.share ?: 1) } }
    val index = state.index
    val canonical: (Int) -> Int = { code -> index.byId(CardId(code))?.id?.value ?: code }
    h.prep.load()
    val games = MatchupLedger.select(h.prep.doc.games, h.prep.doc.sources).filter { it.deckId == me.entry.id }
    val worth = others.flatMap { (them, _) ->
        h.shootout.resultsFor(me, them)?.let { SideWorth.fromShootout(it, them.entry.id) }.orEmpty() +
            SideWorth.fromGames(games, them.entry.id, canonical)
    }
    val deck = webs.deckOf(me, state)
    val held = (deck.main + deck.extra).map { canonical(it.value) }.toSet()
    // Candidates: the Side Deck as it is, and every card the evidence rates that the deck does not play.
    val candidates = (deck.side.map { canonical(it.value) } + worth.map { it.card }.filter { it !in held }).distinct()
    val opponents = others.map { (them, share) -> SideChooser.Opponent(them.entry.id, them.entry.name, share.toDouble()) }
    val suggestion = withContext(Dispatchers.Default) {
        SideChooser.choose(
            deck, opponents, worth, candidates,
            allowed = { code -> index.byId(CardId(code))?.let { state.limits.statusOf(it).maxCopies } ?: 3 },
            isExtra = { index.byId(it)?.isExtraDeck == true },
            canonical = canonical,
        )
    }
    return SideChoice(suggestion, opponents, opponents.associate { it.id to it.name })
}

/**
 * "Choose for the field…" (G2.2): the fifteen and each opponent's plan, every choice with the numbers it rests on, what is
 * unrated; then **Play them out** against the side deck and plans as they are, by simulated matches (G2.1). Nothing is
 * written: a choice the matches bear out is the person's to make on the page.
 */
@Composable
internal fun SideChoiceDialog(h: NeueHolders, me: StoredDeck, close: () -> Unit) {
    val c = Mu.colors
    val index = h.builder.index
    fun name(code: Int) = index.byId(CardId(code))?.name ?: "#$code"
    val choice by produceState<SideChoice?>(null, me.entry.id) { value = sideChoice(h, me) }
    var run by remember { mutableStateOf<String?>(null) }
    val kit = remember(h.effects.revision, h.effects.loaded, index) { h.goldfishKit() }
    val scope = rememberCoroutineScope()
    MuDialog(
        "Choose for the field",
        close,
        width = 720.dp,
        description = "A Side Deck and a plan against each deck of the web, from the evidence there is: what Shootout's judged hands and your games rate each card against them. A card nothing rates is never chosen for a number it does not have.",
        footer = { MuButton("Close", close, variant = BtnVariant.GHOST) },
    ) {
        val r = run
        val ch = choice
        when {
            r != null -> SeriesResultPanel(h, r)
            ch == null -> Small("Reading the evidence…", color = c.ink45)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val s = ch.suggestion
                if (s.side.isEmpty() && s.plans.none { it.first.sided }) {
                    Help(SideChooser.words(s, ::name) { ch.names[it] ?: it })
                } else {
                    Micro("The Side Deck · ${s.cards.size}", color = c.ink45)
                    s.side.forEach { x ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Mono("${x.copies}×", color = c.ink)
                            Small(name(x.card), Modifier.weight(1f), color = c.ink, maxLines = 1)
                            Mono(
                                if (x.kept) "kept from your Side Deck"
                                else x.worth.groupBy { it.opponent }.entries.joinToString(" · ") { (o, ws) ->
                                    val w = ws.maxBy { it.n }
                                    "${ch.names[o] ?: o} ${points(w.points)} (${points(w.low)} to ${points(w.high)})"
                                },
                                color = c.ink70,
                            )
                        }
                    }
                    Micro("The plans", color = c.ink45)
                    s.plans.forEach { p ->
                        Small(
                            "Against ${p.name}: " + if (p.first.sided) "out ${counted(p.first.out, ::name)}; in ${counted(p.first.into, ::name)}" else p.why,
                            color = c.ink,
                        )
                    }
                    if (s.unrated.isNotEmpty()) Small("Nothing rates a card against ${s.unrated.joinToString(", ") { ch.names[it] ?: it }}: their plans are yours to write.", color = c.ink70)
                    s.problems.forEach { Small(it, color = c.ink) }
                    Help("Points of win chance with their 95 % ranges; a range across zero is not a verdict. Play them out before keeping any of it.")
                    val state = h.builder
                    val deck = h.webs.deckOf(me, state)
                    MuButton(
                        "Play them out",
                        {
                            val a = SeriesDeck(me.entry.id, me.entry.name, deck)
                            val b = SeriesDeck("${me.entry.id}+side", "${me.entry.name} with this Side Deck", deck.copy(side = s.cards))
                            val plans: Map<String, Pair<SidePlan, SidePlan>> = s.plans.associate { it.opponent to (it.first to it.second) }
                            scope.launch {
                                val field = h.series.field(me.entry.id, me.entry.name, a, b, null, kit, bPlans = plans)
                                val why = h.series.refusal(field)
                                if (why != null) h.neue.note = Note(why)
                                else h.series.start(me.entry.id, a, b, "This Side Deck and its plans", field, PAIRS, kit)?.let { run = it }
                            }
                        },
                        variant = BtnVariant.PRIMARY,
                        enabled = h.series.running == null && s.plans.any { it.first.sided },
                        reason = if (h.series.running != null) "A series is playing" else "No plan to play out",
                    )
                }
            }
        }
    }
}

/** Seed pairs a suggestion is played out on. */
private const val PAIRS = 8

private fun points(x: Double): String = x.roundToInt().let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }

/** "3× Mulcharmy Purulia, 1× Nibiru" from a plan's copies. */
private fun counted(cards: List<CardId>, name: (Int) -> String): String =
    SidingMath.counted(cards).joinToString(", ") { (id, n) -> "$n× ${name(id.value)}" }
