package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One version of a deck (Phase G, G.8; the red team's L1): the deck's cards as they were saved under one print
 * ([Ledger.fingerprint] by card, the Side Deck apart), when, and the version it came from. Kept one immutable file a
 * version, `<data>/deckversions/<deck>/<print>.json` ([DeckVersions.path]) — a file never changes once written, so
 * newer-wins sync is safe on it — synced, backed up and deleted with the deck.
 *
 * A version is what a game, a Shootout trial or a goldfish run was played at, so results before and after a change can
 * be told apart: each of those carries its deck's print (recorded since Phase G's first release). A record with none, or
 * with a print no version holds, reads as an unknown version.
 */
@Serializable
data class DeckVersion(
    val deckId: String = "",
    /** The Main and Extra Decks' print by card ([Ledger.fingerprint]); the file's name. */
    val print: String = "",
    /** The deck's name when this version was saved. */
    val name: String = "",
    /** When it was first saved at this print (ms). */
    val at: Long = 0L,
    val main: List<Int> = emptyList(),
    val extra: List<Int> = emptyList(),
    val side: List<Int> = emptyList(),
    /** The print it was changed from: the deck's newest version before it, or for a duplicate's first, its source's. */
    val parent: String? = null,
    /** For a duplicate's first version: the deck it was duplicated from. */
    val parentDeck: String? = null,
    /** The person's own word for it ("before the Droll cut"); blank for none. */
    val label: String = "",
    val version: Int = VERSION,
) {
    val deck: Deck get() = Deck(main = main.map(::CardId), extra = extra.map(::CardId), side = side.map(::CardId))

    companion object {
        const val VERSION = 1
    }
}

/** [DeckVersion] to and from its file. Reading forgives: unknown keys skipped, a broken file null. */
object DeckVersionCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = false }

    fun encode(v: DeckVersion): String = json.encodeToString(DeckVersion.serializer(), v)

    fun decode(text: String): DeckVersion? = runCatching { json.decodeFromString(DeckVersion.serializer(), text) }.getOrNull()?.takeIf { it.print.isNotBlank() }
}

/** The versions of a deck: where they live, a new one, their numbers, what changed between two. */
object DeckVersions {
    /** The folder under `<data>/`: top level, so neither sync nor a restore mistakes a version for a deck (`decks/`). */
    const val FOLDER = "deckversions"

    fun dir(deckId: String): String = "$FOLDER/${safe(deckId)}"

    fun path(deckId: String, print: String): String = "${dir(deckId)}/${safe(print)}.json"

    /** A name a file may carry: letters, digits, '-' and '_' only, so no id can reach outside its folder. */
    private fun safe(s: String): String = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "deck" }

    /** [deck]'s print, by card, as every record of Phase G carries it. */
    fun print(deck: Deck, cards: ((CardId) -> Card?)?): String = Ledger.fingerprint(deck, cards)

    /**
     * The version [deck] saved under [deckId] makes, or null when [existing] already holds its print (a save that changed
     * only the order, the Side Deck or the groups makes none). Its parent is the newest of [existing]; for a deck with no
     * version yet, [parentDeck]/[parentPrint] name a duplicate's source.
     */
    fun next(
        existing: List<DeckVersion>,
        deckId: String,
        deck: Deck,
        name: String,
        at: Long,
        cards: ((CardId) -> Card?)?,
        parentDeck: String? = null,
        parentPrint: String? = null,
    ): DeckVersion? {
        val print = print(deck, cards)
        if (existing.any { it.print == print }) return null
        val newest = existing.maxByOrNull { it.at }
        return DeckVersion(
            deckId = deckId,
            print = print,
            name = name,
            at = at,
            main = deck.main.map { it.value },
            extra = deck.extra.map { it.value },
            side = deck.side.map { it.value },
            parent = newest?.print ?: parentPrint,
            parentDeck = if (newest == null) parentDeck else null,
        )
    }

    /** Oldest first, numbered from 1: what the person calls "v3". */
    fun numbered(versions: List<DeckVersion>): List<Pair<Int, DeckVersion>> =
        versions.sortedWith(compareBy<DeckVersion> { it.at }.thenBy { it.print }).mapIndexed { i, v -> (i + 1) to v }

    /** "v3", or "an unknown version" for a print no version holds (a record from before versions were kept). */
    fun nameOf(print: String?, versions: List<DeckVersion>): String {
        if (print == null) return UNKNOWN
        val n = numbered(versions).firstOrNull { it.second.print == print }?.first ?: return UNKNOWN
        return "v$n"
    }

    const val UNKNOWN = "an unknown version"

    /** One card's change between two versions: [section], [card] by its canonical passcode, and copies (+ in, − out). */
    data class Change(val section: DeckSection, val card: CardId, val delta: Int)

    /** Every change by card from [from] to [to], Main Deck first, the most copies first. */
    fun changes(from: Deck, to: Deck, cards: ((CardId) -> Card?)?): List<Change> {
        val read: (CardId) -> Card? = cards ?: { null }
        return DeckSection.entries.flatMap { s ->
            val a = CardIdentity.canonicalised(from[s], read).groupingBy { it }.eachCount()
            val b = CardIdentity.canonicalised(to[s], read).groupingBy { it }.eachCount()
            (a.keys + b.keys).mapNotNull { id -> ((b[id] ?: 0) - (a[id] ?: 0)).takeIf { it != 0 }?.let { Change(s, id, it) } }
                .sortedWith(compareByDescending<Change> { kotlin.math.abs(it.delta) }.thenBy { it.card.value })
        }
    }

    /** "+1 Ash Blossom & Joyous Spring · −2 Droll & Lock Bird · Side +1 Dark Ruler No More"; "no change by card" for none. */
    fun words(changes: List<Change>, name: (CardId) -> String, most: Int = Int.MAX_VALUE): String {
        if (changes.isEmpty()) return "no change by card"
        val shown = changes.take(most).joinToString(" · ") { c ->
            val where = if (c.section == DeckSection.MAIN) "" else "${c.section.displayName} "
            "$where${if (c.delta > 0) "+" else "−"}${kotlin.math.abs(c.delta)} ${name(c.card)}"
        }
        return if (changes.size > most) "$shown · and ${changes.size - most} more" else shown
    }

    /**
     * The decks a deck descends from by duplication, nearest first, each with the print it was duplicated at: [of] finds a
     * deck's versions. A cycle (never written, but a sync could meet one) stops.
     */
    fun lineage(deckId: String, of: (String) -> List<DeckVersion>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>().apply { add(deckId) }
        var at = deckId
        while (true) {
            val first = of(at).minByOrNull { it.at } ?: break
            val parent = first.parentDeck ?: break
            val print = first.parent ?: break
            if (!seen.add(parent)) break
            out += parent to print
            at = parent
        }
        return out
    }
}
