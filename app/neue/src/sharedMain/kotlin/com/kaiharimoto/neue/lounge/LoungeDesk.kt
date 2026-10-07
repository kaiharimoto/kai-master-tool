package com.kaiharimoto.neue.lounge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.theme.Mu

/**
 * kai's way into the Lounge from the Duel page (`docs/LOUNGE.md`): opening it, and the lobby friends see — rooms, seats,
 * who is here — with kai's library decks brought in. Sitting at a room whose duel is on closes it: the Duel page then
 * draws that room's table.
 */
@Composable
internal fun LoungeDialog(h: NeueHolders) {
    val lounge = h.lounge
    val close = { lounge.dialogOpen = false }
    val client = lounge.client
    if (!lounge.open || client == null) {
        MuDialog(
            "The Lounge",
            close,
            description = "Friends duel at your tables from a browser, with a passcode you give them: rooms to sit or watch in, " +
                "their decks kept on this computer. Open while Neue is.",
            footer = {
                MuButton("Close", close, variant = BtnVariant.GHOST)
                MuButton("Open the Lounge", lounge::openLounge, variant = BtnVariant.PRIMARY, enabled = lounge.hasPasscode, reason = "Set a passcode first")
            },
        ) {
            lounge.problem?.let { Small(it, color = Mu.colors.ink) }
            if (!lounge.hasPasscode) {
                Help("A passcode, the address and Cloudflare's tunnel are set in Settings › The Lounge.")
                MuButton("Settings", { close(); h.neue.go(Page.SETTINGS) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, arrow = true)
            }
        }
        return
    }
    // In a room whose duel is on: the table is the page's.
    LaunchedEffect(client.seated?.room, client.room?.playing) {
        if (client.seated?.room != null && client.room?.playing == true) close()
    }
    MuDialog(
        "The Lounge",
        close,
        width = 760.dp,
        scrolls = false,
        footer = {
            MuButton("Close the Lounge", { lounge.closeLounge(); close() }, variant = BtnVariant.GHOST)
            Box(Modifier.weight(1f))
            MuButton("Done", close, variant = BtnVariant.SECONDARY)
        },
    ) {
        val c = Mu.colors
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mono(lounge.prefs.address.ifBlank { "http://localhost:${lounge.prefs.port}" }, color = c.ink)
            if (lounge.prefs.address.isNotBlank()) MuButton("Copy", { Platform.copy(lounge.prefs.address) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            lounge.tunnel?.let { Small(it, Modifier.weight(1f), color = c.ink45, maxLines = 2) }
        }
        BringDeck(h, client)
        Results(h)
        Box(Modifier.fillMaxWidth().height(480.dp)) {
            LoungeLobby(client, Modifier.fillMaxWidth(), onTable = close)
        }
    }
}

/** One of kai's library decks, brought into the Lounge to sit down with. */
@Composable
private fun BringDeck(h: NeueHolders, client: LoungeClient) {
    var library by remember { mutableStateOf<List<StoredDeck>>(emptyList()) }
    LaunchedEffect(Unit) { library = h.deps.deckRepository.all().sortedByDescending { it.entry.updatedAtEpochMs } }
    var chosen by remember(library) { mutableStateOf(library.firstOrNull { it.entry.id == h.builder.deckId } ?: library.firstOrNull()) }
    var brought by remember { mutableStateOf<String?>(null) }
    val pick = chosen ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Small("Bring a deck", color = Mu.colors.ink70)
        MuSelect(pick, library, { it.entry.name }, { chosen = it; brought = null }, Modifier.widthIn(min = 220.dp, max = 320.dp))
        MuButton("Bring", {
            client.ask(LoungeWire.DeckSave(null, pick.entry.name, YdkCodec.write(YdkDocument(pick.entry.deck))))
            brought = "${pick.entry.name} is in your Lounge decks"
        }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
        brought?.let { Small(it, color = Mu.colors.ink45) }
    }
}

/** Who has met whom in the Lounge, and how it went: "kai 7 – 4 Mika". */
@Composable
private fun Results(h: NeueHolders) {
    var pairings by remember { mutableStateOf<List<DuelResults.Pairing>>(emptyList()) }
    LaunchedEffect(Unit) { pairings = DuelResults.lounge(h.duel.readResults()) }
    if (pairings.isEmpty()) return
    Small(
        "Results here: " + pairings.take(RESULTS_SHOWN).joinToString(" · ") { p ->
            "${p.names[0]} ${p.wins[0]} – ${p.wins[1]} ${p.names[1]}" + if (p.draws > 0) " (${p.draws} drawn)" else ""
        },
        color = Mu.colors.ink70,
        maxLines = 2,
    )
}

/** The pairings the dialog names, most played first. */
private const val RESULTS_SHOWN = 5
