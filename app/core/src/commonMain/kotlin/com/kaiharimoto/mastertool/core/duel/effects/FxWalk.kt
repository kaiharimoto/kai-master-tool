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

    /** How deep [steps] nest: 1 for a flat list (D.md §3.4 allows 6). */
    fun depth(steps: List<Step>): Int = if (steps.isEmpty()) 0 else 1 + (steps.maxOfOrNull { s ->
        when (val op = s.op) {
            is Op.Choose -> op.options.maxOfOrNull(::depth) ?: 0
            is Op.If -> maxOf(depth(op.then), depth(op.otherwise))
            else -> 0
        }
    } ?: 0)

    fun unread(f: Filter?): Boolean = when (f) {
        null -> false
        is Filter.Unknown -> true
        is Filter.All -> f.all.any(::unread)
        is Filter.AnyOf -> f.any.any(::unread)
        is Filter.Not -> unread(f.not)
        else -> false
    }

    fun unread(n: Num?): Boolean = when (n) {
        null -> false
        is Num.Unknown -> true
        is Num.Count -> unread(n.where)
        else -> false
    }

    fun unread(c: Cond?): Boolean = when (c) {
        null -> false
        is Cond.Unknown -> true
        is Cond.Controls -> unread(c.where) || unread(c.n)
        is Cond.Count -> unread(c.where) || unread(c.n)
        is Cond.Newest -> unread(c.about)
        is Cond.Lp -> unread(c.n)
        is Cond.Compare -> unread(c.left) || unread(c.right)
        is Cond.All -> c.all.any(::unread)
        is Cond.AnyOf -> c.any.any(::unread)
        is Cond.Not -> unread(c.not)
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
        is Proc.Inherent -> unread(p.condition) || p.opt is Opt.Unknown || p.cost.any { unread(it.op) }
    }

    /** Whether anything in [e] is a word this build cannot read: such an effect is never used. */
    fun unread(e: Effect): Boolean =
        e.opt is Opt.Unknown || unread(e.condition) || unread(e.trigger?.about) || unread(e.respond?.about) ||
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
