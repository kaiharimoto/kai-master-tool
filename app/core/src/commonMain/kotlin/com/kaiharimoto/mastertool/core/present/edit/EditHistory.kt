package com.kaiharimoto.mastertool.core.present.edit

/**
 * Undo and redo over whole values (the presentation is immutable data, so a snapshot is a
 * reference, not a copy). Each entry is the value before an edit and the edit's words; a
 * run of edits sharing a [coalesce] key — one drag, one stretch of typing — is one entry.
 */
class EditHistory<T>(private val limit: Int = 200) {
    private data class Entry<T>(val before: T, val label: String, val key: String?)

    private val undos = ArrayDeque<Entry<T>>()
    private val redos = ArrayDeque<Entry<T>>()

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** The words of the edit Undo would take back. */
    val undoLabel: String? get() = undos.lastOrNull()?.label
    val redoLabel: String? get() = redos.lastOrNull()?.label

    /** Records that [before] is about to become something else by [label]. */
    fun push(before: T, label: String, coalesce: String? = null) {
        val last = undos.lastOrNull()
        redos.clear()
        if (coalesce != null && last != null && last.key == coalesce) return
        undos.addLast(Entry(before, label, coalesce))
        while (undos.size > limit) undos.removeFirst()
    }

    /** Ends a coalescing run, so the next edit with the same key starts a new entry. */
    fun seal() {
        val last = undos.removeLastOrNull() ?: return
        undos.addLast(last.copy(key = null))
    }

    /** The value before the last edit, [current] kept for Redo; null with nothing to undo. */
    fun undo(current: T): T? {
        val e = undos.removeLastOrNull() ?: return null
        redos.addLast(Entry(current, e.label, null))
        return e.before
    }

    fun redo(current: T): T? {
        val e = redos.removeLastOrNull() ?: return null
        undos.addLast(Entry(current, e.label, null))
        return e.before
    }

    fun clear() {
        undos.clear()
        redos.clear()
    }
}
