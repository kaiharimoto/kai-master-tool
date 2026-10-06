package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.effects.CardFrame
import com.kaiharimoto.mastertool.core.duel.effects.CardType
import com.kaiharimoto.mastertool.core.duel.effects.Filter

/*
 * The target editor's model (Phase D step 4, agent (c); `docs/phases/D.md` §5.2): an end board as a person makes one in the
 * Effects app — without knowing the vocabulary. Cards picked by their art (or "any Extra Deck monster", a kind), counted, in
 * one of four places; set Spells and Traps; interruptions; "any one of these" for a place. It is read from an [EndBoard] and
 * written back to one, and what it cannot show (a newer build's condition, an "any of" across places, a second count of the
 * same card) is kept as written, never dropped.
 */

/** Where on the end board a need stands: the four places a target names cards in. */
enum class BoardPlace(val words: String) {
    FIELD("On the field"),
    HAND("Held in hand"),
    GY("In the GY"),
    BANISHED("Banished"),
}

/** A kind of card a need may name instead of one card: what the editor offers beside the deck's art. */
enum class NeedKind(val words: String, val plural: String) {
    EXTRA_MONSTER("Extra Deck monster", "Extra Deck monsters"),
    FUSION("Fusion Monster", "Fusion Monsters"),
    SYNCHRO("Synchro Monster", "Synchro Monsters"),
    XYZ("Xyz Monster", "Xyz Monsters"),
    LINK("Link Monster", "Link Monsters"),
    MONSTER("monster", "monsters"),
    SPELL("Spell", "Spells"),
    TRAP("Trap", "Traps"),
    ANY("card", "cards"),
    ;

    /** The filter it stands for, in the effect vocabulary. */
    val filter: Filter
        get() = when (this) {
            EXTRA_MONSTER -> Filter.AnyOf(EXTRA_FRAMES.map { Filter.Frame(it) })
            FUSION -> Filter.Frame(CardFrame.FUSION)
            SYNCHRO -> Filter.Frame(CardFrame.SYNCHRO)
            XYZ -> Filter.Frame(CardFrame.XYZ)
            LINK -> Filter.Frame(CardFrame.LINK)
            MONSTER -> Filter.Kind(CardType.MONSTER)
            SPELL -> Filter.Kind(CardType.SPELL)
            TRAP -> Filter.Kind(CardType.TRAP)
            ANY -> Filter.Any
        }

    companion object {
        val EXTRA_FRAMES = listOf(CardFrame.FUSION, CardFrame.SYNCHRO, CardFrame.XYZ, CardFrame.LINK)

        /** The kinds the editor offers for [place]: the field's monsters first; a hand, a GY and banishment any card. */
        fun offered(place: BoardPlace): List<NeedKind> = when (place) {
            BoardPlace.FIELD -> listOf(EXTRA_MONSTER, FUSION, SYNCHRO, XYZ, LINK, MONSTER, SPELL, TRAP)
            BoardPlace.HAND -> listOf(MONSTER, SPELL, TRAP, ANY)
            BoardPlace.GY, BoardPlace.BANISHED -> listOf(MONSTER, SPELL, TRAP, ANY)
        }

        /** The kind [f] is, when it is exactly one of these. */
        fun of(f: Filter): NeedKind? = entries.firstOrNull { it.filter == f }
    }
}

/** What a need names: one card (by passcode), a kind of card, or a word in names (an archetype). */
sealed interface Needed {
    data class Card(val code: Int) : Needed
    data class Kind(val kind: NeedKind) : Needed
    data class Word(val word: String) : Needed

    /** The filter it stands for. */
    val filter: Filter
        get() = when (this) {
            is Card -> Filter.Name(code)
            is Kind -> kind.filter
            is Word -> Filter.NameHas(word)
        }

    companion object {
        /** What [f] names, when the editor can show it; null for anything else (it is kept as written). */
        fun of(f: Filter): Needed? = when (f) {
            is Filter.Name -> Card(f.card)
            is Filter.NameHas -> Word(f.word)
            else -> NeedKind.of(f)?.let(::Kind)
        }
    }
}

/** [n] or more of [what]. */
data class Need(val what: Needed, val n: Int = 1)

