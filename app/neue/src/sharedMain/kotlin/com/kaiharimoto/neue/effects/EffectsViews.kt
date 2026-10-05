package com.kaiharimoto.neue.effects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.effects.FxAsks
import com.kaiharimoto.mastertool.core.duel.effects.FxCost
import com.kaiharimoto.mastertool.core.duel.effects.FxFrom
import com.kaiharimoto.mastertool.core.duel.effects.FxStatus
import com.kaiharimoto.mastertool.core.duel.effects.FxSuggest
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The window's holders, for the views that are drawn without them and offer an effect's go (the card viewer, the
 * builder's inspector): provided by the shell, null in a picture drawn without one (then nothing is offered).
 */
val LocalEffectsHolders = staticCompositionLocalOf<NeueHolders?> { null }

/**
 * A card's written effect, where the card is read (Phase D step 2, D.md §3.1, §3.5): its status, its script in our own words
 * (`FxWords`) and what asking it cost — and, on a card with none, **Write its effect**, the person's go, with the cost said
 * before it. [from] is where the go is made (`FxFrom`). Shown only while Ai is on; a Normal Monster says it has nothing.
 */
@Composable
fun CardEffects(h: NeueHolders, card: Card, from: String, modifier: Modifier = Modifier) {
    if (!h.neue.prefs.ai.enabled) return
    val c = Mu.colors
    val fx = h.effects
    val code = card.id.value
    // Read again whenever the library or the asked list moves.
    val status = remember(fx.revision, fx.entries, code) { fx.status(code) }
    val words = remember(fx.revision, fx.entries, code) { fx.words(code) }
    val ask = fx.asked.of(code)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono(status.words.replaceFirstChar { it.uppercase() }, color = if (status == FxStatus.BROKEN || status == FxStatus.WARNED) c.ink else c.ink70)
        }
        words.forEach { line -> Small("${line.head} — ${line.text}", color = c.ink) }
        if (status.usable && words.isNotEmpty()) Help("Written as code, in our own words above; unverified until it has been tested against your duels.")
        ask?.let { a ->
            val spent = FxCost.spentWords(a)
            Help(
                "Asked from ${FxFrom.words(a.from)} · ${FxAsks.stateWords(a.state)}" + (spent?.let { " · $it" } ?: ""),
                color = c.ink45,
            )
        }
        val writing = fx.authoring?.request?.cards?.contains(code) == true && h.ai.running
        when {
            status == FxStatus.NONE -> Unit
            writing -> Help("${h.ai.name} is writing it now.", color = c.ink70)
            status == FxStatus.MISSING || status == FxStatus.BROKEN || status == FxStatus.WARNED -> {
                val offer = remember(fx.revision, fx.asked, code, h.ai.prefs.connection) { h.offerFor(listOf(code), "${card.name}'s effect", h.builder.deckId) }
                val label = if (status == FxStatus.MISSING) "Write its effect" else "Repair its effect"
                MuButton(label, { h.go(offer, from) }, size = BtnSize.SM, variant = BtnVariant.SECONDARY, enabled = !h.ai.running, reason = "${h.ai.name} is answering: wait, or Stop it first")
                Help(FxCost.words(offer.estimate), color = c.ink45)
            }
            else -> Unit
        }
    }
}

/** The Duel page inspector's "Effect as code" (D.md §3.5): the same words and go as the builder's, for a card the person sees. */
@Composable
fun DuelCardEffects(card: Card) {
    val h = LocalEffectsHolders.current ?: return
    if (!h.neue.prefs.ai.enabled) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("Effect as code", color = Mu.colors.ink45)
        CardEffects(h, card, FxFrom.DUEL)
    }
}

/**
 * **Write this combo's cards** on a combo's row (D.md §3.1): the cards [combo] uses among its deck's ([deckId]), with the
 * cost in the tip before the go. [onGo] closes what it stands in.
 */
@Composable
fun ComboWriteButton(h: NeueHolders, combo: Combo, deckId: String, onGo: () -> Unit) {
    if (!h.neue.prefs.ai.enabled) return
    val cards by produceState(emptyList<Int>(), combo, deckId, h.builder.index.size) {
        val deck = if (deckId == h.builder.deckId) h.builder.deck else h.deps.deckRepository.byId(deckId)?.entry?.deck
        val index = h.builder.index
        val inDeck = deck?.let { d -> (d.main + d.extra).mapNotNull { id -> index.byId(id) }.distinctBy { c -> c.id } }.orEmpty()
        value = withContext(Dispatchers.Default) { FxSuggest.comboCards(combo, inDeck).toList() }
    }
    val offer = remember(cards, h.effects.revision, h.effects.asked, h.ai.prefs.connection) {
        h.offerFor(cards, "the cards of the combo “${combo.name}”", deckId)
    }
    Tip(if (cards.isEmpty()) "No card of the deck is named in this combo" else FxCost.words(offer.estimate)) {
        MuButton(
            "Write its cards",
            { if (h.go(offer, FxFrom.COMBO)) onGo() },
            size = BtnSize.SM,
            variant = BtnVariant.GHOST,
            enabled = offer.toWrite.isNotEmpty() && !h.ai.running,
            reason = if (cards.isEmpty()) "No card of the deck is named in it" else h.goBlocked(offer) ?: "${h.ai.name} is answering",
        )
    }
}
