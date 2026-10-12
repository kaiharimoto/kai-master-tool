package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ToolTier
import com.kaiharimoto.mastertool.core.ai.ToolTiers
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalScore
import com.kaiharimoto.mastertool.core.ai.report.book.BookNotes
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesExpect
import com.kaiharimoto.mastertool.core.ai.proposals.Expect
import com.kaiharimoto.mastertool.core.ai.proposals.Proposal
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalOp
import com.kaiharimoto.mastertool.core.ai.proposals.Proposals
import com.kaiharimoto.mastertool.core.deck.DeckDependents
import com.kaiharimoto.mastertool.core.prefs.TierPrefs
import com.kaiharimoto.mastertool.core.world.Instruments
import com.kaiharimoto.mastertool.core.world.WorldCodec
import androidx.compose.runtime.snapshotFlow
import com.kaiharimoto.neue.versions.forgetVersions
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import com.kaiharimoto.mastertool.core.deck.DeckVersion
import com.kaiharimoto.mastertool.core.deck.DeckVersions
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.prep.MatchupLedger
import com.kaiharimoto.neue.prep.ledger
import com.kaiharimoto.neue.prep.ledgerWords
import com.kaiharimoto.mastertool.core.search.CardLikeness
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiSettings
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.ai.avatar.MoodTracker
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.MemoryScope
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiStream
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.deck.DeckEdit
import com.kaiharimoto.mastertool.core.deck.DeckEditor
import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.deck.DeckValidator
import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.deck.PlayChoice
import com.kaiharimoto.mastertool.core.deck.RejectionReason
import com.kaiharimoto.mastertool.core.duel.ai.Secrets
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.library.StartingDeck
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.search.CardFilter
import com.kaiharimoto.mastertool.core.search.CardSort
import com.kaiharimoto.mastertool.core.search.EffectKind
import com.kaiharimoto.mastertool.core.search.EffectKinds
import com.kaiharimoto.mastertool.core.search.SearchScope
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.ydk.DeckCodes
import com.kaiharimoto.mastertool.core.ydk.DeckExportFormat
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.ai.course.StudyRun
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.run
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The deck's and the app's own tools (Phase I, I.5): looking at the app and the decks, editing, saving, importing and
 * exporting, the settings, the pages, asking the person — what [AiHost] answered itself until then, beside the groups it
 * hands every other tool to. Over the same state the person's own clicks change ([NeueHolders]), on the main thread.
 */
internal class AiDeckTools(private val h: NeueHolders, private val ai: AiState, private val banTools: AiBanlist) {
    private val state get() = h.builder
    private val neue get() = h.neue
    private val webs get() = h.webs
    private val index get() = state.index

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun ok(content: JsonElement, summary: String) = MetaAnswer(content.toString(), summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    /** [name] answered, or null when it is not one of these. */
    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "app_state" -> appState()
        "list_decks" -> listDecks(ToolArgs.string(i, "query"))
        "get_deck" -> getDeck(ToolArgs.string(i, "deck_id"))
        "validate_deck" -> validate(ToolArgs.string(i, "deck_id"), ToolArgs.string(i, "format"), ToolArgs.string(i, "as_of"))
        "get_settings" -> ok(AiSettings.describe(neue.prefs, h.play.label, state.searchEffects), "Read the settings")
        "search_cards" -> searchCards(i)
        "similar_cards" -> similarCards(i)
        "deck_propose" -> deckPropose(i)
        "proposals_scored" -> h.proposalScore().let { sc -> ok(ProposalScore.words(sc), "Read ${sc.scored.size} suggestions scored against the games since") }
        "run_instrument" -> runInstrument(i)
        "compare_versions" -> compareVersions(i)
        "card_info" -> cardInfo(ToolArgs.strings(i, "cards"))
        "show_in_pool" -> showInPool(i)
        "open_deck" -> openDeck(ToolArgs.string(i, "deck_id")!!)
        "new_deck" -> newDeck(ToolArgs.string(i, "name")!!, ToolArgs.strings(i, "main"), ToolArgs.strings(i, "extra"), ToolArgs.strings(i, "side"))
        "edit_deck" -> editDeck(ToolArgs.objects(i, "ops"))
        "set_groups" -> setGroups(ToolArgs.objects(i, "groups"), ToolArgs.bool(i, "replace") ?: false, ToolArgs.bool(i, "show") ?: true)
        "rename_deck" -> ToolArgs.string(i, "name")!!.let { state.rename(it); ok("Renamed to “$it”.", "Renamed the deck “$it”") }
        "save_deck" -> save().let { ok("Saved as $it.", "Saved “${state.deckName}”") }
        "undo" -> undo(ToolArgs.int(i, "steps") ?: 1, ToolArgs.bool(i, "redo") == true)
        "import_deck" -> importDeck(ToolArgs.string(i, "text")!!, ToolArgs.string(i, "name"))
        "export_deck" -> exportDeck(ToolArgs.string(i, "format")!!)
        "delete_deck" -> deleteDeck(ToolArgs.string(i, "deck_id")!!)
        "navigate" -> navigate(ToolArgs.string(i, "page")!!)
        "run_action" -> runAction(ToolArgs.string(i, "action")!!)
        "set_setting" -> setSetting(ToolArgs.string(i, "key")!!, ToolArgs.element(i, "value") ?: JsonNull)
        "context_status" -> ok(ai.contextReport(), "Checked how full its memory is")
        "compact" -> {
            ai.compactNow(ToolArgs.string(i, "focus"))
            ok("The start of this conversation will be summarised as soon as this answer is done.", "Asked to summarise the start of the conversation")
        }
        "ask_user" -> askUser(ToolArgs.string(i, "question")!!, ToolArgs.strings(i, "options"), ToolArgs.bool(i, "multiple") ?: false, ToolArgs.strings(i, "cards"), ToolArgs.strings(i, "heard"))
        else -> null
    }

    // ---- looking ---------------------------------------------------------------

    private fun appState(): MetaAnswer = ok(buildJsonObject {
        put("page", neue.page.name)
        put("device", if (neue.phone) "phone" else if (neue.touchFirst) "tablet" else "desktop")
        put("format", state.format.name)
        put("open_deck", deckJson(state.deckId, state.deckName, state.deck, state.groups, open = true))
        neue.selection?.let { put("selected_card", it.card.name) }
        put("webs", buildJsonArray { webs.library.webs.forEach { add(JsonPrimitive("${it.name} (id ${it.id}, ${it.entries.size} decks)")) } })
        put("card_pool", "${index.size} cards")
    }, "Looked at the app")

