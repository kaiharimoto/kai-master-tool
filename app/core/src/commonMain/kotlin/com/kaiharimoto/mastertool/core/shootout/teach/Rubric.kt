package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryDoc
import com.kaiharimoto.mastertool.core.ai.memory.MemoryWrite
import com.kaiharimoto.mastertool.core.shootout.store.TrialNote

/**
 * The rubric (S.md §6½ "What Ai learns"): how this person judges this matchup, in words — "a hand with a starter and a
 * hand trap beats their turn one unless it is Ash-only". Markdown entries in `<deck>/<matchup>.rubric.md` beside the
 * trials ([com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths.rubric]), written from the interview and the
 * person's notes, reviewed like the guide, and every number in it carrying its proof (the evidence ledger).
 */
object Rubric {
    /** The whole rubric and one entry, in characters: short enough to hand Ai with every hand. */
    const val LIMIT = 6_000
    const val ENTRY = 600

    fun title(deck: String, opponent: String?): String = "Rubric: $deck" + (opponent?.let { " against $it" } ?: " on its own")

    fun read(text: String?, deck: String, opponent: String?): MemoryDoc =
        text?.takeIf { it.isNotBlank() }?.let(AiMemory::parse) ?: MemoryDoc.blank(title(deck, opponent))

    fun add(doc: MemoryDoc, entry: String): MemoryWrite = AiMemory.add(doc, entry, LIMIT, ENTRY)
    fun replace(doc: MemoryDoc, old: String, entry: String): MemoryWrite = AiMemory.replace(doc, old, entry, LIMIT, ENTRY)
    fun remove(doc: MemoryDoc, old: String): MemoryWrite = AiMemory.remove(doc, old)

    /**
     * A short stable name for what a rubric says (FNV-1a over its entries, in order): kept on each of Ai's answers so
     * what it was shown is on record. Null when there is no rubric yet.
     */
    fun hash(text: String?): String? {
        val entries = text?.let { AiMemory.parse(it).entries }.orEmpty()
        if (entries.isEmpty()) return null
        var h = -0x340d631b7bdddcdbL
        for (ch in entries.joinToString("\n")) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0').take(12)
    }

    /** The rubric as Ai is handed it: its entries as a list, or a line saying there is none yet. */
    fun forPrompt(text: String?): String {
        val entries = text?.let { AiMemory.parse(it).entries }.orEmpty()
        return if (entries.isEmpty()) "(No rubric yet: judge from the examples and the cards.)" else entries.joinToString("\n") { "- $it" }
    }
}

/**
 * The person's notes that keep coming back (S.md §6½: "the recurring ones are offered for the rubric"): a word of
 * substance — a card's name, "imperm", "bricked" — written in the notes of at least [min] different trials is a theme,
 * offered with the notes behind it. Most first; a theme whose notes another already holds is left out.
 */
object RubricNotes {

    class Theme(val word: String, val notes: List<TrialNote>)

    fun recurring(notes: List<TrialNote>, min: Int = 3, max: Int = 5): List<Theme> {
        val byWord = LinkedHashMap<String, MutableList<TrialNote>>()
        notes.forEach { n ->
            words(n.text).forEach { w -> byWord.getOrPut(w) { mutableListOf() }.add(n) }
        }
        val themes = byWord.entries
            .map { (w, ns) -> Theme(w, ns.distinctBy { it.trial }) }
            .filter { it.notes.size >= min }
            .sortedWith(compareByDescending<Theme> { it.notes.size }.thenBy { it.word })
        val out = ArrayList<Theme>()
        for (t in themes) {
            val trials = t.notes.map { it.trial }.toSet()
            if (out.any { o -> o.notes.map { it.trial }.toSet().containsAll(trials) }) continue
            out += t
            if (out.size == max) break
        }
        return out
    }

    /** The words of substance in [text]: four letters or more, lower case, not one of the common ones. */
    fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in COMMON }.toSet()

    private val COMMON = setOf(
        "this", "that", "with", "they", "them", "their", "there", "then", "than", "have", "only", "when", "what", "will",
        "would", "could", "should", "into", "from", "your", "just", "hand", "hands", "wins", "win", "lose", "loses",
        "card", "cards", "game", "turn", "does", "doesn", "unless", "because", "even", "more", "less", "much", "very",
        "still", "also", "some", "were", "been", "being", "here", "make", "makes", "going", "first", "second", "opponent",
    )
}
