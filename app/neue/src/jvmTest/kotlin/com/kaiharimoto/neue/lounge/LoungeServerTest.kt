package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeAuth
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeCodec
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.net.Wire
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Lounge end to end over real sockets (`docs/LOUNGE.md`): the passcode, the cookie, the WebSocket's checks, and two
 * friends dueling at a room while a third watches — each sent only what they may see.
 */
class LoungeServerTest {
    private val dir = Files.createTempDirectory("lounge").toFile()
    private val kept = mutableListOf<Pair<String, DuelGame>>()
    private val host = LoungeHost(dir, catalog = { DuelCatalog.NONE }, keep = { name, g -> kept += name to g })
    private val port = ServerSocket(0).use { it.localPort }
    private val passcode = "labrynth-night"
    private val server = LoungeServer(
        host,
        passcodeHash = { LoungeAuth.hash(passcode, ByteArray(16) { it.toByte() }, iterations = 1_000) },
        pool = { emptyList() },
        original = { null },
        artCache = dir.resolve("art"),
        page = { path -> if (path == "index.html") "<!doctype html><title>The Lounge</title>".encodeToByteArray() else null },
    ).also { it.start(port, lan = false) }
    private val http = HttpClient.newHttpClient()
    private val base = "http://127.0.0.1:$port"

    @AfterTest
    fun close() {
        server.stop()
    }

