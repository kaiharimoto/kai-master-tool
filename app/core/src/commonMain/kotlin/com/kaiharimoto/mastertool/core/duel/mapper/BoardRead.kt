package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Effect
import com.kaiharimoto.mastertool.core.duel.effects.Event
import com.kaiharimoto.mastertool.core.duel.effects.FxAct
import com.kaiharimoto.mastertool.core.duel.effects.FxRules
import com.kaiharimoto.mastertool.core.duel.effects.FxSteps
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.effects.FxWalk
import com.kaiharimoto.mastertool.core.duel.effects.Kind
import com.kaiharimoto.mastertool.core.duel.effects.Op
import com.kaiharimoto.mastertool.core.duel.effects.Opt
import com.kaiharimoto.mastertool.core.duel.effects.Pick
import com.kaiharimoto.mastertool.core.duel.effects.Where
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
    /** Life points left: a line that paid for itself is another board. */
    val lp: Int = 0,
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
                lp = side.lp,
            )
        }
    }
}

/**
 * An end board's identity: its [BoardCards] as one canonical text, hashed. Two lines that end with the same cards in the same
 * places are one board, whatever order they were made in and whichever copy was used. Zone indexes are ignored (a board is
 * the same board one zone to the left) — the price is that a Link arrow's aim is not part of a board's identity.
 *
 * The counted interruptions are part of it too: the same cards with a once-per-Duel effect spent on one line and kept on the
 * other are two boards, and the library must never show one board's line beside the other's count.
 */
object BoardKey {
    fun text(c: BoardCards, t: BoardTraits): String = buildString {
        append("M").append(c.monsters.joinToString(","))
        append("|D").append(c.setMonsters.joinToString(","))
        append("|S").append(c.spells.joinToString(","))
        append("|Z").append(c.set.joinToString(","))
        append("|H").append(c.hand.joinToString(","))
        append("|G").append(c.gy.joinToString(","))
        append("|B").append(c.banished.joinToString(","))
        append("|U").append(c.under.joinToString(","))
        append("|L").append(c.lp)
        append("|T").append(t.interruptions).append(',').append(t.negates).append(',').append(t.removal).append(',').append(t.handInterruptions)
    }

    /** The key: FNV-1a over [text], 64 bits, in hex. Stable on every platform, so a library synced between devices agrees. */
    fun of(c: BoardCards, t: BoardTraits): String {
        var h = -0x340d631b7bdddcdbL
        for (ch in text(c, t)) h = (h xor ch.code.toLong()) * 0x100000001b3L
        return h.toULong().toString(16).padStart(16, '0')
    }
}

/**
 * What an end board measures, counted (M.md §2.3): its interruptions by kind, its bodies, what it keeps, and — filled by the
 * stress tests — what it plays through. These are the value heads the network predicts ([HEADS]) and the axes the person's
 * filters and weights read.
 *
 * Interruptions are the goldfish's (`Interruptions`: one per once-per-turn group of an effect that could answer on the other
 * player's turn), **less the ones the board could not use**: a once-per-Duel effect already spent, or a cost that cannot be
 * paid as the board stands (an Xyz with its materials detached, a discard with an empty hand). Hand traps kept are counted
 * apart ([handInterruptions]): a line that pitched one as a cost is not free.
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
    /** Effects in the hand that could answer on the other player's turn (hand traps kept), one per once-per-turn group. */
    val handInterruptions: Int = 0,
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
        "handInterruptions" -> handInterruptions.toDouble()
        else -> if (head.startsWith(THROUGH)) through[head.removePrefix(THROUGH)]?.toDouble() else null
    }

    /** Every head this board has a value for, in [HEADS] order then its stress keys sorted. */
    fun heads(): List<String> = HEADS + through.keys.sorted().map { THROUGH + it }

    companion object {
        /** The value heads every board has, in the network's order (`heads.json`). Only ever appended to. */
        val HEADS = listOf("interruptions", "negates", "removal", "bodies", "set", "hand", "gy", "banished", "handInterruptions")

        /**
         * The traits where more is plainly better, whatever a player wants: what a ranking with no weights, the training's
         * policy target and the gate read (with every stress key). Bodies, cards kept and the GY are trade-offs, left to the
         * person's weights.
         */
        val MORE_IS_BETTER = listOf("interruptions", "negates", "removal", "handInterruptions")

        const val THROUGH = "through:"

        /** Seat [seat]'s board on [t], counted. */
        fun of(t: FxTable, seat: Int): BoardTraits {
            val s = t.state
            val groups = Interruptions.groups(t, seat).values.filter { (uid, effect) -> usable(t, seat, uid, effect) }
            var negates = 0
            var removal = 0
            groups.forEach { (uid, effect) ->
                val e = t.script(uid)?.effect(effect) ?: return@forEach
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
                handInterruptions = handTraps(t, seat),
            )
        }

        /** Whether the board could use [uid]'s [effect] on the other player's turn as it stands: not spent for the Duel, its cost payable. */
        private fun usable(t: FxTable, seat: Int, uid: Int, effect: String): Boolean {
            val e = t.script(uid)?.effect(effect) ?: return false
            if (e.opt == Opt.PerDuel && FxRules.optRefusal(t, seat, uid, effect, e.opt) != null) return false
            return payable(t, seat, uid, e)
        }

        private fun payable(t: FxTable, seat: Int, uid: Int, e: Effect): Boolean {
            if (e.cost.isEmpty()) return true
            val act = FxAct(seat, uid, t.code(uid) ?: 0, e.id, FxTag.COST, link = 1, bound = mapOf(Pick.SELF to listOf(uid)))
            return FxSteps.able(t, act, e.cost)
        }

        /** The hand's answers: a Quick Effect (or a trigger on an activation) used from the hand that answers, its cost payable. */
        private fun handTraps(t: FxTable, seat: Int): Int {
            val groups = HashSet<String>()
            t.state.seats[seat].hand.forEach { uid ->
                val code = t.code(uid) ?: return@forEach
                t.script(uid)?.effects.orEmpty().forEach { e ->
                    val fromHand = Where.HAND in e.from &&
                        (e.kind == Kind.QUICK || (e.kind == Kind.TRIGGER && e.trigger?.on?.event == Event.ACTIVATED))
                    if (!fromHand || !Interruptions.answers(e) || !payable(t, seat, uid, e)) return@forEach
                    groups += when (val o = e.opt) {
                        is Opt.ByName -> "name:$code:${o.group ?: e.id}"
                        else -> "copy:$uid:${e.id}"
                    }
                }
            }
            return groups.size
        }

        private fun negates(op: Op): Boolean = when (op) {
            is Op.Negate -> true
            is Op.Choose -> op.options.any { o -> o.any { negates(it.op) } }
            is Op.If -> (op.then + op.otherwise).any { negates(it.op) }
            else -> false
        }
    }
}
