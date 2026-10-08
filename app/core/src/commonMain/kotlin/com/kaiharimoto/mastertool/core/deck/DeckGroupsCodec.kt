package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.hand.Ask
import com.kaiharimoto.mastertool.core.hand.HandGoal
import com.kaiharimoto.mastertool.core.hand.HandGoals
import com.kaiharimoto.mastertool.core.hand.LensOdds
import com.kaiharimoto.mastertool.core.model.CardId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/**
 * Groups live inside the `#ydkx-extended` payload, under their own key.
 *
 * The payload is otherwise opaque to this app and must stay that way: it
 * carries the legacy tool's siding patterns and whatever future keys either
 * tool invents, and a deck file that loses a key it didn't understand is a
 * deck file nobody can trust. Reading tolerates any malformed shape by
 * returning empty; writing replaces exactly one key and copies every other
 * verbatim. The legacy reserved `notes` slot is deliberately left alone.
 *
 * Shape, kept boring on purpose:
 * ```json
 * "groups": {
 *   "defs": [{ "id": "g1", "name": "Handtraps", "color": 3, "order": 0 }],
 *   "cards": { "14558127": "g1" },
 *   "lens": "ROLES",
 *   "fitted": [14558127, 23434538],
 *   "goals": [{ "id": "q1", "name": "Opens", "hand": 5, "asks": { "g1": "AT_LEAST_1" } }]
 * },
 * "groupSets": {
 *   "active": "s1",
 *   "sets": [{ "id": "s1", "name": "Roles" }, { "id": "s2", "name": "Combo", "defs": [], "cards": {} }]
 * }
 * ```
 * `groupSets` is written only once a deck has more than its one unnamed set.
 */
object DeckGroupsCodec {

    private const val KEY = "groups"
    private const val SETS_KEY = "groupSets"

    fun read(extended: JsonObject?): StoredGroups {
        val sets = readSets(extended?.get(SETS_KEY) as? JsonObject)
        val node = extended?.get(KEY) as? JsonObject ?: return StoredGroups.EMPTY.copy(sets = sets)
        return StoredGroups(readGroups(node), readLens(node), readGoals(node), sets)
    }

