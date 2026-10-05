package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * One way to summon [uid] now by a procedure: its script's [proc] (index [index] in `SummonRule.procs`), a [kind], every
 * legal material set ([sets], each a list of uids in table order; empty lists for an inherent summon) and, for each set,
 * the zones it leaves the monster ([zones], in step with [sets]).
 */
data class ProcOption(
    val uid: Int,
    val index: Int,
    val proc: Proc,
    val kind: ProcKind,
    val sets: List<List<Int>>,
    val zones: List<List<Place.Zone>>,
)

/**
 * The summoning procedures (D.md §2.2 `Proc`) as pure functions: the legal material sets and zones, and the ordinary
 * `DuelAction`s a choice of them makes. Nothing here chooses — the engine asks its `Chooser` ([FxSummons]).
 *
 * - **Link**: [Proc.Link.min]–[Proc.Link.max] face-up monsters you control that [Proc.Link.each] matches, one matching
 *   [Proc.Link.also] when it says; their ratings sum to the card's Link rating, a Link Monster counting 1 or its rating.
 *   It goes to an Extra Monster Zone or a zone a Link points to, once its materials have left ([FxRules.summonZones]).
 * - **Synchro**: a Tuner ([Proc.Synchro.tuner]) and the rest ([Proc.Synchro.others]), face-up monsters you control with a
 *   Level (as it is now), the Levels summing to the card's.
 * - **Xyz**: [Proc.Xyz.n] (to [Proc.Xyz.max]) face-up monsters you control, not Tokens, that [Proc.Xyz.each] matches, each
 *   of Level = the card's Rank; they go beneath it.
 * - **Inherent**: from [Proc.Inherent.from] while its condition holds, once per its rule; its cost is the step
 *   executor's (`FxSteps`).
 * - **Fusion** and **Ritual** are made by effects (`Op.FusionSummon`, `Op.RitualSummon`); [fusionSets] and [ritualSets]
 *   list their materials for the step executor.
 *
 * Link, Synchro and Xyz materials go to the GY (a Token leaves the duel); Xyz materials go beneath the monster. Material
 * sets are bounded ([MOST]).
 */
object FxProcs {
    /** The most material sets listed for one procedure. */
    const val MOST = 256

    /**
     * The most candidate sets one search looks at (the red team, D.md §9 "a pick of a whole Deck"): a Ritual or Fusion
     * whose materials may come from a Deck walked every subset — billions — when none fit. Deterministic: the same sets
     * in the same order are looked at every time, so the same table always gives the same answer.
     */
    const val MOST_TRIED = 20_000

    /** How many more candidate sets a search may look at. */
    private class Budget(var left: Int = MOST_TRIED)

    /** The face-up monsters [seat] controls: what Link, Synchro and Xyz Summons use. */
    fun fieldMaterials(t: FxTable, seat: Int): List<Int> =
        FxFilters.area(Area.MONSTERS, seat, t.state).filter { t.inst(it)?.faceUp == true }

    /** Every procedure [seat] may use on [uid] where it is now (rules for the turn apart: [FxSummons] checks the open state). */
    fun options(t: FxTable, seat: Int, uid: Int): List<ProcOption> {
        val script = t.script(uid) ?: return emptyList()
        val c = t.card(uid) ?: return emptyList()
        val p = t.state.placeOf(uid)
        val procs = script.summon?.procs.orEmpty()
        return procs.mapIndexedNotNull { i, proc ->
            if (FxWalk.unread(proc)) return@mapIndexedNotNull null
            val inExtra = p is Place.Pile && p.kind == PileKind.EXTRA && p.seat == seat
            when (proc) {
                is Proc.Link -> if (inExtra && c.link != null) build(t, seat, uid, i, proc, ProcKind.LINK, linkSets(t, seat, uid, proc)) else null
                is Proc.Synchro -> if (inExtra && CardFrame.SYNCHRO in c.frames) build(t, seat, uid, i, proc, ProcKind.SYNCHRO, synchroSets(t, seat, uid, proc)) else null
                is Proc.Xyz -> if (inExtra && c.rank != null) build(t, seat, uid, i, proc, ProcKind.XYZ, xyzSets(t, seat, uid, proc)) else null
                is Proc.Inherent -> inherent(t, seat, uid, i, proc)
                is Proc.Fusion, Proc.Ritual, is Proc.Unknown -> null
            }
        }
    }

