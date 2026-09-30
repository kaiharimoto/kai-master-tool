package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.meta.DeckAnalysis
import com.kaiharimoto.mastertool.core.ai.meta.FieldBuilder
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import com.kaiharimoto.mastertool.core.remote.YgoProDeckDecks
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

/** What a meta tool answered: for the model, for the chat, and whether it failed. */
internal class MetaAnswer(val content: String, val summary: String, val isError: Boolean = false)

/**
 * Ai's reading of the meta (phase 2): YGOPRODeck's tournament decks, the field built
 * from them, and a deck's shape in numbers. Every list fetched this session is kept
 * by its number, so a list named in one answer can be read or imported in the next.
 */
internal class AiMeta(private val h: NeueHolders, private val ai: AiState) {
    private val source by lazy {
        YgoProDeckDecks(HttpClientFactory.create(), userAgent = "NeueMasterTool/${Platform.version}", clock = System::currentTimeMillis)
    }
    private val seen = LinkedHashMap<Int, TournamentDeck>()
    private val index get() = h.builder.index

    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "analyze_deck" -> analyze(ToolArgs.string(i, "deck_id"))
        "ygopro_tournament_decks" -> tournamentDecks(i)
        "ygopro_deck" -> deck(ToolArgs.int(i, "deck_number") ?: return fail("deck_number is needed."))
        "import_ygopro_deck" -> import(i)
        "ygopro_field_snapshot" -> field(i)
        else -> null
    }

    private suspend fun analyze(id: String?): MetaAnswer {
        val state = h.builder
        val (name, deck, groups) = if (id == null || id == state.deckId) {
            Triple(state.deckName, state.deck, state.groups)
        } else {
            val s = h.deps.deckRepository.byId(id) ?: return fail("No deck $id.")
            Triple(s.entry.name, s.entry.deck, DeckGroupsCodec.read(s.extended).groups)
        }
        return MetaAnswer("“$name”\n" + DeckAnalysis.describe(deck, index::byId, state.format, groups), "Analysed “$name”")
    }

    private fun formatOf(word: String?): DeckFormat = when (word?.trim()?.lowercase()) {
        "ocg" -> DeckFormat.OCG
        "genesys" -> DeckFormat.GENESYS
        "tcg" -> DeckFormat.TCG
        else -> if (h.builder.format.name == "OCG") DeckFormat.OCG else DeckFormat.TCG
    }

    private fun line(d: TournamentDeck): String =
        "#${d.number} | ${d.name} | ${d.placement} of ${d.players ?: "?"} at ${d.event} | ${d.format.name.lowercase()} | ${if (d.daysAgo == 0) "today" else "${d.daysAgo}d ago"}"

    private suspend fun tournamentDecks(i: JsonObject): MetaAnswer {
        val tier = (ToolArgs.int(i, "tier") ?: 2).coerceIn(1, 4)
        val format = formatOf(ToolArgs.string(i, "format"))
        val days = (ToolArgs.int(i, "days") ?: 60).coerceIn(1, 365)
        val archetype = ToolArgs.string(i, "archetype")?.lowercase()
        val event = ToolArgs.string(i, "event")?.lowercase()
        val page = ToolArgs.int(i, "page") ?: 0
        val (decks, problems) = if (page > 0) {
            val got = (tier..4).flatMap { t -> source.page(t, page).getOrElse { emptyList() } }
            got.filter { it.format == format } to emptyList()
        } else {
            source.recent(tier, days, format)
        }
        decks.forEach { seen[it.number] = it }
        val shown = decks.filter { d -> (archetype == null || archetype in d.name.lowercase()) && (event == null || event in d.event.lowercase()) }
            .sortedBy { it.daysAgo }
        if (shown.isEmpty()) {
            val why = if (problems.isNotEmpty()) " YGOPRODeck: ${problems.joinToString("; ")}." else ""
            return if (problems.isNotEmpty() && decks.isEmpty()) fail("Could not read YGOPRODeck's tournament decks.$why")
            else MetaAnswer("No ${format.name} tournament decks match (tier $tier+, last $days days).$why", "No matching tournament decks")
        }
        val head = "${shown.size} ${format.name} tournament decks, tier $tier and up, last $days days (YGOPRODeck):"
        return MetaAnswer(
            head + "\n" + shown.take(60).joinToString("\n") { line(it) } + if (problems.isNotEmpty()) "\n(Some pages failed: ${problems.joinToString("; ")})" else "",
            "Read ${shown.size} tournament decks from YGOPRODeck",
        )
    }

    private fun counted(ids: List<CardId>): String =
        ids.groupingBy { it }.eachCount().entries.joinToString("\n") { "${it.value} ${index.byId(it.key)?.name ?: "card ${it.key.value}"}" }

    private fun deck(number: Int): MetaAnswer {
        val d = seen[number] ?: return fail("Deck #$number is not among the lists read this session; list them first with ygopro_tournament_decks or ygopro_field_snapshot.")
        return MetaAnswer(
            "${line(d)}\nPilot: ${d.pilot ?: "unknown"} · ${d.url}\n\nMain (${d.deck.main.size}):\n${counted(d.deck.main)}\n\nExtra (${d.deck.extra.size}):\n${counted(d.deck.extra)}\n\nSide (${d.deck.side.size}):\n${counted(d.deck.side)}",
            "Read #${d.number}, ${d.name}",
        )
    }

    private suspend fun import(i: JsonObject): MetaAnswer {
        val number = ToolArgs.int(i, "deck_number") ?: return fail("deck_number is needed.")
        val d = seen[number] ?: return fail("Deck #$number is not among the lists read this session; list them first.")
        val name = ToolArgs.string(i, "name") ?: "${d.name} (${d.placement}, ${d.event})"
        val webId = ToolArgs.string(i, "web_id")
        val notes = "From YGOPRODeck: ${d.url} — ${d.placement} of ${d.players ?: "?"} at ${d.event}, piloted by ${d.pilot ?: "?"}."
        if (webId != null) {
            val web = h.webs.library.byId(webId) ?: return fail("No web $webId.")
            val id = suspendCancellableCoroutine<String> { cont -> h.webs.add(webId, name, YdkDocument(d.deck)) { if (cont.isActive) cont.resume(it) } }
            ToolArgs.int(i, "share")?.let { h.webs.share(webId, id, it) }
            h.decksReload++
            return MetaAnswer("Added “$name” to “${web.name}” as deck $id. $notes", "Imported #${d.number} into “${web.name}”")
        }
        val id = h.deps.newDeckId()
        h.deps.deckRepository.save(id, name, d.deck, null, notes)
        h.decksReload++
        return MetaAnswer("Saved “$name” to the library as deck $id. $notes", "Imported #${d.number} to the library")
    }

    private suspend fun field(i: JsonObject): MetaAnswer {
        val tier = (ToolArgs.int(i, "tier") ?: 2).coerceIn(1, 4)
        val format = formatOf(ToolArgs.string(i, "format"))
        val days = (ToolArgs.int(i, "days") ?: 45).coerceIn(7, 365)
        val top = (ToolArgs.int(i, "top") ?: 12).coerceIn(3, 30)
        val (decks, problems) = source.recent(tier, days, format, maxPages = 6)
        decks.forEach { seen[it.number] = it }
        if (decks.isEmpty()) {
            return if (problems.isNotEmpty()) fail("Could not read YGOPRODeck's tournament decks: ${problems.joinToString("; ")}")
            else MetaAnswer("No ${format.name} results at tier $tier+ in the last $days days.", "No results to build a field from")
        }
        val clusters = FieldBuilder.build(decks, top)
        val text = buildString {
            appendLine("The ${format.name} field from ${decks.size} tournament decks (tier $tier+, last $days days, YGOPRODeck), by strategy; share is of results weighted by placement and event size:")
            clusters.forEachIndexed { n, c ->
                appendLine()
                appendLine("${n + 1}. ${c.name} — ${c.share}% (${c.decks.size} lists). Representative: #${c.representative.number} (${c.representative.name}, ${c.representative.placement} at ${c.representative.event}).")
                appendLine("   Best: " + c.best.joinToString("; ") { "${it.placement} of ${it.players ?: "?"} at ${it.event}" })
                if (c.core.isNotEmpty()) appendLine("   Core: " + c.core.take(10).joinToString { index.byId(it)?.name ?: it.value.toString() })
            }
            val covered = clusters.sumOf { it.share }
            appendLine()
            append("These ${clusters.size} strategies are $covered% of the weighted results.")
            if (problems.isNotEmpty()) append(" (Some pages failed: ${problems.joinToString("; ")}.)")
        }
        return MetaAnswer(text, "Read the ${format.name} field: ${clusters.take(3).joinToString { "${it.name} ${it.share}%" }}")
    }
}
