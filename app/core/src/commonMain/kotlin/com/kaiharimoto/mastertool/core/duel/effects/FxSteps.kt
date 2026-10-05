package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * Who is running steps, for what (the step executor's context): [seat] using [uid]'s ([card], canonical) [effect] — an
 * effect id, or [FxTag.PROC] for an inherent summon's cost — in its [part] ([FxTag.COST], [FxTag.ACTIVATE],
 * [FxTag.RESOLVE]), on chain link [link] when it has one, with the cards [bound] so far ([Pick.SELF] is always [uid];
 * [Pick.TARGETS] once targets are chosen). Every action the steps make is tagged from this.
 */
data class FxAct(
    val seat: Int,
    val uid: Int,
    val card: Int,
    val effect: String,
    val part: String,
    val link: Int? = null,
    val bound: Map<String, List<Int>> = emptyMap(),
    val script: String = "",
    val verified: Boolean = false,
    /** What was declared so far ([Op.Declare]), by name. */
    val declared: Map<String, Declared> = emptyMap(),
) {
    /** The eyes this act's filters and conditions are judged through. */
    fun scope(t: FxTable): FxScope = FxScope(t, seat, uid, bound + (Pick.SELF to listOf(uid)), declared)

    fun tag(): FxTag = FxTag(uid, effect, part, link, script, verified)
}

/** What running steps came to. */
sealed interface FxRun {
    /**
     * The tagged [actions] (applied already: [state] is the table after them) and the engine state after; [events] in
     * their batches; [bound] the bindings after (targets, names the steps bound); [whole] false when a step could not
     * happen in full — what `AND_IF_YOU_DO` and `THEN` read.
     */
    data class Done(
        val actions: List<DuelAction>,
        val tags: List<FxTag>,
        val state: DuelState,
        val fx: FxState,
        val events: List<FxEvent> = emptyList(),
        val bound: Map<String, List<Int>> = emptyMap(),
        val whole: Boolean = true,
        val declared: Map<String, Declared> = emptyMap(),
    ) : FxRun

    data class Refused(val why: String) : FxRun

    data object Cancelled : FxRun
}

/**
 * The step executor (D.md §2.2, §2.3): every [Op] turned into ordinary `DuelAction`s with its `how` word, each applied by
 * `DuelRules` as it is made and folded into `FxState` from its tagged entry ([FxScribe], [FxFold]). The summons
 * (`FxSummons`, an inherent summon's cost) and the chain (`FxChain`) run their steps here.
 *
 * - **Joins.** Steps joined by [Join.AND] or [Join.AND_IF_YOU_DO] are one batch, happening at the same time; [Join.THEN]
 *   and [Join.ALSO] begin a new batch. A dependent step ([Join.AND_IF_YOU_DO], [Join.THEN]) happens only if the step
 *   before it happened in full. Each action carries its batch ([FxMemo.batch]), which is what "last" reads.
 * - **Choices** are the [Chooser]'s, asked only when there is more than one legal answer: which cards (a pick's
 *   candidates, judged by `FxFilters` through the acting seat's eyes, at most [Pick.MOST]), a zone, a position, an
 *   option, a declaration. "Up to n" is 1 to n. A pick of [Pick.all] or [Pick.top] is no choice.
 * - **Whole.** A pick happened in full when it moved as many cards as it asks for ([Pick.all]: at least one; a [Pick.ref]
 *   all the cards bound, none gone). A cost that cannot be paid in full refuses the activation (`FxChain`).
 * - **Bindings**: a pick's [Pick.bind] names what it chose; [Op.Declare] binds its answer; [Op.Negate] binds the negated
 *   card.
 * - **What the table does not hold** goes in the log too, so the fold reads it: a restriction as a `Lock`, a Level change,
 *   a "Normal Summon 1 more" and a declaration as notes, each with its [FxMemo].
 * - **Game rules on the way**: a search is revealed and the Deck shuffled after it; an Extra Deck monster sent to the hand
 *   or the Deck goes to the Extra Deck; a Pendulum Monster destroyed on the field goes to the Extra Deck face-up; a
 *   Special Summon honours `FxRules.specialRefusal` (restrictions, "once per turn", "must first be") and the zones a
 *   Link or an Extra Deck monster may take.
 * - [Op.Unknown] is refused (an effect holding one is never offered: `FxWalk.unread`).
 */
object FxSteps {
    /** Runs [steps] for [act] on [t]. */
    fun run(t: FxTable, act: FxAct, steps: List<Step>, chooser: Chooser): FxRun {
        if (steps.isEmpty()) return FxRun.Done(emptyList(), emptyList(), t.state, t.fx, bound = act.bound, declared = act.declared)
        val sc = FxScribe(t, chooser, act.seat)
        return try {
            val r = exec(sc, act, steps)
            FxRun.Done(sc.actions.toList(), sc.tags.toList(), sc.t.state, sc.t.fx, sc.events.toList(), r.bound, r.whole, r.declared)
        } catch (s: FxStop) {
            if (s.why == null) FxRun.Cancelled else FxRun.Refused(s.why)
        }
    }

    /** Chooses [targets] for [act] as it is activated, bound as [Pick.TARGETS] (and each pick's own [Pick.bind]). */
    fun target(t: FxTable, act: FxAct, targets: List<Pick>, chooser: Chooser): FxRun {
        if (targets.isEmpty()) return FxRun.Done(emptyList(), emptyList(), t.state, t.fx, bound = act.bound, declared = act.declared)
        val sc = FxScribe(t, chooser, act.seat)
        return try {
            val r = targets(sc, act, targets)
            FxRun.Done(emptyList(), emptyList(), sc.t.state, sc.t.fx, bound = r.bound, whole = r.whole, declared = r.declared)
        } catch (s: FxStop) {
            if (s.why == null) FxRun.Cancelled else FxRun.Refused(s.why)
        }
    }

