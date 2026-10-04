package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MoodTracker
import com.kaiharimoto.mastertool.core.ai.calc.Calc
import com.kaiharimoto.mastertool.core.ai.rules.Wikitext
import com.kaiharimoto.mastertool.core.ai.rules.Yugipedia
import com.kaiharimoto.mastertool.core.ai.web.HtmlText
import com.kaiharimoto.mastertool.core.ai.web.SearchResults
import com.kaiharimoto.mastertool.core.ai.wire.Unreachable
import com.kaiharimoto.mastertool.core.ai.web.Untrusted
import com.kaiharimoto.mastertool.core.ai.web.UrlGuard
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.hand.HandConstraint
import com.kaiharimoto.mastertool.core.hand.HandOdds
import com.kaiharimoto.mastertool.core.hand.HandQuery
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
 * `archetype_guide`, from Yugipedia, whose text is CC BY-SA and says so), and a helper
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
        "rulings" -> rulings(ToolArgs.string(i, "card")!!)
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
        fun set(cards: List<String>, group: String?): Pair<String, Set<CardId>>? {
            if (!group.isNullOrBlank()) {
                val g = groups.groups.firstOrNull { it.name.equals(group.trim(), ignoreCase = true) } ?: return null
                return g.name to groups.assignments.filterValues { it == g.id }.keys
            }
            if (cards.isEmpty()) return null
            val ids = cards.mapNotNull { (CardWords.resolve(it, state.index) as? Resolved.Found)?.card?.id }.toSet()
            return cards.joinToString(", ") to ids
        }
        val first = set(ToolArgs.strings(i, "cards"), ToolArgs.string(i, "group"))
            ?: return fail("Name the cards that count, or one of the deck's groups: ${groups.groups.joinToString { it.name }.ifBlank { "it has none" }}.")
        val second = set(ToolArgs.strings(i, "and_cards"), ToolArgs.string(i, "and_group"))
        val atLeast = ToolArgs.int(i, "at_least") ?: 1
        val andAtLeast = ToolArgs.int(i, "and_at_least") ?: 1
        fun copies(ids: Set<CardId>) = main.count { it in ids }
        val sizes = buildMap {
            put("a", copies(first.second))
            if (second != null) put("b", copies(second.second - first.second))
        }
        val query = HandQuery(
            buildList {
                add(HandConstraint("a", atLeast, 60))
                if (second != null) add(HandConstraint("b", andAtLeast, 60))
            },
        )
        val turn = ToolArgs.string(i, "turn") ?: "both"
        val lines = buildList {
            if (turn != "second") add("going first (5 cards): ${pct(HandOdds.probability(sizes, main.size, 5, query))}")
            if (turn != "first") add("going second (6 cards): ${pct(HandOdds.probability(sizes, main.size, 6, query))}")
        }
        val what = "at least $atLeast of ${first.first} (${sizes["a"]} in ${main.size})" +
            (second?.let { " and at least $andAtLeast of ${it.first} (${sizes["b"]} more)" } ?: "")
        return MetaAnswer("“$name”, $what:\n" + lines.joinToString("\n"), "Worked out the odds of $what")
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
     */
    private suspend fun get(url: String, keep: Boolean = false, chosen: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val key = File(cache, Sha256.hex(url) + ".txt")
        if (keep && key.isFile && System.currentTimeMillis() - key.lastModified() < WEEK) {
            return@withContext Result.success(key.readText())
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
            }.onSuccess { body -> if (keep && !Yugipedia.isError(body)) runCatching { cache.mkdirs(); key.writeText(body) } }
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

    private suspend fun rulings(card: String): MetaAnswer {
        val name = (CardWords.resolve(card, h.builder.index) as? Resolved.Found)?.card?.name ?: card.trim()
        val url = Yugipedia.rulingsUrl(name)
        val json = get(url, keep = true).getOrElse { return fail("Could not read Yugipedia's rulings for “$name”. ${Unreachable.of(url, it)}") }
        val wikitext = Yugipedia.wikitextOf(json) ?: return fail("Yugipedia has no rulings page for “$name”.")
        val list = Wikitext.rulings(wikitext)
        if (list.isEmpty()) return MetaAnswer("No rulings listed for “$name”.\n${Yugipedia.ATTRIBUTION}", "No rulings for $name")
        // A wiki anyone can edit: the rulings go in the envelope, cut to size first so its end is never cut off.
        // Each with the headings it stands under (TCG and OCG rulings can differ) and its source.
        val read = buildString {
            list.take(40).forEach { appendLine(it.line()) }
            if (list.size > 40) appendLine("(${list.size - 40} more on the page)")
        }
        val text = "Rulings for “$name”, each with its section and source:\n" +
            Untrusted.wrap("Yugipedia rulings: $name", HtmlText.truncate(read.trimEnd(), 13_500)) + "\n" +
            Yugipedia.ATTRIBUTION
        return MetaAnswer(text, "Read ${list.size} rulings for $name")
    }

    private suspend fun archetype(name: String, wanted: List<String>): MetaAnswer {
        val page = name.trim()
        val url = Yugipedia.sectionsUrl(page)
        val sections = get(url, keep = true).getOrElse { return fail("Could not read Yugipedia's page on “$page”. ${Unreachable.of(url, it)}") }
        val want = wanted.ifEmpty { listOf("Playing style", "Combo", "Recommended", "Weakness") }
        val found = Yugipedia.sectionIndex(sections, want)
        if (found.isEmpty()) return fail("Yugipedia has no ${want.joinToString("/")} sections for “$page” (is it the archetype's exact name?).")
        // A wiki anyone can edit: the sections go in the envelope, cut to size first so its end is never cut off.
        val read = buildString {
            found.take(6).forEach { (index, title) ->
                val body = get(Yugipedia.parseUrl(page, index), keep = true).getOrNull()?.let(Yugipedia::wikitextOf)?.let(Wikitext::plain)
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
        const val WEEK = 7L * 24 * 60 * 60 * 1000
        const val MAX_BODY = 2_000_000
    }
}
