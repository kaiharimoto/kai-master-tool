package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.versus.VersusDeal
import com.kaiharimoto.mastertool.core.shootout.versus.VersusResults
import com.kaiharimoto.mastertool.core.shootout.versus.VersusRun
import com.kaiharimoto.mastertool.core.shootout.versus.VersusSide
import com.kaiharimoto.mastertool.core.shootout.versus.VersusWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.SectionTitle
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.present.SEARCH_DEBOUNCE_MS
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Card against card (2026-10, kai): pick a card of the deck and a substitute, judge hands dealt from either deck, and
// read which card is better on its own, beside which cards, and which deck does better overall. Master UI throughout:
// ink on paper, the cards the only colour.

/** The way in, on the Shootout's setup. */
@Composable
internal fun VersusEntry(h: NeueHolders) {
    val c = Mu.colors
    Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Card against card", color = c.ink45)
        Body(
            "Weighing two cards for one slot? Pick a card of the deck and the card that might replace it. You judge hands dealt from the deck as it is and from the deck with the other card in its place, without being told which beyond the card itself. The results say which card is better on its own, beside which of your cards, and which deck does better overall.",
            color = c.ink70,
        )
        MuButton("Compare two cards", h.shootout.versus::open, arrow = true, enabled = h.shootout.bench != null, reason = h.shootout.problem)
    }
}

@Composable
internal fun VersusView(h: NeueHolders, phone: Boolean) {
    when (h.shootout.versus.phase) {
        ShootoutVersus.Phase.SETUP -> VersusSetup(h, phone)
        ShootoutVersus.Phase.TRIAL -> VersusTrial(h, phone)
        ShootoutVersus.Phase.RESULTS -> VersusResultsView(h, phone)
    }
}

private fun nameOf(h: NeueHolders, id: Int?): String = id?.let { h.shootout.card(it)?.name } ?: id?.toString() ?: "?"

// ---- choosing the two cards --------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersusSetup(h: NeueHolders, phone: Boolean) {
    val s = h.shootout
    val v = s.versus
    val c = Mu.colors
    if (s.input == null) {
        if (s.problem != null) {
            EmptyState("Nothing to deal yet.", s.problem ?: "")
        } else {
            Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Breathe()
                Small("Reading the deck")
            }
        }
        return
    }
    val scroll = rememberScrollState()
    val thumb = if (phone) 56.dp else 72.dp
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (phone) 16.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            H2("Compare two cards")
            Body(
                "The deck is the same either way but for one card: the substitute takes the place of every copy. Each hand comes from one deck or the other, about half each, and is judged on the same five answers as any Shootout hand. Going second, the card drawn for your turn is rated apart from the opening five.",
                color = c.ink70,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("1 · The card in your deck", color = c.ink45)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                v.deckCards().forEach { id ->
                    key(id) { PickCard(h, id, thumb, selected = v.card == id, tag = "×${v.copies(id)}") { v.chooseCard(id) } }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("2 · Its substitute", color = c.ink45)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val sub = v.substitute
                if (sub != null) PickCard(h, sub, thumb, selected = true) { v.choosing = true }
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sub != null) {
                        RowText(nameOf(h, sub))
                        Small(v.card?.let { "In place of every copy of ${nameOf(h, it)}" } ?: "Choose the card it replaces")
                    }
                    MuButton(if (sub == null) "Choose a card" else "Change", { v.choosing = true }, size = BtnSize.SM, enabled = v.card != null, reason = "Choose the card in your deck first")
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("Which hands", color = c.ink45)
            val options = listOf<Stratum?>(null) + v.strata()
            val bench = s.bench
            if (phone) {
                MuSelect(v.pinned, options, { st -> st?.let { ShootoutWords.situation(it, bench?.opponentName) } ?: "Both turns" }, { v.pinned = it }, Modifier.fillMaxWidth(), small = true)
            } else {
                Segmented(v.pinned, options, { it?.let(ShootoutWords::stratum) ?: "Both turns" }, { v.pinned = it }, small = true)
            }
            if (bench?.alone == false) Help("Against an opponent the cards are compared in game 1: a siding plan may take the card out.")
        }
        val problem = v.problem()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton("Begin", { v.begin() }, variant = BtnVariant.PRIMARY, arrow = true, enabled = problem == null && !v.thinking, reason = problem)
            KeyCap(keyOf(DeskAction.SHOOTOUT_START, "Enter"))
        }
        if (v.earlier.isNotEmpty()) {
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Micro("Earlier comparisons", Modifier.padding(bottom = 6.dp), color = c.ink45)
                HRule()
                v.earlier.forEach { e ->
                    key(e.pick.card, e.pick.substitute) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RowText("${nameOf(h, e.pick.substitute)} for ${nameOf(h, e.pick.card)}", Modifier.weight(1f))
                            Small(ShootoutWords.hands(e.hands), color = c.ink45, maxLines = 1)
                            MicroLink("Results", { v.resume(e, showResults = true) })
                            MicroLink("Judge more", { v.resume(e, showResults = false) })
                        }
                        HRule()
                    }
                }
            }
        }
        Help("About ten minutes is a session. Stop whenever you like: every answer is kept with these two cards, apart from the deck's own ratings, and carries over to the next session.")
    }
}

