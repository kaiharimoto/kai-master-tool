package com.kaiharimoto.mastertool.core.ai.eval

/**
 * Test scores' words (Settings › Assistant › Test scores; the code keeps its first name, Trust): for what a run got wrong, the
 * question first, cut at a word, then what it answered against what is right and how close it had to be, and the item's
 * id last — never an id leading a line, nor a prompt cut mid-word; and for each set, a verdict against fixed bars.
 */
object TrustWords {
    /**
     * What a set's score means for the person (the design review, finding 7, kai's choice (b)): a word, said against
     * fixed bars, so a newcomer who opened the dialog to decide whether to trust the odds is told.
     */
    enum class Verdict(val words: String) {
        RELY("Rely on it"),
        CHECK("Check it"),
        YOURSELF("Do it yourself"),
    }

    /** A set's bars: at or over [rely] it is relied on, at or over [check] checked, under it done yourself. */
    data class Bars(val rely: Double, val check: Double)

    /**
     * The answer sets' bars — hand odds, rulings, decklists, card truth: 95 % and 80 %. A wrong answer here is a wrong
     * number or ruling the person acts on, so relying on it takes nineteen in twenty; under four in five, the app's own
     * counter or the rulebook is quicker than checking every answer.
     */
    val ANSWERS = Bars(0.95, 0.80)

    /**
     * The fact-checker's bars, on mistakes caught of those planted: 90 % and 70 %. The checker is a second pass over an
     * answer that was already right or wrong on its own, so a miss in ten leaves the answer's own accuracy standing; but
     * a checker that cries wolf teaches you to ignore it, so more than one false alarm in [ALARMS_RELY] clean answers
     * keeps it from "Rely on it", and more than one in [ALARMS_CHECK] puts it at "Do it yourself".
     */
    val CHECKER = Bars(0.90, 0.70)
    const val ALARMS_RELY = 10
    const val ALARMS_CHECK = 4

    /**
     * The duel puzzles' bars, on puzzles solved: 90 % and 60 %. A puzzle is a whole turn played to lethal, many moves
     * each of which must be legal and right, so the bar for checking its lines is lower than for one number; but a score
     * no better than only attacking (the set's greedy baseline) is never more than "Do it yourself".
     */
    val PUZZLES = Bars(0.90, 0.60)

    /** For the checker: mistakes caught of those planted, and false alarms of the clean answers. */
    data class Checker(val caught: Int, val planted: Int, val alarms: Int, val clean: Int)

    fun checker(set: EvalSet, run: EvalRun): Checker {
        val planted = set.items.filter { (it.grader as? Grader.Planted)?.hasError == true }.map { it.id }.toSet()
        val byId = run.items.associateBy { it.id }
        val caught = planted.count { byId[it]?.firstPass == true }
        val clean = run.items.filter { it.id !in planted }
        return Checker(caught, planted.count { it in byId }, clean.count { !it.firstPass }, clean.size)
    }

    /** The bars [set] is read against. */
    fun bars(set: EvalSet): Bars = when {
        set.checker -> CHECKER
        set.id == EvalSets.PUZZLES -> PUZZLES
        else -> ANSWERS
    }

    /**
     * The share the verdict reads: for the checker, mistakes caught; for the rest, right first time — and with several
     * tries, the lower of that and right every time, since relying on an answer means it comes out the same again.
     */
    fun share(set: EvalSet, run: EvalRun): Double {
        if (set.checker) return checker(set, run).let { if (it.planted == 0) 0.0 else it.caught.toDouble() / it.planted }
        return if (run.tries > 1) minOf(run.passAt1, run.passAll) else run.passAt1
    }

    /**
     * [run]'s verdict on [set], or null when there is none to give: nothing answered, or a run stopped before the end (a
     * part of a set is not the set). [greedy] is the puzzles' only-attacks baseline, when known.
     */
    fun verdict(set: EvalSet, run: EvalRun, greedy: Int? = null): Verdict? {
        if (run.items.isEmpty() || run.stoppedEarly) return null
        val bars = bars(set)
        val share = share(set, run)
        var v = when {
            share >= bars.rely - 1e-9 -> Verdict.RELY
            share >= bars.check - 1e-9 -> Verdict.CHECK
            else -> Verdict.YOURSELF
        }
        if (set.checker) {
            val c = checker(set, run)
            if (v == Verdict.RELY && c.alarms * ALARMS_RELY > c.clean) v = Verdict.CHECK
            if (c.alarms * ALARMS_CHECK > c.clean) v = Verdict.YOURSELF
        }
        if (set.id == EvalSets.PUZZLES && greedy != null && run.items.count { it.firstPass } <= greedy) v = Verdict.YOURSELF
        return v
    }

    private fun pct(x: Double): String = "${(x * 100).toInt()}%"

    /**
     * The bars said in words, for the set's details: "Rely on it at 95% and over, check it from 80%, under 80% do it
     * yourself — right first time …".
     */
    fun barsWords(set: EvalSet): String {
        val b = bars(set)
        val of = when {
            set.checker -> " — mistakes caught, and no more than one false alarm in $ALARMS_RELY clean answers to rely on it"
            set.id == EvalSets.PUZZLES -> " — puzzles solved, and never when only attacking would do as well"
            set.id == EvalSets.OPTIMIZE -> " — the planted change found and proposed with numbers a tool computed"
            else -> " — right first time" + " (with several tries, right every time too)"
        }
        return "Rely on it at ${pct(b.rely)} and over, check it from ${pct(b.check)}, under ${pct(b.check)} do it yourself$of."
    }
    /** One missed item: the question's gist, the verdict in words, and the item's id. */
    data class Miss(val question: String, val verdict: String, val id: String)

    private val expected = Regex("""^(.+?) \(expected (.+?)\)$""")

    fun miss(item: EvalItem?, outcome: ItemOutcome, max: Int = 90): Miss =
        Miss(item?.prompt?.let { gist(it, max) }.orEmpty(), verdict(item?.grader, outcome.read), outcome.id)

    /**
     * The grader's reading in words: "74.5% (expected 74.2%)" is "answered 74.5%, right is 74.2% (to 0.1%)" — the
     * precision said, so a near miss reads as the miss it is; anything else as the grader wrote it.
     */
    fun verdict(grader: Grader?, read: String): String {
        val m = expected.matchEntire(read.trim()) ?: return read
        val (said, right) = m.destructured
        val within = (grader as? Grader.Percent)?.let { " (to ${precision(it.decimals)}%)" }.orEmpty()
        return "answered $said, right is $right$within"
    }

    /** 1 → "0.1", 0 → "1", 2 → "0.01". */
    fun precision(decimals: Int): String =
        if (decimals <= 0) "1" else "0." + "0".repeat(decimals - 1) + "1"

    /**
     * A question's first line, at most [max] characters, cut at a word with "…" — never inside a `[[Card]]`, which the
     * dialog draws as the card's name.
     */
    fun gist(prompt: String, max: Int = 90): String {
        val line = prompt.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.length <= max) return line
        var cut = line.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        // A card's name is never cut: back to before its brackets.
        val open = line.lastIndexOf("[[", cut)
        if (open >= 0 && line.indexOf("]]", open).let { it < 0 || it + 2 > cut }) cut = line.lastIndexOf(' ', open).takeIf { it > 0 } ?: open
        return line.substring(0, cut).trimEnd(' ', ',', ';', ':', '(', '—') + "…"
    }
}
