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
 */
object Secrets {
    data class Redacted(val text: String, val hidden: List<String>) {
        val changed: Boolean get() = hidden.isNotEmpty()
    }

    fun redact(text: String, s: DuelState, opponent: Int, aiSeat: Int, catalog: DuelCatalog): Redacted {
        if (s.solo || opponent == aiSeat) return Redacted(text, emptyList())
        val seen = s.cards.values.filter { DuelSight.sees(s, it.uid, opponent) }.map { catalog.nameOf(it).lowercase() }.toSet()
        val secret = s.cards.values
            .filter { it.owner == aiSeat || it.controller == aiSeat }
            .filter { !it.token && !DuelSight.sees(s, it.uid, opponent) }
            .map { catalog.nameOf(it) }
            .filter { it.length >= 3 && !it.startsWith("#") && it.lowercase() !in seen }
            .distinct()
            // Longer names first, so a name inside a longer one is not cut out of it.
            .sortedByDescending { it.length }
        var out = text
        val hidden = mutableListOf<String>()
        secret.forEach { name ->
            val pattern = Regex("(\\[\\[)?(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])(\\]\\])?", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(out)) {
                hidden += name
                out = pattern.replace(out, "a card")
            }
        }
        return Redacted(out, hidden)
    }
}