    private fun build(t: FxTable, seat: Int, uid: Int, index: Int, proc: Proc, kind: ProcKind, sets: List<List<Int>>): ProcOption? {
        if (FxRules.specialRefusal(t, seat, uid, kind) != null) return null
        val kept = sets.map { it to FxRules.summonZones(t, seat, uid, it.toSet()) }.filter { it.second.isNotEmpty() }
        if (kept.isEmpty()) return null
        return ProcOption(uid, index, proc, kind, kept.map { it.first }, kept.map { it.second })
    }

    private fun inherent(t: FxTable, seat: Int, uid: Int, index: Int, proc: Proc.Inherent): ProcOption? {
        val p = t.state.placeOf(uid)
        if (!at(p, proc.from, seat)) return null
        val scope = FxScope(t, seat, uid)
        if (proc.condition != null && !FxConds.holds(proc.condition, scope)) return null
        if (FxRules.optRefusal(t, seat, uid, FxTag.PROC, proc.opt) != null) return null
        if (FxRules.specialRefusal(t, seat, uid, ProcKind.INHERENT) != null) return null
        val zones = FxRules.summonZones(t, seat, uid)
        if (zones.isEmpty()) return null
        return ProcOption(uid, index, proc, ProcKind.INHERENT, listOf(emptyList()), listOf(zones))
    }

    /** Whether the place [p] is [where], on [seat]'s side. */
    fun at(p: Place?, where: Where, seat: Int): Boolean = when (p) {
        is Place.Pile -> p.seat == seat && when (where) {
            Where.HAND -> p.kind == PileKind.HAND
            Where.DECK -> p.kind == PileKind.DECK
            Where.EXTRA -> p.kind == PileKind.EXTRA
            Where.GY -> p.kind == PileKind.GY
            Where.BANISHED -> p.kind == PileKind.BANISHED
            else -> false
        }
        is Place.Zone -> p.seat == seat && when (where) {
            Where.MONSTER_ZONE -> p.kind == ZoneKind.MONSTER || p.kind == ZoneKind.EMZ
            Where.SPELL_ZONE -> p.kind == ZoneKind.SPELL
            Where.FIELD_ZONE -> p.kind == ZoneKind.FIELD
            else -> false
        }
        else -> false
    }

    // ---- material sets -------------------------------------------------------------------------------------------------

    /** Every subset of [pool] of a size in [sizes], in table order, smallest first — no more than [budget] allows. */
    private fun subsets(pool: List<Int>, sizes: IntRange, budget: Budget = Budget()): Sequence<List<Int>> = sequence {
        val n = pool.size
        for (k in sizes.first.coerceAtLeast(0)..minOf(sizes.last, n)) {
            if (k == 0) { yield(emptyList()); continue }
            val idx = IntArray(k) { it }
            while (true) {
                if (budget.left-- <= 0) return@sequence
                yield(idx.map { pool[it] })
                var i = k - 1
                while (i >= 0 && idx[i] == n - k + i) i--
                if (i < 0) break
                idx[i]++
                for (j in i + 1 until k) idx[j] = idx[j - 1] + 1
            }
        }
    }

