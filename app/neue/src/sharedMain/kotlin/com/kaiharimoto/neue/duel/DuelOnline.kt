package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.net.PairCode
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.qr.QrMatrix
import com.kaiharimoto.neue.qr.QrPicture
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.delay

/** The words for each response-window setting. */
internal fun windowsLabel(w: String) = when (w) {
    Windows.OFF -> "Never wait for me"
    Windows.ACTIVATIONS -> "Their activations wait"
    Windows.SUMMONS -> "Activations and summons wait"
    else -> "Every move waits"
}

/**
 * A networked table's own row (1.0.77) — on a phone under the bar, elsewhere over the table's top edge while something waits (1.0.78): who it is with, and what the table waits on —
 * a Respond / Pass for this player when the other's move opened a window on them, a "waiting for them"
 * with Go on anyway when this player's did, a take-back to allow or refuse. Never a dialog: the table
 * stays live, and talk (chat, pings, Thinking) never waits.
 */
@Composable
internal fun NetBar(h: NeueHolders, duels: Duels, overlay: Boolean = false) {
    val c = Mu.colors
    val prefs = h.neue.prefs.duel
    val waiting = duels.waitingFor
    val me = duels.mySeat
    val peer = duels.peer ?: "The other player"
    // Auto-pass, when this player asked for it: the window answers itself after so many seconds.
    LaunchedEffect(waiting, prefs.autoPass) {
        if (waiting == me && prefs.autoPass > 0) {
            delay(prefs.autoPass * 1000L)
            if (duels.waitingFor == me) duels.act(DuelAction.Answer(me, respond = false), me)
        }
    }
    // Over the table (1.0.78), the row stands only while something waits; who it is with is in the bar.
    val waits = (duels.takeBackAsked != null && duels.takeBackAsked != me) || waiting != null
    if (overlay && !waits) return
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(if (waiting == me) c.ink else c.paper)
            .then(if (overlay) Modifier.border(1.dp, c.ink) else Modifier).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val ink = if (waiting == me) c.paper else c.ink
        Micro("Online", color = if (waiting == me) c.paper else c.ink45)
        Small(duels.netStatus ?: "", Modifier.weight(1f), color = ink, maxLines = 1)
        duels.netCode?.takeIf { duels.role == Duels.NetRole.HOST && duels.peer == null }?.let { Mono(it, color = ink, size = 14.sp) }
        when {
            duels.takeBackAsked != null && duels.takeBackAsked != me -> {
                Small("$peer asks to take back their last move", color = ink)
                MuButton("Allow", { duels.answerTakeBack(true) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                MuButton("No", { duels.answerTakeBack(false) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            waiting == me -> {
                Small("$peer waits: respond, or pass", color = ink)
                MuButton("Respond", { duels.act(DuelAction.Answer(me, respond = true), me) }, size = BtnSize.SM)
                MuButton("Pass", { duels.act(DuelAction.Answer(me, respond = false), me) }, size = BtnSize.SM)
            }
            waiting != null -> {
                Small("Waiting for $peer to respond", color = ink)
                MuButton("Go on anyway", { duels.forceNext = true; duels.problem = "Your next move goes ahead" }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        }
        if (!overlay) MuButton("Leave", { duels.leave() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
    }
    if (!overlay) Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
}

/**
 * Hosting and joining, in the New duel dialog: the host opens the table and shares its code (or the
 * QR); the guest types the code. Both bring their own deck — the host never sees the guest's order,
 * and the guest's app is only ever told what its seat may see.
 */
@Composable
internal fun OnlineSetup(h: NeueHolders, duels: Duels, hosting: Boolean, mine: () -> SeatSetup) {
    val c = Mu.colors
    val prefs = h.neue.prefs.duel
    var code by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel("Response windows")
        Segmented(prefs.windows, Windows.ALL, ::windowsLabel, { v -> h.neue.update { it.copy(duel = it.duel.copy(windows = v)) } }, small = true)
        Help("When the other player's move opens a window, the table holds for your Respond or Pass. Chat, pings and Thinking never wait.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("Pass by myself after", color = c.ink70)
            Segmented(prefs.autoPass, listOf(0, 5, 10, 20), { if (it == 0) "Never" else "$it s" }, { v -> h.neue.update { it.copy(duel = it.duel.copy(autoPass = v)) } }, small = true)
        }
        if (hosting) {
            val shared = duels.netCode
            if (duels.role == Duels.NetRole.HOST && shared != null) {
                FieldLabel("The table's code")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val matrix = remember(shared) { QrMatrix.of(PairCode.qr(shared)) }
                    if (matrix != null) Box(Modifier.size(120.dp).border(1.dp, c.ink)) { QrPicture(matrix, Modifier.size(120.dp)) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Mono(shared, color = c.ink, size = 22.sp)
                        Help("Read it out, or show the QR. The other player joins from New duel › Join, on the same Wi-Fi.")
                        Small(duels.netStatus ?: "", color = c.ink70)
                    }
                }
            } else {
                Help("Opens this device to the local network for one other player. Your deck sits at your seat; theirs comes with them.")
                MuButton("Open the table", { duels.host(mine()) }, variant = BtnVariant.PRIMARY)
            }
        } else {
            FieldLabel("The table's code")
            MuInput(code, { code = it }, Modifier.fillMaxWidth(), placeholder = "K7Q2-M9XA-3FBCP", mono = true, onSubmit = { duels.join(code, mine()) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MuButton("Join", { duels.join(code, mine()) }, variant = BtnVariant.PRIMARY, enabled = PairCode.decode(code) != null, reason = "Type the code the host shows")
                Small(duels.netStatus.takeIf { duels.role == Duels.NetRole.GUEST } ?: "", color = c.ink70)
            }
        }
    }
}
