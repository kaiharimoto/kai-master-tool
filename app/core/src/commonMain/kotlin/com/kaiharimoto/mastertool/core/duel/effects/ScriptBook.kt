package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity

/**
 * The scripts the engine plays by (D.md §2.5): any printing resolves to its card's one script through
 * [CardIdentity.canonical], so an alternate artwork reads the same effects.
 *
 * Built either over every script ([all]: the tests, the table's Shortcut verb, which offers unverified effects too) or
 * over verified effects only ([verified]: the goldfish — "nothing unverified is used by search"). In a verified book a
 * partly verified card keeps only its verified effects; its summoning procedures stay only when [FxTag.PROC] is
 * verified, and what only restricts it (cannot be Normal Summoned, must first be) always stays, since it never lets a
 * card do more.
 *
 * A script of a newer vocabulary than this build's is never here. Two scripts for one card: the first is kept.
 */
class ScriptBook private constructor(
    private val scripts: Map<Int, CardScript>,
    private val canonFrom: (Int) -> Int,
    /** Built by [verified]: only verified effects and procedures are here. */
    val verifiedOnly: Boolean,
    /**
     * Built by [trusted] (Phase D step 4, kai's decision: no step 3): only the scripts `FxTrust` lets the goldfish use —
     * every one that compiles and passes `FxCheck`, open warnings and all; a broken, unsupported or missing one is not here.
     */
    val trustedOnly: Boolean = false,
) {
    /** The canonical passcode of any printing. */
    fun canonical(code: Int): Int = canon(code)

    /**
     * [canonFrom], remembered a passcode at a time (a map replaced whole on a miss, so the goldfish's workers share it
     * safely): every trigger check asks it of every card on the table, and the pool's identity look-up is not free.
     */
    private fun canon(code: Int): Int {
        val now = canons
        now[code]?.let { return it }
        val c = canonFrom(code)
        canons = HashMap<Int, Int>(now.size * 2 + 4).apply { putAll(now); put(code, c) }
        return c
    }

    @kotlin.concurrent.Volatile
    private var canons: Map<Int, Int> = emptyMap()

    /** [code]'s script, by any printing, or null when the card has none. */
    fun script(code: Int): CardScript? = scripts[canon(code)]

    fun has(code: Int): Boolean = script(code) != null

    /** [code]'s effect [id], when the book holds it. */
    fun effect(code: Int, id: String): Effect? = script(code)?.effect(id)

    /** Every card the book holds a script for, as canonical passcodes. */
    val cards: Set<Int> get() = scripts.keys

    /**
     * Each card's effects (by id) and procedures (by index) that hold a word this build cannot read or nest too deep
     * ([FxWalk.unread]), worked out once a book: a script never changes, and walking every effect again on every move was
     * a tenth of the engine's time (the red team's profile).
     */
    private val unreadEffects: Map<Int, Set<String>> by lazy {
        scripts.mapValues { (_, s) -> s.effects.filter(FxWalk::unread).map { it.id }.toSet() }.filterValues { it.isNotEmpty() }
    }
    private val unreadProcs: Map<Int, Set<Int>> by lazy {
        scripts.mapValues { (_, s) -> s.summon?.procs.orEmpty().withIndex().filter { FxWalk.unread(it.value) }.map { it.index }.toSet() }.filterValues { it.isNotEmpty() }
    }

    /** Whether [code]'s effect [id] cannot be read by this build ([FxWalk.unread]): never offered, never used. */
    fun unread(code: Int, id: String): Boolean = unreadEffects[canon(code)]?.contains(id) == true

    /** Whether [code]'s summoning procedure [index] cannot be read by this build. */
    fun unreadProc(code: Int, index: Int): Boolean = unreadProcs[canon(code)]?.contains(index) == true

    /** The cards whose scripts hold a trigger effect, as canonical passcodes: the only ones an event can set off. */
    val triggers: Set<Int> by lazy {
        scripts.filterValues { s -> s.effects.any { it.kind == Kind.TRIGGER } }.keys
    }

    /**
     * [code]'s script's hash ([FxCodec.hash]), worked out once a card: every tag carries it, and hashing a script is a
     * SHA-256 of its JSON. A map replaced whole on a miss, so the goldfish's workers share it safely. Empty without one.
     */
    fun hash(code: Int): String {
        val card = canon(code)
        val now = hashes
        now[card]?.let { return it }
        val h = scripts[card]?.let(FxCodec::hash) ?: ""
        hashes = HashMap<Int, String>(now.size * 2 + 4).apply { putAll(now); put(card, h) }
        return h
    }

    @kotlin.concurrent.Volatile
    private var hashes: Map<Int, String> = emptyMap()

    val size: Int get() = scripts.size

    companion object {
        val EMPTY = ScriptBook(emptyMap(), { it }, verifiedOnly = false)

        /** Every script, every effect: [canonical] resolves a printing to its card (the identity when not given). */
        fun all(scripts: Iterable<CardScript>, canonical: (Int) -> Int = { it }): ScriptBook =
            ScriptBook(index(scripts, canonical), canonical, verifiedOnly = false)

        /** Every script, any printing resolved through the pool. */
        fun over(scripts: Iterable<CardScript>, cards: (CardId) -> Card?): ScriptBook =
            all(scripts) { code: Int -> CardIdentity.canonical(CardId(code), cards).value }

        /** The canonical passcode of any printing, through the pool. */
        fun canonical(cards: (CardId) -> Card?): (Int) -> Int = { code -> CardIdentity.canonical(CardId(code), cards).value }

        /**
         * Verified effects only: [verified] is each card's verified effect ids, [FxTag.PROC] for its procedures. A card
         * with nothing verified is not in the book at all: unknown to the goldfish, inert (§5.5).
         */
        fun verified(scripts: Iterable<CardScript>, verified: Map<Int, Set<String>>, canonical: (Int) -> Int = { it }): ScriptBook {
            val ok = verified.mapKeys { canonical(it.key) }
            val kept = index(scripts, canonical).mapNotNull { (card, s) ->
                val ids = ok[card].orEmpty()
                val effects = s.effects.filter { it.id in ids }
                val procs = FxTag.PROC in ids
                if (effects.isEmpty() && !procs) return@mapNotNull null
                card to s.copy(effects = effects, summon = s.summon?.let { r -> if (procs) r else r.copy(procs = emptyList()) })
            }.toMap()
            return ScriptBook(kept, canonical, verifiedOnly = true)
        }

        /**
         * The goldfish's book (D.md §11, `FxTrust`): exactly the [scripts] it trusts, whole — each card's every effect and
         * procedure. A card left out is unknown to the search and inert (§5.5).
         */
        fun trusted(scripts: Iterable<CardScript>, canonical: (Int) -> Int = { it }): ScriptBook =
            ScriptBook(index(scripts, canonical), canonical, verifiedOnly = false, trustedOnly = true)

        private fun index(scripts: Iterable<CardScript>, canonical: (Int) -> Int): Map<Int, CardScript> {
            val out = LinkedHashMap<Int, CardScript>()
            scripts.forEach { s ->
                if (s.vocab > FxVocab.VERSION) return@forEach
                val card = canonical(s.card)
                if (card !in out) out[card] = s
            }
            return out
        }
    }
}