/** One place's needs; [anyOne]: any one of them is enough (at least two needs), else every one. */
data class DraftSection(val needs: List<Need> = emptyList(), val anyOne: Boolean = false)

/**
 * An end board being made or edited. [kept] holds what the editor cannot show, written back as it came. [board] makes the
 * [EndBoard]; [of] reads one. A draft read from a board and written back is the same board, up to the order of its
 * conditions (held by `TargetDraftTest`).
 */
data class TargetDraft(
    val name: String = "",
    val field: DraftSection = DraftSection(),
    val hand: DraftSection = DraftSection(),
    val gy: DraftSection = DraftSection(),
    val banished: DraftSection = DraftSection(),
    /** At least this many set Spells and Traps (0: not asked). */
    val set: Int = 0,
    /** At least this many interruptions (0: not asked). */
    val interruptions: Int = 0,
    val kept: List<BoardCond> = emptyList(),
) {
    fun section(p: BoardPlace): DraftSection = when (p) {
        BoardPlace.FIELD -> field
        BoardPlace.HAND -> hand
        BoardPlace.GY -> gy
        BoardPlace.BANISHED -> banished
    }

    fun with(p: BoardPlace, s: DraftSection): TargetDraft = when (p) {
        BoardPlace.FIELD -> copy(field = s)
        BoardPlace.HAND -> copy(hand = s)
        BoardPlace.GY -> copy(gy = s)
        BoardPlace.BANISHED -> copy(banished = s)
    }

    /** [what] added to [p]: one more when it is there already. */
    fun add(p: BoardPlace, what: Needed): TargetDraft {
        val s = section(p)
        val at = s.needs.indexOfFirst { it.what == what }
        val needs = if (at < 0) s.needs + Need(what) else s.needs.mapIndexed { i, n -> if (i == at) n.copy(n = (n.n + 1).coerceAtMost(MOST)) else n }
        return with(p, s.copy(needs = needs))
    }

    /** [what] in [p] set to [n]; at 0 or less it goes. */
    fun count(p: BoardPlace, what: Needed, n: Int): TargetDraft {
        val s = section(p)
        val needs = if (n <= 0) s.needs.filterNot { it.what == what } else s.needs.map { if (it.what == what) it.copy(n = n.coerceAtMost(MOST)) else it }
        return with(p, s.copy(needs = needs, anyOne = s.anyOne && needs.size >= 2))
    }

    fun remove(p: BoardPlace, what: Needed): TargetDraft = count(p, what, 0)

    /** "Any one of these" for [p], only where it holds two or more. */
    fun anyOne(p: BoardPlace, on: Boolean): TargetDraft = section(p).let { s -> with(p, s.copy(anyOne = on && s.needs.size >= 2)) }

    /** How many conditions it makes. */
    val conditions: Int
        get() = BoardPlace.entries.sumOf { p -> section(p).let { s -> if (s.anyOne && s.needs.size >= 2) 1 else s.needs.size } } +
            (if (set > 0) 1 else 0) + (if (interruptions > 0) 1 else 0) + kept.size

    /** Why it cannot be kept yet, in words; null when it can. */
    fun problem(): String? = when {
        conditions == 0 -> "Say what the end board must hold: a card on the field, a set card, an interruption…"
        conditions > GoldfishCodec.MOST_CONDITIONS -> "At most ${GoldfishCodec.MOST_CONDITIONS} conditions a target ($conditions here)."
        else -> null
    }

    /** The conditions, in order: the field, the hand, the GY, banished, set cards, interruptions, then what was kept. */
    fun conds(): List<BoardCond> = buildList {
        BoardPlace.entries.forEach { p ->
            val s = section(p)
            val each = s.needs.map { cond(p, it) }
            if (s.anyOne && each.size >= 2) add(BoardCond.AnyOf(each)) else addAll(each)
        }
        if (set > 0) add(BoardCond.SetCards(set))
        if (interruptions > 0) add(BoardCond.Interruptions(interruptions))
        addAll(kept)
    }

    /** The target it makes: [name] (or one made from its conditions), for [deck], [by] whom. */
    fun board(id: String, deck: String, by: String, at: Long, cardName: (Int) -> String = { "#$it" }): EndBoard =
        EndBoard(id, name.trim().ifBlank { suggestedName(cardName) }.take(120), deck, conds(), by, at)

    /** A name made from what it holds: "Mirrorjade + 1 interruption". */
    fun suggestedName(cardName: (Int) -> String = { "#$it" }): String {
        val parts = buildList {
            field.needs.forEach { add(needWords(it, cardName)) }
            if (interruptions > 0) add("$interruptions interruption${if (interruptions == 1) "" else "s"}")
            if (set > 0) add("$set set")
            hand.needs.forEach { add(needWords(it, cardName) + " in hand") }
            gy.needs.forEach { add(needWords(it, cardName) + " in GY") }
            banished.needs.forEach { add(needWords(it, cardName) + " banished") }
        }
        val joint = if (field.anyOne) " or " else " + "
        return parts.take(3).joinToString(joint).ifBlank { "End board" } + if (parts.size > 3) " …" else ""
    }

    companion object {
        /** The most of one need the editor counts to. */
        const val MOST = 5

        /** The condition [need] makes in [p]. */
        fun cond(p: BoardPlace, need: Need): BoardCond = when (p) {
            BoardPlace.FIELD -> BoardCond.Controls(need.what.filter, need.n)
            BoardPlace.HAND -> BoardCond.Holds(need.what.filter, need.n)
            BoardPlace.GY -> BoardCond.InGy(need.what.filter, need.n)
            BoardPlace.BANISHED -> BoardCond.Banished(need.what.filter, need.n)
        }

        /** [c] as a place and a need, when it is one the editor shows. */
        fun need(c: BoardCond): Pair<BoardPlace, Need>? = when (c) {
            is BoardCond.Controls -> Needed.of(c.where)?.let { BoardPlace.FIELD to Need(it, c.n) }
            is BoardCond.Holds -> Needed.of(c.where)?.let { BoardPlace.HAND to Need(it, c.n) }
            is BoardCond.InGy -> Needed.of(c.where)?.let { BoardPlace.GY to Need(it, c.n) }
            is BoardCond.Banished -> Needed.of(c.where)?.let { BoardPlace.BANISHED to Need(it, c.n) }
            else -> null
        }?.takeIf { it.second.n in 1..MOST }

        /** [b] as a draft: what the editor can show in its places, the rest kept as written. */
        fun of(b: EndBoard): TargetDraft {
            var d = TargetDraft(name = b.name)
            val kept = ArrayList<BoardCond>()
            b.all.forEach { c ->
                when (c) {
                    is BoardCond.SetCards -> if (d.set == 0 && c.n in 1..MOST) d = d.copy(set = c.n) else kept += c
                    is BoardCond.Interruptions -> if (d.interruptions == 0 && c.n in 1..MOST) d = d.copy(interruptions = c.n) else kept += c
                    is BoardCond.AnyOf -> {
                        // "Any one of these" in one place, the place still empty: shown; else kept.
                        val each = c.any.map(::need)
                        val place = each.firstOrNull()?.first
                        val needs = each.mapNotNull { it?.second }
                        val fits = each.size >= 2 && each.all { it != null && it.first == place } && needs.map { it.what }.distinct().size == needs.size
                        if (fits && place != null && d.section(place).needs.isEmpty()) d = d.with(place, DraftSection(needs, anyOne = true)) else kept += c
                    }
                    else -> {
                        val pn = need(c)
                        val s = pn?.let { d.section(it.first) }
                        // A second count of one card in one place says something else than a sum: kept as written.
                        if (pn != null && s != null && !s.anyOne && s.needs.none { it.what == pn.second.what }) {
                            d = d.with(pn.first, s.copy(needs = s.needs + pn.second))
                        } else {
                            kept += c
                        }
                    }
                }
            }
            return d.copy(kept = kept)
        }

        /** A need in words: "2 Mirrorjade", "1 Extra Deck monster", "a \"Pond\" card". */
        fun needWords(need: Need, cardName: (Int) -> String = { "#$it" }): String = when (val w = need.what) {
            is Needed.Card -> if (need.n == 1) cardName(w.code) else "${need.n} × ${cardName(w.code)}"
            is Needed.Kind -> "${need.n} ${if (need.n == 1) w.kind.words else w.kind.plural}"
            is Needed.Word -> "${need.n} “${w.word}” card${if (need.n == 1) "" else "s"}"
        }
    }
}
