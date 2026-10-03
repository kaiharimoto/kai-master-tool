package com.kaiharimoto.mastertool.core.duel

/**
 * The numbers a fold would otherwise hand out, written into the log instead (1.0.86): a token's uid and a
 * lock's id. Before, a token took the table's next uid and a lock the highest held id + 1 as the log was
 * folded, so a move put into a phase gone by (`Replays.insert`, Insert here, Ai's `at`) renumbered every
 * later token and lock — and a later `Move(token)`, `Attack(token)` or `Unlock(id)` silently acted on the
 * wrong one. They are stamped on commit now, as randomness is ([DuelRandom]), and a stamped number is
 * never one used anywhere else in the log: a token put into the past takes the highest ever + 1.
 *
 * A log written before has none; it folds as it always did, and is [settle]d — every number written in
 * as the fold gave it — before anything is put into it or taken out of it.
 */
object DuelIds {
    /** The first token uid and lock id used nowhere in a log. */
    data class Next(val uid: Int, val lock: Int)

    /** The first free numbers for a log whose fold (all of it) is [end]: past every one handed out or written. */
    fun next(end: DuelState, entries: List<DuelEntry>): Next {
        var uid = end.nextUid
        var lock = maxOf(end.lastLock, end.locks.maxOfOrNull { it.id } ?: 0) + 1
        entries.forEach { e ->
            when (val a = e.action) {
                is DuelAction.Token -> a.uid?.let { uid = maxOf(uid, it + 1) }
                is DuelAction.Lock -> a.id?.let { lock = maxOf(lock, it + 1) }
                else -> Unit
            }
        }
        return Next(uid, lock)
    }

    /**
     * [actions] — one group, made against the table [before] — with every token and lock numbered from
     * [next]. A later action of the group that names the token or lock by the number the table would
     * have given it (a combo's "token, then tribute it") is pointed at the stamped one. Actions already
     * stamped keep their numbers.
     */
    fun stamp(before: DuelState, actions: List<DuelAction>, next: Next): List<DuelAction> {
        if (actions.none { (it is DuelAction.Token && it.uid == null) || (it is DuelAction.Lock && it.id == null) }) return actions
        var raw = before
        var uid = next.uid
        var lock = next.lock
        val uids = HashMap<Int, Int>()
        val locks = HashMap<Int, Int>()
        return actions.map { a ->
            var out = remap(a, uids, locks)
            if (a is DuelAction.Token && a.uid == null) {
                val given = uid++
                if (raw.nextUid != given) uids[raw.nextUid] = given
                out = (out as DuelAction.Token).copy(uid = given)
            } else if (a is DuelAction.Lock && a.id == null) {
                val predicted = (raw.locks.maxOfOrNull { it.id } ?: 0) + 1
                val given = lock++
                if (predicted != given) locks[predicted] = given
                out = (out as DuelAction.Lock).copy(id = given)
            }
            // The table as the group's maker saw it, to know which number each would have been given.
            raw = (DuelRules.apply(raw, a) as? Outcome.Ok)?.state ?: raw
            out
        }
    }

    /** The log, folded, and the table after [at] of its entries and after all of them. */
    data class Settled(val entries: List<DuelEntry>, val before: DuelState, val end: DuelState)

    /**
     * Every token and lock in [entries] that folds with the number the fold gave it written in. Nothing
     * on the table changes; what changes is that a later edit can no longer renumber them. One left out by
     * the fold (struck through) keeps none.
     */
    fun settle(header: DuelHeader, entries: List<DuelEntry>, at: Int = 0): Settled {
        var s = DuelSetup.initial(header)
        val k = at.coerceIn(0, entries.size)
        var before = s
        val out = ArrayList<DuelEntry>(entries.size)
        entries.forEachIndexed { i, e ->
            if (i == k) before = s
            val o = DuelRules.apply(s, e.action, e.seat)
            if (o is Outcome.Ok) {
                val a = e.action
                val written = when {
                    a is DuelAction.Token && a.uid == null -> a.copy(uid = s.nextUid)
                    a is DuelAction.Lock && a.id == null -> a.copy(id = o.state.locks.last().id)
                    else -> null
                }
                out += if (written != null) e.copy(action = written) else e
                s = o.state
            } else {
                out += e
            }
        }
        if (k == entries.size) before = s
        return Settled(out, before, s)
    }

    /** [a] with the token uids and lock ids it names moved by [uids] and [locks]. */
    fun remap(a: DuelAction, uids: Map<Int, Int>, locks: Map<Int, Int> = emptyMap()): DuelAction {
        if (uids.isEmpty() && locks.isEmpty()) return a
        fun u(x: Int): Int = uids[x] ?: x
        fun u(x: Int?): Int? = x?.let { uids[it] ?: it }
        fun p(place: Place?): Place? = if (place is Place.Under) place.copy(host = u(place.host)) else place
        return when (a) {
            is DuelAction.Move -> a.copy(uid = u(a.uid), to = p(a.to)!!)
            is DuelAction.Position -> a.copy(uid = u(a.uid))
            is DuelAction.Counter -> a.copy(uid = u(a.uid))
            is DuelAction.ChainAdd -> a.copy(uid = u(a.uid), targets = a.targets.map { u(it) })
            is DuelAction.Attack -> a.copy(attacker = u(a.attacker), target = u(a.target))
            is DuelAction.Keep -> a.copy(uid = u(a.uid))
            is DuelAction.Target -> a.copy(from = u(a.from), to = a.to.map { u(it) })
            is DuelAction.Reveal -> a.copy(uids = a.uids.map { u(it) })
            is DuelAction.Ping -> a.copy(uid = u(a.uid), place = p(a.place))
            is DuelAction.Unlock -> a.copy(id = locks[a.id] ?: a.id)
            else -> a
        }
    }
}