    /** What a run of steps came to inside a move: whether every step happened in full, and the bindings after. */
    internal class Result(val whole: Boolean, val bound: Map<String, List<Int>>, val declared: Map<String, Declared>)

    internal fun exec(sc: FxScribe, act: FxAct, steps: List<Step>): Result {
        val c = Ctx(sc, act)
        val whole = c.steps(steps)
        return Result(whole, c.bound.toMap(), c.declared.toMap())
    }

    internal fun targets(sc: FxScribe, act: FxAct, picks: List<Pick>): Result {
        val c = Ctx(sc, act)
        c.targets(picks)
        return Result(true, c.bound.toMap(), c.declared.toMap())
    }

    /** The binding of target pick [i] alone, so each target is checked again against its own pick at resolution. */
    fun targetKey(i: Int): String = "${Pick.TARGETS}#$i"

    // ---- what could happen, without choosing ----------------------------------------------------------------------

    /**
     * Whether every one of [steps] could happen now for [act] (a cost must be payable in full): a quick look, never a
     * choice. Steps that read a binding not made yet are taken as able.
     */
    fun able(t: FxTable, act: FxAct, steps: List<Step>): Boolean = steps.all { able(t, act, it.op) }

    /** Whether [targets] each have enough legal targets now, none chosen twice. */
    fun ableTargets(t: FxTable, act: FxAct, targets: List<Pick>): Boolean {
        val scope = act.scope(t)
        var used = 0
        targets.forEach { p ->
            val n = FxFilters.candidates(p, scope).size - used
            val need = if (p.upTo || p.all) 1 else p.n.coerceIn(1, Pick.MOST)
            if (n < need) return false
            used += need
        }
        return true
    }

    /** Whether [op] could happen now for [act], at all. */
    fun able(t: FxTable, act: FxAct, op: Op): Boolean {
        val scope = act.scope(t)
        val seat = act.seat
        val s = t.state
        fun enough(p: Pick, keep: (Int) -> Boolean = { true }): Boolean {
            if (p.ref != null && p.ref != Pick.SELF && p.ref !in act.bound) return true
            val n = FxFilters.candidates(p, scope).count(keep)
            return n >= if (p.all || p.upTo || p.ref != null) 1 else if (p.top) p.n.coerceAtLeast(1) else p.n.coerceIn(1, Pick.MOST)
        }
        return when (op) {
            is Op.Move -> enough(op.pick)
            is Op.Add -> enough(op.pick) { !inPile(s, it, PileKind.HAND) }
            is Op.Send -> enough(op.pick) { !inPile(s, it, PileKind.GY) }
            is Op.Discard -> enough(handPick(op.pick)) { inPile(s, it, PileKind.HAND) }
            is Op.Destroy -> enough(op.pick) { u -> s.placeOf(u).let { it is Place.Zone || (it is Place.Pile && it.kind == PileKind.HAND) } }
            is Op.Banish -> enough(op.pick) { !inPile(s, it, PileKind.BANISHED) }
            is Op.Tribute -> enough(tributePick(op.pick))
            is Op.Return -> enough(op.pick)
            is Op.Reveal -> enough(op.pick)
            is Op.Draw -> scope.seats(op.rel).all { s.seats[it].deck.size >= op.n.coerceAtLeast(1) }
            is Op.Shuffle -> true
            is Op.SpecialSummon -> enough(op.pick) { summonable(t, seat, it) } &&
                FxFilters.candidates(op.pick, scope).any { summonable(t, seat, it) && FxRules.summonZones(t, seat, it).isNotEmpty() }
            is Op.FusionSummon -> fusions(t, act, op).isNotEmpty()
            is Op.RitualSummon -> rituals(t, act, op).isNotEmpty()
            is Op.SynchroSummon -> procedures(t, act, op.f, ProcKind.SYNCHRO).isNotEmpty()
            is Op.XyzSummon -> procedures(t, act, op.f, ProcKind.XYZ).isNotEmpty()
            is Op.LinkSummon -> procedures(t, act, op.f, ProcKind.LINK).isNotEmpty()
            is Op.Attach -> enough(op.pick) && host(t, scope, op.to) != null
            is Op.Detach -> host(t, scope, op.from)?.let { (t.inst(it)?.under?.size ?: 0) >= op.n.coerceAtLeast(1) } ?: (op.from !in act.bound && op.from != Pick.SELF)
            is Op.Token -> scope.seats(op.rel).any { s.freeZones(it, ZoneKind.MONSTER).isNotEmpty() } && !tokensBanned(t, seat)
            is Op.Negate -> negatable(t, act, op) != null
            is Op.ChangeLevel -> enough(op.pick) { t.level(it) != null }
            is Op.PayLp -> FxConds.value(op.n, scope)?.let { s.seats[seat].lp >= it } ?: false
            is Op.Counter -> enough(op.pick) { s.placeOf(it) is Place.Zone }
            is Op.Choose -> op.options.any { o -> o.isEmpty() || able(t, act, o.first().op) }
            is Op.Declare -> declarable(t, act, op).isNotEmpty()
            is Op.Lp, is Op.NormalSummonAgain, is Op.If, is Op.Restrict -> true
            is Op.Unknown -> false
        }
    }

    // ---- shared look-ups --------------------------------------------------------------------------------------------

    private fun inPile(s: DuelState, uid: Int, kind: PileKind) = s.placeOf(uid).let { it is Place.Pile && it.kind == kind }

    /** A discard is from the hand: your own unless it says. */
    internal fun handPick(p: Pick): Pick = if (p.from.isEmpty() && p.ref == null) p.copy(from = listOf(Spot(Rel.YOU, Area.HAND))) else p

