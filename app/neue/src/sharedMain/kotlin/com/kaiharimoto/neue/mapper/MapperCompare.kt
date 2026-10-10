package com.kaiharimoto.neue.mapper

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.Share
import com.kaiharimoto.mastertool.core.duel.mapper.StarterOdds
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Ablation
import com.kaiharimoto.mastertool.core.duel.mapper.compare.ChangedHand
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareAsk
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareWords
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CoverageGuard
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckChange
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckCoverage
import com.kaiharimoto.mastertool.core.duel.mapper.compare.HandAnswer
import com.kaiharimoto.mastertool.core.duel.mapper.compare.PairedMath
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Variants
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.effects.CoverageStrip
import com.kaiharimoto.neue.effects.WriteThese
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/*
 * Phase G on page 10 (`docs/phases/G.md` §G.2, mockup D): the library led by board depth — how many hands end on at least
 * one, two, three interruptions, with their ranges — and what the engine can play beside it; "Compare with…", a change or
 * another deck on the same hands; on the Starters tab the exact chance of opening a mapped starter and each card's worth
 * without it.
 */

/** The ask a comparison or "without it" is put: at least [n] interruptions, or the filters on screen. */
internal fun askOf(n: Int, query: BoardPreset): CompareAsk =
    if (n == 0 && query.filters.isNotEmpty()) CompareAsk.of(query.copy(name = "the boards your filters ask for")) else CompareAsk.interruptions(n.coerceAtLeast(1))

