package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.WriteUpEntry
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriors
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.readProblem
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuCheckbox
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/*
 * Ai reads the deck first, and the results go into the guide (Phase S, S4.3; `docs/phases/S.md` §6 1, §6 4).
 */

/**
 * Ai's reading of the deck, on the setup (S4.3): asked for once, then every line the person's — each role's lean stepped
 * down or up or turned off, each pair turned off — and put away whole. Where the ratings start, and only that.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiReadingBlock(h: NeueHolders) {
    val s = h.shootout
    val c = Mu.colors
    val r = s.deckReading
    val bench = s.bench ?: return
    val p = r.priors
    if (!h.ai.enabled && p == null) return
    val ai = h.ai.name
    Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("$ai's reading of the deck", color = c.ink45)
        if (p == null || p.isEmpty) {
            Small(
                "Before the first hand, $ai can read the cards and say which of the deck's ${if (bench.roleNames == listOf(Bench.ALL_CARDS)) "cards' roles" else "groups"} likely help a hand, and which cards likely work together. " +
                    "It only sets where the ratings start: your answers move them from there.",
                color = c.ink70,
            )
            ReadButton(h, "Ask $ai to read the deck", BtnVariant.SECONDARY)
        } else {
            if (p.summary.isNotBlank()) Body(p.summary, color = c.ink)
            if (bench.aiRoles) {
                val counts = p.roles.groupBy { it.role }.map { (role, cs) -> "$role ${cs.size}" }
                Small("The deck has no groups, so $ai sorted its cards: ${counts.joinToString(", ")}.", color = c.ink70)
            }
            Column {
                HRule()
                bench.roleNames.forEach { role ->
                    key(role) {
                        val lean = p.leans.firstOrNull { it.role.equals(role, ignoreCase = true) }
                        LeanRow(h, role, lean?.step ?: 0, lean?.why.orEmpty(), lean?.on ?: true, has = lean != null)
                        HRule()
                    }
                }
                p.pairs.forEach { pair ->
                    key(pair.a, pair.b) {
                        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RowText("${s.card(pair.a)?.name ?: pair.a} + ${s.card(pair.b)?.name ?: pair.b}", color = if (pair.on) c.ink else c.ink45, maxLines = 2)
                            if (pair.why.isNotBlank()) Small(pair.why, color = c.ink45)
                            MuButton(if (pair.on) "Turn off" else "Turn on", { r.togglePair(pair.a, pair.b) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                        }
                        HRule()
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ReadButton(h, "Read it again", BtnVariant.GHOST)
                MuButton("Put it away", { r.clear() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            Help("Where the ratings start, and only that: your answers move every number from there, and a lean that is wrong is answered away. A pair is fitted first; it still has to earn its place.")
        }
    }
}

@Composable
private fun ReadButton(h: NeueHolders, label: String, variant: BtnVariant) {
    val r = h.shootout.deckReading
    if (r.asking) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Breathe()
            Small("${h.ai.name} is reading the deck")
        }
        return
    }
    val why = h.ai.readProblem()
    MuButton(label, { r.ask() }, variant = variant, size = BtnSize.SM, enabled = why == null, reason = why)
}

/** One role's lean: the step down and up in words, why, and on or off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeanRow(h: NeueHolders, role: String, step: Int, why: String, on: Boolean, has: Boolean) {
    val c = Mu.colors
    val r = h.shootout.deckReading
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(160.dp)) { RowText(role, color = if (on) c.ink else c.ink45, maxLines = 1) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            MuButton("−", { r.setStep(role, step - 1) }, variant = BtnVariant.GHOST, size = BtnSize.SM, enabled = step > ShootoutPriors.STEPS.first, reason = "The least it can lean")
            Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) { Small(if (on) ShootoutPriors.stepWords(step) else "off", color = if (on) c.ink else c.ink45) }
            MuButton("+", { r.setStep(role, step + 1) }, variant = BtnVariant.GHOST, size = BtnSize.SM, enabled = step < ShootoutPriors.STEPS.last, reason = "The most it can lean")
        }
        if (why.isNotBlank()) Small(why, Modifier.widthIn(max = 320.dp), color = c.ink45)
        if (has) MuButton(if (on) "Turn off" else "Turn on", { r.toggleLean(role) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
    }
}

/**
 * The write-up offered (S4.3): every line the results would write into the deck's guide, each ticked to keep, and where its
 * numbers come from. Written from the computed numbers only, each kept with its proof; the lines this target wrote before
 * are replaced, never piled up.
 */
@Composable
internal fun WriteUpDialog(h: NeueHolders, lines: List<WriteUpEntry>) {
    val s = h.shootout
    val c = Mu.colors
    var chosen by remember(lines) { mutableStateOf(lines.indices.toSet()) }
    MuDialog(
        "Into the guide of ${s.deckName}", { s.deckReading.writeUp = null }, width = 760.dp,
        description = "What the hands have called, each line written from the results with its proof kept beside the guide. A change to the deck marks them stale; writing again replaces these lines.",
        footer = {
            MuButton("Cancel", { s.deckReading.writeUp = null }, variant = BtnVariant.GHOST)
            MuButton(
                "Write ${chosen.size} line${if (chosen.size == 1) "" else "s"}", { s.deckReading.keepWriteUp(lines.filterIndexed { i, _ -> i in chosen }) },
                variant = BtnVariant.PRIMARY, enabled = chosen.isNotEmpty(), reason = "Tick a line to write",
            )
        },
    ) {
        lines.forEachIndexed { i, line ->
            key(i) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    MuCheckbox(i in chosen, { on -> chosen = if (on) chosen + i else chosen - i })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Small(line.text, color = c.ink)
                        Micro("Proof: Shootout · ${line.proof.excerpt}", color = c.ink45)
                    }
                }
                HRule()
            }
        }
    }
}

/** The results' way into the guide: offered once there is a saved deck and results to write. */
@Composable
internal fun WriteUpButton(h: NeueHolders) {
    val r = h.shootout.deckReading
    val why = r.writeUpProblem()
    MuButton("Write into the guide", { r.offerWriteUp() }, variant = BtnVariant.GHOST, size = BtnSize.SM, enabled = why == null, reason = why)
}
