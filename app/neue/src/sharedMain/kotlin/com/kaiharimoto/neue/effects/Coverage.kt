package com.kaiharimoto.neue.effects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckCoverage
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.kit.Small

/**
 * What the engine can play of the open deck (Phase G, G4; mockup D's coverage strip): one component for the Mapper, the
 * goldfish and the Effects app. The line "24 of 30 cards play" stands beside every share; unfolded, the deck small with the
 * inert cards dimmed, and Write these for them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoverageStrip(h: NeueHolders, coverage: DeckCoverage, what: String, open: Boolean = false) {
    val c = Mu.colors
    var shown by remember(coverage) { mutableStateOf(open) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small(
                coverage.words + if (coverage.inert.isEmpty()) "." else ": ${coverage.inert.size} ${if (coverage.inert.size == 1) "is" else "are"} played as inert, so every share is at least what it says.",
                Modifier.weight(1f, fill = false),
                color = if (coverage.inert.isEmpty()) c.ink70 else c.ink,
                maxLines = 2,
            )
            if (coverage.inert.isNotEmpty()) MicroLink(if (shown) "Fold" else "Which", { shown = !shown })
        }
        if (shown && coverage.inert.isNotEmpty()) {
            Micro("The deck, the inert cards dimmed", color = c.ink45)
            val w = if (LocalPhone.current) 30.dp else 36.dp
            val index = h.builder.index
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                coverage.cards.forEach { code ->
                    key(code) {
                        CardArt(h, code, w, dimmed = code in coverage.inert, caption = "Open") {
                            index.byId(CardId(code))?.let { h.neue.viewing = Viewing(it, null, 0) }
                        }
                    }
                }
            }
            Help(
                "Most copies first: ${coverage.inert.take(6).joinToString(", ") { code -> "${index.byId(CardId(code))?.name ?: "#$code"} ×${coverage.copies[code] ?: 1}" }}" +
                    if (coverage.inert.size > 6) ", …" else "",
                color = c.ink70,
            )
            WriteThese(h, coverage.inert, what)
        }
    }
}