/** A card to pick: its art, framed in ink when chosen, with an optional tag (the copies in the deck). */
@Composable
private fun PickCard(h: NeueHolders, id: Int, width: Dp, selected: Boolean, tag: String? = null, onPick: () -> Unit) {
    val c = Mu.colors
    val card = h.shootout.card(id)
    Box(
        Modifier
            .size(width, width / CARD_RATIO)
            .border(if (selected) 3.dp else 1.dp, if (selected) c.ink else c.ink12)
            .cursorPointer(label = card?.name ?: id.toString())
            .muClickable(onClick = onPick),
    ) {
        if (card != null) {
            NeueCard(card, Modifier.fillMaxSize().padding(if (selected) 3.dp else 0.dp), format = h.builder.format, foil = "off")
        } else {
            Small(id.toString(), Modifier.align(Alignment.Center))
        }
        if (tag != null) {
            Box(Modifier.align(Alignment.TopStart).background(c.ink).padding(horizontal = 4.dp, vertical = 1.dp)) { Micro(tag, color = c.paper) }
        }
    }
}

/** The substitute's chooser: the side deck first, any Main Deck card by name or by what it says. */
@Composable
internal fun SubstituteChooser(h: NeueHolders) {
    val v = h.shootout.versus
    val index = h.builder.index
    var query by remember { mutableStateOf("") }
    val side: List<Card> = remember(index, v.card, h.shootout.input) { v.sideCards().mapNotNull { index.byId(CardId(it)) } }
    val found: List<Card>? by produceState<List<Card>?>(null, query, index) {
        if (query.trim().length < 2) {
            value = null
            return@produceState
        }
        delay(SEARCH_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { index.search(query, limit = 90).cards.filter { !it.isExtraDeck } }
    }
    val results: List<Card> = if (query.trim().length < 2) side else found ?: side
    MuDialog(
        "Choose its substitute",
        { v.choosing = false },
        width = 860.dp,
        scrolls = false,
        description = v.card?.let { "It takes the place of every copy of ${nameOf(h, it)}." },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MuInput(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search by name, or text: what it says")
            if (results.isEmpty()) Help(if (query.isBlank()) "Your side deck has no Main Deck card: type a card's name." else "No card matches.")
            else if (query.trim().length < 2) Help("From your side deck")
            LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxWidth().height(420.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id.value }) { card ->
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(CARD_RATIO)
                            .border(1.dp, Mu.colors.ink12)
                            .cursorPointer(label = card.name)
                            .muClickable { v.chooseSubstitute(card.id.value) },
                    ) {
                        NeueCard(card, Modifier.fillMaxSize(), format = h.builder.format, foil = "off")
                    }
                }
            }
        }
    }
}

// ---- a hand ------------------------------------------------------------------------------------------------------

