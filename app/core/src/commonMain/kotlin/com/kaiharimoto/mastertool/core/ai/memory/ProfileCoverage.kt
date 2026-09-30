package com.kaiharimoto.mastertool.core.ai.memory

import com.kaiharimoto.mastertool.core.ai.report.GuideDoc

/**
 * How much of the person's profile is known, section by section (1.0.65, kai: "how personalized is
 * the about you interview? … I feel like being more personalized matters more"). Learn About You
 * opens on it, so each interview starts where the profile is thinnest — never at the top of the
 * same outline every time — and moves on from what is already covered.
 */
object ProfileCoverage {
    enum class Depth(val word: String) { EMPTY("nothing yet"), THIN("thin"), COVERED("covered") }

    data class Line(val section: String, val entries: Int, val depth: Depth)

    /** The sections an interview fills, in the profile's own order ("Notes" is where the unlabelled go). */
    val SECTIONS: List<String> = GuideDoc.PROFILE - "Notes"

    /** Each section's entries in USER.md, and how well it is known: none, one or two, three or more. */
    fun of(userMemory: String?): List<Line> {
        val doc = GuideDoc.profile(userMemory)
        return SECTIONS.map { name ->
            val n = doc.sections.firstOrNull { it.name == name }?.entries?.size ?: 0
            Line(name, n, if (n == 0) Depth.EMPTY else if (n < 3) Depth.THIN else Depth.COVERED)
        }
    }

    /** The sections to start with: empty ones first, then thin, in the profile's order. */
    fun thinnest(lines: List<Line>): List<String> =
        lines.filter { it.depth != Depth.COVERED }.sortedBy { it.depth.ordinal }.map { it.section }

    /** The line the interview opens on: what is known, and where to begin. */
    fun brief(lines: List<Line>): String {
        val known = lines.joinToString(", ") { "${it.section}: ${it.entries} (${it.depth.word})" }
        val start = thinnest(lines)
        return "What my profile already covers — $known. " +
            if (start.isEmpty()) "Everything has something: deepen what is out of date, and ask about what changed since." else "Start with ${start.take(2).joinToString(" and ")}."
    }
}
