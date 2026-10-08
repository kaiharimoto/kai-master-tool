package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.mapper.MapperPaths
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.world.WorldHost
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The library of written effects (Phase D step 2, `docs/phases/D.md` §3.3, §6): one per person, at `<data>/effects/`,
 * keyed by canonical passcode and mounted at `lib/effects/` in every world. Written once, used by every deck that holds the
 * card, in any printing (`CardIdentity`). What is here is pure — the paths, the person's review, a card's status, what to
 * compile again and the book the engine reads; the neue holder `Effects` does the reading and writing.
 */

/** Where the library's files live, and which of them may be written by whom (D.md §6, §7). */
object FxPaths {
    /** `<data>/effects/`: the sources, the compiled scripts, the person's reviews. Synced, newer wins; backed up. */
    const val FOLDER = "effects"

    /** `<data>/fxcache/`: verdicts and test runs, recomputed on each device — never synced, never backed up. */
    const val CACHE = "fxcache"

    /** Where every world sees the library. */
    const val MOUNT = "lib/effects/"

    /** At most this many scripts in a library (D.md §7). */
    const val MOST_SCRIPTS = 2_000

    private val SOURCE = Regex("([1-9][0-9]{0,9})\\.js")
    private val COMPILED = Regex("([1-9][0-9]{0,9})\\.json")
    private val REVIEW = Regex("([1-9][0-9]{0,9})\\.review\\.json")
    private val HELPER = Regex("_[A-Za-z0-9][A-Za-z0-9_-]{0,47}\\.js")

    fun js(card: Int): String = "$card.js"
    fun json(card: Int): String = "$card.json"
    fun review(card: Int): String = "$card.review.json"

    /** The card a source file is for ("900000001.js"), or null. */
    fun sourceOf(name: String): Int? = SOURCE.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    /** The card a compiled file is for ("900000001.json"), or null. */
    fun compiledOf(name: String): Int? = COMPILED.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    /** The card a review is for ("900000001.review.json"), or null. */
    fun reviewOf(name: String): Int? = REVIEW.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    /** A shared helper (`_branded.js`): loaded with `ygo.use`, compiled into no card of its own. */
    fun helper(name: String): Boolean = HELPER.matches(name)

    /**
     * What a world may write under [MOUNT] — by Ai's `world_write` or the person's editor: a card's source or a helper,
     * nothing else. A compiled script, a review (the person's acceptances) and the asked list are the app's own: a script
     * that wrote its own "accepted" would vouch for itself (D.md §7). [rel] is the path under the mount.
     */
    fun writable(rel: String): Boolean = '/' !in rel && (sourceOf(rel) != null || helper(rel))

    /** What a world may read or list under [MOUNT]: sources, helpers and the compiled scripts (read-only). */
    fun readable(rel: String): Boolean = writable(rel) || ('/' !in rel && compiledOf(rel) != null)

    /** [path] under the mount ("lib/effects/900000001.js" → "900000001.js"), or null when it is not there. */
    fun mounted(path: String): String? = path.takeIf { it.startsWith(MOUNT) }?.removePrefix(MOUNT)?.takeIf { it.isNotEmpty() }

    /**
     * What may arrive in the library folder from another device or a backup ([rel] under `effects/`): sources, helpers,
     * compiled scripts, reviews, and the files later steps keep there (`asked.json`, `played.json`, `decks/`, `goldfish/`,
     * and the mapper's `mapper/<deck>/` but never its `train/`). Nothing else.
     */
    fun syncs(rel: String): Boolean {
        if (rel.endsWith(".tmp")) return false
        val parts = rel.split('/')
        return when (parts.size) {
            1 -> sourceOf(rel) != null || compiledOf(rel) != null || reviewOf(rel) != null || helper(rel) || rel == ASKED || rel == PLAYED
            2 -> parts[0] in setOf("decks", "goldfish") && parts[1].endsWith(".json") && !parts[1].startsWith(".")
            3 -> MapperPaths.syncs(rel)
            else -> false
        }
    }

    /** The asked list (`FxAsks`, a later agent's): kept here so it travels with the library. */
    const val ASKED = "asked.json"

    /** The "played by you" marks (`FxPlayed`, Phase D step 4): synced newer wins, backed up, with the library. */
    const val PLAYED = "played.json"

    /** A deck's goldfish targets and kept results (`GoldfishDoc`), under `goldfish/`: synced, backed up, deleted with the deck. */
    const val GOLDFISH = "goldfish"
}

