package com.kaiharimoto.guest

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeRules
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.lounge.Viewer
import com.kaiharimoto.mastertool.core.layout.FormFactor
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.duel.DuelPlayArea
import com.kaiharimoto.neue.duel.OfflineNet
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.LocalTextFocus
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MenuLayer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.TextFocus
import com.kaiharimoto.neue.lounge.LoungeClient
import com.kaiharimoto.neue.lounge.LoungeAiHears
import com.kaiharimoto.neue.lounge.LoungeLobby
import com.kaiharimoto.neue.lounge.asksAi
import com.kaiharimoto.neue.lounge.TableKeys
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive

/** Where the friend is on the page. */
private enum class Screen { LOBBY, DECKS, TABLE }

/** How far in: the passcode, a name, the cards arriving, the Lounge. */
private sealed interface Stage {
    data object Checking : Stage
    data object Passcode : Stage
    data object Name : Stage
    data class Loading(val what: String) : Stage
    data object In : Stage
    data class Away(val why: String) : Stage
}

/**
 * The Lounge's page (`docs/LOUNGE.md`): kai's passcode once, a nickname once, then the lobby, the friend's decks, and
 * the table — Neue's own ([DuelPlayArea]), held on kai's computer, sent here as this member may see it.
 */
@Composable
fun GuestApp() {
    MuTheme(ink = prefersDark) {
        val host = remember { GuestHost() }
        var stage by remember { mutableStateOf<Stage>(Stage.Checking) }
        var socket by remember { mutableStateOf<LoungeSocket?>(null) }
        val client = remember { LoungeClient(host.duel, send = { w -> socket?.send(w) }, away = { OfflineNet() }).also { host.lounge = it } }
        val scope = rememberCoroutineScope()
        // The name last tried: a refused one is shown again to change, not typed again.
        var tried by remember { mutableStateOf("") }

        // A dropped connection is tried again, quietly, for as long as a seat is held for its player.
        var reconnecting by remember { mutableStateOf(false) }
        var waited by remember { mutableStateOf(0L) }
        var tries by remember { mutableStateOf(0) }
        var turnedAway by remember { mutableStateOf(false) }

        fun connect(nick: String, again: Boolean = false) {
            if (nick.isNotBlank()) tried = nick
            if (!again) { stage = Stage.Loading("Joining the Lounge…"); turnedAway = false }
            socket?.close()
            lateinit var mine: LoungeSocket
            mine = LoungeSocket(
                onOpen = { mine.send(LoungeWire.Hi(nick = nick, token = Kept.get(TOKEN))) },
                onHear = { w ->
                    client.hear(w)
                    when (w) {
                        is LoungeWire.Welcome -> {
                            Kept.put(TOKEN, w.token); Kept.put(NICK, nick.ifBlank { Kept.get(NICK) })
                            stage = Stage.In; reconnecting = false; waited = 0; tries = 0
                            client.ask(LoungeWire.Decks)
                        }
                        is LoungeWire.Rejected -> { turnedAway = true; reconnecting = false; stage = Stage.Away(w.reason) }
                        // A name refused (taken, not a name): asked again.
                        is LoungeWire.Refused -> if (stage !is Stage.In) { stage = Stage.Name }
                        else -> Unit
                    }
                },
                onClose = close@{ why ->
                    // A socket this page replaced, or one turned away, says nothing more.
                    if (socket !== mine || turnedAway) return@close
                    if (stage is Stage.In && waited < RECONNECT_FOR_MS) {
                        reconnecting = true
                        val pause = RECONNECT_STEPS_MS.getOrElse(tries) { RECONNECT_STEPS_MS.last() }
                        tries++
                        waited += pause
                        scope.launch { delay(pause); if (reconnecting) connect("", again = true) }
                    } else {
                        reconnecting = false
                        client.lost()
                        if (stage !is Stage.Away) stage = Stage.Away(why)
                    }
                },
            )
            socket = mine
        }

        LaunchedEffect(Unit) {
            val (status, _) = runCatching { ask("/api/me") }.getOrDefault(0 to "")
            if (status != 204) { stage = Stage.Passcode; return@LaunchedEffect }
            stage = Stage.Loading("Fetching the cards…")
            loadCards(host)
            // A friend who has been here before comes back under their token, with no name to type.
            stage = if (Kept.get(TOKEN) != null) Stage.Loading("Joining the Lounge…").also { connect("") } else Stage.Name
        }

        Box(Modifier.fillMaxSize().background(Mu.colors.paper)) {
            when (val s = stage) {
                Stage.Checking -> Unit
                is Stage.Loading -> Centered { Small(s.what, color = Mu.colors.ink70) }
                Stage.Passcode -> PasscodeScreen { scope.launch { stage = Stage.Loading("Fetching the cards…"); loadCards(host); stage = Stage.Name } }
                Stage.Name -> NameScreen(client.problem, tried.ifBlank { Kept.get(NICK).orEmpty() }) { nick -> client.problem = null; connect(nick) }
                is Stage.Away -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Body(s.why)
                        MuButton("Join again", { connect("") }, variant = BtnVariant.PRIMARY)
                    }
                }
                Stage.In -> Lounge(host, client)
            }
            if (reconnecting && stage is Stage.In) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).background(Mu.colors.ink).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Small("Reconnecting to kai's computer… your seat is kept for you.", color = Mu.colors.paper)
                }
            }
        }
    }
}