@Composable
private fun VersusTrial(h: NeueHolders, phone: Boolean) {
    val s = h.shootout
    val v = s.versus
    val c = Mu.colors
    val d = v.deal
    val bench = v.bench
    if (d == null || bench == null) {
        Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Breathe()
            Small("Dealing a hand")
        }
        return
    }
    var hint by remember { mutableStateOf<Answer?>(null) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = if (phone) 12.dp else 32.dp, vertical = if (phone) 10.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            H2(ShootoutWords.situation(d.proposal.stratum, bench.opponentName), Modifier.weight(1f), maxLines = 2)
            if (!phone) Small(progressLine(h), color = c.ink45, maxLines = 1)
        }
        if (phone) Small(progressLine(h), color = c.ink45, maxLines = 2)
        if (v.sessionMs >= VersusRun.SESSION_MS) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Small("Ten minutes in: a good place to stop. Every answer is kept.")
                MicroLink("Stop and see the results", v::stop)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().swipeToAnswer(onHint = { hint = it }, onAnswer = v::answer)) {
            VersusHands(h, d, maxWidth, maxHeight, phone)
            hint?.let { a ->
                Box(Modifier.align(Alignment.TopCenter).background(c.ink).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Micro(ShootoutWords.label(a, bench.alone), color = c.paper)
                }
            }
        }
        ReadingStrip(s.reading)
        Body(ShootoutWords.question(bench.alone), color = c.ink)
        AnswerScale(v.thinking, v::answer, bench.alone, phone)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!phone) {
                KeyCap("${keyOf(DeskAction.SHOOTOUT_ANSWER_1, "1")}–${keyOf(DeskAction.SHOOTOUT_ANSWER_5, "5")}")
                Help("answer", color = c.ink45)
                KeyCap(keyOf(DeskAction.SHOOTOUT_DRAW_MINE, "D"))
                Help("draw", color = c.ink45)
                KeyCap("Esc")
                Help("stop", color = c.ink45, modifier = Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f))
            }
            MuButton("Stop and see the results", v::stop, size = BtnSize.SM, enabled = !v.thinking)
        }
    }
}

/** "Ash Blossom leans ahead by 3 points on its own, known within ± 9 points · 12 hands this session · 4 min". */
private fun progressLine(h: NeueHolders): String {
    val v = h.shootout.versus
    val parts = mutableListOf<String>()
    v.progress?.let { p ->
        if (p.withCard + p.withSubstitute > 0) {
            parts += VersusWords.verdict(p.alone, nameOf(h, p.card), nameOf(h, p.substitute)) + " on its own, " + VersusWords.known(p.alone)
        }
    }
    parts += "${ShootoutWords.hands(v.sessionAnswers)} this session"
    if (v.sessionMs >= 60_000) parts += "${v.sessionMs / 60_000} min"
    return parts.joinToString(" · ")
}

/** The hand (and theirs above it in a matchup), as a Shootout's: the turn's draw sixth and marked, draws by effects apart. */
@Composable
private fun VersusHands(h: NeueHolders, d: VersusDeal, width: Dp, height: Dp, phone: Boolean) {
    val v = h.shootout.versus
    val mine = v.myShown(d)
    val theirs = v.theirShown(d)
    val gap = if (phone) 6.dp else 12.dp
    val label = 28.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (theirs != null) {
            val room = height - label * 2 - gap * 3
            HandHead(handWords("Their hand", theirs), "Draw for them", keyOf(DeskAction.SHOOTOUT_DRAW_THEIRS, "Shift D"), phone) { v.drawTheirs() }
            Hand(h, theirs, width, room * 0.38f, gap, phone)
            HandHead(handWords("Your hand", mine), "Draw for you", keyOf(DeskAction.SHOOTOUT_DRAW_MINE, "D"), phone) { v.drawMine() }
            Hand(h, mine, width, room * 0.62f, gap, phone)
        } else {
            HandHead(handWords("Your hand", mine), "Draw a card", keyOf(DeskAction.SHOOTOUT_DRAW_MINE, "D"), phone) { v.drawMine() }
            Hand(h, mine, width, height - label - gap, gap, phone)
        }
    }
}

// ---- the results -------------------------------------------------------------------------------------------------

