package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.duel.lounge.LoungeMatch
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Which turn a plan is for. */
enum class Turn(val title: String) {
    FIRST("Going first"),
    SECOND("Going second");

    /** The other player's turn in the same game: you going first is them going second. */
    val theirs: Turn get() = if (this == FIRST) SECOND else FIRST
}

/**
 * One turn's side plan: the cards taken out of the main and extra decks, the
 * cards brought in from the side, one entry per copy, and why.
 */
data class SidePlan(
    val out: List<CardId> = emptyList(),
    val into: List<CardId> = emptyList(),
    val note: String = "",
    /**
     * Which copies go out, where the person picked them on the board (kai, 2026-10: "have that
     * card be the card selected, not the first of that card"): a card's copy by its place among
     * that card's copies in its section, 0 the first. Copies the plan moves beyond these are the
     * first ones not picked ([SidingMarks]), so a plan without picks reads as it always did.
     */
    val outCopies: Map<CardId, List<Int>> = emptyMap(),
    /** The same for the copies brought in from the Side Deck. */
    val inCopies: Map<CardId, List<Int>> = emptyMap(),
) {
    val isEmpty: Boolean get() = out.isEmpty() && into.isEmpty() && note.isBlank()
    val sided: Boolean get() = out.isNotEmpty() || into.isNotEmpty()

    /** In minus out: 0 keeps the deck its size. */
    val balance: Int get() = into.size - out.size

    fun outCount(card: CardId): Int = out.count { it == card }
    fun inCount(card: CardId): Int = into.count { it == card }

    /** One more copy of [card] out: the copy picked, [at], when one was. */
    fun plusOut(card: CardId, at: Int? = null): SidePlan {
        val next = out + card
        return copy(out = next, outCopies = outCopies.picked(card, at).trimmed(next))
    }

    fun plusIn(card: CardId, at: Int? = null): SidePlan {
        val next = into + card
        return copy(into = next, inCopies = inCopies.picked(card, at).trimmed(next))
    }

    /** One copy of [card] back: the copy [at] when it names one, else the last picked beyond the count. */
    fun minusOut(card: CardId, at: Int? = null): SidePlan {
        val next = out.minusOne(card)
        return copy(out = next, outCopies = outCopies.unpicked(card, at).trimmed(next))
    }

    fun minusIn(card: CardId, at: Int? = null): SidePlan {
        val next = into.minusOne(card)
        return copy(into = next, inCopies = inCopies.unpicked(card, at).trimmed(next))
    }

    private fun Map<CardId, List<Int>>.picked(card: CardId, at: Int?): Map<CardId, List<Int>> =
        if (at == null || at < 0) this else this + (card to (this[card].orEmpty() - at + at))

    private fun Map<CardId, List<Int>>.unpicked(card: CardId, at: Int?): Map<CardId, List<Int>> =
        if (at == null) this else this + (card to (this[card].orEmpty() - at))

    /** No card picked more often than the plan moves it, and no empty entries. */
    private fun Map<CardId, List<Int>>.trimmed(moved: List<CardId>): Map<CardId, List<Int>> {
        val counts = moved.groupingBy { it }.eachCount()
        return mapValues { (card, picks) -> picks.take(counts[card] ?: 0) }.filterValues { it.isNotEmpty() }
    }

    private fun List<CardId>.minusOne(card: CardId): List<CardId> {
        val at = lastIndexOf(card)
        return if (at < 0) this else toMutableList().apply { removeAt(at) }
    }
}

/**
 * How a deck sides against one opponent (kai, 1.0.35: "siding patterns, can use
 * other decks in the deck web as a matchup and also write notes"): the
 * opponent — a deck of the same web by its id, or only a name — a note on the
 * matchup, and a plan for each turn.
 */
data class Matchup(
    val id: String,
    val name: String,
    /**
     * The deck this is against, when it is one: a deck of the web, or — for a deck sided
     * on its own (1.0.42) — any deck of the library, linked when its list is to hand.
     */
    val deckId: String? = null,
    val note: String = "",
    val first: SidePlan = SidePlan(),
    val second: SidePlan = SidePlan(),
    /**
     * The three cards the opponent is known by (1.0.42, kai: "create opponent decks by
     * choosing a name and 3 main cards"), for a matchup with no decklist to show its faces.
     */
    val covers: List<CardId> = emptyList(),
) {
    fun plan(turn: Turn): SidePlan = if (turn == Turn.FIRST) first else second

    fun withPlan(turn: Turn, plan: SidePlan): Matchup = if (turn == Turn.FIRST) copy(first = plan) else copy(second = plan)

    /**
     * The list's mark for one turn: `■` sided with its reason written, `□` sided,
     * `·` not yet.
     */
    fun mark(turn: Turn): Char {
        val p = plan(turn)
        return when {
            p.sided && p.note.isNotBlank() -> '■'
            p.sided -> '□'
            else -> '·'
        }
    }
}