    /** A Tribute is of monsters you control unless it says. */
    internal fun tributePick(p: Pick): Pick = if (p.from.isEmpty() && p.ref == null) p.copy(from = listOf(Spot(Rel.YOU, Area.MONSTERS))) else p

    /** Whether [seat] may Special Summon [uid] by an effect now (zones apart). */
    internal fun summonable(t: FxTable, seat: Int, uid: Int): Boolean =
        t.card(uid)?.monster == true && t.inst(uid)?.token != true && FxRules.specialRefusal(t, seat, uid, ProcKind.SPECIAL) == null

    /** The first card bound to [ref] that is on the field: a host for materials. */
    internal fun host(t: FxTable, scope: FxScope, ref: String): Int? =
        scope.ref(ref).firstOrNull { u -> t.state.placeOf(u).let { it is Place.Zone && (it.kind == ZoneKind.MONSTER || it.kind == ZoneKind.EMZ) } }

    /** A restriction with no exception that bars [seat] from Special Summoning: no token either. */
    internal fun tokensBanned(t: FxTable, seat: Int): Boolean =
        FxRules.inForce(t).any { it.seat == seat && it.restriction.ban == Ban.SPECIAL_SUMMON && it.restriction.except == null }

    /** The Chain Link [op] would negate for [act], or null when there is none to negate. */
    internal fun negatable(t: FxTable, act: FxAct, op: Op.Negate): Int? {
        val chain = t.state.chain
        val mine = act.link ?: (chain.size + 1)
        val n = when (op.link) {
            LinkRef.ANSWERED -> mine - 1
            LinkRef.NEWEST -> if (chain.size >= mine) mine - 1 else chain.size
        }
        if (n !in 1..chain.size) return null
        if (op.what == NegWhat.ACTIVATION && chain[n - 1].negated) return null
        if (op.what == NegWhat.EFFECT && (chain[n - 1].negated || t.fx.links.any { it.link == n && it.effectNegated })) return null
        return n
    }

    /** The Fusion Monsters [op] could summon now for [act], with their material sets. */
    internal fun fusions(t: FxTable, act: FxAct, op: Op.FusionSummon): List<Pair<Int, List<List<Int>>>> {
        val seat = act.seat
        val scope = act.scope(t)
        val pool = FxFilters.cards(op.materialsFrom, scope)
        return FxFilters.area(Area.EXTRA, seat, t.state).mapNotNull { u ->
            val c = t.card(u) ?: return@mapNotNull null
            if (CardFrame.FUSION !in c.frames || !FxFilters.matches(op.fusion, u, scope)) return@mapNotNull null
            if (FxRules.specialRefusal(t, seat, u, ProcKind.FUSION) != null) return@mapNotNull null
            val proc = t.script(u)?.summon?.procs?.filterIsInstance<Proc.Fusion>()?.firstOrNull() ?: return@mapNotNull null
            val sets = FxProcs.fusionSets(t, seat, u, proc, pool).filter { FxRules.summonZones(t, seat, u, it.toSet()).isNotEmpty() }
            if (sets.isEmpty()) null else u to sets
        }
    }

    /** The Ritual Monsters [op] could summon now for [act], with their Tribute sets. */
    internal fun rituals(t: FxTable, act: FxAct, op: Op.RitualSummon): List<Pair<Int, List<List<Int>>>> {
        val seat = act.seat
        val scope = act.scope(t)
        val pool = FxFilters.cards(op.tributesFrom, scope)
        return FxFilters.cards(op.from, scope).mapNotNull { u ->
            val c = t.card(u) ?: return@mapNotNull null
            if (!c.monster || CardFrame.RITUAL !in c.frames || !FxFilters.matches(op.ritual, u, scope)) return@mapNotNull null
            if (FxRules.specialRefusal(t, seat, u, ProcKind.RITUAL) != null) return@mapNotNull null
            val sets = FxProcs.ritualSets(t, seat, u, pool, op.levels).filter { FxRules.summonZones(t, seat, u, it.toSet()).isNotEmpty() }
            if (sets.isEmpty()) null else u to sets
        }
    }

    /** The Extra Deck monsters [f] matches that [act]'s seat could summon now by their own [kind] procedure. */
    internal fun procedures(t: FxTable, act: FxAct, f: Filter, kind: ProcKind): List<ProcOption> {
        val scope = act.scope(t)
        return FxFilters.area(Area.EXTRA, act.seat, t.state).flatMap { u ->
            if (!FxFilters.matches(f, u, scope)) emptyList() else FxProcs.options(t, act.seat, u).filter { it.kind == kind }
        }
    }

    /** What [op] may declare for [act]: names of the cards its seat can see (its own, and what is public), or a fixed list. */
    internal fun declarable(t: FxTable, act: FxAct, op: Op.Declare): List<Declared> = when (op.kind) {
        DeclareKind.NAME -> {
            val scope = act.scope(t)
            val s = t.state
            val seen = s.cards.values.filter { c ->
                if (c.token) return@filter false
                if (c.owner == act.seat) return@filter true
                val p = s.placeOf(c.uid)
                c.faceUp && (p is Place.Zone || (p is Place.Pile && (p.kind == PileKind.GY || p.kind == PileKind.BANISHED)))
            }
            seen.filter { op.among == null || FxFilters.matches(op.among, it.uid, scope) }
                .mapNotNull { c -> t.code(c.uid)?.let { code -> t.card(c.uid)?.name?.let { code to it } } }
                .distinctBy { it.first }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .map { (code, name) -> Declared(DeclareKind.NAME, value = code, word = name) }
        }
        DeclareKind.TYPE -> TYPES.map { Declared(DeclareKind.TYPE, word = it) }
        DeclareKind.ATTRIBUTE -> CardAttribute.entries.filter { it != CardAttribute.UNKNOWN }.map { Declared(DeclareKind.ATTRIBUTE, word = it.name) }
        DeclareKind.LEVEL -> (1..12).map { Declared(DeclareKind.LEVEL, value = it, word = "$it") }
    }

