package com.kaiharimoto.neue.lounge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.duel.lounge.DeckInfo
import com.kaiharimoto.mastertool.core.duel.lounge.Lounge
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeMatch
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.lounge.Member
import com.kaiharimoto.mastertool.core.duel.lounge.Room
import com.kaiharimoto.mastertool.core.duel.lounge.Seat
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.theme.Mu

/**
 * The Lounge's lobby (`docs/LOUNGE.md`; kai: "a nickname and room system that lets us swap around and play or
 * spectate as we choose"): who is here, every room with its two seats and its watchers, and — in a room — sitting,
 * standing, swapping, getting ready with a deck, and the room's duel ending. The same in a friend's browser and in
 * kai's own window; kai, the host, also keeps a room's watchers to the public table, closes it and
 * sends people away.
 *
 * [onDecks] opens where the member's decks are kept (the browser's deck page, the desk's library); [onTable] goes to the
 * room's table; [cardOf] is a card by its passcode, for siding between a match's games.
 */
@Composable
fun LoungeLobby(
    client: LoungeClient,
    modifier: Modifier = Modifier,
    onDecks: (() -> Unit)? = null,
    onTable: (() -> Unit)? = null,
    cardOf: (Int) -> Card? = { null },
) {
    val c = Mu.colors
    val lounge = client.lounge
    val me = client.member
    val room = client.room
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Micro("The Lounge", color = c.ink)
            Mono("${lounge.members.count { it.online }} here", color = c.ink45)
            Box(Modifier.weight(1f))
            if (onDecks != null) MuButton("Your decks", onDecks, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
        }
        client.problem?.let { why ->
            Row(Modifier.fillMaxWidth().border(1.dp, c.ink).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Small(why, Modifier.weight(1f), color = c.ink)
                MuButton("OK", { client.problem = null }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        }
        if (room != null && me != null) RoomPanel(client, lounge, room, me, onTable, cardOf)
        else Rooms(client, lounge, me)
        if (me != null) ChatStrip(client, if (room != null) "Said in ${room.name}" else "Said in the lobby")
        People(client, lounge, me)
    }
}

@Composable
private fun Rooms(client: LoungeClient, lounge: Lounge, me: Member?) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("Rooms")
        if (lounge.rooms.isEmpty()) Small("No rooms yet. Make one, and the others can sit down or watch.", color = c.ink45)
        lounge.rooms.forEach { r ->
            Row(
                Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Small(r.name, color = c.ink)
                        if (r.playing) Mono("DUELING", color = c.ink, size = 9.sp)
                        if (r.siding) Mono("SIDING", color = c.ink, size = 9.sp)
                        if (r.bestOf > 1) Mono("BEST OF ${r.bestOf}", color = c.ink45, size = 9.sp)
                        if (r.legalOnly) Mono("LEGAL DECKS", color = c.ink45, size = 9.sp)
                    }
                    Small(r.seats.joinToString("  v  ") { seatName(lounge, it) } + watchersLine(lounge, r), color = c.ink70, maxLines = 1)
                }
                MuButton(if (r.seats.any { it.empty }) "Go in" else "Watch", { client.ask(LoungeWire.Enter(r.id)) }, size = BtnSize.SM)
            }
        }
        if (me != null) {
            var name by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuInput(name, { name = it.take(30) }, Modifier.widthIn(max = 280.dp).weight(1f, fill = false), placeholder = "A room's name", dense = true, onSubmit = {
                    if (name.isNotBlank()) { client.ask(LoungeWire.Create(name)); name = "" }
                })
                MuButton("Make a room", { if (name.isNotBlank()) { client.ask(LoungeWire.Create(name)); name = "" } }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = name.isNotBlank())
            }
        }
    }
}