/** Every matchup a deck has a plan for, in the order they were made. */
data class DeckSiding(val matchups: List<Matchup> = emptyList()) {
    val isEmpty: Boolean get() = matchups.isEmpty()

    fun byId(id: String?): Matchup? = id?.let { wanted -> matchups.firstOrNull { it.id == wanted } }

    /**
     * The matchup against [deckId], or — for a plan written before the decks
     * shared a web (a legacy file, a deck opened on its own) — against a deck
     * called [name].
     */
    fun against(deckId: String?, name: String?): Matchup? =
        deckId?.let { id -> matchups.firstOrNull { it.deckId == id } }
            ?: name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { n ->
                matchups.firstOrNull { it.deckId == null && it.name.trim().lowercase() == n }
            }

    /** [matchup] in place of the one with its id, else last. */
    fun put(matchup: Matchup): DeckSiding =
        if (matchups.any { it.id == matchup.id }) copy(matchups = matchups.map { if (it.id == matchup.id) matchup else it })
        else copy(matchups = matchups + matchup)

    fun remove(id: String): DeckSiding = copy(matchups = matchups.filterNot { it.id == id })

    /** Every web deck id replaced through [ids], and a link to a deck [ids] does not know kept as a name only. */
    fun remapped(ids: Map<String, String>): DeckSiding =
        copy(matchups = matchups.map { m -> m.copy(deckId = m.deckId?.let { ids[it] }) })

    companion object {
        val EMPTY = DeckSiding()
    }
}

/**
 * Siding lives in the `#ydkx-extended` payload under its own key, `siding`,
 * like the groups under theirs: reading tolerates any shape by dropping what it
 * cannot read, and writing replaces exactly that key and copies every other
 * verbatim.
 *
 * ```json
 * "siding": { "matchups": [ {
 *   "id": "m-1a2b", "name": "Yubel", "deck": "<web deck id>", "note": "…",
 *   "first":  { "out": [14558127, 14558127], "in": [9822220], "note": "…", "outCopies": { "14558127": [2, 0] } },
 *   "second": { "out": [], "in": [], "note": "" },
 *   "covers": [89631139, 14558127, 23434538]
 * } ] }
 * ```
 *
 * The legacy tool's `sidingPatterns` (the reason the payload is kept opaque)
 * is read once, when a deck has no `siding` of its own, and never written:
 * CLAUDE.md asked for siding redesigned from scratch, not built on it, and a
 * file the legacy tool reads again must still hold what it wrote.
 */
object SidingCodec {
    const val KEY = "siding"

    /** How many cards an opponent is known by. */
    const val COVERS = 3
    private const val LEGACY = "sidingPatterns"

    fun read(extended: JsonObject?): DeckSiding {
        val node = extended?.get(KEY) as? JsonObject ?: return readLegacy(extended?.get(LEGACY) as? JsonObject)
        val list = node["matchups"] as? JsonArray ?: return DeckSiding.EMPTY
        return DeckSiding(
            list.mapIndexedNotNull { i, element ->
                val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                Matchup(
                    id = obj.string("id") ?: "m-$i",
                    name = obj.string("name") ?: "Opponent",
                    deckId = obj.string("deck"),
                    note = obj.string("note").orEmpty(),
                    first = plan(obj["first"]),
                    second = plan(obj["second"]),
                    covers = ids(obj["covers"]).distinct().take(COVERS),
                )
            }.distinctBy { it.id },
        )
    }

    /** [extended] with the siding key rewritten, or removed when there is no siding. */
    fun write(extended: JsonObject?, siding: DeckSiding): JsonObject? {
        val others = extended?.filterKeys { it != KEY }.orEmpty()
        if (siding.isEmpty) return if (others.isEmpty()) null else JsonObject(others)
        return JsonObject(others + (KEY to node(siding)))
    }

