package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.world.Study.Companion.obj
import com.kaiharimoto.mastertool.core.world.Study.Companion.strs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The instruments about what a deck is (1.0.97): who reaches whom, read off the cards' own text ([CardText]), and what
 * the deck is made of. Both read text approximately and say so: a link is drawn only where every word was understood.
 */
internal object DeckInstruments {
    // ---- card_web ---------------------------------------------------------------------------------------------

    fun cardWeb(args: JsonObject, host: WorldHost): Instruments.Result {
        val read = Instruments.deckFor(args, host)
        val include = (args["include"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() } ?: listOf("main", "extra")
        val ids = buildList {
            if ("main" in include) addAll(read.entry.deck.main)
            if ("extra" in include) addAll(read.entry.deck.extra)
            if ("side" in include) addAll(read.entry.deck.side)
        }
        val copies = ids.groupingBy { read.name(it) }.eachCount()
        val cards = ids.distinct().mapNotNull { read.cards[it] }.distinctBy { it.name }
        val s = Study()
        s.say("card_web: ${read.entry.name} — which of the deck's cards reach which, by what their text says?")
        s.say("  method: each card's action clauses read off Konami's phrasing (by name, archetype or properties such as \"Level 1 FIRE monster\"), and Extra Deck materials; a clause after \"cannot\" is a lock, not a link; a phrase not wholly understood is skipped")
        val edges = LinkedHashMap<Pair<String, String>, MutableSet<String>>()
        cards.forEach { a ->
            val links = CardText.links(a).map { it.verb to it.filter } +
                (if (a.isExtraDeck) CardText.materials(a).map { "material" to it } else emptyList())
            links.forEach { (verb, filter) ->
                if (!filter.specific) return@forEach
                cards.filter { b -> b.name != a.name && filter.matches(b) }.forEach { b ->
                    // A material is drawn from the material to what it makes.
                    val key = if (verb == "material") b.name to a.name else a.name to b.name
                    edges.getOrPut(key) { linkedSetOf() } += if (verb == "material") "is material for" else verb
                }
            }
        }
        val edgeList = edges.entries.map { (k, v) -> Triple(k.first, k.second, v.joinToString("/")) }.take(WorldGraph.MAX_EDGES)
        val degree = cards.associate { c -> c.name to edgeList.count { it.first == c.name || it.second == c.name } }
        val alone = cards.filter { degree[it.name] == 0 }.map { it.name }
        val hubs = degree.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(5)
        // Access: a card searched from the Deck by others is in effect more copies.
        val searchers = cards.associate { c -> c.name to edgeList.filter { it.second == c.name && "searches" in it.third }.map { it.first } }
        val access = cards.filter { !it.isExtraDeck }.map { c ->
            Triple(c.name, copies[c.name] ?: 0, (copies[c.name] ?: 0) + searchers.getValue(c.name).sumOf { copies[it] ?: 0 })
        }.sortedByDescending { it.third }
        s.say("  ${cards.size} cards, ${edgeList.size} links; hubs: " + hubs.joinToString { "${it.key} (${it.value})" }.ifEmpty { "none" })
        if (alone.isNotEmpty()) s.say("  standing alone (no link read either way): " + alone.joinToString())
        access.firstOrNull { it.third > it.second }?.let { s.say("  most reachable: ${it.first} — ${it.second} copies, ${it.third} counting the cards that search it") }
        if (edgeList.isEmpty()) s.warn("no card's text names or describes another here: links by effects the text does not spell out are not read")
        val groupOf = { n: String -> read.groups.entries.firstOrNull { n in it.value }?.key.orEmpty() }
        val linked = (edgeList.map { it.first } + edgeList.map { it.second }).toSet()
        val graph = WorldGraph(
            "",
            cards.filter { it.name in linked }.take(WorldGraph.MAX_NODES).map { c ->
                WorldGraph.Node(c.name, c.name, groupOf(c.name), (1.0 + (degree[c.name] ?: 0) / 3.0).coerceAtMost(4.0), card = true)
            },
            edgeList.map { WorldGraph.Edge(it.first, it.second, it.third) },
        )
        if (graph.nodes.isNotEmpty()) {
            s.board("card-web", "Card web — ${read.entry.name}", BoardKind.GRAPH, WorldGraph.encode(graph),
                "Read off the cards' text: an arrow from a card to each card it searches, summons, sends or recovers, and from a material to what it makes. A card standing alone is not drawn.")
        }
        s.table("card-access", "Each card's access", listOf("Card", "Copies", "With its searchers", "Searched by"),
            access.take(30).map { listOf(it.first, "${it.second}", "${it.third}", searchers.getValue(it.first).joinToString()) },
            "Copies plus the copies of every card that searches it from the Deck: how many cards in the deck lead to it.", cards = listOf(0))
        return s.done(obj(
            "nodes" to JsonPrimitive(cards.size),
            "edges" to JsonArray(edgeList.map { strs(listOf(it.first, it.second, it.third)) }),
            "hubs" to strs(hubs.map { it.key }),
            "alone" to strs(alone),
        ))
    }

    // ---- composition ------------------------------------------------------------------------------------------

    fun composition(args: JsonObject, host: WorldHost): Instruments.Result {
        val read = Instruments.deckFor(args, host)
        val mainIds = read.entry.deck.main
        val main = mainIds.mapNotNull { read.cards[it] }
        val extra = read.entry.deck.extra.mapNotNull { read.cards[it] }
        val unresolved = (mainIds + read.entry.deck.extra).count { it !in read.cards }
        val s = Study()
        s.say("composition: ${read.entry.name} — what is the deck made of, and what do its cards do?")
        s.say("  method: counted card by card, every copy; what a card does read off its text (approximate, and said to be)")
        if (unresolved > 0) s.warn("$unresolved card${if (unresolved == 1) "" else "s"} unknown to the pool: not counted below (is the pool up to date?)")
        fun Map<String, Int>.chart(id: String, title: String, note: String) {
            if (isEmpty()) return
            s.chart(id, title, ChatChart.Type.BAR, keys.toList(), listOf(ChatChart.Series("cards", values.map { it.toDouble() })), "", note)
        }
        val kinds = main.groupingBy { it.category.name.lowercase().replaceFirstChar { c -> c.uppercase() } }.eachCount()
        val subtypes = main.groupingBy { c -> subtype(c) }.eachCount().toList().sortedByDescending { it.second }.toMap()
        val monsters = main.filter { it.category == CardCategory.MONSTER }
        val levels = monsters.groupingBy { it.level?.toString() ?: "—" }.eachCount().entries.sortedBy { it.key.toIntOrNull() ?: 99 }.associate { it.key to it.value }
        val attributes = monsters.groupingBy { it.attribute.name.lowercase().replaceFirstChar { c -> c.uppercase() } }.eachCount()
        val races = monsters.groupingBy { it.race ?: "—" }.eachCount().toList().sortedByDescending { it.second }.toMap()
        val roles = main.flatMap { c -> CardText.roles(c).map { it to c.name } }.groupBy({ it.first }, { it.second })
        val extraKinds = extra.groupingBy { c -> listOf("link", "xyz", "synchro", "fusion").firstOrNull { c.frameType.contains(it, true) } ?: "other" }.eachCount()
        s.say("  Main Deck: " + kinds.entries.joinToString { "${it.value} ${it.key}" } + "; Extra Deck: " + extraKinds.entries.joinToString { "${it.value} ${it.key}" }.ifEmpty { "none" })
        roles.entries.sortedByDescending { it.value.size }.forEach { (role, names) -> s.say("  $role: ${names.size} cards (${names.distinct().joinToString()})") }
        kinds.chart("composition-kinds", "Main Deck by kind", "Every copy counted.")
        subtypes.chart("composition-subtypes", "By subtype", "Every copy counted.")
        levels.chart("composition-levels", "Monsters by Level or Rank", "Every copy counted.")
        attributes.chart("composition-attributes", "Monsters by Attribute", "Every copy counted.")
        races.chart("composition-types", "Monsters by Type", "Every copy counted.")
        extraKinds.chart("composition-extra", "Extra Deck by kind", "Every copy counted.")
        if (roles.isNotEmpty()) {
            s.table("composition-roles", "What the cards do", listOf("Does", "Cards", "Which"),
                roles.entries.sortedByDescending { it.value.size }.map { (r, n) -> listOf(r, "${n.size}", n.distinct().joinToString()) },
                "Read off the text by pattern: approximate — check a surprising count against the cards.")
        }
        val atk = monsters.mapNotNull { it.atk?.toDouble() }
        if (atk.size >= 3) {
            WorldChart.parse("""{"type":"histogram","values":[${atk.joinToString()}],"bins":8}""").getOrNull()?.let {
                s.board("composition-atk", "ATK curve", BoardKind.CHART, WorldChart.encode(it), "Every monster copy's printed ATK.")
            }
        }
        return s.done(obj(
            "unresolved" to JsonPrimitive(unresolved),
            "kinds" to JsonObject(kinds.mapValues { JsonPrimitive(it.value) }),
            "levels" to JsonObject(levels.mapValues { JsonPrimitive(it.value) }),
            "roles" to JsonObject(roles.mapValues { JsonPrimitive(it.value.size) }),
            "extra" to JsonObject(extraKinds.mapValues { JsonPrimitive(it.value) }),
        ))
    }

    private fun subtype(c: Card): String = when (c.category) {
        CardCategory.SPELL, CardCategory.TRAP -> "${c.race ?: "Normal"} ${c.category.name.lowercase().replaceFirstChar { it.uppercase() }}"
        else -> listOf("Tuner", "Pendulum", "Ritual", "Flip", "Gemini", "Spirit", "Union", "Toon", "Normal", "Effect").firstOrNull { c.type.contains(it, ignoreCase = true) } ?: "Monster"
    }
}
