package com.kaiharimoto.neue.effects

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboCodec
import com.kaiharimoto.mastertool.core.duel.effects.FxCost
import com.kaiharimoto.mastertool.core.duel.effects.FxFrom
import com.kaiharimoto.mastertool.core.duel.effects.FxOffer
import com.kaiharimoto.mastertool.core.duel.effects.FxOffers
import com.kaiharimoto.mastertool.core.duel.effects.FxRequest
import com.kaiharimoto.mastertool.core.duel.effects.FxReviews
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.platform.Platform
import java.io.File

/*
 * The person's go (Phase D step 2, `docs/phases/D.md` §3.1, §3.6): wherever the ask starts — the card viewer, an inspector,
 * the Effects app, a combo's row, or Write on Ai's request card in the chat — the click puts the cards on the asked list
 * (`Effects.go`, `FxAsks.go`: the person's alone) and starts an effects session (`AiSession.MODE_EFFECTS`, the
 * `effects-author` skill) that writes them where the person watches.
 */

/** The connection's name for what a card cost on it: "anthropic/claude-sonnet-5-5". */
internal fun modelKey(c: AiConnection): String = "${c.provider}/${c.model}"

/** What [cards] would cost to write on the person's connection now: the offer every place shows before its go. */
fun NeueHolders.offerFor(cards: List<Int>, what: String, deck: String?, id: String = newRequestId()): FxOffer {
    val c = ai.prefs.connection
    return effects.offer(id, what, deck, cards, c?.let(::modelKey), c?.let { Prices.of(it.provider, it.model) })
}

fun newRequestId(): String = "fxr-${System.currentTimeMillis().toString(36)}"

/** Why a go cannot start now, in words, or null: Ai is off, or nothing is to write. */
fun NeueHolders.goBlocked(offer: FxOffer): String? = when {
    !neue.prefs.ai.enabled -> "Ai is off: turn it on in Settings to have effects written"
    offer.toWrite.isEmpty() -> "Every card here is already written"
    else -> null
}

/**
 * **The person's go** from [from]: [cards] (any printings) offered, put on the asked list, and written in a new effects
 * session. False when it could not start — Ai off, nothing to write, or no connection yet (the panel opens on its setup).
 */
fun NeueHolders.writeEffects(cards: List<Int>, from: String, what: String, deck: String? = builder.deckId): Boolean =
    go(offerFor(cards, what, deck), from)

/** The person's Write on [offer] (the chat's request card, or any place that showed the offer first). */
fun NeueHolders.go(offer: FxOffer, from: String = FxFrom.CHAT): Boolean {
    goBlocked(offer)?.let {
        neue.note = Note(it)
        return false
    }
    val connection = ai.prefs.connection
    if (connection == null) {
        // The first connection is made in the panel's setup; the cards are asked once there is one to write them.
        ai.setOpen(true)
        return false
    }
    val request = effects.go(FxOffers.request(offer, System.currentTimeMillis()).copy(from = from), FxReviews.PERSON) ?: return false
    ai.setOpen(true)
    ai.newChat(AiSession.MODE_EFFECTS)
    val session = ai.session ?: return false
    effects.begin(session.id, request, ai.spent, modelKey(connection), Prices.of(connection.provider, connection.model))
    ai.send(brief(request, offer))
    return true
}

/** The first message of an effects session: the cards in the request's order, each to write or repair, and the deck. */
internal fun NeueHolders.brief(r: FxRequest, offer: FxOffer): String = buildString {
    val index = builder.index
    fun name(c: Int) = index.byId(CardId(c))?.name ?: c.toString()
    append("Write the effects I asked for (${r.what.ifBlank { "cards" }}, from ${FxFrom.words(r.from)}), in this order:")
    r.cards.forEachIndexed { i, c -> append("\n${i + 1}. ${name(c)} ($c)").append(if (c in offer.repair) " — to repair" else " — to write") }
    if (offer.reused.isNotEmpty()) append("\nAlready written, leave as they are: ").append(offer.reused.joinToString { name(it) }).append('.')
    r.deck?.let { id ->
        val deckName = if (id == builder.deckId) builder.deckName else null
        append("\nThe deck: ").append(deckName?.let { "$it ($id)" } ?: id).append('.')
    }
    append("\nEstimated: ").append(FxCost.words(offer.estimate)).append('.')
    append("\nRead the effects-author skill first. When every card is checked, tell me in a few lines what each one does.")
}

/** [deckId]'s saved combos (`<data>/duel/combos/<deck>.json`), read without starting the duel. */
internal fun combosOf(deckId: String?): List<Combo> {
    deckId ?: return emptyList()
    val f = File(File(Platform.dataDir, "duel"), ComboCodec.path(deckId))
    return runCatching { f.takeIf { it.isFile }?.readText()?.let(ComboCodec::decode)?.combos }.getOrNull().orEmpty()
}
