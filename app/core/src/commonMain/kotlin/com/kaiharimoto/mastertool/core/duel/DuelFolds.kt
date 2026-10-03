package com.kaiharimoto.mastertool.core.duel

/**
 * The log folded once and kept (1.0.86): the duel's log rail, the turn's tally and the lines a guest is
 * sent all read the table entry by entry, and each of them used to fold the whole log again from the deal
 * on every move and every tick of a replay — a cost that grew with the square of the duel. This keeps what
 * was read of each entry ([read]: its words, say) and the table every [EVERY] entries, and on each [sync]
 * only reads the entries it has not seen: a new move adds one, an undo or a redo reads nothing (the log
 * still holds what was undone), and an edit before the end — a move put into the past, a step cut — goes
 * back to the nearest table kept before it and reads on from there.
 *
 * Memory only, and one thread's (the page's): the log is the record, this is a cache of it.
 */
class DuelFolds<T>(
    val header: DuelHeader,
    /** What to keep of each entry, read with the table [before] and [after] it, and whether the table took it. */
    private val read: ((entry: DuelEntry, before: DuelState, after: DuelState, applied: Boolean) -> T)? = null,
) {
    private val entries = ArrayList<DuelEntry>()
    private val results = ArrayList<T>()
    /** The table after k entries, for every k that is a multiple of [EVERY]. */
    private val snapshots = ArrayList<DuelState>()
    private var tip: DuelState = DuelSetup.initial(header)

    /** How many entries this has applied to a table, all told: what the tests count. */
    var applied: Int = 0
        private set

    /** How many entries of the log it holds. */
    val size: Int get() = entries.size

    init {
        snapshots += tip
    }

    /** Brings the cache up to [log]: whatever it held past the first entry that differs is read again. */
    fun sync(log: List<DuelEntry>): DuelFolds<T> {
        val m = minOf(entries.size, log.size)
        var k = 0
        // Data classes compare by identity first, so an unchanged log costs a pointer check an entry.
        while (k < m && entries[k] == log[k]) k++
        if (k < entries.size) truncate(k)
        for (i in k until log.size) extend(log[i])
        return this
    }

    /** What was read of the first [n] entries. */
    fun results(n: Int = entries.size): List<T> = results.take(n.coerceIn(0, results.size))

    /** The table after the first [n] entries of the log last synced. */
    fun stateAt(n: Int): DuelState {
        val target = n.coerceIn(0, entries.size)
        if (target == entries.size) return tip
        val base = target / EVERY
        var s = snapshots[base]
        for (i in base * EVERY until target) s = fold(s, entries[i])
        return s
    }

    private fun fold(s: DuelState, e: DuelEntry): DuelState {
        applied++
        return (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.state ?: s
    }

    private fun extend(e: DuelEntry) {
        applied++
        val o = DuelRules.apply(tip, e.action, e.seat) as? Outcome.Ok
        val after = o?.state ?: tip
        read?.let { results += it(e, tip, after, o != null) }
        entries += e
        tip = after
        if (entries.size % EVERY == 0) snapshots += tip
    }

    private fun truncate(k: Int) {
        val state = stateAt(k)
        while (entries.size > k) entries.removeAt(entries.size - 1)
        while (results.size > k) results.removeAt(results.size - 1)
        while (snapshots.size > k / EVERY + 1) snapshots.removeAt(snapshots.size - 1)
        tip = state
    }

    companion object {
        const val EVERY = 32

        /** A cache of the tables alone, nothing read. */
        fun states(header: DuelHeader): DuelFolds<Unit> = DuelFolds(header, null)
    }
}
