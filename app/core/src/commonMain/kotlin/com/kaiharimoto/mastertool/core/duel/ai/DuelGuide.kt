package com.kaiharimoto.mastertool.core.duel.ai

/**
 * Ai's guide at the table (Phase C stage 2, `docs/phases/C.md` §4; the lead "Ai has no access to its guide at the table"):
 * the guide to the deck it plays (`guides/<deck>.md`, `MemoryKind.GUIDE`, each number wearing its proof's mark) and the
 * deck's combos (`<data>/duel/combos/<deck>.json`), put in front of a duel conversation once — lean, within a budget,
 * because every round at the table is read again. The guide is unbounded (Fine Tuning may write 20,000 characters); here
 * its entries are taken in order, each cut to [ENTRY_CAP], until [GUIDE_BUDGET], and the rest is counted, not dropped
 * silently. The other deck's guide is the caller's to add, and only with full knowledge.
 */
object DuelGuide {
    /** The guide's room at the table, in characters (about a thousand tokens). */
    const val GUIDE_BUDGET = 4000

    /** One guide entry's room: its point, not its whole argument. */
    const val ENTRY_CAP = 600

    /** The combos' room, in characters. */
    const val COMBO_BUDGET = 1500

    /** One combo's room: its name, what it needs, its steps. */
    const val COMBO_CAP = 360

    /**
     * The block for [deckName]: its [guide] as the prompt reads it (`- entry` lines, as `guideForPrompt` writes them) and
     * its [combos], within budget; "" when there is neither. [theirs]: the other seat's deck, read with full knowledge.
     */
    fun block(
        deckName: String,
        guide: String,
        combos: List<Combo>,
        theirs: Boolean = false,
        guideBudget: Int = GUIDE_BUDGET,
        comboBudget: Int = COMBO_BUDGET,
    ): String {
        val entries = entries(guide)
        if (entries.isEmpty() && combos.isEmpty()) return ""
        val deck = "“${deckName.ifBlank { "this deck" }}”"
        return buildString {
            if (entries.isNotEmpty()) {
                appendLine(
                    if (theirs) "Their deck's guide, $deck (you read the table with full knowledge; a mark in brackets says whether a number still holds):"
                    else "Your guide to how $deck plays, at the table (a mark in brackets says whether a number still holds):",
                )
                val (kept, left) = fit(entries.map { cut(it, ENTRY_CAP) }, guideBudget)
                kept.forEach { appendLine("- $it") }
                if (left > 0) appendLine("(and $left more ${if (left == 1) "entry" else "entries"} of the guide, left out for room)")
            }
            if (combos.isNotEmpty()) {
                if (entries.isNotEmpty()) appendLine()
                appendLine(if (theirs) "Their deck's combos:" else "Your combos for $deck (duel_combo run plays one by its id):")
                val lines = combos.map { c ->
                    val needs = c.needs.joinToString().ifBlank { "nothing" }
                    cut("${c.name} (id ${c.id}; needs $needs): ${c.steps.joinToString("; ")}", COMBO_CAP)
                }
                val (kept, left) = fit(lines, comboBudget)
                kept.forEach { appendLine("- $it") }
                if (left > 0) appendLine("(and $left more; duel_combo list names them all)")
            }
        }.trimEnd()
    }

    /** A guide as `- entry` lines into its entries; a line not starting a new entry belongs to the one before it. */
    fun entries(guide: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        guide.lineSequence().forEach { line ->
            when {
                line.startsWith("- ") -> out += StringBuilder(line.removePrefix("- ").trim())
                line.isBlank() -> Unit
                out.isEmpty() -> out += StringBuilder(line.trim())
                else -> out.last().append(' ').append(line.trim())
            }
        }
        return out.map { it.toString() }.filter { it.isNotBlank() }
    }

    /** The [items] that fit in [budget] characters in order, and how many did not. The first always fits. */
    private fun fit(items: List<String>, budget: Int): Pair<List<String>, Int> {
        var used = 0
        val kept = mutableListOf<String>()
        for (item in items) {
            if (kept.isNotEmpty() && used + item.length + 3 > budget) break
            kept += item
            used += item.length + 3
        }
        return kept to items.size - kept.size
    }

    /** [text] cut to [cap] characters at a word, with an ellipsis when it was cut. */
    fun cut(text: String, cap: Int): String {
        val t = text.trim()
        if (t.length <= cap) return t
        val head = t.take(cap - 1)
        val space = head.lastIndexOf(' ')
        return (if (space > cap / 2) head.take(space) else head).trimEnd(',', ';', ':', ' ') + "…"
    }
}
