package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.search.SearchScope

/**
 * Cards as a player writes them, read back into the pool's cards: a passcode, an
 * exact name, a name in any case or punctuation, a count in front ("3 Ash Blossom",
 * "3x Ash Blossom", "Ash Blossom x3"), or — failing all of those — the one card a
 * name search is sure of. A guess is reported, so Ai can say what it chose.
 */
data class CardWord(val count: Int, val text: String, val explicit: Boolean = false)

sealed interface Resolved {
    /** [explicit]: a count was written with the name. */
    data class Found(val card: Card, val count: Int, val guessed: Boolean = false, val explicit: Boolean = false) : Resolved
    data class Unknown(val text: String, val suggestions: List<String>) : Resolved
}

object CardWords {
    private val leading = Regex("^(\\d{1,2})\\s*[xX×]?\\s+(.+)$")
    private val trailing = Regex("^(.+?)\\s+[xX×]\\s*(\\d{1,2})$")

    fun split(raw: String): CardWord {
        val t = raw.trim().removePrefix("-").removePrefix("•").trim()
        // A passcode alone is a card, not a count: "14558127".
        if (t.all { it.isDigit() }) return CardWord(1, t)
        leading.find(t)?.let { m -> return CardWord(m.groupValues[1].toInt(), m.groupValues[2].trim(), explicit = true) }
        trailing.find(t)?.let { m -> return CardWord(m.groupValues[2].toInt(), m.groupValues[1].trim(), explicit = true) }
        return CardWord(1, t)
    }

    fun resolve(raw: String, index: CardIndex): Resolved {
        // A name that starts with a number is a name first ("7 Colored Fish").
        index.byName(raw.trim().removeSurrounding("[[", "]]"))?.let { return Resolved.Found(it, 1) }
        val word = split(raw)
        val count = word.count
        val explicit = word.explicit
        val text = word.text.removeSurrounding("[[", "]]").removeSurrounding("\"").trim()
        text.toIntOrNull()?.let { code -> index.byId(CardId(code))?.let { return Resolved.Found(it, count, explicit = explicit) } }
        index.byName(text)?.let { return Resolved.Found(it, count, explicit = explicit) }
        val hits = index.search(text, scope = SearchScope.NAMES, limit = 5).cards
        val exactish = hits.firstOrNull { normal(it.name) == normal(text) }
        if (exactish != null) return Resolved.Found(exactish, count, explicit = explicit)
        // One hit, or a first hit that starts with what was written: sure enough, and said so.
        val first = hits.firstOrNull()
        if (first != null && (hits.size == 1 || normal(first.name).startsWith(normal(text)))) {
            return Resolved.Found(first, count, guessed = true, explicit = explicit)
        }
        return Resolved.Unknown(text, hits.map { it.name })
    }

    private fun normal(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * A decklist in text, as players post them: section headings ("Main Deck (40)",
     * "Extra:", "Side Deck") and lines of "3 Card Name". Without headings, every line
     * is the main deck and Extra Deck monsters go to the extra by themselves.
     */
    fun deckList(text: String, index: CardIndex): Pair<Deck, List<String>> {
        val main = mutableListOf<CardId>()
        val extra = mutableListOf<CardId>()
        val side = mutableListOf<CardId>()
        val problems = mutableListOf<String>()
        var section: DeckSection? = null
        text.lines().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val head = line.lowercase().trimEnd(':').replace(Regex("\\(\\d+\\)"), "").trim()
            when {
                head.startsWith("main") || head == "#main" -> { section = DeckSection.MAIN; return@forEach }
                head.startsWith("extra") || head == "#extra" -> { section = DeckSection.EXTRA; return@forEach }
                head.startsWith("side") || head == "!side" -> { section = DeckSection.SIDE; return@forEach }
                head in setOf("monsters", "spells", "traps", "monster", "spell", "trap") || head.startsWith("#") || head.startsWith("//") -> return@forEach
            }
            when (val r = resolve(line, index)) {
                is Resolved.Found -> {
                    val target = when (section) {
                        DeckSection.SIDE -> side
                        DeckSection.EXTRA -> extra
                        else -> if (r.card.isExtraDeck) extra else main
                    }
                    repeat(r.count.coerceIn(1, 3)) { target += r.card.id }
                    if (r.guessed) problems += "Read “${CardWords.split(line).text}” as ${r.card.name}."
                }
                is Resolved.Unknown -> problems += "No card “${r.text}”" + if (r.suggestions.isNotEmpty()) " (closest: ${r.suggestions.take(3).joinToString()})." else "."
            }
        }
        return Deck(main, extra, side) to problems
    }
}
