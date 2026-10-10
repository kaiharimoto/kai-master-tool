package com.kaiharimoto.neue.ai

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.proposals.Proposal
import com.kaiharimoto.mastertool.core.ai.proposals.Proposals
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.prep.ledger
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ai's `deck_propose` in the chat (Phase G, G.9; the red team's A1): the change as its cards — out, then in — why, what it
 * should do with its numbers, and **Apply** and **Not now**. Apply makes the edit on the builder as one step of undo; until
 * then nothing changes. Once applied it says so, and later what the games since said (`Proposals.outcome`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProposalCard(ai: AiState, shown: Proposal) {
    val c = Mu.colors
    val h = ai.h
    val scope = rememberCoroutineScope()
    // As it stands in its deck's book: applied or put aside since it was drawn.
    val p by produceState(shown, shown.id, ai.proposalsRevision) { value = withContext(Dispatchers.IO) { ai.proposalNow(shown) } }
    var said by remember(p.id) { mutableStateOf<String?>(null) }
    val outcome by produceState<Proposals.Outcome?>(null, p.state, p.toPrint, h.prep.doc.games) {
        if (p.state == Proposal.APPLIED) value = runCatching { Proposals.outcome(p, h.ledger(p.deckId, null).all) }.getOrNull()
    }
    val open = p.state == Proposal.OPEN
    Column(Modifier.fillMaxWidth().border(1.dp, if (open) c.ink else c.ink25).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Micro("A change, proposed", color = c.ink45)
        MuText(p.title.ifBlank { Proposals.opsWords(p.ops) }, style = MuType.row(LocalMuFonts.current).copy(fontWeight = FontWeight.Medium), color = c.ink)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val across = if (LocalPhone.current) 5 else 6
            val w: Dp = ((maxWidth - 4.dp * (across - 1)) / across).coerceIn(40.dp, 64.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Out" to p.ops.filter { it.op == "remove" || it.op == "move" },
                    "In" to p.ops.filter { it.op == "add" || it.op == "set" },
                ).filter { it.second.isNotEmpty() }.forEach { (label, ops) ->
                    Micro(label, color = c.ink70)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ops.forEach { o -> ChatCard(ai, o.card, w, mark = (o.count ?: 1).takeIf { it > 1 }?.let { "×$it" }) }
                    }
                }
            }
        }
        Small(Proposals.opsWords(p.ops), color = c.ink70)
        if (p.why.isNotBlank()) Small(p.why, color = c.ink)
        p.expect.forEach { e -> Small(Proposals.expectWords(e), color = c.ink) }
        if (p.evidence.isNotEmpty()) Help("From: " + p.evidence.joinToString(" · "))
        when (p.state) {
            Proposal.APPLIED -> {
                Help(said ?: "Applied on the builder: save the deck to keep it.", color = c.ink70)
                outcome?.let { Small(Proposals.outcomeWords(it), color = c.ink) }
            }
            Proposal.DECLINED -> Help("Not now: the deck was left as it was.", color = c.ink70)
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Why an Apply did not go through (unsaved changes on another deck, a deck no longer kept).
                said?.let { Help(it, color = c.ink) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MuButton(
                    "Apply",
                    { scope.launch { said = runCatching { ai.host.applyProposal(p) }.getOrElse { it.message ?: "It could not be applied." } } },
                    variant = BtnVariant.PRIMARY,
                    size = BtnSize.SM,
                    enabled = !ai.running,
                    reason = "${ai.name} is still answering: Stop it first, or wait",
                )
                MuButton("Not now", { ai.keepProposal(p.copy(state = Proposal.DECLINED, decidedAt = System.currentTimeMillis())) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                }
            }
        }
    }
}