/**
 * The person's review of a card's script (`<data>/effects/<passcode>.review.json`): the warnings they accepted, each with
 * why (D.md §3.5). **The person's alone**: written only by the app on the person's click, never through a world, and core
 * refuses an acceptance Ai makes ([FxReviews.accept]). Versioned, read forgivingly.
 */
@Serializable
data class FxReview(
    val version: Int = VERSION,
    val card: Int,
    val accepted: List<Accepted> = emptyList(),
) {
    @Serializable
    data class Accepted(
        /** The finding's [FxFinding.key]: "lint-opt-copy@e2". */
        val key: String,
        val why: String,
        val at: Long = 0L,
    )

    val keys: Set<String> get() = accepted.map { it.key }.toSet()

    companion object {
        const val VERSION = 1
    }
}

object FxReviews {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(r: FxReview): String = json.encodeToString(FxReview.serializer(), r)

    /** A review as stored, or null when it is unreadable; a newer version's is read for what this build knows. */
    fun decode(text: String?): FxReview? = text?.let { runCatching { json.decodeFromString(FxReview.serializer(), it) }.getOrNull() }

    /** Who acts: only [PERSON] may accept a warning (D.md §7, as `AiSettings.GUARDS` keeps safeguards the person's). */
    const val PERSON = "person"
    const val AI = "ai"

    /**
     * [r] with the warning [key] accepted because [why], by [by]: refused (null) when Ai asks, or no reason is given. An
     * acceptance of a key already there replaces its reason.
     */
    fun accept(r: FxReview, key: String, why: String, by: String, at: Long): FxReview? {
        if (by != PERSON || why.isBlank() || key.isBlank()) return null
        return r.copy(accepted = r.accepted.filterNot { it.key == key } + FxReview.Accepted(key, why.trim().take(500), at))
    }

    /** [r] with [key] no longer accepted (the person's undo). */
    fun withdraw(r: FxReview, key: String): FxReview = r.copy(accepted = r.accepted.filterNot { it.key == key })
}

/** A card's status in the library (D.md §4.4); step 2 knows no tests, so nothing is [VERIFIED] or [FAILING] yet. */
@Serializable
enum class FxStatus {
    VERIFIED, UNTESTED, FAILING, WARNED, BROKEN, UNSUPPORTED, MISSING, NONE;

    /** Usable at the table (Shortcut is offered on any status but [BROKEN]; a missing script offers nothing). */
    val usable: Boolean get() = this != BROKEN && this != MISSING && this != NONE

    val words: String
        get() = when (this) {
            VERIFIED -> "verified"
            UNTESTED -> "written, not yet tested"
            FAILING -> "failing its tests"
            WARNED -> "written, with warnings open"
            BROKEN -> "broken: it does not compile or check"
            UNSUPPORTED -> "written, but nothing in it can be played yet"
            MISSING -> "no effect written"
            NONE -> "a Normal Monster: no effect to write"
        }
}

/**
 * One card of the library, as the holder loaded it: [sourceHash] of its `.js` (null without one), what its `.json` read as
 * ([compiled]: null without one), why its last compile failed ([compileError]), what the pass found ([report]) and the
 * person's [review].
 */
data class FxEntry(
    val card: Int,
    val sourceHash: String? = null,
    val compiled: FxRead? = null,
    val compileError: String? = null,
    val report: FxReport? = null,
    val review: FxReview? = null,
) {
    val script: CardScript? get() = (compiled as? FxRead.Script)?.script

    /** A newer build's script: kept as it is, never compiled over, never played here. */
    val newer: Boolean get() = compiled is FxRead.Newer

    val status: FxStatus get() = FxShelf.status(this)

    /** The warnings still open (not accepted by the person). */
    val open: List<FxFinding> get() = report?.open(review?.keys.orEmpty()).orEmpty()
}

/** The library's arithmetic: what to compile, each card's status, the book the engine and the table read. */
object FxShelf {
    /**
     * Whether a card's source must be compiled (again): it has one, and its compiled script is missing, broken, or was
     * built from another source. **Never** over a newer build's script ([FxRead.Newer]): its source may use words this
     * build does not have, and compiling it here would write an older script over a newer one (`OldDataTest`).
     */
    fun needsCompile(sourceHash: String?, compiled: FxRead?): Boolean = when {
        sourceHash == null -> false
        compiled == null -> true
        compiled is FxRead.Newer -> false
        compiled is FxRead.Bad -> true
        compiled is FxRead.Script -> compiled.script.source != sourceHash
        else -> true
    }

