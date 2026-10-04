package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.meta.DeckAnalysis
import com.kaiharimoto.mastertool.core.ai.meta.FieldBuilder
import com.kaiharimoto.mastertool.core.ai.meta.FieldLegality
import com.kaiharimoto.mastertool.core.ai.wire.Unreachable
import com.kaiharimoto.mastertool.core.ai.web.Untrusted
import com.kaiharimoto.mastertool.core.cards.BanlistMatch
import com.kaiharimoto.mastertool.core.cards.BanlistWords
import com.kaiharimoto.mastertool.core.cards.LimitationList
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Format
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
import java.time.LocalDate
import kotlin.coroutines.resume

/** What a meta tool answered: for the model, for the chat, and whether it failed. */
internal class MetaAnswer(val content: String, val summary: String, val isError: Boolean = false)

/**
 * Ai's reading of the meta (phase 2): YGOPRODeck's tournament decks, the field built
 * from them, and a deck's shape in numbers. Every list fetched this session is kept
 * by its number, so a list named in one answer can be read or imported in the next.
 */
internal class AiMeta(private val h: NeueHolders, private val ai: AiState) {
    private val ygoProDeck by lazy {
        YgoProDeckDecks(HttpClientFactory.create(), userAgent = "NeueMasterTool/${Platform.version}", clock = System::currentTimeMillis)
    }
    private val source: YgoProDeckDecks get() = ai.tournaments ?: ygoProDeck
    private val bans by lazy { AiBanlist(h) }
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

    /**
     * A past format (1.1.1, Phase B): the day the window ends, and the list in force then in the lists' region (none for
     * Genesys, which has no Forbidden & Limited list).
     */
    private class AsOf(val day: String, val region: Format?, val list: LimitationList?, val match: BanlistMatch?, val note: String?)

    /** The `as_of` asked for, its region's list that day; null when none was asked; a failure in words. */
    private suspend fun asOf(i: JsonObject, format: DeckFormat): Result<AsOf?> {
        val day = ToolArgs.string(i, "as_of")?.trim()?.ifEmpty { null } ?: return Result.success(null)
        fun no(why: String) = Result.failure<AsOf?>(IllegalArgumentException(why))
        if (!Legality.isDate(day)) return no("as_of is a day, yyyy-MM-dd (it was “$day”).")
        if (day > LocalDate.now().toString()) return no("as_of is ${Legality.readable(day)}, after today: there are no results from then yet.")
        val region = FieldLegality.formatOf(format) ?: return Result.success(AsOf(day, null, null, null, null))
        val dated = bans.listOn(region, day)
        val list = dated.list ?: return no(dated.problem ?: "No ${region.name} list for ${Legality.readable(day)}.")
        val match = dated.match ?: return no(dated.problem ?: "The ${list.title} could not be matched to the app's pool.")
        return Result.success(AsOf(day, region, list, match, dated.note))
    }

    /** What was left out for [at]'s day, and the list it was judged by, in words; the lists kept. */
    private fun judged(all: List<TournamentDeck>, at: AsOf): Pair<List<TournamentDeck>, String> {
        val region = at.region
        val list = at.list
        val match = at.match
        if (region == null || list == null || match == null) {
            return all to "Genesys has no Forbidden & Limited list, so the lists are read for the window alone."
        }
        val read = FieldLegality.asOf(all, index::byId, region, at.day, match)
        val unmatched = match.unmatched.size
        val words = listOfNotNull(
            FieldLegality.unreleasedWords(read.unreleased, region, at.day).ifEmpty { null },
            FieldLegality.words(read.dropped, "the ${list.title}").ifEmpty { null },
            "Legal as of ${Legality.readable(at.day)}: the ${list.title} (${BanlistWords.span(list)}) and the cards released " +
                "in the ${Legality.word(region)} by then. ${bans.cite(list)}",
            if (unmatched > 0) "($unmatched ${if (unmatched == 1) "name" else "names"} on that list matched no card in the app's pool, so " +
                "${if (unmatched == 1) "it was" else "they were"} not checked; banlist names them.)" else null,
            at.note,
        )
        return read.kept to words.joinToString("\n")
    }

