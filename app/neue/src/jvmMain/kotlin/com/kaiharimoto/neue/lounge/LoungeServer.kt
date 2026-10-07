package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.duel.lounge.LoungeAuth
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeCodec
import com.kaiharimoto.mastertool.core.duel.lounge.Lockout
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.sync.Sha256
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

/**
 * The Lounge's door on kai's computer (`docs/LOUNGE.md`): a small web server on 127.0.0.1 — Cloudflare's tunnel
 * carries `duel.labrynth.info` to it — that hands a friend's browser the Lounge's page, lets them in with kai's
 * passcode, and carries the Lounge over a WebSocket to [host]. Card art and the card pool come from here too, so the
 * page asks nothing of anyone but kai's computer.
 *
 * What it checks: the passcode (PBKDF2's hash in [passcodeHash]; wrong tries wait longer each time, per address), a
 * session cookie on everything after, the WebSocket's Origin against the address it was opened on, and the size and
 * pace of what a socket sends.
 */
class LoungeServer(
    private val host: LoungeHost,
    /** The passcode's hash (`LoungeAuth.hash`), read each time: kai may change it while the Lounge is open. */
    private val passcodeHash: () -> String?,
    /** The pool for the page: every card, as the builder knows them. */
    private val pool: () -> List<Card>,
    /** An original artwork on disk, when the art library has it. */
    private val original: (Int) -> File?,
    /** Where small renders fetched for guests are kept. */
    private val artCache: File,
    /** The page's files (the browser app), by path; null when there is no such file. */
    private val page: (String) -> ByteArray?,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val random = SecureRandom()
    /** Session cookies given out, by their hash. */
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val lockouts = ConcurrentHashMap<String, Lockout>()
    @Volatile private var poolBody: Pair<Int, ByteArray>? = null

    val running: Boolean get() = server != null

    /** Opens the door on [port]; on every address of this computer too when [lan], else only to the tunnel. */
    fun start(port: Int, lan: Boolean) {
        stop()
        server = embeddedServer(CIO, port = port, host = if (lan) "0.0.0.0" else "127.0.0.1") {
            install(WebSockets) {
                maxFrameSize = LoungeCodec.MAX_IN.toLong()
                pingPeriodMillis = 20_000
                timeoutMillis = 40_000
            }
            routing {
                post("/api/enter") { enter(call) }
                get("/api/me") { call.respond(if (signedIn(call)) HttpStatusCode.NoContent else HttpStatusCode.Unauthorized) }
                get("/cards.json") {
                    if (!signedIn(call)) return@get call.respond(HttpStatusCode.Unauthorized)
                    val body = withContext(Dispatchers.Default) { cardsBody() }
                    call.response.header(HttpHeaders.ContentEncoding, "gzip")
                    call.response.header(HttpHeaders.CacheControl, "private, no-cache")
                    call.respondBytes(body, ContentType.Application.Json)
                }
                get("/art/{size}/{name}") { art(call) }
                webSocket("/ws") {
                    if (!signedIn(call) || !sameOrigin(call)) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Enter the passcode first")); return@webSocket }
                    val out = Channel<String>(Channel.BUFFERED)
                    val session = withContext(Dispatchers.Main) {
                        host.open(out = { w -> out.trySend(LoungeCodec.encode(w)) }, close = { out.close() })
                    }
                    val writer = launch { for (text in out) send(Frame.Text(text)) ; close() }
                    var budget = BURST
                    var since = System.currentTimeMillis()
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            // A token bucket: BURST at once, RATE a second after.
                            val now = System.currentTimeMillis()
                            budget = minOf(BURST.toDouble(), budget + (now - since) * RATE / 1000.0).toInt().also { since = now }
                            if (budget <= 0) continue
                            budget--
                            val w = LoungeCodec.decode(frame.readText()) ?: continue
                            withContext(Dispatchers.Main) { session.hear(w) }
                        }
                    } finally {
                        withContext(Dispatchers.Main) { session.gone() }
                        out.close()
                        writer.cancel()
                    }
                }
                get("/") { file(call, "index.html") }
                get("/{path...}") { file(call, call.parameters.getAll("path").orEmpty().joinToString("/")) }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 2000)
        server = null
        sessions.clear()
    }

    // ---- getting in ------------------------------------------------------------------------------------------

    /** The address a request came from: Cloudflare's header through the tunnel, else the socket's. */
    private fun from(call: ApplicationCall): String =
        call.request.header("CF-Connecting-IP") ?: call.request.local.remoteAddress

    private suspend fun enter(call: ApplicationCall) {
        val who = from(call)
        val now = System.currentTimeMillis()
        val lock = lockouts[who] ?: Lockout()
        if (lock.waiting(now)) {
            call.response.header(HttpHeaders.RetryAfter, ((lock.until - now) / 1000 + 1).toString())
            return call.respondText("Too many wrong passcodes: wait a little and try again", status = HttpStatusCode.TooManyRequests)
        }
        val hash = passcodeHash() ?: return call.respondText("The Lounge has no passcode set", status = HttpStatusCode.ServiceUnavailable)
        val given = runCatching { Json.parseToJsonElement(call.receiveText().take(1024)).jsonObject["passcode"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val right = withContext(Dispatchers.Default) { LoungeAuth.verify(given, hash) }
        if (!right) {
            lockouts[who] = lock.failed(now)
            return call.respondText("That is not the passcode", status = HttpStatusCode.Forbidden)
        }
        lockouts.remove(who)
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        sessions += Sha256.hex(token)
        // Secure through the tunnel (https); a LAN visit is plain http, where a Secure cookie would never come back.
        val secure = call.request.header("X-Forwarded-Proto") == "https" || call.request.header("CF-Visitor")?.contains("https") == true
        call.response.header(HttpHeaders.SetCookie, "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${60 * 60 * 24 * 30}" + if (secure) "; Secure" else "")
        call.respond(HttpStatusCode.NoContent)
    }

    private fun signedIn(call: ApplicationCall): Boolean =
        call.request.cookies[COOKIE]?.let { Sha256.hex(it) in sessions } == true

    /** A WebSocket opened by this page, not by another site the friend has open. */
    private fun sameOrigin(call: ApplicationCall): Boolean {
        val origin = call.request.header(HttpHeaders.Origin) ?: return false
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        return originHost.equals(call.request.host(), ignoreCase = true)
    }

    // ---- the page, the cards and their art ----------------------------------------------------------------------

    private suspend fun file(call: ApplicationCall, path: String) {
        if (".." in path || path.startsWith("/")) return call.respond(HttpStatusCode.NotFound)
        val bytes = page(path) ?: return call.respond(HttpStatusCode.NotFound)
        val type = when (path.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html
            "js", "mjs" -> ContentType.Application.JavaScript
            "wasm" -> ContentType("application", "wasm")
            "css" -> ContentType.Text.CSS
            "json" -> ContentType.Application.Json
            "png" -> ContentType.Image.PNG
            "ttf" -> ContentType("font", "ttf")
            else -> ContentType.Application.OctetStream
        }
        call.response.header(HttpHeaders.CacheControl, if (path == "index.html") "no-cache" else "private, max-age=3600")
        call.respondBytes(bytes, type)
    }

    /** Every card, as the page builds its index from: gzipped once a pool, art addressed to this server. */
    private fun cardsBody(): ByteArray {
        val cards = pool()
        val key = cards.size * 31 + (cards.firstOrNull()?.id?.value ?: 0)
        poolBody?.takeIf { it.first == key }?.let { return it.second }
        val shaped = cards.map { c -> c.copy(imageUrl = "/art/f/${c.id.value}.jpg", imageUrlSmall = "/art/s/${c.id.value}.jpg") }
        val text = POOL_JSON.encodeToString(ListSerializer(Card.serializer()), shaped)
        val bytes = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.encodeToByteArray()) } }.toByteArray()
        poolBody = key to bytes
        return bytes
    }

    private suspend fun art(call: ApplicationCall) {
        if (!signedIn(call)) return call.respond(HttpStatusCode.Unauthorized)
        val id = call.parameters["name"]?.removeSuffix(".jpg")?.toIntOrNull()?.takeIf { it > 0 } ?: return call.respond(HttpStatusCode.NotFound)
        val full = call.parameters["size"] == "f"
        val bytes = withContext(Dispatchers.IO) {
            if (full) original(id)?.takeIf { it.isFile }?.readBytes() else small(id)
        } ?: return call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "private, max-age=604800, immutable")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }

    /** A card's small render: kept once fetched, so a duel's cards cost YGOPRODeck one fetch each, ever. */
    private fun small(id: Int): ByteArray? {
        val kept = File(artCache, "$id.jpg")
        if (kept.isFile) return kept.readBytes()
        val bytes = runCatching {
            URI("https://images.ygoprodeck.com/images/cards_small/$id.jpg").toURL().openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 20_000
            }.getInputStream().use { it.readBytes() }
        }.getOrNull() ?: return null
        runCatching {
            artCache.mkdirs()
            val temp = File(artCache, "$id.jpg.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(kept)) { kept.delete(); temp.renameTo(kept) }
        }
        return bytes
    }

    companion object {
        private const val COOKIE = "lounge"
        /** Messages a socket may send at once, and a second after that. */
        private const val BURST = 40
        private const val RATE = 15
        private val POOL_JSON = Json { encodeDefaults = false; explicitNulls = false }
    }
}
