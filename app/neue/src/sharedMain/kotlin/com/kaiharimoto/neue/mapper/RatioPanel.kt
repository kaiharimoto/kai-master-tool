package com.kaiharimoto.neue.mapper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareAsk
import com.kaiharimoto.mastertool.core.duel.mapper.compare.FieldHold
import com.kaiharimoto.mastertool.core.duel.mapper.compare.RatioSearch
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.kit.Badge
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.theme.Mu

/*
 * "Search the ratios" (Phase G part two, G2.4; `docs/phases/G2.md` §8): one more of an engine card for one fewer of another,
 * each swap compared with the deck as it is on the same hands, the field's interruptions in the other seat's hand at their
 * shares; called by Holm over the swaps, so the family is held to one false call in twenty. A swap worth keeping opens in
 * Compare — on the same hands, or played out by simulated matches.
 */

/** Hands a swap may be given. */
private val RATIO_HANDS = listOf(200, 400, 800)

/** The other seat's interruptions for the open deck: the suite's field cards at their shares, or null when it holds none. */
fun fieldHold(h: NeueHolders): FieldHold? {
    val kit = h.goldfishKit()
    val copies = h.field.read?.profile?.interaction()?.associate { it.card.value to it.mean }.orEmpty()
    return FieldHold.of(h.mapper.suite, written = { kit.book.has(it) }, copies = { code -> copies[code] })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RatioDialog(h: NeueHolders) {
    val m = h.mapper
    if (!m.searchingRatios) return
    val c = Mu.colors
    val index = h.builder.index
    fun name(code: Int) = index.byId(CardId(code))?.name ?: "#$code"
    val kit = remember(h.effects.revision, h.effects.loaded, index) { h.goldfishKit() }
    val deck = remember(h.builder.deck, index) { h.goldfishDeck() }
    val limits = h.builder.limits
    val swaps = remember(deck, kit, limits) {
        RatioSearch.swaps(deck, kit, allowed = { code -> index.byId(CardId(code))?.let { limits.statusOf(it).maxCopies } ?: 3 }, most = RatioSearch.CAP)
    }
    val held = remember(m.suite, kit, h.field.read) { fieldHold(h) }
    var chosen by remember(swaps) { mutableStateOf(swaps.take(RatioSearch.MOST).toSet()) }
    var depth by remember { mutableStateOf(1) }
    var hands by remember { mutableStateOf(RatioSearch.HANDS) }
    var useField by remember(held) { mutableStateOf(held != null && held.sets.isNotEmpty()) }
    val shown = m.ratios
    var editing by remember { mutableStateOf(shown == null) }
    val running = m.running?.kind == Mappers.Kind.RATIOS
    MuDialog(
        "Search the ratios",
        { m.closeRatios() },
        width = 720.dp,
        description = "One more copy of an engine card for one fewer of another, each swap set against the deck as it is on the same hands, " +
            "${if (m.first) "going first" else "going second"}. A swap is called better or worse only when it holds across every swap asked.",
        footer = {
            if (editing || shown == null) {
                MuButton(
                    "Search",
                    {
                        val ask = CompareAsk.interruptions(depth)
                        val list = swaps.filter { it in chosen }
                        if (m.startRatios(deck, kit, list, ask, held.takeIf { useField }, hands) != null) editing = false
                    },
                    variant = BtnVariant.PRIMARY,
                    enabled = chosen.isNotEmpty() && !m.busy,
                    reason = if (m.busy) "A run is going" else "Choose a swap",
                )
            } else if (running) {
                MuButton("Stop", m::stop)
            } else {
                MuButton("Search again", { editing = true }, enabled = !m.busy, reason = "A run is going")
            }
            MuButton("Close", { m.closeRatios() }, variant = BtnVariant.GHOST)
        },
    ) {
        if (!editing && shown != null) {
            RatioResult(h, shown, ::name)
        } else if (swaps.isEmpty()) {
            Small("No swap to ask: every engine card is at its limit, or the engine is one card. Write more of the deck's effects, or open Compare with… for a card it does not play.", color = c.ink70)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Micro("The swaps · ${chosen.size} of ${swaps.size}", color = c.ink45)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    swaps.forEach { s ->
                        key(s) {
                            Tag(RatioSearch.swapWords(s, ::name), s in chosen, { chosen = if (s in chosen) chosen - s else chosen + s }, caption = if (s in chosen) "Leave out" else "Ask")
                        }
                    }
                }
                Help("The third copy of one card against the second of another comes first. Each swap deals its new copy where the cut one was.", color = c.ink45)
                Micro("What a hand is asked", color = c.ink45)
                Segmented(depth, listOf(1, 2, 3), { if (it == 1) "≥ 1 interruption" else "≥ $it interruptions" }, { depth = it }, small = true)
                Micro("Hands for each swap", color = c.ink45)
                Segmented(hands, RATIO_HANDS, { GoldfishWords.count(it) }, { hands = it }, small = true)
                Micro("The other seat", color = c.ink45)
                if (held == null) {
                    Small("The stress suite holds none of the field's interruptions: add them on the Stress tab (From the field) to search against them. Until then the goldfish alone.", color = c.ink70)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MuSwitch(useField && held.sets.isNotEmpty(), { useField = it && held.sets.isNotEmpty() })
                        Small("Holds the field's interruptions at their shares", Modifier.weight(1f), color = c.ink)
                    }
                    Help(RatioSearch.heldWords(held.takeIf { useField }, ::name), color = c.ink70)
                }
                Help(
                    "${chosen.size} swaps × ${GoldfishWords.count(hands)} hands, each hand mapped both ways" +
                        (if (useField && held != null) " and stress tested where an interruption is held" else "") +
                        ". Long: it runs off the frame thread, and Stop keeps what was compared.",
                    color = c.ink45,
                )
            }
        }
    }
}

