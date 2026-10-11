package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SideChooser
import com.kaiharimoto.mastertool.core.siding.SideCoverage
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.ydk.DeckCodes
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.pages.sideChoice
import com.kaiharimoto.neue.web.shareSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ai's hands on webs of decks and siding (split out of [AiHost], Phase H, H.3): reading a web, building one, a deck's
 * siding plans and its Side Deck across the field. On the same state the Format and Siding pages change.
 */
internal class AiWebs(private val h: NeueHolders, private val ai: AiState) {
    private val state get() = h.builder
    private val neue get() = h.neue
    private val webs get() = h.webs
    private val index get() = state.index

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun ok(content: JsonElement, summary: String) = MetaAnswer(content.toString(), summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    private suspend fun stored(id: String): StoredDeck? = h.deps.deckRepository.byId(id)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "list_webs" -> listWebs()
        "get_web" -> getWeb(ToolArgs.string(i, "web_id")!!)
        "get_siding" -> getSiding(ToolArgs.string(i, "deck_id")!!, ToolArgs.string(i, "against"))
        "side_coverage" -> sideCoverage(ToolArgs.string(i, "deck_id") ?: state.deckId)
        "side_choose" -> sideChoose(ToolArgs.string(i, "deck_id") ?: state.deckId)
        "create_web" -> createWeb(ToolArgs.string(i, "name")!!, ToolArgs.string(i, "notes"))
        "add_deck_to_web" -> addDeckToWeb(i)
        "set_web_entry" -> setWebEntry(i)
        "set_web_notes" -> setWebNotes(i)
        "remove_from_web" -> removeFromWeb(ToolArgs.string(i, "web_id")!!, ToolArgs.string(i, "deck_id")!!)
        "delete_web" -> deleteWeb(ToolArgs.string(i, "web_id")!!)
        "set_siding_plan" -> setSidingPlan(i)
        else -> null
    }

    private suspend fun listWebs(): MetaAnswer {
        if (webs.library.webs.isEmpty()) return ok("No webs yet. create_web makes one.", "No webs yet")
        val lines = webs.library.webs.map { w ->
            val decks = webs.decks(w).associateBy { it.entry.id }
            val entries = w.entries.joinToString("; ") { e -> (decks[e.deckId]?.entry?.name ?: "?") + (e.share?.let { " $it%" } ?: "") + (if (e.mine) " ★" else "") }
            "${w.id} | ${w.name} | ${w.entries.size} decks: $entries"
        }
        return ok(lines.joinToString("\n"), "Listed ${lines.size} webs")
    }

    private suspend fun getWeb(id: String): MetaAnswer {
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

    private suspend fun getSiding(deckId: String, against: String?): MetaAnswer {
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

    /** `side_coverage` (Phase G, G.6): the Side Deck across the deck's field, in words. */
    private suspend fun sideCoverage(deckId: String?): MetaAnswer {
        val s = deckId?.let { stored(it) } ?: return fail("Name a saved deck (deck_id), or open one in the builder.")
        val c = webs.coverage(s, state)
        if (c.cards.isEmpty() && c.unplanned.isEmpty()) return ok("“${s.entry.name}” has no Side Deck and no matchups to read.", "Nothing to cover")
        return ok("“${s.entry.name}”\n" + SideCoverage.words(c) { state.index.byId(it)?.name ?: "#${it.value}" }, "Read “${s.entry.name}”'s Side Deck across the field")
    }

    /** A Side Deck chosen for the deck's field (G2.2), in words: read-only. */
    private suspend fun sideChoose(deckId: String?): MetaAnswer {
        val s = deckId?.let { stored(it) } ?: return fail("Name a saved deck (deck_id), or open one in the builder.")
        val ch = sideChoice(h, s) ?: return fail("“${s.entry.name}” is in no web: put it in one with the decks it expects to face (Format).")
        return ok(
            "“${s.entry.name}”\n" + SideChooser.words(ch.suggestion, { state.index.byId(CardId(it))?.name ?: "#$it" }) { ch.names[it] ?: it },
            "Chose a Side Deck for “${s.entry.name}”'s field",
        )
    }

    private fun createWeb(name: String, notes: String?): MetaAnswer {
        val web = webs.create(name)
        notes?.let { webs.setNotes(web.id, it) }
        neue.go(Page.FORMAT)
        return ok("Made the web “$name” (id ${web.id}).", "Made the web “$name”")
    }

    private suspend fun addDeckToWeb(i: JsonObject): MetaAnswer {
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
        ToolArgs.int(i, "share")?.let { webs.share(webId, newId, it, shareSource(i)) }
        if (ToolArgs.bool(i, "mine") == true) webs.star(webId, newId, true)
        h.decksReload++
        return ok("Added “$name” to “${web.name}” as deck $newId." + if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else "", "Added “$name” to “${web.name}”")
    }

    private fun setWebEntry(i: JsonObject): MetaAnswer {
        val webId = ToolArgs.string(i, "web_id")!!
        val deckId = ToolArgs.string(i, "deck_id")!!
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        if (!web.has(deckId)) return fail("Deck $deckId is not in “${web.name}”.")
        ToolArgs.int(i, "share")?.let { webs.share(webId, deckId, it.takeIf { s -> s >= 0 }, shareSource(i)) }
        ToolArgs.bool(i, "mine")?.let { webs.star(webId, deckId, it) }
        ToolArgs.int(i, "position")?.let { webs.move(webId, deckId, it) }
        return ok("Updated.", "Updated a deck in “${web.name}”")
    }

    private fun setWebNotes(i: JsonObject): MetaAnswer {
        val webId = ToolArgs.string(i, "web_id")!!
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        ToolArgs.string(i, "name")?.let { webs.rename(webId, it) }
        ToolArgs.string(i, "notes")?.let { webs.setNotes(webId, it) }
        return ok("Updated “${web.name}”.", "Updated the web “${ToolArgs.string(i, "name") ?: web.name}”")
    }

    private suspend fun removeFromWeb(webId: String, deckId: String): MetaAnswer {
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        if (!web.has(deckId)) return fail("Deck $deckId is not in “${web.name}”.")
        suspendCancellableCoroutine<Unit> { cont -> webs.remove(webId, deckId) { if (cont.isActive) cont.resume(Unit) } }
        h.decksReload++
        return ok("Removed it from “${web.name}”.", "Removed a deck from “${web.name}”")
    }

    private suspend fun deleteWeb(webId: String): MetaAnswer {
        val web = webs.library.byId(webId) ?: return fail("No web $webId.")
        suspendCancellableCoroutine<Unit> { cont -> webs.delete(webId) { if (cont.isActive) cont.resume(Unit) } }
        ai.files.delete(AiMemory.path(MemoryKind.WEB, webId))
        h.decksReload++
        return ok("Deleted “${web.name}”.", "Deleted the web “${web.name}”")
    }

    private suspend fun setSidingPlan(i: JsonObject): MetaAnswer {
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
}
