package com.kaiharimoto.neue.mapper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.effects.FxFrom
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressRun
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSet
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSuite
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.effects.writeEffects
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
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu

/*
 * The Stress tab (Phase E, E.1; M.md §3, E.md §4.1): the interruptions a deck is tested against — its suite — and what the
 * boards and starters keep through each set. The other seat holds the set in hand and answers at the worst moment, knowing
 * the hand: the worst case. Every number comes from the engine playing the written effects; a card of a set with no written
 * effect is named, with Write these, and nothing is tested until the person asks for it.
 */

/** The Stress tab: the suite beside the set chosen; on a phone the suite as a row of sets over it. */
@Composable
internal fun StressTab(h: NeueHolders, phone: Boolean) {
    val m = h.mapper
    LaunchedEffect(Unit) { h.field.load() }
    val set = m.shownSet()
    if (m.suite.sets.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (phone) 16.dp else 32.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EmptyState(
                "No interruptions to test against yet.",
                "Add the hand traps you expect to face: the other seat holds them, nothing else, and answers at the worst moment for " +
                    "you. What each board and starter keeps through them is measured by the engine, from the effects that are written.",
            )
            AddSet(h, null)
        }
        return
    }
    if (phone) {
        Column(Modifier.fillMaxSize()) {
            SuiteChips(h)
            HRule()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                set?.let { SetView(h, it, phone) }
                HRule()
                AddSet(h, set)
            }
        }
        val open = m.stressStarter?.let { s -> set?.let { m.stressReport(it.key) }?.hands?.firstOrNull { it.starter == s } }
        if (open != null && set != null) MuDialog("Through ${setWords(h, set)}", { m.stressStarter = null }) { HandLine(h, set, open) }
        return
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Micro("The suite", color = Mu.colors.ink45)
            Help("What the other seat holds, a set at a time: in hand, nothing on the field, answering at the worst moment for you.")
            m.suite.sets.forEach { s -> key(s.key) { SetRow(h, s, s.key == set?.key) } }
            HRule()
            AddSet(h, set)
        }
        VRule(color = Mu.colors.ink12)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            set?.let { SetView(h, it, phone) }
        }
        val open = m.stressStarter?.let { s -> set?.let { m.stressReport(it.key) }?.hands?.firstOrNull { it.starter == s } }
        if (open != null && set != null) {
            VRule(color = Mu.colors.ink12)
            Box(Modifier.width(380.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) { HandLine(h, set, open) }
            }
        }
    }
}

/** A set's cards in words: "Ash Blossom & Joyous Spring + Effect Veiler". */
internal fun setWords(h: NeueHolders, s: StressSet): String = StressSuite.words(s.key) { name(h, it) }

/** One set of the suite, chosen by a press: its cards, whose it is, and what is not written yet. */
@Composable
private fun SetRow(h: NeueHolders, s: StressSet, chosen: Boolean) {
    val m = h.mapper
    Inverted(chosen) {
        val c = Mu.colors
        Column(
            Modifier.fillMaxWidth().background(c.paper).border(1.dp, if (chosen) c.ink else c.ink12)
                .cursorPointer(caption = "Show").muClickable { m.stressSet = s.key; m.stressStarter = null }.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                s.cards.forEach { id -> key(id) { BoardCard(h, Shown(id), 28.dp) } }
            }
            Small(setWords(h, s), color = c.ink, maxLines = 3)
            if (s.by == StressSet.AI) Help("Ai's: ${s.why.ifBlank { "proposed" }}", color = c.ink70)
            else if (s.why.isNotBlank()) Help(s.why, color = c.ink70)
        }
    }
}

/** The suite on a phone: a row of sets to choose from. */
@Composable
private fun SuiteChips(h: NeueHolders) {
    val m = h.mapper
    val chosen = m.shownSet()?.key
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        m.suite.sets.forEach { s ->
            key(s.key) {
                MuButton(
                    StressSuite.short(s.key) { name(h, it) },
                    { m.stressSet = s.key; m.stressStarter = null },
                    variant = if (s.key == chosen) BtnVariant.PRIMARY else BtnVariant.GHOST,
                    size = BtnSize.SM,
                )
            }
        }
    }
}

