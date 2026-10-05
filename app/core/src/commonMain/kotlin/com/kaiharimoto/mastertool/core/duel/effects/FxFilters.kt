package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * Whose eyes a filter or a condition is judged through: [seat] using the card [self] (null when no card is the source,
 * as a Normal Summon's rule), with the cards [bound] so far by name ([Pick.SELF] is [self]).
 */
data class FxScope(
    val t: FxTable,
    val seat: Int,
    val self: Int? = null,
    val bound: Map<String, List<Int>> = emptyMap(),
    /** What was declared so far, by the name each [Op.Declare] bound it under. */
    val declared: Map<String, Declared> = emptyMap(),
) {
    val state: DuelState get() = t.state

    /** The absolute seats [rel] names, the scope's own first. */
    fun seats(rel: Rel): List<Int> = when (rel) {
        Rel.YOU -> listOf(seat)
        Rel.THEM -> listOf(1 - seat)
        Rel.ANY -> listOf(seat, 1 - seat)
    }

    /** The cards [name] is bound to: [Pick.SELF] the source, else a binding (gone cards included; the caller checks). */
    fun ref(name: String): List<Int> = if (name == Pick.SELF) listOfNotNull(self) else bound[name].orEmpty()
}

/**
 * The [Filter] evaluator (D.md §2.2): a filter judged against a card instance on the table, through the game's eyes.
 *
 * - **A face-down card on the field shows only where it is and whose it is**: any filter that reads its face (a name, a
 *   frame, a stat, a Spell's kind) is false; [Filter.Kind] with no `sub` is true of a face-down card by its zone (a
 *   monster in a Monster Zone; a Spell or a Trap in a Spell & Trap Zone, either word, since its face is not known).
 * - **Names by identity**: [Filter.Name] compares canonical passcodes, so an alternate artwork is the same card.
 * - **Archetypes by name**: [Filter.NameHas] is the words in the card's name, ignoring case, or one of its script's
 *   [CardScript.alsoNamed].
 * - **Levels as they are now**: a Level changed by an effect is read through [FxTable.level].
 * - [Filter.Lowest] and [Filter.Highest] are judged among the cards a pick chooses from ([among]): beside other filters in
 *   an [Filter.All], among the cards those others let through; alone, among all of them. Per card ([matches]) they hold.
 * - [Filter.Unknown] matches nothing.
 */
object FxFilters {

    /** Whether [uid] matches [f] (with [Filter.Lowest]/[Filter.Highest] holding: they are judged by [among]). */
    fun matches(f: Filter, uid: Int, scope: FxScope): Boolean {
        val c = scope.t.inst(uid) ?: return false
        val zone = scope.state.placeOf(uid) as? Place.Zone
        val hidden = zone != null && !c.faceUp
        fun facts() = if (hidden) null else scope.t.card(uid)
        return when (f) {
            Filter.Any -> true
            Filter.Self -> uid == scope.self
            Filter.NotSelf -> uid != scope.self
            is Filter.Name -> !hidden && scope.t.code(uid)?.let { it == scope.t.book.canonical(f.card) } == true
            is Filter.NameHas -> !hidden && named(uid, f.word, scope)
            is Filter.Kind -> when {
                hidden && f.sub == null -> when (f.type) {
                    CardType.MONSTER -> zone.kind == ZoneKind.MONSTER || zone.kind == ZoneKind.EMZ
                    CardType.SPELL, CardType.TRAP -> zone.kind == ZoneKind.SPELL || zone.kind == ZoneKind.FIELD
                }
                else -> facts()?.let { it.type == f.type && (f.sub == null || it.isSpellSub(f.sub)) } == true
            }
            is Filter.Frame -> facts()?.let { f.frame in it.frames } == true
            is Filter.Attribute -> facts()?.attribute?.let { it in f.any } == true
            is Filter.Race -> facts()?.race?.let { r -> f.any.any { it.equals(r, ignoreCase = true) } } == true
            is Filter.Level -> !hidden && scope.t.level(uid)?.let { it in f.span } == true
            is Filter.Rank -> facts()?.rank?.let { it in f.span } == true
            is Filter.LinkRating -> facts()?.link?.let { it in f.span } == true
            is Filter.Atk -> !hidden && stat(Stat.ATK, uid, scope)?.let { it in f.span } == true
            is Filter.Def -> !hidden && stat(Stat.DEF, uid, scope)?.let { it in f.span } == true
            Filter.FaceUp -> c.faceUp
            Filter.FaceDown -> !c.faceUp
            is Filter.Controller -> controller(uid, scope.state)?.let { it in scope.seats(f.rel) } == true
            is Filter.Same -> !hidden && key(f.stat, uid, scope)?.let { k -> scope.ref(f.ref).any { r -> r != uid && key(f.stat, r, scope) == k } } == true
            is Filter.Lowest, is Filter.Highest -> !hidden && numeric(f.stat()) && stat(f.stat(), uid, scope) != null
            is Filter.All -> f.all.all { matches(it, uid, scope) }
            is Filter.AnyOf -> f.any.any { matches(it, uid, scope) }
            is Filter.Not -> f.not !is Filter.Unknown && !matches(f.not, uid, scope)
            is Filter.Declared -> !hidden && scope.declared[f.ref]?.let { declaredBy(it, uid, scope) } == true
            is Filter.Unknown -> false
        }
    }

    /**
     * Whether a card off the table, [c] (its printed facts, and its [script] for "always treated as"), matches [f] — what a
     * name declaration may name ([Op.Declare.among]). Only what is printed is judged: where a card is, its face and who
     * controls it say nothing of a card that is only a name, so they hold; a filter about a bound card, a declaration or
     * the lowest and highest among cards cannot be judged of a name alone and does not hold.
     */
    fun printed(f: Filter, c: FxCard, script: CardScript?, scope: FxScope): Boolean = when (f) {
        Filter.Any, Filter.NotSelf, Filter.FaceUp, Filter.FaceDown, is Filter.Controller -> true
        Filter.Self, is Filter.Same, is Filter.Lowest, is Filter.Highest, is Filter.Declared, is Filter.Unknown -> false
        is Filter.Name -> scope.t.book.canonical(c.code) == scope.t.book.canonical(f.card)
        is Filter.NameHas -> {
            val w = f.word.trim().lowercase()
            w.isNotEmpty() && (phrase(c.name.lowercase(), w) || script?.alsoNamed?.any { it.trim().lowercase() == w || phrase(it.lowercase(), w) } == true)
        }
        is Filter.Kind -> c.type == f.type && (f.sub == null || c.isSpellSub(f.sub))
        is Filter.Frame -> f.frame in c.frames
        is Filter.Attribute -> c.attribute?.let { it in f.any } == true
        is Filter.Race -> c.race?.let { r -> f.any.any { it.equals(r, ignoreCase = true) } } == true
        is Filter.Level -> c.level?.let { it in f.span } == true
        is Filter.Rank -> c.rank?.let { it in f.span } == true
        is Filter.LinkRating -> c.link?.let { it in f.span } == true
        is Filter.Atk -> c.atk?.let { it in f.span } == true
        is Filter.Def -> c.def?.let { it in f.span } == true
        is Filter.All -> f.all.all { printed(it, c, script, scope) }
        is Filter.AnyOf -> f.any.any { printed(it, c, script, scope) }
        is Filter.Not -> f.not !is Filter.Unknown && !printed(f.not, c, script, scope)
    }

    /** The cards of [uids] that match [f], in their order, the lowest and highest judged among them. */
    fun among(f: Filter, uids: List<Int>, scope: FxScope): List<Int> {
        if (f is Filter.All) {
            val (extremes, rest) = f.all.partition { it is Filter.Lowest || it is Filter.Highest }
            var kept = uids.filter { u -> rest.all { matches(it, u, scope) } }
            extremes.forEach { e -> kept = extreme(e, kept, scope) }
            return kept
        }
        if (f is Filter.Lowest || f is Filter.Highest) return extreme(f, uids, scope)
        return uids.filter { matches(f, it, scope) }
    }

    private fun extreme(f: Filter, uids: List<Int>, scope: FxScope): List<Int> {
        val stat = f.stat()
        if (!numeric(stat)) return emptyList()
        val values = uids.mapNotNull { u -> stat(stat, u, scope)?.takeIf { matches(f, u, scope) }?.let { u to it } }
        val best = (if (f is Filter.Lowest) values.minOfOrNull { it.second } else values.maxOfOrNull { it.second }) ?: return emptyList()
        return values.filter { it.second == best }.map { it.first }
    }

    private fun Filter.stat(): Stat = when (this) {
        is Filter.Lowest -> stat
        is Filter.Highest -> stat
        else -> Stat.ATK
    }

    private fun numeric(s: Stat) = s == Stat.LEVEL || s == Stat.RANK || s == Stat.LINK || s == Stat.ATK || s == Stat.DEF

    /** [uid]'s numeric [stat] now, or null when it has none (an Xyz Monster's Level, a Spell's ATK). */
    fun stat(stat: Stat, uid: Int, scope: FxScope): Int? {
        val inst = scope.t.inst(uid) ?: return null
        val c = scope.t.card(uid) ?: return null
        return when (stat) {
            Stat.LEVEL -> scope.t.level(uid)
            Stat.RANK -> c.rank
            Stat.LINK -> c.link
            Stat.ATK -> if (inst.token) inst.atk ?: c.atk else c.atk
            Stat.DEF -> if (inst.token) inst.def ?: c.def else c.def
            Stat.NAME, Stat.ATTRIBUTE, Stat.RACE -> null
        }
    }

    /** What "the same [stat]" compares: a number, a canonical passcode, an Attribute, a Type. */
    private fun key(stat: Stat, uid: Int, scope: FxScope): Any? = when (stat) {
        Stat.NAME -> scope.t.code(uid) ?: scope.t.card(uid)?.name?.lowercase()
        Stat.ATTRIBUTE -> scope.t.card(uid)?.attribute
        Stat.RACE -> scope.t.card(uid)?.race?.lowercase()
        else -> stat(stat, uid, scope)
    }

    /** Whether [uid] has what [d] declared: its name (by identity), Type, Attribute or Level now. */
    private fun declaredBy(d: Declared, uid: Int, scope: FxScope): Boolean {
        val c = scope.t.card(uid) ?: return false
        return when (d.kind) {
            DeclareKind.NAME -> scope.t.code(uid) == scope.t.book.canonical(d.value)
            DeclareKind.TYPE -> c.race?.equals(d.word, ignoreCase = true) == true
            DeclareKind.ATTRIBUTE -> c.attribute?.name.equals(d.word, ignoreCase = true)
            DeclareKind.LEVEL -> scope.t.level(uid) == d.value
        }
    }

    /** Whether [uid] is "a [word] card": the words in its name, or one its script says it is always treated as. */
    fun named(uid: Int, word: String, scope: FxScope): Boolean {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return false
        val name = scope.t.card(uid)?.name?.lowercase() ?: return false
        if (phrase(name, w)) return true
        return scope.t.script(uid)?.alsoNamed?.any { it.trim().lowercase() == w || phrase(it.lowercase(), w) } == true
    }

    /** [w] in [name] as whole words: "Example" is in "Example Scout" and "Example-Knight", not in "Examples". */
    private fun phrase(name: String, w: String): Boolean {
        var from = 0
        while (true) {
            val i = name.indexOf(w, from)
            if (i < 0) return false
            val before = name.getOrNull(i - 1)
            val after = name.getOrNull(i + w.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
            from = i + 1
        }
    }

    /** Who controls [uid]: a zone's seat, a pile's owner, a material's host's controller. */
    fun controller(uid: Int, s: DuelState): Int? = when (val p = s.placeOf(uid)) {
        is Place.Zone -> if (p.kind == ZoneKind.EMZ) s.cards[uid]?.controller else p.seat
        is Place.Pile -> p.seat
        is Place.Under -> controller(p.host, s)
        else -> null
    }

    /** The cards in [spots], in order: each spot's seats (the scope's own first), each area top first, never twice. */
    fun cards(spots: List<Spot>, scope: FxScope): List<Int> {
        val out = LinkedHashSet<Int>()
        spots.forEach { spot -> scope.seats(spot.rel).forEach { seat -> out += area(spot.area, seat, scope.state) } }
        return out.toList()
    }

    /** The cards in [seat]'s [area]. */
    fun area(area: Area, seat: Int, s: DuelState): List<Int> {
        val side = s.seats.getOrNull(seat) ?: return emptyList()
        fun monsters() = side.monsters.filterNotNull() + s.emz.filterNotNull().filter { s.cards[it]?.controller == seat }
        fun spells() = side.spells.filterNotNull() + listOfNotNull(side.field)
        return when (area) {
            Area.HAND -> side.pile(PileKind.HAND)
            Area.DECK -> side.pile(PileKind.DECK)
            Area.EXTRA -> side.pile(PileKind.EXTRA)
            Area.GY -> side.pile(PileKind.GY)
            Area.BANISHED -> side.pile(PileKind.BANISHED)
            Area.MONSTERS -> monsters()
            Area.SPELLS -> spells()
            Area.FIELD -> monsters() + spells()
            Area.MATERIALS -> monsters().flatMap { s.cards[it]?.under.orEmpty() }
        }
    }

    /**
     * The cards [pick] may choose among: its [Pick.ref]'s cards still matching, or the cards of its spots that match —
     * the top [Pick.n] of the Deck for [Pick.top]. At most [Pick.MOST].
     */
    fun candidates(pick: Pick, scope: FxScope): List<Int> {
        val out = when {
            pick.ref != null -> among(pick.where, scope.ref(pick.ref).filter { scope.state.cards.containsKey(it) }, scope)
            pick.top -> cards(pick.from, scope).take(pick.n.coerceIn(0, Pick.MOST))
            else -> among(pick.where, cards(pick.from, scope), scope)
        }
        // Face-up banished cards only, unless the effect says otherwise.
        val seen = if (pick.faceDown) out else out.filter { u ->
            val p = scope.state.placeOf(u)
            !(p is Place.Pile && p.kind == PileKind.BANISHED && scope.t.inst(u)?.faceUp == false)
        }
        return seen.take(Pick.MOST)
    }

    /** How many cards [pick] takes at least and at most from [n] candidates: all of them for [Pick.all]. */
    fun bounds(pick: Pick, n: Int): IntRange = when {
        pick.all || pick.top -> n..n
        pick.upTo -> minOf(1, n)..minOf(pick.n, n) // "up to n" is 1 to n (a house ruling, D.md §2.3½)
        else -> pick.n.coerceAtMost(Pick.MOST)..pick.n.coerceAtMost(Pick.MOST)
    }
}

/** The [Cond] evaluator: numbers are constants, counts or a bound card's stat; [Cond.Unknown] never holds. */
object FxConds {
    fun holds(c: Cond, scope: FxScope): Boolean {
        if (FxWalk.unread(c)) return false
        return eval(c, scope)
    }

    private fun eval(c: Cond, scope: FxScope): Boolean {
        val s = scope.state
        return when (c) {
            is Cond.Controls -> {
                val n = value(c.n, scope) ?: return false
                scope.seats(c.seat).any { seat -> FxFilters.among(c.where, FxFilters.area(Area.FIELD, seat, s), scope).size >= n }
            }
            is Cond.NoMonsters -> scope.seats(c.seat).all { FxFilters.area(Area.MONSTERS, it, s).isEmpty() }
            is Cond.Count -> {
                val n = value(c.n, scope) ?: return false
                compare(FxFilters.among(c.where, FxFilters.cards(c.from, scope), scope).size, c.cmp, n)
            }
            is Cond.Phase -> s.phase in c.any
            is Cond.Turn -> s.active in scope.seats(c.whose)
            Cond.ChainEmpty -> s.chain.isEmpty()
            is Cond.Newest -> {
                val top = s.chain.lastOrNull() ?: return false
                val link = scope.t.fx.links.firstOrNull { it.link == s.chain.size }
                top.seat in scope.seats(c.seat) &&
                    (c.about == null || top.uid?.let { FxFilters.matches(c.about, it, scope) } == true) &&
                    (c.includes.isEmpty() || link?.let { l -> scope.t.book.effect(l.card, l.effect)?.let { FxWalk.includes(it).containsAll(c.includes) } } == true)
            }
            is Cond.ThisTurn -> {
                val self = scope.self ?: return false
                val how = scope.t.fx.summoned[self]
                when (c.event) {
                    Event.SUMMONED -> how != null
                    Event.NORMAL_SUMMONED -> how == ProcKind.NORMAL || how == ProcKind.TRIBUTE
                    Event.SPECIAL_SUMMONED -> how != null && how != ProcKind.NORMAL && how != ProcKind.TRIBUTE && how != ProcKind.FLIP
                    Event.FLIPPED -> how == ProcKind.FLIP
                    Event.SENT_TO_GY -> self in scope.t.fx.sent
                    else -> false
                }
            }
            is Cond.Lp -> {
                val n = value(c.n, scope) ?: return false
                scope.seats(c.seat).any { compare(s.seats[it].lp, c.cmp, n) }
            }
            is Cond.Compare -> {
                val l = value(c.left, scope) ?: return false
                val r = value(c.right, scope) ?: return false
                compare(l, c.cmp, r)
            }
            is Cond.All -> c.all.all { eval(it, scope) }
            is Cond.AnyOf -> c.any.any { eval(it, scope) }
            is Cond.Not -> !eval(c.not, scope)
            is Cond.Unknown -> false
        }
    }

    /** A number's value now, or null when it has none (an unknown, a stat the bound card lacks). */
    fun value(n: Num, scope: FxScope): Int? = when (n) {
        is Num.Const -> n.n
        is Num.Count -> FxFilters.among(n.where, FxFilters.cards(n.from, scope), scope).size
        is Num.Of -> scope.ref(n.ref).firstOrNull()?.let { FxFilters.stat(n.stat, it, scope) }
        is Num.Unknown -> null
    }

    fun compare(a: Int, cmp: Cmp, b: Int): Boolean = when (cmp) {
        Cmp.GE -> a >= b
        Cmp.LE -> a <= b
        Cmp.EQ -> a == b
        Cmp.GT -> a > b
        Cmp.LT -> a < b
        Cmp.NE -> a != b
    }
}
