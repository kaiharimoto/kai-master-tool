package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.present.DeckSnapshot
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.ai.PresentReport
import com.kaiharimoto.mastertool.core.present.ai.PresentWriter
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.modules.ModuleInput
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.mastertool.core.present.modules.Pick
import com.kaiharimoto.mastertool.core.present.modules.Shoutout
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.present.ModuleData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Present for Ai (1.0.71): the outline of a presentation, a batch of edits to it, and a look at
 * one slide — on the same [com.kaiharimoto.neue.present.Presentations] the editor reads, so an
 * edit Ai makes is on the canvas at once and one step of the person's Undo ("Ai: …"). The edits
 * themselves are [PresentWriter]'s; what needs the rest of the app (a new profile of a saved deck,
 * a module made from the deck's siding, practice or an event) is here. The `deck-profile` and
 * `slide-design` skills are how they are used.
 */
internal class AiPresent(private val h: NeueHolders) {
    private val present get() = h.present
    private val state get() = h.builder

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "present_state" -> presentState(ToolArgs.string(i, "presentation_id"))
        "present_edit" -> edit(ToolArgs.objects(i, "ops"))
        "present_view" -> view(ToolArgs.element(i, "slide"))
        else -> null
    }

    private fun cardName(id: Int): String? = state.index.byId(CardId(id))?.name

    private fun cardId(name: String): Int? = when (val r = CardWords.resolve(name, state.index)) {
        is Resolved.Found -> r.card.id.value
        is Resolved.Unknown -> null
    }

    private val ctx get() = PresentWriter.Context(::cardId, System.currentTimeMillis())

    private suspend fun presentState(id: String?): MetaAnswer {
        present.ensureLoaded()
        val p = if (id != null) present.library.firstOrNull { it.id == id } ?: return fail("No presentation $id.") else present.open
        if (p == null) {
            val all = present.library
            if (all.isEmpty()) return ok("No presentations yet. Make a deck profile with present_edit's create.", "Present: none yet")
            return ok(
                "None is open. Presentations (open one with present_state's id, then edit it):\n" +
                    all.joinToString("\n") { "- ${it.id} | ${it.name} | ${Presentation.styleName(it.style)} | ${it.slides.size} slides${it.deck?.let { d -> " | deck ${d.name}" } ?: ""}" },
                "Listed ${all.size} presentations",
            )
        }
        if (present.open?.id != p.id) {
            present.openIt(p)
            h.neue.go(Page.PRESENT)
        }
        return ok(PresentReport.outline(p, ::cardName).trim(), "Read “${p.name}”")
    }

    /** The ops in order: [PresentWriter]'s in runs, the app's own between them; the whole batch one Undo. */
    private suspend fun edit(ops: List<JsonObject>): MetaAnswer {
        if (ops.isEmpty()) return fail("Give ops.")
        present.ensureLoaded()
        val lines = ArrayList<String>()
        var p: Presentation? = present.open
        var touched: String? = null
        var failed = false
        val batch = ArrayList<JsonObject>()

        fun flushRun() {
            if (batch.isEmpty() || failed) return
            val start = p ?: run { lines += "There is no presentation open: begin with create, or open one with present_state."; failed = true; return }
            val r = PresentWriter.apply(start, batch.toList(), ctx)
            // The writer numbers its lines from 1 for its own run; the batch's numbers are kept instead.
            val base = lines.size
            val number = Regex("^(Operation )?(\\d+)")
            r.lines.forEach { l -> lines += number.replace(l) { m -> m.groupValues[1] + (m.groupValues[2].toInt() + base) } }
            p = r.presentation
            r.touched?.let { touched = it }
            if (!r.ok) failed = true
            batch.clear()
        }

        for (op in ops) {
            if (failed) break
            when (val action = ToolArgs.string(op, "action")) {
                "create" -> {
                    flushRun()
                    if (failed) break
                    // What was edited before stays, as its own step.
                    p?.let { commit(it, ops) }
                    val made = try {
                        create(op)
                    } catch (e: ModuleProblem) {
                        lines += "${lines.size + 1}. ${e.message}"
                        failed = true
                        break
                    }
                    if (made == null) {
                        lines += "${lines.size + 1}. create: no saved deck ${ToolArgs.string(op, "deck_id")}; list_decks has their ids"
                        failed = true
                        break
                    }
                    present.create(made)
                    h.neue.go(Page.PRESENT)
                    p = made
                    lines += "${lines.size + 1}. Made “${made.name}” (id ${made.id}), ${Presentation.styleName(made.style)}, ${made.slides.size} slides"
                }
                "add_module" -> {
                    flushRun()
                    val start = p ?: run { lines += "There is no presentation open."; failed = true; null } ?: break
                    val made = try {
                        addModule(start, op)
                    } catch (e: ModuleProblem) {
                        lines += "${lines.size + 1}. ${e.message}"
                        failed = true
                        break
                    }
                    if (made == null) {
                        lines += "${lines.size + 1}. add_module: give type, one of ${Modules.all.joinToString()}"
                        failed = true
                        break
                    }
                    val (next, line, first) = made
                    p = next
                    first?.let { touched = it }
                    lines += "${lines.size + 1}. $line"
                }
                "refresh_module" -> {
                    flushRun()
                    val start = p ?: run { lines += "There is no presentation open."; failed = true; null } ?: break
                    val ref = ToolArgs.string(op, "slide") ?: (op["slide"] as? JsonPrimitive)?.intOrNull?.toString()
                    val s = ref?.let { r -> start.slide(r) ?: r.toIntOrNull()?.let { start.slides.getOrNull(it - 1) } }
                    if (s?.module == null) {
                        lines += "${lines.size + 1}. refresh_module: ${if (s == null) "no slide $ref" else "slide ${start.indexOf(s.id) + 1} is not a module's"}"
                        failed = true
                        break
                    }
                    // A siding slide follows its own matchup (the audit's B2); one whose matchup is gone is left, and said.
                    when (val r = ModuleData.refreshed(h, start, s)) {
                        is Modules.Refreshed.Made -> {
                            p = PresentEdits.updateSlide(start, s.id) { r.slide }
                            touched = s.id
                            lines += "${lines.size + 1}. Refreshed slide ${start.indexOf(s.id) + 1} from its data; what was changed by hand stayed"
                        }
                        is Modules.Refreshed.Gone -> {
                            lines += "${lines.size + 1}. refresh_module: slide ${start.indexOf(s.id) + 1} was left as it was. ${r.why}"
                            failed = true
                            break
                        }
                    }
                }
                null -> { lines += "${lines.size + 1}. An op has no action."; failed = true }
                else -> {
                    if (action !in PresentWriter.ACTIONS) {
                        flushRun()
                        lines += "${lines.size + 1}. Unknown action $action."
                        failed = true
                    } else batch += op
                }
            }
        }
        flushRun()
        p?.let { commit(it, ops) }
        touched?.let { t -> if (present.open?.slide(t) != null) { present.slideId = t; present.selection = emptySet() } }
        val open = present.open
        val text = buildString {
            appendLine(lines.joinToString("\n"))
            if (failed) appendLine("Stopped there; the ops before it are done. Fix that one and send the rest again.")
            if (open != null) {
                touched?.let { t -> open.indexOf(t).takeIf { it >= 0 }?.let { appendLine("Check it with present_view {slide: ${it + 1}}.") } }
                append("“${open.name}”: ${open.slides.size} slides.")
            }
        }
        val summary = if (failed) "Present: stopped at an edit" else "Edited the presentation (${ops.size} ${if (ops.size == 1) "change" else "changes"})"
        return MetaAnswer(text.trim(), summary, isError = failed && lines.size <= 1)
    }

    private fun commit(next: Presentation, ops: List<JsonObject>) {
        if (present.open?.id != next.id) return
        val what = ops.mapNotNull { ToolArgs.string(it, "action") }.distinct().joinToString(", ") { it.replace('_', ' ') }
        present.commit(next, "Ai: $what")
        present.seal()
    }

    private suspend fun create(op: JsonObject): Presentation? {
        val now = System.currentTimeMillis()
        val prefs = h.neue.prefs
        val deckId = ToolArgs.string(op, "deck_id")
        val snapshot: DeckSnapshot? = if (deckId != null) {
            val stored = h.deps.deckRepository.byId(deckId) ?: return null
            snap(stored.entry.deck, DeckGroupsCodec.read(stored.extended).groups, stored.entry.name, stored.entry.id, now)
        } else if (state.deck.main.isNotEmpty() || state.deck.extra.isNotEmpty()) {
            snap(state.deck, state.groups, state.deckName, state.deckId, now)
        } else null
        val style = ToolArgs.string(op, "style")?.let(PresentWriter::styleOf) ?: prefs.present.style
        val theme = ToolArgs.string(op, "theme")?.let { t -> Themes.named(t)?.id }
            ?: prefs.present.startTheme(prefs.theme == NeueTheme.INK)
        val base = WebcamZone(enabled = prefs.present.webcam, preset = prefs.present.webcamPreset)
        val webcam = (op["webcam"] as? JsonObject)?.let { w -> PresentWriter.webcamOf(base, w).getOrElse { throw ModuleProblem("create: ${it.message}") } } ?: base
        val enabled = webcam.enabled
        val preset = webcam.preset
        val creator = ToolArgs.string(op, "creator") ?: prefs.present.creator
        val name = ToolArgs.string(op, "name") ?: snapshot?.name?.let { "$it deck profile" } ?: "Untitled presentation"
        val p = if (snapshot == null) {
            Presentation(present.newId(), name, now, now, style, null, theme, webcam = webcam, creator = creator, slides = listOf(SlideLayouts.slide(SlideLayouts.TITLE)))
        } else {
            PresentEdits.newProfile(present.newId(), name, snapshot, style, theme, webcam, creator, now)
        }
        h.neue.update { it.copy(present = it.present.copy(style = style, webcam = enabled, webcamPreset = preset, creator = creator, open = p.id)) }
        return p
    }

    private fun snap(deck: Deck, groups: DeckGroups, name: String, id: String?, now: Long): DeckSnapshot {
        val prefs = h.neue.prefs
        val ids = (deck.main + deck.extra + deck.side).map { it.value }.toSet()
        return PresentEdits.snapshot(
            deck, groups, name, id, prefs.groupArrangement, prefs.arts.filterKeys { it in ids },
            GroupMarkers.palettes.indexOfFirst { it.id == prefs.groupPalette }.coerceAtLeast(0), now,
        )
    }

    /** A module's slides, made from the app's data and what Ai gave, after the slide it names. */
    private suspend fun addModule(p: Presentation, op: JsonObject): Triple<Presentation, String, String?>? {
        val type = ToolArgs.string(op, "type")?.uppercase()?.replace(' ', '_')?.takeIf { it in Modules.all } ?: return null
        val unknown = ArrayList<String>()
        fun picks(key: String): List<Pick> = (op[key] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject
            val card = (o?.get("card") as? JsonPrimitive ?: e as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val id = cardId(card) ?: run { unknown += card; return@mapNotNull null }
            Pick(id, (o?.get("note") as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        var input = ModuleInput(
            title = ToolArgs.string(op, "title").orEmpty(),
            strong = picks("strong"),
            weak = picks("weak"),
            picks = picks("picks"),
            placement = ToolArgs.string(op, "placement").orEmpty(),
            shoutouts = (op["shoutouts"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                Shoutout(null, s("name"), s("handle"), s("line"))
            },
        )
        if (unknown.isNotEmpty()) throw ModuleProblem("add_module: no card called ${unknown.joinToString { "“$it”" }}; nothing added. Check the names with search_cards.")
        when (type) {
            Modules.SIDING -> {
                val names = (op["matchups"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                input = input.copy(params = mapOf("matchups" to names.joinToString("\n")))
            }
            Modules.TOURNAMENT -> {
                val event = ToolArgs.string(op, "event_id") ?: ModuleData.events(h).firstOrNull()?.id
                    ?: throw ModuleProblem("add_module tournament: there are no events on Prep; make one with set_event and log its rounds first.")
                input = input.copy(params = mapOf("event" to event))
            }
        }
        val gathered = ModuleData.gather(h, p, type, input)
        if (type == Modules.SIDING && gathered.matchups.isEmpty()) throw ModuleProblem("add_module siding: the deck has no siding plans (or none by those names). Make them with set_siding_plan first.")
        if (type == Modules.MATCHUPS && gathered.rows.isEmpty()) throw ModuleProblem("add_module matchups: no practice games are logged for this deck. Log them with log_game first.")
        // Opening odds and Ratios with no groups, as the dialog refuses them (the audit's I3).
        Modules.missing(type, gathered)?.let { throw ModuleProblem("add_module ${type.lowercase()}: $it") }
        val slides = Modules.generate(type, gathered, System.currentTimeMillis())
        var after = ToolArgs.string(op, "after")?.let { r -> p.slide(r)?.let { p.indexOf(it.id) } ?: r.toIntOrNull()?.minus(1) }
            ?: (p.slides.lastIndex - if (p.slides.lastOrNull()?.layout == SlideLayouts.END_CARD) 1 else 0)
        var next = p
        slides.forEach { s -> next = PresentEdits.addSlide(next, s, after); after++ }
        val missing = if (type == Modules.SHOUTOUTS && input.shoutouts.isNotEmpty()) " Their logos are empty picture slots: the person adds them." else ""
        return Triple(next, "Added ${Modules.name(type).lowercase()}: ${slides.size} ${if (slides.size == 1) "slide" else "slides"} (${slides.joinToString { it.id }}).$missing", slides.firstOrNull()?.id)
    }

    /** Something the app could not do for an op, said so Ai can fix it. */
    private class ModuleProblem(message: String) : Exception(message)

    private suspend fun view(ref: kotlinx.serialization.json.JsonElement?): MetaAnswer {
        val p = present.open ?: return fail("There is no presentation open.")
        val r = (ref as? JsonPrimitive)?.contentOrNull ?: return fail("Give slide: its id or number.")
        val i = p.slide(r)?.let { p.indexOf(it.id) } ?: r.toIntOrNull()?.minus(1)?.takeIf { it in p.slides.indices }
            ?: return fail("No slide $r; there are ${p.slides.size}.")
        present.slideId = p.slides[i].id
        val problems = PresentReport.check(p, i, ::cardName)
        // A model that sees is shown the slide itself too (1.1.x, the audit's M9): colour, balance and crowding
        // are judged from pixels, not words. Drawn as the audience's finished picture, once its art is in.
        val picture = slidePicture(p, i)
        val text = buildString {
            if (picture != null) appendLine("The slide as the audience sees it, every build done, is attached as a picture (960 × 540). Judge color, balance and crowding from it.")
            val s = p.slides[i]
            appendLine("Slide ${i + 1} of ${p.slides.size}: ${s.title.ifBlank { "(untitled)" }}")
            PresentReport.deckWords(p, i, ::cardName)?.let { appendLine("Deck: $it") }
            if (problems.isEmpty()) appendLine("Reads well: nothing on the camera or off the slide, the words are big and clear enough.")
            else {
                appendLine("To fix:")
                problems.forEach { appendLine("- $it") }
            }
        }
        return MetaAnswer(
            text.trim(),
            if (problems.isEmpty()) "Looked at slide ${i + 1}: reads well" else "Looked at slide ${i + 1}: ${problems.size} to fix",
            pictures = listOfNotNull(picture),
        )
    }

    /**
     * Slide [i] of [p] as a PNG for the conversation, when the model sees (`Vision` says so for sure: a model
     * that cannot would refuse every later turn with the picture in it) and talks to the app over an API (a
     * CLI's tools answer in words alone); null otherwise, or if the window could not draw it in time.
     */
    private suspend fun slidePicture(p: Presentation, i: Int): com.kaiharimoto.mastertool.core.ai.Part.Image? {
        val ai = h.ai
        if (ai.sight != com.kaiharimoto.mastertool.core.ai.vision.Vision.Sight.YES) return null
        val wire = com.kaiharimoto.mastertool.core.ai.providers.Providers.byId(ai.prefs.connection?.provider)?.wire ?: return null
        if (wire == com.kaiharimoto.mastertool.core.ai.providers.Wire.CLAUDE_CLI || wire == com.kaiharimoto.mastertool.core.ai.providers.Wire.CODEX_CLI) return null
        val session = ai.session?.id ?: return null
        val image = present.output.picture(p, i) ?: return null
        val bytes = com.kaiharimoto.neue.platform.encodePng(image) ?: return null
        val part = ai.files.putImage(session, bytes, "image/png", image.width, image.height)
        return part.copy(data = java.util.Base64.getEncoder().encodeToString(bytes))
    }
}