    /** One set of groups as a node holds it: `defs`, `cards` and `fitted`, each tolerated missing. */
    private fun readGroups(node: JsonObject): DeckGroups {
        val defs = (node["defs"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            DeckGroup(
                id = id,
                name = (obj["name"] as? JsonPrimitive)?.content ?: "Group",
                color = (obj["color"] as? JsonPrimitive)?.intOrNull ?: 0,
                order = (obj["order"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE,
            )
        }.orEmpty()

        val cards = (node["cards"] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
            val passcode = key.toIntOrNull() ?: return@mapNotNull null
            val group = (value as? JsonPrimitive)?.content ?: return@mapNotNull null
            CardId(passcode) to group
        }?.toMap().orEmpty()

        // The Fitted order (1.0.39): passcodes, each once; anything else in the list is skipped.
        val fitted = (node["fitted"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull()?.let(::CardId) }?.distinct().orEmpty()

        return DeckGroups(defs, cards, fitted)
    }

    /**
     * The deck's other sets of groups (2026-10), under a key of their own beside `groups`, so an
     * older build — which rewrites the `groups` key whole and carries every other key byte for
     * byte — keeps them. The set in use is named here but its groups are the `groups` key's.
     */
    private fun readSets(node: JsonObject?): GroupSets {
        node ?: return GroupSets.PLAIN
        val list = (node["sets"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GroupSet(id, (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id, readGroups(obj))
        }.orEmpty().distinctBy { it.id }
        if (list.isEmpty()) return GroupSets.PLAIN
        val active = (node["active"] as? JsonPrimitive)?.content
        // The set in use is always one of the list: a file naming none puts the first in use.
        return GroupSets(list, active?.takeIf { a -> list.any { it.id == a } } ?: list.first().id)
    }

    /**
     * The questions the deck was left holding.
     *
     * Tolerant in exactly the way the rest of this codec is: an ask naming a
     * position this build has never heard of is dropped rather than failing the
     * read, because a deck file written by a newer version must still open.
     */
    private fun readGoals(node: JsonObject): HandGoals {
        val array = node["goals"] as? JsonArray ?: return HandGoals.EMPTY

        return HandGoals(
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                HandGoal(
                    id = id,
                    name = (obj["name"] as? JsonPrimitive)?.content.orEmpty(),
                    handSize = (obj["hand"] as? JsonPrimitive)?.intOrNull ?: LensOdds.DEFAULT_HAND,
                    asks = (obj["asks"] as? JsonObject)?.entries?.mapNotNull { (group, value) ->
                        val name = (value as? JsonPrimitive)?.content ?: return@mapNotNull null
                        Ask.entries.firstOrNull { it.name == name }?.let { group to it }
                    }?.toMap().orEmpty(),
                )
            }
        )
    }

    /**
     * Which lens the deck was last read through.
     *
     * `breakdown: true` is what v1.2 wrote, back when there was one lens and it
     * was the user's own groups. Decks saved then still open on the reading
     * they were left on; an unknown name falls back to the plain deck rather
     * than to whatever the enum happens to declare first.
     */
    private fun readLens(node: JsonObject): Lens {
        (node["lens"] as? JsonPrimitive)?.content?.let { name ->
            Lens.entries.firstOrNull { it.name == name }?.let { return it }
        }
        return if ((node["breakdown"] as? JsonPrimitive)?.content == "true") {
            Lens.ROLES
        } else {
            Lens.DECK
        }
    }

    /**
     * Returns [extended] with the groups key rewritten — or removed when there
     * is nothing to store, so a deck that never used groups round-trips
     * byte-identically. Every other key is carried over untouched.
     */
    fun write(extended: JsonObject?, stored: StoredGroups): JsonObject? {
        val others = extended?.filterKeys { it != KEY && it != SETS_KEY } ?: emptyMap()
        val sets = if (stored.sets.isPlain) null else SETS_KEY to writeSets(stored.sets)

        if (stored.groups.isEmpty && stored.lens == Lens.DECK && stored.goals.isEmpty) {
            val rest = others + listOfNotNull(sets)
            return if (rest.isEmpty()) null else JsonObject(rest)
        }

        val node = buildJsonObject {
            // The key order older builds wrote: defs, cards, lens, fitted.
            putGroups(stored.groups, lens = stored.lens)

            if (!stored.goals.isEmpty) {
                put(
                    "goals",
                    buildJsonArray {
                        stored.goals.goals.forEach { goal ->
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(goal.id))
                                    put("name", JsonPrimitive(goal.name))
                                    put("hand", JsonPrimitive(goal.handSize))
                                    put(
                                        "asks",
                                        buildJsonObject {
                                            goal.asks.forEach { (group, ask) ->
                                                put(group, JsonPrimitive(ask.name))
                                            }
                                        },
                                    )
                                }
                            )
                        }
                    },
                )
            }
        }

        return JsonObject(others + (KEY to node) + listOfNotNull(sets))
    }

    /** One set's groups: `defs`, `cards`, and `fitted` once a card has been moved there. */
    private fun JsonObjectBuilder.putGroups(groups: DeckGroups, lens: Lens? = null) {
        put(
            "defs",
            buildJsonArray {
                groups.ordered().forEach { group ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(group.id))
                            put("name", JsonPrimitive(group.name))
                            put("color", JsonPrimitive(group.color))
                            put("order", JsonPrimitive(group.order))
                        }
                    )
                }
            },
        )
        put(
            "cards",
            buildJsonObject {
                groups.assignments.forEach { (card, group) ->
                    put(card.value.toString(), JsonPrimitive(group))
                }
            },
        )
        lens?.let { put("lens", JsonPrimitive(it.name)) }
        if (groups.fitted.isNotEmpty()) {
            put("fitted", buildJsonArray { groups.fitted.forEach { add(JsonPrimitive(it.value)) } })
        }
    }

    /** Every set in the menu's order; the one in use by its id and name alone, since its groups are `groups`. */
    private fun writeSets(sets: GroupSets): JsonObject = buildJsonObject {
        put("active", JsonPrimitive(sets.current.id))
        put(
            "sets",
            buildJsonArray {
                sets.sets.forEach { set ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(set.id))
                            put("name", JsonPrimitive(set.name))
                            if (set.id != sets.current.id) putGroups(set.groups)
                        }
                    )
                }
            },
        )
    }
}

/**
 * What the payload stores: the groups, how the deck is being read, and what it
 * is being asked.
 */
data class StoredGroups(
    val groups: DeckGroups,
    /**
     * The lens travels with the deck rather than the install: a deck organised
     * into groups is meant to open organised, and a deck you were last reading
     * by archetype is meant to open that way too.
     */
    val lens: Lens,
    /**
     * And so do the questions. A goal written in terms of this deck's roles is
     * about this deck and nothing else, and a question you have to rebuild
     * from memory every session is one you stop asking.
     */
    val goals: HandGoals = HandGoals.EMPTY,
    /**
     * The deck's other ways of grouping it, and which is in use (2026-10). The one in use is
     * [groups]; the others wait here. Lens and goals belong to the deck, not to a set.
     */
    val sets: GroupSets = GroupSets.PLAIN,
) {
    companion object {
        val EMPTY = StoredGroups(DeckGroups.EMPTY, Lens.DECK, HandGoals.EMPTY, GroupSets.PLAIN)
    }
}