/** Board depth, the coverage line, and Compare with…: over the library, once hands are counted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DepthStrip(h: NeueHolders, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val run = m.counted
    val deck = h.builder.deck
    val coverage = remember(deck, h.effects.revision, h.effects.loaded) { DeckCoverage.of(h.goldfishDeck(), h.goldfishKit()) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Micro("Board depth ${if (m.first) "going first" else "going second"}", Modifier.weight(1f), color = c.ink45)
            MuButton("Compare with…", { m.comparing = true }, size = BtnSize.SM, enabled = !m.busy, reason = "A run is going")
        }
        if (run != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(if (phone) 16.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { n -> key(n) { DepthCell(n, run.share { it.interruptions >= n }) } }
            }
        } else {
            Small("Map hands to count how often a hand ends on one, two or three interruptions.", color = c.ink70)
        }
        CoverageStrip(h, coverage, "the cards the Mapper plays as inert in ${h.builder.deckName.ifBlank { "the deck" }}")
    }
    HRule()
}

@Composable
private fun DepthCell(n: Int, s: Share) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Micro(if (n == 1) "At least 1 interruption" else "At least $n interruptions", color = c.ink70)
        MuText(GoldfishWords.pct(s.share), style = MuType.h2(LocalMuFonts.current), color = c.ink)
        Help("95 %: ${GoldfishWords.interval(s.hits, s.of)} · ${GoldfishWords.count(s.of)} hands", color = c.ink45)
    }
}

// ---- Compare with… ------------------------------------------------------------------------------------------------

private enum class Against(val words: String) { CHANGE("A change"), DECK("Another deck") }

/** "Compare with…": a change (cut, add, swap) or another saved deck, put to the deck as it is on the same hands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompareDialog(h: NeueHolders) {
    val m = h.mapper
    if (!m.comparing) return
    val c = Mu.colors
    val phone = LocalPhone.current
    val index = h.builder.index
    fun name(code: Int) = index.byId(CardId(code))?.name ?: if (code == com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit.BLANK) "a blank card" else "#$code"
    var against by remember { mutableStateOf(Against.CHANGE) }
    // A change asked for elsewhere (the inspector's 41st card) fills the dialog in, once.
    val preset = remember { m.preset }
    LaunchedEffect(Unit) { m.preset = null }
    var out by remember { mutableStateOf(preset?.out) }
    var into by remember { mutableStateOf(preset?.into) }
    var copies by remember { mutableStateOf(preset?.copies ?: 1) }
    var typed by remember { mutableStateOf("") }
    var other by remember { mutableStateOf<StoredDeck?>(null) }
    var depth by remember { mutableStateOf(1) }
    var editing by remember { mutableStateOf(m.compared == null) }
    val kit = remember(h.effects.revision, h.effects.loaded, index) { h.goldfishKit() }
    val a = remember(h.builder.deck, index) { h.goldfishDeck() }
    val deckCards = remember(a, kit) { (a.main + a.extra).map(kit::canonical).distinct().sortedBy { name(it) } }
    val library by produceState(emptyList<StoredDeck>(), m.comparing) { value = h.webs.libraryDecks().filter { it.entry.id != h.builder.deckId } }
    val found = remember(typed, index) { if (typed.trim().length < 2) emptyList() else index.search(typed.trim(), limit = 8).cards }
    val isExtra: (Int) -> Boolean = { code -> index.byId(CardId(code))?.isExtraDeck == true }
    // The variant and its words, as chosen.
    val variant: Pair<GoldfishDeck, String>? = remember(against, out, into, copies, other, a, index) {
        when (against) {
            Against.CHANGE -> {
                if (out == null && into == null) null
                else Variants.apply(a, listOf(DeckChange(out, into, copies)), isExtra)?.let { b ->
                    b to listOfNotNull(out?.let { "cut $copies ${name(it)}" }, into?.let { "add $copies ${name(it)}" }).joinToString(", ").replaceFirstChar { it.uppercase() }
                }
            }
            Against.DECK -> other?.let { s ->
                val d = s.entry.deck
                GoldfishDeck(d.main.map { it.value }, d.extra.map { it.value }, s.entry.id, Ledger.fingerprint(d, index::byId), s.entry.name) to s.entry.name
            }
        }
    }
    val guard = remember(variant, kit) { variant?.let { CoverageGuard.check(a, it.first, kit) } }
    val ask = askOf(depth, m.query)
    MuDialog(
        "Compare with…",
        { m.closeCompare() },
        width = 720.dp,
        description = "The deck as it is against a change or another deck, ${if (m.first) "going first" else "going second"}, on the same hands: each hand dealt both ways, so only the hands the change touches can differ.",
        footer = {
            if (editing || m.compared == null) {
                MuButton(
                    "Compare",
                    {
                        val v = variant ?: return@MuButton
                        if (m.startCompare(a, v.first, v.second, ask, kit) != null || m.compared?.result != null) editing = false
                    },
                    variant = BtnVariant.PRIMARY,
                    enabled = variant != null && guard?.ok == true && !m.busy,
                    reason = when {
                        m.busy -> "A run is going"
                        variant == null -> "Choose the change, or the deck"
                        else -> "The two versions cannot be compared fairly yet"
                    },
                )
            } else {
                MuButton("Change it", { editing = true }, enabled = !m.busy, reason = "Stop the comparison first")
            }
            MuButton("Close", { m.closeCompare() }, variant = BtnVariant.GHOST)
        },
    ) {
        val result = m.compared
        if (!editing && result != null) {
            CompareResult(h, result, ::name)
            return@MuDialog
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Segmented(against, Against.entries, { it.words }, { against = it }, small = true)
            when (against) {
                Against.CHANGE -> {
                    Micro("Cut", color = c.ink45)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        deckCards.forEach { code -> key(code) { Tag(name(code), out == code, { out = if (out == code) null else code }, caption = "Cut") } }
                    }
                    Micro("Add", color = c.ink45)
                    MuInput(typed, { typed = it }, Modifier.widthIn(max = 360.dp).fillMaxWidth(), placeholder = "A card's name")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        into?.let { code -> Tag(name(code), true, { into = null }, caption = "Remove") }
                        found.filter { it.id.value != into }.forEach { card -> key(card.id.value) { Tag(card.name, false, { into = kit.canonical(card.id.value); typed = "" }, caption = "Add") } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Micro("Copies", color = c.ink45)
                        Segmented(copies, listOf(1, 2, 3), { it.toString() }, { copies = it }, small = true)
                    }
                    Help("Cut and add together swap them: each new copy is dealt where a cut one was.", color = c.ink45)
                }
                Against.DECK -> {
                    if (library.isEmpty()) Small("No other saved deck yet.", color = c.ink70)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        library.forEach { s -> key(s.entry.id) { Tag(s.entry.name, other?.entry?.id == s.entry.id, { other = if (other?.entry?.id == s.entry.id) null else s }, caption = "Against") } }
                    }
                }
            }
            Micro("What a hand is asked", color = c.ink45)
            Segmented(
                depth,
                listOfNotNull(1, 2, 3, 0.takeIf { m.query.filters.isNotEmpty() }),
                { if (it == 0) "Your filters" else if (it == 1) "≥ 1 interruption" else "≥ $it interruptions" },
                { depth = it },
                small = true,
            )
            variant?.let { (_, words) -> Small("$words. ${if (m.first) "Going first" else "Going second"}, from seed ${m.seed}.", color = c.ink) }
            guard?.let { g ->
                if (!g.ok) {
                    Column(Modifier.fillMaxWidth().border(1.dp, c.ink).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Small(CompareWords.refused(g, ::name), color = c.ink)
                        if (g.unwritten.isNotEmpty()) WriteThese(h, g.unwritten, "the cards a comparison of ${h.builder.deckName} needs")
                    }
                }
                g.questions.forEach { q -> Small(q, color = c.ink70) }
            }
        }
    }
}

/** A comparison's answer: the headline, the verdict, the changed hands, each openable both ways. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompareResult(h: NeueHolders, x: Mappers.Compared, name: (Int) -> String) {
    val m = h.mapper
    val c = Mu.colors
    var all by remember(x) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RowText(x.label, color = c.ink, maxLines = 2)
        Help("Asked: ${x.ask}. ${if (x.first) "Going first" else "Going second"}, from seed ${x.seed}.", color = c.ink45)
        val r = x.result
        if (r == null) {
            val p = x.progress
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Breathe()
                Small(
                    if (p == null) "Dealing the first hands both ways" else "${GoldfishWords.count(p.hands)} hands so far: ${PairedMath.points(p.paired.difference)} points (95 %: ${CompareWords.interval(p.paired.interval)})",
                    Modifier.weight(1f),
                    color = c.ink,
                )
                MuButton("Stop", m::stop, size = BtnSize.SM)
            }
            Progress(p?.let { it.hands.toFloat() / it.most })
            Help("It stops by itself once the answer is known: the interval clear of zero, or within a point either way.", color = c.ink45)
            return@Column
        }
        r.guard?.takeIf { !it.ok }?.let { g ->
            Small(CompareWords.refused(g, name), color = c.ink)
            if (g.unwritten.isNotEmpty()) WriteThese(h, g.unwritten, "the cards a comparison of ${h.builder.deckName} needs")
            return@Column
        }
        val p = r.paired
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column {
                Micro("As it is", color = c.ink70)
                MuText(GoldfishWords.pct(p.a), style = MuType.h2(LocalMuFonts.current), color = c.ink)
            }
            Column {
                Micro("With the change", color = c.ink70)
                MuText(GoldfishWords.pct(p.b), style = MuType.h2(LocalMuFonts.current), color = c.ink)
            }
            Column {
                Micro("Difference", color = c.ink70)
                MuText("${PairedMath.points(p.difference)} pts", style = MuType.h2(LocalMuFonts.current), color = c.ink)
                Help("95 %: ${CompareWords.interval(p.interval)}", color = c.ink45)
            }
        }
        CompareWords.all(r, x.ask).drop(1).forEach { Small(it, color = c.ink) }
        Help(
            "${GoldfishWords.count(p.hands)} hands dealt both ways · ${GoldfishWords.count(r.mappedA + r.mappedB)} maps searched, ${GoldfishWords.count(r.cachedA + r.cachedB)} already known" +
                if (r.rechecked > 0) " · ${r.rechecked} undecided hands searched again with five times the room" else "",
            color = c.ink45,
        )
        if (r.changed.isNotEmpty()) {
            Micro("The hands that changed", color = c.ink45)
            val shown = if (all) r.changed else r.changed.take(10)
            shown.forEach { ch -> key(ch.k) { ChangedRow(h, x, ch, name) } }
            if (r.changed.size > 10) MicroLink(if (all) "Fewer" else "All ${r.changed.size}", { all = !all })
        }
    }
}

@Composable
private fun ChangedRow(h: NeueHolders, x: Mappers.Compared, ch: ChangedHand, name: (Int) -> String) {
    val c = Mu.colors
    fun word(a: HandAnswer) = when (a) {
        HandAnswer.YES -> "reaches it"
        HandAnswer.NO -> "does not"
        HandAnswer.UNDECIDED -> "undecided"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Mono("#${ch.k + 1}", Modifier.width(48.dp), color = c.ink45)
        Column(Modifier.weight(1f)) {
            RowText(ch.handB.joinToString(", ") { name(it) }, color = c.ink, maxLines = 2)
            Help("As it is ${word(ch.a)}; with the change ${word(ch.b)}.", color = c.ink70)
        }
        MicroLink("As it is", { openHand(h, x.a, x, ch.k, "as it is") })
        MicroLink("With it", { openHand(h, x.b, x, ch.k, "with the change") })
    }
}

/** Hand [k] of [deck] in the comparison [x], opened on the Duel page as an unsaved replay. */
private fun openHand(h: NeueHolders, deck: GoldfishDeck, x: Mappers.Compared, k: Int, words: String) {
    val kit = h.goldfishKit()
    val main = deck.main.map(kit::canonical)
    val game = GoldfishHands.game(main, deck.extra.map(kit::canonical), x.seed, k, x.first, h.builder.deckName, keyAs = deck.keyAs?.map(kit::canonical))
    h.duel.openGame("Hand ${k + 1}, $words (${x.label})", game)
    h.mapper.comparing = false
    h.neue.go(Page.DUEL)
    h.neue.note = Note("Hand ${k + 1} $words, dealt as the comparison dealt it. Play it out; nothing is kept unless you keep it.")
}

