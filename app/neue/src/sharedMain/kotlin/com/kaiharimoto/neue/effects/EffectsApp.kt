package com.kaiharimoto.neue.effects

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.effects.FxAsks
import com.kaiharimoto.mastertool.core.duel.effects.FxCost
import com.kaiharimoto.mastertool.core.duel.effects.FxEntry
import com.kaiharimoto.mastertool.core.duel.effects.FxFrom
import com.kaiharimoto.mastertool.core.duel.effects.FxReviews
import com.kaiharimoto.mastertool.core.duel.effects.FxStatus
import com.kaiharimoto.mastertool.core.duel.effects.FxSuggest
import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuTabs
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Effects app in Ai World (Phase D step 2, `docs/phases/D.md` §3.5; `Alt 8`, `BuiltInApp.EFFECTS`): the library of
 * written effects and asking for more. Above, the open deck — how much of it is written, a choice of what to write
 * (what to write first, the engine, a group, a combo) as the cards' art, and **Write these** with the cost said before
 * the go. Below, every card the library holds: its status, its script in words, what asking it cost, its warnings (only the
 * person accepts one, with why), and Ask Ai about it. A card written for another deck shows as reused, at no cost.
 */

/** The phone switcher's line for the Effects app. */
internal fun effectsSummary(h: NeueHolders): String {
    val n = h.effects.entries.size
    return if (n == 0) "No effects written yet" else "$n card${if (n == 1) "" else "s"} written as code"
}

/** One choice of what to write for the open deck: its name in the picker and its cards, in order. */
private data class Scope(val label: String, val what: String, val cards: List<Int>)

/** The Effects app's window body. */
@Composable
fun EffectsApp(h: NeueHolders, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val fx = h.effects
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // The library, and the goldfish on the open deck (Phase D step 4).
            val runs = fx.goldfishRuns
            MuTabs(runs.tab, EffectsTab.entries, { it.words }, { runs.tab = it }, Modifier.fillMaxWidth())
            if (runs.tab == EffectsTab.GOLDFISH) {
                GoldfishPane(h)
            } else {
                if (!h.neue.prefs.ai.enabled) {
                    Help("Ai is off: the library below is read, and nothing new can be asked for.", color = c.ink70)
                }
                fx.working?.let { Mono(it, color = c.ink45) }
                if (h.builder.deck.totalCards > 0) {
                    DeckSection(h)
                    HRule()
                }
                LibrarySection(h)
            }
        }
        ScrollbarFor(scroll)
    }
}

