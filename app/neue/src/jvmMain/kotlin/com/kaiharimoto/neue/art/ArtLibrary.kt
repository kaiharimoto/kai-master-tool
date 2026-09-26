package com.kaiharimoto.neue.art

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** The library the card on screen draws from, when there is one. The studio has none, and draws the small renders. */
val LocalArt = staticCompositionLocalOf<ArtLibrary?> { null }

/**
 * Every card's full-size picture, on this computer.
 *
 * YGOPRODeck serves two renders of each card: a 268-pixel one sized for a grid,
 * and the 813 × 1185 original. A card drawn wider than the small one — the
 * inspector always, the deck on a large display — is an upscale, and the first
 * thing an upscale loses is the small print. So the library downloads the
 * originals in the background, while the app is used, and a card draws from
 * the original the moment it is on disk.
 *
 * - **Order.** What is on screen first — the deck, the card being read, the
 *   pool's results — and then the rest of the pool in its own order. [want]
 *   jumps the queue, so a card you are looking at is never waiting behind
 *   thirteen thousand you are not.
 * - **Manners.** YGOPRODeck asks that its images be downloaded and kept rather
 *   than fetched again, and caps requests at twenty a second. This keeps them,
 *   and stays under a dozen a second with four at a time.
 * - **Resumable.** A file is written beside its final name and moved into
 *   place, so a half-downloaded picture is never mistaken for a whole one, and
 *   quitting halfway loses nothing but the file in flight.
 * - **Nothing idles.** The workers wait on a signal when the queue is empty,
 *   and the whole thing stops when the setting is turned off.
 */
