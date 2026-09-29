package com.kaiharimoto.mastertool.core.siding

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
) {
    val isEmpty: Boolean get() = out.isEmpty() && into.isEmpty() && note.isBlank()
    val sided: Boolean get() = out.isNotEmpty() || into.isNotEmpty()

    /** In minus out: 0 keeps the deck its size. */
    val balance: Int get() = into.size - out.size

    fun outCount(card: CardId): Int = out.count { it == card }
    fun inCount(card: CardId): Int = into.count { it == card }

    fun plusOut(card: CardId) = copy(out = out + card)
    fun plusIn(card: CardId) = copy(into = into + card)
    fun minusOut(card: CardId) = copy(out = out.minusOne(card))
    fun minusIn(card: CardId) = copy(into = into.minusOne(card))

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
    /** The web deck this is against, when it is one. */
    val deckId: String? = null,
    val note: String = "",
    val first: SidePlan = SidePlan(),
    val second: SidePlan = SidePlan(),
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
 *   "first":  { "out": [14558127, 14558127], "in": [9822220], "note": "…" },
 *   "second": { "out": [], "in": [], "note": "" }
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
    }

    private fun plan(element: JsonElement?): SidePlan {
        val obj = element as? JsonObject ?: return SidePlan()
        return SidePlan(ids(obj["out"]), ids(obj["in"]), obj.string("note").orEmpty())
    }

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
