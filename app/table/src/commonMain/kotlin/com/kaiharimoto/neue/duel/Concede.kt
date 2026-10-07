package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * Whether the person can concede the duel shown (kai, after 1.1.49: "it's not very clear how to surrender"): a two-seat
 * duel still going, at a seat of their own — not a replay, not watching Ai vs Ai or a Lounge room from the rail.
 */
val Duels.canConcede: Boolean
    get() {
        val s = shown?.state ?: return false
        return !s.solo && replay == null && !spectating && !network.watching && s.conceded == null && DuelResults.ending(s) == null
    }

/**
 * Concede, asked once more before it is done: the duel ends, the other seat winning. The same in the life-point pad, a
 * friend's page and wherever else the table offers it; typed, it is `concede` (or `surrender`).
 */
@Composable
fun ConcedeButton(duels: Duels, size: BtnSize = BtnSize.SM) {
    if (!duels.canConcede) return
    var sure by remember(duels.shown?.header?.id) { mutableStateOf(false) }
    if (!sure) {
        MuButton("Concede", { sure = true }, size = size, variant = BtnVariant.GHOST)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Small("Concede the duel?", color = Mu.colors.ink)
        MuButton("Concede", { sure = false; duels.run("concede") }, size = size, variant = BtnVariant.PRIMARY)
        MuButton("Keep playing", { sure = false }, size = size, variant = BtnVariant.GHOST)
    }
}

/** The words for a duel that has ended ([end]: the winner, or null for a draw, and how), for its bar and its tests. */
fun duelOverWords(s: DuelState, end: Pair<Int?, String>): String {
    val (winner, how) = end
    fun name(seat: Int) = DuelWords.seatName(s, seat)
    return when {
        how == DuelResult.CONCEDE && winner != null -> "${name(1 - winner)} conceded: ${name(winner)} wins the duel."
        winner != null -> "${name(winner)} wins the duel: ${name(1 - winner)}'s life points are 0."
        else -> "A draw: both players' life points are 0."
    }
}

/** Over the table's top edge once the duel has ended: who won and how, and what the host says comes next. */
@Composable
fun DuelOverBar(h: TableHost, s: DuelState, end: Pair<Int?, String>) {
    val c = Mu.colors
    Row(
        Modifier.background(c.paper).border(1.dp, c.ink).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Small(duelOverWords(s, end), color = c.ink)
        h.afterDuel?.let { Small(it, color = c.ink45) }
    }
}