    fun status(e: FxEntry): FxStatus {
        val s = e.script
        return when {
            e.compiled == null && e.sourceHash == null -> FxStatus.MISSING
            e.newer -> FxStatus.BROKEN
            s == null || e.compileError != null -> FxStatus.BROKEN
            e.report?.broken == true -> FxStatus.BROKEN
            s.effects.isEmpty() && s.summon?.procs.isNullOrEmpty() && s.unsupported.isNotEmpty() -> FxStatus.UNSUPPORTED
            e.open.isNotEmpty() -> FxStatus.WARNED
            else -> FxStatus.UNTESTED
        }
    }

    /** A card with no script: a Normal Monster is [FxStatus.NONE] (nothing to write), any other [FxStatus.MISSING]. */
    fun statusWithout(card: Card?): FxStatus = if (card != null && FxFacts.of(card).normal) FxStatus.NONE else FxStatus.MISSING

    /**
     * The book the engine and the table read (D.md §2.5): every script that compiled and checked without an error —
     * unverified ones too, which the table marks so; a broken one is never here. Any printing resolves through
     * [canonical], so an alternate artwork reads its card's script.
     */
    fun book(entries: Iterable<FxEntry>, canonical: (Int) -> Int = { it }): ScriptBook =
        ScriptBook.all(entries.filter { it.status != FxStatus.BROKEN }.mapNotNull { it.script }, canonical)

    /** [entries] over the cap: the cards kept are the first [FxPaths.MOST_SCRIPTS] by passcode, the rest named. */
    fun capped(cards: Collection<Int>): Pair<List<Int>, List<Int>> {
        val sorted = cards.sorted()
        return sorted.take(FxPaths.MOST_SCRIPTS) to sorted.drop(FxPaths.MOST_SCRIPTS)
    }

    /**
     * A compiled script made whole from what the app knows (`FxCompile`, after the run): the pool's name when the script
     * gives none, the printed text's hash ([CardScript.text]) and the source's ([CardScript.source]), and — for a Spell's or
     * a Trap's own activation that says nowhere it is used from — where its kind is activated from (a Spell from the hand
     * or its zone, a Field Spell from the hand or the Field Zone, a Trap from its zone once Set).
     */
    fun fill(script: CardScript, card: Card?, source: String): CardScript {
        val facts = card?.let(FxFacts::of)
        val effects = script.effects.map { e ->
            if (e.kind != Kind.ACTIVATION || e.from.isNotEmpty() || facts == null) e
            else when {
                facts.type == CardType.TRAP -> e.copy(from = setOf(Where.SPELL_ZONE))
                facts.type == CardType.SPELL && facts.isSpellSub("Field") -> e.copy(from = setOf(Where.HAND, Where.FIELD_ZONE))
                facts.type == CardType.SPELL -> e.copy(from = setOf(Where.HAND, Where.SPELL_ZONE))
                else -> e
            }
        }
        return script.copy(
            name = script.name.ifBlank { card?.name.orEmpty() },
            text = card?.let { FxCodec.textOf(it.description) } ?: script.text,
            source = FxCodec.short(source),
            effects = effects,
        )
    }
}

/**
 * The app as a script being compiled sees it (`FxCompile`): the pool, read-only, and the library's helpers for `ygo.use`
 * (`lib/effects/_x.js`) — no decks, no duel, nothing else. [files] answers a world path.
 */
class FxHost(
    private val byId: (Int) -> Card?,
    private val byName: (String) -> Card? = { null },
    private val files: (String) -> String? = { null },
) : WorldHost {
    override fun cardById(id: Int): Card? = byId(id)
    override fun cardNamed(name: String): Card? = byName(name)
    override fun search(query: String, limit: Int): List<Card> = listOfNotNull(byName(query)).take(limit)
    override fun deck(id: String?): DeckEntry? = null
    override fun decks(): List<DeckEntry> = emptyList()
    override fun file(path: String): String? = files(path)

    companion object {
        /** Over a list of cards (and their alternate artworks): the tests'. */
        fun of(cards: Iterable<Card>, files: (String) -> String? = { null }): FxHost {
            val byId = HashMap<Int, Card>()
            cards.forEach { c -> c.passcodes.forEach { byId[it.value] = c } }
            val byName = cards.associateBy { it.name.lowercase() }
            return FxHost({ byId[it] }, { byName[it.trim().lowercase()] }, files)
        }

        /** The canonical passcode through [byId]: an alternate artwork's card. */
        fun canonical(byId: (Int) -> Card?): (Int) -> Int = { code -> byId(code)?.id?.value ?: code }
    }
}