// ---- the starters: exact odds, and each card without it -------------------------------------------------------------

/**
 * Over the starter table: the exact chance of opening a mapped starter that reaches the ask (singles and pairs only, so at
 * least this), what it says about the groups, and each card's worth without it.
 */
@Composable
internal fun StarterOddsLine(h: NeueHolders, rows: List<StarterTable.Row>, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val lib = m.side.library
    val kit = remember(h.effects.revision, h.effects.loaded, h.builder.index) { h.goldfishKit() }
    val main = remember(h.builder.deck, kit) { h.goldfishDeck().main.map(kit::canonical) }
    val ask = askOf(1, m.query)
    val odds = remember(rows, lib, main) { StarterOdds.of(rows, lib, main, ask.passes) }
    val groups = h.builder.groups
    val audit = remember(rows, lib, groups) {
        StarterOdds.audit(rows, lib, ask.passes, { code -> groups.groupOf(CardId(code))?.let { groups.byId(it)?.name } }) { code ->
            h.builder.index.byId(CardId(code))?.name ?: "#$code"
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (odds != null) {
            Small(
                "Opening a mapped starter that reaches ${ask.name}: ${GoldfishWords.pct(odds.first)} going first, ${GoldfishWords.pct(odds.second)} going second — " +
                    "counted exactly over every hand. Singles and pairs only, so at least this.",
                color = c.ink,
            )
        }
        audit.take(4).forEach { Help(it, color = c.ink70) }
        val w = m.without
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Help(
                if (w == null) "Without it: each engine card's copies cut one at a time for a blank, the same hands dealt, and the hands that lose ${ask.name} counted."
                else "Without it: on ${GoldfishWords.count(w.hands)} hands each, ${w.ask}${if (w.first != m.first) " (the other side)" else ""}.",
                Modifier.weight(1f),
                color = c.ink45,
            )
            MuButton(
                if (w == null) "Measure each card" else "Measure again",
                { m.startWithout(h.goldfishDeck(), kit, ask, Ablation.Copies.ONE) },
                size = BtnSize.SM,
                variant = BtnVariant.GHOST,
                enabled = !m.busy,
                reason = "A run is going",
            )
        }
    }
}

/**
 * A starter's worth without one copy of it, "−4.2" points, or empty when it was not measured on this deck ([print]) and side.
 */
internal fun withoutWords(h: NeueHolders, row: StarterTable.Row, print: String): String {
    val m = h.mapper
    val w = m.without ?: return ""
    if (row.cards.size != 1 || w.first != m.first || w.print != print) return ""
    val r = w.rows[row.cards[0]] ?: return ""
    return PairedMath.points(-r.worth)
}
