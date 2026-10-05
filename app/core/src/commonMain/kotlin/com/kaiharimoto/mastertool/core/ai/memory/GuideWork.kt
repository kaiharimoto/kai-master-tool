package com.kaiharimoto.mastertool.core.ai.memory

import com.kaiharimoto.mastertool.core.ai.TuneIntensity
import com.kaiharimoto.mastertool.core.ai.report.SessionReport

/**
 * What one Fine Tuning run may add to a deck's guide (1.0.66, kai: "if the study run is deep let it
 * add up to 20k"). The guide itself has no cap ([UNBOUNDED]); a run does, by its intensity, so a
 * Quick pass stays a quick pass and a Deep one has the room to write everything it found. It is
 * cost control, never a cap on what Ai knows (1.1.11): a run that fills its room says so, leaves what
 * it had left in its report as "Next run:" questions, and the next run on the deck begins with them.
 */
object GuideBudget {
    /** What an open question in a report begins with when it is writing a full run left for the next. */
    const val NEXT_RUN = "Next run:"

    /** The refusal Ai reads when a write would take this run past [budget]; null when it fits. */
    fun refusal(startUsed: Int, nextUsed: Int, budget: Int, intensity: String): String? {
        val added = nextUsed - startUsed
        if (added <= budget) return null
        return "This run has room to add ${grouped(budget)} characters to the guide at $intensity, and that would make it ${grouped(added)}. " +
            "The guide itself has no cap; the room is this run's. Tighten or merge entries (replace, remove) if that says it better; " +
            "otherwise stop writing to the guide in this run, tell the person the run's room is full, and put what you still meant " +
            "to write in session_report's open_questions, each beginning “$NEXT_RUN”: the next run on this deck starts from them."
    }

    /** How the run's room is said to the model at the start. */
    fun brief(intensity: TuneIntensity): String = "You may add up to ${grouped(intensity.guideBudget)} characters to the guide in this run."

    /** What a run that filled its room left for the next ([NEXT_RUN] questions in its [report]), without the mark. */
    fun leftOver(report: SessionReport?): List<String> = report?.openQuestions.orEmpty()
        .map(String::trim)
        .filter { it.startsWith(NEXT_RUN, ignoreCase = true) }
        .map { it.drop(NEXT_RUN.length).trim() }
        .filter { it.isNotEmpty() }

    /** The words a new run on the deck begins with, when the last one ([report]) filled its room; null when it did not. */
    fun carryOver(report: SessionReport?): String? {
        val left = leftOver(report).takeIf { it.isNotEmpty() } ?: return null
        return "The last run filled its room in the guide before it was done, and left this to write first: " +
            left.joinToString("; ") { it.trimEnd('.') } + "."
    }

    /** What the person is told when a run used all of its room: the guide is not full, the run is. */
    fun filled(budget: Int, intensity: String): String =
        "This run used all of its room in the guide (${grouped(budget)} characters at $intensity). The guide has no cap: " +
            "what it had left is in its report, and the next Fine Tuning run on this deck carries on from there."

    /** 20000 as "20,000". */
    fun grouped(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
}

/**
 * Refactor guide (1.0.66, kai: "a refactor guide functionality that cleans up anything that's not
 * actually helpful or useful/improve and organize it"): Ai rewrites the whole guide at once — the
 * one write that can reorder, merge and drop — in a session of its own that ends in the review,
 * where every change is kept or undone.
 */
object GuideRewrite {
    /** The new guide, from [text]: one `- ` entry per line, an indented line continuing one; headings and blank lines dropped. */
    fun entries(text: String): List<String> =
        AiMemory.parse(text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")).let { parsed ->
            // Lines before the first bullet are the preamble to a parser; here every line is meant as an entry.
            (parsed.preamble.filter { it.isNotBlank() } + parsed.entries).map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() }
        }

    /** [doc] with its entries replaced by [text]'s, the title and any note above them kept. */
    fun rewrite(doc: MemoryDoc, text: String, entryLimit: Int): MemoryWrite {
        val next = entries(text)
        if (next.isEmpty()) return MemoryWrite.Refused("That would leave the guide empty. Rewrite it with its entries, one \"- \" line each.")
        // A rewrite that keeps a tenth of a long guide is a mistake more often than a cleanup.
        if (doc.used > 2000 && next.sumOf { it.length } < doc.used / 10) {
            return MemoryWrite.Refused("That keeps under a tenth of the guide (${next.sumOf { it.length }} of ${doc.used} characters). Rewrite all of it, not part.")
        }
        next.firstOrNull { it.length > entryLimit }?.let { return MemoryWrite.Refused("One entry is ${it.length} characters; keep each under $entryLimit, or split it.") }
        val after = doc.copy(entries = next)
        return MemoryWrite.Done(after, summary(doc, after))
    }

    /** "Rewrote the guide: 48 entries, 12,030 characters → 31 entries, 8,410 characters; 9 kept word for word." */
    fun summary(before: MemoryDoc, after: MemoryDoc): String {
        val same = after.entries.count { it in before.entries }
        return "Rewrote the guide: ${before.entries.size} entries, ${before.used} characters → ${after.entries.size} entries, ${after.used} characters; " +
            "$same kept word for word. The person reviews every change when the session ends."
    }
}