    private suspend fun listDecks(query: String?): MetaAnswer {
        val decks = h.deps.deckRepository.all().sortedByDescending { it.entry.updatedAtEpochMs }
        val q = query?.lowercase()
        val shown = decks.filter { d ->
            q == null || d.entry.name.lowercase().contains(q) ||
                d.entry.deck.main.plus(d.entry.deck.extra).plus(d.entry.deck.side).distinct().any { index.byId(it)?.name?.lowercase()?.contains(q) == true }
        }
        val lines = shown.map { d ->
            val web = webs.webOf(d.entry.id)
            "${d.entry.id} | ${d.entry.name} | ${d.entry.deck.main.size}/${d.entry.deck.extra.size}/${d.entry.deck.side.size}" +
                (web?.let { " | web: ${it.name}" } ?: "") + (if (d.entry.id == state.deckId) " | open" else "")
        }
        return ok(
            if (lines.isEmpty()) "No decks${q?.let { " matching “$it”" } ?: ""}." else "id | name | main/extra/side\n" + lines.joinToString("\n"),
            "Listed ${lines.size} decks",
        )
    }

    private fun counted(ids: List<CardId>): JsonArray {
        val counts = LinkedHashMap<CardId, Int>()
        ids.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return buildJsonArray { counts.forEach { (id, n) -> add(JsonPrimitive("$n ${index.byId(id)?.name ?: "unknown card"} (${id.value})")) } }
    }