    /** The legal Link material sets for [uid] by [proc]. */
    fun linkSets(t: FxTable, seat: Int, uid: Int, proc: Proc.Link): List<List<Int>> {
        val rating = t.card(uid)?.link ?: return emptyList()
        val scope = FxScope(t, seat, uid)
        val pool = FxFilters.among(proc.each, fieldMaterials(t, seat).filter { it != uid }, scope)
        val min = proc.min.coerceAtLeast(1)
        val max = proc.max.coerceAtMost(rating).coerceAtLeast(min)
        return subsets(pool, min..max).filter { set ->
            (proc.also == null || set.any { FxFilters.matches(proc.also, it, scope) }) && reaches(set.map { linkWorth(t, it) }, rating)
        }.take(MOST).toList()
    }

    /** What a Link material may count for: 1, or its rating as well when it is a Link Monster. */
    private fun linkWorth(t: FxTable, uid: Int): Set<Int> = setOfNotNull(1, t.card(uid)?.link)

    /** Whether choosing one of each of [worths] can sum to [target]. */
    private fun reaches(worths: List<Set<Int>>, target: Int): Boolean {
        var sums = setOf(0)
        worths.forEach { w -> sums = sums.flatMap { s -> w.map { s + it } }.filter { it <= target }.toSet() }
        return target in sums
    }

    /** The legal Synchro material sets for [uid] by [proc]: Tuners first, then the rest, each set in table order. */
    fun synchroSets(t: FxTable, seat: Int, uid: Int, proc: Proc.Synchro): List<List<Int>> {
        val level = t.card(uid)?.level ?: return emptyList()
        val scope = FxScope(t, seat, uid)
        val pool = fieldMaterials(t, seat).filter { it != uid && t.level(it) != null }
        val tuners = pool.filter { t.card(it)?.tuner == true && FxFilters.matches(proc.tuner.where, it, scope) }
        val out = LinkedHashSet<List<Int>>()
        for (tu in subsets(tuners, proc.tuner.least..proc.tuner.most)) {
            if (tu.isEmpty()) continue
            val rest = FxFilters.among(proc.others.where, pool.filter { it !in tu }, scope)
            for (others in subsets(rest, proc.others.least..proc.others.most)) {
                if (others.isEmpty() && proc.others.least > 0) continue
                val set = tu + others
                if (set.sumOf { t.level(it) ?: 0 } == level) out += pool.filter { it in set }
                if (out.size >= MOST) return out.toList()
            }
        }
        return out.toList()
    }

    /** The legal Xyz material sets for [uid] by [proc]. */
    fun xyzSets(t: FxTable, seat: Int, uid: Int, proc: Proc.Xyz): List<List<Int>> {
        val rank = t.card(uid)?.rank ?: return emptyList()
        val scope = FxScope(t, seat, uid)
        val pool = FxFilters.among(proc.each, fieldMaterials(t, seat), scope)
            .filter { it != uid && t.inst(it)?.token != true && t.level(it) == rank }
        val n = proc.n.coerceAtLeast(1)
        return subsets(pool, n..(proc.max ?: n).coerceAtLeast(n)).take(MOST).toList()
    }

    /**
     * Fusion material sets for [fusion] by [proc] from [from] (the step executor's pick of places): each [Mat] its own
     * cards, none used twice. Each set in [from]'s order.
     */
    fun fusionSets(t: FxTable, seat: Int, fusion: Int, proc: Proc.Fusion, from: List<Int>): List<List<Int>> {
        val scope = FxScope(t, seat, fusion)
        val pool = from.filter { it != fusion && t.card(it)?.monster == true }
        val out = LinkedHashSet<List<Int>>()
        val budget = Budget()
        fun assign(i: Int, used: List<Int>) {
            if (out.size >= MOST || budget.left <= 0) return
            if (i == proc.materials.size) {
                out += pool.filter { it in used }
                return
            }
            val m = proc.materials[i]
            val fit = FxFilters.among(m.where, pool.filter { it !in used }, scope)
            subsets(fit, m.least..minOf(m.most, fit.size), budget).forEach { pick -> if (pick.isNotEmpty() || m.least == 0) assign(i + 1, used + pick) }
        }
        assign(0, emptyList())
        return out.toList()
    }

