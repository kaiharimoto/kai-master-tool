package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.meta.DeckAnalysis
import com.kaiharimoto.mastertool.core.ai.meta.FieldBuilder
import com.kaiharimoto.mastertool.core.ai.wire.Unreachable
import com.kaiharimoto.mastertool.core.ai.web.Untrusted
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.PlayerPages
import com.kaiharimoto.mastertool.core.remote.RecentDecks
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
        "ygopro_player" -> player(ToolArgs.string(i, "name") ?: return fail("name is needed."), ToolArgs.string(i, "archetype"))
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
        "#${d.number} | ${d.name} | ${d.placement} of ${d.players ?: "?"} at ${d.event} | ${d.pilot ?: "pilot unknown"} | ${d.format.name.lowercase()} | " +
            (d.date ?: if (d.daysAgo == 0) "today" else if (d.daysAgo >= 999) "date unknown" else "${d.daysAgo}d ago")

    private suspend fun tournamentDecks(i: JsonObject): MetaAnswer {
        val tier = (ToolArgs.int(i, "tier") ?: 2).coerceIn(1, 4)
        val format = formatOf(ToolArgs.string(i, "format"))
        val daysGiven = ToolArgs.int(i, "days")
        val days = (daysGiven ?: 60).coerceIn(1, 365)
        val archetype = ToolArgs.string(i, "archetype")?.lowercase()
        val event = ToolArgs.string(i, "event")?.lowercase()
        val pilot = ToolArgs.string(i, "player")
        val page = ToolArgs.int(i, "page") ?: 0
        // An older page is read whole, whatever its age, unless days was asked for; a page that fails fails the answer.
        val window = if (page > 0 && daysGiven == null) "page $page of each tier, any age" else "last $days days" + if (page > 0) ", page $page" else ""
        val (decks, problems, unread) = if (page > 0) {
            val got = (tier..4).flatMap { t ->
                source.page(t, page).getOrElse { return fail("Could not read YGOPRODeck's tournament decks (tier $t, page $page). ${Unreachable.of(YgoProDeckDecks.SITE, it)}") }
            }
            RecentDecks(got.filter { it.format == format && (daysGiven == null || it.daysAgo <= days) }, emptyList())
        } else {
            source.recent(tier, days, format, maxPages = RECENT_PAGES)
        }
        decks.forEach { seen[it.number] = it }
        val shown = decks.filter { d -> (archetype == null || archetype in d.name.lowercase()) && (event == null || event in d.event.lowercase()) && (pilot == null || PlayerPages.names(pilot, d.pilot)) }
            .sortedBy { it.daysAgo }
        val cut = cutShort(unread, "ask again with page $RECENT_PAGES and up to read further back")
        if (shown.isEmpty()) {
            val why = if (problems.isNotEmpty()) " YGOPRODeck: ${problems.joinToString("; ")}." else ""
            return if (problems.isNotEmpty() && decks.isEmpty()) fail("Could not read YGOPRODeck's tournament decks.$why")
            else MetaAnswer(
                "No ${format.name} tournament decks match (tier $tier+, $window).$why$cut" +
                    if (pilot != null) " These are only the recent pages; ygopro_player reads $pilot's whole record." else "",
                "No matching tournament decks",
            )
        }
        val head = "${shown.size} ${format.name} tournament decks, tier $tier and up, $window (YGOPRODeck):"
        return MetaAnswer(
            head + "\n" + Untrusted.wrap(SOURCE, shown.take(60).joinToString("\n") { line(it) }) +
                (if (shown.size > 60) "\n(${shown.size - 60} more not shown: narrow with archetype, event or days.)" else "") +
                (if (problems.isNotEmpty()) "\n(Some pages failed: ${problems.joinToString("; ")})" else "") +
                (if (cut.isNotEmpty()) "\n(${cut.trim()})" else ""),
            "Read ${shown.size} tournament decks from YGOPRODeck",
        )
    }

    /** What [YgoProDeckDecks.recent] left unread, in words — empty when it read the whole window. */
    private fun cutShort(unread: List<Int>, next: String): String {
        if (unread.isEmpty()) return ""
        val tiers = unread.joinToString(", ") { "tier $it" }
        return " Not every list in the window was read at $tiers: the last page read was still inside it, so older lists in it were left out — $next."
    }

    private companion object {
        /** Pages a tier that ygopro_tournament_decks reads; page this and up reaches further back. */
        const val RECENT_PAGES = 4

        /** Pages a tier that the field snapshot reads. */
        const val FIELD_PAGES = 6

        /** Where the lists come from, for the envelope round what people typed into the site. */
        const val SOURCE = "YGOPRODeck"
    }

    private fun counted(ids: List<CardId>): String =
        ids.groupingBy { it }.eachCount().entries.joinToString("\n") { "${it.value} ${index.byId(it.key)?.name ?: "card ${it.key.value}"}" }

    /** A list by its number: one read this session, else the deck's own page on YGOPRODeck. */
    private suspend fun listNumbered(number: Int): Result<TournamentDeck> {
        seen[number]?.let { return Result.success(it) }
        return source.deck(number).mapCatching { it ?: error("YGOPRODeck has no list numbered $number.") }
            .onSuccess { seen[number] = it }
    }

    private suspend fun deck(number: Int): MetaAnswer {
        val d = listNumbered(number).getOrElse { return fail("Could not read deck #$number. ${Unreachable.of(YgoProDeckDecks.SITE, it)}") }
        return MetaAnswer(
            Untrusted.wrap(
                "$SOURCE deck #${d.number}",
                "${line(d)}\nPilot: ${d.pilot ?: "unknown"} · ${d.url}\n\nMain (${d.deck.main.size}):\n${counted(d.deck.main)}\n\nExtra (${d.deck.extra.size}):\n${counted(d.deck.extra)}\n\nSide (${d.deck.side.size}):\n${counted(d.deck.side)}",
            ),
            "Read #${d.number}, ${d.name}",
        )
    }

    /**
     * A player's record (1.0.59): the site's player search, then the one player it means — the
     * only match, or the one whose whole name it is — then their page, every top with its list.
     */
    private suspend fun player(name: String, archetype: String?): MetaAnswer {
        val found = source.players(name).getOrElse { return fail("Could not search YGOPRODeck's players. ${Unreachable.of(YgoProDeckDecks.SITE, it)}") }
        val paths = found.map { it.path }.distinct()
        val path = when {
            paths.size == 1 -> paths.single()
            else -> found.filter { PlayerPages.fold(it.name) == PlayerPages.fold(name) }.map { it.path }.distinct().singleOrNull()
        }
        if (found.isEmpty()) return MetaAnswer("YGOPRODeck has no tournament player named like “$name”. Try part of the name, or another spelling.", "No player “$name”")
        if (path == null) {
            return MetaAnswer(
                "${found.size} players on YGOPRODeck match “$name”; ask again with one full name" +
                    (if (found.size >= 25) " (the site shows at most 25 matches, so the one meant may not be among these — use more of the name)" else "") + ":\n" +
                    Untrusted.wrap(
                        "$SOURCE player search",
                        found.take(30).joinToString("\n") { "- ${it.name}${it.country?.let { c -> " ($c)" }.orEmpty()}, last top ${it.lastSeen}" },
                    ),
                "${found.size} players match “$name”",
            )
        }
        val career = source.career(path).getOrElse { return fail("Could not read the player's page. ${Unreachable.of(YgoProDeckDecks.SITE, it)}") }
            ?: return MetaAnswer("YGOPRODeck lists no results for “$name”.", "No results for “$name”")
        val results = career.results.filter { r -> archetype == null || r.archetypes.any { PlayerPages.names(archetype, it) } }
        // The player's name, events and decks are what people typed into the site: outside text.
        val record = buildString {
            append("${career.name}${career.country?.let { " ($it)" }.orEmpty()} on YGOPRODeck")
            if (career.tally.isNotEmpty()) append(" — ").append(career.tally.joinToString("; "))
            appendLine(". ${results.size} results${archetype?.let { " with $it" }.orEmpty()}, newest first:")
            results.take(60).forEach { r ->
                append(r.date).append(" | ").append(r.placement).append(" | ").append(r.event).append(" | ").append(r.archetypes.joinToString(" / ").ifEmpty { "?" })
                appendLine(if (r.deckNumber != null) " | list #${r.deckNumber}" else " | no list published")
            }
        }
        val text = Untrusted.wrap(PlayerPages.absolute(path), record) +
            "\nRead a list with ygopro_deck and its number. Source: ${PlayerPages.absolute(path)}"
        return MetaAnswer(text, "Read ${career.name}'s ${results.size} results")
    }

    private suspend fun import(i: JsonObject): MetaAnswer {
        val number = ToolArgs.int(i, "deck_number") ?: return fail("deck_number is needed.")
        val d = listNumbered(number).getOrElse { return fail("Could not read deck #$number. ${Unreachable.of(YgoProDeckDecks.SITE, it)}") }
        val name = ToolArgs.string(i, "name") ?: "${d.name} (${d.placement}, ${d.event})"
        val webId = ToolArgs.string(i, "web_id")
        val notes = "From YGOPRODeck: ${d.url} — ${d.placement} of ${d.players ?: "?"} at ${d.event}${d.date?.let { " ($it)" }.orEmpty()}, piloted by ${d.pilot ?: "?"}."
        // The deck's name, event and pilot are what people typed into the site: in the answer they are
        // outside text; the notes kept with the deck stay as they were, plain.
        val from = "$SOURCE deck #${d.number}"
        if (webId != null) {
            val web = h.webs.library.byId(webId) ?: return fail("No web $webId.")
            val id = suspendCancellableCoroutine<String> { cont -> h.webs.add(webId, name, YdkDocument(d.deck)) { if (cont.isActive) cont.resume(it) } }
            ToolArgs.int(i, "share")?.let { h.webs.share(webId, id, it) }
            h.decksReload++
            return MetaAnswer("Added it to “${web.name}” as deck $id:\n" + Untrusted.wrap(from, "“$name”\n$notes"), "Imported #${d.number} into “${web.name}”")
        }
        val id = h.deps.newDeckId()
        h.deps.deckRepository.save(id, name, d.deck, null, notes)
        h.decksReload++
        return MetaAnswer("Saved it to the library as deck $id:\n" + Untrusted.wrap(from, "“$name”\n$notes"), "Imported #${d.number} to the library")
    }

    private suspend fun field(i: JsonObject): MetaAnswer {
        val tier = (ToolArgs.int(i, "tier") ?: 2).coerceIn(1, 4)
        val format = formatOf(ToolArgs.string(i, "format"))
        val days = (ToolArgs.int(i, "days") ?: 45).coerceIn(7, 365)
        val top = (ToolArgs.int(i, "top") ?: 12).coerceIn(3, 30)
        val (decks, problems, unread) = source.recent(tier, days, format, maxPages = FIELD_PAGES)
        decks.forEach { seen[it.number] = it }
        if (decks.isEmpty()) {
            return if (problems.isNotEmpty()) fail("Could not read YGOPRODeck's tournament decks: ${problems.joinToString("; ")}")
            else MetaAnswer("No ${format.name} results at tier $tier+ in the last $days days.", "No results to build a field from")
        }
        val clusters = FieldBuilder.build(decks, top)
        // The strategies are named from the lists' own names, and the events are the site's: outside text.
        val strategies = buildString {
            clusters.forEachIndexed { n, c ->
                if (n > 0) appendLine()
                appendLine("${n + 1}. ${c.name} — ${c.share}% (${c.decks.size} lists). Representative: #${c.representative.number} (${c.representative.name}, ${c.representative.placement} at ${c.representative.event}).")
                appendLine("   Best: " + c.best.joinToString("; ") { "${it.placement} of ${it.players ?: "?"} at ${it.event}" })
                if (c.core.isNotEmpty()) appendLine("   Core: " + c.core.take(10).joinToString { index.byId(it)?.name ?: it.value.toString() })
            }
        }
        val text = buildString {
            appendLine("What topped in ${format.name} from ${decks.size} tournament decks (tier $tier+, last $days days, YGOPRODeck), by strategy; share is of top cuts, weighted by placement and event size.")
            appendLine(FieldBuilder.SHARE_CAVEAT)
            appendLine(Untrusted.wrap("$SOURCE field", strategies))
            val covered = clusters.sumOf { it.share }
            appendLine()
            append("These ${clusters.size} strategies are $covered% of the weighted top cuts.")
            if (problems.isNotEmpty()) append(" (Some pages failed: ${problems.joinToString("; ")}.)")
            append(cutShort(unread, "a shorter days window reads all of it"))
        }
        return MetaAnswer(text, "Read what topped in ${format.name}: ${clusters.take(3).joinToString { "${it.name} ${it.share}%" }} of top cuts")
    }
}
