package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.FxWalk
import com.kaiharimoto.mastertool.core.duel.effects.Op
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardCheck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Interruptions
import kotlinx.serialization.Serializable

/*
 * An end board read two ways (M.md §2.3): what it is ([BoardCards], keyed by [BoardKey]) and what it measures ([BoardTraits]).
 * Nothing here is an opinion: every trait is counted off the table and the trusted scripts, and a board's worth is left to the
 * person's weights at query time ([BoardQuery]) — kai: a ranking given in advance skews what the model learns.
 */

/** What an end board holds, by card identity (canonical passcodes, sorted): what the page draws and the key is made from. */
@Serializable
data class BoardCards(
    /** Face-up monsters (the Extra Monster Zone the seat holds counted in). */
    val monsters: List<Int> = emptyList(),
    /** Face-down monsters. */
    val setMonsters: List<Int> = emptyList(),
    /** Face-up Spells and Traps, and the Field Spell. */
    val spells: List<Int> = emptyList(),
    /** Set Spells and Traps. */
    val set: List<Int> = emptyList(),
    val hand: List<Int> = emptyList(),
    val gy: List<Int> = emptyList(),
    val banished: List<Int> = emptyList(),
    /** Materials attached, by host: "host:material". */
    val under: List<String> = emptyList(),
) {
    companion object {
        /** Seat [seat]'s board on [t]. A token is "T" + its name, so two tokens of one kind are equal. */
        fun of(t: FxTable, seat: Int): BoardCards {
            val s = t.state
            val side = s.seats[seat]
            fun code(uid: Int): Int = t.code(uid) ?: 0
            val mons = side.monsters.filterNotNull() + s.emz.filterNotNull().filter { s.cards[it]?.controller == seat }
            val up = mons.filter { s.cards[it]?.faceUp == true }
            val down = mons.filter { s.cards[it]?.faceUp == false }
            val st = side.spells.filterNotNull() + listOfNotNull(side.field)
            val under = mons.flatMap { h -> (s.cards[h]?.under ?: emptyList()).map { m -> "${code(h)}:${code(m)}" } }
            return BoardCards(
                monsters = up.map(::code).sorted(),
                setMonsters = down.map(::code).sorted(),
                spells = st.filter { s.cards[it]?.faceUp == true }.map(::code).sorted(),
                set = st.filter { s.cards[it]?.faceUp == false }.map(::code).sorted(),
                hand = side.hand.map(::code).sorted(),
                gy = side.gy.map(::code).sorted(),
                banished = side.banished.map(::code).sorted(),
                under = under.sorted(),
            )
        }
    }
}

/**
 * An end board's identity: its [BoardCards] as one canonical text, hashed. Two lines that end with the same cards in the same
 * places are one board, whatever order they were made in and whichever copy was used. Zone indexes are ignored (a board is
 * the same board one zone to the left) — the price is that a Link arrow's aim is not part of a board's identity.
 */
object BoardKey {
    fun text(c: BoardCards): String = buildString {
        append("M").append(c.monsters.joinToString(","))
        append("|D").append(c.setMonsters.joinToString(","))
        append("|S").append(c.spells.joinToString(","))
        append("|Z").append(c.set.joinToString(","))
        append("|H").append(c.hand.joinToString(","))
        append("|G").append(c.gy.joinToString(","))
        append("|B").append(c.banished.joinToString(","))
        append("|U").append(c.under.joinToString(","))
    }

    /** The key: FNV-1a over [text], 64 bits, in hex. Stable on every platform, so a library synced between devices agrees. */
    fun of(c: BoardCards): String {
        var h = -0x340d631b7bdddcdbL
        for (ch in text(c)) h = (h xor ch.code.toLong()) * 0x100000001b3L
        return h.toULong().toString(16).padStart(16, '0')
    }
}

/**
 * What an end board measures, counted (M.md §2.3): its interruptions by kind (from the trusted scripts, as the goldfish
 * counts them: `Interruptions`), its bodies, what it keeps, and — filled by the stress tests — what it plays through.
 * These are the value heads the network predicts ([HEADS]) and the axes the person's filters and weights read.
 */
@Serializable
data class BoardTraits(
    /** Answers the board holds on the other player's turn, one per once-per-turn group. */
    val interruptions: Int = 0,
    /** Of them, the ones that negate. */
    val negates: Int = 0,
    /** Of them, the ones that destroy, banish or return a card of theirs. */
    val removal: Int = 0,
    /** Face-up monsters. */
    val bodies: Int = 0,
    /** Set Spells and Traps. */
    val set: Int = 0,
    /** Cards left in hand. */
    val hand: Int = 0,
    val gy: Int = 0,
    val banished: Int = 0,
    /**
     * What the board's best line plays through (M2's stress tests): an interruption set's key ("ash", "ash+imperm") to the
     * interruptions the line can still guarantee with it in the other seat's hand. Absent: not tested yet, never zero.
     */
    val through: Map<String, Int> = emptyMap(),
) {
    /** The trait named [head] ([HEADS], or "through:<key>"), or null when it was not measured. */
    operator fun get(head: String): Double? = when (head) {
        "interruptions" -> interruptions.toDouble()
        "negates" -> negates.toDouble()
        "removal" -> removal.toDouble()
        "bodies" -> bodies.toDouble()
        "set" -> set.toDouble()
        "hand" -> hand.toDouble()
        "gy" -> gy.toDouble()
        "banished" -> banished.toDouble()
        else -> if (head.startsWith(THROUGH)) through[head.removePrefix(THROUGH)]?.toDouble() else null
    }

    /** Every head this board has a value for, in [HEADS] order then its stress keys sorted. */
    fun heads(): List<String> = HEADS + through.keys.sorted().map { THROUGH + it }

    companion object {
        /** The value heads every board has, in the network's order (`heads.json`). */
        val HEADS = listOf("interruptions", "negates", "removal", "bodies", "set", "hand", "gy", "banished")

        const val THROUGH = "through:"

        /** Seat [seat]'s board on [t], counted. */
        fun of(t: FxTable, seat: Int): BoardTraits {
            val s = t.state
            val groups = Interruptions.groups(t, seat).values
            var negates = 0
            var removal = 0
            groups.forEach { (uid, effect) ->
                val e = t.script(uid)?.effects?.firstOrNull { it.id == effect } ?: return@forEach
                if (FxWalk.steps(e.does).any { negates(it.op) }) negates++ else removal++
            }
            val side = s.seats[seat]
            val faceUp = BoardCheck.field(t, seat).count { u ->
                s.cards[u]?.faceUp == true && (s.placeOf(u) as? Place.Zone)?.kind.let { it == ZoneKind.MONSTER || it == ZoneKind.EMZ }
            }
            return BoardTraits(
                interruptions = groups.size,
                negates = negates,
                removal = removal,
                bodies = faceUp,
                set = BoardCheck.set(t, seat).size,
                hand = side.hand.size,
                gy = side.gy.size,
                banished = side.banished.size,
            )
        }

        private fun negates(op: Op): Boolean = when (op) {
            is Op.Negate -> true
            is Op.Choose -> op.options.any { o -> o.any { negates(it.op) } }
            is Op.If -> (op.then + op.otherwise).any { negates(it.op) }
            else -> false
        }
    }
}