    private fun deckJson(id: String?, name: String, deck: Deck, groups: DeckGroups, open: Boolean): JsonObject = buildJsonObject {
        put("id", id ?: "unsaved")
        put("name", name)
        if (open) put("open", true)
        if (open && state.dirty) put("unsaved_changes", true)
        // Checked as the builder checks: the day and list chosen, or Genesys (the 1.1.2 design review, finding 1).
        val rules = state.rulesInForce
        val v = if (open) state.validation else rules.validate(deck, index::byId, state.today)
        put("legal_${state.format.name}", v.isLegal)
        if (!rules.isDefault) put("checked_against", rules.words())
        if (v.issues.isNotEmpty()) put("issues", buildJsonArray { v.issues.forEach { add(JsonPrimitive(it.message)) } })
        put("main", counted(deck.main))
        put("extra", counted(deck.extra))
        put("side", counted(deck.side))
        webs.webOf(id)?.let { put("web", "${it.name} (id ${it.id})") }
        if (groups.groups.isNotEmpty()) {
            putJsonArray("groups") {
                groups.ordered().forEach { g ->
                    add(buildJsonObject {
                        put("name", g.name)
                        put("cards", buildJsonArray {
                            groups.assignments.filterValues { it == g.id }.keys.mapNotNull { index.byId(it)?.name }.sorted().forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            }
        }
    }

    private suspend fun stored(id: String): StoredDeck? = h.deps.deckRepository.byId(id)

    private suspend fun getDeck(id: String?): MetaAnswer {
        if (id == null || id == state.deckId) return ok(deckJson(state.deckId, state.deckName, state.deck, state.groups, open = true), "Read “${state.deckName}”")
        val s = stored(id) ?: return fail("No deck $id. list_decks shows the ids.")
        return ok(deckJson(id, s.entry.name, s.entry.deck, DeckGroupsCodec.read(s.extended).groups, open = false), "Read “${s.entry.name}”")
    }

    private suspend fun deckOf(id: String?): Pair<String, Deck>? =
        if (id == null || id == state.deckId) state.deckName to state.deck else stored(id)?.let { it.entry.name to it.entry.deck }

    private suspend fun validate(id: String?, format: String?, asOf: String?): MetaAnswer {
        val (name, deck) = deckOf(id) ?: return fail("No deck $id.")
        val f = format?.let { runCatching { Format.valueOf(it.uppercase()) }.getOrNull() } ?: state.format
        val day = asOf?.trim()?.ifEmpty { null }
        if (day == null && format == null && (id == null || id == state.deckId)) {
            // The open deck, asked plainly: checked as the builder checks it (1.1.1), a chosen day or Genesys included.
            val rules = state.rulesInForce
            val v = state.validation
            val text = if (v.issues.isEmpty()) "“$name” is legal in ${rules.words()}: main ${deck.main.size}, extra ${deck.extra.size}, side ${deck.side.size}." else
                "“$name” in ${rules.words()}:\n" + v.issues.joinToString("\n") { "- ${it.severity.name.lowercase()}: ${it.message}" }
            return ok(text, if (v.isLegal) "“$name” is legal in ${rules.words()}" else "“$name”: ${v.errors.size} problems in ${rules.words()}")
        }
        if (day == null) {
            val v = DeckValidator.validate(deck, index::byId, f)
            val text = if (v.issues.isEmpty()) "“$name” is legal in ${f.name}: main ${deck.main.size}, extra ${deck.extra.size}, side ${deck.side.size}." else
                "“$name” in ${f.name}:\n" + v.issues.joinToString("\n") { "- ${it.severity.name.lowercase()}: ${it.message}" }
            return ok(text, if (v.isLegal) "“$name” is legal in ${f.name}" else "“$name”: ${v.errors.size} problems in ${f.name}")
        }
        // As of a day (1.1.1): that day's Forbidden & Limited list, and the cards released by then.
        val dated = banTools.listOn(f, day)
        val list = dated.list ?: return fail(dated.problem ?: "No ${f.name} list for $day.")
        val v = DeckValidator.validate(deck, index::byId, f, asOf = day, limits = dated.match)
        val on = "${f.name} on ${Legality.readable(day)} (the ${list.title}, from Yugipedia, CC BY-SA)"
        val text = (if (v.issues.isEmpty()) "“$name” is legal in $on: main ${deck.main.size}, extra ${deck.extra.size}, side ${deck.side.size}." else
            "“$name” in $on:\n" + v.issues.joinToString("\n") { "- ${it.severity.name.lowercase()}: ${it.message}" }) +
            (dated.note?.let { "\n$it" } ?: "")
        return ok(text, if (v.isLegal) "“$name” is legal on the ${list.title}" else "“$name”: ${v.errors.size} problems on the ${list.title}")
    }

    /** `similar_cards` (Phase G, G.7): cards like a card, or more for a group's role, only what the rules in force let in. */
    private suspend fun similarCards(i: JsonObject): MetaAnswer {
        val limit = (ToolArgs.int(i, "limit") ?: 12).coerceIn(1, 30)
        val index = state.index
        val deck = state.deck
        val (targets, label) = ToolArgs.string(i, "card")?.let { n ->
            val c = index.byName(n) ?: return fail("No card named “$n”.")
            listOf(c) to c.name
        } ?: ToolArgs.string(i, "group")?.let { n ->
            val g = state.groups.groups.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: return fail("No group called “$n” in “${state.deckName}”.")
            (deck.main + deck.extra + deck.side).filter { state.groups.groupOf(it) == g.id }.mapNotNull(index::byId).distinctBy { it.id } to "the group ${g.name}"
        } ?: return fail("Name a card or a group.")
        if (targets.isEmpty()) return fail("That group holds no cards.")
        val rules = state.rulesInForce
        val found = withContext(Dispatchers.Default) {
            val held = (deck.main + deck.extra + deck.side).groupingBy { CardIdentity.canonical(it, index::byId) }.eachCount()
            val freed = targets.singleOrNull()?.takeIf { t -> held.containsKey(t.id) }?.genesysPoints ?: 0
            val left = rules.points(deck, index::byId)?.let { it.cap - it.points + freed }
            CardLikeness.similar(targets, index.cards, CardLikeness.Room(rules, state.today, left, held), limit = limit)
        }
        if (found.isEmpty()) return ok("Nothing like $label that the rules in force let into “${state.deckName}”.", "Nothing alike")
        val text = "Cards like $label, the most alike first (likeness 0–100 by effect kinds, what the card is, archetype and text; " +
            "legal under ${rules.listName ?: "the rules in force"}" + (rules.genesysCap?.let { ", within the Genesys points left of $it" } ?: "") + "):\n" +
            found.joinToString("\n") { l -> "- ${l.card.name} (${(l.score * 100).toInt()})" + (l.card.genesysPoints?.takeIf { rules.genesys }?.let { " · $it points" } ?: "") + " — ${l.card.type}" }
        return ok(text, "Found ${found.size} cards like $label")
    }

    /**
     * `compare_versions` (Phase G, G.8): a deck's versions and the ledger's games at each, or two of them side by side —
     * what changed by card, each one's rate with its range, and the difference with its range.
     */
    private suspend fun compareVersions(i: JsonObject): MetaAnswer {
        val id = ToolArgs.string(i, "deck_id") ?: state.deckId ?: return fail("Name a saved deck (deck_id), or open one in the builder.")
        val s = stored(id) ?: return fail("No deck $id.")
        val index = state.index
        val byId = index::byId.takeIf { index.cards.isNotEmpty() }
        // The deck as it stands is a version too, kept now if a save outside the builder made it.
        val versions = withContext(Dispatchers.IO) {
            h.versions.record(id, s.entry.deck, s.entry.name, s.entry.updatedAtEpochMs, byId)
            h.versions.of(id)
        }
        val numbered = DeckVersions.numbered(versions)
        val now = DeckVersions.print(s.entry.deck, byId)
        val web = h.prep.active?.takeIf { it.deckId == id }?.webId?.let { webs.library.byId(it) } ?: webs.library.webs.firstOrNull { it.entry(id) != null }
        val doc = h.prep.doc
        val counted = if (ToolArgs.bool(i, "all_sources") == true) MatchupLedger.OTHERS else doc.sources
        val read = h.ledger(id, web, doc.copy(sources = counted))
        val by = MatchupLedger.byVersion(read.games.filter { it.round == null }).associateBy { it.print }
        fun name(c: CardId) = index.byId(c)?.name ?: "#${c.value}"
        fun day(at: Long) = IsoDate.of(Math.floorDiv(at, 86_400_000L))
        fun label(v: DeckVersion) = DeckVersions.nameOf(v.print, versions) + (if (v.print == now) " (now)" else "") + ", saved ${day(v.at)}" + (v.label.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        fun rates(print: String?): String = by[print]?.let { b ->
            "${MatchupLedger.rateWords(b.all)}; going first ${MatchupLedger.rateWords(b.first)}, second ${MatchupLedger.rateWords(b.second)}"
        } ?: "no games"
        fun find(word: String?): DeckVersion? {
            val w = word?.trim()?.lowercase() ?: return null
            val at = numbered.indexOfFirst { it.second.print == now }
            return when {
                w == "now" || w == "current" -> numbered.getOrNull(at)?.second
                w == "previous" || w == "before" -> numbered.getOrNull(at - 1)?.second
                w.startsWith("v") && w.drop(1).toIntOrNull() != null -> numbered.firstOrNull { it.first == w.drop(1).toInt() }?.second
                else -> versions.firstOrNull { it.print == word.trim() }
            }
        }
        val from = ToolArgs.string(i, "from")
        val to = ToolArgs.string(i, "to")
        val lineage = h.versions.lineage(id)
        val text = buildString {
            if (from == null && to == null) {
                appendLine("“${s.entry.name}” has ${versions.size} ${if (versions.size == 1) "version" else "versions"} (a new one each time the Main or Extra Deck changes by card). Games: ${ledgerWords(read, counted)}")
                numbered.forEach { (n, v) ->
                    val parent = numbered.firstOrNull { it.second.print == v.parent }?.second
                    val change = parent?.let { DeckVersions.words(DeckVersions.changes(it.deck, v.deck, byId), ::name, most = 6) }
                    appendLine("- ${label(v)}: ${rates(v.print)}" + (change?.let { ". From v${numbered.first { it.second == parent }.first}: $it" } ?: ""))
                }
                by.keys.filter { p -> p == null || versions.none { it.print == p } }.forEach { p -> appendLine("- ${DeckVersions.UNKNOWN} (games from before versions were kept): ${rates(p)}") }
                lineage.firstOrNull()?.let { (deck, print) -> appendLine("Duplicated from deck $deck at its ${DeckVersions.nameOf(print, h.versions.of(deck))}: its games at that list count here too.") }
                append("Name two (from, to) to compare them.")
            } else {
                val a = find(from ?: "previous") ?: return fail("No version “${from ?: "previous"}” of “${s.entry.name}”: it has v1 to v${numbered.size}.")
                val b = find(to ?: "now") ?: return fail("No version “${to ?: "now"}” of “${s.entry.name}”: it has v1 to v${numbered.size}.")
                appendLine("${label(a)} → ${label(b)}: ${DeckVersions.words(DeckVersions.changes(a.deck, b.deck, byId), ::name)}.")
                appendLine("- ${DeckVersions.nameOf(a.print, versions)}: ${rates(a.print)}")
                appendLine("- ${DeckVersions.nameOf(b.print, versions)}: ${rates(b.print)}")
                val ra = by[a.print]
                val rb = by[b.print]
                val d = if (ra != null && rb != null) MatchupLedger.difference(ra.all, rb.all) else null
                append(
                    d?.let { "Game win ${DeckVersions.nameOf(b.print, versions)} against ${DeckVersions.nameOf(a.print, versions)}: ${MatchupLedger.differenceWords(it)} (95% range, two independent sets of games: the opponents faced may differ)." }
                        ?: "Not enough games at both to compare: play the version with none.",
                )
            }
        }
        return ok(text.trim(), "Read the versions of “${s.entry.name}”")
    }

    // ---- cards -----------------------------------------------------------------

    private fun filterOf(i: JsonObject): CardFilter {
        fun <T> set(key: String, parse: (String) -> T?): Set<T> = ToolArgs.strings(i, key).mapNotNull { runCatching { parse(it.trim()) }.getOrNull() }.toSet()
        val types = ToolArgs.strings(i, "types").map { t -> t.trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }.toSet()
        val atk = ToolArgs.int(i, "atk_min") to ToolArgs.int(i, "atk_max")
        val def = ToolArgs.int(i, "def_min") to ToolArgs.int(i, "def_max")
        return CardFilter(
            categories = set("categories") { CardCategory.valueOf(it.uppercase()) },
            attributes = set("attributes") { Attribute.valueOf(it.uppercase()) },
            races = types,
            properties = types,
            levels = ToolArgs.ints(i, "levels").toSet(),
            archetypes = ToolArgs.strings(i, "archetypes").mapNotNull { a -> index.archetypes.firstOrNull { it.equals(a.trim(), ignoreCase = true) } ?: a.trim() }.toSet(),
            banStatuses = set("ban_status") { BanStatus.valueOf(it.uppercase()) },
            atkRange = if (atk.first != null || atk.second != null) (atk.first ?: 0)..(atk.second ?: 99_999) else null,
            defRange = if (def.first != null || def.second != null) (def.first ?: 0)..(def.second ?: 99_999) else null,
            extraDeckOnly = ToolArgs.bool(i, "extra_deck"),
            effects = set("effects") { EffectKind.valueOf(it.uppercase()) },
            format = state.format,
            sort = when (ToolArgs.string(i, "sort")) {
                "points" -> CardSort.POINTS
                "newest" -> CardSort.NEWEST
                "name" -> CardSort.NAME
                else -> CardSort.RELEVANCE
            },
            // The rules in force (Phase G, R2): what can go in the deck, the Genesys points, what is new or coming.
            legalOnly = ToolArgs.bool(i, "legal_only") == true,
            points = (ToolArgs.int(i, "points_min") to ToolArgs.int(i, "points_max")).let { (lo, hi) -> if (lo != null || hi != null) (lo ?: 0)..(hi ?: 999) else null },
            releasedAfter = ToolArgs.string(i, "released_after")?.trim()?.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) },
            notYetInTcg = ToolArgs.bool(i, "not_yet_in_tcg") == true,
        ).let(state::withRules)
    }

    private fun cardLine(c: Card, full: Boolean = false): String = buildString {
        append(c.name).append(" (").append(c.id.value).append(") — ").append(c.type)
        if (c.category == CardCategory.MONSTER) {
            append("; ").append(c.attribute.name).append(' ').append(c.race.orEmpty())
            c.level?.let { append(if (c.frameType.contains("xyz")) " Rank " else " Level ").append(it) }
            c.linkValue?.let { append(" Link-").append(it) }
            append(' ').append(c.atk ?: "?").append('/').append(if (c.linkValue != null) "-" else (c.def ?: "?"))
        } else {
            c.race?.let { append("; ").append(it) }
        }
        c.archetype?.let { append("; archetype ").append(it) }
        val tcg = c.banStatus(Format.TCG)
        val ocg = c.banStatus(Format.OCG)
        if (tcg != BanStatus.UNLIMITED || ocg != BanStatus.UNLIMITED) append("; TCG ").append(tcg.name.lowercase()).append(", OCG ").append(ocg.name.lowercase())
        if (full) {
            val kinds = EffectKinds.of(c)
            if (kinds.isNotEmpty()) append("\nDoes: ").append(kinds.joinToString { it.label })
            if (c.alternateIds.size > 1) append("\nArtworks: ").append(c.alternateIds.size)
                .append(" (passcodes ").append(c.alternateIds.joinToString { it.value.toString() }).append(": one card, every printing counted against its limit)")
            releaseLine(c)?.let { append("\n").append(it) }
            append("\n").append(c.description)
        } else if (c.description.isNotBlank()) {
            append(" — ").append(c.description.replace('\n', ' ').take(170)).append(if (c.description.length > 170) "…" else "")
        }
    }

    /** Where and when it was released, Konami's id and its Genesys points, as the pool has them (Phase B). */
    private fun releaseLine(c: Card): String? {
        val parts = buildList {
            if (c.formats.isNotEmpty()) {
                fun region(f: Format, date: String?) = when {
                    // Not printed there only when Yugipedia agrees with the pool (1.1.1); otherwise said to be unknown.
                    Legality.release(c, f) is Legality.Release.NotReleased -> "not released in the ${Legality.word(f)}"
                    Legality.word(f) !in c.formats -> "${Legality.word(f)} unknown (the sources disagree or have not been read)"
                    date != null -> "${Legality.word(f)} ${Legality.readable(date)}"
                    else -> Legality.word(f)
                }
                add("Released: " + region(Format.TCG, c.tcgDate) + ", " + region(Format.OCG, c.ocgDate))
            }
            c.genesysPoints?.let { add("Genesys $it point${if (it == 1) "" else "s"}") }
            c.konamiId?.let { add("Konami id $it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun searchCards(i: JsonObject): MetaAnswer {
        if (index.size == 0) return fail("The card pool has not loaded yet. Try again in a moment.")
        val query = ToolArgs.string(i, "query").orEmpty()
        val scope = when (ToolArgs.string(i, "scope")) {
            "names" -> SearchScope.NAMES
            "text" -> SearchScope.TEXT
            else -> SearchScope.ALL
        }
        val limit = (ToolArgs.int(i, "limit") ?: 20).coerceIn(1, 60)
        val found = index.search(query, filterOf(i), scope, limit)
        if (found.cards.isEmpty()) return ok("No cards match.", "No cards for “$query”")
        val head = "${found.matchCount} match${if (found.matchCount == 1) "" else "es"}${if (found.truncated) ", first $limit shown" else ""}:"
        return ok(head + "\n" + found.cards.joinToString("\n") { cardLine(it) }, "Found ${found.matchCount} cards" + if (query.isNotBlank()) " for “$query”" else "")
    }

    private fun cardInfo(cards: List<String>): MetaAnswer {
        if (cards.isEmpty()) return fail("Name at least one card.")
        val out = cards.take(12).map { word ->
            when (val r = CardWords.resolve(word, index)) {
                is Resolved.Found -> cardLine(r.card, full = true) + if (r.guessed) "\n(read “$word” as ${r.card.name})" else ""
                is Resolved.Unknown -> "No card “${r.text}”." + if (r.suggestions.isNotEmpty()) " Closest: ${r.suggestions.joinToString()}." else ""
            }
        }
        return ok(out.joinToString("\n\n"), "Read ${cards.take(3).joinToString { CardWords.split(it).text }}${if (cards.size > 3) "…" else ""}")
    }

    private fun showInPool(i: JsonObject): MetaAnswer {
        neue.go(Page.BUILDER)
        neue.update { it.copy(poolVisible = true) }
        if (ToolArgs.bool(i, "clear") == true) {
            state.onQueryChange("")
            state.clearFilters()
            return ok("The pool shows every card.", "Cleared the pool's search")
        }
        state.onQueryChange(ToolArgs.string(i, "query").orEmpty())
        state.onFilterChange(filterOf(i).copy(onlyIds = state.filter.onlyIds, sort = state.filter.sort, rules = null, today = "", banSource = null))
        return ok("The pool shows the search.", "Searched the pool" + (ToolArgs.string(i, "query")?.let { " for “$it”" } ?: ""))
    }

    // ---- building ---------------------------------------------------------------

    private suspend fun waitFor(ms: Long = 5000, done: () -> Boolean): Boolean =
        withTimeoutOrNull(ms) { while (!done()) delay(40); true } ?: false

    /** Saves the open deck; a save that never says it is done is a failure said in words, never a tool that hangs (1.0.98). */
    private suspend fun save(): String = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            state.save(quiet = true) { id ->
                h.decksReload++
                if (cont.isActive) cont.resume(id)
            }
        }
    } ?: error("Saving the deck did not finish: the database did not answer. Nothing may have been saved; try save_deck again.")

    private suspend fun openDeck(id: String): MetaAnswer {
        val s = stored(id) ?: return fail("No deck $id. list_decks shows the ids.")
        h.openDeck(id)
        // Said as it is (1.0.98, the red team): "Opened" only once it is.
        if (!waitFor { state.deckId == id && state.deckName == s.entry.name }) {
            return fail("“${s.entry.name}” did not open in time: the builder still shows “${state.deckName}”. app_state says what is open.")
        }
        return ok("Opened “${s.entry.name}”.", "Opened “${s.entry.name}”")
    }

    private fun sectionOf(word: String?, card: Card): DeckSection = when (word?.lowercase()) {
        "side" -> DeckSection.SIDE
        "extra" -> DeckSection.EXTRA
        "main" -> if (card.isExtraDeck) DeckSection.EXTRA else DeckSection.MAIN
        else -> card.requiredSection()
    }

    private fun why(reason: RejectionReason, card: Card): String = when (reason) {
        RejectionReason.SECTION_FULL -> "that section is full"
        RejectionReason.COPY_LIMIT -> "${card.name} is at its limit (${DeckEditor.copyLimit(card, state.format, state.limits)} in ${state.rulesInForce.short()})"
        RejectionReason.WRONG_SECTION -> if (card.isExtraDeck) "${card.name} belongs in the extra deck" else "${card.name} cannot go in the extra deck"
        RejectionReason.NOT_PLAYABLE -> "${card.name} cannot be put in a deck"
        RejectionReason.NOT_PRESENT -> "${card.name} is not there"
    }

    /** Cards added to [deck] from words, into their sections; what went in and what did not. */
    private fun fill(deck: Deck, words: List<String>, section: DeckSection?, notes: MutableList<String>): Deck {
        var d = deck
        words.forEach { w ->
            when (val r = CardWords.resolve(w, index)) {
                is Resolved.Found -> {
                    if (r.guessed) notes += "Read “${CardWords.split(w).text}” as ${r.card.name}."
                    val target = if (section == DeckSection.SIDE) DeckSection.SIDE else r.card.requiredSection()
                    repeat(r.count.coerceIn(1, 3)) {
                        when (val e = DeckEditor.add(d, r.card, target, state.format, state.limits)) {
                            is DeckEdit.Applied -> d = e.deck
                            is DeckEdit.Rejected -> {
                                notes += "Left out a copy of ${r.card.name}: ${why(e.reason, r.card)}."
                                return@repeat
                            }
                        }
                    }
                }
                is Resolved.Unknown -> notes += "No card “${r.text}”" + (if (r.suggestions.isNotEmpty()) " (closest: ${r.suggestions.take(3).joinToString()})" else "") + "."
            }
        }
        return d
    }

    private suspend fun newDeck(name: String, main: List<String>, extra: List<String>, side: List<String>): MetaAnswer {
        if (index.size == 0) return fail("The card pool has not loaded yet.")
        val notes = mutableListOf<String>()
        var deck = Deck.EMPTY
        deck = fill(deck, main, null, notes)
        deck = fill(deck, extra, null, notes)
        deck = fill(deck, side, DeckSection.SIDE, notes)
        if (h.builder.dirty && (state.deckId != null || !state.deck.isEmpty)) save()
        state.adoptDeck(deck, name)
        neue.go(Page.BUILDER)
        val id = save()
        val v = state.rulesInForce.validate(deck, index::byId, state.today)
        val text = "Made “$name” (id $id): main ${deck.main.size}, extra ${deck.extra.size}, side ${deck.side.size}; " +
            (if (v.isLegal) "legal in ${state.rulesInForce.words()}." else "not legal yet: ${v.errors.joinToString("; ") { it.message }}.") +
            (if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else "")
        return ok(text, "Built “$name” — ${deck.main.size}/${deck.extra.size}/${deck.side.size}")
    }

    /** [ops] applied to [base] in order, as `edit_deck` reads them: the deck after, what was done and what could not be. */
    private class Applied(val deck: Deck, val done: List<String>, val problems: List<String>)

    private fun applyOps(base: Deck, ops: List<JsonObject>): Applied {
        var deck = base
        val done = mutableListOf<String>()
        val problems = mutableListOf<String>()
        ops.forEach { op ->
            val kind = ToolArgs.string(op, "op")
            val word = ToolArgs.string(op, "card")
            if (kind == null || word == null) {
                problems += "An op needs op and card."
                return@forEach
            }
            val r = CardWords.resolve(word, index)
            if (r !is Resolved.Found) {
                problems += "No card “${(r as Resolved.Unknown).text}”" + (if (r.suggestions.isNotEmpty()) " (closest: ${r.suggestions.take(3).joinToString()})" else "") + "."
                return@forEach
            }
            if (r.guessed) problems += "Read “$word” as ${r.card.name}."
            val card = r.card
            val section = sectionOf(ToolArgs.string(op, "section"), card)
            val count = ToolArgs.int(op, "count") ?: if (r.explicit) r.count else null
            when (kind) {
                "add" -> {
                    var added = 0
                    repeat((count ?: 1).coerceIn(1, 3)) {
                        when (val e = DeckEditor.add(deck, card, section, state.format, state.limits)) {
                            is DeckEdit.Applied -> { deck = e.deck; added++ }
                            is DeckEdit.Rejected -> { problems += "Could not add ${card.name}: ${why(e.reason, card)}."; return@repeat }
                        }
                    }
                    if (added > 0) done += "+$added ${card.name}"
                }
                "remove" -> {
                    val have = deck[section].count { it == card.id }
                    val n = (count ?: have).coerceAtMost(have)
                    if (n == 0) problems += "${card.name} is not in the ${section.name.lowercase()} deck."
                    repeat(n) { (DeckEditor.remove(deck, card.id, section) as? DeckEdit.Applied)?.let { deck = it.deck } }
                    if (n > 0) done += "−$n ${card.name}"
                }
                "set" -> when (val e = DeckEditor.setCount(deck, card, section, (count ?: 1).coerceIn(0, 3), state.format, state.limits)) {
                    is DeckEdit.Applied -> { deck = e.deck; done += "${card.name} ×${deck[section].count { it == card.id }}" }
                    is DeckEdit.Rejected -> problems += "Could not set ${card.name}: ${why(e.reason, card)}."
                }
                "move" -> {
                    val to = ToolArgs.string(op, "to_section")?.let { sectionOf(it, card) }
                    if (to == null) { problems += "A move of ${card.name} needs to_section."; return@forEach }
                    val from = ToolArgs.string(op, "section")?.let { sectionOf(it, card) } ?: DeckSection.entries.first { s -> s != to && deck[s].contains(card.id) }
                    var moved = 0
                    repeat((count ?: 1).coerceIn(1, 3)) {
                        when (val e = DeckEditor.move(deck, card, from, to, state.format)) {
                            is DeckEdit.Applied -> { if (e.deck != deck) moved++; deck = e.deck }
                            is DeckEdit.Rejected -> { problems += "Could not move ${card.name}: ${why(e.reason, card)}."; return@repeat }
                        }
                    }
                    if (moved > 0) done += "$moved ${card.name} → ${to.name.lowercase()}"
                }
                else -> problems += "Unknown op $kind."
            }
        }
        return Applied(deck, done, problems)
    }

    private suspend fun editDeck(ops: List<JsonObject>): MetaAnswer {
        if (ops.isEmpty()) return fail("No ops given.")
        val before = state.deck
        val a = applyOps(before, ops)
        val done = a.done
        val deck = a.deck
        state.setCards(deck, if (done.isNotEmpty()) "${ai.name}: ${done.take(3).joinToString(", ")}${if (done.size > 3) "…" else ""}" else null)
        neue.go(Page.BUILDER)
        // What the cut broke (Phase G, G.9; A4): the combos, mapped boards and playbook lines that used a card now gone.
        val broke = h.cutBreaks(state.deckId, before, deck)
        val text = (if (done.isEmpty()) "Nothing changed." else "Done: ${done.joinToString("; ")}. Now main ${deck.main.size}, extra ${deck.extra.size}, side ${deck.side.size}.") +
            (if (a.problems.isNotEmpty()) "\n" + a.problems.joinToString("\n") else "") +
            (if (broke.isNotEmpty()) "\nWhat the cut breaks — check these lines:\n" + DeckDependents.report(broke) else "")
        return MetaAnswer(text, if (done.isEmpty()) "Changed nothing" else done.joinToString(", ").take(140), isError = done.isEmpty() && a.problems.isNotEmpty())
    }

    /**
     * `deck_propose` (Phase G, G.9; A1): the change tried on a copy of the deck (never on it), its claims held to the
     * conversation's numbers ([Evidence.judge]) — a change with no number says (judgment) — and what it breaks named; kept
     * in the deck's book and drawn as a card with Apply and Not now.
     */
    private suspend fun deckPropose(i: JsonObject): MetaAnswer {
        val id = ToolArgs.string(i, "deck_id") ?: state.deckId ?: return fail("Save the deck first, or name one (deck_id): a proposal is kept with its deck.")
        val base = if (id == state.deckId) state.deck else stored(id)?.entry?.deck ?: return fail("No deck $id.")
        val raw = ToolArgs.objects(i, "ops")
        if (raw.isEmpty()) return fail("A proposal needs its ops.")
        val tried = applyOps(base, raw)
        if (tried.done.isEmpty()) return fail("None of the ops would change the deck: " + tried.problems.joinToString(" ").ifBlank { "check the cards and sections." })
        // An expectation measured by simulated matches (G2.2) takes its numbers from the run itself, never from Ai's words.
        val runs = if (ToolArgs.objects(i, "expect").any { ToolArgs.string(it, "measured_by") == Expect.SERIES }) h.series.readAll() else emptyMap()
        // What a run must have measured (R2): the deck as it stands against the deck with these ops, by the prints it was made with.
        val cards = index::byId.takeIf { index.cards.isNotEmpty() }
        val beforePrint = DeckVersions.print(base, cards)
        val afterPrint = DeckVersions.print(tried.deck, cards)
        val measured = ArrayList<Evidence.Source>()
        val expects = ToolArgs.objects(i, "expect").map { e ->
            val kind = ToolArgs.string(e, "measured_by")?.takeIf { it in Expect.KINDS }
            if (kind == Expect.SERIES) {
                val runId = ToolArgs.string(e, "run") ?: return fail("An expectation measured by simulated matches names its run (match_results).")
                val run = runs[runId] ?: return fail("No comparison $runId: match_results lists them.")
                if (run.deck != id) return fail("Comparison $runId is of another deck.")
                val expect = SeriesExpect.forProposal(run, beforePrint, afterPrint)
                    ?: return fail("Not proposed: ${SeriesExpect.refusal(run, beforePrint, afterPrint)}. Measure this change with match_request, or give the expectation without measured_by: series.")
                expect.also { measured += Evidence.Source("match_results", runId, Proposals.expectWords(it)) }
            } else {
                Expect(ToolArgs.string(e, "metric").orEmpty(), ToolArgs.double(e, "before"), ToolArgs.double(e, "after"), ToolArgs.double(e, "low"), ToolArgs.double(e, "high"), ToolArgs.string(e, "unit") ?: "%", kind = kind)
            }
        }
        val p = Proposal(
            id = "prop-" + System.currentTimeMillis().toString(36) + "-" + (0..0xfff).random().toString(36),
            deckId = id,
            title = ToolArgs.string(i, "title").orEmpty().ifBlank { tried.done.take(2).joinToString(", ") },
            ops = raw.map { o -> ProposalOp(ToolArgs.string(o, "op").orEmpty(), ToolArgs.string(o, "card").orEmpty(), ToolArgs.int(o, "count"), ToolArgs.string(o, "section"), ToolArgs.string(o, "to_section")) },
            why = ToolArgs.string(i, "why").orEmpty(),
            evidence = ToolArgs.strings(i, "evidence"),
            expect = expects,
            at = System.currentTimeMillis(),
            fromPrint = beforePrint,
        )
        // Every number a tool's or the person's, else marked (estimate); a change with none says whose judgment it is.
        // The conversation's numbers — a course study's own when one runs, as the guide's are read (proveGuide).
        val turns = currentCoroutineContext()[StudyRun]?.evidence() ?: ai.session?.turns.orEmpty()
        when (val v = Evidence.judge(Proposals.claims(p), Evidence.sources(turns) + measured, "", System.currentTimeMillis())) {
            is Evidence.Verdict.Refused -> return fail("Not proposed: ${v.message}")
            is Evidence.Verdict.Words -> if (!p.why.contains("(judgment)", ignoreCase = true)) {
                return fail("Not proposed: say what the change should do with a tool's numbers (expect), or mark why (judgment) when it is a judgment.")
            }
            is Evidence.Verdict.Proved -> Unit
        }
        val broke = h.cutBreaks(id, base, tried.deck)
        ai.keepProposal(p)
        val text = "Proposed to the person: “${p.title}” — ${tried.done.joinToString("; ")}" +
            (if (tried.problems.isNotEmpty()) " (" + tried.problems.joinToString(" ") + ")" else "") + ". " +
            "They see a card with Apply and Not now; nothing changes until they press Apply. Do not edit the deck for it yourself." +
            (if (broke.isNotEmpty()) "\nWhat the cut breaks:\n" + DeckDependents.report(broke) else "") +
            "\n" + Proposals.embed(p)
        return ok(text, "Proposed “${p.title}”")
    }

    /**
     * The person's Apply on a proposal's card: the deck opened on the builder if it is not, the ops made as one step of undo,
     * and the proposal kept as applied with the print it made — what its results are read at later.
     */
    suspend fun applyProposal(p: Proposal): String {
        if (state.deckId != p.deckId) {
            val target = stored(p.deckId) ?: return "That deck is no longer kept: nothing changed."
            // Another deck open with unsaved changes is never thrown away for a proposal.
            if (state.dirty) return "“${state.deckName}” has unsaved changes on the builder: save them first, then Apply."
            state.load(p.deckId)
            withTimeoutOrNull(5_000) { snapshotFlow { state.deckId }.first { it == p.deckId } }
                ?: return "“${target.entry.name}” did not open: nothing changed."
        }
        val before = state.deck
        val a = applyOps(before, p.ops.map { o ->
            JsonObject(
                buildMap {
                    put("op", JsonPrimitive(o.op))
                    put("card", JsonPrimitive(o.card))
                    o.count?.let { put("count", JsonPrimitive(it)) }
                    o.section?.let { put("section", JsonPrimitive(it)) }
                    o.toSection?.let { put("to_section", JsonPrimitive(it)) }
                },
            )
        })
        if (a.done.isEmpty()) return "Nothing changed: " + a.problems.joinToString(" ")
        state.setCards(a.deck, "${p.title}: ${a.done.take(3).joinToString(", ")}. Save to keep it.")
        neue.go(Page.BUILDER)
        val byId = index::byId.takeIf { index.cards.isNotEmpty() }
        ai.keepProposal(p.copy(state = Proposal.APPLIED, decidedAt = System.currentTimeMillis(), fromPrint = DeckVersions.print(before, byId), toPrint = DeckVersions.print(a.deck, byId)))
        return "Applied: ${a.done.joinToString("; ")}" + if (a.problems.isNotEmpty()) " (${a.problems.joinToString(" ")})" else ""
    }

    /** `run_instrument` (Phase G, G.9; A3): an instrument with no World — nothing pinned — and the numbers it computed as claims. */
    private suspend fun runInstrument(i: JsonObject): MetaAnswer {
        val name = ToolArgs.string(i, "name") ?: return fail("Name the instrument.")
        if (name == "list") return ok(Instruments.list(), "Listed the instruments")
        val args = when (val raw = i["args"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            is JsonPrimitive -> raw.contentOrNull?.let { runCatching { WorldCodec.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?: return fail("args is an object, like {\"conditions\": [\"Starters>=1\"]}.")
            else -> return fail("args is an object.")
        }
        return runCatching { withContext(Dispatchers.Default) { Instruments.run(name, args, h.world.host()) } }.fold(
            { r ->
                val claims = Instruments.claims(r.answer)
                ok(
                    r.lines.joinToString("\n") + (if (claims.isNotEmpty()) "\nClaims:\n" + claims.joinToString("\n") { "- $it" } else ""),
                    "Ran the $name instrument",
                )
            },
            { fail(it.message ?: "The $name instrument could not run.") },
        )
    }

    private fun setGroups(groups: List<JsonObject>, replace: Boolean, show: Boolean): MetaAnswer {
        val inDeck = DeckSection.entries.flatMap { state.deck[it] }.toSet()
        val notes = mutableListOf<String>()
        var made = 0
        state.updateGroups { current ->
            var g = if (replace) DeckGroups(emptyList(), emptyMap(), current.fitted) else current
            groups.forEach { spec ->
                val name = ToolArgs.string(spec, "name") ?: return@forEach
                val existing = g.groups.firstOrNull { it.name.equals(name, ignoreCase = true) }
                val color = ToolArgs.int(spec, "color") ?: existing?.color ?: g.nextColor()
                val group = existing?.copy(color = color) ?: DeckGroup("g-" + java.util.UUID.randomUUID().toString().take(8), name, color, g.groups.size)
                g = g.upsert(group)
                // A group named again is its cards as named now: the old ones leave it.
                if (existing != null) g.assignments.filterValues { it == group.id }.keys.forEach { g = g.assign(it, null) }
                ToolArgs.strings(spec, "cards").forEach { word ->
                    when (val r = CardWords.resolve(word, index)) {
                        // The deck may hold the card by another printing (Phase B): it is in the deck all the same.
                        is Resolved.Found -> if (inDeck.any { it in r.card.passcodes }) {
                            g = g.assignCard(r.card.id, group.id, inDeck) { CardIdentity.canonical(it, index::byId) }
                        } else {
                            notes += "${r.card.name} is not in the deck."
                        }
                        is Resolved.Unknown -> notes += "No card “${r.text}”."
                    }
                }
                made++
            }
            g
        }
        if (show) h.setGroups(true)
        neue.go(Page.BUILDER)
        return ok("Set $made groups." + (if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else ""), "Grouped the deck: ${groups.mapNotNull { ToolArgs.string(it, "name") }.joinToString(", ").take(120)}")
    }

    private fun undo(steps: Int, redo: Boolean): MetaAnswer {
        var n = 0
        repeat(steps.coerceIn(1, 50)) {
            if (redo && state.canRedo) { state.redo(); n++ } else if (!redo && state.canUndo) { state.undo(); n++ }
        }
        return ok("${if (redo) "Redid" else "Undid"} $n step${if (n == 1) "" else "s"}.", "${if (redo) "Redid" else "Undid"} $n")
    }

    private suspend fun importDeck(text: String, name: String?): MetaAnswer {
        val read = DeckCodes.read(text, JvmZlib)
        val notes = mutableListOf<String>()
        val (deck, extended, title) = if (read != null) {
            Triple(read.parsed.document.deck, read.parsed.document.extended, name ?: read.name)
        } else {
            val (d, problems) = CardWords.deckList(text, index)
            notes += problems
            Triple(d, null, name)
        }
        if (deck.totalCards == 0) return fail("No deck in that text." + if (notes.isNotEmpty()) " " + notes.take(5).joinToString(" ") else "")
        if (state.dirty && (state.deckId != null || !state.deck.isEmpty)) save()
        val deckName = title ?: "Imported deck"
        state.adoptDeck(deck, deckName, extended)
        neue.go(Page.BUILDER)
        val id = save()
        return ok("Imported “$deckName” (id $id): main ${deck.main.size}, extra ${deck.extra.size}, side ${deck.side.size}." + if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else "", "Imported “$deckName”")
    }

    private fun exportDeck(format: String): MetaAnswer {
        val f = when (format.lowercase()) {
            "ydk" -> DeckExportFormat.YDK
            "ydkx" -> DeckExportFormat.YDKX
            "ydke" -> DeckExportFormat.YDKE
            "text" -> DeckExportFormat.TEXT
            "qr" -> DeckExportFormat.QR
            else -> return fail("Formats: ydk, ydkx, ydke, text, qr.")
        }
        CardActions.export(f, state, neue)
        return ok(
            when (f) {
                DeckExportFormat.YDKE, DeckExportFormat.TEXT -> "Copied to the clipboard."
                DeckExportFormat.QR -> "The QR code is on screen."
                else -> "A save dialog is open for the person."
            },
            "Exported as ${f.label}",
        )
    }

    private suspend fun deleteDeck(id: String): MetaAnswer {
        val s = stored(id) ?: return fail("No deck $id.")
        h.deps.deckRepository.delete(id)
        if (neue.prefs.defaultDeckId == id || id in neue.prefs.covers) {
            neue.update { it.copy(defaultDeckId = it.defaultDeckId?.takeIf { d -> d != id }, covers = it.covers - id) }
        }
        ai.files.delete(AiMemory.path(MemoryKind.DECK, id))
        ai.files.delete(AiMemory.path(MemoryKind.GUIDE, id))
        ai.files.delete(GuideBook.path(id))
        ai.files.delete(BookNotes.path(id))
        ai.files.deleteReports(id)
        h.shootout.forgetDeck(id)
        h.effects.forgetDeck(id)
        if (h.mapperStarted) h.mapper.forgetDeck(id)
        h.forgetVersions(id)
        if (state.deckId == id) {
            val next = StartingDeck.pick(h.deps.deckRepository.all().map { it.entry }, neue.prefs.defaultDeckId)
            if (next != null) state.load(next) else state.newDeck()
        }
        h.decksReload++
        return ok("Deleted “${s.entry.name}”.", "Deleted “${s.entry.name}”")
    }

    // ---- the app -------------------------------------------------------------------

    private fun navigate(page: String): MetaAnswer {
        val p = runCatching { Page.valueOf(page.uppercase()) }.getOrNull() ?: return fail("Pages: ${Page.entries.joinToString { it.name }}.")
        neue.go(p)
        return ok("On ${p.title}.", "Went to ${p.title}")
    }

    private fun runAction(name: String): MetaAnswer {
        val action = runCatching { DeskAction.valueOf(name.uppercase()) }.getOrNull()?.takeIf { it !in DeskAction.AI && it !in DeskAction.HELD }
            ?: return fail("No action $name.")
        h.run(action)
        return ok("Done: $name.", name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
    }

    private suspend fun setSetting(key: String, value: JsonElement): MetaAnswer {
        when (key) {
            AiSettings.FORMAT -> {
                // What is played (1.1.8): the bar's three choices, Genesys among them.
                val play = (value as? JsonPrimitive)?.content?.let(PlayChoice::parse) ?: return fail("format is TCG, OCG or Genesys.")
                h.setPlay(play)
                return ok("Playing ${play.label}.", "Set to ${play.label}")
            }
            AiSettings.SEARCH_EFFECTS -> {
                val on = (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: return fail("searchEffects is true or false.")
                h.setSearchEffects(on)
                return ok("Searching card text: $on.", "Card text search ${if (on) "on" else "off"}")
            }
        }
        val next = AiSettings.set(neue.prefs, key, value).getOrElse { return fail(it.message ?: "That does not fit $key.") }
        // Never onto a connection that may do more than the one this run is on (H.4): only the person lifts a ceiling.
        if (key == "ai.active") next.ai.connection?.let { to -> ToolTiers.switchRefusal(ai.runConnection ?: ai.prefs.connection, to) }?.let { return fail(it) }
        // A new name goes through the rename, so the voice file follows it too (1.0.46).
        if (key == "ai.name") {
            ai.rename(next.ai.name)
            return ok("Your name is now ${next.ai.name}.", "Renamed to ${next.ai.name}")
        }
        if (key == "ai.enabled" && !next.ai.enabled) {
            if (!ai.ask(Confirm("Turn ${ai.name} off?", "${ai.name} and everything about it will be hidden until you turn it on again in Settings.", "set_setting"))) {
                return fail("The person kept ${ai.name} on.")
            }
        }
        neue.update { next }
        val shown = AiSettings.flatten(next)[key] ?: value
        return ok("$key is now $shown.", "Set $key to $shown")
    }

    /** The deck a Fine Tuning run is about to its end, whatever the builder shows meanwhile ([AiMemoryTools.pinned]). */
    private val pinned: AiSession? get() = ai.session?.takeIf { it.mode in AiSession.DECK_MODES && it.deckId != null }

    private suspend fun askUser(question: String, options: List<String>, multiple: Boolean, cards: List<String> = emptyList(), heard: List<String> = emptyList()): MetaAnswer {
        // The cards a question is about, shown as their art (1.0.48): "what does this one do for you?"
        val shown = cards.take(6).mapNotNull { (CardWords.resolve(it, index) as? Resolved.Found)?.card }
        // At the duel table "No response" is always one of the answers (1.0.80, kai: "there was no 'No response'
        // option and I had to keep typing it out").
        val duel = ai.session?.mode == AiSession.MODE_DUEL
        // Five of Ai's own at most there, so No response always has a digit (1.0.86).
        val offered = if (duel && options.none { it.equals("No response", true) }) options.take(5) + "No response" else options.take(6)
        val said = heard.map { it.trim() }.filter { it.isNotEmpty() }.take(8)
        // At its own table its hidden cards stay out of the question as out of its words (1.0.86): the question,
        // what it heard and every option put through `Secrets` for the person's eyes; the answer handed back as
        // Ai wrote the options. A hidden card's art is not shown.
        val g = h.duel.game?.takeIf { duel && h.duel.role == null && !it.state.solo }
        if (g != null) {
            val aiSeat = h.neue.prefs.duel.aiSeat
            val secret = { t: String -> Secrets.redact(t, g.state, 1 - aiSeat, aiSeat, h.duel.catalog) }
            val o = Secrets.options(offered, g.state, 1 - aiSeat, aiSeat, h.duel.catalog)
            val art = shown.filterNot { secret(it.name).changed }
            val picked = ai.ask(Question(secret(question).text, o.shown, multiple, art, said.map { secret(it).text }))
            val answer = Secrets.answer(picked, o)
            return ok("The person answered: $answer", "${question.take(70)} → ${answer.take(90)}")
        }
        val answer = ai.ask(Question(question, offered, multiple, shown, said))
        // What was said stays in the conversation, not only the question (1.0.65).
        return ok("The person answered: $answer", "${question.take(70)} → ${answer.take(90)}")
    }
}
