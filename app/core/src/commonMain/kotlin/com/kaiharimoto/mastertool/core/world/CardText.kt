package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory

/**
 * What a card's text reaches for, read off Konami's phrasing (1.0.97, the instruments' red team): "add 1 Level 1 FIRE
 * monster from your Deck to your hand" is a search for every Level 1 FIRE monster in the deck, not only for the cards
 * it names in quotes. [links] reads each action clause — its verb, where it takes from, and what it takes as a
 * [Filter] — and [materials] reads an Extra Deck monster's materials line.
 *
 * **Precision over recall.** A phrase is read only when every word of it is understood: "1 monster with the same
 * Type as that monster" refers to another card in play and is skipped, never guessed. A filter that would match
 * nearly anything ("1 monster", "2+ Effect Monsters") is not a link. A clause after "cannot" is a lock, not a verb.
 */
object CardText {
    enum class Kind { MONSTER, SPELL, TRAP, SPELL_TRAP, CARD }

    /** What a phrase asks of a card. Every set field must hold; within [names], any one of them. */
    data class Filter(
        /** Quoted: a card's own name, or an archetype ("Snake-Eye") its name holds or the pool lists it under. */
        val names: List<String> = emptyList(),
        /** "that mentions "X"": the card's own text quotes X. */
        val mentions: List<String> = emptyList(),
        val except: List<String> = emptyList(),
        val kind: Kind? = null,
        /** Normal, Tuner, Effect, Ritual, Pendulum, Gemini, Spirit, Union, Flip, Toon; Quick-Play, Continuous, Field, Equip, Counter. */
        val subtypes: Set<String> = emptySet(),
        val nonTuner: Boolean = false,
        val level: IntRange? = null,
        val rank: IntRange? = null,
        val attributes: Set<Attribute> = emptySet(),
        val races: Set<String> = emptySet(),
        val atk: IntRange? = null,
        val def: IntRange? = null,
    ) {
        /** Narrow enough to be a link: something beyond "a monster" or "an Effect Monster". */
        val specific: Boolean
            get() = names.isNotEmpty() || mentions.isNotEmpty() || level != null || rank != null || attributes.isNotEmpty() ||
                races.isNotEmpty() || atk != null || def != null || nonTuner || subtypes.any { it != "Effect" }

        fun matches(c: Card): Boolean {
            if (except.any { it.equals(c.name, ignoreCase = true) }) return false
            if (names.isNotEmpty() && names.none { n -> nameHolds(c, n) }) return false
            if (mentions.isNotEmpty() && mentions.none { m -> c.description.contains("\"$m\"", ignoreCase = true) }) return false
            val monster = c.category == CardCategory.MONSTER
            when (kind) {
                Kind.MONSTER -> if (!monster) return false
                Kind.SPELL -> if (c.category != CardCategory.SPELL) return false
                Kind.TRAP -> if (c.category != CardCategory.TRAP) return false
                Kind.SPELL_TRAP -> if (c.category != CardCategory.SPELL && c.category != CardCategory.TRAP) return false
                Kind.CARD, null -> Unit
            }
            subtypes.forEach { s ->
                val ok = when (s) {
                    // A Normal Spell or Trap is its race; a Normal Monster its type.
                    "Normal" -> if (monster) c.type.contains("Normal", ignoreCase = true) else c.race.equals("Normal", ignoreCase = true)
                    "Ritual" -> if (monster) c.type.contains("Ritual", ignoreCase = true) else c.race.equals("Ritual", ignoreCase = true)
                    "Quick-Play", "Continuous", "Field", "Equip", "Counter" -> !monster && c.race.equals(s, ignoreCase = true)
                    else -> monster && c.type.contains(s, ignoreCase = true)
                }
                if (!ok) return false
            }
            if (nonTuner && (!monster || c.type.contains("Tuner", ignoreCase = true))) return false
            val xyz = c.frameType.contains("xyz", ignoreCase = true)
            val link = c.frameType.contains("link", ignoreCase = true)
            level?.let { r -> if (!monster || xyz || link || c.level == null || c.level !in r) return false }
            rank?.let { r -> if (!xyz || c.level == null || c.level !in r) return false }
            if (attributes.isNotEmpty() && (!monster || c.attribute !in attributes)) return false
            if (races.isNotEmpty() && (!monster || races.none { it.equals(c.race, ignoreCase = true) })) return false
            atk?.let { r -> if (c.atk == null || c.atk !in r) return false }
            def?.let { r -> if (c.def == null || c.def !in r) return false }
            return true
        }

        /** In words, for a link's note: "Level 1 FIRE monster". */
        fun describe(): String = buildList {
            level?.let { add("Level " + range(it)) }
            rank?.let { add("Rank " + range(it)) }
            if (attributes.isNotEmpty()) add(attributes.joinToString("/") { it.name })
            if (races.isNotEmpty()) add(races.joinToString("/"))
            if (nonTuner) add("non-Tuner")
            subtypes.forEach { add(it) }
            names.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" or ") { n -> "\"$n\"" }) }
            add(kind?.name?.lowercase()?.replace("spell_trap", "Spell/Trap") ?: "card")
            mentions.forEach { add("that mentions \"$it\"") }
            atk?.let { add("with ATK " + range(it)) }
            def?.let { add("with DEF " + range(it)) }
        }.joinToString(" ")

        private fun range(r: IntRange) = when {
            r.first == r.last -> "${r.first}"
            r.first <= 0 -> "${r.last} or lower"
            r.last >= 99_999 -> "${r.first} or higher"
            else -> "${r.first}–${r.last}"
        }
    }

    /** One action clause: [verb] (searches, recovers, summons, sends, sets, banishes) on cards [filter] reads. */
    data class Link(val verb: String, val filter: Filter, val fromDeck: Boolean)

    val RACES = listOf(
        "Aqua", "Beast-Warrior", "Beast", "Creator God", "Cyberse", "Dinosaur", "Divine-Beast", "Dragon", "Fairy", "Fiend", "Fish",
        "Illusion", "Insect", "Machine", "Plant", "Psychic", "Pyro", "Reptile", "Rock", "Sea Serpent", "Spellcaster", "Thunder",
        "Warrior", "Winged Beast", "Wyrm", "Zombie",
    )

    private val SUBTYPES = listOf("Normal", "Tuner", "Effect", "Ritual", "Pendulum", "Gemini", "Spirit", "Union", "Flip", "Toon", "Quick-Play", "Continuous", "Field", "Equip", "Counter")

    private val ACTION = Regex(
        """\b(add|special summon|send|set|banish)\s+(?:up to\s+)?(\d+|one|two|three|a|an)\s+(.+?)\s+(?:directly\s+)?from\s+(?:your\s+)?(hand|deck|main deck|extra deck|gy|graveyard|banishment)\b((?:\s*(?:,|or|and)\s*(?:your\s+)?(?:hand|deck|gy|graveyard))*)""",
        RegexOption.IGNORE_CASE,
    )

    /** Words a phrase may hold besides what it is read for; any other word and the phrase is not read at all. */
    private val GLUE = setOf("or", "and", "a", "an", "with", "lower", "higher", "less", "more", "monster", "monsters", "card", "cards", "spell", "spells", "trap", "traps", "type", "attribute", "level", "rank", "atk", "def", "")

    /** What [c]'s text reaches for, clause by clause: never itself (a card names itself to limit its own use). */
    fun links(c: Card): List<Link> = segments(c.description).flatMap { seg ->
        ACTION.findAll(seg).mapNotNull { m ->
            val before = seg.substring(0, m.range.first).lowercase()
            if ("cannot" in before || "can't" in before) return@mapNotNull null
            val verb = m.groupValues[1].lowercase()
            val sources = (m.groupValues[4] + " " + m.groupValues[5]).lowercase()
            val deck = "deck" in sources && "extra deck" !in sources
            val label = when (verb) {
                "add" -> if (deck) "searches" else if ("gy" in sources || "graveyard" in sources || "banish" in sources) "recovers" else return@mapNotNull null
                "special summon" -> "summons"
                "send" -> if (deck) "sends" else return@mapNotNull null
                "set" -> if (deck) "sets" else return@mapNotNull null
                else -> if (deck) "banishes" else return@mapNotNull null
            }
            val tail = seg.substring(m.range.last + 1)
            val except = EXCEPT.find(tail.substringBefore(';'))?.let { listOf(it.groupValues[1]) }.orEmpty()
            val f = filter(m.groupValues[3])?.let { it.copy(except = it.except + except + c.name) } ?: return@mapNotNull null
            if (!f.specific) null else Link(label, f, deck)
        }.toList()
    }

    /**
     * An Extra Deck monster's materials, one filter per part: `"Snake-Eye Ash" + 1 FIRE monster`, `1 Tuner + 1+ non-Tuner
     * monsters`, `2 monsters, including a "Snake-Eye" monster`. Only the parts specific enough to be a link.
     */
    fun materials(c: Card): List<Filter> {
        if (!c.isExtraDeck) return emptyList()
        val first = c.description.lineSequence().firstOrNull()?.trim().orEmpty()
        if (first.isEmpty() || ':' in first || first.length > 200 || first.endsWith('.') && !first.contains('+')) return emptyList()
        if (!(first.contains('+') || first.first().isDigit() || first.startsWith('"'))) return emptyList()
        val parts = splitOutsideQuotes(first, " + ").flatMap { p ->
            val inc = Regex(""",?\s*including\s+(.+)$""", RegexOption.IGNORE_CASE).find(p)
            if (inc != null) listOf(p.substring(0, inc.range.first), inc.groupValues[1]) else listOf(p)
        }
        return parts.mapNotNull { p ->
            val q = p.trim().replace(Regex("""^(\d+)\+?\s*"""), "")
            // A material named alone in quotes: that card (or archetype), with no kind word after it.
            val f = if (q.startsWith('"') && q.endsWith('"') && q.count { it == '"' } == 2) Filter(names = listOf(q.trim('"'))) else filter(q)
            f?.takeIf { it.specific }
        }
    }

    private val EXCEPT = Regex("""except\s+"([^"]+)"""", RegexOption.IGNORE_CASE)

    /**
     * A noun phrase read into a [Filter], or null when any part of it is not understood (precision over recall):
     * "Level 4 or lower Fiend monster", "\"Snake-Eye\" monster", "Spell/Trap that mentions \"X\"", "FIRE Tuner",
     * "monster with 1500 or less ATK".
     */
    fun filter(phrase: String): Filter? {
        var p = " " + phrase.trim().removeSuffix(",") + " "
        val except = mutableListOf<String>()
        val mentions = mutableListOf<String>()
        Regex(""",?\s*except\s+"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(p).forEach { except += it.groupValues[1] }
        p = p.replace(Regex(""",?\s*except\s+"[^"]+"""", RegexOption.IGNORE_CASE), " ")
        Regex("""\b(?:that|which)\s+(?:mentions|lists)\s+"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(p).forEach { mentions += it.groupValues[1] }
        p = p.replace(Regex("""\b(?:that|which)\s+(?:mentions|lists)\s+"[^"]+"""", RegexOption.IGNORE_CASE), " ")
        val names = Regex(""""([^"]+)"""").findAll(p).map { it.groupValues[1] }.toList()
        p = p.replace(Regex(""""[^"]+""""), " ")

        fun take(r: Regex, f: (MatchResult) -> Unit) {
            r.findAll(p).toList().forEach(f)
            p = p.replace(r, " ")
        }

        var level: IntRange? = null
        var rank: IntRange? = null
        var atk: IntRange? = null
        var def: IntRange? = null
        fun bound(n: Int, how: String?): IntRange = when (how?.lowercase()) {
            "lower", "less" -> 0..n
            "higher", "more" -> n..99_999
            else -> n..n
        }
        take(Regex("""\b(Level|Rank)\s+(\d+)(?:\s+or\s+(lower|higher|less|more))?""", RegexOption.IGNORE_CASE)) { m ->
            val r = bound(m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { null })
            if (m.groupValues[1].equals("rank", true)) rank = r else level = r
        }
        take(Regex("""\bwith\s+(\d+)(?:\s+or\s+(less|more|lower|higher))?\s+(ATK|DEF)\b""", RegexOption.IGNORE_CASE)) { m ->
            val r = bound(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { null })
            if (m.groupValues[3].equals("atk", true)) atk = r else def = r
        }
        // Attributes are written in capitals: "FIRE", never "fire" in a name's sense.
        val attributes = mutableSetOf<Attribute>()
        take(Regex("""\b(DARK|LIGHT|EARTH|WATER|FIRE|WIND|DIVINE)\b""")) { attributes += Attribute.valueOf(it.groupValues[1]) }
        var nonTuner = false
        take(Regex("""\bnon-Tuners?\b""", RegexOption.IGNORE_CASE)) { nonTuner = true }
        val races = mutableSetOf<String>()
        RACES.sortedByDescending { it.length }.forEach { r -> take(Regex("""\b${Regex.escape(r)}(?:-Type)?\b""")) { races += r } }
        var kind: Kind? = null
        take(Regex("""\bSpells?/Traps?\b|\bSpell\s+or\s+Trap\b""", RegexOption.IGNORE_CASE)) { kind = Kind.SPELL_TRAP }
        val subtypes = mutableSetOf<String>()
        SUBTYPES.forEach { s -> take(Regex("""\b${Regex.escape(s)}s?\b""")) { subtypes += s } }
        take(Regex("""\bmonsters?\b""", RegexOption.IGNORE_CASE)) { kind = kind ?: Kind.MONSTER }
        take(Regex("""\bSpells?\b(?:\s+Cards?)?""")) { kind = if (kind == Kind.TRAP) Kind.SPELL_TRAP else Kind.SPELL }
        take(Regex("""\bTraps?\b(?:\s+Cards?)?""")) { kind = if (kind == Kind.SPELL) Kind.SPELL_TRAP else Kind.TRAP }
        take(Regex("""\bcards?\b""", RegexOption.IGNORE_CASE)) { kind = kind ?: Kind.CARD }
        // A "Tuner" on its own is a monster; a Normal or Effect with no kind word too.
        if (kind == null && subtypes.any { it in setOf("Tuner", "Normal", "Effect", "Ritual", "Pendulum") }) kind = Kind.MONSTER
        if (kind == null && (level != null || rank != null || attributes.isNotEmpty() || races.isNotEmpty() || nonTuner)) kind = Kind.MONSTER
        val left = p.lowercase().split(Regex("""[\s,]+""")).filter { it !in GLUE }
        if (left.isNotEmpty()) return null
        if (kind == null && names.isEmpty() && mentions.isEmpty()) return null
        return Filter(names, mentions, except, kind, subtypes, nonTuner, level, rank, attributes, races, atk, def)
    }

    private fun nameHolds(c: Card, n: String): Boolean =
        c.name.equals(n, ignoreCase = true) || c.archetype.equals(n, ignoreCase = true) || c.name.contains(n, ignoreCase = true)

    /** A card's text cut into clauses at `.`, `;`, `:` and line breaks — never inside quotes ("D.D. Crow"). */
    fun segments(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        text.forEach { ch ->
            when {
                ch == '"' -> { quoted = !quoted; cur.append(ch) }
                !quoted && (ch == '.' || ch == ';' || ch == ':' || ch == '\n' || ch == '●') -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun splitOutsideQuotes(text: String, sep: String): List<String> {
        val out = mutableListOf<String>()
        var quoted = false
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '"') quoted = !quoted
            if (!quoted && text.startsWith(sep, i)) {
                out += text.substring(start, i)
                i += sep.length
                start = i
                continue
            }
            i++
        }
        out += text.substring(start)
        return out
    }

    // ---- What a card does, for composition: read off the text, approximate and said to be ---------------------

    /** The roles a player counts by, read off [c]'s text: searcher, summons from Deck, draws, negates, hand trap, removal. */
    fun roles(c: Card): Set<String> {
        val t = c.description
        val low = t.lowercase()
        val ls = links(c)
        return buildSet {
            if (ls.any { it.verb == "searches" }) add("searches")
            if (ls.any { it.verb == "summons" && it.fromDeck }) add("summons from Deck")
            if (Regex("""\bdraw\s+(\d+|a|one|two)\s+cards?\b""").containsMatchIn(low)) add("draws")
            if ("negate" in low) add("negates")
            if (c.category == CardCategory.MONSTER && (
                    "discard this card" in low ||
                        Regex("""send this card from your hand to the gy""").containsMatchIn(low) ||
                        ("(quick effect)" in low && "this card in your hand" in low) ||
                        ("special summon this card from your hand" in low && "opponent" in low && "main phase" in low)
                    )
            ) add("hand trap")
            if (Regex("""\b(destroy|banish|return)\b[^.]*\b(your opponent controls|on the field|opponent's field)""").containsMatchIn(low)) add("removal")
        }
    }
}
