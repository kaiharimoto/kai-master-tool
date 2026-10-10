package com.kaiharimoto.mastertool.core.cards

import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.model.BanStatus

/**
 * The banlist in words, for Ai's `banlist` tool and a world's scripts: which list, when it was in force, and the cards
 * on it — the list's own names, as Yugipedia wrote them.
 */
object BanlistWords {
    fun status(s: BanStatus): String = when (s) {
        BanStatus.FORBIDDEN -> "Forbidden"
        BanStatus.LIMITED -> "Limited"
        BanStatus.SEMI_LIMITED -> "Semi-Limited"
        BanStatus.UNLIMITED -> "Unlimited"
    }

    /** "7 Apr 2025 – 14 Sep 2025", or "since 21 Sep 2026" for a list still in force. */
    fun span(list: LimitationList): String =
        if (list.end == null) "in force since ${Legality.readable(list.start)}"
        else "in force ${Legality.readable(list.start)} – ${Legality.readable(list.end)}"

    /** One line naming the list: its title, its region and when it held. */
    fun header(list: LimitationList): String = "${list.title} — the ${list.region.name} Forbidden & Limited list, ${span(list)}"

    /** The list's cards by status, the cards that came off it last; [unmatched] (names the pool did not match) said at the end. */
    fun body(list: LimitationList, unmatched: List<String> = emptyList()): String = buildString {
        listOf(BanStatus.FORBIDDEN, BanStatus.LIMITED, BanStatus.SEMI_LIMITED).forEach { s ->
            val names = list.at(s)
            append(status(s)).append(" (").append(names.size).append("): ")
            append(if (names.isEmpty()) "none" else names.joinToString(", "))
            append('\n')
        }
        val off = list.at(BanStatus.UNLIMITED)
        if (off.isNotEmpty()) append("Unlimited now (came off the list) (").append(off.size).append("): ").append(off.joinToString(", ")).append('\n')
        if (list.inferred.isNotEmpty()) {
            append("Not on Yugipedia's page, but restricted alike on the lists before and after, so read as such (inferred): ")
                .append(list.inferred.sorted().joinToString(", ")).append('\n')
        }
        if (unmatched.isNotEmpty()) {
            append("Not matched to a card in the app's pool (${unmatched.size}): ").append(unmatched.take(30).joinToString(", "))
            if (unmatched.size > 30) append(", and ${unmatched.size - 30} more")
            append('\n')
        }
    }.trimEnd()

    /** What moved between two lists, in words. */
    fun changes(c: ListChanges): String {
        val head = "From ${c.from.title} (${Legality.readable(c.from.start)}) to ${c.to.title} (${Legality.readable(c.to.start)}): "
        if (c.from.title == c.to.title) return head + "the same list, nothing moved."
        if (c.changes.isEmpty()) return head + "nothing moved."
        return head + "${c.changes.size} cards moved.\n" + c.changes.joinToString("\n") { "- ${it.name}: ${status(it.before)} → ${status(it.after)}" }
    }

    /**
     * A card's history in one line for the inspector (Phase G, G.7; the red team's F4, its first half): each stretch it was
     * restricted, by year — "Limited 2019–21 · Semi-Limited 2023–now". Null for a card never restricted on a list kept here.
     * Words only, never a chance of a coming change.
     */
    fun line(spells: List<BanSpell>): String? {
        val restricted = spells.filter { it.status != BanStatus.UNLIMITED }
        if (restricted.isEmpty()) return null
        return restricted.joinToString(" · ") { s ->
            val from = s.from.take(4)
            val until = s.until?.take(4)
            val years = when {
                until == null -> "$from–now"
                until == from -> from
                until.take(2) == from.take(2) -> "$from–${until.drop(2)}"
                else -> "$from–$until"
            }
            "${status(s.status)} $years"
        }
    }

    /** A card's stretches at each status, oldest first. */
    fun history(name: String, spells: List<BanSpell>): String {
        if (spells.isEmpty()) return "$name has never been on a list kept here: Unlimited throughout."
        return "$name through the lists:\n" + spells.joinToString("\n") { s ->
            val until = s.until?.let { " until ${Legality.readable(it)}" } ?: " to now (the newest list kept)"
            "- ${status(s.status)} from ${Legality.readable(s.from)}$until (${s.lists.first()}" +
                (if (s.lists.size > 1) " … ${s.lists.last()}, ${s.lists.size} lists" else "") + ")"
        }
    }
}
