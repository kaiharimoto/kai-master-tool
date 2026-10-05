package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.theme.Mu

// While a session runs, the page header gives its room to the cards (design review, 1.1.6; NEUE §3 "every pixel of
// chrome is a pixel off every card"): what it carried stands in the window's bar, as Duel's row does — the deck and the
// target, locked for the session, then Results, Trust and Stop, each with its key.

/** The session's items in the window's bar, on the desk. */
@Composable
fun RowScope.ShootoutBarItems(h: NeueHolders, narrow: Boolean) {
    val s = h.shootout
    val c = Mu.colors
    Micro(subtitle(s), Modifier.weight(1f), color = c.ink45)
    SessionActions(h, keys = true, short = narrow)
}

/** A phone's session row, under its bar: the same actions, no keys (a phone has none to press). */
@Composable
internal fun PhoneSessionRow(h: NeueHolders) {
    val s = h.shootout
    val c = Mu.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Micro(s.bench?.opponentName?.let { "Against $it" } ?: "The deck alone", Modifier.weight(1f), color = c.ink45)
        SessionActions(h, keys = false, short = true)
    }
}

@Composable
private fun SessionActions(h: NeueHolders, keys: Boolean, short: Boolean) {
    val s = h.shootout
    val c = Mu.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MuButton("Results", s::toggleResults, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
        if (keys) KeyCap(keyOf(DeskAction.SHOOTOUT_RESULTS, "R"))
    }
    if (h.ai.enabled && s.teachShown) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MuButton("Trust", s.teach::openTrust, size = BtnSize.SM, variant = BtnVariant.GHOST)
            if (keys) KeyCap(keyOf(DeskAction.SHOOTOUT_TRUST, "T"))
        }
    }
    if (keys) VRule(Modifier.height(24.dp), color = c.ink12)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MuButton(if (short) "Stop" else "Stop the session", s::stop, size = BtnSize.SM)
        if (keys) KeyCap("Esc")
    }
}