    /** The monster Types a declaration offers. */
    val TYPES: List<String> = listOf(
        "Aqua", "Beast", "Beast-Warrior", "Cyberse", "Dinosaur", "Divine-Beast", "Dragon", "Fairy", "Fiend", "Fish", "Illusion",
        "Insect", "Machine", "Plant", "Psychic", "Pyro", "Reptile", "Rock", "Sea Serpent", "Spellcaster", "Thunder", "Warrior",
        "Winged Beast", "Wyrm", "Zombie",
    )

    /**
     * The positions a monster may be summoned in by [pos]: face-up Attack or Defense when the effect leaves it open, the one
     * it fixes otherwise, face-down Defense only where it Sets; a Link Monster ([link]) always in Attack Position.
     */
    fun positions(pos: Pos, link: Boolean = false): List<CardPosition> = when {
        link -> listOf(CardPosition.FACE_UP_ATK)
        pos == Pos.EITHER -> listOf(CardPosition.FACE_UP_ATK, CardPosition.FACE_UP_DEF)
        else -> listOf(FxProcs.position(pos))
    }

    /** One run of steps: the act, its bindings as they grow, and the batch it is in. */
    private class Ctx(val sc: FxScribe, val act: FxAct) {
        val bound = LinkedHashMap<String, List<Int>>(act.bound)
        val declared = LinkedHashMap<String, Declared>(act.declared)
        var batch = sc.t.fx.batch + 1
        val seat = act.seat

        init {
            bound[Pick.SELF] = listOf(act.uid)
        }

        val t: FxTable get() = sc.t
        val s: DuelState get() = sc.t.state

        /** The effect asking, for every decision: its card, id and short name. */
        val source: FxSource = FxSource(act.uid, act.effect, sc.t.book.effect(act.card, act.effect)?.label.orEmpty())

        /** Where this run's steps stand among the effect's own (costs, targets, what it does), for "2 of 3". */
        private val effect = sc.t.book.effect(act.card, act.effect)
        private val offset = effect?.let { e ->
            when (act.part) {
                FxTag.ACTIVATE -> e.cost.size
                FxTag.RESOLVE -> e.cost.size + e.targets.size
                else -> 0
            }
        } ?: 0
        private val total = effect?.let { it.cost.size + it.targets.size + it.does.size } ?: 0
        private var at = 0
        private var depth = 0

        fun stepWords(): String? = if (effect == null || total == 0) null else "${(offset + at + 1).coerceAtMost(total)} of $total"

        /** Whether some of [among] lie where only their owner may look: a Deck, or face-down in an Extra Deck. */
        fun hidden(among: List<Int>): Boolean = among.any { u ->
            val p = s.placeOf(u)
            p is Place.Pile && (p.kind == PileKind.DECK || (p.kind == PileKind.EXTRA && t.inst(u)?.faceUp != true))
        }

        /** [min]–[max] of [among], chosen for [purpose] (a cost's own pick is a [Purpose.COST]): the uids. */
        fun cards(verb: String, among: List<Int>, min: Int, max: Int, purpose: Purpose, to: Landing? = null, who: Rel = Rel.YOU): List<Int> {
            val why = if (act.part == FxTag.COST && purpose != Purpose.TARGET) Purpose.COST else purpose
            val d = Decision.Cards(why(verb, who), among, min, max, why, to, among.map { s.placeOf(it) }, source, stepWords(), hidden(among))
            return sc.ask(d).map { among[it] }
        }

        fun landing(dest: Dest, positions: List<CardPosition> = emptyList()) = Landing(dest, seat, positions)

        fun scope(): FxScope = FxScope(sc.t, seat, act.uid, bound, declared)

        fun emit(a: DuelAction, memo: FxMemo = FxMemo()) = sc.emit(a, act.tag().copy(memo = memo.copy(batch = batch)))

        fun name(uid: Int): String = t.card(uid)?.name ?: "a card"

        /** The words for a choice: what, and for which card's effect. */
        fun why(verb: String, who: Rel = Rel.YOU): String {
            val label = t.book.effect(act.card, act.effect)?.label?.takeIf { it.isNotBlank() }
            val whose = if (who == Rel.THEM) "Your opponent chooses: " else ""
            return "$whose$verb · ${name(act.uid)}" + (label?.let { " ($it)" } ?: "")
        }

        // ---- steps and joins --------------------------------------------------------------------------------------

        fun steps(list: List<Step>): Boolean {
            var prev = true
            var all = true
            depth++
            list.forEachIndexed { i, step ->
                if (depth == 1) at = i
                if (i > 0 && (step.link == Join.THEN || step.link == Join.ALSO)) batch++
                val needs = step.link == Join.AND_IF_YOU_DO || step.link == Join.THEN
                val ok = if (needs && !prev) false else op(step.op)
                prev = ok
                all = all && ok
            }
            depth--
            return all
        }

        fun targets(picks: List<Pick>) {
            val all = ArrayList<Int>()
            picks.forEachIndexed { i, p ->
                val c = FxFilters.candidates(p, scope()).filter { it !in all }
                val need = if (p.upTo) 1 else p.n.coerceIn(1, Pick.MOST)
                if (c.size < need) throw FxStop.refuse("No legal target for ${name(act.uid)}.")
                val max = if (p.all) c.size else minOf(p.n.coerceIn(1, Pick.MOST), c.size)
                val min = if (p.all) c.size else need
                at = i
                val chosen = cards("Target", c, min, max, Purpose.TARGET, who = p.who)
                bound[targetKey(i)] = chosen
                p.bind?.let { bound[it] = chosen }
                all += chosen
            }
            bound[Pick.TARGETS] = all.toList()
        }