    /** What reads the part of a past window that was cut: an earlier as_of. */
    private fun earlierWords(read: RecentDecks, fallback: String): String =
        read.earlier()?.takeIf { read.asOf != null }?.let { "ask again with as_of $it to read the days before" } ?: fallback

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
        val at = asOf(i, format).getOrElse { return fail(it.message!!) }
        // A past format is a window ending on its day, found by searching back; page does not apply to it.
        val paged = page > 0 && at == null
        val read = if (paged) {
            val got = (tier..4).flatMap { t ->
                source.page(t, page).getOrElse { return fail("Could not read YGOPRODeck's tournament decks (tier $t, page $page). ${Unreachable.of(YgoProDeckDecks.SITE, it)}") }
            }
            RecentDecks(got.filter { it.format == format && (daysGiven == null || it.daysAgo <= days) }, emptyList())
        } else {
            source.recent(tier, days, format, maxPages = RECENT_PAGES, asOf = at?.day)
        }
        val (read0, problems) = read
        // As of a day, only the lists legal then: that day's list and the cards out by then (Phase B, 1.1.1).
        val (decks, legality) = at?.let { judged(read0, it) } ?: (read0 to "")
        // An older page is read whole, whatever its age, unless days was asked for; a page that fails fails the answer.
        // The window said is the one really read: every tier cut to the same date when one stopped short (Phase B).
        val window = if (paged && daysGiven == null) "page $page of each tier, any age" else read.windowWords(days) + if (paged) ", page $page" else ""
        decks.forEach { seen[it.number] = it }
        val shown = decks.filter { d -> (archetype == null || archetype in d.name.lowercase()) && (event == null || event in d.event.lowercase()) && (pilot == null || PlayerPages.names(pilot, d.pilot)) }
            .sortedBy { RecentDecks.ageOf(it, LocalDate.now().toEpochDay()) }
        val cut = read.cutWords(days, earlierWords(read, "ask again with page $RECENT_PAGES and up to read further back")) + read.endsWords()
        val ignored = if (page > 0 && at != null) " (page is not used with as_of: an earlier as_of reads further back.)" else ""
        if (shown.isEmpty()) {
            val why = if (problems.isNotEmpty()) " YGOPRODeck: ${problems.joinToString("; ")}." else ""
            return if (problems.isNotEmpty() && read0.isEmpty()) fail("Could not read YGOPRODeck's tournament decks.$why")
            else MetaAnswer(
                "No ${format.name} tournament decks match (tier $tier+, $window).$why$cut$ignored" +
                    (if (legality.isNotEmpty()) "\n$legality" else "") +
                    if (pilot != null) " These are only the recent pages; ygopro_player reads $pilot's whole record." else "",
                "No matching tournament decks",
            )
        }
        val head = "${shown.size} ${format.name} tournament decks, tier $tier and up, $window (YGOPRODeck):"
        return MetaAnswer(
            head + "\n" + Untrusted.wrap(SOURCE, shown.take(60).joinToString("\n") { line(it) }) +
                (if (shown.size > 60) "\n(${shown.size - 60} more not shown: narrow with archetype, event or days.)" else "") +
                (if (legality.isNotEmpty()) "\n$legality" else "") +
                (if (problems.isNotEmpty()) "\n(Some pages failed: ${problems.joinToString("; ")})" else "") +
                (if (cut.isNotEmpty()) "\n(${cut.trim()})" else "") + ignored,
            "Read ${shown.size} tournament decks from YGOPRODeck" + (at?.let { " as of ${Legality.readable(it.day)}" } ?: ""),
        )
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
        val at = asOf(i, format).getOrElse { return fail(it.message!!) }
        val read = source.recent(tier, days, format, maxPages = FIELD_PAGES, asOf = at?.day)
        val (all, problems) = read
        all.forEach { seen[it.number] = it }
        val window = read.windowWords(days)
        val cut = read.cutWords(days, earlierWords(read, "a shorter days window reads all of it")) + read.endsWords()
        if (all.isEmpty()) {
            return if (problems.isNotEmpty()) fail("Could not read YGOPRODeck's tournament decks: ${problems.joinToString("; ")}")
            else MetaAnswer("No ${format.name} results at tier $tier+ in the $window.$cut", "No results to build a field from")
        }
        // Lists the banlist does not allow are not the field (Phase B): dropped, and said. Genesys has no list. As of a
        // past day (1.1.1), that day's list, and lists holding cards not out yet set aside first.
        val (decks, dropped) = if (at != null) judged(all, at) else {
            val legal = FieldLegality.formatOf(format)?.let { f -> FieldLegality.check(all, index::byId, f) } ?: FieldLegality.Reading(all, emptyList())
            legal.kept to FieldLegality.words(legal.dropped, "today's ${format.name} Forbidden & Limited list")
        }
        if (decks.isEmpty()) {
            val list = at?.list?.let { "the ${it.title}" } ?: "today's list"
            return MetaAnswer("Every ${format.name} list read at tier $tier+ in the $window is illegal under $list. $dropped", "No legal lists to build a field from")
        }
        val clusters = FieldBuilder.build(decks, top, index::byId)
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
            appendLine("What topped in ${format.name} from ${decks.size} tournament decks (tier $tier+, $window, YGOPRODeck), by strategy; share is of top cuts, weighted by placement and event size.")
            if (dropped.isNotEmpty()) appendLine(dropped)
            appendLine(FieldBuilder.SHARE_CAVEAT)
            appendLine(Untrusted.wrap("$SOURCE field", strategies))
            val covered = clusters.sumOf { it.share }
            appendLine()
            append("These ${clusters.size} strategies are $covered% of the weighted top cuts.")
            if (problems.isNotEmpty()) append(" (Some pages failed: ${problems.joinToString("; ")}.)")
            append(cut)
        }
        val asOfWords = at?.let { " as of ${Legality.readable(it.day)}" }.orEmpty()
        return MetaAnswer(text, "Read what topped in ${format.name}$asOfWords: ${clusters.take(3).joinToString { "${it.name} ${it.share}%" }} of top cuts")
    }
}