    /**
     * Ritual Tribute sets for [ritual] from [from]: monsters with a Level whose Levels equal its own, or reach it with none
     * to spare ([LevelRule.AT_LEAST]: no Tribute could be left out and still reach it).
     */
    fun ritualSets(t: FxTable, seat: Int, ritual: Int, from: List<Int>, rule: LevelRule): List<List<Int>> {
        val level = t.card(ritual)?.level ?: return emptyList()
        val pool = from.filter { it != ritual && t.card(it)?.monster == true && t.level(it) != null }
        val out = ArrayList<List<Int>>()
        for (set in subsets(pool, 1..pool.size.coerceAtMost(12), Budget())) {
            val levels = set.map { t.level(it) ?: 0 }
            val sum = levels.sum()
            val ok = when (rule) {
                LevelRule.EQUAL -> sum == level
                LevelRule.AT_LEAST -> sum >= level && levels.all { sum - it < level }
            }
            if (ok) out += set
            if (out.size >= MOST) break
        }
        return out
    }

    // ---- the actions -------------------------------------------------------------------------------------------------

    fun position(pos: Pos, defense: Boolean = false): CardPosition = when (pos) {
        Pos.ATTACK -> CardPosition.FACE_UP_ATK
        Pos.DEFENSE -> CardPosition.FACE_UP_DEF
        Pos.SET -> CardPosition.FACE_DOWN_DEF
        Pos.EITHER -> if (defense) CardPosition.FACE_UP_DEF else CardPosition.FACE_UP_ATK
    }

    /**
     * The ordinary actions of a procedure: Link and Synchro materials to their owners' GYs (`how` "material"), then the
     * monster to [zone] (`how` "special"); Xyz — the monster to [zone], on top of the material there when there is one
     * (`over`), the others beneath it; an inherent summon is the move alone.
     */
    fun actions(t: FxTable, kind: ProcKind, uid: Int, materials: List<Int>, zone: Place.Zone, pos: CardPosition): List<DuelAction> {
        val summon = DuelAction.Move(uid, zone, pos, HOW_SUMMON)
        return when (kind) {
            ProcKind.XYZ -> {
                val there = t.state.at(zone)?.takeIf { it in materials }
                listOf(summon.copy(over = there != null)) + materials.filter { it != there }.map { DuelAction.Move(it, Place.Under(uid), how = HOW_MATERIAL) }
            }
            ProcKind.LINK, ProcKind.SYNCHRO, ProcKind.FUSION ->
                materials.map { m -> FxSteps.graveFor(t, m, t.inst(m)?.owner ?: 0).let { (to, p) -> DuelAction.Move(m, to, p, HOW_MATERIAL) } } + summon
            else -> listOf(summon)
        }
    }

    /** The events of a procedure: each material's, then the summon's. */
    fun events(t: FxTable, seat: Int, kind: ProcKind, uid: Int, materials: List<Int>, zone: Place.Zone): List<FxEvent> = buildList {
        materials.forEach { m ->
            val from = t.state.placeOf(m)
            val owner = FxFilters.controller(m, t.state) ?: seat
            add(FxEvent(Event.MATERIAL, m, owner, from, null, Cause.MATERIAL, kind, uid))
            if (kind != ProcKind.XYZ) {
                val gy = Place.Pile(t.inst(m)?.owner ?: owner, PileKind.GY)
                add(FxEvent(Event.SENT_TO_GY, m, owner, from, gy, Cause.MATERIAL, kind, uid))
            }
            add(FxEvent(Event.LEFT_FIELD, m, owner, from, null, Cause.MATERIAL, kind, uid))
        }
        val from = t.state.placeOf(uid)
        add(FxEvent(Event.SUMMONED, uid, seat, from, zone, null, kind, uid))
        add(FxEvent(Event.SPECIAL_SUMMONED, uid, seat, from, zone, null, kind, uid))
    }

    const val HOW_SUMMON = "special"
    const val HOW_MATERIAL = "material"
}