@Composable
private fun RoomPanel(client: LoungeClient, lounge: Lounge, room: Room, me: Member, onTable: (() -> Unit)?, cardOf: (Int) -> Card?) {
    val c = Mu.colors
    val mine = room.seated(me.id)
    // The seat Ai is being sat down at, while its deck is chosen.
    var aiAt by remember(room.id) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuButton("← Lobby", { client.ask(LoungeWire.Enter(null)) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            Small(room.name, Modifier.weight(1f), color = c.ink)
            if (room.playing && onTable != null) MuButton(if (mine != null) "To the table" else "Watch the duel", onTable, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
        }
        HRule()
        MatchRow(client, lounge, room, me)
        room.seats.forEachIndexed { i, seat ->
            SeatRow(client, lounge, room, i, seat, me, mine, onAi = { aiAt = if (aiAt == i) null else i })
            if (aiAt == i && seat.empty && !room.playing) AiDeckRow(client) { deck -> client.ask(LoungeWire.AiSeat(i, deck = deck)); aiAt = null }
        }
        // Asked to swap: the other player answers.
        room.swapAsk?.let { asker ->
            if (asker == me.id) Small("Asked ${lounge.member(room.seats.firstOrNull { it.member != me.id }?.member ?: "")?.nick ?: "them"} to swap seats…", color = c.ink45)
            else if (mine != null) Row(Modifier.fillMaxWidth().background(c.ink).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small("${lounge.member(asker)?.nick ?: "They"} ask to swap seats", Modifier.weight(1f), color = c.paper)
                MuButton("Swap", { client.ask(LoungeWire.Swap(yes = true)) }, size = BtnSize.SM)
                MuButton("No", { client.ask(LoungeWire.Swap(yes = false)) }, size = BtnSize.SM)
            }
        }
        val siding = client.siding?.takeIf { it.room == room.id && room.siding }
        when {
            mine != null && siding != null -> SidingStrip(client, siding, cardOf)
            room.siding -> Small(
                "Siding for game ${room.match?.game}. " + room.seats.mapIndexed { i, s ->
                    "${seatName(lounge, s)} " + if (room.match?.sided?.getOrNull(i) == true) "is ready" else "is siding"
                }.joinToString(", ") + ".",
                color = c.ink45,
            )
            mine != null && !room.playing -> ReadyRow(client, room.seats[mine], room.legalOnly)
        }
        if ((room.playing || room.siding) && (mine != null || me.host)) {
            val match = room.siding || room.match?.let { it.bestOf > 1 && !it.over } == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mine != null && room.playing) MuButton("Ask to swap seats", { client.ask(LoungeWire.Swap()) }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
                MuButton(if (match) "End the match" else "End the duel", { client.ask(LoungeWire.End) }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
            }
        }
        Small("Watching: " + lounge.members.filter { it.room == room.id && room.seated(it.id) == null }.joinToString(", ") { it.nick }.ifEmpty { "no one" }, color = c.ink45)
        if (me.host) HostRoom(client, room)
    }
}

@Composable
private fun SeatRow(client: LoungeClient, lounge: Lounge, room: Room, i: Int, seat: Seat, me: Member, mine: Int?, onAi: () -> Unit) {
    val c = Mu.colors
    Row(
        Modifier.fillMaxWidth().border(1.dp, if (i == mine) c.ink else c.ink12).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Mono("SEAT ${i + 1}", Modifier.width(56.dp), color = c.ink45)
        Column(Modifier.weight(1f)) {
            Small(seatName(lounge, seat), color = c.ink)
            val state = when {
                seat.heldUntil != null -> "Away — the seat waits for them"
                room.siding -> if (room.match?.sided?.getOrNull(i) == true) "Ready for game ${room.match?.game}" else "Siding for game ${room.match?.game}"
                seat.ai -> "Ai, on kai's connection · plays ${seat.deckName}"
                seat.ready -> "Ready with ${seat.deckName}"
                seat.member != null && !room.playing -> "Choosing a deck"
                else -> null
            }
            state?.let { Small(it, color = c.ink45) }
        }
        when {
            i == mine && !room.playing && !room.siding -> MuButton("Stand", { client.ask(LoungeWire.Stand) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            seat.empty && mine == null -> MuButton(if (room.playing) "Take the seat" else "Sit", { client.ask(LoungeWire.Sit(i)) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            seat.empty && mine != null && !room.playing -> MuButton("Move here", { client.ask(LoungeWire.Sit(i)) }, size = BtnSize.SM)
            seat.ai && !room.playing -> MuButton("Stand Ai up", { client.ask(LoungeWire.AiSeat(i, on = false)) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
        // Ai across the table, or at both seats for the room to watch: where kai allows it.
        if (seat.empty && room.ai && !room.playing) MuButton("Ai sits here", onAi, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
    }
}

/** The deck Ai plays, chosen from the member's own: Ai sits down with it, ready. */
@Composable
private fun AiDeckRow(client: LoungeClient, choose: (String) -> Unit) {
    val c = Mu.colors
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("The deck Ai plays")
        if (client.decks.isEmpty()) Small("No decks here yet: bring one in Your decks, and Ai can play it.", color = c.ink45)
        client.decks.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small(d.name, Modifier.weight(1f), color = c.ink, maxLines = 1)
                Mono("${d.main} · ${d.extra} · ${d.side}", color = c.ink45)
                MuButton("Ai plays this", { choose(d.id) }, size = BtnSize.SM, enabled = d.main > 0)
            }
        }
    }
}

/** One game or the best of three (the room's maker or kai chooses, between matches), and the match's score. */
@Composable
private fun MatchRow(client: LoungeClient, lounge: Lounge, room: Room, me: Member) {
    val c = Mu.colors
    val names = room.seats.map { seatName(lounge, it) }
    val chooses = (me.host || room.by == me.id) && !room.playing && !room.siding
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (chooses) Segmented(room.bestOf, LoungeMatch.BEST_OF, { if (it == 1) "One game" else "Best of $it" },
            { n -> client.ask(LoungeWire.RoomSet(room.id, bestOf = n)) }, small = true)
        else Mono(if (room.bestOf == 1) "ONE GAME" else "BEST OF ${room.bestOf}", color = c.ink45)
        room.match?.takeIf { it.games > 0 || it.bestOf > 1 }?.let { Small(LoungeMatch.words(it, names), color = c.ink) }
        if (room.legalOnly) Small("Only decks legal in ${client.rules.ifEmpty { "kai's rules" }}", color = c.ink45)
    }
}

/** Getting ready: one of the member's own decks, chosen — each marked legal or not under kai's rules. */
@Composable
private fun ReadyRow(client: LoungeClient, seat: Seat, legalOnly: Boolean) {
    val c = Mu.colors
    val decks: List<DeckInfo> = client.decks
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(if (seat.ready) "Ready with ${seat.deckName}. Choose again to change." else "Choose your deck")
        if (decks.isEmpty()) Small("No decks here yet: bring one in Your decks.", color = c.ink45)
        decks.forEach { d ->
            Row(
                Modifier.fillMaxWidth().border(1.dp, if (seat.deck == d.id) c.ink else c.ink12).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Small(d.name, color = c.ink, maxLines = 1)
                    d.issues.firstOrNull()?.let { Small(it, color = c.ink45, maxLines = 1) }
                }
                LegalMark(d, client.rules)
                Mono("${d.main} · ${d.extra} · ${d.side}", color = c.ink45)
                MuButton(if (seat.deck == d.id) "Ready" else "Use", { client.ask(LoungeWire.Ready(d.id)) }, size = BtnSize.SM,
                    variant = if (seat.deck == d.id) BtnVariant.PRIMARY else BtnVariant.SECONDARY, enabled = d.main > 0 && (d.legal || !legalOnly),
                    reason = if (!d.legal && legalOnly) "This room takes only legal decks" else null)
            }
        }
    }
}

/** kai's settings for a room: Ai allowed, watchers kept to the public table, closing it. */
@Composable
private fun HostRoom(client: LoungeClient, room: Room) {
    val c = Mu.colors
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Your settings for this room", color = c.ink70)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small("Ai may sit and play here, on your connection and today's budget", Modifier.weight(1f), color = c.ink)
            MuSwitch(room.ai, { on -> client.ask(LoungeWire.RoomSet(room.id, ai = on)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small("Watchers see only what is face-up", Modifier.weight(1f), color = c.ink)
            MuSwitch(room.publicOnly, { on -> client.ask(LoungeWire.RoomSet(room.id, publicOnly = on)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small("Only decks legal in ${client.rules.ifEmpty { "your builder's rules" }}", Modifier.weight(1f), color = c.ink)
            MuSwitch(room.legalOnly, { on -> client.ask(LoungeWire.RoomSet(room.id, legalOnly = on)) })
        }
        MuButton("Close the room", { client.ask(LoungeWire.Close(room.id)) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
    }
}

/** What is said where the member is — the room, or the lobby — and a line to say. */
@Composable
private fun ChatStrip(client: LoungeClient, title: String) {
    val c = Mu.colors
    var line by remember { mutableStateOf("") }
    val send = {
        val t = line.trim()
        if (t.isNotEmpty()) { client.ask(LoungeWire.Say(t)); line = "" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(title)
        val lines = client.said.takeLast(CHAT_SHOWN)
        if (lines.isEmpty()) Small("Nothing yet.", color = c.ink45)
        lines.forEach { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small(s.nick, color = if (s.from == client.me) c.ink else c.ink70, maxLines = 1)
                Small(s.text, Modifier.weight(1f), color = c.ink)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuInput(line, { line = it.take(MAX_LINE) }, Modifier.widthIn(max = 420.dp).weight(1f, fill = false), placeholder = "Say something", dense = true, onSubmit = send)
            MuButton("Say", send, size = BtnSize.SM, enabled = line.isNotBlank())
        }
    }
}

/** The lines the lobby's strip shows, and the longest line sent (the host keeps 500). */
private const val CHAT_SHOWN = 12
private const val MAX_LINE = 500

@Composable
private fun People(client: LoungeClient, lounge: Lounge, me: Member?) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Here")
        lounge.members.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).background(if (m.online) c.ink else c.ink25))
                Small(m.nick + if (m.host) " (host)" else "", color = if (m.online) c.ink else c.ink45)
                Small(lounge.room(m.room)?.let { r -> if (r.seated(m.id) != null) "at ${r.name}" else "watching ${r.name}" } ?: "in the lobby", Modifier.weight(1f), color = c.ink45)
                if (me?.host == true && !m.host) MuButton("Send away", { client.ask(LoungeWire.Kick(m.id)) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        }
    }
}

/** ✓ for a deck legal under kai's [rules], ✕ for one that is not, its problems in the tip. */
@Composable
fun LegalMark(d: DeckInfo, rules: String) {
    val c = Mu.colors
    if (rules.isEmpty()) return
    Tip(if (d.legal) "Legal in $rules" else "Not legal in $rules: " + d.issues.joinToString("; ")) {
        Mono(if (d.legal) "✓" else "✕", color = if (d.legal) c.ink45 else c.ink)
    }
}

private fun seatName(lounge: Lounge, s: Seat): String = when {
    s.ai -> "Ai"
    s.member != null -> s.member?.let(lounge::member)?.nick ?: "Someone"
    else -> "Empty"
}

private fun watchersLine(lounge: Lounge, r: Room): String {
    val n = lounge.members.count { it.room == r.id && r.seated(it.id) == null }
    return if (n == 0) "" else " · $n watching"
}
