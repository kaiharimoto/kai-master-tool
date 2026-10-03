package com.kaiharimoto.mastertool.core.duel.ai

import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.nameOf

/**
 * Ai's own secrets kept out of what it writes where its opponent reads (1.0.81, kai: "it said explicitly
 * 'I drew X'. Private information should be in thinking and not shown unless I want to see it"). The
 * names of Ai's cards the person's seat cannot see — its hand, its Deck, its set cards, a face-down Extra
 * Deck — are put as "a card", unless the person can see a card of that name anyway (a face-up copy, one
 * revealed). Judged through the person's seat's own eyes, whatever the hot-seat shows. The prompt asks
 * Ai not to say them; this is the guard for when it does.
 *
 * From 1.0.86 it also knows the names players actually say — "Ash Blossom", "Droll", "Nibiru" for
 * "Ash Blossom & Joyous Spring", "Droll & Lock Bird", "Nibiru, the Primal Being" ([shortNames]) — and it
 * guards Ai's questions and their answers in the log's foot too ([options], [answer]).
 */
object Secrets {
    data class Redacted(val text: String, val hidden: List<String>) {
        val changed: Boolean get() = hidden.isNotEmpty()
    }

    fun redact(text: String, s: DuelState, opponent: Int, aiSeat: Int, catalog: DuelCatalog): Redacted {
        if (s.solo || opponent == aiSeat) return Redacted(text, emptyList())
        return redact(text, names(s, opponent, aiSeat, catalog))
    }

    /** The names to keep out: Ai's hidden cards' full names, then the short names players say for them. */
    fun names(s: DuelState, opponent: Int, aiSeat: Int, catalog: DuelCatalog): List<String> {
        if (s.solo || opponent == aiSeat) return emptyList()
        val seen = s.cards.values.filter { DuelSight.sees(s, it.uid, opponent) }.map { catalog.nameOf(it).lowercase() }.toSet()
        val secret = s.cards.values
            .filter { it.owner == aiSeat || it.controller == aiSeat }
            .filter { !it.token && !DuelSight.sees(s, it.uid, opponent) }
            .map { catalog.nameOf(it) }
            .filter { it.length >= 3 && !it.startsWith("#") && it.lowercase() !in seen }
            .distinct()
            // Longer names first, so a name inside a longer one is not cut out of it.
            .sortedByDescending { it.length }
        val everyName = s.cards.values.filter { !it.token }.map { catalog.nameOf(it) }.distinct()
        val short = shortNames(secret, seen, everyName).filter { it !in secret }
        return secret + short.sortedByDescending { it.length }
    }

    private fun redact(text: String, names: List<String>): Redacted {
        var out = text
        val hidden = mutableListOf<String>()
        names.forEach { name ->
            val pattern = Regex("(\\[\\[)?(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])(\\]\\])?", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(out)) {
                hidden += name
                out = pattern.replace(out, "a card")
            }
        }
        return Redacted(out, hidden)
    }

    /**
     * The short names of [secret] players say: a name's head before " - ", " & ", ", " or " of the " — when
     * the head is two words or more, or one word of five letters or more that is not an everyday word
     * ([COMMON]). Never one a card the person can see goes by ([seen], lower case), and never the head of
     * two different cards on the table ([everyName]): that is an archetype ("Destiny HERO"), not a card.
     * Conservative on purpose — a word cut out of Ai's every sentence makes it unreadable.
     */
    fun shortNames(secret: List<String>, seen: Set<String>, everyName: List<String>): List<String> {
        val heads = everyName.groupBy { head(it)?.lowercase() }
        return secret.mapNotNull { name ->
            val h = head(name) ?: return@mapNotNull null
            val lower = h.lowercase()
            val words = h.split(' ').filter { it.isNotBlank() }
            val distinctive = words.size >= 2 || (h.count { it.isLetter() } >= 5 && lower !in COMMON)
            when {
                !distinctive -> null
                (heads[lower]?.size ?: 0) > 1 -> null
                seen.any { it == lower || wordRun(it, lower) } -> null
                else -> h
            }
        }.distinct()
    }

    /** The head of a card's name before its first separator, or null when it has none. */
    fun head(name: String): String? {
        val cut = SEPARATORS.mapNotNull { sep -> name.indexOf(sep, ignoreCase = true).takeIf { it > 0 } }.minOrNull() ?: return null
        return name.substring(0, cut).trim().takeIf { it.isNotEmpty() }
    }

    private fun wordRun(text: String, run: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(run) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)

    /** What a question's options show the person, and which option each stood for. */
    data class Options(val shown: List<String>, val stoodFor: Map<String, String>)

    /**
     * [options] as the person may read them: a hidden card's name put as "a card", numbered when two
     * would read the same ("a card (2)"), so the answer can still be told apart and handed back as Ai wrote
     * it ([answer]) — Ai knows its own cards.
     */
    fun options(options: List<String>, s: DuelState, opponent: Int, aiSeat: Int, catalog: DuelCatalog): Options {
        val names = names(s, opponent, aiSeat, catalog)
        val shown = ArrayList<String>()
        val stoodFor = LinkedHashMap<String, String>()
        options.forEach { o ->
            val r = if (names.isEmpty()) o else redact(o, names).text
            var label = r
            var n = 2
            while (label in stoodFor) label = "$r (${n++})"
            shown += label
            stoodFor[label] = o
        }
        return Options(shown, stoodFor)
    }

    /** The person's answer, its picked options turned back into what Ai wrote; their own words as they are. */
    fun answer(answer: String, o: Options): String =
        answer.split("; ").joinToString("; ") { part -> o.stoodFor[part] ?: part }

    private val SEPARATORS = listOf(" - ", " & ", ", ", " of the ")

    /** Everyday words, and the game's own, that a one-word head must not be (it would be cut out of every sentence). */
    val COMMON: Set<String> = setOf(
        "about", "above", "after", "again", "against", "ancient", "angel", "attack", "battle", "beast", "black", "blade",
        "blast", "blaze", "blood", "bonds", "brave", "break", "burst", "chain", "chaos", "charge", "circle", "cosmic",
        "crystal", "curse", "cyber", "damage", "darkness", "death", "defense", "demon", "destiny", "destroy", "destruction",
        "divine", "double", "dragon", "dream", "earth", "effect", "elemental", "emperor", "empress", "eternal", "evil",
        "extra", "fairy", "field", "fiend", "final", "first", "flame", "force", "fusion", "galaxy", "ghost", "giant",
        "golden", "grand", "grave", "great", "guardian", "heart", "heavy", "hero", "holy", "infinite", "knight", "light",
        "little", "lord", "machine", "magic", "magical", "master", "mirror", "monster", "mystic", "mystical", "night",
        "normal", "number", "phantom", "phase", "power", "queen", "rescue", "return", "ritual", "royal", "sacred",
        "second", "shadow", "silent", "silver", "solemn", "special", "spell", "spirit", "storm", "summon", "super",
        "sword", "their", "there", "these", "third", "those", "thunder", "token", "tribute", "triple", "twilight",
        "ultimate", "under", "water", "where", "which", "white", "while", "witch", "world", "would", "zombie",
    )
}
