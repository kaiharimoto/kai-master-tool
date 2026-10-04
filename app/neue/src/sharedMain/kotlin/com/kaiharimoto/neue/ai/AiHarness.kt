package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MoodTracker
import com.kaiharimoto.mastertool.core.ai.calc.Calc
import com.kaiharimoto.mastertool.core.ai.rules.Wikitext
import com.kaiharimoto.mastertool.core.ai.rules.YgoOrg
import com.kaiharimoto.mastertool.core.ai.rules.Yugipedia
import com.kaiharimoto.mastertool.core.ai.web.HtmlText
import com.kaiharimoto.mastertool.core.ai.web.ReplyCache
import com.kaiharimoto.mastertool.core.ai.web.SearchResults
import com.kaiharimoto.mastertool.core.ai.wire.Unreachable
import com.kaiharimoto.mastertool.core.ai.web.Untrusted
import com.kaiharimoto.mastertool.core.ai.web.UrlGuard
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.hand.CardSetOdds
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.sync.Sha256
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.Platform
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The harness's own tools (1.0.47, kai: "make sure the AI has as many tools as possible,
 * similar to Claude Code … as advanced as DeepSeek Harness, built and tailored for this
 * program"): exact numbers (`calculate`, `hand_odds`), a plan the person can watch
 * (`todo_write`), the web (`web_search`, `web_fetch`), the rules (`rulings`,
 * `archetype_guide`, from Yugipedia, whose text is CC BY-SA and says so — and, for rulings, Konami's
 * own OCG FAQ and Q&A first, from YGOrganization's database, with its OCG caveat), and a helper
 * with a fresh mind for big reading jobs (`delegate`).
 *
 * Every request to the web goes one at a time, names the app in its User-Agent, and keeps
 * what it read for a week in `<data>/ai/cache`, as Yugipedia's API etiquette asks.
 */
internal class AiHarness(private val h: NeueHolders, private val ai: AiState) {
    private val http by lazy { HttpClientFactory.create() }
    private val oneAtATime = Mutex()
    private val cache = File(ai.files.root, "cache")

    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "calculate" -> calculate(ToolArgs.string(i, "expression")!!)
        "hand_odds" -> handOdds(i)
        "todo_write" -> todo(ToolArgs.strings(i, "items"))
        "web_search" -> search(ToolArgs.string(i, "query")!!)
        "web_fetch" -> fetch(ToolArgs.string(i, "url")!!)
        "rulings" -> rulings(ToolArgs.string(i, "card")!!, ToolArgs.string(i, "with"), ToolArgs.string(i, "source"))
        "archetype_guide" -> archetype(ToolArgs.string(i, "archetype")!!, ToolArgs.strings(i, "sections"))
        "delegate" -> delegate(ToolArgs.string(i, "task")!!, ToolArgs.int(i, "steps") ?: 12)
        "express" -> express(ToolArgs.string(i, "face")!!, ToolArgs.int(i, "seconds") ?: 3)
        "watch_video" -> video.watch(ToolArgs.string(i, "url")!!, ToolArgs.string(i, "focus"))
        else -> null
    }

    /** Videos, watched by Gemini (1.0.62). */
    internal val video by lazy { AiVideo(ai) }

    // ---- numbers ---------------------------------------------------------------------

    private fun calculate(expression: String): MetaAnswer = Calc.eval(expression).fold(
        { v ->
            val shown = Calc.format(v)
            val pct = if (v in 0.0..1.0 && v != 0.0 && v != 1.0) " (${Calc.format(v * 100)}%)" else ""
            MetaAnswer("$expression = $shown$pct", "Worked out $expression = $shown$pct")
        },
        { fail("Could not work out “$expression”: ${it.message}") },
    )

    private suspend fun handOdds(i: JsonObject): MetaAnswer {
        val id = ToolArgs.string(i, "deck_id")
        val state = h.builder
        val (name, deck, groups) = if (id == null || id == state.deckId) {
            Triple(state.deckName, state.deck, state.groups)
        } else {
            val s = h.deps.deckRepository.byId(id) ?: return fail("No deck $id. list_decks shows the ids.")
            Triple(s.entry.name, s.entry.deck, DeckGroupsCodec.read(s.extended).groups)
        }
        val main = deck.main
        if (main.isEmpty()) return fail("“$name” has no Main Deck yet.")
        val groupNames = groups.groups.joinToString { it.name }.ifBlank { "it has none" }

        /** A set to count: its words, its cards, and the names that found no card (said, never dropped in silence). */
        class Asked(val label: String, val ids: Set<CardId>, val missing: List<String>)

        /** The set [cards] or [group] names; null when neither is given; a failure in words when it names nothing. */
        fun set(cards: List<String>, group: String?, which: String): Result<Asked?> {
            if (!group.isNullOrBlank()) {
                val g = groups.groups.firstOrNull { it.name.equals(group.trim(), ignoreCase = true) }
                    ?: return Result.failure(IllegalArgumentException("“$name” has no group “${group.trim()}”. Its groups: $groupNames."))
                return Result.success(Asked(g.name, groups.assignments.filterValues { it == g.id }.keys, emptyList()))
            }
            val named = cards.map { it.trim() }.filter { it.isNotEmpty() }
            if (named.isEmpty()) return Result.success(null)
            val found = named.map { it to (CardWords.resolve(it, state.index) as? Resolved.Found)?.card?.id }
            val missing = found.filter { it.second == null }.map { it.first }
            // None of a set found is no question to answer: refused, never worked out over nothing.
            if (missing.size == named.size) {
                return Result.failure(
                    IllegalArgumentException(
                        "Could not find ${if (named.size == 1) "a card named" else "any of"} ${named.joinToString(", ") { "“$it”" }} " +
                            "(the $which). search_cards finds a card's exact name.",
                    ),
                )
            }
            return Result.success(Asked(named.joinToString(", "), found.mapNotNull { it.second }.toSet(), missing))
        }
        val first = set(ToolArgs.strings(i, "cards"), ToolArgs.string(i, "group"), "cards").getOrElse { return fail(it.message!!) }
            ?: return fail("Name the cards that count, or one of the deck's groups: $groupNames.")
        val second = set(ToolArgs.strings(i, "and_cards"), ToolArgs.string(i, "and_group"), "and_cards").getOrElse { return fail(it.message!!) }
        val atLeast = ToolArgs.int(i, "at_least") ?: 1
        val andAtLeast = ToolArgs.int(i, "and_at_least") ?: 1
        // By card, not printing, and exact when the two sets share cards (Phase B): CardSetOdds, through HandCounter.
        val needs = listOfNotNull(CardSetOdds.Need(first.ids, atLeast), second?.let { CardSetOdds.Need(it.ids, andAtLeast) })
        val odds = CardSetOdds.of(main, needs, state.index::byId)
        val turn = ToolArgs.string(i, "turn") ?: "both"
        val lines = buildList {
            if (turn != "second") add("going first (5 cards): ${pct(odds.first)}")
            if (turn != "first") add("going second (6 cards): ${pct(odds.second)}")
        }
        val what = "at least $atLeast of ${first.label} (${odds.copies[0]} in ${main.size})" +
            (
                second?.let {
                    val b = odds.copies[1]
                    " and at least $andAtLeast of ${it.label} " +
                        if (odds.shared == 0) "($b more)" else "($b, ${odds.shared} of them in both sets: a card in both counts for each)"
                } ?: ""
                )
        val missing = first.missing + second?.missing.orEmpty()
        val notFound = if (missing.isEmpty()) "" else "\nNot found, so not counted: ${missing.joinToString(", ") { "“$it”" }}."
        return MetaAnswer("“$name”, $what:\n" + lines.joinToString("\n") + notFound, "Worked out the odds of $what")
    }

    private fun pct(p: Double) = Calc.format(p * 100) + "%"

    // ---- a plan the person can watch ----------------------------------------------------

    private fun todo(items: List<String>): MetaAnswer {
        ai.todos = items.map { it.trim() }.filter { it.isNotEmpty() }.take(20)
        val done = ai.todos.count { it.startsWith("[x]", ignoreCase = true) }
        return MetaAnswer("Plan shown: $done of ${ai.todos.size} done.", "Plan: $done of ${ai.todos.size} done")
    }

    /** A face for a moment, on the avatar beside the chat and in the bar. */
    private fun express(face: String, seconds: Int): MetaAnswer {
        val e = Expression.byId(face)
            ?.takeIf { it in MoodTracker.expressible }
            ?: return fail("No face “$face”: wink, surprised, delighted, love or angry.")
        ai.express(e, seconds)
        return MetaAnswer("Showing ${e.id} for ${seconds.coerceIn(1, 8)} s.", "${e.title} ${e.kaomoji}")
    }

    // ---- the web -------------------------------------------------------------------------

    private val agent = "NeueMasterTool/${Platform.version} (Yu-Gi-Oh! deck builder; https://github.com/kaiharimoto/kai-master-tool)"

    /**
     * For an address Ai chose (`web_fetch`): redirects followed by hand, each checked by
     * [UrlGuard] before it is asked, so a public page cannot bounce the request onto the network.
     */
    private val guarded by lazy { HttpClientFactory.create().config { followRedirects = false } }

    /**
     * A page's body, or why not; a Yugipedia answer kept a week when [keep]. The file is named by
     * the address's SHA-256 (a 32-bit hash let two addresses share a file, and one served the
     * other's page), and an API error — a page that does not exist yet, answered 200 — is never kept.
     * [chosen] is an address Ai chose, read only where [UrlGuard] allows, every redirect too; the
     * app's own addresses (the search engine, Yugipedia) are fixed hosts and go straight.
     * [readable] is how the caller reads the reply (1.0.99): only one it reads is kept, and a kept one it cannot
     * read is thrown away and asked for again ([ReplyCache]); the reply is returned either way, for the caller to fail on.
     */
    private suspend fun get(
        url: String,
        keep: Boolean = false,
        chosen: Boolean = false,
        readable: ((String) -> Boolean)? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = File(cache, Sha256.hex(url) + ".txt")
        if (keep && key.isFile) {
            val kept = runCatching { key.readText() }.getOrNull()
            if (kept != null && ReplyCache.serve(kept, System.currentTimeMillis() - key.lastModified(), readable)) return@withContext Result.success(kept)
            if (kept == null || !ReplyCache.keep(kept, readable)) runCatching { key.delete() }
        }
        oneAtATime.withLock {
            runCatching {
                val headers: HttpRequestBuilder.() -> Unit = {
                    header("User-Agent", agent)
                    header("Accept", "text/html,application/json;q=0.9,*/*;q=0.5")
                }
                val r = if (chosen) UrlGuard.get(guarded, url, block = headers).getOrThrow() else http.get(url, headers)
                val body = r.bodyAsText()
                if (r.status.value !in 200..299) error("${Unreachable.host(url)} answered ${r.status.value} ${r.status.description}")
                if (body.length > MAX_BODY) body.take(MAX_BODY) else body
            }.onSuccess { body -> if (keep && ReplyCache.keep(body, readable)) runCatching { cache.mkdirs(); key.writeText(body) } }
        }
    }

    private suspend fun fetch(url: String): MetaAnswer {
        val u = url.trim()
        // Public https pages only: never this computer, its network, or a cloud's metadata service.
        UrlGuard.refusal(u)?.let { return fail(it) }
        val body = get(u, chosen = true).getOrElse { return fail("Could not read $u. ${Unreachable.of(u, it)}") }
        val text = if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
            HtmlText.truncate(body, 12_000)
        } else {
            HtmlText.text(body)
        }
        val title = HtmlText.title(body)
        return MetaAnswer(Untrusted.wrap(u, text), "Read ${title ?: u.substringAfter("://").substringBefore('/')}")
    }

    private suspend fun search(query: String): MetaAnswer {
        val q = query.trim()
        val ddg = get("https://html.duckduckgo.com/html/?q=" + Yugipedia.title(q).replace("_", "+")).getOrNull()
        var hits = ddg?.takeIf { !SearchResults.blocked(it) }?.let { SearchResults.duckDuckGo(it) }.orEmpty()
        var from = "the web"
        if (hits.isEmpty()) {
            // The search engine would not answer a program: the Yu-Gi-Oh! wiki's own search, which will.
            // The wiki's search wants every word, so a long query is shortened until something answers.
            val words = q.split(' ').filter { it.isNotBlank() }
            for (n in words.size downTo maxOf(1, words.size - 3)) {
                hits = get(Yugipedia.searchUrl(words.take(n).joinToString(" "))).getOrNull()?.let(Yugipedia::searchHits).orEmpty()
                if (hits.isNotEmpty()) break
            }
            from = "Yugipedia (the web search did not answer)"
        }
        if (hits.isEmpty()) return fail("Nothing found for “$q”.")
        // The titles and snippets are whoever wrote the pages': outside text.
        val text = "Results for “$q” from $from:\n" +
            Untrusted.wrap("search results: $from", hits.joinToString("\n") { "- ${it.title} — ${it.url}\n  ${it.snippet}" })
        return MetaAnswer(text, "Searched for “$q”: ${hits.size} results")
    }

    // ---- the rules ------------------------------------------------------------------------

    /** One source's part of a `rulings` answer: its words, a few for the activity line, and whether it read anything. */
    private class Read(val text: String, val summary: String, val ok: Boolean)

    /**
     * A card's rulings from both sources (1.0.98): Konami's OCG FAQ and Q&A from YGOrganization first —
     * the strongest there is, but the OCG's, so its caveat goes with it — then Yugipedia's page, sectioned
     * TCG and OCG. Each in its own envelope, cut to its share so neither end is cut off; if one source
     * fails the other still answers and the failure is said. [source] is "all", "ygorg" or "yugipedia".
     */
    private suspend fun rulings(card: String, with: String?, source: String?): MetaAnswer {
        val name = (CardWords.resolve(card, h.builder.index) as? Resolved.Found)?.card?.name ?: card.trim()
        val which = source?.trim()?.lowercase().orEmpty()
        val org = which != "yugipedia"
        val wiki = which != "ygorg"
        val both = org && wiki
        val reads = listOfNotNull(
            if (org) ygoOrgRulings(name, card.trim(), with?.trim()?.ifEmpty { null }, if (both) 7_500 else 13_500) else null,
            if (wiki) yugipediaRulings(name, if (both) 6_000 else 13_500) else null,
        )
        val text = reads.joinToString("\n\n") { it.text }
        if (reads.none { it.ok }) return fail(text)
        return MetaAnswer(text, reads.filter { it.ok }.joinToString("; ") { it.summary })
    }

    /** The parsed name index, kept while its cached body is the same (it is read from the week's cache each time). */
    @Volatile
    private var ygoIndex: Pair<String, YgoOrg.Index>? = null

    private suspend fun ygoIndex(): Result<YgoOrg.Index> {
        // Read once: the cache's check of the reply is the parse the answer uses.
        var parsed: Pair<String, Result<YgoOrg.Index>>? = null
        val readable = { b: String -> ygoIndex?.first == b || YgoOrg.index(b).also { parsed = b to it }.isSuccess }
        val body = get(YgoOrg.INDEX_URL, keep = true, readable = readable).getOrElse { return Result.failure(it) }
        ygoIndex?.let { (seen, index) -> if (seen == body) return Result.success(index) }
        val read = parsed?.takeIf { it.first == body }?.second ?: YgoOrg.index(body)
        return read.onSuccess { ygoIndex = body to it }
    }

    /**
     * Konami's OCG documentation for one card, as YGOrganization's database has it: the name index
     * (kept a week), the card, and at most [YgoOrg.MAX_QAS] of its Q&As, the newest — only those it
     * shares with [with] when that names a card. Only what the question needs, as the site asks.
     */
    private suspend fun ygoOrgRulings(name: String, typed: String, with: String?, budget: Int): Read {
        val gone = { why: String -> Read("YGOrganization (Konami's OCG rulings): $why", "", ok = false) }
        val index = ygoIndex().getOrElse { return gone("could not read its card index. ${Unreachable.of(YgoOrg.INDEX_URL, it)}") }
        val id = index.id(name) ?: index.id(typed) ?: return gone("its database has no card named “$name”.")
        val url = YgoOrg.cardUrl(id)
        val card = get(url, keep = true, readable = CARD_READS).mapCatching { YgoOrg.card(it).getOrThrow() }
            .getOrElse { return gone("could not read “$name”. ${Unreachable.of(url, it)}") }
        val pool = h.builder.index
        // A card is named as the app names it where it can be (the index also holds old translations).
        val prefer = { n: String -> pool.byName(n) != null }
        val named = { ref: Int -> if (ref == card.id && card.name != null) card.name else index.name(ref, prefer) }
        // Another card named: only the Q&As both cards are in, read off that card's own list.
        var partner: String? = null
        var shared: Set<Int>? = null
        var note = ""
        if (with != null) {
            val otherName = (CardWords.resolve(with, pool) as? Resolved.Found)?.card?.name ?: with
            val other = index.id(otherName) ?: index.id(with)
            val otherCard = other?.let { o -> get(YgoOrg.cardUrl(o), keep = true, readable = CARD_READS).getOrNull()?.let { YgoOrg.card(it).getOrNull() } }
            if (otherCard != null) {
                partner = otherCard.name ?: otherName
                shared = otherCard.qaIds.toSet()
            } else {
                note = "(“$with” is not a card YGOrganization's database could find, so the newest Q&As are given instead.)\n"
            }
        }
        val ids = YgoOrg.pick(card.qaIds, shared?.toList())
        val total = shared?.let { s -> card.qaIds.distinct().count { it in s } } ?: card.qaIds.distinct().size
        var unread = 0
        val qas = ids.mapNotNull { q ->
            get(YgoOrg.qaUrl(q), keep = true, readable = QA_READS).mapCatching { YgoOrg.qa(it).getOrThrow() }.getOrElse {
                unread++
                null
            }
        }
        val body = YgoOrg.text(card, qas, total, named, partner) +
            (if (unread > 0) "\n($unread Q&As could not be read just now.)" else "")
        // Translations and their notes are written by people outside the app: outside text, in the envelope.
        val text = "Official rulings (Konami's OCG FAQ and Q&A) for “$name”, from YGOrganization:\n" + note +
            YgoOrg.CAVEAT + "\n" +
            Untrusted.wrap("YGOrganization: $name", HtmlText.truncate(body, budget)) + "\n" +
            YgoOrg.ATTRIBUTION
        val shown = qas.count { it.status.shown }
        return Read(text, "Read Konami's OCG notes and $shown Q&As for $name", ok = true)
    }

    private suspend fun yugipediaRulings(name: String, budget: Int): Read {
        val gone = { why: String -> Read("Yugipedia: $why", "", ok = false) }
        val url = Yugipedia.rulingsUrl(name)
        val json = get(url, keep = true, readable = WIKITEXT_READS).getOrElse { return gone("could not read its rulings for “$name”. ${Unreachable.of(url, it)}") }
        val wikitext = Yugipedia.wikitextOf(json) ?: return gone("it has no rulings page for “$name”.")
        val list = Wikitext.rulings(wikitext)
        if (list.isEmpty()) return Read("Yugipedia lists no rulings for “$name”.\n${Yugipedia.ATTRIBUTION}", "no Yugipedia rulings", ok = true)
        // A wiki anyone can edit: the rulings go in the envelope, cut to size first so its end is never cut off.
        // Each with the headings it stands under (TCG and OCG rulings can differ) and its source.
        val read = buildString {
            list.take(40).forEach { appendLine(it.line()) }
            if (list.size > 40) appendLine("(${list.size - 40} more on the page)")
        }
        val text = "Yugipedia's rulings for “$name”, each with its section (TCG or OCG) and source:\n" +
            Untrusted.wrap("Yugipedia rulings: $name", HtmlText.truncate(read.trimEnd(), budget)) + "\n" +
            Yugipedia.ATTRIBUTION
        return Read(text, "${list.size} Yugipedia rulings for $name", ok = true)
    }

    private suspend fun archetype(name: String, wanted: List<String>): MetaAnswer {
        val page = name.trim()
        val url = Yugipedia.sectionsUrl(page)
        val sections = get(url, keep = true, readable = SECTIONS_READS).getOrElse { return fail("Could not read Yugipedia's page on “$page”. ${Unreachable.of(url, it)}") }
        val want = wanted.ifEmpty { listOf("Playing style", "Combo", "Recommended", "Weakness") }
        val found = Yugipedia.sectionIndex(sections, want)
        if (found.isEmpty()) return fail("Yugipedia has no ${want.joinToString("/")} sections for “$page” (is it the archetype's exact name?).")
        // A wiki anyone can edit: the sections go in the envelope, cut to size first so its end is never cut off.
        val read = buildString {
            found.take(6).forEach { (index, title) ->
                val body = get(Yugipedia.parseUrl(page, index), keep = true, readable = WIKITEXT_READS).getOrNull()?.let(Yugipedia::wikitextOf)?.let(Wikitext::plain)
                if (!body.isNullOrBlank()) {
                    appendLine("## $title")
                    appendLine(HtmlText.truncate(body.trim(), 5_000))
                    appendLine()
                }
            }
        }
        val text = "“$page” on Yugipedia:\n" +
            Untrusted.wrap("Yugipedia: $page", HtmlText.truncate(read.trimEnd(), 15_500)) + "\n" +
            Yugipedia.ATTRIBUTION
        return MetaAnswer(text, "Read how $page plays: ${found.joinToString { it.second }}")
    }

    // ---- a helper with a fresh mind --------------------------------------------------------

    private suspend fun delegate(task: String, steps: Int): MetaAnswer {
        val report = ai.delegate(task, steps.coerceIn(2, 30)).getOrElse { return fail(it.message ?: "The helper could not start.") }
        return MetaAnswer(report, "A helper read it through: ${task.take(60)}${if (task.length > 60) "…" else ""}")
    }

    private companion object {
        // How each rulings reply is read: only one that reads is kept for the week (1.0.99, ReplyCache).
        val CARD_READS: (String) -> Boolean = { YgoOrg.card(it).isSuccess }
        val QA_READS: (String) -> Boolean = { YgoOrg.qa(it).isSuccess }
        val WIKITEXT_READS: (String) -> Boolean = { Yugipedia.wikitextOf(it) != null }
        val SECTIONS_READS: (String) -> Boolean = Yugipedia::hasSections
        const val MAX_BODY = 2_000_000
    }
}