@Composable
private fun VersusResultsView(h: NeueHolders, phone: Boolean) {
    val v = h.shootout.versus
    val c = Mu.colors
    val r = v.results
    if (r == null) {
        Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (v.thinking) {
                Breathe()
                Small("Reading every hand")
            } else {
                Small("No results yet.")
            }
        }
        return
    }
    val card = nameOf(h, r.card)
    val sub = nameOf(h, r.substitute)
    val scroll = rememberScrollState()
    // Every number on one axis, as the results' cards are (Phase G, G.4): the opening hand filled, the draw hollow.
    val axis = remember(r) { Axis.from(r.sides.flatMap { listOfNotNull(it.alone, it.drawn, it.overall) } + r.alone + r.overall) }
    val plot = if (phone) 120.dp else 280.dp
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (phone) 16.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HandCard(h, r.card, if (phone) 48.dp else 64.dp)
            Small("against", color = c.ink45)
            HandCard(h, r.substitute, if (phone) 48.dp else 64.dp)
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                H2("$sub for $card", maxLines = 2)
                Small("${ShootoutWords.hands(r.withCard)} with $card · ${ShootoutWords.hands(r.withSubstitute)} with $sub · ${ShootoutWords.hands(r.answered)} judged in all")
            }
        }
        if (r.answered == 0) {
            EmptyState("No hands judged yet.", "Judge a few hands: the first answers stand here with their ranges.")
        } else {
            Help(
                "Every number is in points of win chance, with its 95 % range: how much the deck gains or loses with one card in the other's place. A card is only called better once its range leaves zero behind.",
                Modifier.widthIn(max = 900.dp),
            )
            VersusSection(
                "On its own",
                VersusWords.verdict(r.alone, card, sub),
                r.alone,
                "One copy in the opening hand swapped for the other, the rest of the hand the same, with what either card does beside another card set aside.",
            ) {
                r.sides.forEach { side ->
                    key(side.stratum) {
                        SideRow(ShootoutWords.stratum(side.stratum), side.alone, card, sub, ShootoutWords.hands(side.aloneHands) + " of the report's", axis, plot)
                        side.drawn?.let { SideRow("${ShootoutWords.stratum(side.stratum)} · as your draw", it, card, sub, "rated apart from the five", axis, plot, drawn = true) }
                    }
                }
            }
            PartnersSection(h, r, card, sub, phone)
            VersusSection(
                "The deck overall",
                VersusWords.verdict(r.overall, "The deck with $card", "The deck with $sub").replace("is better", "does better"),
                r.overall,
                "Every hand a shuffle deals, holding the card or not: how often the card turns up counts as much as what it does.",
            ) {
                r.sides.forEach { side -> key(side.stratum) { OverallRow(side, card, sub, axis, plot) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MuButton("Judge more hands", { v.begin() }, variant = BtnVariant.PRIMARY, arrow = true, enabled = !v.thinking)
            MuButton("Choose other cards", v::toSetup, variant = BtnVariant.GHOST)
        }
    }
}

@Composable
private fun VersusSection(title: String, verdict: String, e: Estimate, help: String, rows: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(null, title)
        Body("$verdict (${VersusWords.range(e)})", color = Mu.colors.ink)
        Help(help)
        Column {
            HRule()
            rows()
        }
    }
}

@Composable
private fun SideRow(label: String, e: Estimate, card: String, sub: String, note: String, axis: Axis, plot: Dp, drawn: Boolean = false) {
    val c = Mu.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Small(label, Modifier.width(180.dp), color = c.ink, maxLines = 2)
        Column(Modifier.weight(1f)) {
            RowText(VersusWords.verdict(e, card, sub), maxLines = 2)
            Small("${VersusWords.range(e)} · $note", color = c.ink45, maxLines = 2)
        }
        PlotCell(axis, if (drawn) null else e, if (drawn) e else null, Modifier.width(plot).height(26.dp))
    }
    HRule()
}

@Composable
private fun OverallRow(side: VersusSide, card: String, sub: String, axis: Axis, plot: Dp) {
    val c = Mu.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Small(ShootoutWords.stratum(side.stratum), Modifier.width(180.dp), color = c.ink, maxLines = 2)
        Column(Modifier.weight(1f)) {
            RowText("With $card ${VersusWords.percent(side.withCard)} · with $sub ${VersusWords.percent(side.withSubstitute)}", maxLines = 2)
            Small(
                "${VersusWords.verdict(side.overall, "the deck with $card", "the deck with $sub").replaceFirstChar { it.uppercase() }} · ${VersusWords.range(side.overall)} · $card is in ${(side.heldShare * 10).roundToInt()} hands in 10",
                color = c.ink45,
                maxLines = 2,
            )
        }
        PlotCell(axis, side.overall, null, Modifier.width(plot).height(26.dp))
    }
    HRule()
}