class ArtLibrary(
    val dir: File,
    private val scope: CoroutineScope,
) {
    /** Bumped when pictures arrive, a few times a second at most: what a card reads to notice its original is here. */
    var version by mutableStateOf(0)
        private set

    /** How many of the pool's cards have their original on disk, and how many there are. */
    var have by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    var bytes by mutableStateOf(0L)
        private set

    /** The sweep has work left and is doing it. */
    var running by mutableStateOf(false)
        private set

    /** Why it stopped, when it stopped for a reason rather than finishing. */
    var problem by mutableStateOf<String?>(null)
        private set

    var enabled by mutableStateOf(true)
        private set

    private val present: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val missing: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val inFlight: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val queueLock = Any()
    private val urgent = ArrayDeque<Card>()
    private var catalogue: List<Card> = emptyList()
    private var cursor = 0
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pace = Mutex()
    private var lastStart = 0L
    private var workers: List<Job> = emptyList()
    @Volatile private var scanned = false
    @Volatile private var dirty = false
    private val onDisk = java.util.concurrent.atomic.AtomicLong(0L)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    /** The original of [id], if it is on disk. */
    fun fileFor(id: Int): File? = if (id in present) File(dir, "$id.jpg") else null

    /** One line for the title bar while the sweep is running, else null. */
    val progressLine: String?
        get() = if (enabled && running && total > 0 && have < total) "HD art ${percent()}" else null

    fun percent(): String = if (total == 0) "0%" else "${(have * 100L / total).coerceIn(0, 100)}%"

    fun start() {
        scope.launch {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                var sum = 0L
                dir.listFiles()?.forEach { file ->
                    val name = file.name
                    when {
                        name.endsWith(".part") -> file.delete()
                        name.endsWith(".jpg") -> name.removeSuffix(".jpg").toIntOrNull()?.let {
                            present += it
                            sum += file.length()
                        }
                    }
                }
                onDisk.set(sum)
            }
            scanned = true
            recount()
            // A few times a second at most, tell the cards what has arrived.
            while (isActive) {
                delay(400)
                if (dirty) {
                    dirty = false
                    version++
                    recount()
                }
                val trouble = fault
                if (trouble != problem) problem = trouble
            }
        }
    }

    /** Turned on or off in Settings. Off stops the sweep; what is already here is still drawn. */
    fun enable(on: Boolean) {
        if (enabled == on) return
        enabled = on
        if (on) {
            ensureWorkers()
        } else {
            stop()
        }
    }

    /** The whole pool, in the order the sweep should take it. Called whenever the pool is (re)loaded. */
    fun catalogue(cards: List<Card>) {
        synchronized(queueLock) {
            catalogue = cards
            cursor = 0
        }
        recount()
        ensureWorkers()
    }

    /** These cards are on screen: take them next. */
    fun want(cards: Collection<Card>) {
        if (cards.isEmpty()) return
        synchronized(queueLock) {
            cards.reversed().forEach { card ->
                if (card.id.value !in present && card.id.value !in missing) urgent.addFirst(card)
            }
            while (urgent.size > 400) urgent.removeLast()
        }
        wake.trySend(Unit)
        ensureWorkers()
    }

    fun want(card: Card) = want(listOf(card))

    /**
     * The original of [card], now: from disk, or downloaded before returning.
     * For the screenshot, which is worth waiting a second for. Null when it
     * cannot be had (offline, or a card with no picture).
     */
    suspend fun ensure(card: Card): File? {
        fileFor(card.id.value)?.let { return it }
        return withContext(Dispatchers.IO) { runCatching { download(card) }.getOrNull() }
    }

    fun stop() {
        workers.forEach { it.cancel() }
        workers = emptyList()
        running = false
    }

    /** The folder, with its size, for Settings. */
    fun describe(): String = "%,d of %,d · %s".format(have, total, megabytes(bytes))

    private fun megabytes(n: Long): String = if (n >= 1L shl 30) "%.1f GB".format(n / (1L shl 30).toDouble()) else "${n / (1L shl 20)} MB"

    @Volatile private var fault: String? = null

    private fun recount() {
        val cards = synchronized(queueLock) { catalogue }
        total = cards.size
        have = if (cards.isEmpty()) 0 else cards.count { it.id.value in present }
        bytes = onDisk.get()
        running = enabled && workers.any { it.isActive } && have < total
    }

    private fun ensureWorkers() {
        if (!enabled || workers.any { it.isActive }) return
        fault = null
        workers = List(WORKERS) {
            scope.launch(Dispatchers.IO) {
                while (!scanned) delay(100)
                while (isActive) {
                    val next = take()
                    if (next == null) {
                        wake.receive()
                        continue
                    }
                    // Whoever woke this worker may have queued more than one card: pass it on.
                    wake.trySend(Unit)
                    try {
                        download(next)
                        fault = null
                    } catch (e: IOException) {
                        if (e.message.orEmpty().contains("space", ignoreCase = true)) {
                            fault = "The disk is full, so downloading stopped"
                            return@launch
                        }
                        // Offline, or the server said no: try again in a while, not in a tight loop.
                        fault = "Waiting for the network"
                        synchronized(queueLock) { urgent.addLast(next) }
                        delay(30_000)
                    }
                }
            }
        }
        recount()
    }

    /** The next card to fetch: anything on screen first, then the sweep. */
    private fun take(): Card? = synchronized(queueLock) {
        while (urgent.isNotEmpty()) {
            val card = urgent.removeFirst()
            val id = card.id.value
            if (id !in present && id !in missing && inFlight.add(id)) return card
        }
        while (cursor < catalogue.size) {
            val card = catalogue[cursor++]
            val id = card.id.value
            if (id !in present && id !in missing && card.imageUrl != null && inFlight.add(id)) return card
        }
        null
    }

    private suspend fun download(card: Card): File? {
        val id = card.id.value
        val url = card.imageUrl ?: run { missing += id; inFlight -= id; return null }
        try {
            pace.withLock {
                val wait = lastStart + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastStart = System.currentTimeMillis()
            }
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "NeueMasterTool/${Platform.version}")
                .GET()
                .build()
            val part = File(dir, "$id.jpg.part")
            val response = http.send(request, HttpResponse.BodyHandlers.ofFile(part.toPath()))
            if (response.statusCode() == 404) {
                part.delete()
                missing += id
                return null
            }
            if (response.statusCode() !in 200..299) {
                part.delete()
                throw IOException("HTTP ${response.statusCode()}")
            }
            // A JPEG begins FF D8; anything else is an error page with a 200 on it.
            val head = part.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
            if (part.length() < 1024 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) {
                part.delete()
                missing += id
                return null
            }
            val file = File(dir, "$id.jpg")
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            present += id
            onDisk.addAndGet(file.length())
            dirty = true
            return file
        } finally {
            inFlight -= id
        }
    }

    companion object {
        private const val WORKERS = 4

        /** Between request starts, across all workers: eleven or so a second, under YGOPRODeck's twenty. */
        private const val MIN_INTERVAL_MS = 90L
    }
}
