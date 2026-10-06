package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.effects.CardType
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Area
import com.kaiharimoto.mastertool.core.duel.effects.CardScript
import com.kaiharimoto.mastertool.core.duel.effects.Dest
import com.kaiharimoto.mastertool.core.duel.effects.Effect
import com.kaiharimoto.mastertool.core.duel.effects.Event
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.FxFilters
import com.kaiharimoto.mastertool.core.duel.effects.FxScope
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.Kind
import com.kaiharimoto.mastertool.core.duel.effects.Lenient
import com.kaiharimoto.mastertool.core.duel.effects.Op
import com.kaiharimoto.mastertool.core.duel.effects.Opt
import com.kaiharimoto.mastertool.core.duel.effects.Pick
import com.kaiharimoto.mastertool.core.duel.effects.ReadFilter
import com.kaiharimoto.mastertool.core.duel.effects.Rel
import com.kaiharimoto.mastertool.core.duel.effects.Step
import com.kaiharimoto.mastertool.core.duel.effects.Where
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The goldfish's targets (Phase D step 4, `docs/phases/D.md` §5.2): an end board a deck wants after one turn, as conditions
 * on the table once the turn is over. Named by the person, or by Ai (`fx_target`, `by: "ai"`, shown as Ai's), kept per deck
 * in `<data>/effects/goldfish/<deck>.json` beside the kept results.
 */

/** A target: every condition of [all] holds on the end board. [by] is whose it is: [PERSON] or [AI]. */
@Serializable
data class EndBoard(
    val id: String,
    /** "Mirrorjade + one negate". */
    val name: String,
    /** The deck it is for (its id). */
    val deck: String,
    val all: List<ReadBoardCond> = emptyList(),
    val by: String = PERSON,
    val at: Long = 0L,
) {
    companion object {
        const val PERSON = "person"
        const val AI = "ai"
    }
}

/** A [BoardCond] read forgivingly: a newer build's condition is [BoardCond.Unknown], written back as it came. */
typealias ReadBoardCond = @Serializable(LenientBoardCond::class) BoardCond

object LenientBoardCond : Lenient<BoardCond>({ BoardCond.serializer() }, { BoardCond.Unknown(it) }, { (it as? BoardCond.Unknown)?.raw })

/**
 * One condition on the end board, judged through the turn player's eyes (`FxFilters`) once the turn is over: the End Phase
 * reached and its own triggers resolved. A filter is the effect vocabulary's own, so "an Example monster" is
 * `{"t":"name-has","word":"Example"}` and a card by name is `{"t":"name","card":<passcode>}`.
 */
@Serializable
sealed interface BoardCond {
    /** At least [n] face-up cards [where] matches on your field. */
    @Serializable @SerialName("controls")
    data class Controls(val where: ReadFilter = Filter.Any, val n: Int = 1) : BoardCond

    /** At least [n] set Spells and Traps (face-down in your Spell & Trap Zones). */
    @Serializable @SerialName("set")
    data class SetCards(val n: Int = 1) : BoardCond

    /** At least [n] cards [where] matches in your hand: a hand trap kept. */
    @Serializable @SerialName("holds")
    data class Holds(val where: ReadFilter = Filter.Any, val n: Int = 1) : BoardCond

    /** At least [n] cards [where] matches in your GY. */
    @Serializable @SerialName("gy")
    data class InGy(val where: ReadFilter = Filter.Any, val n: Int = 1) : BoardCond

    /** At least [n] cards [where] matches among your banished cards. */
    @Serializable @SerialName("banished")
    data class Banished(val where: ReadFilter = Filter.Any, val n: Int = 1) : BoardCond

    /** At least [n] interruptions, counted from the trusted scripts on the board ([Interruptions]). */
    @Serializable @SerialName("interruptions")
    data class Interruptions(val n: Int = 1) : BoardCond

    /** Any one of [any]. */
    @Serializable @SerialName("any-of")
    data class AnyOf(val any: List<ReadBoardCond>) : BoardCond

    /** A newer build's condition, kept as written: a target holding one is not computable here. */
    @Serializable @SerialName("?")
    data class Unknown(val raw: JsonObject = JsonObject(emptyMap())) : BoardCond
}

/** The end board judged: whether a target holds on a table, and what of it does. */
object BoardCheck {
    /** Whether every condition of [target] holds on [t] for [seat]. */
    fun meets(t: FxTable, seat: Int, target: EndBoard): Boolean = target.all.all { holds(t, seat, it) }

    /** How many of [target]'s conditions hold now: the search's ordering ("moves that meet a target condition first"). */
    fun met(t: FxTable, seat: Int, target: EndBoard): Int = target.all.count { holds(t, seat, it) }

    fun holds(t: FxTable, seat: Int, c: BoardCond): Boolean {
        val s = t.state
        val scope = FxScope(t, seat)
        fun count(uids: List<Int>, f: Filter) = uids.count { FxFilters.matches(f, it, scope) }
        return when (c) {
            is BoardCond.Controls -> count(field(t, seat).filter { s.cards[it]?.faceUp == true }, c.where) >= c.n
            is BoardCond.SetCards -> set(t, seat).size >= c.n
            is BoardCond.Holds -> count(s.seats[seat].hand, c.where) >= c.n
            is BoardCond.InGy -> count(s.seats[seat].gy, c.where) >= c.n
            is BoardCond.Banished -> count(s.seats[seat].banished, c.where) >= c.n
            is BoardCond.Interruptions -> Interruptions.count(t, seat) >= c.n
            is BoardCond.AnyOf -> c.any.any { holds(t, seat, it) }
            is BoardCond.Unknown -> false
        }
    }

    /** [seat]'s cards on the field: its Monster Zones, the Extra Monster Zone it holds, its Spell & Trap Zones and Field Zone. */
    fun field(t: FxTable, seat: Int): List<Int> {
        val s = t.state
        val side = s.seats[seat]
        return side.monsters.filterNotNull() + s.emz.filterNotNull().filter { s.cards[it]?.controller == seat } +
            side.spells.filterNotNull() + listOfNotNull(side.field)
    }

    /** [seat]'s set Spells and Traps: face-down in its Spell & Trap Zones. */
    fun set(t: FxTable, seat: Int): List<Int> = t.state.seats[seat].spells.filterNotNull().filter { t.state.cards[it]?.faceUp == false }

    private val MONSTER_FRAMES = setOf("Fusion", "Synchro", "Xyz", "Link", "Ritual")

    /** Every filter [c] holds (to tell which cards a target names). */
    fun filters(c: BoardCond): List<Pair<BoardCond, Filter>> = when (c) {
        is BoardCond.Controls -> listOf(c to c.where)
        is BoardCond.Holds -> listOf(c to c.where)
        is BoardCond.InGy -> listOf(c to c.where)
        is BoardCond.Banished -> listOf(c to c.where)
        is BoardCond.AnyOf -> c.any.flatMap(::filters)
        is BoardCond.SetCards, is BoardCond.Interruptions, is BoardCond.Unknown -> emptyList()
    }

    /** Whether [target] holds a condition this build cannot read: such a target is not computable. */
    fun unread(target: EndBoard): Boolean = target.all.any(::unreadCond)

    private fun unreadCond(c: BoardCond): Boolean = c is BoardCond.Unknown || (c is BoardCond.AnyOf && c.any.any(::unreadCond))

    /** [c] in words, with [name] for a card by passcode: "2 face-up Example monsters on your field". */
    fun words(c: BoardCond, name: (Int) -> String = { "#$it" }): String = when (c) {
        is BoardCond.Controls -> "${c.n} face-up ${filterWords(c.where, name)} on your field"
        is BoardCond.SetCards -> "${c.n} set Spell${if (c.n == 1) "" else "s"}/Trap${if (c.n == 1) "" else "s"}"
        is BoardCond.Holds -> "${c.n} ${filterWords(c.where, name)} in your hand"
        is BoardCond.InGy -> "${c.n} ${filterWords(c.where, name)} in your GY"
        is BoardCond.Banished -> "${c.n} ${filterWords(c.where, name)} banished"
        is BoardCond.Interruptions -> "${c.n} interruption${if (c.n == 1) "" else "s"}"
        is BoardCond.AnyOf -> c.any.joinToString(" or ", "(", ")") { words(it, name) }
        is BoardCond.Unknown -> "a condition a newer build wrote"
    }

    /** A target in words: its conditions joined with "and". */
    fun words(target: EndBoard, name: (Int) -> String = { "#$it" }): String =
        target.all.joinToString(" and ") { words(it, name) }.ifEmpty { "any board" }

    private fun filterWords(f: Filter, name: (Int) -> String): String = if (NeedKind.of(f) == NeedKind.EXTRA_MONSTER) "Extra Deck monster(s)" else when (f) {
        Filter.Any -> "card(s)"
        is Filter.Frame -> f.frame.name.lowercase().replaceFirstChar { it.uppercase() }.let { if (it in MONSTER_FRAMES) "$it Monster(s)" else "$it card(s)" }
        is Filter.Name -> name(f.card)
        is Filter.NameHas -> "\"${f.word}\" card(s)"
        is Filter.Kind -> f.type.name.lowercase() + "(s)"
        is Filter.All -> f.all.joinToString(" ") { filterWords(it, name) }
        is Filter.AnyOf -> f.any.joinToString(" or ") { filterWords(it, name) }
        else -> "card(s) of a kind"
    }
}

/**
 * Interruptions counted from the trusted scripts, never guessed (D.md §5.2). An effect counts when:
 * - a card on your field, set, or in your GY could use it on the other player's turn — a [Kind.QUICK] effect used from
 *   where the card is (a monster or a Spell or Trap face-up, the GY); a set Trap's or set Quick-Play's own [Kind.ACTIVATION];
 *   or a [Kind.TRIGGER] on the other player's activation ([Event.ACTIVATED]);
 * - its steps negate, destroy, banish or return a card the other player may hold (a [Op.Negate], or a pick that reaches
 *   their side).
 *
 * They are counted **one per once-per-turn group** ([Opt.ByName] by name and group, [Opt.PerCopy] per copy, an effect with
 * no limit once a copy): how many answers the board holds, not how good they are. The board is read after the End Phase.
 */
object Interruptions {
    fun count(t: FxTable, seat: Int): Int = groups(t, seat).size

    /** The once-per-turn groups that count, each with the card and effect that stands for it. */
    fun groups(t: FxTable, seat: Int): Map<String, Pair<Int, String>> {
        val s = t.state
        val out = LinkedHashMap<String, Pair<Int, String>>()
        fun consider(uid: Int, where: Where, set: Boolean) {
            val script = t.script(uid) ?: return
            val code = t.code(uid) ?: return
            script.effects.forEach { e ->
                if (where !in e.from) return@forEach
                val usable = when (e.kind) {
                    Kind.QUICK -> !set
                    Kind.ACTIVATION -> set && where == Where.SPELL_ZONE && quickOrTrap(t, uid)
                    Kind.TRIGGER -> !set && e.trigger?.on?.event == Event.ACTIVATED
                    else -> false
                }
                if (!usable || !answers(e)) return@forEach
                val key = when (val o = e.opt) {
                    is Opt.ByName -> "name:$code:${o.group ?: e.id}"
                    Opt.PerDuel -> "duel:$code:${e.id}"
                    else -> "copy:$uid:${e.id}"
                }
                out.getOrPut(key) { uid to e.id }
            }
        }
        val side = s.seats[seat]
        (side.monsters.filterNotNull() + s.emz.filterNotNull().filter { s.cards[it]?.controller == seat }).forEach { u ->
            if (s.cards[u]?.faceUp == true) consider(u, Where.MONSTER_ZONE, set = false)
        }
        side.spells.filterNotNull().forEach { u ->
            val faceUp = s.cards[u]?.faceUp == true
            consider(u, Where.SPELL_ZONE, set = !faceUp)
        }
        side.field?.let { u -> if (s.cards[u]?.faceUp == true) consider(u, Where.FIELD_ZONE, set = false) }
        side.gy.forEach { u -> consider(u, Where.GY, set = false) }
        return out
    }

    /** A set card whose own activation may be used on the other player's turn: a Trap, or a Quick-Play Spell. */
    private fun quickOrTrap(t: FxTable, uid: Int): Boolean {
        val c = t.card(uid) ?: return false
        return c.type == CardType.TRAP || (c.type == CardType.SPELL && c.isSpellSub("Quick-Play"))
    }

    /** Whether [e]'s steps negate, or destroy, banish or return a card the other player may hold. */
    fun answers(e: Effect): Boolean {
        val theirTargets = e.targets.filter { reachesThem(it) }.mapNotNull { it.bind }.toSet() + Pick.TARGETS
        val anyTarget = e.targets.any { reachesThem(it) }
        return e.does.any { answers(it, theirTargets, anyTarget) }
    }

    private fun answers(step: Step, theirs: Set<String>, anyTarget: Boolean): Boolean {
        fun hits(p: Pick): Boolean = reachesThem(p) || (p.ref != null && p.ref in theirs && anyTarget)
        return when (val op = step.op) {
            is Op.Negate -> true
            is Op.Destroy -> hits(op.pick)
            is Op.Banish -> hits(op.pick)
            is Op.Return -> hits(op.pick)
            is Op.Move -> op.to != Dest.MONSTER_ZONE && op.to != Dest.SPELL_ZONE && op.to != Dest.FIELD_ZONE && hits(op.pick)
            is Op.Choose -> op.options.any { o -> o.any { answers(it, theirs, anyTarget) } }
            is Op.If -> (op.then + op.otherwise).any { answers(it, theirs, anyTarget) }
            else -> false
        }
    }

    private fun reachesThem(p: Pick): Boolean =
        p.from.any { (it.rel == Rel.THEM || it.rel == Rel.ANY) && it.area in THEIRS }

    private val THEIRS = setOf(Area.MONSTERS, Area.SPELLS, Area.FIELD, Area.GY, Area.BANISHED, Area.HAND, Area.MATERIALS)

    /** Whether any script in [scripts] could count as an interruption somewhere: a deck with none never meets the target. */
    fun anyIn(scripts: Collection<CardScript>): Boolean = scripts.any { s -> s.effects.any { answers(it) } }

    /** Where a pile kind is, as an effect's [Where]. */
    internal fun where(place: Place?): Where? = when (place) {
        is Place.Zone -> when (place.kind) {
            ZoneKind.MONSTER, ZoneKind.EMZ -> Where.MONSTER_ZONE
            ZoneKind.SPELL -> Where.SPELL_ZONE
            ZoneKind.FIELD -> Where.FIELD_ZONE
        }
        is Place.Pile -> when (place.kind) {
            PileKind.HAND -> Where.HAND
            PileKind.DECK -> Where.DECK
            PileKind.EXTRA -> Where.EXTRA
            PileKind.GY -> Where.GY
            PileKind.BANISHED -> Where.BANISHED
        }
        else -> null
    }
}