        // ---- picks ------------------------------------------------------------------------------------------------

        /** The cards [pick] takes, chosen when there is a choice; [keep] narrows its candidates for the op. */
        fun choose(pick: Pick, verb: String, purpose: Purpose, to: Landing? = null, keep: (Int) -> Boolean = { true }): Pair<List<Int>, Boolean> {
            val c = FxFilters.candidates(pick, scope()).filter(keep)
            val chosen: List<Int>
            val whole: Boolean
            when {
                pick.ref != null -> {
                    chosen = c
                    whole = c.isNotEmpty() && c.size == scope().ref(pick.ref).size
                }
                pick.top -> {
                    chosen = c
                    whole = c.isNotEmpty() && c.size == pick.n
                }
                pick.all -> {
                    chosen = c
                    whole = c.isNotEmpty()
                }
                c.isEmpty() -> {
                    chosen = emptyList()
                    whole = false
                }
                else -> {
                    val need = pick.n.coerceIn(1, Pick.MOST)
                    val max = minOf(need, c.size)
                    val min = if (pick.upTo) 1 else max
                    chosen = cards(verb, c, min, max, purpose, to, pick.who)
                    whole = chosen.size >= (if (pick.upTo) 1 else need)
                }
            }
            pick.bind?.let { bound[it] = chosen }
            return chosen to whole
        }

        // ---- the ops ----------------------------------------------------------------------------------------------

        fun op(op: Op): Boolean = when (op) {
            is Op.Move -> move(op)
            is Op.Add -> add(op.pick)
            is Op.Send -> pile(op.pick, "Send to the GY", Purpose.SEND, PileKind.GY, null, "send") { !inPile(s, it, PileKind.GY) }
            is Op.Discard -> pile(handPick(op.pick), "Discard", Purpose.DISCARD, PileKind.GY, null, FxFold.HOW_DISCARD) { inPile(s, it, PileKind.HAND) }
            is Op.Destroy -> destroy(op.pick)
            is Op.Banish -> pile(op.pick, "Banish", Purpose.BANISH, PileKind.BANISHED, if (op.faceDown) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_UP_ATK, "banish") {
                !inPile(s, it, PileKind.BANISHED)
            }
            is Op.Tribute -> pile(tributePick(op.pick), "Tribute", Purpose.TRIBUTE, PileKind.GY, null, FxSummons.HOW_TRIBUTE)
            is Op.Return -> back(op.pick, op.to)
            is Op.Draw -> draw(op)
            is Op.Shuffle -> shuffle(op)
            is Op.Reveal -> reveal(op.pick)
            is Op.SpecialSummon -> special(op)
            is Op.FusionSummon -> fusion(op)
            is Op.RitualSummon -> ritual(op)
            is Op.SynchroSummon -> procedure(op.f, ProcKind.SYNCHRO)
            is Op.XyzSummon -> procedure(op.f, ProcKind.XYZ)
            is Op.LinkSummon -> procedure(op.f, ProcKind.LINK)
            is Op.Attach -> attach(op)
            is Op.Detach -> detach(op)
            is Op.Token -> token(op)
            is Op.Negate -> negate(op)
            is Op.ChangeLevel -> level(op)
            is Op.Lp -> {
                val v = FxConds.value(op.delta, scope())
                if (v == null) false else {
                    scope().seats(op.rel).forEach { emit(DuelAction.Lp(it, delta = v)) }
                    true
                }
            }
            is Op.PayLp -> {
                val v = FxConds.value(op.n, scope())
                if (v == null || v < 0 || s.seats[seat].lp < v) false else {
                    if (v > 0) emit(DuelAction.Lp(seat, delta = -v))
                    true
                }
            }
            is Op.Counter -> counter(op)
            is Op.NormalSummonAgain -> {
                emit(DuelAction.Note("${name(act.uid)}: you may Normal Summon 1 more monster this turn.", seat), FxMemo(grant = op.filter))
                true
            }
            is Op.Choose -> choose(op)
            is Op.If -> if (FxConds.holds(op.cond, scope())) steps(op.then) else steps(op.otherwise)
            is Op.Restrict -> {
                restrict(op.restriction)
                true
            }
            is Op.Declare -> declare(op)
            is Op.Unknown -> throw FxStop.refuse("This effect holds a step written in a newer build's words.")
        }

        private fun move(op: Op.Move): Boolean = when (op.to) {
            Dest.MONSTER_ZONE, Dest.SPELL_ZONE, Dest.FIELD_ZONE -> {
                val (chosen, whole) = choose(op.pick, "Move", Purpose.OTHER, landing(op.to))
                var moved = 0
                chosen.forEach { u ->
                    val zones = when (op.to) {
                        Dest.MONSTER_ZONE -> s.freeZones(seat, ZoneKind.MONSTER)
                        Dest.SPELL_ZONE -> s.freeZones(seat, ZoneKind.SPELL)
                        else -> s.freeZones(seat, ZoneKind.FIELD)
                    }
                    if (zones.isEmpty()) return@forEach
                    val pos = when {
                        !op.faceDown -> CardPosition.FACE_UP_ATK
                        op.to == Dest.MONSTER_ZONE -> CardPosition.FACE_DOWN_DEF
                        else -> CardPosition.FACE_DOWN_ATK
                    }
                    val z = zone(u, zones, listOf(pos)) ?: return@forEach
                    emit(DuelAction.Move(u, z, pos, "place"))
                    moved++
                }
                whole && moved == chosen.size
            }
            Dest.GY -> pile(op.pick, "Send to the GY", Purpose.SEND, PileKind.GY, null, "send") { !inPile(s, it, PileKind.GY) }
            Dest.BANISHED -> pile(op.pick, "Banish", Purpose.BANISH, PileKind.BANISHED, if (op.faceDown) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_UP_ATK, "banish")
            else -> back(op.pick, op.to)
        }