/** A search on screen: its progress, then each swap with its call and numbers, and the ways to look further. */
@Composable
private fun RatioResult(h: NeueHolders, x: Mappers.Ratios, name: (Int) -> String) {
    val m = h.mapper
    val c = Mu.colors
    val r = x.result
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Help("Asked: ${x.ask}. ${if (x.first) "Going first" else "Going second"}, ${GoldfishWords.count(x.hands)} hands a swap. ${RatioSearch.heldWords(x.held, name)}", color = c.ink45)
        if (r == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Breathe()
                Small("${x.steps.size} of ${x.swaps.size} swaps compared. Each is called once all are: Holm reads the family.", Modifier.weight(1f), color = c.ink)
            }
            Progress(x.steps.size.toFloat() / x.swaps.size.coerceAtLeast(1))
        } else {
            val better = r.better
            Small(
                if (better.isEmpty()) "No swap is called better: the ratios as they are hold up on these hands."
                else "${better.size} ${if (better.size == 1) "swap is" else "swaps are"} called better: play ${if (better.size == 1) "it" else "them"} out before keeping any.",
                color = c.ink,
            )
            if (r.stopped) Help("Stopped before the end: only what was compared whole is called, among all ${r.asked} swaps asked.", color = c.ink70)
        }
        x.steps.forEachIndexed { i, step ->
            key(step.swap) {
                val call = r?.calls?.getOrNull(i)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Badge(call?.let(RatioSearch::callWords) ?: "waiting", inverted = call == RatioSearch.Call.BETTER)
                    Column(Modifier.weight(1f)) {
                        Small(RatioSearch.swapWords(step.swap, name), color = c.ink, maxLines = 1)
                        Mono(RatioSearch.numbers(step), color = c.ink70)
                    }
                    if (step.complete && r != null) {
                        MuButton("Same hands", {
                            m.closeRatios()
                            m.compareChange(step.swap.change)
                        }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                        MuButton("Play it out", {
                            m.closeRatios()
                            m.preset = step.swap.change
                            m.presetMatches = true
                            m.comparing = true
                        }, size = BtnSize.SM, variant = if (call == RatioSearch.Call.BETTER) BtnVariant.PRIMARY else BtnVariant.SECONDARY)
                    }
                }
            }
        }
        Help("Points of hands that reach the ask, the swap against the deck as it is, with the 95 % range and the exact McNemar p. A range across zero is not a verdict.", color = c.ink45)
    }
}