// ---- The open deck ---------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckSection(h: NeueHolders) {
    val c = Mu.colors
    val fx = h.effects
    val b = h.builder
    val deckId = b.deckId
    var combos by remember(deckId) { mutableStateOf<List<Combo>>(emptyList()) }
    LaunchedEffect(deckId) { combos = withContext(Dispatchers.IO) { combosOf(deckId) } }
    val index = b.index
    val canon = remember(index.size) { { code: Int -> index.byId(CardId(code))?.id?.value ?: code } }
    val main = b.deck.main.map { it.value }
    val extra = b.deck.extra.map { it.value }
    val distinct = remember(b.deck, index.size) { (main + extra).map(canon).distinct() }
    val deckCards = remember(distinct) { distinct.mapNotNull { index.byId(CardId(it)) } }
    val scopes = remember(fx.revision, fx.entries, b.deck, b.groups, combos, index.size) {
        val comboCards = combos.map { FxSuggest.comboCards(it, deckCards) }
        val engine = FxSuggest.engine(b.groups, canon)
        buildList {
            add(Scope("What to write first", "what to write first for ${b.deckName}", fx.suggest(main, extra, comboCards, engine).map { it.card }))
            val eng = (comboCards.flatten() + engine).distinct()
            if (eng.isNotEmpty()) add(Scope("The engine", "${b.deckName}'s engine", eng))
            b.groups.ordered().forEach { g ->
                val cards = b.groups.assignments.filterValues { it == g.id }.keys.map { canon(it.value) }.sortedBy { distinct.indexOf(it) }
                if (cards.isNotEmpty()) add(Scope("Group · ${g.name}", "${b.deckName}'s ${g.name}", cards))
            }
            combos.forEachIndexed { i, combo -> comboCards[i].takeIf { it.isNotEmpty() }?.let { add(Scope("Combo · ${combo.name}", "the cards of the combo “${combo.name}”", it.toList())) } }
            add(Scope("The whole deck", "every card of ${b.deckName}", distinct))
        }
    }
    var chosen by remember(deckId) { mutableStateOf(0) }
    val scope = scopes.getOrElse(chosen) { scopes.first() }
    var left by remember(deckId, chosen) { mutableStateOf<Set<Int>>(emptySet()) }
    val statuses = remember(fx.revision, fx.entries, distinct) { distinct.associateWith { fx.status(it) } }
    // What the goldfish and the Mapper play (FxTrust.USED), apart from what is written but cannot be played yet: the line
    // counted the two together, and read as more of the deck than the simulation sees (2026-10, the red team's finding 11).
    val played = statuses.values.count { it in FxTrust.USED }
    val unplayable = statuses.values.count { it == FxStatus.UNSUPPORTED }
    val repair = statuses.values.count { it == FxStatus.BROKEN || it == FxStatus.WARNED || it == FxStatus.FAILING }
    val none = statuses.values.count { it == FxStatus.NONE }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Micro("The open deck", color = c.ink45)
        RowText(b.deckName.ifBlank { "Untitled deck" }, color = c.ink)
        Small(
            "$played of ${distinct.size - none} cards play in the goldfish" +
                (if (unplayable > 0) " · $unplayable written, not playable yet" else "") +
                (if (repair > 0) " · $repair to repair" else "") +
                (if (none > 0) " · $none Normal Monster${if (none == 1) "" else "s"}, nothing to write" else ""),
            color = c.ink70,
        )
        MuSelect(scope, scopes, { it.label }, { s -> chosen = scopes.indexOf(s) }, Modifier.fillMaxWidth(), small = true)
        val cards = scope.cards.filter { statuses[it] != FxStatus.NONE }
        if (cards.isEmpty()) {
            Help(if (scope.label == "What to write first") "Nothing to suggest: every card that has an effect is written." else "No cards here.", color = c.ink70)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val across = if (LocalPhone.current) 4 else 6
                val w = ((maxWidth - 6.dp * (across - 1)) / across).coerceIn(44.dp, 84.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cards.forEach { code ->
                        key(code) {
                            val st = statuses[code] ?: fx.status(code)
                            val ask = fx.asked.of(code)
                            val done = st == FxStatus.VERIFIED || st == FxStatus.UNTESTED || st == FxStatus.UNSUPPORTED
                            // Written for another deck (or before asking existed): reused here at no cost (D.md §3.1).
                            val reused = done && (ask == null || ask.deck != deckId)
                            val word = when {
                                st == FxStatus.UNSUPPORTED -> "not playable yet"
                                done && reused -> "reused"
                                done -> "written"
                                st == FxStatus.MISSING -> if (ask != null) FxAsks.stateWords(ask.state) else "to write"
                                else -> "to repair"
                            }
                            Column(Modifier.width(w), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val out = code in left
                                CardArt(
                                    h, code, w,
                                    dimmed = done || out,
                                    caption = if (done) "Open" else if (out) "Put back" else "Leave out",
                                ) { if (done) open(h, code) else left = if (out) left - code else left + code }
                                Micro(if (out) "left out" else word, color = if (done) c.ink45 else c.ink70)
                            }
                        }
                    }
                }
            }
            val offer = remember(fx.revision, fx.asked, cards, left, h.ai.prefs.connection) { h.offerFor(cards - left, scope.what, deckId) }
            Small(FxCost.words(offer.estimate), color = c.ink70)
            if (offer.reused.isNotEmpty()) Help("${offer.reused.size} already written: every deck reads the one script, at no cost.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton(
                    if (offer.toWrite.size <= 1) "Write it" else "Write these ${offer.toWrite.size}",
                    { h.go(offer, FxFrom.PANE) },
                    variant = BtnVariant.PRIMARY,
                    size = BtnSize.SM,
                    enabled = offer.toWrite.isNotEmpty() && h.neue.prefs.ai.enabled && !h.ai.running,
                    reason = h.goBlocked(offer) ?: "${h.ai.name} is answering: wait, or Stop it first",
                )
                if (left.isNotEmpty()) MicroLink("Put every card back", { left = emptySet() })
            }
        }
    }
}

// ---- The library ----------------------------------------------------------------------------------------------------

