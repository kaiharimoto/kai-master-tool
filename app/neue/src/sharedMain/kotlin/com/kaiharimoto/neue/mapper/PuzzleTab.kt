package com.kaiharimoto.neue.mapper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.BoardGoals
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.MapPuzzle
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.MapPuzzles
import com.kaiharimoto.mastertool.core.duel.mapper.puzzle.PuzzleSolve
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu

/*
 * The Puzzles tab (Phase M, M4; M.md §5): positions saved from the Duel page with a goal, solved by the turn search against
 * every card of theirs the solver could see, and opened back on the Duel page — the position, or the line that solves it.
 * Paper and ink: the line is words, the cards are on the Duel page.
 */

/** The Puzzles tab: the puzzles beside the one chosen; on a phone the list over it. */
@Composable
internal fun PuzzlesTab(h: NeueHolders, phone: Boolean) {
    val z = h.puzzles
    LaunchedEffect(Unit) { z.load() }
    val chosen = z.selected?.let(z::byId) ?: z.puzzles.firstOrNull()
    if (z.loaded && z.puzzles.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (phone) 16.dp else 32.dp)) {
            EmptyState(
                "No puzzles yet.",
                "Save a position from the Duel page — the duel in play, a replay at its step, a DuelingBook replay — with Table ▾ › " +
                    "Save as puzzle, and a goal: lethal, their field clear, no answer left, or a board to end on. The search solves it " +
                    "against every card of theirs you could see.",
                inset = 0.dp,
            ) {
                MuButton("Go to the Duel page", { h.neue.go(Page.DUEL) }, arrow = true)
            }
            if (z.unreadable > 0) Help("${z.unreadable} puzzle file${if (z.unreadable == 1) " was" else "s were"} written by a newer version: update the app to read them.")
        }
        return
    }
    if (phone) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            z.puzzles.forEach { p -> key(p.id) { PuzzleRow(h, p, p.id == chosen?.id) } }
            HRule()
            chosen?.let { PuzzleView(h, it) }
        }
        return
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Micro("Your puzzles", color = Mu.colors.ink45)
            Help("Positions saved from the Duel page, each with its goal. Table ▾ › Save as puzzle adds one.")
            z.puzzles.forEach { p -> key(p.id) { PuzzleRow(h, p, p.id == chosen?.id) } }
            if (z.unreadable > 0) Help("${z.unreadable} written by a newer version, left as they are.")
        }
        VRule(color = Mu.colors.ink12)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            chosen?.let { PuzzleView(h, it) }
        }
    }
}

/** One puzzle, chosen by a press: its title, its goal, and where its last solve stands. */
@Composable
private fun PuzzleRow(h: NeueHolders, p: MapPuzzle, chosen: Boolean) {
    val z = h.puzzles
    Inverted(chosen) {
        val c = Mu.colors
        Column(
            Modifier.fillMaxWidth().background(c.paper).border(1.dp, if (chosen) c.ink else c.ink12)
                .cursorPointer(caption = "Show").muClickable { z.selected = p.id }.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Small(p.title, color = c.ink, maxLines = 2)
            Help(p.goal.words(z::name), color = c.ink70, maxLines = 2)
            Mono(verdictTag(h, p))
        }
    }
}

/** Where a puzzle's solve stands, in a few words. */
private fun verdictTag(h: NeueHolders, p: MapPuzzle): String {
    val z = h.puzzles
    if (z.solving == p.id) return "Solving…"
    val a = p.answer ?: return "Not solved yet"
    val v = PuzzleSolve.Verdict.entries.firstOrNull { it.name == a.verdict }?.words ?: "Not solved yet"
    return if (z.stale(p)) "$v · effects changed" else v
}

