package com.kaiharimoto.mastertool.core.duel.effects

/**
 * Walks the vocabulary: every step an effect holds however deep ([steps]), whether anything in it is a newer build's
 * word this one cannot read ([unread]), and what an activation includes ([includes]) — "an effect that adds a card from
 * the Deck", read off its steps, never off its text.
 */
object FxWalk {
    /** Every step of [steps], nested ones ([Op.Choose], [Op.If]) after their parent, in order. */
    fun steps(steps: List<Step>): List<Step> = buildList {
        fun walk(list: List<Step>) {
            list.forEach { s ->
                add(s)
                when (val op = s.op) {
                    is Op.Choose -> op.options.forEach(::walk)
                    is Op.If -> { walk(op.then); walk(op.otherwise) }
                    else -> {}
                }
            }
        }
        walk(steps)
    }

    /** Every step of [e]: its cost, then what it does. */
    fun steps(e: Effect): List<Step> = steps(e.cost) + steps(e.does)

    /** How deep [steps] nest: 1 for a flat list (D.md §3.4 allows 6); counted no further than [cap]. */
    fun depth(steps: List<Step>, cap: Int = DEEPEST): Int = if (steps.isEmpty()) 0 else if (cap <= 0) 1 else 1 + (steps.maxOfOrNull { s ->
        when (val op = s.op) {
            is Op.Choose -> op.options.maxOfOrNull { depth(it, cap - 1) } ?: 0
            is Op.If -> maxOf(depth(op.then, cap - 1), depth(op.otherwise, cap - 1))
            else -> 0
        }
    } ?: 0)

    /** Deeper than any walk goes: a filter or a condition nested past this, or steps past [FxSteps.MOST_DEPTH], is never read. */
    const val DEEPEST = 32

    /**
     * Whether [e] nests deeper than the engine walks — steps past [FxSteps.MOST_DEPTH], filters or conditions past
     * [DEEPEST] — checked without ever going deeper than that itself, so a hostile script cannot overflow the stack.
     * Such an effect is unread: never offered, never used.
     */
    fun tooDeep(e: Effect): Boolean {
        fun f(x: Filter?, d: Int): Boolean = x != null && (d > DEEPEST || when (x) {
            is Filter.All -> x.all.any { f(it, d + 1) }
            is Filter.AnyOf -> x.any.any { f(it, d + 1) }
            is Filter.Not -> f(x.not, d + 1)
            else -> false
        })
        fun n(x: Num?, d: Int): Boolean = x is Num.Count && f(x.where, d + 1)
        fun c(x: Cond?, d: Int): Boolean = x != null && (d > DEEPEST || when (x) {
            is Cond.All -> x.all.any { c(it, d + 1) }
            is Cond.AnyOf -> x.any.any { c(it, d + 1) }
            is Cond.Not -> c(x.not, d + 1)
            is Cond.Controls -> f(x.where, d + 1) || n(x.n, d + 1)
            is Cond.Count -> f(x.where, d + 1) || n(x.n, d + 1)
            is Cond.Newest -> f(x.about, d + 1)
            else -> false
        })
        fun p(x: Pick) = f(x.where, 0)
        fun s(list: List<Step>, d: Int): Boolean = d > FxSteps.MOST_DEPTH || list.any { st ->
            when (val op = st.op) {
                is Op.Choose -> op.options.any { s(it, d + 1) }
                is Op.If -> c(op.cond, 0) || s(op.then, d + 1) || s(op.otherwise, d + 1)
                is Op.Move -> p(op.pick)
                is Op.Add -> p(op.pick)
                is Op.Send -> p(op.pick)
                is Op.Discard -> p(op.pick)
                is Op.Destroy -> p(op.pick)
                is Op.Banish -> p(op.pick)
                is Op.Tribute -> p(op.pick)
                is Op.Return -> p(op.pick)
                is Op.Reveal -> p(op.pick)
                is Op.SpecialSummon -> p(op.pick)
                is Op.Attach -> p(op.pick)
                is Op.Counter -> p(op.pick)
                is Op.ChangeLevel -> p(op.pick)
                is Op.FusionSummon -> f(op.fusion, 0)
                is Op.RitualSummon -> f(op.ritual, 0)
                is Op.SynchroSummon -> f(op.f, 0)
                is Op.XyzSummon -> f(op.f, 0)
                is Op.LinkSummon -> f(op.f, 0)
                is Op.NormalSummonAgain -> f(op.filter, 0)
                is Op.Declare -> f(op.among, 0)
                is Op.Restrict -> f(op.restriction.except, 0)
                else -> false
            }
        }
        return s(e.cost, 1) || s(e.does, 1) || c(e.condition, 0) || f(e.trigger?.about, 0) || f(e.respond?.about, 0) ||
            e.targets.any(::p) || e.leaves.any { f(it.except, 0) }
    }

    /** Whether [f] holds a word this build cannot read, or nests past [DEEPEST] (never walked deeper than that). */
    fun unread(f: Filter?, d: Int = 0): Boolean = when (f) {
        null -> false
        is Filter.Unknown -> true
        is Filter.All -> d >= DEEPEST || f.all.any { unread(it, d + 1) }
        is Filter.AnyOf -> d >= DEEPEST || f.any.any { unread(it, d + 1) }
        is Filter.Not -> d >= DEEPEST || unread(f.not, d + 1)
        else -> false
    }

