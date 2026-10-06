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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardCheck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Goldfish
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishSetup
import java.io.File

/**
 * Effects as code, Ai's hands (Phase D step 2, D.md §3.6): `fx_state` reads the library, `fx_check` compiles and checks one
 * card (and, in an effects session, keeps what the card cost), `fx_request` offers cards to write — a request card in the
 * chat, never an ask: only the person's Write puts cards on the asked list — and `fx_target` names the goldfish's targets
 * (Phase D step 4; the goldfish itself is the `goldfish` instrument). A handler group of its own, as F2's pattern asks.
 */
internal class AiEffects(private val h: NeueHolders) {
    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "fx_state" -> state(ToolArgs.string(i, "card"), ToolArgs.string(i, "deck_id"))
        "fx_check" -> check(ToolArgs.string(i, "card"))
        "fx_request" -> request(ToolArgs.strings(i, "cards"), ToolArgs.string(i, "deck_id"), ToolArgs.string(i, "scope"), ToolArgs.string(i, "name"))
        "fx_target" -> target(ToolArgs.string(i, "deck_id"), ToolArgs.string(i, "name"), i["all"], ToolArgs.string(i, "remove"))
        else -> null
    }

    /**
     * `fx_target` (Phase D step 4, D.md §5.2): names an end board for a deck's goldfish, kept in `goldfish/<deck>.json` as
     * Ai's (`by: "ai"`, the pane says whose it is); takes away one of Ai's own; or lists the deck's targets. The person's
     * targets are never changed or removed here.
     */
    private suspend fun target(deckId: String?, name: String?, all: JsonElement?, remove: String?): MetaAnswer {
        val open = deckId == null || deckId.equals("open", ignoreCase = true)
        val id = (if (open) h.builder.deckId else deckId) ?: return fail("Save the deck first: a goldfish target is kept with its deck.")
        val (deckName, deck, _) = deck(deckId) ?: return fail("No deck $deckId: list_decks names them.")
        val fx = h.effects
        if (!fx.loaded) fx.reloadNow()
        val doc = withContext(Dispatchers.IO) { fx.goldfish(id) }
        val index = h.builder.index
        val nameOf = { c: Int -> index.byId(CardId(c))?.name ?: c.toString() }
        fun whose(t: EndBoard) = if (t.by == EndBoard.AI) "Ai's" else "yours"
        if (remove != null) {
            val t = doc.targets.firstOrNull { it.id == remove } ?: doc.targets.firstOrNull { it.name.equals(remove, ignoreCase = true) }
                ?: return fail("No target “$remove” for $deckName: ${doc.targets.joinToString { it.name }.ifEmpty { "it has none" }}.")
            if (t.by != EndBoard.AI) return fail("“${t.name}” is the person's target: only they take it away.")
            fx.updateGoldfish(id) { GoldfishCodec.dropTarget(it, t.id) }
            return ok("Took away “${t.name}” from $deckName's targets.", "Took away a goldfish target")
        }
        if (name == null && (all == null || all is JsonNull)) {
            if (doc.targets.isEmpty()) return ok("$deckName has no goldfish targets yet: name one with name and all.", "Read $deckName's goldfish targets")
            val text = doc.targets.joinToString("\n") { t -> "- ${t.name} (${whose(t)}, id ${t.id}): ${BoardCheck.words(t, nameOf)}" }
            return ok("$deckName's goldfish targets:\n$text\nRun one with world_tool goldfish {\"target\": \"<its name>\"}.", "Read $deckName's goldfish targets")
        }
        val given = when (all) {
            is JsonPrimitive -> all.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            else -> all
        }
        val same = doc.targets.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
        if (same != null && same.by != EndBoard.AI) return fail("“${same.name}” is the person's target: name yours differently.")
        val now = System.currentTimeMillis()
        val t = runCatching { GoldfishCodec.target(same?.id ?: "ai-$now", name.orEmpty(), id, given, EndBoard.AI, now) }
            .getOrElse { return fail("Not kept: ${it.message}") }
        fx.putTarget(id, t)
        // A target that needs a card the goldfish plays as inert is kept, and said: it is not computable until written.
        val kit = GoldfishKit(fx.trust()) { c -> index.byId(CardId(c)) }
        val check = Goldfish.refusal(GoldfishSetup(GoldfishDeck((deck.main).map { it.value }, deck.extra.map { it.value }), t), kit)
        val text = buildString {
            append("Kept “${t.name}” for $deckName as Ai's target: ${BoardCheck.words(t, nameOf)}.")
            append(" Run it with world_tool goldfish {\"deck\": \"$id\", \"target\": \"${t.name}\"}.")
            if (check != null) append("\nNot computable yet: ${check.why}")
        }
        return ok(text, "Named a goldfish target: ${t.name}")
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
