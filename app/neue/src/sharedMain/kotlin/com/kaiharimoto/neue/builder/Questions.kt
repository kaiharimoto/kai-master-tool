package com.kaiharimoto.neue.builder

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.hand.Ask
import com.kaiharimoto.mastertool.core.hand.GoalCount
import com.kaiharimoto.mastertool.core.hand.HandGoal
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.world.Goals
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu
import kotlin.math.roundToInt

/*
 * The deck's questions, drawn at last (Phase G, G.3; mockup C). A deck has always carried its questions about the opening
 * hand (`HandGoal`, the payload's `goals`), and Neue never drew them. They stand over the groups, each going first and going
 * second, recomputed on every edit with the change since the step before; the inspector puts each one beside the copy
 * stepper at −1, now and +1; the deck's row carries the first one. Counted exactly (`GoalCount`), asks and condition together.
 */

/** A whole percentage, no sign: "57"; ">99" and "<1" at the ends. */
internal fun pctBare(p: Double): String = when {
    p.isNaN() -> "--"
    p <= 0.0 -> "0"
    p >= 1.0 -> "100"
    p < 0.01 -> "<1"
    p > 0.99 -> ">99"
    else -> (p * 100).roundToInt().toString()
}

/** "57 · 65": going first, then going second. */
internal fun pair(o: GoalCount.Odds): String = "${pctBare(o.first)} · ${pctBare(o.second)}"

/** A change in points, signed and whole-ish: "+1.2", "−0.4"; empty when nothing moved. */
private fun moved(now: Double, before: Double?): String {
    if (before == null) return ""
    val d = Math.round((now - before) * 1000) / 10.0
    return when {
        d >= 0.1 -> "+$d"
        d <= -0.1 -> "−${-d}"
        else -> ""
    }
}

/** Each stored goal's odds over the deck as it stands, null for one whose condition no longer reads. */
internal fun goalOdds(state: DeckBuilderState): List<Pair<HandGoal, GoalCount.Odds?>> =
    state.goals.goals.map { g -> g to runCatching { GoalCount.odds(g, state.deck.main, state.groups, state::nameOf) }.getOrNull() }

/** The odds before the latest edit, kept beside the ones now: what "the change since the last step" is measured from. */
private class Since {
    var deck: Any? = null
    var now: Map<String, GoalCount.Odds?> = emptyMap()
    var before: Map<String, GoalCount.Odds?> = emptyMap()
}

/** The questions over the groups: each going first and second, what the last step did to it, and a new one. */
@Composable
internal fun QuestionsStrip(state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val odds = remember(state.deck, state.groups, state.goals, state.index) { goalOdds(state) }
    val since = remember { Since() }
    if (since.deck !== state.deck) {
        since.before = since.now
        since.now = odds.associate { it.first.id to it.second }
        since.deck = state.deck
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro("Questions", Modifier.weight(1f), color = c.ink70)
            if (odds.isNotEmpty()) Micro("1st · 2nd", color = c.ink45)
        }
        if (odds.isEmpty()) {
            Small("Ask what an opening hand must hold — a starter and a hand trap, no more than one brick — and it is counted on every edit.", color = c.ink45)
        }
        odds.forEach { (goal, o) ->
            key(goal.id) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, c.ink25).cursorPointer(caption = "Edit").muClickable { state.openGoal(goal.id) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RowText(goal.name.ifBlank { "A question" }, Modifier.weight(1f), color = c.ink, maxLines = 1)
                    if (o == null) {
                        Mono("--", color = c.ink45)
                    } else {
                        val step = moved(o.first, since.before[goal.id]?.first)
                        if (step.isNotEmpty()) Mono(step, color = c.ink70)
                        Mono(pair(o), color = c.ink)
                    }
                }
            }
        }
        MuButton("New question", { state.newGoal() }, variant = BtnVariant.GHOST, size = BtnSize.SM, icon = Icons.Plus, modifier = Modifier.fillMaxWidth())
    }
}