    fun unread(n: Num?): Boolean = when (n) {
        null -> false
        is Num.Unknown -> true
        is Num.Count -> unread(n.where)
        else -> false
    }

    /** Whether [c] holds a word this build cannot read, or nests past [DEEPEST]. */
    fun unread(c: Cond?, d: Int = 0): Boolean = when (c) {
        null -> false
        is Cond.Unknown -> true
        is Cond.Controls -> unread(c.where) || unread(c.n)
        is Cond.Count -> unread(c.where) || unread(c.n)
        is Cond.Newest -> unread(c.about)
        is Cond.Lp -> unread(c.n)
        is Cond.Compare -> unread(c.left) || unread(c.right)
        is Cond.All -> d >= DEEPEST || c.all.any { unread(it, d + 1) }
        is Cond.AnyOf -> d >= DEEPEST || c.any.any { unread(it, d + 1) }
        is Cond.Not -> d >= DEEPEST || unread(c.not, d + 1)
        else -> false
    }

    fun unread(p: Pick): Boolean = unread(p.where)

    fun unread(op: Op): Boolean = when (op) {
        is Op.Unknown -> true
        is Op.Move -> unread(op.pick)
        is Op.Add -> unread(op.pick)
        is Op.Send -> unread(op.pick)
        is Op.Discard -> unread(op.pick)
        is Op.Destroy -> unread(op.pick)
        is Op.Banish -> unread(op.pick)
        is Op.Tribute -> unread(op.pick)
        is Op.Return -> unread(op.pick)
        is Op.Reveal -> unread(op.pick)
        is Op.SpecialSummon -> unread(op.pick)
        is Op.FusionSummon -> unread(op.fusion)
        is Op.RitualSummon -> unread(op.ritual)
        is Op.SynchroSummon -> unread(op.f)
        is Op.XyzSummon -> unread(op.f)
        is Op.LinkSummon -> unread(op.f)
        is Op.Attach -> unread(op.pick)
        is Op.ChangeLevel -> unread(op.pick) || unread(op.to) || unread(op.by)
        is Op.Lp -> unread(op.delta)
        is Op.PayLp -> unread(op.n)
        is Op.Counter -> unread(op.pick)
        is Op.NormalSummonAgain -> unread(op.filter)
        is Op.Choose -> op.options.any { o -> o.any { unread(it.op) } }
        is Op.If -> unread(op.cond) || (op.then + op.otherwise).any { unread(it.op) }
        is Op.Restrict -> unread(op.restriction.except)
        is Op.Declare -> unread(op.among)
        is Op.Draw, is Op.Shuffle, is Op.Detach, is Op.Token, is Op.Negate -> false
    }

    fun unread(p: Proc): Boolean = when (p) {
        is Proc.Unknown -> true
        is Proc.Fusion -> p.materials.any { unread(it.where) }
        is Proc.Synchro -> unread(p.tuner.where) || unread(p.others.where)
        is Proc.Xyz -> unread(p.each)
        is Proc.Link -> unread(p.each) || unread(p.also)
        Proc.Ritual -> false
        is Proc.Inherent -> tooDeep(Effect(FxTag.PROC, kind = Kind.IGNITION, condition = p.condition, cost = p.cost)) ||
            unread(p.condition) || p.opt is Opt.Unknown || p.cost.any { unread(it.op) }
    }

    /** Whether anything in [e] is a word this build cannot read: such an effect is never used. */
    fun unread(e: Effect): Boolean =
        tooDeep(e) || e.opt is Opt.Unknown || unread(e.condition) || unread(e.trigger?.about) || unread(e.respond?.about) ||
            e.targets.any(::unread) || (steps(e.cost) + steps(e.does)).any { unread(it.op) } || e.leaves.any { unread(it.except) }

    /** What an activation of [e] includes, read off what it does (its cost is not what it includes). */
    fun includes(e: Effect): Set<Includes> = buildSet {
        steps(e.does).forEach { s ->
            when (val op = s.op) {
                is Op.Add -> {
                    val areas = op.pick.from.map { it.area }
                    if (Area.DECK in areas) add(Includes.SEARCH)
                    if (Area.GY in areas || Area.BANISHED in areas) add(Includes.SALVAGE)
                }
                is Op.Send -> if (op.pick.from.any { it.area == Area.DECK } || op.pick.top) add(Includes.SEND_FROM_DECK)
                is Op.Move -> if (op.to == Dest.GY && (op.pick.from.any { it.area == Area.DECK } || op.pick.top)) add(Includes.SEND_FROM_DECK)
                is Op.SpecialSummon, is Op.FusionSummon, is Op.RitualSummon, is Op.SynchroSummon, is Op.XyzSummon,
                is Op.LinkSummon, is Op.Token -> add(Includes.SPECIAL_SUMMON)
                is Op.Draw -> add(Includes.DRAW)
                is Op.Destroy -> add(Includes.DESTROY)
                is Op.Banish -> add(Includes.BANISH)
                is Op.Return -> add(Includes.RETURN)
                is Op.Negate -> add(Includes.NEGATE)
                is Op.Discard -> add(Includes.DISCARD)
                else -> {}
            }
        }
    }
}
