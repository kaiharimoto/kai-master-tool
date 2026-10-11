package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.shootout.bench.Behind
import com.kaiharimoto.mastertool.core.shootout.bench.PatternCell
import com.kaiharimoto.mastertool.core.shootout.bench.PatternFamily
import com.kaiharimoto.mastertool.core.shootout.bench.PatternRow
import com.kaiharimoto.mastertool.core.shootout.bench.PatternWords
import com.kaiharimoto.mastertool.core.shootout.bench.PlanResult
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutPatterns
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.SectionTitle
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlin.math.roundToInt

/*
 * What the hands say (Phase S, S4.2; `docs/phases/S.md` §5): which kinds of opening hand win, first and second, and what each
 * siding plan does on the hands it leaves — read off the same answers as every card's number, every number opening its hands.
 */

/**
 * Opening patterns: the kinds of hand (a starter of yours, interaction of theirs), how many of each group the hand opens, and
 * the commonest mixes of groups — each one's win rate over real hands with its range, on 0–100 with a tick where the whole
 * situation stands, and how often a real hand is one. A pattern clear of its situation with every one counted is set heavier,
 * and the headline names the ones that win most and least.
 */
@Composable
internal fun OpeningPatterns(h: NeueHolders, r: ShootoutResults, shown: List<Stratum>, phone: Boolean) {
    val s = h.shootout
    val alone = s.bench?.alone ?: true
    val called = remember(r) { ShootoutPatterns.called(r.patterns) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(null, "Opening patterns")
        Help(
            "Which kinds of opening hand " + (if (alone) "do what the deck wants" else "win") + ", read off the same answers as the cards: each one's " +
                (if (alone) "rate" else "win rate") + " over real hands with its 80% and 95% ranges, the tick where the whole situation stands, and how often a real hand is one. " +
                "A pattern is set heavier where it stands clear of its situation, by three points or more, with every pattern counted. A press on a number lists the hands behind it.",
            Modifier.widthIn(max = 900.dp),
        )
        if (r.patterns.isEmpty()) Help("No pattern is common enough to read yet.") else PatternTable(h, r, shown, phone, called, alone)
    }
}