        /** Each card [pick] takes to its owner's [kind] pile ([at] in it), `how` [how]. */
        fun pile(pick: Pick, verb: String, purpose: Purpose, kind: PileKind, pos: CardPosition?, how: String, at: Int? = null, keep: (Int) -> Boolean = { true }): Boolean {
            val dest = when {
                kind == PileKind.HAND -> Dest.HAND
                kind == PileKind.GY -> Dest.GY
                kind == PileKind.BANISHED -> Dest.BANISHED
                kind == PileKind.EXTRA -> Dest.EXTRA
                at == Place.BOTTOM -> Dest.DECK_BOTTOM
                else -> Dest.DECK_TOP
            }
            val (chosen, whole) = choose(pick, verb, purpose, landing(dest), keep)
            if (chosen.isEmpty()) return false
            chosen.forEach { u -> emit(DuelAction.Move(u, redirect(u, Place.Pile(owner(u), kind, at)), pos(u, kind, pos), how)) }
            return whole
        }

        fun owner(u: Int): Int = t.inst(u)?.owner ?: seat

        /** An Extra Deck monster sent to the hand or the Deck goes back to the Extra Deck. */
        fun redirect(u: Int, to: Place.Pile): Place.Pile {
            val extra = t.inst(u)?.extraDeck == true || t.card(u)?.extraDeck == true
            return if (extra && (to.kind == PileKind.HAND || to.kind == PileKind.DECK)) Place.Pile(to.seat, PileKind.EXTRA) else to
        }

        /** The position a card lands in: a Main Deck card in the Extra Deck is face-up. */
        fun pos(u: Int, kind: PileKind, wanted: CardPosition?): CardPosition? {
            if (kind == PileKind.EXTRA && t.inst(u)?.extraDeck != true) return CardPosition.FACE_UP_ATK
            return wanted
        }

        private fun add(pick: Pick): Boolean {
            val (chosen, whole) = choose(pick, "Add to your hand", Purpose.ADD, landing(Dest.HAND)) { !inPile(s, it, PileKind.HAND) }
            if (chosen.isEmpty()) return false
            val searched = chosen.filter { inPile(s, it, PileKind.DECK) }
            chosen.forEach { u ->
                val how = if (u in searched) "search" else "add"
                val to = redirect(u, Place.Pile(owner(u), PileKind.HAND))
                emit(DuelAction.Move(u, to, pos(u, to.kind, null), how))
            }
            if (searched.isNotEmpty()) {
                // A card added from the Deck is shown, and a Deck looked through is shuffled.
                emit(DuelAction.Reveal(seat, searched))
                if (!pick.top) searched.map(::owner).distinct().forEach { emit(DuelAction.Shuffle(it, PileKind.DECK)) }
            }
            return whole
        }

        private fun destroy(pick: Pick): Boolean {
            // Only a card on the field, or in a hand, is destroyed.
            val (chosen, whole) = choose(pick, "Destroy", Purpose.DESTROY, landing(Dest.GY)) { u -> s.placeOf(u).let { it is Place.Zone || (it is Place.Pile && it.kind == PileKind.HAND) } }
            if (chosen.isEmpty()) return false
            chosen.forEach { u ->
                val c = t.card(u)
                val onField = s.placeOf(u) is Place.Zone
                // A Pendulum Monster destroyed on the field goes to the Extra Deck face-up.
                val to = if (onField && c?.pendulum == true && c.monster && t.inst(u)?.faceUp == true) Place.Pile(owner(u), PileKind.EXTRA)
                else Place.Pile(owner(u), PileKind.GY)
                emit(DuelAction.Move(u, to, if (to.kind == PileKind.EXTRA) CardPosition.FACE_UP_ATK else null, FxFold.HOW_DESTROY))
            }
            return whole
        }

        private fun back(pick: Pick, to: Dest): Boolean = when (to) {
            Dest.HAND -> pile(pick, "Return to the hand", Purpose.RETURN, PileKind.HAND, null, "return") { !inPile(s, it, PileKind.HAND) }
            Dest.DECK_TOP -> pile(pick, "Return to the Deck", Purpose.RETURN, PileKind.DECK, null, "return", Place.TOP)
            Dest.DECK_BOTTOM -> pile(pick, "Return to the Deck", Purpose.RETURN, PileKind.DECK, null, "return", Place.BOTTOM)
            Dest.DECK_SHUFFLED -> {
                val (chosen, whole) = choose(pick, "Shuffle into the Deck", Purpose.RETURN, landing(Dest.DECK_SHUFFLED))
                if (chosen.isEmpty()) false else {
                    chosen.forEach { u -> emit(DuelAction.Move(u, redirect(u, Place.Pile(owner(u), PileKind.DECK, Place.TOP)), null, "shuffle")) }
                    chosen.filter { inPile(s, it, PileKind.DECK) }.map(::owner).distinct().forEach { emit(DuelAction.Shuffle(it, PileKind.DECK)) }
                    whole
                }
            }
            Dest.EXTRA -> pile(pick, "Return to the Extra Deck", Purpose.RETURN, PileKind.EXTRA, null, "return") { t.card(it)?.let { c -> c.extraDeck || c.pendulum } == true }
            Dest.GY -> pile(pick, "Send to the GY", Purpose.SEND, PileKind.GY, null, "send")
            Dest.BANISHED -> pile(pick, "Banish", Purpose.BANISH, PileKind.BANISHED, CardPosition.FACE_UP_ATK, "banish")
            Dest.MONSTER_ZONE, Dest.SPELL_ZONE, Dest.FIELD_ZONE -> move(Op.Move(pick, to))
        }