    /** The siding key's value alone, for a payload kept elsewhere (the builder's). */
    fun node(siding: DeckSiding): JsonElement = buildJsonObject {
        put("matchups", buildJsonArray {
            siding.matchups.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id)
                    put("name", m.name)
                    m.deckId?.let { put("deck", it) }
                    if (m.note.isNotBlank()) put("note", m.note)
                    put("first", planNode(m.first))
                    put("second", planNode(m.second))
                    if (m.covers.isNotEmpty()) put("covers", buildJsonArray { m.covers.forEach { add(JsonPrimitive(it.value)) } })
                })
            }
        })
    }

    /** [extended] with every web deck id its siding names replaced through [ids] (a `.ydkw` opened with new ids). */
    fun remap(extended: JsonObject?, ids: Map<String, String>): JsonObject? {
        if (extended?.get(KEY) !is JsonObject) return extended
        return write(extended, read(extended).remapped(ids))
    }

    private fun planNode(p: SidePlan) = buildJsonObject {
        put("out", buildJsonArray { p.out.forEach { add(JsonPrimitive(it.value)) } })
        put("in", buildJsonArray { p.into.forEach { add(JsonPrimitive(it.value)) } })
        if (p.note.isNotBlank()) put("note", p.note)
        // The copies picked (2026-10), only once there are: a plan without picks writes as before.
        if (p.outCopies.isNotEmpty()) put("outCopies", copiesNode(p.outCopies))
        if (p.inCopies.isNotEmpty()) put("inCopies", copiesNode(p.inCopies))
    }

    private fun copiesNode(copies: Map<CardId, List<Int>>) = buildJsonObject {
        copies.forEach { (card, picks) -> put(card.value.toString(), buildJsonArray { picks.forEach { add(JsonPrimitive(it)) } }) }
    }

    private fun plan(element: JsonElement?): SidePlan {
        val obj = element as? JsonObject ?: return SidePlan()
        return SidePlan(ids(obj["out"]), ids(obj["in"]), obj.string("note").orEmpty(), copies(obj["outCopies"]), copies(obj["inCopies"]))
    }

    /** `{ "passcode": [copy, …] }`; anything else in it is skipped. */
    private fun copies(element: JsonElement?): Map<CardId, List<Int>> =
        (element as? JsonObject)?.entries?.mapNotNull { (key, value) ->
            val card = key.toIntOrNull()?.takeIf { it > 0 }?.let(::CardId) ?: return@mapNotNull null
            val picks = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { n -> n >= 0 } }?.distinct().orEmpty()
            if (picks.isEmpty()) null else card to picks
        }?.toMap().orEmpty()

    /** Passcodes, as numbers or as the strings the legacy tool wrote. */
    private fun ids(element: JsonElement?): List<CardId> =
        (element as? JsonArray)?.mapNotNull { e ->
            val p = e as? JsonPrimitive ?: return@mapNotNull null
            (p.intOrNull ?: p.content.trim().toIntOrNull())?.takeIf { it > 0 }?.let(::CardId)
        }.orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun readLegacy(patterns: JsonObject?): DeckSiding {
        if (patterns == null) return DeckSiding.EMPTY
        return DeckSiding(
            patterns.entries.mapIndexedNotNull { i, (key, value) ->
                val obj = value as? JsonObject ?: return@mapIndexedNotNull null
                val name = obj.string("deckName")?.takeIf { it.isNotBlank() } ?: obj.string("ydkDeckName") ?: key
                // The oldest shape had one plan, { out, in }, which the legacy tool read as going first.
                val flat = obj["goingFirst"] == null && obj["goingSecond"] == null
                Matchup(
                    id = "legacy-$i",
                    name = name,
                    first = if (flat) plan(obj) else plan(obj["goingFirst"]),
                    second = if (flat) SidePlan() else plan(obj["goingSecond"]),
                )
            },
        )
    }
}

/**
 * Which copies on the board a plan marks (2026-10, kai: "per copy, not per card name"): for each
 * card, the copies picked ([SidePlan.outCopies]) that the section still has, then — for copies the
 * plan moves beyond them — the first ones not picked, in the deck's order.
 */
