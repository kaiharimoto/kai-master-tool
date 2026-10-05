package com.kaiharimoto.mastertool.core.ai.evidence

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
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
    }
}

object Ledger {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }
    private val serializer = ListSerializer(Proven.serializer())

    fun path(deckId: String): String = "evidence/${AiMemory.safeId(deckId)}.json"

    fun read(text: String?): List<Proven> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun write(list: List<Proven>): String = json.encodeToString(serializer, list)

    /** The deck as a check saw it: its cards, counted, order aside — what odds and studies depend on. */
    fun fingerprint(deck: Deck): String {
        fun part(ids: List<Any>) = ids.map { it.toString() }.groupingBy { it }.eachCount().toSortedMap().entries.joinToString(",") { "${it.key}x${it.value}" }
        val s = "m:" + part(deck.main.map { it.value }) + "|e:" + part(deck.extra.map { it.value }) + "|s:" + part(deck.side.map { it.value })
        // A short, stable hash: FNV-1a over the text.
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

    /** [list] with every entry computed on another deck than [now] marked stale (estimates stay what they are). */
    fun staleAgainst(list: List<Proven>, now: String): List<Proven> = list.map { p ->
        if (p.status == Proven.Status.ESTIMATE || p.proofs.all { it.deck.isEmpty() || it.deck == now }) p
        else if (p.status == Proven.Status.CHECKED) p.copy(status = Proven.Status.STALE)
        else p
    }

    /** The mark an entry wears where the guide is read — by Ai in its prompt, by the person in the guide. */
    fun mark(p: Proven?): String? = when (p?.status) {
        null -> null
        Proven.Status.CHECKED -> "checked by ${p.proofs.map { it.tool }.distinct().joinToString()}"
        Proven.Status.STALE -> "stale: the deck changed since this was computed — check it again before relying on it"
        Proven.Status.CONTRADICTED -> "contradicted: checked again, it no longer holds${p.note.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
        Proven.Status.ESTIMATE -> "estimate, not computed"
    }

    /** The guide's entries with their marks, for the prompt: what Ai reads knows which of its numbers hold. */
    fun annotate(entries: List<String>, list: List<Proven>): List<String> {
        val by = list.associateBy { it.entry }
        return entries.map { e -> mark(by[e])?.let { "$e [$it]" } ?: e }
    }
}
