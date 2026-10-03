package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.QueryKind

/**
 * The table read aloud (1.0.87): a typed or spoken question — `hand`, `their field`, `ogy`, `lp`, `chain`, `?m3` —
 * answered in words through the asker's eyes only. A card the viewer cannot see is "a face-down card", the other
 * seat's hand is a count, and a Deck's order is no one's: every name here passes [DuelSight] first.
 */
object DuelAnswer {

    fun answer(q: Parsed.Query, s: DuelState, viewer: Int, catalog: DuelCatalog, secret: Long = 0L): String {
        val seat = if (q.theirs) 1 - viewer else viewer
        if (q.theirs && s.solo) return "There is no other player at this table."
        val your = if (q.theirs) "Their" else "Your"
        fun name(uid: Int): String = s.cards[uid]?.takeIf { DuelSight.sees(s, uid, viewer) }?.let { catalog.nameOf(it) } ?: "a face-down card"
        fun listed(uids: List<Int>, coord: (Int, Int) -> String?): String =
            uids.mapIndexed { i, u -> listOfNotNull(coord(i, u), name(u)).joinToString(" ") }.joinToString(", ")
        val st = s.seats[seat]
        return when (q.kind) {
            QueryKind.HAND -> when {
                st.hand.isEmpty() -> "$your hand is empty."
                seat != viewer -> "Their hand: ${count(st.hand.size, "card")}."
                else -> "Your hand (${st.hand.size}): ${listed(DuelNotation.handOrder(s, seat, viewer, secret)) { i, _ -> "h${i + 1}" }}."
            }
            QueryKind.FIELD -> field(s, seat, viewer, catalog, secret)
            QueryKind.BOARD -> if (s.solo) field(s, viewer, viewer, catalog, secret) else field(s, viewer, viewer, catalog, secret) + " " + field(s, 1 - viewer, viewer, catalog, secret)
            QueryKind.GY -> pile(your, "GY", st.gy, if (q.theirs) "ogy" else "gy", ::name)
            QueryKind.BANISHED -> pile(your, "banished cards", st.banished, if (q.theirs) "oban" else "ban", ::name)
            QueryKind.EXTRA -> {
                val shown = st.extra.filter { DuelSight.sees(s, it, viewer) }
                if (seat == viewer) pile(your, "Extra Deck", st.extra, "ex", ::name)
                else "Their Extra Deck: ${count(st.extra.size, "card")}" + if (shown.isEmpty()) "." else ", face-up: ${shown.joinToString(", ") { name(it) }}."
            }
            QueryKind.DECK -> "$your Deck: ${count(st.deck.size, "card")}."
            QueryKind.LP -> if (s.solo) "${DuelWords.seatName(s, viewer)}: ${s.seats[viewer].lp} LP."
                else "You: ${s.seats[viewer].lp} LP. ${DuelWords.seatName(s, 1 - viewer)}: ${s.seats[1 - viewer].lp} LP."
            QueryKind.CHAIN -> if (s.chain.isEmpty()) "There is no chain." else "The chain: " + s.chain.mapIndexed { i, l ->
                val whose = if (l.seat == viewer) "yours" else "theirs"
                "${i + 1}. ${l.uid?.let { name(it) } ?: l.note.ifBlank { "an effect" }} ($whose)"
            }.joinToString(", ") + "."
            QueryKind.TURN -> "Turn ${s.turn}: ${if (s.active == viewer) "your" else DuelWords.possessive(DuelWords.seatName(s, s.active))} ${s.phase.label} Phase."
            QueryKind.CARD -> q.uid?.let { card(s, it, viewer, catalog, secret) } ?: "Which card?"
        }
    }

    /** One card, as far as the viewer can see it: name, kind, ATK/DEF, position, counters, materials. */
    fun card(s: DuelState, uid: Int, viewer: Int, catalog: DuelCatalog, secret: Long = 0L): String {
        val c = s.cards[uid] ?: return "That card has left the duel."
        val where = DuelNotation.coordOf(s, uid, viewer, secret)
        val place = s.placeOf(uid)
        val monsterZone = place is Place.Zone && (place.kind == ZoneKind.MONSTER || place.kind == ZoneKind.EMZ)
        val position = when {
            !monsterZone -> if (place is Place.Zone && !c.faceUp) "Set" else null
            !c.faceUp -> "face-down Defense Position"
            c.defense -> "Defense Position"
            else -> "Attack Position"
        }
        if (!DuelSight.sees(s, uid, viewer)) {
            return listOfNotNull(where?.let { "$it:" }, "a face-down card", position?.let { "in $it" }).joinToString(" ") + "."
        }
        val info = catalog.info(c.code)
        val kind = when (DuelVerbs.kindOf(c, catalog)) {
            CardKind.MONSTER -> "Monster"
            CardKind.EXTRA_MONSTER -> "Extra Deck monster"
            CardKind.SPELL -> listOfNotNull(info?.sub, "Spell").joinToString(" ")
            CardKind.FIELD_SPELL -> "Field Spell"
            CardKind.TRAP -> listOfNotNull(info?.sub, "Trap").joinToString(" ")
            CardKind.TOKEN -> "Token"
        }
        val stats = listOfNotNull(DuelBattle.atk(c, catalog)?.let { "ATK $it" }, DuelBattle.def(c, catalog)?.let { "DEF $it" }).joinToString(" ").takeIf { it.isNotEmpty() }
        val counters = c.counters.entries.joinToString(", ") { (k, n) -> "$n ${k.ifBlank { "counter" }}${if (n == 1) "" else "s"}" }.takeIf { it.isNotEmpty() }
        val under = if (c.under.isEmpty()) null else "${count(c.under.size, "material")}: ${c.under.joinToString(", ") { u -> s.cards[u]?.let { catalog.nameOf(it) } ?: "a card" }}"
        val owner = if (c.owner != viewer && place is Place.Pile && place.kind != PileKind.HAND) "theirs" else null
        return (where?.let { "$it: " } ?: "") + listOfNotNull(catalog.nameOf(c), kind, stats, position?.let { "in $it" }, counters, under, owner).joinToString(", ") + "."
    }

    private fun field(s: DuelState, seat: Int, viewer: Int, catalog: DuelCatalog, secret: Long): String {
        val st = s.seats[seat]
        val cards = buildList {
            st.monsters.forEach { it?.let(::add) }
            s.emz.forEach { u -> if (u != null && s.cards[u]?.controller == seat) add(u) }
            st.spells.forEach { it?.let(::add) }
            st.field?.let(::add)
        }
        val whose = if (seat == viewer) "Your" else "Their"
        if (cards.isEmpty()) return "$whose field is empty."
        return "$whose field: " + cards.joinToString("; ") { uid -> card(s, uid, viewer, catalog, secret).removeSuffix(".") } + "."
    }

    private fun pile(your: String, what: String, uids: List<Int>, coord: String, name: (Int) -> String): String =
        if (uids.isEmpty()) "$your $what: none." else "$your $what (${uids.size}): " + uids.mapIndexed { i, u -> "$coord${i + 1} ${name(u)}" }.joinToString(", ") + "."

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