@Composable
private fun PatternTable(h: NeueHolders, r: ShootoutResults, shown: List<Stratum>, phone: Boolean, called: Set<Pair<String, Stratum>>, alone: Boolean) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.mapNotNull { PatternWords.headline(r.patterns, it, called, alone) }.forEach { Body(it, color = c.ink) }
        val name: Dp? = if (phone) null else 260.dp
        if (!phone && shown.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.width(name ?: 0.dp))
                shown.forEachIndexed { i, st ->
                    key(st) {
                        if (i > 0) Spacer(Modifier.width(16.dp))
                        Micro(ShootoutWords.stratum(st), Modifier.weight(1f), color = c.ink70)
                    }
                }
            }
        }
        PatternFamily.entries.forEach { family ->
            val rows = r.patterns.filter { it.pattern.family == family }
            if (rows.isNotEmpty()) {
                key(family) {
                    Column {
                        Micro(familyWords(family, alone), Modifier.padding(top = 8.dp, bottom = 4.dp), color = c.ink45)
                        HRule()
                        rows.forEach { row ->
                            key(row.pattern.key) {
                                PatternLine(h, row, shown, name, called, alone)
                                HRule()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun familyWords(f: PatternFamily, alone: Boolean): String = when (f) {
    PatternFamily.KIND -> if (alone) "With a starter or without" else "Your starters and their interaction"
    PatternFamily.ROLE -> "How many of each group the hand opens"
    PatternFamily.MIX -> "The groups a hand opens together, the commonest"
}

@Composable
private fun PatternLine(h: NeueHolders, row: PatternRow, shown: List<Stratum>, name: Dp?, called: Set<Pair<String, Stratum>>, alone: Boolean) {
    val c = Mu.colors
    val heavy = shown.any { (row.pattern.key to it) in called }
    val title: @Composable (Modifier) -> Unit = { m ->
        MuText(
            row.pattern.words, m,
            MuType.row(LocalMuFonts.current).let { if (heavy) it.copy(fontWeight = FontWeight.Medium) else it },
            if (heavy) c.ink else c.ink70, maxLines = 2,
        )
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (name == null) title(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (name != null) title(Modifier.width(name).padding(end = 12.dp))
            shown.forEachIndexed { i, st ->
                key(st) {
                    if (i > 0) Spacer(Modifier.width(16.dp))
                    Box(Modifier.weight(1f)) {
                        val cell = row.cells[st]
                        if (cell == null) Mono(if ((row.shares[st] ?: 0.0) > 0.5) "nearly all" else "rare", color = c.ink45, size = 10.sp)
                        else PatternCellView(h, row, st, cell, (row.pattern.key to st) in called, alone)
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternCellView(h: NeueHolders, row: PatternRow, stratum: Stratum, cell: PatternCell, called: Boolean, alone: Boolean) {
    val s = h.shootout
    val c = Mu.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
            Number("${cell.rate.value.roundToInt()}%", "${PatternWords.lift(cell, alone)} · ${ShootoutWords.hands(cell.trials)}", color = if (called) c.ink else c.ink70) {
                s.behind = Behind.Pattern(row.pattern.key, stratum)
            }
        }
        RangeBar(
            cell.rate, cell.rate.value - cell.lift.value,
            Modifier.weight(1f).height(10.dp),
        )
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterEnd) { Mono(ShootoutWords.percent(cell.share), color = c.ink45, size = 10.sp) }
    }
}

/** A rate on 0–100 % in ink with its situation's [base] as a tick: the 95 % range thin, the 80 % thick, the value a square. */
@Composable
private fun RangeBar(e: Estimate, base: Double, modifier: Modifier) {
    val c = Mu.colors
    Box(
        modifier.drawBehind {
            val w = size.width
            val mid = size.height / 2
            fun x(v: Double) = (v / 100).coerceIn(0.0, 1.0).toFloat() * w
            drawLine(c.ink12, Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
            drawLine(c.ink45, Offset(x(base), 0f), Offset(x(base), size.height), 1.dp.toPx())
            drawLine(c.ink45, Offset(x(e.range95.start), mid), Offset(x(e.range95.endInclusive), mid), 1.dp.toPx())
            drawLine(c.ink, Offset(x(e.range80.start), mid), Offset(x(e.range80.endInclusive), mid), 3.dp.toPx())
            val d = 6.dp.toPx()
            drawRect(c.ink, Offset(x(e.value) - d / 2, mid - d / 2), Size(d, d))
        },
    )
}

/**
 * Siding plans compared (S4.2): in each sided situation, your plan read on the hands it leaves you — the win rate after
 * siding, less the same game with game one's deck — and theirs against you, each with its range; a situation still waiting
 * for a plan says why. A press on a number lists the hands holding a card the plan moves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SidingPlans(h: NeueHolders, r: ShootoutResults, shown: List<Stratum>) {
    val s = h.shootout
    val c = Mu.colors
    val opponent = s.bench?.opponentName ?: return
    val sided = (r.strata + r.waiting.keys).filter { it.sided && (it in shown || shown.any { x -> x.goingFirst == it.goingFirst }) }.distinct()
    if (sided.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(null, "Siding plans")
        Help(
            "Each plan read on the hands it leaves: the win rate after siding, less the same game with game one's deck — yours, and $opponent's against you — " +
                "with the same answers as every number above. A press on a number lists the hands holding a card the plan moves.",
            Modifier.widthIn(max = 900.dp),
        )
        Column {
            HRule()
            sided.forEach { st ->
                key(st) {
                    val plan = r.plans.firstOrNull { it.stratum == st }
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Micro(ShootoutWords.stratum(st), color = c.ink70)
                        val waiting = r.waiting[st]
                        when {
                            waiting != null -> Small(waiting, color = c.ink45)
                            plan == null -> Small("Not read yet.", color = c.ink45)
                            else -> {
                                PlanLine(h, "Your plan", plan, plan.yours, plan.yourCards, theirs = false, plan.yourTrials, "Your plan changes no Main Deck card.")
                                PlanLine(h, "$opponent's plan", plan, plan.theirs, plan.theirCards, theirs = true, plan.theirTrials, "$opponent's plan changes no Main Deck card.")
                            }
                        }
                    }
                    HRule()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanLine(h: NeueHolders, who: String, plan: PlanResult, e: Estimate?, cards: Set<Int>, theirs: Boolean, trials: Int, none: String) {
    val s = h.shootout
    val c = Mu.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Small("$who:", color = c.ink70)
        if (e == null) {
            Small(none, color = c.ink45)
        } else {
            val clear = e.excludesZero
            Number(ShootoutWords.points(e.value) + " points", ShootoutWords.hands(trials), color = if (clear) c.ink else c.ink70) {
                s.behind = Behind.Plan(plan.stratum, cards, theirs)
            }
            Mono("95 %: ${ShootoutWords.points(e.range95.start)} to ${ShootoutWords.points(e.range95.endInclusive)}", color = c.ink45)
            Small(if (theirs) "on your win rate, against their game one's deck" else "on the hands it leaves you, against keeping game one's deck", color = c.ink45)
        }
    }
}
