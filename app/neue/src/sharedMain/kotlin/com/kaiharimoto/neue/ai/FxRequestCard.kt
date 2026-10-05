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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.effects.FxAsks
import com.kaiharimoto.mastertool.core.duel.effects.FxCost
import com.kaiharimoto.mastertool.core.duel.effects.FxFrom
import com.kaiharimoto.mastertool.core.duel.effects.FxOffer
import com.kaiharimoto.neue.effects.go
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * Ai's `fx_request` in the chat (Phase D step 2, D.md §3.1): the cards offered as their art — to write, to repair, and
 * already done (reused at no cost) — the cost before the go in words, and **Write** and **Not now**. Write is the person's
 * click that puts the cards on the asked list and starts the session that writes them; until then nothing is asked. Once
 * answered the card says how, and keeps the cards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FxRequestCard(ai: AiState, offer: FxOffer) {
    val c = Mu.colors
    val h = ai.h
    val fx = h.effects
    // Answered: Write (its request is on the list), or Not now.
    val written = fx.asked.requests.any { it.id == offer.id }
    val declined = offer.id in fx.declined
    Column(Modifier.fillMaxWidth().border(1.dp, if (written || declined) c.ink25 else c.ink).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Micro("Effects to write", color = c.ink45)
        MuText(
            offer.what.ifBlank { "Cards to write" }.replaceFirstChar { it.uppercase() },
            style = MuType.row(LocalMuFonts.current).copy(fontWeight = FontWeight.Medium),
            color = c.ink,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val phone = LocalPhone.current
            val across = if (phone) 5 else 6
            val w: Dp = ((maxWidth - 4.dp * (across - 1)) / across).coerceIn(40.dp, 64.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "To write" to offer.write,
                    "To repair" to offer.repair,
                    "Already done · reused at no cost" to offer.reused,
                ).filter { it.second.isNotEmpty() }.forEach { (label, cards) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Micro(label, color = c.ink70)
                        Mono(cards.size.toString(), color = c.ink45)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        cards.forEach { code -> ChatCard(ai, code.toString(), w, dimmed = label.startsWith("Already")) }
                    }
                }
            }
        }
        if (offer.nothing.isNotEmpty()) Help("${offer.nothing.size} Normal Monster${if (offer.nothing.size == 1) " has" else "s have"} no effect to write.")
        Small(FxCost.words(offer.estimate), color = c.ink70)
        when {
            written -> {
                val asked = offer.toWrite.mapNotNull { fx.asked.of(it) }
                val done = asked.count { it.state == FxAsks.WRITTEN }
                Help("Asked · $done of ${offer.toWrite.size} written" + (asked.sumOf { it.tokens }.takeIf { it > 0 }?.let { " · ${FxCost.tokens(it)} tokens so far" } ?: ""), color = c.ink70)
            }
            declined -> Help("Not now: nothing was asked.", color = c.ink70)
            offer.toWrite.isEmpty() -> Help("Nothing to write: every card here is already done.", color = c.ink70)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton(
                    if (offer.toWrite.size == 1) "Write it" else "Write these ${offer.toWrite.size}",
                    { h.go(offer, FxFrom.CHAT) },
                    variant = BtnVariant.PRIMARY,
                    size = BtnSize.SM,
                    enabled = !ai.running,
                    reason = "${ai.name} is still answering: Stop it first, or wait",
                )
                MuButton("Not now", { fx.declined = fx.declined + offer.id }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
        }
    }
}
