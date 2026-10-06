package com.kaiharimoto.mastertool.core.duel.effects.goldfish

/*
 * Reading a result in the Effects app (Phase D step 4, agent (c); `docs/phases/D.md` §5.6): **every number opens its hands**
 * — reached, no line, undecided, a line, the hands that held a card played as inert — and any hand opens as a replay. A kept
 * result says when it is stale: the deck changed, or a written effect the run trusted. Pure, so the pane and its tests read
 * the same lists.
 */

/** Which hands a number stands for. */
sealed interface HandPick {
    /** The hands that ended [end]. */
    data class End(val end: HandEnd) : HandPick

    /** The hands line [index] of the result reached. */
    data class Line(val index: Int) : HandPick

    /** The hands that held a card played as inert. */
    data object HeldUnknown : HandPick

    /** The reached hands whose line moved a card played as inert. */
    data object TouchedUnknown : HandPick
}

object GoldfishBrowse {
    /** The hands of [r] that [pick] stands for, in index order. */
    fun hands(r: GoldfishResult, pick: HandPick): List<HandOutcome> = r.outcomes.filter { o ->
        when (pick) {
            is HandPick.End -> o.end == pick.end
            is HandPick.Line -> o.end == HandEnd.REACHED && o.line == pick.index
            HandPick.HeldUnknown -> o.heldUnknown
            HandPick.TouchedUnknown -> o.touchedUnknown
        }
    }

    /** How many hands [pick] stands for, without listing them. */
    fun count(r: GoldfishResult, pick: HandPick): Int = when (pick) {
        is HandPick.End -> when (pick.end) {
            HandEnd.REACHED -> r.reached
            HandEnd.NO_LINE -> r.noLine
            HandEnd.UNDECIDED -> r.undecided
        }
        is HandPick.Line -> r.lines.getOrNull(pick.index)?.count ?: 0
        HandPick.HeldUnknown -> r.heldUnknown
        HandPick.TouchedUnknown -> r.touchedUnknown
    }

    /** The word for how a hand ended. */
    fun endWords(end: HandEnd): String = when (end) {
        HandEnd.REACHED -> "Reached"
        HandEnd.NO_LINE -> "No line"
        HandEnd.UNDECIDED -> "Undecided"
    }

    /** What [pick] means, for the head of its list: "Reached — 1,262 of 2,000 hands (63.1 %)". */
    fun title(r: GoldfishResult, pick: HandPick): String {
        val n = count(r, pick)
        val of = "${GoldfishWords.count(n)} of ${GoldfishWords.count(r.hands)} hands (${GoldfishWords.pct(n.toDouble() / r.hands.coerceAtLeast(1))})"
        return when (pick) {
            is HandPick.End -> "${endWords(pick.end)} — $of"
            is HandPick.Line -> "${r.lines.getOrNull(pick.index)?.skeleton ?: "A line"} — $of"
            HandPick.HeldUnknown -> "Held a card with no trusted effect — $of"
            HandPick.TouchedUnknown -> "Reached through a line that moves an unknown card — $of"
        }
    }

    /** What [pick] means, in a line under its title: how to read the hands it lists. */
    fun explain(pick: HandPick): String = when (pick) {
        is HandPick.End -> when (pick.end) {
            HandEnd.REACHED -> "The trusted effects found a line to the end board from each of these."
            HandEnd.NO_LINE -> "The search covered every line within its bounds: the trusted effects cannot get there from these."
            HandEnd.UNDECIDED -> "The search ran out of budget: a larger budget may settle these. They count as not reached."
        }
        is HandPick.Line -> "Each of these hands reached the end board by this line."
        HandPick.HeldUnknown -> "Each of these held a card with no trusted effect, played as inert: the true number may be higher."
        HandPick.TouchedUnknown -> "Each of these reached the board through a line that moves a card with no trusted effect."
    }

    /** A hand's name: "Hand 13" (hands are counted from 1 where a person reads them; the index stays 0-based). */
    fun handName(o: HandOutcome): String = "Hand ${o.index + 1}"

    /** The name a hand's replay is opened under: "Goldfish: Mirrorjade — hand 13 of 2,000, seed 7, going first". */
    fun replayName(r: GoldfishResult, index: Int): String =
        "Goldfish: ${r.target.name} — hand ${index + 1} of ${GoldfishWords.count(r.hands)}, seed ${r.seed}, going ${if (r.first) "first" else "second"}"

    /** The shares of reached, no line and undecided, for the bar: they add to one (or are all zero). */
    fun shares(r: GoldfishResult): Triple<Double, Double, Double> {
        val n = r.hands.coerceAtLeast(1).toDouble()
        return Triple(r.reached / n, r.noLine / n, r.undecided / n)
    }

    /**
     * Why a kept [r] no longer stands for the deck as it is: [deck] is the deck's fingerprint now (`Ledger.fingerprint`),
     * [library] the trusted scripts' now (`FxTrust.library` over the deck's cards). Empty when it is fresh.
     */
    fun stale(r: GoldfishResult, deck: String, library: String): List<String> = buildList {
        if (r.deck.isNotEmpty() && r.deck != deck) add("the deck changed since")
        if (r.library.isNotEmpty() && r.library != library) add("a written effect it trusted changed since")
    }

    /**
     * The setup that makes [r]'s hands again on [deck] (the deck as it is now), so a kept result's hands open as replays;
     * null, with why, when they would not be the same hands: the deck changed, or the result played a recorded line
     * ([r]'s combo is not kept with it).
     */
    fun setup(r: GoldfishResult, deck: GoldfishDeck): Pair<GoldfishSetup?, String?> = when {
        r.deck.isNotEmpty() && r.deck != deck.fingerprint -> null to "The deck changed since this run: its hands are not the deck's now. Run it again."
        r.combo != null -> null to "This run played a recorded line: open its hands from a new run."
        else -> GoldfishSetup(deck, r.target, r.first, r.hands, r.seed, r.budget, depth = r.depth.takeIf { it > 0 } ?: GoldfishSearch.DEFAULT_DEPTH) to null
    }

    /** The newest kept results first. */
    fun newestFirst(results: List<GoldfishResult>): List<GoldfishResult> = results.withIndex().sortedWith(
        compareByDescending<IndexedValue<GoldfishResult>> { it.value.at }.thenByDescending { it.index },
    ).map { it.value }

    /** A kept result in one line: "Mirrorjade · going first · 2,000 hands · seed 7 · 63.1 %". */
    fun keptWords(r: GoldfishResult): String =
        "${r.target.name} · going ${if (r.first) "first" else "second"} · ${GoldfishWords.count(r.hands)} hands · seed ${r.seed} · ${GoldfishWords.pct(r.rate)}"

    /** A run's live line: "812 of 2,000 hands · 401 reached · 38 hands a second". */
    fun progressWords(p: GoldfishProgress): String =
        "${GoldfishWords.count(p.done)} of ${GoldfishWords.count(p.total)} hands · ${GoldfishWords.count(p.reached)} reached · " +
            "${p.handsPerSecond.let { if (it >= 10) it.toInt().toString() else ((it * 10).toInt() / 10.0).toString() }} hands a second"

    /** A seed typed by the person: digits (an optional minus), else null. */
    fun seedOf(text: String): Long? = text.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()

    /** A count of hands typed by the person, kept between 1 and [Goldfish.MOST_HANDS]; null when it does not read. */
    fun handsOf(text: String): Int? = text.trim().replace(",", "").toIntOrNull()?.takeIf { it in 1..Goldfish.MOST_HANDS }
}
