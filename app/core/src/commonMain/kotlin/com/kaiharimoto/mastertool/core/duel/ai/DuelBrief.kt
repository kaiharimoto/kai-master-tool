package com.kaiharimoto.mastertool.core.duel.ai

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.ViewCard
import com.kaiharimoto.mastertool.core.duel.text.DuelWords

/**
 * The duel table in words, as one seat sees it — what Ai reads before it plays (`duel_state`). Built
 * from [DuelView], so a hidden card is never named: it is "a face-down card" with a veil (`?…`) that
 * holds while it stays where it is. A card the seat can see is written with its uid (`#17`), which
 * every command and `duel_act` op accepts in place of a name.
 *
 * Knowledge is three ways, kai's (1.0.76): **full** (every card, as a tester wants), **one seat's**
 * (what that player could know — no more), and **auto**, the seat's own view with `duel_peek` for when
 * Ai judges it needs more, every peek written in the log for both players to see.
 */
object DuelBrief {
    const val FULL = "full"
    const val SELF = "self"
    const val OPPONENT = "opponent"
    const val AUTO = "auto"
    val PERSPECTIVES = listOf(FULL, SELF, OPPONENT, AUTO)

    /** The seat a perspective reads through, for Ai acting as [seat]; null is everything. */
    fun viewer(perspective: String, seat: Int): Int? = when (perspective) {
        FULL -> null
        OPPONENT -> 1 - seat
        else -> seat
    }

    fun describe(s: DuelState, viewer: Int?, catalog: DuelCatalog, secret: Long, seat: Int? = viewer): String {
        val v = DuelView.of(s, viewer, secret)
        fun name(c: ViewCard): String {
            if (c.code == null) return "a face-down card [?${-c.ref}]"
            val n = if (c.token) (c.name ?: "Token") else catalog.info(c.code)?.name ?: "#${c.code}"
            val pos = when (c.pos) {
                CardPosition.FACE_UP_ATK -> ""
                CardPosition.FACE_UP_DEF -> ", Defense"
                CardPosition.FACE_DOWN_DEF -> ", set"
                CardPosition.FACE_DOWN_ATK -> ", set"
            }
            val extra = buildList {
                if (c.counters.isNotEmpty()) add(c.counters.entries.joinToString { (k, n) -> "$n ${k.ifBlank { "counter" }}" })
                if (c.under.isNotEmpty()) add("materials: " + c.under.joinToString { name(it) })
            }.joinToString("; ")
            return "#${c.ref} $n$pos${if (extra.isNotEmpty()) " ($extra)" else ""}"
        }
        fun list(cards: List<ViewCard>) = if (cards.isEmpty()) "none" else cards.joinToString(", ") { name(it) }
        fun zones(cards: List<ViewCard?>, prefix: String) =
            cards.mapIndexed { i, c -> "$prefix${i + 1} ${c?.let(::name) ?: "—"}" }.joinToString(" · ")
        return buildString {
            val active = DuelWords.seatName(s, s.active)
            appendLine("Turn ${s.turn} · $active's turn · ${s.phase.label} Phase" + if (s.solo) " · one player's table" else "")
            appendLine("You read the table as: ${if (viewer == null) "everything (full knowledge)" else "${DuelWords.seatName(s, viewer)} (seat $viewer) — only what that player could see"}")
            if (seat != null) appendLine("You act as: ${DuelWords.seatName(s, seat)} (seat $seat)")
            if (s.chain.isNotEmpty()) {
                appendLine("Chain: " + v.chain.mapIndexed { i, l ->
                    val card = l.uid?.let { ref -> if (ref > 0) s.cards[ref]?.let { catalog.info(it.code)?.name ?: it.name } else "a face-down card" }
                    "${i + 1}) ${card ?: l.note.ifBlank { "an effect" }} by ${DuelWords.seatName(s, l.seat)}"
                }.joinToString("; "))
            }
            val emz = v.emz.mapIndexed { i, c -> "${if (i == 0) "left" else "right"} ${c?.let { "${name(it)} (${DuelWords.seatName(s, it.controller)}'s)" } ?: "—"}" }
            appendLine("Extra Monster Zones: ${emz.joinToString(" · ")}")
            appendLine()
            val seats = if (s.solo) listOf(0) else listOf(0, 1)
            seats.forEach { i ->
                val st = v.seats[i]
                appendLine("${DuelWords.seatName(s, i)} (seat $i) · ${st.lp} LP${if (i in s.thinking) " · thinking" else ""}")
                appendLine("  Hand (${st.hand.size}): ${list(st.hand)}")
                appendLine("  Monster Zones: ${zones(st.monsters, "M")}")
                appendLine("  Spell & Trap Zones: ${zones(st.spells, "S")}")
                appendLine("  Field Zone: ${st.field?.let(::name) ?: "—"}")
                appendLine("  GY (${st.gy.size}): ${list(st.gy)}")
                appendLine("  Banished (${st.banished.size}): ${list(st.banished)}")
                val extraKnown = st.extra.filter { it.code != null }
                appendLine("  Extra Deck (${st.extra.size}): ${if (extraKnown.isEmpty()) "unseen" else list(extraKnown) + if (extraKnown.size < st.extra.size) ", and ${st.extra.size - extraKnown.size} unseen" else ""}")
                appendLine("  Deck: ${st.deck} cards${if (st.deckKnown.isNotEmpty()) " (known: " + st.deckKnown.entries.joinToString { (k, c) -> "${k + 1} from the top ${name(c)}" } + ")" else ""}")
            }
            if (s.arrows.isNotEmpty()) appendLine("Arrows: " + v.arrows.joinToString("; ") { a -> "${DuelWords.seatName(s, a.seat)} → ${a.to.joinToString { "#$it" }}" })
        }.trimEnd()
    }
}
