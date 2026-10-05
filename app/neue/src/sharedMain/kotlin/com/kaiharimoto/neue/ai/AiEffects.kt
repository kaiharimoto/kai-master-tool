package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.neue.NeueHolders
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Effects as code, Ai's hands (Phase D step 2, D.md §3.6): `fx_state` reads the library, `fx_check` compiles and checks one
 * card. A handler group of its own, as F2's pattern asks, not more of `AiHost`; the asking agent's `fx_request`, targets
 * and the goldfish join it here.
 */
internal class AiEffects(private val h: NeueHolders) {
    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "fx_state" -> state(ToolArgs.string(i, "card"), ToolArgs.string(i, "deck_id"))
        "fx_check" -> check(ToolArgs.string(i, "card"))
        else -> null
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
