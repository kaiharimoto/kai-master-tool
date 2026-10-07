package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.ai.Recall
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.avatar.MoodTracker
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.evidence.Numbers
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.memory.GuideBudget
import com.kaiharimoto.mastertool.core.ai.memory.GuideRewrite
import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.ai.report.SessionQuestions
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.ai.report.book.BookWriter
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.vision.ReadCards
import com.kaiharimoto.mastertool.core.duel.ai.Secrets
import com.kaiharimoto.neue.run
import com.kaiharimoto.mastertool.core.ai.AiSettings
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryReview
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.MemoryQuery
import com.kaiharimoto.mastertool.core.ai.memory.Persona
import com.kaiharimoto.mastertool.core.ai.memory.MemoryScope
import com.kaiharimoto.mastertool.core.ai.memory.MemoryWrite
import com.kaiharimoto.mastertool.core.ai.skills.BuiltInSkills
import com.kaiharimoto.mastertool.core.ai.skills.Skill
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiStream
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.deck.PlayChoice
import com.kaiharimoto.mastertool.core.deck.DeckEdit
import com.kaiharimoto.mastertool.core.deck.DeckEditor
import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.deck.DeckValidator
import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.deck.RejectionReason
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.library.StartingDeck
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.search.CardFilter
import com.kaiharimoto.mastertool.core.search.EffectKind
import com.kaiharimoto.mastertool.core.search.EffectKinds
import com.kaiharimoto.mastertool.core.search.SearchScope
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.ydk.DeckCodes
import com.kaiharimoto.mastertool.core.ydk.DeckExportFormat
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.ai.course.StudyRun
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.resume

/**
 * What each of Ai's tools does in the app: one method a tool, over the same state the
 * person's own clicks change ([NeueHolders]) — so an edit Ai makes lands on the same
 * undo, saves the same way and shows on the same page. Runs on the main thread (the
 * state is Compose's). A destructive tool asks the person first, in the chat.
 *
 * Every answer is short text or compact JSON for the model, and a line in words for
 * the chat ([Part.ToolResult.summary]).
 */
class AiHost(private val h: NeueHolders, private val ai: AiState) {
    private val state get() = h.builder
    private val neue get() = h.neue
    private val webs get() = h.webs
    private val index get() = state.index

    private class Answer(val content: String, val summary: String, val isError: Boolean = false, val pictures: List<Part.Image> = emptyList())

    private fun ok(content: String, summary: String) = Answer(content, summary)
    private fun ok(content: JsonElement, summary: String) = Answer(content.toString(), summary)
    private fun fail(message: String) = Answer(message, message, isError = true)

    suspend fun run(call: Part.ToolUse): Part.ToolResult {
        val spec = AiTools.named(call.name)?.takeIf { it in ai.tools }
            ?: return result(call, fail("There is no tool ${call.name} in this version of the app."))
        // At the duel table only the table's tools answer — over a CLI too, whose MCP list is the whole catalogue (1.0.85).
        if (ai.session?.mode == AiSession.MODE_DUEL && spec.name !in AiTools.DUEL) {
            return result(call, fail("${spec.name} is not used at the duel table."))
        }
        if (OpenAiStream.BROKEN_ARGS in call.input) {
            return result(call, fail("The input for ${spec.name} arrived cut short or was not JSON. Send it again, whole."))
        }
        ToolArgs.problem(spec, call.input)?.let { return result(call, fail(it)) }
        // What the mode closes, whatever the model tries — over a CLI too, whose MCP list is the whole catalogue: the decks
        // while Ai learns one or the person, and from first principles (1.0.54) the web and the community's lists.
        // A course study's step runs in a context of its own (StudyRun): its mode, deck and turns, never the panel's.
        val study = currentCoroutineContext()[StudyRun]
        (if (study != null) AiSession.MODE_COURSE else ai.session?.mode)?.let { mode -> AiTools.barredWhy(mode, spec.name) }?.let { return result(call, fail(it)) }
        // Unattended, a study's tools never touch the panel: its status line, the face and Chessy's copies are the person's conversation's.
        if (study != null) {
            val answer = try {
                dispatch(spec, call.input, study)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail("${spec.name} failed: ${t.message ?: t::class.simpleName}")
            }
            return result(call, answer)
        }
        val line = describe(spec, call.input)
        ai.working(line)
        ai.tool = spec.name
        // Chessy, busy: a copy of her blinks in beside what this tool touches and says what she is doing (kai, 2026-10)
        val copy = if (ai.prefs.persona == AiPrefs.PERSONA_CHESSY) ai.crew.act(spec.name, call.input, line) else null
        var said: Answer? = null
        val answer = try {
            dispatch(spec, call.input).also { said = it }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            fail("${spec.name} failed: ${t.message ?: t::class.simpleName}").also { said = it }
        } finally {
            ai.working(null)
            ai.tool = null
            copy?.let { id -> said.let { a -> ai.crew.done(id, a?.summary ?: "Stopped", a?.isError ?: true) } }
        }
        // Something found: the face lights up for a moment.
        if (!answer.isError && spec.name in MoodTracker.finding && !answer.summary.startsWith("No ")) ai.mood.found(ai.clock())
        ai.activity(Part.Activity(spec.name, answer.summary, answer.isError))
        return result(call, answer)
    }

    private fun result(call: Part.ToolUse, a: Answer) = Part.ToolResult(call.id, call.name, a.content, a.isError, a.summary, pictures = a.pictures)

