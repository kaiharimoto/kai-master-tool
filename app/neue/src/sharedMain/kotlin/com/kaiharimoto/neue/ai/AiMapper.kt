package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.MapperTools
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.mapper.BoardLibrary
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.MapEnd
import com.kaiharimoto.mastertool.core.duel.mapper.MapPlan
import com.kaiharimoto.mastertool.core.duel.mapper.MapperPresets
import com.kaiharimoto.mastertool.core.duel.mapper.MapperReport
import com.kaiharimoto.mastertool.core.duel.mapper.MapperWords
import com.kaiharimoto.mastertool.core.duel.mapper.Pareto
import com.kaiharimoto.mastertool.core.duel.mapper.Mapper
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Ablation
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareAsk
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareWords
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckChange
import com.kaiharimoto.mastertool.core.duel.mapper.compare.MapCache
import com.kaiharimoto.mastertool.core.duel.mapper.compare.PairedMath
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Variants
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.mapper.MapperSide
import com.kaiharimoto.neue.mapper.Mappers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Gameplay Mapper, Ai's hands (Phase M step M1, M.md §6): `mapper_library` reads a deck's library as a query ranks it,
 * `mapper_starters` reads or runs the starter table, `mapper_map` deals and maps hands (or maps one), and `mapper_preset`
 * keeps a query as Ai's preset. Every run is the page's own ([Mappers]): on the builder's deck, shown on page 10 with the
 * person's Stop, and kept with the deck.
 */
