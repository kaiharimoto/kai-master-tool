package com.kaiharimoto.neue.art

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.offline.ArtCount
import com.kaiharimoto.mastertool.core.offline.Offline
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.httpDownload
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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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
 * - **It says how far it has got** ([count]): what is here, what YGOPRODeck
 *   has no original for (settled, not pending — or it would sit at 99% for
 *   ever), and how fast pictures have been arriving, for the title bar's bar
 *   and Settings' "Ready for offline".
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

    /** Of the pool's cards, how many YGOPRODeck has no original for. */
    var unavailable by mutableStateOf(0)
        private set

    /** Originals arriving a second over the last half minute; 0 when none are. */
    var perSecond by mutableStateOf(0.0)
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
    /** When each of the last half minute's originals arrived, for [perSecond]. */
    private val arrivals = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    @Volatile private var sweepStarted = 0L

    /**
     * The counts kept as pictures arrive, rather than counted again over the whole pool every
     * tick (1.0.92): how many times each id stands in the catalogue, and of the catalogue's cards
     * how many are [present] and how many [missing] — what `catalogue.count { it in present }`
     * gave, kept current under [tally] by [markPresent] and [markMissing].
     */
    private val tally = Any()
    private var times: Map<Int, Int> = emptyMap()
    private var haveCount = 0
    private var missingCount = 0

    /** Woken when there is something for the ticker to tell: a picture, a failure, the sweep starting. */
    private val tick = Channel<Unit>(Channel.CONFLATED)

    private fun markPresent(id: Int) = synchronized(tally) {
        if (present.add(id)) haveCount += times[id] ?: 0
    }

    private fun markMissing(id: Int) = synchronized(tally) {
        if (missing.add(id)) missingCount += times[id] ?: 0
    }

    private fun changed() {
        dirty = true
        tick.trySend(Unit)
    }


    /** The original of [id], if it is on disk. */
    fun fileFor(id: Int): File? = if (id in present) File(dir, "$id.jpg") else null

    /** How far the library has got, for the progress bars and "Ready for offline". */
    val count: ArtCount
        get() = ArtCount(have, unavailable, total, bytes, perSecond)

    /** One line for the rail while the sweep is running, else null. */
    val progressLine: String?
        get() = if (enabled && running && !count.complete) "HD art ${percent()}" else null

    fun percent(): String = "${count.percent}%"

    fun start() {
        // Off the main thread (1.0.92): the folder's thirteen thousand names, and the ticker after.
        scope.launch(Dispatchers.Default) {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                onDisk.set(scan())
            }
            scanned = true
            recount()
            // A few times a second at most, tell the cards what has arrived — and with nothing to
            // tell (nothing arriving, nothing failing, the rate at rest), wait to be told.
            while (isActive) {
                if (!dirty && arrivals.isEmpty() && perSecond == 0.0 && fault == problem) tick.receive()
                delay(400)
                if (dirty) {
                    dirty = false
                    version++
                    recount()
                }
                measureRate()
                val trouble = fault
                if (trouble != problem) problem = trouble
            }
        }
    }

    /**
     * The folder read once: half-written files let go, each original marked present, and their
     * sizes summed — from the directory walk's own attributes, which on Windows come with the
     * listing rather than as a query a file.
     */
    private fun scan(): Long {
        var sum = 0L
        Files.walkFileTree(
            dir.toPath(),
            emptySet(),
            1,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    when {
                        name.endsWith(".part") -> runCatching { Files.delete(file) }
                        name.endsWith(".jpg") -> name.removeSuffix(".jpg").toIntOrNull()?.let {
                            markPresent(it)
                            sum += if (attrs.isRegularFile) attrs.size() else 0L
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return sum
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
            synchronized(tally) {
                catalogue = cards
                val counted = HashMap<Int, Int>(cards.size * 2)
                cards.forEach { counted[it.id.value] = (counted[it.id.value] ?: 0) + 1 }
                times = counted
                haveCount = cards.count { it.id.value in present }
                missingCount = cards.count { it.id.value in missing }
            }
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
                // A picture the person added (a negative id, `CustomArt`) is already on disk.
                if (card.id.value > 0 && card.id.value !in present && card.id.value !in missing) urgent.addFirst(card)
            }
            while (urgent.size > 400) urgent.removeLast()
        }
        wake.trySend(Unit)
        ensureWorkers()
    }

    fun want(card: Card) = want(listOf(card))

    /**
     * Every card's original, now (Settings → Download all, before a flight): the
     * sweep does this by itself while the setting is on, so this only makes
     * sure it is running — and, when it is waiting out the network, stops
     * waiting and tries again at once. The sweep's pace is unchanged.
     */
    fun downloadAll() {
        if (!enabled) return
        if (fault != null) {
            stop()
            fault = null
            problem = null
        }
        synchronized(queueLock) { cursor = 0 }
        ensureWorkers()
        wake.trySend(Unit)
    }

    /**
     * The original of [card], now: from disk, or downloaded before returning.
     * For the screenshot, which is worth waiting a second for. Null when it
     * cannot be had (offline, or a card with no picture).
     */
    suspend fun ensure(card: Card): File? {
        fileFor(card.id.value)?.let { return it }
        return withContext(Dispatchers.IO) {
            // Asked before the sweep has started (the studio, a crop opened at once), the folder may not be there yet.
            dir.mkdirs()
            runCatching { download(card) }.getOrNull()
        }
    }

    fun stop() {
        workers.forEach { it.cancel() }
        workers = emptyList()
        running = false
    }

    /** The folder, with its size, for Settings. */
    fun describe(): String = Offline.artLine(count, enabled, problem)

    private fun measureRate() {
        val now = System.currentTimeMillis()
        while (true) {
            val first = arrivals.peekFirst() ?: break
            if (first >= now - RATE_WINDOW_MS) break
            arrivals.pollFirst()
        }
        val span = (now - sweepStarted).coerceIn(5_000L, RATE_WINDOW_MS)
        // To a tenth, so the pages reading it redraw when the figure would change, not every tick.
        val rate = if (!running) 0.0 else kotlin.math.round(arrivals.size * 10_000.0 / span) / 10.0
        if (rate != perSecond) perSecond = rate
    }

    @Volatile private var fault: String? = null

    private fun recount() {
        synchronized(tally) {
            total = catalogue.size
            have = haveCount
            unavailable = missingCount
        }
        bytes = onDisk.get()
        running = enabled && workers.any { it.isActive } && have + unavailable < total
    }

    private fun ensureWorkers() {
        if (!enabled || workers.any { it.isActive }) return
        fault = null
        sweepStarted = System.currentTimeMillis()
        arrivals.clear()
        tick.trySend(Unit)
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
                        if (fault != null) {
                            fault = null
                            tick.trySend(Unit)
                        }
                    } catch (e: IOException) {
                        if (e.message.orEmpty().contains("space", ignoreCase = true)) {
                            fault = "The disk is full, so downloading stopped"
                            tick.trySend(Unit)
                            return@launch
                        }
                        // Offline, or the server said no: try again in a while, not in a tight loop.
                        fault = "Waiting for the network"
                        tick.trySend(Unit)
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
        val url = card.imageUrl ?: run { markMissing(id); inFlight -= id; return null }
        try {
            pace.withLock {
                val wait = lastStart + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastStart = System.currentTimeMillis()
            }
            val part = File(dir, "$id.jpg.part")
            val status = httpDownload(url, part, mapOf("User-Agent" to "NeueMasterTool/${Platform.version}"), timeoutSeconds = 30)
            if (status == 404) {
                part.delete()
                markMissing(id)
                changed()
                return null
            }
            if (status !in 200..299) {
                part.delete()
                throw IOException("HTTP $status")
            }
            // A JPEG begins FF D8; anything else is an error page with a 200 on it.
            val head = part.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
            if (part.length() < 1024 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) {
                part.delete()
                markMissing(id)
                changed()
                return null
            }
            val file = File(dir, "$id.jpg")
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            markPresent(id)
            onDisk.addAndGet(file.length())
            arrivals.addLast(System.currentTimeMillis())
            changed()
            return file
        } finally {
            inFlight -= id
        }
    }

    companion object {
        private const val WORKERS = 4

        /** Between request starts, across all workers: eleven or so a second, under YGOPRODeck's twenty. */
        private const val MIN_INTERVAL_MS = 90L

        /** How far back [perSecond] looks. */
        private const val RATE_WINDOW_MS = 30_000L
    }
}
