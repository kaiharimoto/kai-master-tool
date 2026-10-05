package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.duel.effects.FxOffers
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.effects.FxSuggest
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.neue.effects.combosOf
import com.kaiharimoto.neue.effects.newRequestId
import com.kaiharimoto.neue.effects.offerFor
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.neue.NeueHolders
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Effects as code, Ai's hands (Phase D step 2, D.md §3.6): `fx_state` reads the library, `fx_check` compiles and checks one
 * card (and, in an effects session, keeps what the card cost), `fx_request` offers cards to write — a request card in the
 * chat, never an ask: only the person's Write puts cards on the asked list. A handler group of its own, as F2's pattern
 * asks, not more of `AiHost`; targets and the goldfish join it here.
 */
internal class AiEffects(private val h: NeueHolders) {
    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "fx_state" -> state(ToolArgs.string(i, "card"), ToolArgs.string(i, "deck_id"))
        "fx_check" -> check(ToolArgs.string(i, "card"))
        "fx_request" -> request(ToolArgs.strings(i, "cards"), ToolArgs.string(i, "deck_id"), ToolArgs.string(i, "scope"), ToolArgs.string(i, "name"))
        else -> null
    }

    /** A deck by id ("open" or none: the builder's): its name, cards and groups, or why not. */
    private suspend fun deck(id: String?): Triple<String, Deck, DeckGroups>? {
        val b = h.builder
        if (id == null || id.equals("open", ignoreCase = true) || id == b.deckId) return Triple(b.deckName, b.deck, b.groups)
        val s = h.deps.deckRepository.byId(id) ?: return null
        return Triple(s.entry.name, s.entry.deck, DeckGroupsCodec.read(s.extended).groups)
    }

    /**
     * `fx_request` (D.md §3.6): the cards named, or a deck's engine, group, combo or suggestions, sorted and priced — an offer
     * the chat draws as a request card. **It adds nothing to the asked list.**
     */
    private suspend fun request(words: List<String>, deckId: String?, scope: String?, name: String?): MetaAnswer {
        val fx = h.effects
        if (!fx.loaded) fx.reloadNow()
        val cards = mutableListOf<Int>()
        val unknown = mutableListOf<String>()
        words.forEach { w ->
            val (card, why) = card(w)
            if (card != null) cards += card.id.value else unknown += why.orEmpty()
        }
        var what = when (cards.size) {
            0 -> ""
            1 -> "${h.builder.index.byId(CardId(cards[0]))?.name ?: cards[0]}'s effect"
            else -> "${cards.size} cards' effects"
        }
        var deckScope: String? = null
        if (deckId != null || (words.isEmpty() && scope != null)) {
            val (deckName, deck, groups) = deck(deckId) ?: return fail("No deck $deckId: list_decks names them.")
            val realId = if (deckId == null || deckId.equals("open", ignoreCase = true)) h.builder.deckId else deckId
            deckScope = realId
            val canon = { c: Int -> h.builder.index.byId(CardId(c))?.id?.value ?: c }
            val inDeck = (deck.main + deck.extra).map { it.value }
            val deckCards = inDeck.mapNotNull { h.builder.index.byId(CardId(it)) }.distinctBy { it.id }
            val combos = combosOf(realId)
            when (scope?.lowercase() ?: "engine") {
                "group" -> {
                    val g = groups.groups.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: groups.groups.firstOrNull { name != null && it.name.contains(name, ignoreCase = true) }
                        ?: return fail("No group “${name.orEmpty()}” in $deckName: its groups are ${groups.ordered().joinToString { it.name }.ifBlank { "none" }}.")
                    cards += groups.assignments.filterValues { it == g.id }.keys.map { canon(it.value) }.sortedBy { inDeck.map(canon).indexOf(it) }
                    what = "$deckName's ${g.name}"
                }
                "combo" -> {
                    val combo = combos.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: combos.firstOrNull { name != null && it.name.contains(name, ignoreCase = true) }
                        ?: return fail("No combo “${name.orEmpty()}” kept for $deckName: ${combos.joinToString { it.name }.ifBlank { "it has none yet" }}.")
                    cards += FxSuggest.comboCards(combo, deckCards)
                    what = "the cards of the combo “${combo.name}”"
                }
                "suggested" -> {
                    cards += fx.suggest(deck.main.map { it.value }, deck.extra.map { it.value }, combos.map { FxSuggest.comboCards(it, deckCards) }, FxSuggest.engine(groups, canon)).map { it.card }
                    what = "what to write first for $deckName"
                }
                else -> {
                    cards += combos.flatMap { FxSuggest.comboCards(it, deckCards) } + FxSuggest.engine(groups, canon)
                    what = "$deckName's engine"
                    if (cards.isEmpty()) return fail("$deckName has no saved combos and no group that reads as its engine (Starters, Extenders…): name the cards, or use scope suggested.")
                }
            }
        }
        if (cards.isEmpty()) return fail(if (unknown.isNotEmpty()) unknown.joinToString(" ") else "fx_request needs cards, or a deck with what of it to write.")
        val offer = h.offerFor(cards, what, deckScope, newRequestId())
        val index = h.builder.index
        val text = buildString {
            append(FxOffers.words(offer) { c -> index.byId(CardId(c))?.name ?: c.toString() })
            if (unknown.isNotEmpty()) append("\nNot found: ").append(unknown.joinToString(" "))
            append("\n").append(FxOffers.embed(offer))
        }
        val summary = if (offer.toWrite.isEmpty()) "Every card asked about is already written" else "Offered ${offer.toWrite.size} card${if (offer.toWrite.size == 1) "" else "s"} to write"
        return ok(text, summary)
    }

    /** The card a word names, by name or passcode, or why not. */
    private fun card(word: String): Pair<Card?, String?> = when (val r = CardWords.resolve(word, h.builder.index)) {
        is Resolved.Found -> r.card to null
        is Resolved.Unknown -> null to ("No card “${r.text}”." + if (r.suggestions.isNotEmpty()) " Closest: ${r.suggestions.joinToString()}." else "")
    }

    private suspend fun state(cardWord: String?, deckId: String?): MetaAnswer {
        val fx = h.effects
        if (!fx.loaded) fx.reloadNow()
        if (cardWord != null) {
            val (card, why) = card(cardWord)
            card ?: return fail(why.orEmpty())
            return ok(fx.describe(card), "Read ${card.name}'s written effect")
        }
        if (deckId != null) {
            val (name, codes) = if (deckId.equals("open", ignoreCase = true)) {
                h.builder.deckName to (h.builder.deck.main + h.builder.deck.extra).map { it.value }
            } else {
                val d = h.deps.deckRepository.byId(deckId)?.entry ?: return fail("No deck $deckId: list_decks names them.")
                d.name to (d.deck.main + d.deck.extra).map { it.value }
            }
            return ok(fx.describeDeck(name, codes), "Read $name's written effects")
        }
        return ok(fx.describe(), "Read the library of written effects")
    }

    private suspend fun check(cardWord: String?): MetaAnswer {
        val (card, why) = card(cardWord ?: return fail("fx_check needs a card."))
        card ?: return fail(why.orEmpty())
        val code = card.id.value
        val fx = h.effects
        if (!File(fx.dir, FxPaths.js(code)).isFile) {
            return fail("There is no lib/effects/$code.js for ${card.name}: write it in a world with world_write, then fx_check it.")
        }
        val c = fx.compile(code, WorldEvent.AI)
        // In an effects session, the rounds since the card began (or since its last check) are what it cost (D.md §3.1).
        h.ai.session?.takeIf { it.mode == AiSession.MODE_EFFECTS }?.let { s -> fx.checked(code, s.id, h.ai.spent) }
        val words = fx.words(code)
        val text = buildString {
            append(c.words)
            if (words.isNotEmpty()) {
                append("\n\nRead back in words (compare with the printed text):")
                words.forEach { append("\n- ").append(it.toString()) }
                append("\nPrinted text: ").append(card.description.ifBlank { "(none)" })
            }
        }
        val broken = c.entry.compileError != null || c.entry.report?.broken == true
        return MetaAnswer(text, "Checked ${card.name}'s written effect: ${c.entry.status.words}", isError = broken)
    }
}
