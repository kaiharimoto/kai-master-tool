package com.kaiharimoto.neue.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeAblation
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * Test scores' Shootout section (Phase S, S4.4; `docs/phases/S.md` §6¾ "Is it better?"): what each thing Ai is handed with a
 * hand is worth, on the deck and target Shootout is on — your newest blind answers held out, Ai judging each with everything
 * and without each piece, on the connection in use. Asks what it will spend before it runs; its answers are never trials.
 */
@Composable
internal fun ShootoutAblationSection(ai: AiState, n: Int) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val s = ai.h.shootout
    val a = s.ablation
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HRule(strong = true)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Numeral(n)
            MuText("What helps ${ai.name} judge your Shootout hands", Modifier.weight(1f), style = MuType.h2(f), color = c.ink, maxLines = 2)
        }
        val bench = s.bench
        if (bench == null) {
            Small("Open Shootout on a saved deck: this is measured on its hands.", color = c.ink70)
        } else {
            Small(
                "${s.deckName} · ${bench.opponentName?.let { "against $it" } ?: "the deck alone"}: your ${JudgeAblation.HANDS} newest blind answers held out, " +
                    "each judged by ${ai.name} with everything it is handed and without each piece, on the connection in use.",
                color = c.ink70,
            )
            val scores = a.latest()
            if (scores.isEmpty()) {
                Small("Not run yet.", color = c.ink45)
            } else {
                a.log?.runs?.lastOrNull()?.let { run ->
                    Small("${run.model?.ifBlank { null } ?: run.connection ?: "A connection"}, ${run.hands.size} hands" + (if (run.failed > 0) ", ${run.failed} answers failed" else ""), color = c.ink45)
                }
                scores.forEach { score ->
                    key(score.variant) { Small(JudgeAblation.words(score), color = if (score.variant == JudgeAblation.Variant.FULL) c.ink else c.ink70) }
                }
            }
            val done = a.done
            when {
                done != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Breathe()
                    Small("${done.first} of ${done.second} answers", color = c.ink)
                    MuButton("Stop", { a.stop() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                }
                asking -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Small("${JudgeAblation.HANDS * JudgeAblation.Variant.entries.size} judged hands, about ${a.tokens() / 1000}k tokens.", color = c.ink)
                    MuButton("Run it", { asking = false; a.run() }, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
                    MuButton("Cancel", { asking = false }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                }
                else -> {
                    val why = a.problem()
                    MuButton("Run", { asking = true }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, enabled = why == null, reason = why)
                }
            }
            a.trouble?.let { Small(it, color = c.ink45) }
            Help("Only when you press Run. Its answers are kept apart from your trials: they move no rating and no trust. A piece is worth keeping where leaving it out loses hands, and the p says how likely that split is by chance.")
        }
    }
}