    private fun enter(code: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("$base/api/enter")).POST(HttpRequest.BodyPublishers.ofString("""{"passcode":"$code"}""")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** A friend's socket: what it hears, queued. */
    private inner class Friend(cookie: String?, origin: String? = base) {
        val heard = LinkedBlockingQueue<LoungeWire>()
        val closed = LinkedBlockingQueue<Int>()
        val ws: WebSocket = http.newWebSocketBuilder().apply {
            cookie?.let { header("Cookie", it) }
            origin?.let { header("Origin", it) }
        }.buildAsync(URI("ws://127.0.0.1:$port/ws"), object : WebSocket.Listener {
            private val buffer = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    LoungeCodec.decode(buffer.toString())?.let(heard::add)
                    buffer.setLength(0)
                }
                webSocket.request(1)
                return null
            }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                closed.add(statusCode)
                return null
            }
        }).get(5, TimeUnit.SECONDS)

        fun say(w: LoungeWire) {
            ws.sendText(LoungeCodec.encode(w), true).get(5, TimeUnit.SECONDS)
        }

        /** The next message of type [T], skipping the others. */
        inline fun <reified T : LoungeWire> next(where: (T) -> Boolean = { true }): T {
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                val w = heard.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (w is T && where(w)) return w
            }
            fail("no ${T::class.simpleName} came")
        }
    }

    private fun cookie(): String {
        val r = enter(passcode)
        assertEquals(204, r.statusCode())
        return r.headers().firstValue("Set-Cookie").get().substringBefore(';')
    }

    private fun deck(card: Int) = "#main\n" + List(40) { card }.joinToString("\n") + "\n#extra\n!side\n"

    @Test
    fun thePasscodeLetsInAndGuessesWait() {
        assertEquals(403, enter("not-it").statusCode())
        val c = cookie()
        assertTrue(c.startsWith("lounge="))
        // Five wrong tries are free; the sixth makes the address wait.
        // A right one clears the count; then six wrong tries make the address wait, even for the right one.
        repeat(6) { enter("wrong-$it") }
        assertEquals(429, enter(passcode).statusCode())
    }

    @Test
    fun aSocketWithoutTheCookieOrFromAnotherSiteIsTurnedAway() {
        val noCookie = Friend(cookie = null)
        assertNotNull(noCookie.closed.poll(5, TimeUnit.SECONDS), "closed without the cookie")
        val elsewhere = Friend(cookie(), origin = "https://evil.example")
        assertNotNull(elsewhere.closed.poll(5, TimeUnit.SECONDS), "closed from another site")
    }

    @Test
    fun twoFriendsDuelAtARoomWhileAThirdWatches() {
        val ash = Friend(cookie())
        ash.say(LoungeWire.Hi(nick = "Ash"))
        val ashId = ash.next<LoungeWire.Welcome>().you
        val mira = Friend(cookie())
        mira.say(LoungeWire.Hi(nick = "Mira"))
        mira.next<LoungeWire.Welcome>()
        val kim = Friend(cookie())
        kim.say(LoungeWire.Hi(nick = "Kim"))
        kim.next<LoungeWire.Welcome>()

        ash.say(LoungeWire.Create("Locals"))
        val room = ash.next<LoungeWire.State> { it.lounge.rooms.isNotEmpty() }.lounge.rooms.single().id
        mira.say(LoungeWire.Enter(room))
        kim.say(LoungeWire.Enter(room))
        ash.say(LoungeWire.Sit(0))
        mira.say(LoungeWire.Sit(1))
        assertEquals(0, ash.next<LoungeWire.Seated> { it.seat != null }.seat)
        assertEquals(1, mira.next<LoungeWire.Seated> { it.seat != null }.seat)
        assertEquals(null, kim.next<LoungeWire.Seated> { it.room == room }.seat)

        // Each brings a deck kept on kai's computer, and gets ready with it: the duel deals.
        ash.say(LoungeWire.DeckSave(null, "Ash's", deck(1001)))
        val ashDeck = ash.next<LoungeWire.Deck>().id
        mira.say(LoungeWire.DeckSave(null, "Mira's", deck(2002)))
        val miraDeck = mira.next<LoungeWire.Deck>().id
        ash.say(LoungeWire.Ready(ashDeck))
        mira.say(LoungeWire.Ready(miraDeck))

        fun update(f: Friend, where: (Wire.Update) -> Boolean) = f.next<LoungeWire.Table> { (it.wire as? Wire.Update)?.let(where) == true }.wire as Wire.Update
        // Dealt, and no hand looked at before the opening roll is decided — its owner's either (1.0.93).
        val dealt = update(kim) { u -> u.view.seats.all { s -> s.hand.isNotEmpty() } }.view
        assertTrue(dealt.seats.all { s -> s.hand.all { it.code == null } })
        // Each throws their own dice (the values are kai's computer's), until one wins and chooses to go first.
        var winner: Int? = null
        while (winner == null) {
            ash.say(LoungeWire.Table(Wire.Intent(1, listOf(DuelAction.OpeningRoll(0)))))
            mira.say(LoungeWire.Table(Wire.Intent(1, listOf(DuelAction.OpeningRoll(1)))))
            val o = update(kim) { u -> u.view.opening?.let { it.winner != null || it.tied } == true }.view.opening!!
            winner = o.winner
        }
        (if (winner == 0) ash else mira).say(LoungeWire.Table(Wire.Intent(2, listOf(DuelAction.GoFirst(winner, first = true)))))
        fun update(f: Friend) = update(f) { u -> u.view.opening?.first != null && u.view.seats.all { s -> s.hand.isNotEmpty() } }
        val ashView = update(ash).view
        val miraView = update(mira).view
        val kimView = update(kim).view
        // Each sees their own hand and not the other's; the watcher sees both.
        assertTrue(ashView.seats[0].hand.all { it.code == 1001 } && ashView.seats[1].hand.all { it.code == null })
        assertTrue(miraView.seats[1].hand.all { it.code == 2002 } && miraView.seats[0].hand.all { it.code == null })
        assertTrue(kimView.seats[0].hand.all { it.code == 1001 } && kimView.seats[1].hand.all { it.code == 2002 })

        // Ash sends a card from the hand to the GY: everyone hears it.
        val card = ashView.seats[0].hand.first().ref
        ash.say(LoungeWire.Table(Wire.Intent(1, listOf(DuelAction.Move(card, Place.Pile(0, PileKind.GY))))))
        val seen = mira.next<LoungeWire.Table> { (it.wire as? Wire.Update)?.view?.seats?.get(0)?.gy?.isNotEmpty() == true }.wire as Wire.Update
        assertEquals(1001, seen.view.seats[0].gy.single().code)
        // A watcher's move is refused; so is Mira naming Ash's hand.
        kim.say(LoungeWire.Table(Wire.Intent(1, listOf(DuelAction.Draw(0)))))
        assertTrue("Sit down" in (kim.next<LoungeWire.Table> { it.wire is Wire.Refused }.wire as Wire.Refused).reason)
        mira.say(LoungeWire.Table(Wire.Intent(2, listOf(DuelAction.Move(ashView.seats[0].hand.last().ref, Place.Pile(1, PileKind.HAND))))))
        mira.next<LoungeWire.Table> { it.wire is Wire.Refused }

        // Ash ends it: kept as a replay on kai's computer, the seats ready to choose again.
        ash.say(LoungeWire.End)
        val after = ash.next<LoungeWire.State> { s -> s.lounge.rooms.single().let { !it.playing && it.seats.none { seat -> seat.ready } } }
        assertEquals(ashId, after.lounge.rooms.single().seats[0].member)
        val until = System.currentTimeMillis() + 5_000
        while (kept.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertEquals(1, kept.size)
    }

    @Test
    fun aFriendComesBackUnderTheirTokenWithTheirDecks() {
        val ash = Friend(cookie())
        ash.say(LoungeWire.Hi(nick = "Ash"))
        val welcome = ash.next<LoungeWire.Welcome>()
        ash.say(LoungeWire.DeckSave(null, "Kept", deck(1001)))
        ash.next<LoungeWire.Deck>()
        ash.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS)
        val again = Friend(cookie())
        again.say(LoungeWire.Hi(nick = "", token = welcome.token))
        assertEquals(welcome.you, again.next<LoungeWire.Welcome>().you)
        again.say(LoungeWire.Decks)
        assertEquals(listOf("Kept"), again.next<LoungeWire.DeckList>().decks.map { it.name })
    }

    @Test
    fun thePageIsServedAndNothingElseIs() {
        val page = http.send(HttpRequest.newBuilder(URI("$base/")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, page.statusCode())
        assertTrue("The Lounge" in page.body())
        val walk = http.send(HttpRequest.newBuilder(URI("$base/..%2F..%2Fetc%2Fpasswd")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(404, walk.statusCode())
        val cards = http.send(HttpRequest.newBuilder(URI("$base/cards.json")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(401, cards.statusCode())
    }
}