        private fun draw(op: Op.Draw): Boolean {
            var whole = true
            scope().seats(op.rel).forEach { who ->
                val n = minOf(op.n.coerceAtLeast(1), s.seats[who].deck.size)
                if (n < op.n.coerceAtLeast(1)) whole = false
                if (n > 0) emit(DuelAction.Draw(who, n))
            }
            return whole
        }

        private fun shuffle(op: Op.Shuffle): Boolean {
            val kind = when (op.pile) {
                Area.DECK -> PileKind.DECK
                Area.HAND -> PileKind.HAND
                Area.EXTRA -> PileKind.EXTRA
                else -> return false
            }
            scope().seats(op.rel).forEach { emit(DuelAction.Shuffle(it, kind)) }
            return true
        }

        private fun reveal(pick: Pick): Boolean {
            val (chosen, whole) = choose(pick, "Reveal", Purpose.REVEAL)
            if (chosen.isEmpty()) return false
            emit(DuelAction.Reveal(seat, chosen, 1 - seat))
            return whole
        }

        /** The position [card] takes, among [allowed]: asked when there is more than one ([Decision.Position]). */
        fun position(card: Int, allowed: List<CardPosition>): CardPosition =
            allowed[sc.ask(Decision.Position(card, allowed, source)).single()]

        /** Which of [zones] (the legal, free ones) [card] goes to, in one of [positions]: asked when there is more than one. */
        fun zone(card: Int, zones: List<Place.Zone>, positions: List<CardPosition>): Place.Zone? =
            if (zones.isEmpty()) null else zones[sc.ask(Decision.Zone(zones, card, positions, source)).single()]

        private fun special(op: Op.SpecialSummon): Boolean {
            val (chosen, whole) = choose(op.pick, "Special Summon", Purpose.SUMMON, landing(Dest.MONSTER_ZONE, positions(op.pos))) { summonable(t, seat, it) }
            if (chosen.isEmpty()) return false
            var done = 0
            // One card at a time, each its own zone and position.
            chosen.forEach { u ->
                // Again for each: a second copy of a once-a-turn monster, or a zone the first one took.
                if (!summonable(t, seat, u)) return@forEach
                val allowed = positions(op.pos, link = t.card(u)?.link != null)
                val z = zone(u, FxRules.summonZones(t, seat, u), allowed) ?: return@forEach
                emit(DuelAction.Move(u, z, position(u, allowed), FxProcs.HOW_SUMMON))
                done++
            }
            return whole && done == chosen.size
        }

        /** Which of [sets] [uid]'s materials are: the only one, or the chooser's (a set no option holds is refused). */
        private fun materials(uid: Int, sets: List<List<Int>>, verb: String): List<Int> {
            if (sets.size == 1) return sets.single()
            val among = sets.flatten().distinct()
            val purpose = if (verb == "Ritual Summon") Purpose.TRIBUTE else Purpose.MATERIAL
            val to = if (verb == "Xyz Summon") null else landing(Dest.GY)
            val chosen = cards("$verb for ${name(uid)}", among, sets.minOf { it.size }, sets.maxOf { it.size }, purpose, to).toSet()
            return sets.firstOrNull { it.toSet() == chosen } ?: throw FxStop.refuse("Those materials do not make a $verb of ${name(uid)}.")
        }

        private fun fusion(op: Op.FusionSummon): Boolean {
            val options = fusions(t, act, op)
            if (options.isEmpty()) return false
            val uid = cards("Fusion Summon", options.map { it.first }, 1, 1, Purpose.SUMMON, landing(Dest.MONSTER_ZONE, positions(Pos.EITHER))).single()
            val set = materials(uid, options.first { it.first == uid }.second, "Fusion Summon")
            set.forEach { m -> emit(DuelAction.Move(m, Place.Pile(owner(m), PileKind.GY), how = FxProcs.HOW_MATERIAL), FxMemo(summon = ProcKind.FUSION)) }
            val z = zone(uid, FxRules.summonZones(t, seat, uid), positions(Pos.EITHER)) ?: throw FxStop.refuse("No zone is free for ${name(uid)}.")
            emit(DuelAction.Move(uid, z, position(uid, positions(Pos.EITHER)), "fusion"))
            return true
        }

        private fun ritual(op: Op.RitualSummon): Boolean {
            val options = rituals(t, act, op)
            if (options.isEmpty()) return false
            val uid = cards("Ritual Summon", options.map { it.first }, 1, 1, Purpose.SUMMON, landing(Dest.MONSTER_ZONE, positions(Pos.EITHER))).single()
            val set = materials(uid, options.first { it.first == uid }.second, "Ritual Summon")
            set.forEach { m -> emit(DuelAction.Move(m, Place.Pile(owner(m), PileKind.GY), how = FxSummons.HOW_TRIBUTE), FxMemo(summon = ProcKind.RITUAL)) }
            val z = zone(uid, FxRules.summonZones(t, seat, uid), positions(Pos.EITHER)) ?: throw FxStop.refuse("No zone is free for ${name(uid)}.")
            emit(DuelAction.Move(uid, z, position(uid, positions(Pos.EITHER)), "ritual"))
            return true
        }

        private fun procedure(f: Filter, kind: ProcKind): Boolean {
            val options = procedures(t, act, f, kind)
            if (options.isEmpty()) return false
            val uids = options.map { it.uid }.distinct()
            val allowed = positions(Pos.EITHER, link = kind == ProcKind.LINK)
            val uid = cards("${FxRules.procWord(kind)} Summon", uids, 1, 1, Purpose.SUMMON, landing(Dest.MONSTER_ZONE, allowed)).single()
            val mine = options.filter { it.uid == uid }
            val sets = mine.flatMap { it.sets }
            val set = materials(uid, sets, "${FxRules.procWord(kind)} Summon")
            val option = mine.first { set in it.sets }
            val z = zone(uid, option.zones[option.sets.indexOf(set)], allowed) ?: return false
            val word = kind.name.lowercase()
            val pos = position(uid, allowed)
            FxProcs.actions(t, kind, uid, set, z, pos).forEach { a ->
                if (a is DuelAction.Move && a.uid == uid) emit(a.copy(how = word))
                else emit(a, FxMemo(summon = kind))
            }
            return true
        }