internal class AiMapper(private val h: NeueHolders) {
    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "mapper_library" -> library(i)
        "mapper_starters" -> starters(i)
        "mapper_map" -> map(i)
        "mapper_preset" -> preset(i)
        "deck_compare" -> compare(i)
        "mapper_ablate" -> ablate(i)
        else -> null
    }

    /** The ask in [i]: a board passing its filters, else at least its interruptions (default 1). */
    private fun askOf(i: JsonObject): Pair<CompareAsk, List<String>> {
        val (q, problems) = MapperTools.query(i, BoardPreset(), ::card)
        if (q.filters.isNotEmpty()) return CompareAsk.of(q.copy(name = "a board passing ${MapperReport.query(q, nameOf)}")) to problems
        return CompareAsk.interruptions((ToolArgs.int(i, "interruptions") ?: 1).coerceIn(1, 5)) to problems
    }

    private suspend fun compare(i: JsonObject): MetaAnswer {
        val id = h.builder.deckId ?: return fail("Save the deck first: a comparison is made on the builder's deck.")
        val m = prepared(id) ?: return fail("The deck's boards could not be read.")
        val first = ToolArgs.bool(i, "second") != true
        val seed = ToolArgs.int(i, "seed")?.toLong() ?: 1L
        val most = (ToolArgs.int(i, "hands") ?: Mappers.COMPARE_HANDS).coerceIn(100, 4_000)
        val kit = h.goldfishKit()
        val a = h.goldfishDeck()
        val index = h.builder.index
        val (ask, problems) = askOf(i)
        val (b, label) = ToolArgs.string(i, "variant_deck_id")?.let { vid ->
            val s = h.webs.stored(vid) ?: return fail("No saved deck $vid.")
            val d = s.entry.deck
            GoldfishDeck(d.main.map { it.value }, d.extra.map { it.value }, s.entry.id, Ledger.fingerprint(d, index::byId), s.entry.name) to "against ${s.entry.name}"
        } ?: run {
            val changes = ToolArgs.objects(i, "changes").map { o ->
                val out = ToolArgs.string(o, "out")?.let { w -> card(w)?.let(kit::canonical) ?: return fail("No card “$w”.") }
                val into = ToolArgs.string(o, "into")?.let { w -> card(w)?.let(kit::canonical) ?: return fail("No card “$w”.") }
                if (out == null && into == null) return fail("Each change names out, into or both.")
                DeckChange(out, into, (ToolArgs.int(o, "copies") ?: 1).coerceIn(1, 3))
            }
            if (changes.isEmpty()) return fail("Give the change (changes) or another deck (variant_deck_id).")
            val v = Variants.apply(a, changes, { c -> index.byId(CardId(c))?.isExtraDeck == true })
                ?: return fail("The deck does not hold that many copies to cut.")
            v to changes.joinToString("; ") { c ->
                listOfNotNull(c.out?.let { "cut ${c.copies} ${nameOf(it)}" }, c.into?.let { "add ${c.copies} ${nameOf(it)}" }).joinToString(", ")
            }
        }
        val job = withContext(Dispatchers.Main) { m.startCompare(a, b, label, ask, kit, first, most, seed) }
        if (job == null) {
            val refused = m.compared?.result?.guard?.takeIf { !it.ok }
            return if (refused != null) fail(CompareWords.refused(refused, nameOf)) else fail(m.said ?: "The comparison could not start.")
        }
        withContext(Dispatchers.Main) { m.comparing = true }
        job.join()
        val r = m.compared?.result ?: return fail(m.said ?: "The comparison kept nothing.")
        val text = buildString {
            append("Compared ${side(first)}, seed $seed: $label.\n")
            CompareWords.all(r, ask.name, name = nameOf).forEach { append(it).append("\n") }
            if (r.changed.isNotEmpty()) {
                append("Hands that changed (k: as it is → with the change):\n")
                r.changed.take(CHANGED_SHOWN).forEach { ch ->
                    append("  ${ch.k + 1}: ${ch.a.name.lowercase()} → ${ch.b.name.lowercase()} · ${ch.handB.joinToString(", ") { nameOf(it) }}\n")
                }
                if (r.changed.size > CHANGED_SHOWN) append("  … ${r.changed.size - CHANGED_SHOWN} more, on page 10.\n")
            }
            append("Scripts library ${Mapper.scripts(a, kit)}.")
            problems.forEach { append("\n").append(it) }
        }
        return ok(text, "Compared: ${PairedMath.points(r.paired.difference)} points")
    }

    private suspend fun ablate(i: JsonObject): MetaAnswer {
        val id = h.builder.deckId ?: return fail("Save the deck first.")
        val m = prepared(id) ?: return fail("The deck's boards could not be read.")
        val first = ToolArgs.bool(i, "second") != true
        val kit = h.goldfishKit()
        val deck = h.goldfishDeck()
        val copies = if (ToolArgs.string(i, "copies") == "all") Ablation.Copies.ALL else Ablation.Copies.ONE
        val hands = (ToolArgs.int(i, "hands") ?: Mappers.WITHOUT_HANDS).coerceIn(50, 2_000)
        val (ask, _) = askOf(i)
        val word = ToolArgs.string(i, "card")!!.trim()
        if (word.equals("engine", ignoreCase = true)) {
            val job = withContext(Dispatchers.Main) { m.startWithout(deck, kit, ask, copies, first, hands) } ?: return fail(m.said ?: "It could not start.")
            job.join()
            val rows = m.without?.rows?.values.orEmpty().sortedByDescending { it.worth }
            if (rows.isEmpty()) return fail(m.said ?: "Nothing was measured.")
            val text = buildString {
                append("Each engine card ${if (copies == Ablation.Copies.ALL) "cut whole" else "less one copy"}, ${side(first)}, on ${GoldfishWords.count(hands)} hands each; asked ${ask.name}:\n")
                rows.forEach { r ->
                    append("  ${nameOf(r.card)}: ${PairedMath.points(-r.comparison.paired.difference)} points without it (95 %: ${CompareWords.interval(r.comparison.paired.interval)}), ${r.bricks.size} hands lose it\n")
                }
                append("Scripts library ${Mapper.scripts(deck, kit)}.")
            }
            return ok(text, "Measured ${rows.size} cards without them")
        }
        val code = card(word)?.let(kit::canonical) ?: return fail("No card “$word”.")
        // The page's cache is the page's runs' alone, one at a time: a card measured here maps into a cache of its own.
        if (m.busy) return fail("${m.running?.kind?.words ?: "A run"} already: wait for it, or ask the person to stop it.")
        val row = withContext(Dispatchers.Default) {
            Ablation.run(deck, code, copies, ask, kit, MapCache(), first, ToolArgs.int(i, "seed")?.toLong() ?: 1L, hands, Mappers.RUN_BUDGET)
        } ?: return fail("${nameOf(code)} is not in the deck.")
        val c = row.comparison
        c.guard?.takeIf { !it.ok }?.let { return fail(CompareWords.refused(it, nameOf)) }
        val text = buildString {
            append("${nameOf(code)} ${if (copies == Ablation.Copies.ALL) "cut whole" else "less one copy"} (a blank in its place), ${side(first)}, ${GoldfishWords.count(c.paired.hands)} hands dealt both ways; asked ${ask.name}:\n")
            append("With it ${GoldfishWords.pct(c.paired.a)}, without it ${GoldfishWords.pct(c.paired.b)}: ${PairedMath.points(c.paired.difference)} points (95 %: ${CompareWords.interval(c.paired.interval)}).\n")
            append("${row.bricks.size} hands lose it without the card.")
            if (c.kindsLost.isNotEmpty()) append(" Kinds of board no hand reaches without it: ${c.kindsLost.take(4).joinToString("; ") { MapperWords.traits(it) }}.")
            append("\nScripts library ${Mapper.scripts(deck, kit)}.")
        }
        return ok(text, "Without ${nameOf(code)}: ${PairedMath.points(c.paired.difference)} points")
    }

    private val nameOf: (Int) -> String
        get() {
            val index = h.builder.index
            return { c -> index.byId(CardId(c))?.name ?: "#$c" }
        }

    private fun card(word: String): Int? = when (val r = CardWords.resolve(word, h.builder.index)) {
        is Resolved.Found -> r.card.id.value
        is Resolved.Unknown -> null
    }

    /** [deckId] ("open" or none: the builder's deck), or null when the builder's deck is not saved. */
    private fun idOf(deckId: String?): String? =
        if (deckId == null || deckId.equals("open", ignoreCase = true)) h.builder.deckId else deckId

    /** [id]'s files going first and second, and its presets: the page's when it is the deck loaded, else read from disk. */
    private suspend fun files(id: String): Pair<Map<Boolean, MapperSide>, MapperPresets> {
        val m = h.mapper
        if (m.deckId == id && m.loaded) return m.sides to m.presets
        return withContext(Dispatchers.IO) { m.read(id) to m.readPresets(id) }
    }

    private suspend fun library(i: JsonObject): MetaAnswer {
        val id = idOf(ToolArgs.string(i, "deck_id")) ?: return fail("Save the deck first: its boards are kept with it.")
        val first = ToolArgs.bool(i, "second") != true
        val (sides, presets) = files(id)
        val side = sides[first] ?: MapperSide()
        if (side.unreadable.isNotEmpty()) return fail("${side.unreadable.joinToString()} could not be read by this version.")
        val lib = side.library
        ToolArgs.string(i, "board")?.let { key ->
            val e = lib.byKey[key] ?: lib.boards.firstOrNull { it.key.startsWith(key) } ?: return fail("No board $key in the library ${side(first)}.")
            return ok(MapperReport.board(e, side.run?.takeIf { it.deck == lib.deck }, nameOf), "Read a board of the library")
        }
        val named = ToolArgs.string(i, "preset")?.let { n -> presets.all.firstOrNull { it.name.equals(n, ignoreCase = true) || it.id == n } ?: return fail("No preset “$n”: ${presets.all.joinToString { it.name }}.") }
        val (query, problems) = MapperTools.query(i, named ?: BoardPreset(), ::card)
        val limit = (ToolArgs.int(i, "limit") ?: 8).coerceIn(1, 40)
        val text = MapperReport.library(lib, side.run, query, limit, nameOf) + problems.joinToString("") { "\n$it" }
        return ok(text, "Read the board library ${side(first)}")
    }

    private suspend fun starters(i: JsonObject): MetaAnswer {
        val id = idOf(ToolArgs.string(i, "deck_id")) ?: return fail("Save the deck first: its boards are kept with it.")
        val first = ToolArgs.bool(i, "second") != true
        val only = ToolArgs.string(i, "card")?.let { w -> card(w) ?: return fail("No card “$w”.") }
        if (ToolArgs.bool(i, "run") == true) {
            if (id != h.builder.deckId) return fail("A run maps the builder's deck: open_deck it first.")
            val m = prepared(id) ?: return fail("The deck's boards could not be read.")
            val job = m.startStarters(h.goldfishDeck(), h.goldfishKit(), first, pairs = ToolArgs.bool(i, "pairs") != false)
                ?: return fail(m.said ?: "The starter table could not start.")
            job.join()
        }
        val (sides, _) = files(id)
        val side = sides[first] ?: MapperSide()
        val table = side.starters ?: return ok("No starter table ${side(first)} yet: map it with run: true.", "Read the starter table")
        val said = if (ToolArgs.bool(i, "run") == true) (h.mapper.said?.let { "$it\n" } ?: "") else ""
        return ok(said + MapperReport.starters(table, side.library, only, nameOf), "Read the starter table ${side(first)}")
    }

    private suspend fun map(i: JsonObject): MetaAnswer {
        val id = h.builder.deckId ?: return fail("Save the deck first: its boards are kept with it.")
        ToolArgs.string(i, "deck_id")?.let { d -> if (!d.equals("open", ignoreCase = true) && d != id) return fail("A run maps the builder's deck: open_deck it first.") }
        val first = ToolArgs.bool(i, "second") != true
        val seed = ToolArgs.int(i, "seed")?.toLong() ?: 1L
        val m = prepared(id) ?: return fail("The deck's boards could not be read.")
        val deck = h.goldfishDeck()
        val kit = h.goldfishKit()
        val words = ToolArgs.strings(i, "cards")
        if (words.isNotEmpty()) {
            val cards = words.map { w -> card(w)?.let(kit::canonical) ?: return fail("No card “$w”.") }
            val engine = cards.filterNot(kit::inert).sorted()
            val deal = MapDeal(engine, first, seed, cards.filter(kit::inert).sorted())
            val done = withContext(Dispatchers.Default) {
                MapPlan(deck.main.map(kit::canonical), deck.extra.map(kit::canonical), kit, Mappers.RUN_BUDGET).map(deal)
            }
            val fronts = BoardLibrary().admits(done.ends, againstLibrary = false)
            val best = fronts.sortedWith(compareByDescending<MapEnd> { it.traits.interruptions }
                .thenByDescending { it.traits.negates }.thenByDescending { it.traits.hand }).take(ONE_HAND_BOARDS)
            val text = buildString {
                append("The hand ${cards.joinToString(" + ") { nameOf(it) }} ${side(first)}: ")
                append("${GoldfishWords.count(done.ends.size)} end boards, ${GoldfishWords.count(fronts.size)} fields")
                if (!done.complete) append(" (not searched to the end: there may be more)")
                append(", ${GoldfishWords.count(done.moves)} engine moves.")
                val front = Pareto.front(best.map { e -> BoardTraits.MORE_IS_BETTER.map { e.traits[it] ?: 0.0 }.toDoubleArray() })
                best.forEachIndexed { n, e ->
                    append("\n").append(n + 1).append(". ").append(MapperWords.traits(e.traits))
                    if (n in front) append(" (front)")
                    append("\n   ").append(MapperWords.cards(e.cards, nameOf))
                    append("\n   ").append(MapperWords.line(e.line, nameOf))
                }
                if (fronts.size > best.size) append("\n… ${fronts.size - best.size} more fields.")
                if (engine.isEmpty()) append("\nNo card of this hand has a written effect the mapper trusts: it plays nothing.")
            }
            return ok(text, "Mapped one hand")
        }
        val hands = (ToolArgs.int(i, "hands") ?: AI_HANDS).coerceIn(1, AI_MOST)
        val job = m.startHands(deck, kit, hands, seed, first) ?: return fail(m.said ?: "The run could not start.")
        job.join()
        val side = m.sides[first] ?: MapperSide()
        val run = side.run ?: return fail(m.said ?: "The run kept nothing.")
        return ok(MapperReport.run(run) + "\nRead the library with mapper_library.", "Mapped ${GoldfishWords.count(run.hands)} dealt hands")
    }

    private suspend fun preset(i: JsonObject): MetaAnswer {
        val id = h.builder.deckId ?: return fail("Save the deck first: presets are kept with it.")
        val m = prepared(id) ?: return fail("The deck's boards could not be read.")
        ToolArgs.string(i, "remove")?.let { n ->
            val p = m.presets.presets.firstOrNull { it.name.equals(n, ignoreCase = true) || it.id == n } ?: return fail("No preset “$n”.")
            if (p.by != BoardPreset.AI) return fail("“${p.name}” is the person's preset: only they delete it.")
            m.deletePreset(p.id)?.join()
            return ok("Deleted your preset “${p.name}”.", "Deleted a mapper preset")
        }
        val name = ToolArgs.string(i, "name") ?: return fail("Give the preset a name.")
        val same = m.presets.presets.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (same != null && same.by != BoardPreset.AI) return fail("“${same.name}” is the person's preset: name yours differently.")
        val (query, problems) = MapperTools.query(i, BoardPreset(), ::card)
        val p = query.copy(id = same?.id ?: "", name = name, why = ToolArgs.string(i, "why").orEmpty())
        m.putAiPreset(p)?.join()
        return ok("Kept “$name” as your preset and put it on page 10: ${MapperReport.query(p, nameOf)}." + problems.joinToString("") { "\n$it" }, "Kept a mapper preset: $name")
    }

    /** The holder with [id]'s files read and the written effects loaded, or null. */
    private suspend fun prepared(id: String): Mappers? {
        val fx = h.effects
        if (!fx.loaded) fx.reloadNow()
        val m = h.mapper
        withContext(Dispatchers.Main) { m.open(id) }?.join()
        return m.takeIf { it.deckId == id && it.loaded }
    }

    private fun side(first: Boolean) = if (first) "going first" else "going second"

    companion object {
        /** Hands Ai deals when it does not say: a run Ai starts is seen through in a minute or two. */
        const val AI_HANDS = 100
        const val AI_MOST = 2_000

        /** Boards listed for one hand. */
        const val ONE_HAND_BOARDS = 8

        /** Changed hands listed in a comparison's answer. */
        const val CHANGED_SHOWN = 12
    }
}