/** Beside your other cards: the partners that tell the two apart, then — on a press — every partner. */
@Composable
private fun PartnersSection(h: NeueHolders, r: VersusResults, card: String, sub: String, phone: Boolean) {
    val c = Mu.colors
    var all by remember(r) { mutableStateOf(false) }
    val telling = r.telling
    Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(null, "Beside your other cards")
        Body(
            if (telling.isEmpty()) "No card tells them apart yet." else "${telling.size} of your cards ${if (telling.size == 1) "tells" else "tell"} them apart.",
            color = c.ink,
        )
        Help("What a card held with them changes: the comparison in hands holding that card, less the two cards on their own. A card is named once its range leaves zero behind.")
        Column {
            HRule()
            telling.forEach { (stratum, p) ->
                key(stratum, p.partner) {
                    PartnerRow(h, p.partner, VersusWords.partner(p, card, sub, nameOf(h, p.partner)) ?: "", "${ShootoutWords.stratum(stratum)} · ${VersusWords.range(p.synergy)} · ${ShootoutWords.hands(p.hands)} of the report's", phone)
                }
            }
        }
        MicroLink(if (all) "Hide the other cards" else "Every card held with them", { all = !all })
        if (all) {
            r.sides.forEach { side ->
                key(side.stratum) {
                    Micro(ShootoutWords.stratum(side.stratum), Modifier.padding(top = 12.dp, bottom = 4.dp), color = c.ink70)
                    HRule()
                    side.partners.sortedByDescending { it.both.value }.forEach { p ->
                        key(p.partner) {
                            PartnerRow(h, p.partner, "Beside ${nameOf(h, p.partner)}: " + VersusWords.verdict(p.both, card, sub).replaceFirstChar { it.lowercase() }, "${VersusWords.range(p.both)} · ${ShootoutWords.hands(p.hands)} of the report's", phone)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartnerRow(h: NeueHolders, partner: Int, words: String, note: String, phone: Boolean) {
    val c = Mu.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        HandCard(h, partner, if (phone) 32.dp else 40.dp)
        Column(Modifier.weight(1f)) {
            RowText(words, maxLines = 2)
            Small(note, color = c.ink45, maxLines = 2)
        }
    }
    HRule()
}

// ---- keys ----------------------------------------------------------------------------------------------------------

/** The page's keys while card against card is open; true when the key was its own. */
internal fun runVersus(h: NeueHolders, action: DeskAction): Boolean {
    val v = h.shootout.versus
    if (v.choosing) return action != DeskAction.GO_SHOOTOUT
    when (action) {
        DeskAction.SHOOTOUT_ANSWER_1, DeskAction.SHOOTOUT_ANSWER_2, DeskAction.SHOOTOUT_ANSWER_3,
        DeskAction.SHOOTOUT_ANSWER_4, DeskAction.SHOOTOUT_ANSWER_5,
        -> if (v.running) ShootoutWords.byKey(action.ordinal - DeskAction.SHOOTOUT_ANSWER_1.ordinal + 1)?.let(v::answer)
        DeskAction.SHOOTOUT_DRAW_MINE -> if (v.running) v.drawMine()
        DeskAction.SHOOTOUT_DRAW_THEIRS -> if (v.running) v.drawTheirs()
        DeskAction.SHOOTOUT_START -> if (v.phase == ShootoutVersus.Phase.SETUP) v.begin()
        DeskAction.SHOOTOUT_STOP, DeskAction.SHOOTOUT_RESULTS -> if (v.running) v.stop()
        DeskAction.SHOOTOUT_LEFT, DeskAction.SHOOTOUT_RIGHT, DeskAction.SHOOTOUT_ACCEPT, DeskAction.SHOOTOUT_TRUST -> Unit
        else -> return false
    }
    return true
}

/** Esc while card against card is open: the card being read, then the session stops, then back a step. */
internal fun dismissVersus(h: NeueHolders): Boolean {
    val s = h.shootout
    val v = s.versus
    when {
        v.choosing -> v.choosing = false
        s.reading != null -> s.reading = null
        v.running -> v.stop()
        v.phase == ShootoutVersus.Phase.RESULTS -> v.toSetup()
        else -> v.leave()
    }
    return true
}