/** The goal open in the editor: its name, an ask per group, a condition, and what it comes out at as it is written. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoalDialog(state: DeckBuilderState) {
    val goal = state.editingGoal ?: return
    val c = Mu.colors
    val stored = state.goals.byId(goal.id) != null
    val problem = remember(goal, state.deck, state.groups) { GoalCount.problem(goal, state.deck.main, state.groups, state::nameOf) }
    val odds = remember(goal, state.deck, state.groups) { if (problem == null) runCatching { GoalCount.odds(goal, state.deck.main, state.groups, state::nameOf) }.getOrNull() else null }
    MuDialog(
        if (stored) "Edit the question" else "A new question",
        { state.cancelGoal() },
        width = 560.dp,
        description = "What an opening hand must hold, counted exactly over the Main Deck going first (five cards) and going second (six).",
        footer = {
            MuButton("Save", { state.saveGoal() }, variant = BtnVariant.PRIMARY, enabled = !goal.isEmpty && problem == null, reason = problem ?: "Ask something of a group, or write a condition")
            if (stored) MuButton("Delete", { state.deleteGoal() }, variant = BtnVariant.SUBTLE, icon = Icons.Trash)
            MuButton("Cancel", { state.cancelGoal() }, variant = BtnVariant.GHOST)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MuInput(goal.name, state::setGoalName, Modifier.fillMaxWidth(), placeholder = "Name it: “Opens”, “No brick”")
            val groups = state.groups.ordered()
            if (groups.isNotEmpty()) {
                Micro("Each group", color = c.ink45)
                (groups.map { it.id to it.name } + (HandGoal.UNGROUPED to GoalCount.UNGROUPED)).forEach { (id, name) ->
                    key(id) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RowText(name, Modifier.weight(1f), color = c.ink)
                            Segmented(goal.ask(id), Ask.entries, { it.label }, { state.setGoalAsk(id, it) }, small = true)
                        }
                    }
                }
            }
            Micro("Or a condition, for overlap, or and at most", color = c.ink45)
            MuInput(goal.condition, state::setGoalCondition, Modifier.fillMaxWidth(), placeholder = Goals.EXAMPLE, mono = true)
            if (groups.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g ->
                        key(g.id) {
                            Tag(g.name, false, {
                                val clause = "\"${g.name}\">=1"
                                state.setGoalCondition(if (goal.condition.isBlank()) clause else "${goal.condition.trimEnd()} & $clause")
                            }, caption = "Add")
                        }
                    }
                }
                Help("A chip adds its group; write | for or, <=1 for at most, a card by its name, any(a, b)>=1 for either.", color = c.ink45)
            }
            when {
                problem != null -> Small(problem, color = c.ink)
                odds != null && !goal.isEmpty -> RowText("${pctFull(odds.first)} going first · ${pctFull(odds.second)} going second", color = c.ink)
                else -> Small("Ask something to see what it comes out at.", color = c.ink45)
            }
        }
    }
}

private fun pctFull(p: Double): String = "${Math.round(p * 1000) / 10.0} %"

/**
 * "In this deck" in the inspector (mockup C): each question with one copy of [card] fewer, as it is, and one more — first
 * and second — so the copy count is decided where it is changed. Main Deck cards only: the questions are of the opening hand.
 */
@Composable
internal fun InThisDeck(card: Card, state: DeckBuilderState) {
    val c = Mu.colors
    if (card.requiredSection() != DeckSection.MAIN || state.goals.isEmpty) return
    // The copy the deck holds, by whichever printing it holds it as.
    val id = state.deck.main.lastOrNull { it in card.passcodes } ?: card.id
    val rows = remember(card.id, state.deck, state.groups, state.goals, state.index) {
        state.goals.goals.mapNotNull { g -> runCatching { g to GoalCount.steps(g, state.deck.main, id, state.groups, state::nameOf) }.getOrNull() }
    }
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro("Questions", Modifier.weight(1f))
            Micro("−1", Modifier.width(56.dp), color = c.ink45)
            Micro("Now", Modifier.width(56.dp), color = c.ink45)
            Micro("+1", Modifier.width(56.dp), color = c.ink45)
        }
        rows.forEach { (goal, s) ->
            key(goal.id) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowText(goal.name.ifBlank { "A question" }, Modifier.weight(1f), color = c.ink, maxLines = 1)
                    Mono(s.less?.let(::pair) ?: "", Modifier.width(56.dp), color = c.ink70)
                    Mono(pair(s.now), Modifier.width(56.dp), color = c.ink)
                    Mono(s.more?.let(::pair) ?: "", Modifier.width(56.dp), color = c.ink70)
                }
            }
        }
        Help("Going first · going second, in a Main Deck of ${state.deck.main.size}; −1 and +1 change its size, as the stepper does.", color = c.ink45)
    }
}

/** The deck's first question, for the deck's row: "Opens 57 · 65", or null with none. */
internal fun headline(state: DeckBuilderState): String? {
    val (goal, odds) = goalOdds(state).firstOrNull { it.second != null } ?: return null
    return "${goal.name.ifBlank { "Question" }} ${pair(odds!!)}"
}