/** The puzzle chosen: its position and goal, its answer, and the ways to the Duel page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PuzzleView(h: NeueHolders, p: MapPuzzle) {
    val z = h.puzzles
    val c = Mu.colors
    var renaming by remember(p.id) { mutableStateOf(false) }
    var draft by remember(p.id) { mutableStateOf(p.title) }
    if (renaming) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MuInput(draft, { draft = it }, Modifier.widthIn(max = 360.dp), onSubmit = { z.rename(p.id, draft); renaming = false })
            MuButton("Keep", { z.rename(p.id, draft); renaming = false }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            MuButton("Cancel", { renaming = false; draft = p.title }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
    } else {
        H2(p.title, maxLines = 2)
    }
    Small("Goal: ${p.goal.words(z::name)}, by the end of the turn.", color = c.ink)
    // Help, not Mono: the position wraps on a phone rather than losing its end.
    Help(MapPuzzles.position(p), color = c.ink70)
    Help(
        listOfNotNull(
            p.from.ifBlank { null },
            if (p.knows == DuelBrief.FULL) "both seats' cards known" else "their hidden cards unknown",
        ).joinToString(" · "),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (z.solving == p.id) {
            MuButton("Stop", { z.stop() }, size = BtnSize.SM)
            Breathe()
        } else {
            val why = PuzzleSolve.refusal(p)
            MuButton(if (p.answer == null) "Solve" else "Solve again", { z.solve(p.id) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = why == null && z.solving == null, reason = why ?: z.solving?.let { "Another puzzle is being solved." })
        }
        MuButton("Open the position", { z.openPosition(p.id) }, size = BtnSize.SM, arrow = true)
        val line = z.solvedNow(p.id)?.takeIf { it.met }
        if (line != null) MuButton("Play the line", { z.playLine(p.id) }, size = BtnSize.SM, arrow = true)
    }
    z.problem?.takeIf { z.selected == null || z.selected == p.id }?.let { Small(it, color = c.ink) }
    HRule()
    val a = p.answer
    if (a == null) {
        Help("Not solved yet. Solve searches every line the written effects allow from here, with their cards answering at the worst moment.")
    } else {
        Micro("The answer", color = c.ink45)
        Small(PuzzleSolve.words(a), color = c.ink)
        if (z.stale(p)) Help("The written effects of these cards have changed since: solve again for an answer that holds now.", color = c.ink)
        a.line.forEachIndexed { i, w ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Numeral(i + 1, Modifier.width(20.dp))
                Small(w, color = if (w.startsWith("They: ")) c.ink70 else c.ink)
            }
        }
        if (a.line.isNotEmpty() && z.solvedNow(p.id) == null) Help("Solve again to play the line on the Duel page.")
        Mono((if (a.complete) "Every line searched" else "Cut short") + " · ${a.moves} moves · ${a.positions} tables")
    }
    Help(PuzzleSolve.assumes(p))
    Row(Modifier.padding(top = 8.dp)) {
        MuButton("Rename", { renaming = true; draft = p.title }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        MuButton("Delete", { z.delete(p.id) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
    }
}

/** Save as puzzle (the Duel page's Table ▾): the table on screen kept with a goal and a name. */
@Composable
internal fun SavePuzzleDialog(h: NeueHolders) {
    val z = h.puzzles
    val c = Mu.colors
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(BoardGoals.kinds.first()) }
    var count by remember { mutableIntStateOf(2) }
    val why = z.saveRefusal()
    val goal = BoardGoals.of(kind.key, count)
    MuDialog(
        "Save as puzzle",
        { z.saving = false },
        width = 520.dp,
        description = "The table as it stands, kept with a goal for the seat whose turn it is. Solve it on the Mapper's Puzzles tab.",
        footer = {
            MuButton("Cancel", { z.saving = false }, variant = BtnVariant.GHOST)
            MuButton("Save", { goal?.let { z.save(it, title) } }, variant = BtnVariant.PRIMARY, enabled = why == null && goal != null, reason = why)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (why != null) Small(why, color = c.ink)
            Micro("Its name", color = c.ink45)
            MuInput(title, { title = it }, Modifier.fillMaxWidth(), placeholder = h.duel.shown?.state?.turn?.let { "Turn $it" }.orEmpty())
            Micro("The goal, by the end of the turn", color = c.ink45)
            BoardGoals.kinds.forEach { k ->
                key(k.key) {
                    Inverted(k.key == kind.key) {
                        val cc = Mu.colors
                        Row(
                            Modifier.fillMaxWidth().background(cc.paper).border(1.dp, if (k.key == kind.key) cc.ink else cc.ink12)
                                .cursorPointer(caption = "Choose").muClickable { kind = k }.padding(horizontal = 10.dp, vertical = 8.dp),
                        ) { Small(k.words.replace(" N ", " $count "), color = cc.ink) }
                    }
                }
            }
            if (kind.counted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Small("At least", color = c.ink)
                    Segmented(count, (1..5).toList(), { it.toString() }, { count = it }, small = true)
                }
            }
            Help("What the search will know of their cards is what you see of them now.")
        }
    }
}
