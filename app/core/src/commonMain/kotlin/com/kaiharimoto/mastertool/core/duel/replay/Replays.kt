package com.kaiharimoto.mastertool.core.duel.replay

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelIds
import com.kaiharimoto.mastertool.core.duel.DuelRecord
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.DuelTimeline

/** How far one step of a replay goes: one gesture, to the next phase, or to the next turn. */
enum class ReplayUnit { GROUP, PHASE, TURN }

/**
 * A replay is a duel's record read back and walked — forwards and backwards, a gesture, a phase or a
 * turn at a time — and edited: an entry taken out, one put in where the replay stands, a note written
 * at a moment, and a "what if" played on from any point as a duel of its own.
 *
 * The log is the replay, so editing is editing a list: entries keep their randomness stamped in them,
 * and anything that no longer fits the table after an edit is skipped and shown struck through
 * ([DuelTimeline] says which), never refused — a slip never destroys a replay.
 */
object Replays {

    /** The position of the next stop after [at] (entries played), stepping by [unit]. */
    fun next(entries: List<DuelEntry>, at: Int, unit: ReplayUnit): Int {
        if (at >= entries.size) return entries.size
        return when (unit) {
            ReplayUnit.GROUP -> {
                val g = entries[at].group
                var i = at + 1
                while (i < entries.size && entries[i].group == g) i++
                i
            }
            ReplayUnit.PHASE, ReplayUnit.TURN -> {
                var i = at
                while (i < entries.size) {
                    val a = entries[i].action
                    i++
                    if (a == DuelAction.EndTurn || (unit == ReplayUnit.PHASE && a is DuelAction.Phase)) break
                }
                i
            }
        }
    }

    /** The position of the stop before [at], stepping by [unit]. */
    fun previous(entries: List<DuelEntry>, at: Int, unit: ReplayUnit): Int {
        if (at <= 0) return 0
        return when (unit) {
            ReplayUnit.GROUP -> {
                val g = entries[at - 1].group
                var i = at - 1
                while (i > 0 && entries[i - 1].group == g) i--
                i
            }
            ReplayUnit.PHASE, ReplayUnit.TURN -> {
                // Back past the boundary just behind us, then to the one before it.
                var i = at - 1
                fun boundary(k: Int): Boolean {
                    val a = entries[k].action
                    return a == DuelAction.EndTurn || (unit == ReplayUnit.PHASE && a is DuelAction.Phase)
                }
                if (i >= 0 && boundary(i)) i--
                while (i >= 0 && !boundary(i)) i--
                i + 1
            }
        }
    }

    /** The turn and phase the replay stands in after [at] entries, for the timeline's label. */
    fun marks(entries: List<DuelEntry>): List<Mark> = entries.mapIndexedNotNull { i, e ->
        when (val a = e.action) {
            DuelAction.EndTurn -> Mark(i + 1, Mark.TURN)
            is DuelAction.Phase -> Mark(i + 1, Mark.PHASE, a.phase.label)
            is DuelAction.Note -> Mark(i + 1, Mark.NOTE, a.text)
            else -> null
        }
    }

    data class Mark(val at: Int, val kind: String, val text: String = "") {
        companion object {
            const val TURN = "turn"
            const val PHASE = "phase"
            const val NOTE = "note"
        }
    }

    /** The record with entry [i] taken out. Tokens and locks after it keep their numbers ([DuelIds.settle]). */
    fun delete(r: DuelRecord, i: Int): DuelRecord {
        if (i !in r.entries.indices) return r
        val all = DuelIds.settle(r.header, r.entries).entries
        val entries = (all.take(i) + all.drop(i + 1)).renumber()
        return r.copy(entries = entries, cursor = entries.size)
    }

    /** The record with the whole group of entry [i] taken out — one gesture, as undo takes it. */
    fun deleteGroup(r: DuelRecord, i: Int): DuelRecord {
        val g = r.entries.getOrNull(i)?.group ?: return r
        val entries = DuelIds.settle(r.header, r.entries).entries.filterNot { it.group == g }.renumber()
        return r.copy(entries = entries, cursor = entries.size)
    }

    /**
     * The record with [actions] put in at [at], as one new group by [seat]. Groups after it are
     * renumbered so a group never spans an insertion. A token or lock it makes takes a number used nowhere
     * in the log, and the ones after it keep theirs (1.0.86, [DuelIds]).
     */
    fun insert(r: DuelRecord, at: Int, actions: List<DuelAction>, seat: Int?, time: Long = 0L): DuelRecord {
        val k = at.coerceIn(0, r.entries.size)
        val settled = DuelIds.settle(r.header, r.entries, k)
        val stamped = DuelIds.stamp(settled.before, actions, DuelIds.next(settled.end, settled.entries))
        val group = (settled.entries.getOrNull(k - 1)?.group ?: -1) + 1
        val added = stamped.map { DuelEntry(0, time, seat, group, it) }
        val after = settled.entries.drop(k).map { it.copy(group = it.group + 1) }
        val entries = (settled.entries.take(k) + added + after).renumber()
        return r.copy(entries = entries, cursor = entries.size)
    }

    /** The record with entry [i]'s action replaced. */
    fun alter(r: DuelRecord, i: Int, action: DuelAction): DuelRecord {
        if (i !in r.entries.indices) return r
        return r.copy(entries = DuelIds.settle(r.header, r.entries).entries.mapIndexed { k, e -> if (k == i) e.copy(action = action) else e })
    }

    /** A note at [at]: a line in the log that changes nothing, for whoever watches next. */
    fun annotate(r: DuelRecord, at: Int, text: String, seat: Int? = null): DuelRecord =
        insert(r, at, listOf(DuelAction.Note(text, seat)), seat)

    /**
     * A "what if": the duel as it stood after [at] entries, as a live game to play on from — the same
     * header, so its shuffles and coins still come from the same seed, and its record remembers where
     * it came from.
     */
    fun branch(r: DuelRecord, at: Int): DuelGame {
        val kept = r.entries.take(at.coerceIn(0, r.entries.size)).renumber()
        val game = DuelGame.of(DuelRecord(r.header, kept, kept.size, name = r.name))
        return game
    }

    /** The entries a fold skipped, for the timeline to strike through. */
    fun refused(r: DuelRecord): Set<Int> = DuelSetup.fold(r.header, r.entries).second

    fun timeline(r: DuelRecord): DuelTimeline = DuelTimeline(r.header, r.entries)

    private fun List<DuelEntry>.renumber(): List<DuelEntry> = mapIndexed { i, e -> e.copy(i = i) }
}
