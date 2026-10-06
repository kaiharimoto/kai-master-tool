package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.sync.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * What the goldfish trusts (Phase D step 4, `docs/phases/D.md` §11, kai: "Don't need step 3"): nothing is ever VERIFIED by
 * a test, so the rule is no longer "verified effects only". A script the goldfish uses is one that compiles and passes
 * `FxCheck` — UNTESTED, or WARNED with warnings the person has not accepted, which every result names card by card. A
 * BROKEN, UNSUPPORTED or MISSING script is inert, as an unknown card is (§5.5). And a card used by Shortcut at the table,
 * kept, is marked "played by you" per card and script hash (`<data>/effects/played.json`): a light confirmation, never a
 * test, and a changed script loses it.
 */

/**
 * The "played by you" marks (`<data>/effects/played.json`, D.md §11): each card used by Shortcut at the table and kept (its
 * group not undone), keyed by its canonical passcode and its script's hash ([FxCodec.hash]) as it was played. Synced newer
 * wins and backed up with the library; versioned, read forgivingly — a later build's keys are skipped.
 */
@Serializable
data class FxPlayed(
    val version: Int = VERSION,
    val marks: List<Mark> = emptyList(),
) {
    /**
     * [card] played with the script whose hash was [script], [uses] times kept (an undo takes one back), the last at [at].
     * [effects]: the effect ids used ("e1", [FxTag.PROC] for a summoning procedure).
     */
    @Serializable
    data class Mark(
        val card: Int,
        val script: String,
        val uses: Int = 1,
        val at: Long = 0L,
        val effects: List<String> = emptyList(),
    )

    /** [card]'s mark for the script hashed [script], when one is kept. */
    fun of(card: Int, script: String): Mark? = marks.firstOrNull { it.card == card && it.script == script && it.uses > 0 }

    companion object {
        const val VERSION = 1
    }
}

/** One use to mark: [card] (canonical), its script's [script] hash, the [effect] used. */
data class FxPlayedUse(val card: Int, val script: String, val effect: String)

object FxPlays {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(p: FxPlayed): String = json.encodeToString(FxPlayed.serializer(), p)

    /** The marks as stored; an unreadable file reads as none (the marks are a light confirmation, never a gate). */
    fun decode(text: String?): FxPlayed =
        text?.let { runCatching { json.decodeFromString(FxPlayed.serializer(), it) }.getOrNull() } ?: FxPlayed()

    /** [p] with each of [uses] kept once more, at [at]: one use of a card counts once however many of its effects it used. */
    fun mark(p: FxPlayed, uses: List<FxPlayedUse>, at: Long): FxPlayed {
        var marks = p.marks
        uses.groupBy { it.card to it.script }.forEach { (key, us) ->
            val (card, script) = key
            if (script.isBlank()) return@forEach
            val old = marks.firstOrNull { it.card == card && it.script == script }
            val effects = ((old?.effects ?: emptyList()) + us.map { it.effect }).distinct().sorted()
            val next = FxPlayed.Mark(card, script, (old?.uses ?: 0) + 1, at, effects)
            marks = marks.filterNot { it.card == card && it.script == script } + next
        }
        return p.copy(marks = marks.sortedWith(compareBy({ it.card }, { it.script })))
    }

    /** [p] with each of [uses] taken back once (its group was undone): a mark at no uses is gone. */
    fun unmark(p: FxPlayed, uses: List<FxPlayedUse>): FxPlayed {
        var marks = p.marks
        uses.map { it.card to it.script }.distinct().forEach { (card, script) ->
            marks = marks.mapNotNull { m -> if (m.card == card && m.script == script) m.copy(uses = m.uses - 1).takeIf { it.uses > 0 } else m }
        }
        return p.copy(marks = marks)
    }

    /**
     * The uses one committed Shortcut made, read off its tags ([FxTag]): each card whose effect was activated or whose
     * procedure was used, with its script's hash as the tag carries it. [code] turns a tag's uid into the card's canonical
     * passcode (null for a token or a card gone). Rule moves (a Normal Summon, the phase) and skipped triggers are no use.
     */
    fun uses(tags: List<FxTag?>, code: (Int) -> Int?): List<FxPlayedUse> =
        tags.filterNotNull()
            .filter { it.part == FxTag.ACTIVATE || it.part == FxTag.PROC }
            .mapNotNull { t -> code(t.uid)?.let { c -> FxPlayedUse(c, t.script, if (t.part == FxTag.PROC) FxTag.PROC else t.effect) } }
            .distinct()
}

/**
 * What the goldfish trusts (D.md §11): built over the library's [entries] (by canonical passcode) and the [played] marks.
 * Pure: the same library gives the same book and the same fingerprint everywhere.
 */
class FxTrust(
    val entries: Map<Int, FxEntry>,
    val played: FxPlayed = FxPlayed(),
    val canonical: (Int) -> Int = { it },
) {
    private fun entry(code: Int): FxEntry? = entries[canonical(code)]

    /** [code]'s status in the library, or null when it has no entry (a Normal Monster or a card never written). */
    fun status(code: Int): FxStatus? = entry(code)?.status

    /** Whether the goldfish uses [code]'s script: it compiles and passes `FxCheck` (UNTESTED or WARNED; VERIFIED too). */
    fun trusted(code: Int): Boolean = status(code) in USED

    /** The warnings on [code]'s script the person has not accepted: named under every result that used it. */
    fun openWarnings(code: Int): List<FxFinding> = entry(code)?.takeIf { trusted(code) }?.open.orEmpty()

    /** [code]'s script's hash ([FxCodec.hash]) as the library holds it now; null without a trusted script. */
    fun hash(code: Int): String? = entry(code)?.takeIf { trusted(code) }?.script?.let(FxCodec::hash)

    /** Whether [code] was played by you with the script it has now: a changed script loses the mark. */
    fun playedByYou(code: Int): Boolean {
        val h = hash(code) ?: return false
        return played.of(canonical(code), h) != null
    }

    /** Every trusted card, by canonical passcode. */
    val cards: Set<Int> by lazy { entries.filterValues { it.status in USED }.keys }

    /** The book the goldfish's engine is handed: exactly the trusted scripts ([ScriptBook.trusted]). */
    val book: ScriptBook by lazy { ScriptBook.trusted(entries.values.filter { it.status in USED }.mapNotNull { it.script }, canonical) }

    /**
     * The library fingerprint of a run over [cards] (D.md §5.6, `Proof.library`): each trusted card among them with its
     * script's hash, sorted, and the engine's version — 12 hex. A changed script, a card newly trusted or no longer, or a
     * new engine moves it, and the number it proved goes stale.
     */
    fun library(cards: Collection<Int>): String {
        val parts = cards.map(canonical).distinct().sorted().mapNotNull { c -> hash(c)?.let { "$c:$it" } }
        return Sha256.hex("engine ${FxVocab.ENGINE}|" + parts.joinToString(",")).take(12)
    }

    companion object {
        /** The statuses whose scripts the goldfish uses (VERIFIED is never set now; kept should a later step set it). */
        val USED: Set<FxStatus> = setOf(FxStatus.UNTESTED, FxStatus.WARNED, FxStatus.VERIFIED)

        /** The statuses that are inert to the goldfish, as an unknown card is (§5.5). */
        val INERT: Set<FxStatus> = setOf(FxStatus.BROKEN, FxStatus.UNSUPPORTED, FxStatus.MISSING, FxStatus.FAILING)

        val EMPTY = FxTrust(emptyMap())
    }
}