@Composable
private fun LibrarySection(h: NeueHolders) {
    val c = Mu.colors
    val fx = h.effects
    val index = h.builder.index
    val rows = remember(fx.entries, fx.revision, index.size) {
        // What needs the person first: broken, then warned, then the rest by name.
        fx.entries.values.sortedWith(
            compareBy<FxEntry>({ order(it.status) }, { index.byId(CardId(it.card))?.name ?: it.card.toString() }),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Micro("The library", color = c.ink45)
        val by = rows.groupingBy { it.status }.eachCount()
        Small(
            if (rows.isEmpty()) "No card's effect is written yet. Ask for one above, from a card's Write its effect, or in the chat."
            else "${rows.size} card${if (rows.size == 1) "" else "s"}: " + FxStatus.entries.mapNotNull { st -> by[st]?.let { "$it ${st.words}" } }.joinToString(" · "),
            color = c.ink70,
        )
        Help("One script a card, read by every deck that plays it, in any printing. Everything here is unverified until it is tested against your duels.")
        rows.forEach { e -> key(e.card) { LibraryRow(h, e) } }
    }
}

private fun order(s: FxStatus): Int = when (s) {
    FxStatus.BROKEN -> 0
    FxStatus.FAILING -> 1
    FxStatus.WARNED -> 2
    else -> 3
}

@Composable
private fun LibraryRow(h: NeueHolders, e: FxEntry) {
    val c = Mu.colors
    val fx = h.effects
    val card = h.builder.index.byId(CardId(e.card))
    val words = remember(e, fx.revision) { fx.words(e.card) }
    val ask = fx.asked.of(e.card)
    val phone = LocalPhone.current
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardArt(h, e.card, if (phone) 44.dp else 56.dp, dimmed = false, caption = "Open") { open(h, e.card) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RowText(card?.name ?: e.card.toString(), color = c.ink)
                Mono(e.status.words.replaceFirstChar { it.uppercase() }, color = if (order(e.status) < 3) c.ink else c.ink45)
                ask?.let { a ->
                    Help(FxCost.askedWords(a), color = c.ink45)
                }
            }
        }
        e.compileError?.let { Small("Did not compile: $it", color = c.ink) }
        words.forEach { line -> Small("${line.head} — ${line.text}", color = c.ink) }
        e.report?.errors?.forEach { f -> Small("Error${f.effect?.let { " · $it" }.orEmpty()}: ${f.message}", color = c.ink) }
        e.open.forEach { f -> WarningRow(h, e.card, f.key, "Warning${f.effect?.let { " · $it" }.orEmpty()}: ${f.message}") }
        e.review?.accepted?.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small("Accepted ${a.key}: ${a.why}", Modifier.weight(1f), color = c.ink45)
                MicroLink("Withdraw", { fx.withdraw(e.card, a.key) })
            }
        }
        if (h.neue.prefs.ai.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MicroLink("Ask ${h.ai.name} about it", {
                    h.ai.draft = "About the written effect of [[${card?.name ?: e.card}]] (${e.card}): "
                    h.ai.setOpen(true)
                    h.ai.focusTick++
                })
                if (e.status == FxStatus.BROKEN || e.status == FxStatus.WARNED) {
                    MicroLink("Repair it", { h.writeEffects(listOf(e.card), FxFrom.PANE, "${card?.name ?: e.card}'s effect") })
                }
            }
        }
    }
}

/** A warning, with the person's Accept and why (D.md §3.5, §7: only the person, never Ai, and never without a reason). */
@Composable
internal fun WarningRow(h: NeueHolders, card: Int, key: String, text: String) {
    val c = Mu.colors
    var why by remember(card, key) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Small(text, Modifier.weight(1f), color = c.ink)
            if (why == null) MicroLink("Accept…", { why = "" })
        }
        why?.let { w ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuInput(w, { why = it }, Modifier.weight(1f), placeholder = "Why it is right as it is", dense = true, onSubmit = {
                    if (w.isNotBlank() && h.effects.accept(card, key, w, FxReviews.PERSON)) why = null
                })
                MuButton("Accept", { if (h.effects.accept(card, key, w, FxReviews.PERSON)) why = null }, size = BtnSize.SM, enabled = w.isNotBlank(), reason = "Say why first")
                MicroLink("Cancel", { why = null })
            }
        }
    }
}

/** A card's art at [width]; a click does [onClick] ([caption] on the cursor). A passcode the pool does not know is a box. */
@Composable
internal fun CardArt(h: NeueHolders, code: Int, width: Dp, dimmed: Boolean, caption: String, onClick: () -> Unit) {
    val c = Mu.colors
    val card = h.builder.index.byId(CardId(code))
    val height = width / CARD_RATIO
    val tap = Modifier.cursorPointer(caption = caption).muClickable(onClick = onClick)
    if (card == null) {
        Box(Modifier.width(width).height(height).border(1.dp, c.ink25).then(tap).padding(4.dp)) { Micro(code.toString(), color = c.ink45, maxLines = 3) }
    } else {
        NeueCard(card, Modifier.width(width).alpha(if (dimmed) 0.45f else 1f).then(tap), format = h.builder.format, foil = "off")
    }
}

private fun open(h: NeueHolders, code: Int) {
    h.builder.index.byId(CardId(code))?.let { h.neue.viewing = Viewing(it, null, 0) }
}
