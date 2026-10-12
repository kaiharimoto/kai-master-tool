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
        // What Ai may do, by tier (H.4): the person's conversations. A course study is the person's own start, under its
        // own guards, and keeps asking before it deletes as it always has.
        // A connection's own ceiling only lowers it — the connection the run began on, whatever Ai has switched to since.
        // Judged per call where the reach depends on what is asked (the red team, 2026-10): a Python run reaches outside too,
        // a deck imported is a change too, an app of the World deleted is Deleting — the strictest tier winning.
        val on = ai.runConnection ?: ai.prefs.connection
        val deletes = ToolTiers.deletes(spec, i)
        val policy = if (study == null) ToolTiers.policyFor(spec, i, ai.prefs, on) else if (deletes && !ai.prefs.alwaysAllow) TierPrefs.ASK else TierPrefs.ALLOW
        if (policy == TierPrefs.OFF) return fail(ToolTiers.offWords(spec, ai.prefs, on, i))
        if (policy == TierPrefs.ASK) {
            val (title, detail) = if (deletes) consequence(spec, i) else asking(spec, i, ToolTiers.asking(spec, i, ai.prefs, on))
            if (!ai.ask(Confirm(title, detail, action = spec.name))) {
                return fail(ToolTiers.DENIED)
            }
        }
        // Every handler in turn, the deck's and the app's own first: one list, so a new group is one more line (I.5).
        for (handler in handlers) handler(spec.name, i, study)?.let { return Answer(it.content, it.summary, it.isError, it.pictures) }
        return fail("${spec.name} is not in this version of the app yet.")
    }

    /** The deck's and the app's own tools (I.5: answered here until then). */
    private val deckTools by lazy { AiDeckTools(h, ai, banTools) }

    /**
     * Who answers a tool, in the order they are asked (Phase I, I.5): each group answers its own tools and passes on the rest
     * (null). `ArchitectureLawTest` holds every tool offered to an answer here.
     */
    private val handlers: List<suspend (String, JsonObject, StudyRun?) -> MetaAnswer?> by lazy {
        listOf(
            { n, i, _ -> deckTools.run(n, i) },
            { n, i, s -> learn.run(n, i, learningDeck(s), { Evidence.sources(s?.evidence() ?: ai.session?.turns.orEmpty()) }) },
            { n, i, s -> ai.courses.tool(n, i, s) },
            { n, i, _ -> harness.run(n, i) },
            { n, i, _ -> banTools.run(n, i) },
            { n, i, _ -> prepTools.run(n, i) },
            { n, i, _ -> predictTools.run(n, i) },
            { n, i, _ -> presentTools.run(n, i) },
            { n, i, _ -> duelTools.run(n, i) },
            { n, i, _ -> worldTools.run(n, i) },
            { n, i, _ -> shootoutTools.run(n, i) },
            { n, i, _ -> effectsTools.run(n, i) },
            { n, i, _ -> mapperTools.run(n, i) },
            { n, i, _ -> webTools.run(n, i) },
            { n, i, s -> memoryTools.run(n, i, s) },
            { n, i, _ -> meta.run(n, i) },
        )
    }

    /** The deck a Fine Tuning run is about to its end, whatever the builder shows meanwhile ([AiMemoryTools.pinned]). */
    private val pinned: AiSession? get() = ai.session?.takeIf { it.mode in AiSession.DECK_MODES && it.deckId != null }

    /** A proposal applied (the proposal card's Apply): the deck's tools do it. */
    suspend fun applyProposal(p: Proposal): String = deckTools.applyProposal(p)

    /** Memory, skills, sessions and the reader's guide: what Ai keeps and writes. */
    private val memoryTools = AiMemoryTools(h, ai, this)

    /** Webs of decks and siding: reading, building and planning them. */
    private val webTools = AiWebs(h, ai)

    /** The meta's tools: YGOPRODeck, the field, a deck's numbers (phase 2). */
    private val meta = AiMeta(h, ai)

    /** The harness's own: numbers, a plan, the web, the rules, a helper (1.0.47). */
    private val harness = AiHarness(h, ai)

    /** Every banlist by date (1.1.1): the list on a day, a card's history, what moved; `validate_deck`'s `as_of`. */
    private val banTools = AiBanlist(h)

    /** Tournament prep's (1.0.50): the event, the test games, the numbers, the drills. */
    private val prepTools = AiPrep(h)
    private val predictTools = AiPredict(h, ai)
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

    /** Gameplay Mapper (Phase M step M1): the board library read, the starter table and dealt hands mapped, Ai's presets. */
    private val mapperTools = AiMapper(h)

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
        // An app of Ai World (the red team, 2026-10): asked here, by the Deleting tier, as every deletion is.
        "world_app" -> {
            val slug = ToolArgs.string(i, "slug")?.trim()?.lowercase().orEmpty()
            val title = h.world.open?.let { h.world.apps.manifest(slug)?.title } ?: slug
            "Delete the app “$title”?" to "Its code, its earlier versions and everything kept in it go. This cannot be undone."
        }
        else -> "${spec.name}?" to spec.description
    }

    /** What the person is asked before a change or a reach outside the app, when they asked to be (H.4): [tiers] are the ones asking. */
    private fun asking(spec: ToolSpec, i: JsonObject, tiers: Set<ToolTier>): Pair<String, String> {
        val doing = describe(spec, i)
        val what = when {
            ToolTier.OUTWARD in tiers && ToolTiers.runsPython(spec, i) -> "This runs Python, a program on this computer that can reach the network (${spec.name})"
            ToolTier.OUTWARD in tiers && ToolTier.WRITE in tiers -> "This reaches outside the app and keeps what it brings (${spec.name})"
            ToolTier.OUTWARD in tiers -> "This reaches outside the app (${spec.name})"
            else -> "This changes something of yours (${spec.name})"
        }
        return "Let ${ai.name} go ahead? $doing" to "$what. You asked to be asked first: Settings › Ai."
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
}
