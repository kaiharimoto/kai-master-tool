package com.kaiharimoto.mastertool.core.ai.evidence

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Where what Ai wrote into a deck's guide came from (1.0.98, the evidence ledger): each entry with a number keeps the
 * check that computed it — the tool, what it was asked, the deck as it stood — so the number can be checked again when
 * the deck changes, and the guide says which of its numbers still hold. Kept beside the guide,
 * `ai/evidence/<deck>.json`; an entry not in it is words, or an estimate said to be one.
 */
@Serializable
data class Proof(
    /** The tool that computed it: hand_odds, calculate, world_tool, world_run. */
    val tool: String,
    /** What it was asked, as JSON. */
    val input: String,
    /** The part of its answer the number was read from. */
    val excerpt: String = "",
    val at: Long = 0,
    /** The deck it was computed on ([fingerprint]); empty when the number does not depend on the deck. */
    val deck: String = "",
    /**
     * The written effects a goldfish number was computed with (Phase D step 4, `FxTrust.library`: the trusted scripts of
     * the deck's cards and the engine's version); empty for every other tool. A changed script makes the number stale.
     */
    val library: String = "",
)

@Serializable
data class Proven(
    /** The guide entry, word for word. */
    val entry: String,
    val proofs: List<Proof> = emptyList(),
    val status: Status = Status.CHECKED,
    /** When it was last checked, and what the check said when it no longer agrees. */
    val checkedAt: Long = 0,
    val note: String = "",
) {
    @Serializable
    enum class Status {
        /** A check computed every number in it, on the deck as it is. */
        CHECKED,

        /** Computed on a deck that has changed since, and not checked again yet. */
        STALE,

        /** Checked again on the deck as it is now, and the number is no longer what it says. */
        CONTRADICTED,

        /** Ai's own estimate, written as one: not computed, never passed off as computed. */
        ESTIMATE,

        /**
         * Someone else's number, read from outside the app — a course chapter, a web page, a video — and written as theirs
         * ("(per <author>)"): what they claim, never a check of ours. Added last so an older build that does not know it
         * reads the rest of the ledger unchanged.
         */
        QUOTED,
    }
}

object Ledger {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }
    private val serializer = ListSerializer(Proven.serializer())

    fun path(deckId: String): String = "evidence/${AiMemory.safeId(deckId)}.json"

    fun read(text: String?): List<Proven> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun write(list: List<Proven>): String = json.encodeToString(serializer, list)

    /**
     * The deck as a check saw it (2026-10, the red team's bug 10): its Main and Extra Deck by card — [cards] resolves a
     * printing to its card (Phase B); without it, by passcode — counted, order aside: what odds and simulations depend on.
     * The Side Deck is left out, so a change to it stales no number of the deck; and an alternate printing is its card.
     */
    fun fingerprint(deck: Deck, cards: ((CardId) -> Card?)? = null): String {
        fun id(c: CardId) = cards?.let { CardIdentity.canonical(c, it) } ?: c
        return hash("v2|m:" + part(deck.main.map { id(it).value }) + "|e:" + part(deck.extra.map { id(it).value }))
    }

    /**
     * The print before 1.1.62 (deal and print 2): every passcode as written, the Side Deck in it. Read beside [fingerprint]
     * wherever a stored print is judged, so nothing made with the deck as it is goes stale on the upgrade.
     */
    fun fingerprintV1(deck: Deck): String =
        hash("m:" + part(deck.main.map { it.value }) + "|e:" + part(deck.extra.map { it.value }) + "|s:" + part(deck.side.map { it.value }))

    /** Both prints of [deck] that stand for it as it is: [fingerprint] and the earlier [fingerprintV1]. */
    fun prints(deck: Deck, cards: ((CardId) -> Card?)? = null): Set<String> = setOf(fingerprint(deck, cards), fingerprintV1(deck))

    private fun part(ids: List<Int>) = ids.map { it.toString() }.groupingBy { it }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}x${it.value}" }

    /** A short, stable hash: FNV-1a over the text. */
    private fun hash(s: String): String {
        var h = 0xcbf29ce484222325uL
        s.forEach { c ->
            h = h xor c.code.toULong()
            h *= 0x100000001b3uL
        }
        return h.toString(16)
    }

    /** [list] with [proven] in place of whatever stood for the same entry, or [replaced] when an entry was rewritten. */
    fun put(list: List<Proven>, proven: Proven, replaced: String? = null): List<Proven> =
        list.filter { it.entry != proven.entry && it.entry != replaced } + proven

    /** [list] kept to the entries the guide still holds. */
    fun prune(list: List<Proven>, entries: List<String>): List<Proven> {
        val held = entries.toHashSet()
        return list.filter { it.entry in held }
    }

    /**
     * [list] with every entry computed on another deck than [now] marked stale (estimates stay what they are) — and, given
     * the deck's [library] of written effects as it is now (`FxTrust.library`), every goldfish number computed with other
     * scripts: a script it used changed, was repaired or was newly written (Phase D step 4).
     */
    fun staleAgainst(list: List<Proven>, now: String, library: String? = null, also: Set<String> = emptySet()): List<Proven> = list.map { p ->
        // [also]: the deck's earlier print ([fingerprintV1]), which stands for it as it is.
        val deckMoved = p.proofs.any { it.deck.isNotEmpty() && it.deck != now && it.deck !in also }
        val libraryMoved = library != null && p.proofs.any { it.library.isNotEmpty() && it.library != library }
        if (p.status == Proven.Status.ESTIMATE || (!deckMoved && !libraryMoved)) p
        else if (p.status == Proven.Status.CHECKED) p.copy(status = Proven.Status.STALE, note = if (libraryMoved && !deckMoved) LIBRARY_MOVED else p.note)
        else p
    }

    /** Why a number went stale though the deck did not change. */
    const val LIBRARY_MOVED = "a written effect it used has changed since"

    /** The mark an entry wears where the guide is read — by Ai in its prompt, by the person in the guide. */
    fun mark(p: Proven?): String? = when (p?.status) {
        null -> null
        Proven.Status.CHECKED -> "checked by ${p.proofs.map { it.tool }.distinct().joinToString()}"
        Proven.Status.STALE -> if (p.note == LIBRARY_MOVED) "stale: $LIBRARY_MOVED — run the goldfish again before relying on it"
        else "stale: the deck changed since this was computed — check it again before relying on it"
        Proven.Status.CONTRADICTED -> "contradicted: checked again, it no longer holds${p.note.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
        Proven.Status.ESTIMATE -> "estimate, not computed"
        Proven.Status.QUOTED -> "quoted from ${p.proofs.map { it.tool }.distinct().joinToString()}, not computed"
    }

    /** The guide's entries with their marks, for the prompt: what Ai reads knows which of its numbers hold. */
    fun annotate(entries: List<String>, list: List<Proven>): List<String> {
        val by = list.associateBy { it.entry }
        return entries.map { e -> mark(by[e])?.let { "$e [$it]" } ?: e }
    }
}
