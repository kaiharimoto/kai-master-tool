package com.kaiharimoto.neue.lounge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeAuth
import com.kaiharimoto.mastertool.core.duel.lounge.LoungePrefs
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeProbe
import com.kaiharimoto.neue.duel.DuelHosting
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.theme.Mu

/**
 * Settings › The Lounge (`docs/LOUNGE.md`): friends duel at this computer's tables from a browser. Opening it, the
 * passcode they type, the address they open, Cloudflare's tunnel that carries that address here, and the local network.
 * Rooms and who is in them are on the Duel page, where kai plays.
 */
@Composable
fun LoungeSection(lounge: LoungeCenter, row: @Composable (label: String, help: String, onToggle: (() -> Unit)?, control: @Composable () -> Unit) -> Unit) {
    val c = Mu.colors
    val prefs = lounge.prefs
    val toggle = { if (lounge.open) lounge.closeLounge() else lounge.openLounge() }

    row(
        "The Lounge",
        "Friends duel at your tables from a browser: rooms to sit or watch in, their decks kept here, the table drawn as Neue " +
            "draws it. Everything runs on this computer, so it is open only while Neue is.",
        toggle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuSwitch(lounge.open, { toggle() })
            Small(
                lounge.problem ?: if (lounge.open) "Open on this computer at http://localhost:${prefs.port}" else "Closed",
                color = if (lounge.problem != null) c.ink else c.ink70,
            )
        }
    }

    var passcode by remember { mutableStateOf("") }
    var passcodeSaid by remember { mutableStateOf<String?>(null) }
    row(
        "Passcode",
        "What friends type to come in: at least ${LoungeAuth.MIN_LENGTH} characters, shared only with them. Only a hash of it " +
            "is kept, with the app's secrets. A wrong guess waits longer each time.",
        null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val set = {
                passcodeSaid = lounge.setPasscode(passcode) ?: "Saved. It works the next time someone comes in."
                if (passcode.length >= LoungeAuth.MIN_LENGTH) passcode = ""
            }
            MuInput(passcode, { passcode = it.take(128); passcodeSaid = null }, Modifier.widthIn(min = 160.dp, max = 240.dp),
                placeholder = if (lounge.hasPasscode) "Set · type to change" else "None yet", secret = true, onSubmit = set)
            MuButton("Save", set, variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = passcode.isNotEmpty())
            passcodeSaid?.let { Small(it, color = c.ink70) }
        }
    }

    var address by remember(prefs.address) { mutableStateOf(prefs.address) }
    row(
        "Address",
        "What friends open, such as https://duel.labrynth.info: a name on your domain that Cloudflare's tunnel carries to this " +
            "computer. docs/LOUNGE.md walks through setting it up.",
        null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuInput(address, { address = it.take(200) }, Modifier.widthIn(min = 200.dp, max = 320.dp), placeholder = "https://duel.labrynth.info", mono = true, dense = true,
                onSubmit = { lounge.update { it.copy(address = address.trim()) } }, onFocusChange = { f -> if (!f) lounge.update { it.copy(address = address.trim()) } })
            if (prefs.address.isNotBlank()) MuButton("Copy", { Platform.copy(prefs.address) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
    }

    val check = lounge.addressCheck
    row(
        "Can friends reach it?",
        when {
            lounge.checking -> "Knocking on ${prefs.address.trim()} from outside…"
            check != null -> check.words
            else -> "Asks the address from this computer, out through the internet and back in through the tunnel, and says " +
                "what is wrong if it does not come back here. Open the Lounge first."
        },
        null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuButton(if (lounge.checking) "Testing…" else "Test the address", lounge::testAddress, variant = BtnVariant.SUBTLE, size = BtnSize.SM,
                enabled = !lounge.checking && prefs.address.isNotBlank())
            check?.let { Mono(when (it.verdict) { LoungeProbe.Verdict.OK -> "✓ Reached"; LoungeProbe.Verdict.WARN -> "? Unsure"; LoungeProbe.Verdict.FAIL -> "✕ Not reached" }, color = c.ink) }
        }
    }

    val line = LoungeProbe.installLine(Platform.os)
    if (lounge.available && line != null && !lounge.cloudflaredFound) {
        row(
            "Install cloudflared",
            "The tunnel runs Cloudflare's cloudflared, which is not on this computer yet. Paste this in a terminal, then open " +
                "the Lounge again: $line",
            null,
        ) {
            MuButton("Copy", { Platform.copy(line) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
        }
    }

    var token by remember { mutableStateOf("") }
    row(
        "Cloudflare tunnel",
        "Started with the Lounge, so the address reaches this computer without opening your router. Paste the tunnel's token " +
            "(or its whole install line); cloudflared must be installed. ${lounge.tunnel ?: if (lounge.hasTunnelToken) "A token is kept." else "No token yet."}",
        { lounge.update { it.copy(tunnel = !it.tunnel) } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuSwitch(prefs.tunnel, { on -> lounge.update { it.copy(tunnel = on) } })
            MuInput(token, { token = it.take(4000) }, Modifier.widthIn(min = 160.dp, max = 240.dp),
                placeholder = if (lounge.hasTunnelToken) "Kept · paste to replace" else "Tunnel token", secret = true, dense = true,
                onSubmit = { lounge.setTunnelToken(token); token = "" })
            MuButton("Save", { lounge.setTunnelToken(token); token = "" }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = token.isNotBlank())
            if (lounge.open && prefs.tunnel) MuButton("Start again", lounge::startTunnel, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
    }

    val lan = remember(prefs.lan) { DuelHosting.lanAddress() }
    row(
        "Local network",
        "Friends in the same house come in at ${lan?.let { "http://$it:${prefs.port}" } ?: "this computer's address"} without the " +
            "tunnel. Off, the door answers this computer alone and the tunnel. Takes effect when the Lounge next opens.",
        { lounge.update { it.copy(lan = !it.lan) } },
    ) {
        MuSwitch(prefs.lan, { on -> lounge.update { it.copy(lan = on) } })
    }

    row(
        "Ai at the tables",
        "Friends can sit Ai across from them, or at both seats to watch, in rooms where you allow it — on your Ai connection " +
            "(an API one), so on your bill. This is the most it reads and writes in a day, all rooms together; past it Ai stops " +
            "and says so. Off keeps it out.",
        null,
    ) {
        Segmented(prefs.aiDailyTokens, LoungePrefs.AI_BUDGETS.let { if (prefs.aiDailyTokens in it) it else (it + prefs.aiDailyTokens).sorted() }, ::tokensLabel,
            { n -> lounge.update { it.copy(aiDailyTokens = n) } }, small = true)
    }

    var port by remember(prefs.port) { mutableStateOf(prefs.port.toString()) }
    var nick by remember(prefs.nick) { mutableStateOf(prefs.nick) }
    row(
        "Port and name",
        "The port the tunnel points at (http://localhost:${prefs.port}), and what friends see you called. Both take effect when " +
            "the Lounge next opens.",
        null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val savePort = {
                val n = port.toIntOrNull()?.takeIf { it in 1024..65535 }
                if (n != null) lounge.update { it.copy(port = n) } else port = prefs.port.toString()
            }
            MuInput(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.widthIn(min = 72.dp, max = 96.dp), placeholder = LoungePrefs.DEFAULT_PORT.toString(),
                mono = true, dense = true, onSubmit = savePort, onFocusChange = { f -> if (!f) savePort() })
            MuInput(nick, { nick = it.take(20) }, Modifier.widthIn(min = 120.dp, max = 180.dp), placeholder = "kai", dense = true,
                onSubmit = { lounge.update { it.copy(nick = nick.trim().ifBlank { "kai" }) } },
                onFocusChange = { f -> if (!f) lounge.update { it.copy(nick = nick.trim().ifBlank { "kai" }) } })
            if (lounge.open) Mono("${lounge.client?.lounge?.members?.count { it.online } ?: 0} here", color = c.ink45)
        }
    }
}

/** A day's tokens as Settings writes them: Off, 500k, 2M. */
private fun tokensLabel(n: Long): String = when {
    n <= 0 -> "Off"
    n >= 1_000_000 -> "${n / 1_000_000}${if (n % 1_000_000 != 0L) "." + (n % 1_000_000) / 100_000 else ""}M"
    else -> "${n / 1000}k"
}