        private fun attach(op: Op.Attach): Boolean {
            val h = host(t, scope(), op.to) ?: return false
            val (chosen, whole) = choose(op.pick, "Attach", Purpose.ATTACH) { it != h && it !in t.inst(h)?.under.orEmpty() }
            if (chosen.isEmpty()) return false
            chosen.forEach { emit(DuelAction.Move(it, Place.Under(h), how = "attach")) }
            return whole
        }

        private fun detach(op: Op.Detach): Boolean {
            val h = host(t, scope(), op.from) ?: return false
            val under = t.inst(h)?.under.orEmpty()
            val n = op.n.coerceAtLeast(1)
            if (under.size < n) return false
            val chosen = cards("Detach", under, n, n, Purpose.SEND, landing(Dest.GY))
            chosen.forEach { m -> emit(DuelAction.Move(m, Place.Pile(owner(m), PileKind.GY), how = FxFold.HOW_DETACH)) }
            return true
        }

        private fun token(op: Op.Token): Boolean {
            if (tokensBanned(t, seat)) return false
            val to = scope().seats(op.rel).first()
            val n = op.n.coerceIn(1, 5)
            var made = 0
            val allowed = positions(op.pos)
            repeat(n) {
                // A token is no card yet: its zone and position name the table's next uid, the one it will take.
                val next = s.nextUid
                val z = zone(next, s.freeZones(to, ZoneKind.MONSTER), allowed) ?: return@repeat
                emit(
                    DuelAction.Token(seat, z, position(next, allowed), 0, op.name, op.atk, op.def),
                    FxMemo(token = FxToken(op.level, op.attribute, op.race)),
                )
                made++
            }
            return made == n
        }

        private fun negate(op: Op.Negate): Boolean {
            val n = negatable(t, act, op) ?: return false
            val card = s.chain[n - 1].uid
            if (op.what == NegWhat.ACTIVATION) {
                DuelVerbs.negate(s, seat, n, t.facts.catalog()).actions.forEach { emit(it) }
            } else {
                emit(DuelAction.Negate(seat, n), FxMemo(effectOnly = true))
            }
            op.bind?.let { bound[it] = listOfNotNull(card) }
            return true
        }

        private fun level(op: Op.ChangeLevel): Boolean {
            val (chosen, whole) = choose(op.pick, "Change the Level", Purpose.OTHER) { t.level(it) != null }
            if (chosen.isEmpty()) return false
            val to = op.to?.let { FxConds.value(it, scope()) }
            val by = op.by?.let { FxConds.value(it, scope()) }
            if (to == null && by == null) return false
            chosen.forEach { u ->
                val now = t.level(u) ?: return@forEach
                val next = ((to ?: now) + (by ?: 0)).coerceAtLeast(1)
                emit(
                    DuelAction.Note("${name(u)} is Level $next" + (if (op.until == com.kaiharimoto.mastertool.core.duel.Lock.UNTIL_DUEL) "." else " this turn."), seat),
                    FxMemo(level = LevelChange(u, t.fx.life(u), to, by, op.until)),
                )
            }
            return whole
        }

        private fun counter(op: Op.Counter): Boolean {
            val (chosen, whole) = choose(op.pick, "Counters", Purpose.OTHER) { s.placeOf(it) is Place.Zone }
            if (chosen.isEmpty()) return false
            var ok = whole
            chosen.forEach { u ->
                val has = t.inst(u)?.counters?.get(op.kind) ?: 0
                if (has + op.delta < 0) ok = false else emit(DuelAction.Counter(u, op.delta, op.kind))
            }
            return ok
        }

        private fun choose(op: Op.Choose): Boolean {
            val open = op.options.indices.filter { i -> op.options[i].isEmpty() || able(t, act.copy(bound = bound, declared = declared), op.options[i].first().op) }
            if (open.isEmpty()) return false
            val labels = open.map { i -> op.labels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Option ${i + 1}" }
            val pick = open[sc.ask(Decision.Option(labels, source)).single()]
            return steps(op.options[pick])
        }

        fun restrict(r: Restriction) {
            FxRules.seatsOf(r.seat, seat).forEach { who ->
                emit(DuelAction.Lock(who, lockWords(r, name(act.uid)), r.until), FxMemo(restriction = r))
            }
        }

        private fun declare(op: Op.Declare): Boolean {
            val among = declarable(t, act, op)
            if (among.isEmpty()) return false
            val d = among[sc.ask(Decision.Declare(op.kind, among.map { it.word }, source)).single()]
            declared[op.bind] = d
            emit(DuelAction.Note("${name(act.uid)}: declared ${d.word}.", seat), FxMemo(declared = mapOf(op.bind to d)))
            return true
        }
    }

    /** A restriction as a lock's words, in our own: what it forbids, and whose effect left it. */
    fun lockWords(r: Restriction, source: String): String {
        val what = when (r.ban) {
            Ban.SPECIAL_SUMMON -> "No Special Summons"
            Ban.SPECIAL_SUMMON_FROM_EXTRA -> "No Special Summons from the Extra Deck"
            Ban.NORMAL_SUMMON -> "No Normal Summons or Sets"
            Ban.ACTIVATE -> "No activations"
        }
        return if (r.except != null) "$what, except as $source allows" else "$what ($source)"
    }
}