object SidingMarks {
    /** For each position of [cards], whether the plan marks that copy. */
    fun of(cards: List<CardId>, moved: List<CardId>, picked: Map<CardId, List<Int>> = emptyMap()): List<Boolean> {
        val counts = moved.groupingBy { it }.eachCount()
        val copies = cards.groupingBy { it }.eachCount()
        val marked = HashMap<CardId, Set<Int>>()
        counts.forEach { (card, n) ->
            val have = copies[card] ?: 0
            val chosen = picked[card].orEmpty().filter { it in 0 until have }.distinct().take(n)
            val rest = (0 until have).filter { it !in chosen }.take(n - chosen.size)
            marked[card] = (chosen + rest).toSet()
        }
        val ordinals = ordinals(cards)
        return cards.mapIndexed { i, id -> ordinals[i] in marked[id].orEmpty() }
    }

    /** Each position's place among the copies of its card, 0 the first. */
    fun ordinals(cards: List<CardId>): IntArray {
        val seen = HashMap<CardId, Int>()
        return IntArray(cards.size) { i ->
            val k = seen[cards[i]] ?: 0
            seen[cards[i]] = k + 1
            k
        }
    }
}

/** The arithmetic of a plan against the deck it sides. */
object SidingMath {
    /** How many copies of [card] can go out: every copy in the main and extra decks. */
    fun outOf(deck: Deck, card: CardId): Int = deck.main.count { it == card } + deck.extra.count { it == card }

    /** How many copies of [card] can come in: every copy in the side deck. */
    fun inOf(deck: Deck, card: CardId): Int = deck.side.count { it == card }

    fun canOut(deck: Deck, plan: SidePlan, card: CardId): Boolean = plan.outCount(card) < outOf(deck, card)

    fun canIn(deck: Deck, plan: SidePlan, card: CardId): Boolean = plan.inCount(card) < inOf(deck, card)

    /** [ids] as (card, copies), in the order each card first appears. */
    fun counted(ids: List<CardId>): List<Pair<CardId, Int>> =
        ids.groupingBy { it }.eachCount().let { counts -> ids.distinct().map { it to counts.getValue(it) } }

    /**
     * The copies [plan] names that [deck] no longer has — the deck changed after
     * the plan was written — as (card, copies too many), outs then ins.
     */
    fun stale(deck: Deck, plan: SidePlan): List<Pair<CardId, Int>> =
        counted(plan.out).mapNotNull { (card, n) -> (n - outOf(deck, card)).takeIf { it > 0 }?.let { card to it } } +
            counted(plan.into).mapNotNull { (card, n) -> (n - inOf(deck, card)).takeIf { it > 0 }?.let { card to it } }

    /**
     * What is wrong with the deck [plan] leaves of [deck], in words, or null when it is legal (2026-10, the red team's
     * finding 6): the Lounge's own rule ([LoungeMatch.check], Policy §VII.C), one function for both. Counting the outs
     * against the ins across the Main and Extra Decks let a plan take a Main Deck card out for an Extra Deck card, and
     * leave a 39-card Main Deck. [isExtra] null is a card not known, not judged.
     */
    fun legalAfter(deck: Deck, plan: SidePlan, isExtra: (CardId) -> Boolean?): String? =
        LoungeMatch.check(deck, postSide(deck, plan) { isExtra(it) == true }, isExtra)

    /** How the balance reads: `even`, `2 more in`, `1 more out`. */
    fun balanceWords(plan: SidePlan): String = when {
        plan.balance == 0 -> "even"
        plan.balance > 0 -> "${plan.balance} more in"
        else -> "${-plan.balance} more out"
    }

    /**
     * The deck after siding: the outs taken from the main or extra deck, the ins
     * put where [isExtra] says they belong, and the side deck holding what was
     * swapped. Copies the deck does not have are ignored.
     */
    fun postSide(deck: Deck, plan: SidePlan, isExtra: (CardId) -> Boolean): Deck {
        val main = deck.main.toMutableList()
        val extra = deck.extra.toMutableList()
        val side = deck.side.toMutableList()
        plan.out.forEach { card ->
            val taken = if (isExtra(card)) extra.remove(card) || main.remove(card) else main.remove(card) || extra.remove(card)
            if (taken) side += card
        }
        plan.into.forEach { card ->
            if (side.remove(card)) {
                if (isExtra(card)) extra += card else main += card
            }
        }
        return deck.copy(main = main, extra = extra, side = side)
    }
}
