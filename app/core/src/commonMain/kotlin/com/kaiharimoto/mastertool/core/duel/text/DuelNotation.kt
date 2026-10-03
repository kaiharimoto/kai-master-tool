package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * Table notation (1.0.87, Command mode): every place on the table has a short name from the viewer's own side,
 * as a chessboard's squares do, so a duel can be played by typing and by voice alone.
 *
 * | Coordinate | Place |
 * |---|---|
 * | `h1…` | your hand, left to right (`h1` is the hand's first card) |
 * | `m1`–`m5` | your Monster Zones |
 * | `s1`–`s5` | your Spell & Trap Zones |
 * | `e1`, `e2` | the Extra Monster Zones, by their absolute index (left and right as seat 0 sees them) |
 * | `fz` | your Field Zone |
 * | `gy`, `ban`, `ex`, `dk` | your piles; `gy3` is the third card from the top |
 *
 * The other seat's places take an `o` prefix: `oh3`, `om1`, `os2`, `ofz`, `ogy`, `ogy2`, `oban`, `oex`, `odk`.
 * Case does not matter. A hidden card may be *addressed* by its coordinate — `target oh3`, `destroy os2`, as a hand
 * reaches across a table — but nothing here ever names a card; naming is the caller's, through [DuelSight].
 */
object DuelNotation {

    enum class Kind(val short: String, val pile: PileKind?, val zone: ZoneKind?) {
        HAND("h", PileKind.HAND, null),
        MONSTER("m", null, ZoneKind.MONSTER),
        SPELL("s", null, ZoneKind.SPELL),
        EMZ("e", null, ZoneKind.EMZ),
        FIELD("fz", null, ZoneKind.FIELD),
        GY("gy", PileKind.GY, null),
        BANISHED("ban", PileKind.BANISHED, null),
        EXTRA("ex", PileKind.EXTRA, null),
        DECK("dk", PileKind.DECK, null),
        ;

        /** A zone on the field (or an Extra Monster Zone), not a pile or the hand. */
        val onField: Boolean get() = zone != null
    }

    /**
     * A place as the viewer names it: [mine] is the viewer's own side (an Extra Monster Zone is always "mine": the
     * zones are shared and named absolutely). [index] is 0-based — `m3` is 2 — and null for a whole pile (`gy`) and
     * the Field Zone's only slot is 0.
     */
    data class Coord(val mine: Boolean, val kind: Kind, val index: Int? = null) {
        /** The coordinate as typed: `m3`, `ogy2`, `e1`, `fz`. */
        override fun toString(): String = buildString {
            if (!mine && kind != Kind.EMZ) append('o')
            append(kind.short)
            when (kind) {
                Kind.FIELD -> Unit
                else -> if (index != null) append(index + 1)
            }
        }
    }

    private val pattern = Regex("^(o)?(h|m|s|gy|ban|ex|dk)(\\d{1,2})?$")
    private val emz = Regex("^e([12])$")
    private val field = Regex("^(o)?fz$")

    /** The coordinate [word] is, or null. `m1`/`m2` parse here; the command line keeps them as phases when alone. */
    fun parse(word: String): Coord? {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return null
        emz.find(w)?.let { return Coord(true, Kind.EMZ, it.groupValues[1].toInt() - 1) }
        field.find(w)?.let { return Coord(it.groupValues[1].isEmpty(), Kind.FIELD, 0) }
        val m = pattern.find(w) ?: return null
        val mine = m.groupValues[1].isEmpty()
        val kind = when (m.groupValues[2]) {
            "h" -> Kind.HAND
            "m" -> Kind.MONSTER
            "s" -> Kind.SPELL
            "gy" -> Kind.GY
            "ban" -> Kind.BANISHED
            "ex" -> Kind.EXTRA
            else -> Kind.DECK
        }
        val n = m.groupValues[3].toIntOrNull()
        return when (kind) {
            Kind.HAND -> if (n == null || n < 1) null else Coord(mine, kind, n - 1)
            Kind.MONSTER, Kind.SPELL -> if (n == null || n !in 1..DuelState.ZONES) null else Coord(mine, kind, n - 1)
            else -> if (n != null && n < 1) null else Coord(mine, kind, n?.let { it - 1 })
        }
    }

    /** The seat a coordinate's side is, for [viewer]. */
    fun seatOf(c: Coord, viewer: Int): Int = if (c.mine) viewer else 1 - viewer

    /**
     * Where [c] is on the table. A zone is a [Place.Zone] (an Extra Monster Zone carries the viewer's seat, as a
     * monster put there is theirs); a pile or a hand card is a [Place.Pile] at its index (null: the pile itself).
     */
    fun toPlace(c: Coord, viewer: Int): Place {
        val seat = seatOf(c, viewer)
        return when (c.kind) {
            Kind.MONSTER -> Place.Zone(seat, ZoneKind.MONSTER, c.index ?: 0)
            Kind.SPELL -> Place.Zone(seat, ZoneKind.SPELL, c.index ?: 0)
            Kind.EMZ -> Place.Zone(viewer, ZoneKind.EMZ, c.index ?: 0)
            Kind.FIELD -> Place.Zone(seat, ZoneKind.FIELD, 0)
            else -> Place.Pile(seat, c.kind.pile!!, c.index)
        }
    }

    /**
     * A hand in the order [viewer] is shown it: their own in its real order, the other seat's as [DuelView] deals it —
     * sorted by each card's veil, so which card came in last is not the viewer's to know (1.0.85). `oh3` is the third
     * card of *this* order. [secret] is the duel's (its seed), the one [DuelView] veils with.
     */
    fun handOrder(s: DuelState, seat: Int, viewer: Int, secret: Long = 0L): List<Int> {
        val hand = s.seats.getOrNull(seat)?.hand ?: return emptyList()
        if (seat == viewer) return hand
        return hand.sortedBy { uid -> if (DuelSight.sees(s, uid, viewer)) uid else DuelView.veil(secret, uid, s.epoch[uid] ?: 0) }
    }

    /** The uid at [c] for [viewer] — a whole pile is its top card — or null when nothing is there. */
    fun at(s: DuelState, c: Coord, viewer: Int, secret: Long = 0L): Int? = when (val p = toPlace(c, viewer)) {
        is Place.Zone -> s.at(p)
        is Place.Pile -> if (p.kind == PileKind.HAND) handOrder(s, p.seat, viewer, secret).getOrNull(p.at ?: 0)
            else s.seats.getOrNull(p.seat)?.pile(p.kind)?.getOrNull(p.at ?: 0)
        else -> null
    }

    /** [at] for a typed word: null when it is no coordinate, or nothing is there. */
    fun at(s: DuelState, word: String, viewer: Int, secret: Long = 0L): Int? = parse(word)?.let { at(s, it, viewer, secret) }

    /**
     * The coordinate of the card [uid] for [viewer]: `m3`, `oh2`, `gy1`, `e2`. Null for a material (it sits under a
     * card, not in a place of its own), a card gone from the duel, and a Deck card the viewer does not know — a
     * deck's order is no one's to see.
     */
    fun coordOf(s: DuelState, uid: Int, viewer: Int, secret: Long = 0L): String? {
        val p = s.placeOf(uid) ?: return null
        if (p is Place.Pile && p.kind == PileKind.DECK && !DuelSight.sees(s, uid, viewer)) return null
        if (p is Place.Pile && p.kind == PileKind.HAND) {
            val i = handOrder(s, p.seat, viewer, secret).indexOf(uid)
            return slotCoord(p.copy(at = i), viewer)
        }
        return slotCoord(p, viewer)
    }

    /**
     * The coordinate of a place, filled or empty: a zone (`m3`, `os2`, `e1`, `fz`), a pile (`gy`, `oban`), or a card's
     * place in a pile when it has an index (`gy3`, `oh2`). A hand without an index has no coordinate of its own.
     */
    fun slotCoord(place: Place, viewer: Int): String? = when (place) {
        is Place.Zone -> {
            val mine = place.seat == viewer
            when (place.kind) {
                ZoneKind.MONSTER -> Coord(mine, Kind.MONSTER, place.index)
                ZoneKind.SPELL -> Coord(mine, Kind.SPELL, place.index)
                ZoneKind.FIELD -> Coord(mine, Kind.FIELD, 0)
                ZoneKind.EMZ -> Coord(true, Kind.EMZ, place.index)
            }.toString()
        }
        is Place.Pile -> {
            val kind = Kind.entries.first { it.pile == place.kind }
            val at = place.at?.takeIf { it >= 0 }
            if (kind == Kind.HAND && at == null) null else Coord(place.seat == viewer, kind, at).toString()
        }
        else -> null
    }

    /**
     * A coordinate's label for the preview's words: a zone as the log writes it (`M3`, `S2`, `E1`, `FZ`), a pile by
     * its name (`GY`), the other seat's prefixed "their".
     */
    fun label(c: Coord): String {
        val base = when (c.kind) {
            Kind.MONSTER -> "M${(c.index ?: 0) + 1}"
            Kind.SPELL -> "S${(c.index ?: 0) + 1}"
            Kind.EMZ -> "E${(c.index ?: 0) + 1}"
            Kind.FIELD -> "FZ"
            Kind.HAND -> if (c.index == null) "hand" else "h${c.index + 1}"
            Kind.GY -> if (c.index == null) "GY" else "GY ${c.index + 1}"
            Kind.BANISHED -> if (c.index == null) "banished" else "banished ${c.index + 1}"
            Kind.EXTRA -> if (c.index == null) "Extra Deck" else "Extra Deck ${c.index + 1}"
            Kind.DECK -> if (c.index == null) "Deck" else "Deck ${c.index + 1}"
        }
        return if (c.mine || c.kind == Kind.EMZ) base else "their $base"
    }

    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val NUMBERS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    private fun number(n: Int): String = NUMBERS.getOrNull(n - 1) ?: n.toString()
    private fun ordinal(n: Int): String = ORDINALS.getOrNull(n - 1) ?: "number $n"

    /** A coordinate in words, as it is said aloud: "your monster zone three", "their graveyard", "the second card in your hand". */
    fun spoken(c: Coord): String {
        val whose = if (c.mine) "your" else "their"
        val i = c.index?.plus(1)
        return when (c.kind) {
            Kind.MONSTER -> "$whose monster zone ${number(i ?: 1)}"
            Kind.SPELL -> "$whose spell and trap zone ${number(i ?: 1)}"
            Kind.EMZ -> "extra monster zone ${number(i ?: 1)}"
            Kind.FIELD -> "$whose field zone"
            Kind.HAND -> if (i == null) "$whose hand" else "the ${ordinal(i)} card in $whose hand"
            Kind.GY -> if (i == null) "$whose graveyard" else "the ${ordinal(i)} card in $whose graveyard"
            Kind.BANISHED -> if (i == null) "$whose banished cards" else "the ${ordinal(i)} of $whose banished cards"
            Kind.EXTRA -> if (i == null) "$whose extra deck" else "the ${ordinal(i)} card in $whose extra deck"
            Kind.DECK -> if (i == null) "$whose deck" else "the ${ordinal(i)} card of $whose deck"
        }
    }

    /** Every zone coordinate on the field for one side, in reading order — for completion and hints. */
    fun zones(mine: Boolean): List<Coord> =
        (0 until DuelState.ZONES).map { Coord(mine, Kind.MONSTER, it) } +
            (0 until DuelState.ZONES).map { Coord(mine, Kind.SPELL, it) } +
            listOf(Coord(mine, Kind.FIELD, 0))

    /** The pile coordinates of one side. */
    fun piles(mine: Boolean): List<Coord> = listOf(Kind.GY, Kind.BANISHED, Kind.EXTRA, Kind.DECK).map { Coord(mine, it, null) }
}