/** A card added by name: alone as a new set, or into the set shown; and the field's sets proposed. */
@Composable
private fun AddSet(h: NeueHolders, shown: StressSet?) {
    val m = h.mapper
    val c = Mu.colors
    var text by remember { mutableStateOf("") }
    val found = remember(text, h.builder.index) {
        if (text.trim().length < 2) null else h.builder.index.search(text.trim(), limit = 1).cards.firstOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Add a card", color = c.ink45)
        MuInput(text, { text = it }, Modifier.fillMaxWidth(), placeholder = "A card's name", onSubmit = {
            found?.let { m.putSet(StressSet(listOf(h.goldfishKit().canonical(it.id.value)), at = System.currentTimeMillis())); text = "" }
        })
        if (found != null) {
            Small(found.name, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("As a set", {
                    m.putSet(StressSet(listOf(h.goldfishKit().canonical(found.id.value)), at = System.currentTimeMillis()))
                    text = ""
                }, size = BtnSize.SM)
                if (shown != null) MuButton("With ${StressSuite.short(shown.key) { name(h, it) }}", {
                    m.removeSet(shown.key)
                    m.putSet(shown.copy(cards = (shown.cards + h.goldfishKit().canonical(found.id.value)).sorted()))
                    text = ""
                }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        } else if (text.trim().length >= 2) Help("No card by that name.", color = c.ink45)
        val profile = h.field.read?.profile
        if (profile != null) {
            val proposals = remember(profile, m.suite) {
                StressSuite.fromField(profile, { h.builder.index.byId(it) }, at = System.currentTimeMillis()).filter { m.suite.byKey(it.key) == null }
            }
            if (proposals.isNotEmpty()) {
                MuButton("Add the field's ${proposals.size}", { proposals.forEach { m.putSet(it) } }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                Help("What the field plays most, alone and the top two together: ${proposals.take(3).joinToString { setWords(h, it) }}" + (if (proposals.size > 3) "…" else "."))
            }
        } else {
            Help("Read the field on the Prep page, and its most played answers are offered here.", color = c.ink45)
        }
    }
}

/** One set: what is tested, the run, then what the starters and the boards keep through it. */
@Composable
private fun SetView(h: NeueHolders, s: StressSet, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val kit = remember(h.effects.revision, h.builder.index) { h.goldfishKit() }
    val missing = remember(s, kit) { StressRun.missing(s, kit) }
    H2("Through ${setWords(h, s)}", maxLines = 3)
    Small(
        "The other seat holds ${if (s.cards.size == 1) "it" else "them"} in hand, nothing else, and answers at the worst moment for you, " +
            "knowing your hand. ${if (m.first) "Going first." else "Going second."}",
        color = c.ink70,
    )
    if (missing.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().border(1.dp, c.ink).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("Not written yet: ${missing.joinToString { name(h, it) }}. A card with no written effect cannot answer, so nothing is tested until it is.", color = c.ink)
            MuButton(
                if (missing.size == 1) "Write it" else "Write these ${missing.size}",
                { h.writeEffects(missing, FxFrom.STRESS, "the stress suite: ${setWords(h, s)}") },
                size = BtnSize.SM, variant = BtnVariant.PRIMARY,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MuButton(
            "Stress test", { runStress(h, s) }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, arrow = true,
            enabled = !m.busy && missing.isEmpty() && m.side.library.live.isNotEmpty(),
            reason = when {
                m.busy -> "A run is going"
                missing.isNotEmpty() -> "Write its effects first"
                else -> "Map the starters first: there is nothing to test"
            },
        )
        MuButton("Remove the set", { m.removeSet(s.key) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
    }
    val report = m.stressReport(s.key)
    BoardsKept(h, s, phone)
    HRule()
    Micro("Starters", color = c.ink45)
    if (report == null || report.hands.isEmpty()) {
        Help(
            if (m.side.starters == null) "Map the starters, then stress test: each starter's hand is tested whole, for the most it can guarantee and the line that does."
            else "Stress test to see each starter's guarantee and the line that keeps it.",
            color = c.ink45,
        )
    } else {
        report.hands.forEach { hand -> key(hand.starter.joinToString(",")) { HandRow(h, s, hand, phone) } }
    }
}

/** What the library's boards keep through [s]: how many keep how much, against what they make unopposed. */
@Composable
private fun BoardsKept(h: NeueHolders, s: StressSet, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val head = BoardTraits.THROUGH + s.key
    val live = m.side.library.live
    val tested = remember(live, s.key) { live.filter { s.key in it.traits.through } }
    Micro("Boards", color = c.ink45)
    if (tested.isEmpty()) {
        Help("No board is tested through it yet: the run tests the first ${StressRun.MOST_BOARDS} of the library, in its order on screen.", color = c.ink45)
        return
    }
    val byKeep = remember(tested, s.key) { tested.groupingBy { it.traits.through.getValue(s.key) }.eachCount().toSortedMap(compareByDescending { it }) }
    Row(horizontalArrangement = Arrangement.spacedBy(if (phone) 16.dp else 24.dp)) {
        byKeep.forEach { (keeps, n) ->
            Column {
                Numeral(keeps, color = c.ink)
                Help("kept by ${n} board${if (n == 1) "" else "s"}", color = c.ink70)
            }
        }
    }
    val dropped = tested.count { it.traits.through.getValue(s.key) < it.traits.interruptions }
    Small(
        "${tested.size} of ${live.size} boards tested. $dropped lose interruptions to it when their line is answered at its worst point; " +
            "the rest keep every one.",
        color = c.ink70,
    )
    MuButton("Weigh it in the library", {
        m.weigh(head, 1.0)
        m.tab = MapperTab.LIBRARY
    }, size = BtnSize.SM, variant = BtnVariant.GHOST, arrow = true)
}

/** One starter's test: its cards, what it guarantees of what it makes, and where they answer. */
@Composable
private fun HandRow(h: NeueHolders, s: StressSet, hand: StressRun.Hand, phone: Boolean) {
    val m = h.mapper
    val chosen = m.stressStarter == hand.starter
    val r = hand.result
    Inverted(chosen) {
        val c = Mu.colors
        Row(
            Modifier.fillMaxWidth().background(c.paper).border(1.dp, if (chosen) c.ink else c.ink12)
                .cursorPointer(caption = "Read").muClickable { m.stressStarter = hand.starter }.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!phone) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { hand.starter.forEach { id -> key(id) { BoardCard(h, Shown(id), 32.dp) } } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Small(hand.starter.joinToString(" + ") { name(h, it) }, color = c.ink, maxLines = 2)
                val chokes = StressWords.chokes(r) { name(h, it) }
                Help(if (chokes.isEmpty()) "They never need to answer" else "They answer: ${chokes.joinToString("; ")}", color = c.ink70, maxLines = 2)
            }
            Mono((if (r.complete) "" else "≥") + "${r.guarantee}" + (r.unopposed?.let { " / $it" } ?: ""), color = c.ink)
        }
    }
}

/** A starter's stress line, step by step, with what it guarantees and what playing around costs. */
@Composable
internal fun HandLine(h: NeueHolders, s: StressSet, hand: StressRun.Hand) {
    val c = Mu.colors
    val r = hand.result
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        H2(hand.starter.joinToString(" + ") { name(h, it) }, maxLines = 3)
        Small(StressWords.guarantee(r, setWords(h, s)) + ".", color = c.ink)
        Help(
            if (r.complete) "Searched to the end: no line keeps more." else "Cut short after ${r.moves} engine moves: at least this, perhaps more.",
            color = c.ink70,
        )
        Micro("The line", color = c.ink45)
        StressWords.line(r) { name(h, it) }.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono("${i + 1}".padStart(2, '0'), color = c.ink45)
                Small(step, color = if (step.startsWith("They answer")) c.ink else c.ink70)
            }
        }
        StressWords.cost(r)?.let { Small(it, color = c.ink70) }
        r.end?.let { end ->
            Micro("The board it keeps", color = c.ink45)
            FieldStrip(h, end, 40.dp, most = 8)
        }
    }
}

/** [s] stressed against the library in its order on screen, and the starter table. */
internal fun runStress(h: NeueHolders, s: StressSet) {
    val m = h.mapper
    if (m.busy || !h.effects.loaded) return
    val keys = m.ordered().filterNot { it.entry.stale }.map { it.entry.key }
    m.startStress(h.goldfishDeck(), h.goldfishKit(), s, keys)
}