    /** What a tool is doing, while it does it. */
    private fun describe(spec: ToolSpec, input: JsonObject): String = when (spec.name) {
        "search_cards" -> "Searching cards" + (ToolArgs.string(input, "query")?.let { " for “$it”" } ?: "")
        "new_deck" -> "Building ${ToolArgs.string(input, "name") ?: "a deck"}"
        "edit_deck" -> "Editing the deck"
        "resolve_cards" -> "Reading the cards off the picture"
        "watch_video" -> "Watching the video with Gemini"
        "context_status" -> "Checking how full its memory is"
        "banlist" -> "Reading the banlist" + (ToolArgs.string(input, "date")?.let { " of $it" } ?: "")
        "recall" -> "Remembering" + (ToolArgs.string(input, "query")?.let { " “$it”" } ?: "")
        "present_state" -> "Reading the presentation"
        "present_edit" -> "Building the slides"
        "present_view" -> "Looking at slide " + (ToolArgs.element(input, "slide")?.toString()?.trim('"') ?: "")
        else -> spec.name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private suspend fun dispatch(spec: ToolSpec, i: JsonObject, study: StudyRun? = null): Answer {
        if (spec.destructive && !ai.prefs.alwaysAllow) {
            val (title, detail) = consequence(spec, i)
            if (!ai.ask(Confirm(title, detail, action = spec.name))) {
                return fail("The person said no. Don't do it unless they ask again.")
            }
        }
        return when (spec.name) {
            "app_state" -> appState()
            "list_decks" -> listDecks(ToolArgs.string(i, "query"))
            "get_deck" -> getDeck(ToolArgs.string(i, "deck_id"))
            "validate_deck" -> validate(ToolArgs.string(i, "deck_id"), ToolArgs.string(i, "format"), ToolArgs.string(i, "as_of"))
            "get_settings" -> ok(AiSettings.describe(neue.prefs, h.play.label, state.searchEffects), "Read the settings")
            "list_webs" -> listWebs()
            "get_web" -> getWeb(ToolArgs.string(i, "web_id")!!)
            "get_siding" -> getSiding(ToolArgs.string(i, "deck_id")!!, ToolArgs.string(i, "against"))
            "search_cards" -> searchCards(i)
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
            "create_web" -> createWeb(ToolArgs.string(i, "name")!!, ToolArgs.string(i, "notes"))
            "add_deck_to_web" -> addDeckToWeb(i)
            "set_web_entry" -> setWebEntry(i)
            "set_web_notes" -> setWebNotes(i)
            "remove_from_web" -> removeFromWeb(ToolArgs.string(i, "web_id")!!, ToolArgs.string(i, "deck_id")!!)
            "delete_web" -> deleteWeb(ToolArgs.string(i, "web_id")!!)
            "set_siding_plan" -> setSidingPlan(i)
            "navigate" -> navigate(ToolArgs.string(i, "page")!!)
            "run_action" -> runAction(ToolArgs.string(i, "action")!!)
            "set_setting" -> setSetting(ToolArgs.string(i, "key")!!, ToolArgs.element(i, "value") ?: JsonNull)
            "memory" -> memory(ToolArgs.string(i, "action")!!, ToolArgs.string(i, "scope")!!, ToolArgs.string(i, "text"), ToolArgs.string(i, "old_text"), study)
            "memory_read" -> memoryRead(
                ToolArgs.string(i, "scope")!!, ToolArgs.string(i, "id"), ToolArgs.string(i, "query"), ToolArgs.string(i, "label"),
                ToolArgs.int(i, "from"), ToolArgs.int(i, "count"), study,
            )
            "skill_view" -> skillView(ToolArgs.string(i, "name")!!)
            "skill_manage" -> skillManage(i)
            "session_search" -> sessionSearch(ToolArgs.string(i, "query")!!, ToolArgs.int(i, "limit") ?: 12)
            "session_report" -> sessionReport(i)
            "reader_guide" -> readerGuide(i)
            "resolve_cards" -> resolveCards(ToolArgs.objects(i, "cards"))
            "context_status" -> ok(ai.contextReport(), "Checked how full its memory is")
            "compact" -> {
                ai.compactNow(ToolArgs.string(i, "focus"))
                ok("The start of this conversation will be summarised as soon as this answer is done.", "Asked to summarise the start of the conversation")
            }
            "recall" -> recall(ToolArgs.string(i, "query").orEmpty(), ToolArgs.string(i, "scope") ?: "this", ToolArgs.int(i, "limit") ?: 8)
            "ask_user" -> askUser(ToolArgs.string(i, "question")!!, ToolArgs.strings(i, "options"), ToolArgs.bool(i, "multiple") ?: false, ToolArgs.strings(i, "cards"), ToolArgs.strings(i, "heard"))
            else -> (learn.run(spec.name, i, learningDeck(study), { Evidence.sources(study?.evidence() ?: ai.session?.turns.orEmpty()) })
                ?: ai.courses.tool(spec.name, i, study) ?: harness.run(spec.name, i) ?: banTools.run(spec.name, i) ?: prepTools.run(spec.name, i) ?: presentTools.run(spec.name, i) ?: duelTools.run(spec.name, i) ?: worldTools.run(spec.name, i) ?: shootoutTools.run(spec.name, i) ?: effectsTools.run(spec.name, i) ?: meta.run(spec.name, i))?.let { Answer(it.content, it.summary, it.isError, it.pictures) }
                ?: fail("${spec.name} is not in this version of the app yet.")
        }
    }

    /** The meta's tools: YGOPRODeck, the field, a deck's numbers (phase 2). */
    private val meta = AiMeta(h, ai)

    /** The harness's own: numbers, a plan, the web, the rules, a helper (1.0.47). */
    private val harness = AiHarness(h, ai)

    /** Every banlist by date (1.1.1): the list on a day, a card's history, what moved; `validate_deck`'s `as_of`. */
    private val banTools = AiBanlist(h)

    /** Tournament prep's (1.0.50): the event, the test games, the numbers, the drills. */
    private val prepTools = AiPrep(h)
    private val learn = AiLearn(h, ai)

    /**
     * The deck the learning tools are about when none is named: a study's, the one Ai plays at the table, else the
     * builder's open deck.
     */
    private fun learningDeck(study: StudyRun?): String? {
        study?.let { return it.deckId }
        if (ai.session?.mode == AiSession.MODE_DUEL) {
            val g = h.duel.game
            val seat = if (g?.state?.solo == true) 0 else neue.prefs.duel.aiSeat
            g?.header?.seats?.getOrNull(seat)?.deckId?.let { return it }
        }
        return state.deckId
    }

    /** Present's (1.0.71): the outline, the edits, a look at a slide. */
    private val presentTools = AiPresent(h)

    /** Duel's (1.0.76): the table, moves played out, a logged peek, the log, a new duel, combos. */
    private val duelTools = AiDuel(h)

    /** Ai World (1.0.97): its own computer, watched. */
    private val worldTools = AiWorld(h)

    /** Shootout's (Phase S stage 3): the matchup and how far Ai is trusted on it, and its rubric. */
    private val shootoutTools = AiShootoutTools(h, ai)

    /** Effects as code (Phase D step 2): the library read, a card's script compiled and checked. */
    private val effectsTools = AiEffects(h)

    /** What a destructive tool will do, for the confirm card. */
    private suspend fun consequence(spec: ToolSpec, i: JsonObject): Pair<String, String> = when (spec.name) {
        "delete_deck" -> {
            val name = h.deps.deckRepository.byId(ToolArgs.string(i, "deck_id")!!)?.entry?.name ?: "this deck"
            "Delete “$name”?" to "It will be removed from the library for good. This cannot be undone."
        }
        "delete_web" -> {
            val web = webs.library.byId(ToolArgs.string(i, "web_id"))
            "Delete the web “${web?.name ?: "?"}”?" to "The web and its ${web?.entries?.size ?: 0} decks will be deleted. This cannot be undone."
        }
        "remove_from_web" -> {
            val web = webs.library.byId(ToolArgs.string(i, "web_id"))
            val name = h.deps.deckRepository.byId(ToolArgs.string(i, "deck_id")!!)?.entry?.name ?: "this deck"
            "Remove “$name” from “${web?.name ?: "the web"}”?" to "The web's copy of the deck is deleted with it."
        }
        else -> "${spec.name}?" to spec.description
    }

    // ---- where the person is ---------------------------------------------------

    /** The memory scope now: the open deck's (or its web's), or the web on Format. */
    fun scope(): MemoryScope? = MemoryScope.of(
        neue.page.name,
        // On Present, the deck the open presentation profiles (1.0.71).
        presentDeck()?.deckId ?: state.deckId,
        presentDeck()?.name ?: state.deckName,
        // On Prep, the web in view is the event's field (1.0.50).
        if (neue.page == Page.PREP) h.prep.active?.webId ?: webs.selected?.id else webs.selected?.id,
        webs.library,
    )

    private fun presentDeck() = if (neue.page == Page.PRESENT) h.present.open?.deck?.takeIf { it.deckId != null } else null

    /**
     * The scope's notes as the prompt holds them (1.1.11): the whole file when it fits its room on the model in use, else
     * the entries most relevant to [query] and the scope, with the index line for the rest.
     */
    fun notes(scope: MemoryScope, query: String = ""): String =
        ai.promptMemory(scope.kind, scope.id, if (scope.kind == MemoryKind.WEB) "web" else "deck", query)

    /** What <app_context> says: the date, the device, the page, the open deck, the selection. */
    fun situation(): List<String> = buildList {
        val today = LocalDate.now()
        add("Date: $today (${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)})")
        add("Device: ${if (neue.phone) "phone" else if (neue.touchFirst) "tablet" else "desktop"}; format ${state.format.name}")
        add("Page: ${neue.page.title}")
        val v = state.validation
        val web = webs.webOf(state.deckId)
        add(
            "Open deck: “${state.deckName}” (${state.deckId?.let { "id $it" } ?: "never saved"}${if (state.dirty) ", unsaved changes" else ""}) — " +
                "main ${state.deck.main.size}, extra ${state.deck.extra.size}, side ${state.deck.side.size}; " +
                (if (v.isLegal) "legal in ${state.rulesInForce.words()}" else "${v.errors.size} rule problems in ${state.rulesInForce.words()}") +
                (web?.let { "; in the web “${it.name}” (id ${it.id})" } ?: ""),
        )
        pinned?.takeIf { it.deckId != state.deckId }?.let {
            add("This Fine Tuning run is about “${it.deckName}” (id ${it.deckId}), not the deck now open: the guide, report and book you write are “${it.deckName}”'s.")
        }
        neue.selection?.let { add("Selected card: ${it.card.name}") }
        if (neue.page == Page.FORMAT || neue.page == Page.SIDING) webs.selected?.let { add("Web on screen: “${it.name}” (id ${it.id}), ${it.entries.size} decks") }
        if (neue.page == Page.PREP) h.prep.active?.let { e -> add("Event being prepared for: “${e.name}” (id ${e.id}) on ${e.date}, tab ${h.prep.tab.title}; prep_state has the rest") }
        if (neue.page == Page.DUEL) h.duel.game?.let { g ->
            val d = neue.prefs.duel
            add("Duel on the table: turn ${g.state.turn}, ${g.state.phase.label} Phase, ${if (g.state.solo) "one player's table" else "two seats"}; you play seat ${d.aiSeat} with ${d.aiKnowledge} knowledge; duel_state reads it")
        }
        if (neue.page == Page.PRESENT) h.present.open?.let { p ->
            add("Presentation open: “${p.name}” (id ${p.id}), ${p.slides.size} slides, on slide ${h.present.slideIndex + 1}${p.deck?.let { d -> "; profiles the deck “${d.name}”" } ?: ""}; present_state has the outline")
        }
        if (neue.page == Page.WORLD) {
            val w = h.world.open
            add(
                if (w == null) "On Ai World, with no world open: world_new makes one."
                else "On Ai World: “${w.title}” (id ${w.id}) is open, ${h.world.files.size} files, ${w.boards.size} boards" +
                    (h.world.editorPath?.let { "; the editor shows $it" } ?: "") + "; world_state has the rest",
            )
        }
    }

    // ---- looking ---------------------------------------------------------------

    private fun appState(): Answer = ok(buildJsonObject {
        put("page", neue.page.name)
        put("device", if (neue.phone) "phone" else if (neue.touchFirst) "tablet" else "desktop")
        put("format", state.format.name)
        put("open_deck", deckJson(state.deckId, state.deckName, state.deck, state.groups, open = true))
        neue.selection?.let { put("selected_card", it.card.name) }
        put("webs", buildJsonArray { webs.library.webs.forEach { add(JsonPrimitive("${it.name} (id ${it.id}, ${it.entries.size} decks)")) } })
        put("card_pool", "${index.size} cards")
    }, "Looked at the app")

    private suspend fun listDecks(query: String?): Answer {
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

    private suspend fun getDeck(id: String?): Answer {
        if (id == null || id == state.deckId) return ok(deckJson(state.deckId, state.deckName, state.deck, state.groups, open = true), "Read “${state.deckName}”")
        val s = stored(id) ?: return fail("No deck $id. list_decks shows the ids.")
        return ok(deckJson(id, s.entry.name, s.entry.deck, DeckGroupsCodec.read(s.extended).groups, open = false), "Read “${s.entry.name}”")
    }

    private suspend fun deckOf(id: String?): Pair<String, Deck>? =
        if (id == null || id == state.deckId) state.deckName to state.deck else stored(id)?.let { it.entry.name to it.entry.deck }

    private suspend fun validate(id: String?, format: String?, asOf: String?): Answer {
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

    private suspend fun listWebs(): Answer {
        if (webs.library.webs.isEmpty()) return ok("No webs yet. create_web makes one.", "No webs yet")
        val lines = webs.library.webs.map { w ->
            val decks = webs.decks(w).associateBy { it.entry.id }
            val entries = w.entries.joinToString("; ") { e -> (decks[e.deckId]?.entry?.name ?: "?") + (e.share?.let { " $it%" } ?: "") + (if (e.mine) " ★" else "") }
            "${w.id} | ${w.name} | ${w.entries.size} decks: $entries"
        }
        return ok(lines.joinToString("\n"), "Listed ${lines.size} webs")
    }

    private suspend fun getWeb(id: String): Answer {
        val w = webs.library.byId(id) ?: return fail("No web $id. list_webs shows them.")
        val decks = webs.decks(w).associateBy { it.entry.id }
        return ok(buildJsonObject {
            put("id", w.id)
            put("name", w.name)
            put("notes", w.notes)
            put("decks", buildJsonArray {
                w.entries.forEach { e ->
                    val d = decks[e.deckId] ?: return@forEach
                    add(buildJsonObject {
                        put("deck_id", e.deckId)
                        put("name", d.entry.name)
                        e.share?.let { put("share", it) }
                        if (e.mine) put("mine", true)
                        val top = d.entry.deck.main.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10)
                        put("headline", top.joinToString(", ") { "${it.value} ${index.byId(it.key)?.name ?: it.key.value}" })
                    })
                }
            })
            put("total_share", w.totalShare)
        }, "Read the web “${w.name}”")
    }

    private fun names(ids: List<CardId>) = ids.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.value} ${index.byId(it.key)?.name ?: it.key.value}" }

    private suspend fun getSiding(deckId: String, against: String?): Answer {
        val s = stored(deckId) ?: return fail("No deck $deckId.")
        val siding = webs.sidingOf(s, state)
        val shown = siding.matchups.filter { m -> against == null || m.deckId == against || m.name.equals(against, ignoreCase = true) }
        if (shown.isEmpty()) return ok("No siding plans for “${s.entry.name}”${against?.let { " against $it" } ?: ""} yet.", "No plans yet")
        val text = shown.joinToString("\n\n") { m ->
            "Against ${m.name}${m.deckId?.let { " (web deck $it)" } ?: ""}:\n" + Turn.entries.joinToString("\n") { t ->
                val p = m.plan(t)
                "  ${t.title}: out ${names(p.out).ifEmpty { "nothing" }}; in ${names(p.into).ifEmpty { "nothing" }}" + (p.note.takeIf { it.isNotBlank() }?.let { ". Why: $it" } ?: "")
            }
        }
        return ok(text, "Read the siding for “${s.entry.name}”")
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
        )
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

    private fun searchCards(i: JsonObject): Answer {
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

    private fun cardInfo(cards: List<String>): Answer {
        if (cards.isEmpty()) return fail("Name at least one card.")
        val out = cards.take(12).map { word ->
            when (val r = CardWords.resolve(word, index)) {
                is Resolved.Found -> cardLine(r.card, full = true) + if (r.guessed) "\n(read “$word” as ${r.card.name})" else ""
                is Resolved.Unknown -> "No card “${r.text}”." + if (r.suggestions.isNotEmpty()) " Closest: ${r.suggestions.joinToString()}." else ""
            }
        }
        return ok(out.joinToString("\n\n"), "Read ${cards.take(3).joinToString { CardWords.split(it).text }}${if (cards.size > 3) "…" else ""}")
    }

    private fun showInPool(i: JsonObject): Answer {
        neue.go(Page.BUILDER)
        neue.update { it.copy(poolVisible = true) }
        if (ToolArgs.bool(i, "clear") == true) {
            state.onQueryChange("")
            state.clearFilters()
            return ok("The pool shows every card.", "Cleared the pool's search")
        }
        state.onQueryChange(ToolArgs.string(i, "query").orEmpty())
        state.onFilterChange(filterOf(i).copy(onlyIds = state.filter.onlyIds, sort = state.filter.sort))
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

    private suspend fun openDeck(id: String): Answer {
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

    private suspend fun newDeck(name: String, main: List<String>, extra: List<String>, side: List<String>): Answer {
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

    private fun editDeck(ops: List<JsonObject>): Answer {
        if (ops.isEmpty()) return fail("No ops given.")
        var deck = state.deck
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
        state.setCards(deck, if (done.isNotEmpty()) "${ai.name}: ${done.take(3).joinToString(", ")}${if (done.size > 3) "…" else ""}" else null)
        neue.go(Page.BUILDER)
        val text = (if (done.isEmpty()) "Nothing changed." else "Done: ${done.joinToString("; ")}. Now main ${deck.main.size}, extra ${deck.extra.size}, side ${deck.side.size}.") +
            (if (problems.isNotEmpty()) "\n" + problems.joinToString("\n") else "")
        return Answer(text, if (done.isEmpty()) "Changed nothing" else done.joinToString(", ").take(140), isError = done.isEmpty() && problems.isNotEmpty())
    }

    private fun setGroups(groups: List<JsonObject>, replace: Boolean, show: Boolean): Answer {
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
                        is Resolved.Found -> if (r.card.id in inDeck) g = g.assign(r.card.id, group.id) else notes += "${r.card.name} is not in the deck."
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

    private fun undo(steps: Int, redo: Boolean): Answer {
        var n = 0
        repeat(steps.coerceIn(1, 50)) {
            if (redo && state.canRedo) { state.redo(); n++ } else if (!redo && state.canUndo) { state.undo(); n++ }
        }
        return ok("${if (redo) "Redid" else "Undid"} $n step${if (n == 1) "" else "s"}.", "${if (redo) "Redid" else "Undid"} $n")
    }

    private suspend fun importDeck(text: String, name: String?): Answer {
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

    private fun exportDeck(format: String): Answer {
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

    private suspend fun deleteDeck(id: String): Answer {
        val s = stored(id) ?: return fail("No deck $id.")
        h.deps.deckRepository.delete(id)
        if (neue.prefs.defaultDeckId == id || id in neue.prefs.covers) {
            neue.update { it.copy(defaultDeckId = it.defaultDeckId?.takeIf { d -> d != id }, covers = it.covers - id) }
        }
        ai.files.delete(AiMemory.path(MemoryKind.DECK, id))
        ai.files.delete(AiMemory.path(MemoryKind.GUIDE, id))
        ai.files.deleteReports(id)
        h.shootout.forgetDeck(id)
        h.effects.forgetDeck(id)
        if (state.deckId == id) {
            val next = StartingDeck.pick(h.deps.deckRepository.all().map { it.entry }, neue.prefs.defaultDeckId)
            if (next != null) state.load(next) else state.newDeck()
        }
        h.decksReload++
        return ok("Deleted “${s.entry.name}”.", "Deleted “${s.entry.name}”")
    }

    // ---- webs and siding ------------------------------------------------------------

    private fun createWeb(name: String, notes: String?): Answer {
        val web = webs.create(name)
        notes?.let { webs.setNotes(web.id, it) }
        neue.go(Page.FORMAT)
        return ok("Made the web “$name” (id ${web.id}).", "Made the web “$name”")
    }

    private suspend fun addDeckToWeb(i: JsonObject): Answer {
        val webId = ToolArgs.string(i, "web_id")!!
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        val deckId = ToolArgs.string(i, "deck_id")
        val text = ToolArgs.string(i, "text")
        val notes = mutableListOf<String>()
        val (name, document) = when {
            deckId != null -> {
                val s = stored(deckId) ?: return fail("No deck $deckId.")
                // The builder's copy when it is open there: its unsaved edits go in too.
                val doc = if (state.deckId == deckId) YdkDocument(state.deck, extended = state.extendedNow()) else s.toDocument()
                (ToolArgs.string(i, "name") ?: s.entry.name) to doc
            }
            text != null -> {
                val read = DeckCodes.read(text, JvmZlib)
                if (read != null) (ToolArgs.string(i, "name") ?: read.name ?: "Deck") to read.parsed.document else {
                    val (deck, problems) = CardWords.deckList(text, index)
                    notes += problems
                    if (deck.totalCards == 0) return fail("No deck in that text.")
                    (ToolArgs.string(i, "name") ?: "Deck") to YdkDocument(deck)
                }
            }
            else -> return fail("Give deck_id (a library deck) or text (a decklist).")
        }
        val newId = suspendCancellableCoroutine<String> { cont -> webs.add(webId, name, document) { if (cont.isActive) cont.resume(it) } }
        deckId?.let { from ->
            ai.foldIntoWeb(from, name, webId)
            ai.carryLearning(from, newId)
        }
        ToolArgs.int(i, "share")?.let { webs.share(webId, newId, it) }
        if (ToolArgs.bool(i, "mine") == true) webs.star(webId, newId, true)
        h.decksReload++
        return ok("Added “$name” to “${web.name}” as deck $newId." + if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else "", "Added “$name” to “${web.name}”")
    }

    private fun setWebEntry(i: JsonObject): Answer {
        val webId = ToolArgs.string(i, "web_id")!!
        val deckId = ToolArgs.string(i, "deck_id")!!
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        if (!web.has(deckId)) return fail("Deck $deckId is not in “${web.name}”.")
        ToolArgs.int(i, "share")?.let { webs.share(webId, deckId, it.takeIf { s -> s >= 0 }) }
        ToolArgs.bool(i, "mine")?.let { webs.star(webId, deckId, it) }
        ToolArgs.int(i, "position")?.let { webs.move(webId, deckId, it) }
        return ok("Updated.", "Updated a deck in “${web.name}”")
    }

    private fun setWebNotes(i: JsonObject): Answer {
        val webId = ToolArgs.string(i, "web_id")!!
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        ToolArgs.string(i, "name")?.let { webs.rename(webId, it) }
        ToolArgs.string(i, "notes")?.let { webs.setNotes(webId, it) }
        return ok("Updated “${web.name}”.", "Updated the web “${ToolArgs.string(i, "name") ?: web.name}”")
    }

    private suspend fun removeFromWeb(webId: String, deckId: String): Answer {
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        if (!web.has(deckId)) return fail("Deck $deckId is not in “${web.name}”.")
        suspendCancellableCoroutine<Unit> { cont -> webs.remove(webId, deckId) { if (cont.isActive) cont.resume(Unit) } }
        h.decksReload++
        return ok("Removed it from “${web.name}”.", "Removed a deck from “${web.name}”")
    }

    private suspend fun deleteWeb(webId: String): Answer {
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        suspendCancellableCoroutine<Unit> { cont -> webs.delete(webId) { if (cont.isActive) cont.resume(Unit) } }
        ai.files.delete(AiMemory.path(MemoryKind.WEB, webId))
        h.decksReload++
        return ok("Deleted “${web.name}”.", "Deleted the web “${web.name}”")
    }

    private suspend fun setSidingPlan(i: JsonObject): Answer {
        val deckId = ToolArgs.string(i, "deck_id")!!
        val s = stored(deckId) ?: return fail("No deck $deckId.")
        val deck = webs.deckOf(s, state)
        val against = ToolArgs.string(i, "against")!!
        val turn = if (ToolArgs.string(i, "turn") == "second") Turn.SECOND else Turn.FIRST
        val web = webs.webOf(deckId)
        val opponent = web?.takeIf { it.has(against) }?.let { against }
        val opponentName = opponent?.let { stored(it)?.entry?.name } ?: against
        val siding = webs.sidingOf(s, state)
        val existing = siding.against(opponent, opponentName)
        val notes = mutableListOf<String>()
        var plan = SidePlan(note = ToolArgs.string(i, "why") ?: existing?.plan(turn)?.note.orEmpty())
        ToolArgs.strings(i, "out").forEach { w ->
            when (val r = CardWords.resolve(w, index)) {
                is Resolved.Found -> repeat(r.count.coerceIn(1, 3)) {
                    if (SidingMath.canOut(deck, plan, r.card.id)) plan = plan.plusOut(r.card.id) else { notes += "Only ${SidingMath.outOf(deck, r.card.id)} ${r.card.name} to take out."; return@repeat }
                }
                is Resolved.Unknown -> notes += "No card “${r.text}”."
            }
        }
        ToolArgs.strings(i, "in").forEach { w ->
            when (val r = CardWords.resolve(w, index)) {
                is Resolved.Found -> repeat(r.count.coerceIn(1, 3)) {
                    if (SidingMath.canIn(deck, plan, r.card.id)) plan = plan.plusIn(r.card.id) else { notes += "The side deck has ${SidingMath.inOf(deck, r.card.id)} ${r.card.name}."; return@repeat }
                }
                is Resolved.Unknown -> notes += "No card “${r.text}”."
            }
        }
        val matchup = (existing ?: Matchup("m-" + java.lang.Long.toString(System.nanoTime(), 36), opponentName, opponent)).withPlan(turn, plan)
        webs.saveSiding(deckId, siding.put(matchup), state)
        if (plan.balance != 0) notes += "In and out differ by ${plan.balance}: the deck's size changes after siding."
        return ok(
            "${s.entry.name} against $opponentName, ${turn.title.lowercase()}: out ${names(plan.out).ifEmpty { "nothing" }}; in ${names(plan.into).ifEmpty { "nothing" }}." +
                if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else "",
            "Siding vs $opponentName (${turn.title.lowercase()}): −${plan.out.size} +${plan.into.size}",
        )
    }

    // ---- the app -------------------------------------------------------------------

    private fun navigate(page: String): Answer {
        val p = runCatching { Page.valueOf(page.uppercase()) }.getOrNull() ?: return fail("Pages: ${Page.entries.joinToString { it.name }}.")
        neue.go(p)
        return ok("On ${p.title}.", "Went to ${p.title}")
    }

    private fun runAction(name: String): Answer {
        val action = runCatching { DeskAction.valueOf(name.uppercase()) }.getOrNull()?.takeIf { it !in DeskAction.AI && it !in DeskAction.HELD }
            ?: return fail("No action $name.")
        h.run(action)
        return ok("Done: $name.", name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
    }

    private suspend fun setSetting(key: String, value: JsonElement): Answer {
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

    // ---- memory and skills ------------------------------------------------------------

    private companion object {
        /** The guide as it was, as a source: a number an entry keeps from it keeps its proof. */
        const val CARRIED = "the guide"
    }

    /**
     * The deck a guide, report or book write is about (1.0.98, the red team): a Fine Tuning run's own deck to its end,
     * whatever the builder shows meanwhile; else the builder's.
     */
    private val pinned: AiSession? get() = ai.session?.takeIf { it.mode in AiSession.DECK_MODES && it.deckId != null }
    private val aboutId: String? get() = pinned?.deckId ?: state.deckId
    private val aboutName: String get() = pinned?.deckName ?: state.deckName

    private fun memoryTarget(scope: String, id: String? = null, study: StudyRun? = null): Triple<MemoryKind, String?, String>? = when (scope) {
        "user" -> Triple(MemoryKind.USER, null, ai.name)
        "agent" -> Triple(MemoryKind.AGENT, null, ai.name)
        // How the open deck plays (1.0.48): its own file, in a web or not. A course study writes the guide of the deck it
        // was started for, whatever the builder shows meanwhile.
        "guide" -> if (study != null) Triple(MemoryKind.GUIDE, study.deckId, study.deckName) else (id ?: aboutId)?.let { Triple(MemoryKind.GUIDE, it, if (it == aboutId) aboutName else "this deck") }
        "web" -> (id?.let { webs.library.byId(it) } ?: scope()?.takeIf { it.kind == MemoryKind.WEB }?.let { webs.library.byId(it.id) })
            ?.let { Triple(MemoryKind.WEB, it.id, it.name) }
        "deck" -> {
            val here = scope()
            when {
                id != null -> Triple(MemoryKind.DECK, id, state.deckName)
                // A deck in a web keeps its notes in the web's file.
                here?.kind == MemoryKind.WEB -> Triple(MemoryKind.WEB, here.id, here.name)
                here != null -> Triple(MemoryKind.DECK, here.id, here.name)
                else -> null
            }
        }
        else -> null
    }

    /**
     * The guide's ledger after a write (1.0.98): each new entry judged against what this conversation computed and what
     * the person said ([Evidence.judge]); an entry that kept a number from the one it replaces, or from the guide as it
     * was, keeps that number's proof. A failure is the refusal, in words for Ai.
     */
    private fun proveGuide(deckId: String, before: List<String>, after: List<String>, replaced: String?, study: StudyRun? = null): Result<List<Proven>> = runCatching {
        val was = Ledger.read(ai.files.read(Ledger.path(deckId)))
        val deck = if (deckId == state.deckId) Ledger.fingerprint(state.deck) else ""
        val now = System.currentTimeMillis()
        // What the guide already held is a source for the numbers it keeps: their proofs go with them.
        val carried = before.joinToString("\n")
        val sources = Evidence.sources(study?.evidence() ?: ai.session?.turns.orEmpty()) + Evidence.Source(CARRIED, "", carried)
        var next = Ledger.prune(was, after)
        // A set: a guide of thousands of entries is compared in one pass (1.1.11).
        val held = before.toHashSet()
        after.filter { it !in held }.forEach { entry ->
            when (val v = Evidence.judge(entry, sources, deck, now)) {
                Evidence.Verdict.Words -> Unit
                is Evidence.Verdict.Refused -> error(v.message)
                is Evidence.Verdict.Proved -> {
                    // A number carried from the guide keeps the proof it had there.
                    val earlier = was.filter { p -> p.entry == replaced || p.entry in held }.flatMap { it.proofs }
                    val proofs = v.proven.proofs.flatMap { p -> if (p.tool == CARRIED) earlier.ifEmpty { listOf(p) } else listOf(p) }.distinct()
                    val status = if (proofs.any { it.tool == CARRIED } && v.proven.status == Proven.Status.CHECKED) {
                        was.firstOrNull { it.entry == replaced }?.status ?: Proven.Status.CHECKED
                    } else {
                        v.proven.status
                    }
                    next = Ledger.put(next, v.proven.copy(proofs = proofs, status = status), replaced)
                }
            }
        }
        next
    }

    private fun memory(action: String, scope: String, text: String?, old: String?, study: StudyRun? = null): Answer {
        // A course study keeps to the deck's guide: no notes on the person, no lessons of Ai's from someone else's words.
        if (study != null && scope != "guide") return fail("A course study writes the deck's guide only.")
        val (kind, id, name) = memoryTarget(scope, study = study) ?: return fail(
            if (scope == "web") "No web is in scope: the open deck is in no web, and none is on screen." else "Save the deck first; a deck never saved has no notes or guide yet.",
        )
        val doc = ai.files.memory(kind, id, name)
        val inWebForDeck = scope == "deck" && kind == MemoryKind.WEB
        val entry = text?.let { if (inWebForDeck && !it.startsWith("[")) "[${state.deckName}] $it" else it }
        val write = when (action) {
            "add" -> AiMemory.add(doc, entry ?: return fail("add needs text."), kind.limit, kind.entryLimit)
            "replace" -> AiMemory.replace(doc, old ?: return fail("replace needs old_text."), entry ?: return fail("replace needs text."), kind.limit, kind.entryLimit)
            "remove" -> AiMemory.remove(doc, old ?: text ?: return fail("remove needs old_text."))
            // The whole guide at once (1.0.66): only in Refactor guide, which ends in the person's review.
            "rewrite" -> when {
                kind != MemoryKind.GUIDE -> return fail("rewrite is for the guide only.")
                // A study is never the person's Refactor guide, whatever the panel shows meanwhile (1.1.52).
                study != null || ai.session?.mode != AiSession.MODE_REFACTOR -> return fail("rewrite is for Refactor guide. Here, use replace and remove.")
                else -> GuideRewrite.rewrite(doc, text ?: return fail("rewrite needs text: the whole guide."), kind.entryLimit)
            }
            else -> return fail("Actions: add, replace, remove.")
        }
        // The guide's numbers carry their proof (1.0.98, the evidence ledger): a percentage or odds nobody computed is refused.
        var ledger: List<Proven>? = null
        if (write is MemoryWrite.Done && kind == MemoryKind.GUIDE && id != null) {
            ledger = proveGuide(id, doc.entries, write.doc.entries, old, study).getOrElse { return fail(it.message ?: "Not written.") }
        }
        // What one Fine Tuning run may add to the guide, by its intensity (1.0.66: Deep, 20,000 characters).
        if (write is MemoryWrite.Done && kind == MemoryKind.GUIDE && action != "rewrite") {
            (if (study != null) study.guideRoom else ai.guideRoom())?.let { (start, budget, label) ->
                GuideBudget.refusal(start, write.doc.used, budget, label)?.let {
                    // The run is full, never the guide (1.1.9): the person is told when the run ends, and the next run carries on.
                    if (study != null) study.filled = GuideBudget.filled(budget, label) else ai.guideFilled = GuideBudget.filled(budget, label)
                    return fail(it)
                }
            }
        }
        return when (write) {
            is MemoryWrite.Done -> {
                ai.files.save(kind, id, write.doc)
                if (ledger != null && id != null) ai.files.write(Ledger.path(id), Ledger.write(ledger))
                // A study's own change to the guide, written down: its review lists what the study wrote, and Undo all takes
                // back that and nothing else (1.1.52: it put back the whole guide as it was days before).
                if (study != null) {
                    val path = AiMemory.path(kind, id)
                    MemoryReview.diff(mapOf(path to doc.render()), mapOf(path to write.doc.render())).forEach { ai.courses.wrote(study.courseId, it) }
                }
                ok(write.message, when (action) {
                    "add" -> "Remembered: ${entry.orEmpty().take(100)}"
                    "replace" -> "Updated a memory"
                    "rewrite" -> "Rewrote the guide"
                    else -> "Forgot: ${old.orEmpty().take(80)}"
                })
            }
            is MemoryWrite.Refused -> fail(write.message)
        }
    }

    /**
     * A memory file read a page at a time (1.1.11): the files have no cap, so this is how Ai reaches what its prompt left
     * out — by [query], [label] or range ([from], [count]), each entry numbered by its place in the file.
     */
    private suspend fun memoryRead(scope: String, id: String?, query: String?, label: String?, from: Int?, count: Int?, study: StudyRun? = null): Answer {
        val (kind, fid, name) = memoryTarget(scope, id, study) ?: return fail("Nothing in scope for $scope.")
        val doc = withContext(Dispatchers.IO) { ai.files.memory(kind, fid, name) }
        val title = doc.preamble.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: AiMemory.title(kind, name)
        val text = withContext(Dispatchers.Default) { MemoryQuery.read(doc, title, query, label, from, count) }
        val how = when {
            !query.isNullOrBlank() -> " for “${query.trim()}”"
            !label.isNullOrBlank() -> " under ${label.trim()}"
            from != null -> " from entry $from"
            else -> ""
        }
        return ok(text, "Read ${kind.name.lowercase()} memory$how")
    }

    private fun skillView(name: String): Answer {
        val skill = ai.skills().firstOrNull { it.name == Skills.slug(name) } ?: return fail("No skill $name. Skills: ${ai.skills().joinToString { it.name }}.")
        return ok(skill.body, "Read the skill ${skill.name}")
    }

    private suspend fun skillManage(i: JsonObject): Answer {
        val name = Skills.slug(ToolArgs.string(i, "name")!!)
        if (name.isEmpty()) return fail("A skill needs a name.")
        val own = ai.files.ownSkills().firstOrNull { it.name == name }
        val action = ToolArgs.string(i, "action")
        // A skill of the app's name stands in for the app's own (`Skills.merge`), changing how Ai does a whole
        // kind of task from then on: the person says yes first, the way a destructive tool asks.
        val builtIn = BuiltInSkills.all.firstOrNull { it.name == name }
        if (builtIn != null && (action == "create" || action == "patch") && !ai.prefs.alwaysAllow) {
            val confirm = Confirm(
                "Change how ${ai.name} does “$name”?",
                "“$name” is one of the app's own skills: ${builtIn.description.trimEnd('.')}. ${ai.name}'s version is used in its place " +
                    "from now on, until it is deleted.",
                action = "skill_manage",
            )
            if (!ai.ask(confirm)) return fail("The person said no: $name stays the app's. Don't change it unless they ask.")
        }
        return when (action) {
            "create" -> {
                val body = ToolArgs.string(i, "body") ?: return fail("create needs body.")
                val description = ToolArgs.string(i, "description") ?: return fail("create needs description.")
                ai.files.saveSkill(Skill(name, description, body))
                ok("Saved the skill $name.", "Wrote the skill $name")
            }
            "patch" -> {
                val base = own ?: ai.skills().firstOrNull { it.name == name }?.copy(builtIn = false) ?: return fail("No skill $name.")
                val old = ToolArgs.string(i, "old_text") ?: return fail("patch needs old_text.")
                val new = ToolArgs.string(i, "new_text") ?: return fail("patch needs new_text.")
                if (old !in base.body) return fail("That text is not in $name.")
                ai.files.saveSkill(base.copy(body = base.body.replace(old, new), description = ToolArgs.string(i, "description") ?: base.description))
                ok("Improved $name.", "Improved the skill $name")
            }
            "delete" -> {
                if (own == null) return fail("Only your own skills can be deleted; $name is the app's.")
                ai.files.deleteSkill(name)
                ok("Deleted $name.", "Deleted the skill $name")
            }
            else -> fail("Actions: create, patch, delete.")
        }
    }

    private suspend fun sessionSearch(query: String, limit: Int): Answer {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        val hits = withContext(Dispatchers.IO) { ai.files.sessions() }.flatMap { s ->
            s.turns.filter { !it.isToolResults }.mapNotNull { t ->
                val text = t.text
                if (words.all { text.lowercase().contains(it) }) {
                    val at = text.lowercase().indexOf(words.first())
                    "${java.time.Instant.ofEpochMilli(t.at).toString().take(10)} “${s.title}” (${if (t.role == com.kaiharimoto.mastertool.core.ai.Role.USER) "person" else "you"}): …${text.substring((at - 80).coerceAtLeast(0), (at + 160).coerceAtMost(text.length)).replace('\n', ' ')}…"
                } else null
            }
        }.take(limit.coerceIn(1, 40))
        return ok(if (hits.isEmpty()) "Nothing found for “$query”." else hits.joinToString("\n"), "Searched past conversations for “$query”")
    }

    /** Fine Tuning's report (1.0.54): kept beside the deck's guide, and a PDF when the session ends. */
    /** Words found in this conversation's saved history, the summarised part too, or in every conversation (1.0.56). */
    private suspend fun recall(query: String, scope: String, limit: Int): Answer {
        if (query.isBlank()) return fail("Say what to find.")
        // Everything Ai remembers (1.1.11): every memory file, entry by entry — what a prompt's budget left out.
        if (scope == "memory") {
            val hits = withContext(Dispatchers.IO) {
                val files = ai.files.memoryFiles().filter { it.name != Persona.FILE }
                    .associate { it.relativeTo(ai.files.root).invariantSeparatorsPath to it.readText() }
                MemoryQuery.search(files, query, limit.coerceIn(1, 30))
            }
            val said = hits.joinToString("\n") { "${it.path} [${it.number}]: ${it.excerpt}" }
            return ok(
                if (hits.isEmpty()) "Nothing in memory holds “$query”." else "$said\n(memory_read with the file's scope and from = the number reads an entry whole, with its neighbours.)",
                "Recalled “$query” from memory",
            )
        }
        val current = ai.session
        val pool = if (scope == "all") (withContext(Dispatchers.IO) { ai.files.sessions() }.filter { it.id != current?.id } + listOfNotNull(current)) else listOfNotNull(current)
        val hits = Recall.search(pool, query, limit.coerceIn(1, 30))
        val day = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.systemDefault())
        val said = hits.joinToString("\n") { h ->
            val who = if (h.who == Role.USER) "the person" else "you"
            val where = if (h.session == current?.id) "this conversation" else "“${h.title}”"
            "${day.format(java.time.Instant.ofEpochMilli(h.at))}, $where, $who: …${h.excerpt}…"
        }
        return ok(if (hits.isEmpty()) "Nothing found for “$query”." else said, "Recalled “$query”" + if (scope == "all") " across conversations" else "")
    }

    /** Names read off a picture, matched to cards (1.0.55). */
    private fun resolveCards(lines: List<JsonObject>): Answer {
        if (lines.isEmpty()) return fail("Give the cards you read.")
        val read = lines.take(80).mapNotNull { o ->
            val name = ToolArgs.string(o, "name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ReadCards.Read(name, ToolArgs.int(o, "count") ?: 1, ToolArgs.string(o, "section"))
        }
        val matches = ReadCards.resolve(read, index)
        val unsure = matches.count { !it.sure }
        return ok(
            ReadCards.describe(matches),
            "Read ${matches.size} cards off the picture" + if (unsure > 0) ", $unsure to check" else "",
        )
    }

    /** The reader's guide (1.0.67): a book about the open deck, written a chapter at a time and checked as it goes. */
    private suspend fun readerGuide(i: JsonObject): Answer {
        val deckId = aboutId ?: return fail("Save the deck first: the guide belongs to a saved deck.")
        val path = GuideBook.path(deckId)
        val book = GuideBook.read(ai.files.read(path))
            ?: GuideBook(aboutName)
        val deck = if (deckId == state.deckId) state.deck else stored(deckId)?.entry?.deck ?: return fail("The deck this guide is about is gone.")
        val main = deck.main.mapNotNull { state.index.byId(it)?.name }
        // What a write is stamped with (1.0.99): the deck and Ai's notes it was written on, for the reader to check against.
        val notesHash = ReaderGuide.hashOf(ai.files.read(AiMemory.path(MemoryKind.GUIDE, deckId)).orEmpty())
        val ctx = BookWriter.Context({ state.index.byName(it)?.name }, main, System.currentTimeMillis(), Ledger.fingerprint(deck), notesHash)
        val w = BookWriter
        // A chapter's percentages and odds are the deck's facts or a check's (1.0.98, the evidence ledger), like the guide's.
        if (ToolArgs.string(i, "action") == "write_chapter") {
            val facts = w.facts(book, ctx)
            val sources = Evidence.sources(ai.session?.turns.orEmpty()).map { it.content } + facts + ai.files.entries(MemoryKind.GUIDE, deckId)
            val unsourced = textsOf(ToolArgs.element(i, "chapter")).filter { !Numbers.isEstimate(it) }.flatMap { Numbers.unsourced(it, sources) }
            if (unsourced.isNotEmpty()) {
                return fail(
                    "Not written: the book's numbers are the deck's facts (the facts action) or a check's, and nothing computed " +
                        unsourced.distinctBy { it.written }.joinToString { "“${it.written}”" } + ". Compute it first, or leave the number out, or mark the line “(estimate)”.",
                )
            }
        }
        val result = when (ToolArgs.string(i, "action")) {
            "outline" -> BookWriter.Result(book, w.outline(book, ctx))
            "set_outline" -> w.setOutline(book, ToolArgs.objects(i, "chapters"))
            "set_front" -> w.setFront(book, i, ctx)
            "write_chapter" -> w.writeChapter(book, ToolArgs.element(i, "chapter"), ctx)
            "read_chapter" -> w.readChapter(book, ToolArgs.string(i, "id").orEmpty())
            "remove_chapter" -> w.removeChapter(book, ToolArgs.string(i, "id").orEmpty())
            "facts" -> BookWriter.Result(book, w.facts(book, ctx))
            else -> return fail("Actions: outline, set_outline, set_front, write_chapter, read_chapter, remove_chapter, facts.")
        }
        if (!result.ok) return fail(result.message)
        if (result.book != book) {
            // Which version of Ai's notes it was written from, so the app can say when it is out of date.
            ai.files.write(path, GuideBook.write(result.book.copy(notesHash = notesHash)))
            ai.bookChanged()
        }
        val summary = when (ToolArgs.string(i, "action")) {
            "write_chapter" -> "Wrote a chapter of the guide"
            "set_outline" -> "Planned the guide's chapters"
            "set_front" -> "Set the guide's front"
            "facts" -> "Read the deck's numbers"
            "remove_chapter" -> "Removed a chapter"
            else -> "Read the guide"
        }
        return ok(result.message, summary)
    }

    /** Every string in a chapter's JSON, for the number check. */
    private fun textsOf(e: kotlinx.serialization.json.JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonObject -> e.values.flatMap { textsOf(it) }
        is kotlinx.serialization.json.JsonArray -> e.flatMap { textsOf(it) }
        is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
    }

    private fun sessionReport(i: JsonObject): Answer {
        val deckId = aboutId ?: return fail("There is no saved deck open to report on.")
        val s = ai.session ?: return fail("There is no session to report on.")
        fun num(key: String) = (ToolArgs.element(i, key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        val report = SessionReport(
            deckId = deckId,
            deckName = aboutName,
            at = System.currentTimeMillis(),
            mode = s.mode.takeIf { it in AiSession.DECK_MODES } ?: AiSession.MODE_TUNE,
            intensity = neue.prefs.ai.tuneIntensity,
            summary = ToolArgs.string(i, "summary").orEmpty(),
            learned = ToolArgs.strings(i, "learned"),
            insights = ToolArgs.strings(i, "insights"),
            openQuestions = ToolArgs.strings(i, "open_questions"),
            understanding = SessionReport.score(num("understanding")),
            playing = SessionReport.score(num("playing")),
            mirror = SessionReport.score(num("mirror")),
            why = ToolArgs.string(i, "why").orEmpty(),
            questions = SessionQuestions.of(s.turns),
            startedAt = s.createdAt,
        )
        ai.files.addReport(report)
        ai.lastReport = report
        return ok(
            "Filed. The person gets it as a PDF when the session ends, and the deck's guide shows the scores.",
            "Filed the report: understanding ${report.understanding}, playing ${report.playing}, mirror ${report.mirror}%",
        )
    }

    private suspend fun askUser(question: String, options: List<String>, multiple: Boolean, cards: List<String> = emptyList(), heard: List<String> = emptyList()): Answer {
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