@Composable
private fun Lounge(host: GuestHost, client: LoungeClient) {
    val c = Mu.colors
    var screen by remember { mutableStateOf(Screen.LOBBY) }
    val seated = client.seated
    val room = client.room
    // Into the table when a duel is on at your room; back to the lobby when you leave it.
    LaunchedEffect(seated?.room, room?.playing) { if (seated?.room == null || room?.playing != true) { if (screen == Screen.TABLE) screen = Screen.LOBBY } else if (screen == Screen.LOBBY) screen = Screen.TABLE }
    host.onTable = screen == Screen.TABLE
    LaunchedEffect(host.onTable) { holdTableKeys(host.onTable) }
    val keys = remember { TableKeys(host) }
    // Which field has the keyboard (the log's box, the Spotlight's): the duel's keys leave it the letters and Enter.
    val textFocus = remember { TextFocus() }
    val focus = remember { FocusRequester() }
    // The keys reach the table once it is drawn, not while it is still on its way.
    val tableDrawn = screen == Screen.TABLE && host.duel.shown != null
    LaunchedEffect(tableDrawn) { if (tableDrawn) runCatching { focus.requestFocus() } }
    LaunchedEffect(host.notice) { if (host.notice != null) { delay(6000); host.notice = null } }
    LaunchedEffect(host.duel.problem) { host.duel.problem?.let { host.notice = it; host.duel.problem = null } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val touch = coarsePointer
        val form = FormFactor.of(maxWidth.value, maxHeight.value, touch)
        CompositionLocalProvider(LocalTouchFirst provides touch, LocalPhone provides form.isPhone, LocalTextFocus provides textFocus) {
            Column(Modifier.fillMaxSize()) {
                val phone = form.isPhone
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // A phone keeps the row for the name and the pages; the watcher's sight gets a row of its own.
                    if (!phone) Micro("The Lounge", color = c.ink)
                    Small(client.member?.nick.orEmpty(), color = c.ink45, maxLines = 1)
                    Box(Modifier.weight(1f))
                    if (screen == Screen.TABLE && !phone) {
                        Small(room?.name.orEmpty(), color = c.ink70, maxLines = 1)
                        WatchSight(client)
                        LoungeAiHears(client)
                    }
                    Segmented(screen, listOfNotNull(Screen.LOBBY, Screen.DECKS, Screen.TABLE.takeIf { room?.playing == true }), {
                        when (it) { Screen.LOBBY -> "Lobby"; Screen.DECKS -> "Decks"; Screen.TABLE -> "Table" }
                    }, { screen = it }, small = true)
                }
                if (screen == Screen.TABLE && phone && (client.watching || client.asksAi)) {
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        WatchSight(client)
                        LoungeAiHears(client)
                    }
                }
                HRule()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.LOBBY -> LoungeLobby(client, Modifier.fillMaxSize(), onDecks = { screen = Screen.DECKS }, onTable = { screen = Screen.TABLE },
                            cardOf = { id -> host.cards.byId(CardId(id)) })
                        Screen.DECKS -> DecksPage(client, host.cards, Modifier.fillMaxSize())
                        Screen.TABLE -> {
                            val game = host.duel.shown
                            if (game == null) Centered { Small("Waiting for the table…", color = c.ink45) }
                            else Box(
                                Modifier.fillMaxSize().focusRequester(focus).focusable()
                                    .onPreviewKeyEvent { e -> keys.onKey(e, textFocused = host.duel.spotlightTyping || textFocus.any, overlayOpen = host.openMenu != null) },
                            ) {
                                DuelPlayArea(host, host.duel, game, form, form.isPhone, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            MenuLayer(host.openMenu) { host.openMenu = null }
            host.notice?.let { n ->
                Box(Modifier.align(Alignment.BottomCenter).padding(16.dp).background(c.ink).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Small(n, color = c.paper)
                }
            }
        }
    }
}

/** Watching, with the whole table sent: which hands to show is the watcher's own choice. */
private val LoungeClient.watching: Boolean
    get() = seated?.let { it.seat == null && !it.publicOnly } == true && tableNet != null

/** A player at a room where Ai is on: what they type to it goes to everyone, or to them alone. */

/** What a watcher sees of the table they are sent whole. */
@Composable
private fun WatchSight(client: LoungeClient) {
    val net = client.tableNet ?: return
    // Kept in this browser: the next duel watched starts as this one was left.
    LaunchedEffect(net) { Kept.get(SIGHT)?.let { k -> SIGHTS.firstOrNull { sightName(it) == k } }?.let { net.watchSight = it } }
    if (client.watching) Segmented(net.watchSight, SIGHTS, ::sightName, { net.watchSight = it; Kept.put(SIGHT, sightName(it)) }, small = true)
}

private val SIGHTS = listOf(setOf(0, 1), setOf(0), setOf(1), setOf(Viewer.PUBLIC))
private fun sightName(s: Set<Int>) = when (s) {
    setOf(0, 1) -> "Both hands"
    setOf(0) -> "Seat 1's"
    setOf(1) -> "Seat 2's"
    else -> "Neither"
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PasscodeScreen(onIn: () -> Unit) {
    val c = Mu.colors
    var code by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val scope = rememberCoroutineScope()
    fun enter() {
        scope.launch {
            val (status, text) = runCatching { ask("/api/enter", "POST", """{"passcode":${JsonPrimitive(code)}}""") }
                .onFailure { println("[lounge] enter: $it") }
                .getOrDefault(0 to "kai's computer did not answer")
            if (status == 204) onIn() else problem = text.ifBlank { "That is not the passcode" }
        }
    }
    Centered {
        Column(Modifier.widthIn(max = 360.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            H2("The Lounge")
            Small("kai's duel tables. Type the passcode kai gave you.", color = c.ink70)
            MuInput(code, { code = it }, Modifier.fillMaxWidth(), placeholder = "Passcode", focusRequester = focus, onSubmit = ::enter, secret = true)
            problem?.let { Small(it, color = c.ink) }
            MuButton("Come in", ::enter, variant = BtnVariant.PRIMARY, enabled = code.isNotBlank())
        }
    }
}

@Composable
private fun NameScreen(problem: String?, kept: String, onName: (String) -> Unit) {
    val c = Mu.colors
    var nick by remember { mutableStateOf(kept) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Centered {
        Column(Modifier.widthIn(max = 360.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            H2("Your name here")
            Small("What the others see at the table. Letters, digits and spaces, up to twenty.", color = c.ink70)
            MuInput(nick, { nick = it.take(20) }, Modifier.fillMaxWidth(), placeholder = "Nickname", focusRequester = focus, onSubmit = { if (nick.isNotBlank()) onName(nick) })
            problem?.let { Small(it, color = c.ink) }
            MuButton("Join", { onName(nick) }, variant = BtnVariant.PRIMARY, enabled = nick.isNotBlank())
        }
    }
}

/** The pool from kai's computer: every card, its art addressed to this page's origin. */
private suspend fun loadCards(host: GuestHost) {
    if (host.cards.size > 0) return
    val (status, text) = runCatching { ask("/cards.json") }.getOrDefault(0 to "")
    if (status != 200) return
    val cards = runCatching { GuestHost.JSON.decodeFromString(ListSerializer(Card.serializer()), text) }.getOrDefault(emptyList())
    host.usePool(cards)
}

/** How long a dropped page keeps trying (a seat is held this long, `LoungeRules.HOLD_MS`), and its pauses. */
private val RECONNECT_FOR_MS = LoungeRules.HOLD_MS
private val RECONNECT_STEPS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

private const val TOKEN = "lounge.token"
private const val NICK = "lounge.nick"
private const val SIGHT = "lounge.sight"
