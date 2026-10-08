package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords

/*
 * Gameplay Mapper in words (M.md §6): what the page writes beside a board and what Ai is told — the same sentences, so the
 * person and Ai read one library. Card names come from the caller ([name]); nothing here ranks a board.
 */
object MapperWords {
    /** A trait's name as the page shows it. */
    fun head(h: String): String = when (h) {
        "interruptions" -> "Interruptions"
        "negates" -> "Negates"
        "removal" -> "Removal"
        "bodies" -> "Bodies"
        "set" -> "Set"
        "hand" -> "Kept in hand"
        "gy" -> "In the GY"
        "banished" -> "Banished"
        "handInterruptions" -> "Hand traps kept"
        else -> if (h.startsWith(BoardTraits.THROUGH)) "Through ${h.removePrefix(BoardTraits.THROUGH)}" else h
    }

    /** A trait's short name, for a column or a chip. */
    fun short(h: String): String = when (h) {
        "interruptions" -> "Int"
        "negates" -> "Neg"
        "removal" -> "Rem"
        "bodies" -> "Bod"
        "set" -> "Set"
        "hand" -> "Hand"
        "gy" -> "GY"
        "banished" -> "Ban"
        "handInterruptions" -> "HT"
        else -> if (h.startsWith(BoardTraits.THROUGH)) h.removePrefix(BoardTraits.THROUGH) else h
    }

    /** What a board measures in a sentence: "3 interruptions (2 negates, 1 removal), 4 bodies, 2 set, 1 kept in hand". */
    fun traits(t: BoardTraits): String = buildString {
        append(plural(t.interruptions, "interruption"))
        if (t.interruptions > 0) {
            val parts = listOfNotNull(
                t.negates.takeIf { it > 0 }?.let { plural(it, "negate") },
                t.removal.takeIf { it > 0 }?.let { "$it removal" },
            )
            if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(")")
        }
        append(", ").append(plural(t.bodies, "body", "bodies"))
        if (t.set > 0) append(", ").append(t.set).append(" set")
        append(", ").append(t.hand).append(" kept in hand")
        if (t.handInterruptions > 0) append(" (").append(plural(t.handInterruptions, "hand trap")).append(")")
        t.through.entries.sortedBy { it.key }.forEach { (k, n) -> append("; through ").append(k).append(": ").append(plural(n, "interruption")) }
    }

    /** The board's cards: "Field: A, B (under B: C), Token ×2 · Set: 2 · Hand: D · GY: E". */
    fun cards(c: BoardCards, name: (Int) -> String): String {
        val under = c.under.associate { s -> s.substringBefore(':').toIntOrNull() to s.substringAfter(':', "").split('+').mapNotNull { it.toIntOrNull() } }
        val field = c.monsters.filter { it != 0 }.map { m ->
            val mats = under[m].orEmpty()
            if (mats.isEmpty()) name(m) else "${name(m)} (under it: ${mats.joinToString { name(it) }})"
        } + c.tokens.groupingBy { it }.eachCount().map { (t, n) -> if (n > 1) "$t ×$n" else t } +
            c.setMonsters.map { "a set monster" } + c.spells.map(name)
        return listOfNotNull(
            "Field: " + field.ifEmpty { listOf("nothing") }.joinToString(", "),
            c.set.takeIf { it.isNotEmpty() }?.let { "Set: ${it.joinToString { n -> name(n) }}" },
            c.hand.takeIf { it.isNotEmpty() }?.let { "Hand: ${it.joinToString { n -> name(n) }}" },
            c.gy.takeIf { it.isNotEmpty() }?.let { "GY: ${it.joinToString { n -> name(n) }}" },
            c.banished.takeIf { it.isNotEmpty() }?.let { "Banished: ${it.joinToString { n -> name(n) }}" },
        ).joinToString(" · ")
    }

    /** A share of hands: "41.2 % of 1,000 hands (95 %: 38.2–44.3 %)"; "no run has counted it" for none. */
    fun share(s: Share): String =
        if (s.of == 0) "no run has counted it"
        else "${GoldfishWords.pct(s.share)} of ${GoldfishWords.count(s.of)} hands (95 %: ${GoldfishWords.interval(s.hits, s.of)})"

    /** One move: "Normal Summon A", "Activate B's effect", "Summon C by its procedure", "To the Battle Phase". */
    fun step(s: MapStep, name: (Int) -> String): String {
        val card = s.card?.let(name) ?: "a card"
        return when (s.kind) {
            "a" -> "Activate $card"
            "n" -> "Normal Summon $card"
            "s" -> "Set $card"
            "p" -> "Summon $card by its procedure"
            "f" -> DuelPhase.entries.firstOrNull { it.name == s.what }?.let { "To ${if (it.label.startsWith("Main")) it.label else "the ${it.label} Phase"}" } ?: "To another phase"
            "x" -> "Pass"
            "r" -> "Resolve the chain"
            else -> "A move this version cannot read"
        }
    }

    /** A line in a sentence: "From A + B: Normal Summon A, Activate A, …; Set C". */
    fun line(l: MapLine, name: (Int) -> String): String = buildString {
        append("From ").append(l.starter.joinToString(" + ") { name(it) }).append(": ")
        val moves = l.steps.filter { it.kind != "x" && it.kind != "r" }.map { step(it, name) }
        append(moves.ifEmpty { listOf("nothing played") }.joinToString(", "))
        if (l.sets.isNotEmpty()) append("; ").append(plural(l.sets.size, "card")).append(" Set at the end")
    }

    private fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
}
