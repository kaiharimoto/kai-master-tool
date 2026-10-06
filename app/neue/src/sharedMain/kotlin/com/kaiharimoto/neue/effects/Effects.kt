package com.kaiharimoto.neue.effects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.duel.effects.FxAsked
import com.kaiharimoto.mastertool.core.duel.effects.FxAsks
import com.kaiharimoto.mastertool.core.duel.effects.FxCheck
import com.kaiharimoto.mastertool.core.duel.effects.FxCost
import com.kaiharimoto.mastertool.core.duel.effects.FxMeter
import com.kaiharimoto.mastertool.core.duel.effects.FxOffer
import com.kaiharimoto.mastertool.core.duel.effects.FxOffers
import com.kaiharimoto.mastertool.core.duel.effects.FxRequest
import com.kaiharimoto.mastertool.core.duel.effects.FxSuggest
import com.kaiharimoto.mastertool.core.duel.effects.FxCodec
import com.kaiharimoto.mastertool.core.duel.effects.FxCompile
import com.kaiharimoto.mastertool.core.duel.effects.FxEntry
import com.kaiharimoto.mastertool.core.duel.effects.FxFacts
import com.kaiharimoto.mastertool.core.duel.effects.FxHost
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.effects.FxRead
import com.kaiharimoto.mastertool.core.duel.effects.FxReview
import com.kaiharimoto.mastertool.core.duel.effects.FxReviews
import com.kaiharimoto.mastertool.core.duel.effects.FxShelf
import com.kaiharimoto.mastertool.core.duel.effects.FxStatus
import com.kaiharimoto.mastertool.core.duel.effects.FxWords
import com.kaiharimoto.mastertool.core.duel.effects.FxMarks
import com.kaiharimoto.mastertool.core.duel.effects.FxPlayed
import com.kaiharimoto.mastertool.core.duel.effects.FxPlayedUse
import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDoc
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishResult
import com.kaiharimoto.mastertool.core.duel.effects.ScriptBook
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.neue.world.WorldMount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The library of written effects for the app's lifetime (Phase D step 2, `docs/phases/D.md` §3.3, §6): `<data>/effects/`
 * read, each source compiled when it changed ([FxShelf.needsCompile]: never over a newer build's script), every script
 * checked ([FxCheck.full], on each load and after each sync, so nothing arrives "checked"), and the [book] the engine and
 * the table read — unverified scripts too, a broken one never. Every printing reads its card's script (`CardIdentity`).
 *
 * Mounted in every world at `lib/effects/` ([mount]): a source written there — by Ai's `world_write` or the person's editor —
 * is compiled and checked at once, and the writer is told how it went.
 *
 * All work runs off the frame thread, one piece at a time ([work]); what the page shows ([entries], [revision]) changes on
 * the main thread. The seams later agents build on are named on each member: [gate] (the asked list, `FxAsks`),
 * [revision] and [book] (the table's Shortcuts), [entries] / [words] / [accept] (the Effects pane), [describe] (`fx_state`).
 */
class Effects(val dir: File, val cacheDir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val work = Mutex()

    /** The pool as the app holds it now, read on the main thread and handed to the work whole: set by the holders. */
    var pool: () -> CardIndex = { CardIndex.EMPTY }

    /** Told on the main thread whenever the library changed (the world's file listing follows it). */
    var onChange: () -> Unit = {}

    /**
     * Why [by] may not write [card]'s source ([rel] under `lib/effects/`) now, or null when they may — the asked list
     * (D.md §3.1, [FxAsks.gate]): Ai's write to a card nobody asked for is refused; the person's own saves need no list.
     * Null [card]: a helper (`_x.js`), Ai's only while something is asked.
     */
    var gate: (card: Int?, rel: String, by: String) -> String? = { card, _, by -> FxAsks.gate(asked, card, by) }

    /** The asked list (`<data>/effects/asked.json`, [FxAsks]): what the person asked to have written, and what it cost. */
    var asked by mutableStateOf(FxAsked())
        private set

    /** Request cards the person answered Not now, by id: the chat folds them to a line. Kept while the app runs. */
    var declined by mutableStateOf<Set<String>>(emptySet())

    /** Every card the library holds, by canonical passcode: what the Effects pane lists. */
    var entries by mutableStateOf<Map<Int, FxEntry>>(emptyMap())
        private set

    /** The library has been read at least once. */
    var loaded by mutableStateOf(false)
        private set

    /** What is being done now, in words ("Compiling 900000001"), or null. */
    var working by mutableStateOf<String?>(null)
        private set

    /** Cards past the cap ([FxPaths.MOST_SCRIPTS]): kept on disk, never read. */
    var over by mutableStateOf<List<Int>>(emptyList())
        private set

    /** Moves on with every change of the [book]: the table rebuilds its Shortcuts when it does. */
    var revision by mutableStateOf(0)
        private set

    /** The scripts the engine and the table read, any printing resolved: safe to read from any thread. */
    @Volatile
    var book: ScriptBook = ScriptBook.EMPTY
        private set

    // ---- Reading ---------------------------------------------------------------------------------------------------

    /** Reads the library (and compiles and checks what needs it), off the frame thread. */
    fun load(): Job = scope.launch {
        reloadNow()
        // A pool still downloading: check again once it is there, so the card's layer and the lints run (ten minutes at most).
        var waited = 0
        while (pool().size == 0 && waited < 300) {
            delay(2_000)
            waited++
        }
        if (waited > 0 && pool().size > 0) reloadNow()
    }

    /** Reads the library again: a sync or a restore brought files in. Everything is checked again. */
    fun reload(): Job = load()

    /** [load], waited for: the tests', and a tool that needs the library read first. */
    suspend fun reloadNow() {
        val index = withContext(Dispatchers.Main) { pool() }
        val read = work.withLock {
            withContext(Dispatchers.Main) { working = "Reading the library" }
            try {
                withContext(Dispatchers.IO) { readAll(index) }
            } finally {
                withContext(Dispatchers.Main) { working = null }
            }
        }
        // The asked list travels with the library (a sync or a restore may bring a newer one). Read under the work's lock, so
        // never half-written, and taken only when no edit made here is still on its way to the disk — or a reload that
        // raced a go would put back the list from before it.
        val edits = withContext(Dispatchers.Main) { askedEdits }
        val list = work.withLock { withContext(Dispatchers.IO) { FxAsks.decode(file(FxPaths.ASKED).takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() }) } }
        withContext(Dispatchers.Main) { if (askedEdits == edits && askedSaved >= askedEdits) asked = list }
        // The "played by you" marks travel the same way (Phase D step 4), under the same care.
        val playedEditsNow = withContext(Dispatchers.Main) { playedEdits }
        val marks = work.withLock { withContext(Dispatchers.IO) { FxMarks.decode(file(FxPaths.PLAYED).takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() }) } }
        withContext(Dispatchers.Main) { if (playedEdits == playedEditsNow && playedSaved >= playedEdits) played = marks }
        publish(read.first, read.second, index)
    }

    private fun readAll(index: CardIndex): Pair<Map<Int, FxEntry>, List<Int>> {
        val names = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }
        val cards = names.mapNotNull { FxPaths.sourceOf(it) ?: FxPaths.compiledOf(it) ?: FxPaths.reviewOf(it) }.toSet()
        val (kept, over) = FxShelf.capped(cards)
        val out = LinkedHashMap<Int, FxEntry>()
        kept.forEach { card -> out[card] = readOne(card, index, force = false) }
        return out to over
    }

    /** One card read: its source compiled when it changed (or when [force]), then checked. On the work's thread. */
    private fun readOne(card: Int, index: CardIndex, force: Boolean): FxEntry {
        val source = file(FxPaths.js(card)).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val sourceHash = source?.let(FxCodec::short)
        val compiledFile = file(FxPaths.json(card))
        var compiled: FxRead? = compiledFile.takeIf { it.isFile }?.let { f -> runCatching { FxCodec.read(f.readText()) }.getOrElse { FxRead.Bad("Unreadable: ${it.message}") } }
        var compileError: String? = null
        if (source != null && (FxShelf.needsCompile(sourceHash, compiled) || (force && compiled !is FxRead.Newer))) {
            when (val o = FxCompile.compile(card, source, host(index), index.byId(CardId(card)))) {
                is FxCompile.Outcome.Compiled -> {
                    write(compiledFile, o.json)
                    compiled = FxRead.Script(o.script)
                }
                is FxCompile.Outcome.Failed -> compileError = o.why
            }
        }
        val review = file(FxPaths.review(card)).takeIf { it.isFile }?.let { runCatching { FxReviews.decode(it.readText()) }.getOrNull() }
        val script = (compiled as? FxRead.Script)?.script
        val report = script?.let { s ->
            FxCheck.full(s, index.byId(CardId(card)), canonical(index), expected = card, bytes = FxCodec.encode(s).encodeToByteArray().size, nameOf = { index.byId(CardId(it))?.name })
        }
        return FxEntry(card, sourceHash, compiled, compileError, report, review)
    }

    private suspend fun publish(next: Map<Int, FxEntry>, over: List<Int>, index: CardIndex) {
        val b = FxShelf.book(next.values, canonical(index))
        withContext(Dispatchers.Main) {
            entries = next
            this@Effects.over = over
            book = b
            loaded = true
            revision++
            onChange()
        }
    }

    // ---- Compiling one ------------------------------------------------------------------------------------------------

    /** What compiling one card came to, for its writer: the entry, and the words ("Compiled: 2 effects; 1 warning"). */
    data class Compiled(val entry: FxEntry, val words: String)

    /** Compiles and checks [card]'s source now, even when it did not change (`fx_check`); [by] is who asked. */
    suspend fun compile(card: Int, by: String = WorldEvent.AI): Compiled {
        val index = withContext(Dispatchers.Main) { pool() }
        val canon = canonical(index)(card)
        val e = work.withLock {
            withContext(Dispatchers.Main) { working = "Compiling $canon" }
            try {
                withContext(Dispatchers.IO) { readOne(canon, index, force = true) }
            } finally {
                withContext(Dispatchers.Main) { working = null }
            }
        }
        val next = entries.toMutableMap().also { if (e.sourceHash == null && e.compiled == null && e.review == null) it.remove(canon) else it[canon] = e }
        publish(next, over, index)
        return Compiled(e, outcome(e, index))
    }

    /** Every card whose source loads helper [rel] (`ygo.use('lib/effects/_x.js')`), compiled again: its data may have moved. */
    private suspend fun helperChanged(rel: String): String {
        val users = withContext(Dispatchers.IO) {
            dir.listFiles().orEmpty().mapNotNull { f -> FxPaths.sourceOf(f.name)?.takeIf { runCatching { rel in f.readText() }.getOrDefault(false) } }
        }
        users.forEach { compile(it, WorldEvent.YOU) }
        return if (users.isEmpty()) "No card's script uses $rel yet." else "Compiled again the ${users.size} card${if (users.size == 1) "" else "s"} that use $rel."
    }

    /** [card]'s source and compiled script removed (a world deleted the source). */
    private suspend fun removed(card: Int) {
        withContext(Dispatchers.IO) { file(FxPaths.json(card)).delete() }
        val index = withContext(Dispatchers.Main) { pool() }
        publish(entries - card, over, index)
    }

    // ---- What the page and Ai read --------------------------------------------------------------------------------------

    /**
     * The pool's facts for the engine (`FxFacts.over`), any printing resolved: with [book], what the table's
     * `Shortcuts.of(game, h.effects.book, h.effects.facts(), …)` needs (agent (e)'s seam; rebuild when [revision] moves).
     */
    fun facts(): FxFacts {
        val index = pool()
        return FxFacts.over { index.byId(it) }
    }

    /** The pool's cards by exact name, any case, as passcodes: `Shortcuts.names`, for a typed `declare=`. */
    fun names(): (String) -> List<Int> {
        val index = pool()
        return { name -> listOfNotNull(index.byName(name)?.id?.value) }
    }

    /** [code]'s entry, by any printing; null when the library has nothing for it. */
    fun entry(code: Int): FxEntry? = entries[canonical(pool())(code)]

    /** [code]'s status; a card with no script is MISSING, a Normal Monster NONE. */
    fun status(code: Int): FxStatus = entry(code)?.status ?: FxShelf.statusWithout(pool().byId(CardId(code)))

    /** [code]'s script in words, one line an effect; empty without one. */
    fun words(code: Int): List<FxWords.Line> {
        val index = pool()
        return entry(code)?.script?.let { FxWords.of(it) { c -> index.byId(CardId(c))?.name } }.orEmpty()
    }

    /**
     * The person accepts warning [key] on [card] because [why] (the Effects pane's, later): refused for Ai, or without why
     * (D.md §7). Written to `<passcode>.review.json`; the card's status follows.
     */
    fun accept(card: Int, key: String, why: String, by: String, at: Long = System.currentTimeMillis()): Boolean {
        val canon = canonical(pool())(card)
        val old = entries[canon]?.review ?: FxReview(card = canon)
        val next = FxReviews.accept(old, key, why, by, at) ?: return false
        saveReview(canon, next)
        return true
    }

    /** The person takes an acceptance back. */
    fun withdraw(card: Int, key: String) {
        val canon = canonical(pool())(card)
        val old = entries[canon]?.review ?: return
        saveReview(canon, FxReviews.withdraw(old, key))
    }

    private fun saveReview(card: Int, r: FxReview) {
        val e = (entries[card] ?: FxEntry(card)).copy(review = r)
        entries = entries + (card to e)
        revision++
        scope.launch(Dispatchers.IO) { write(file(FxPaths.review(card)), FxReviews.encode(r)) }
    }

    /** A card's outcome in words, for its writer and `fx_check`. */
    fun outcome(e: FxEntry, index: CardIndex = pool()): String = buildString {
        val name = e.script?.name?.ifBlank { null } ?: index.byId(CardId(e.card))?.name ?: "${e.card}"
        val s = e.script
        when {
            e.newer -> append("$name (${e.card}): written by a newer build, in words this one does not read; kept as it is.")
            e.compileError != null -> append("$name (${e.card}) did not compile: ${e.compileError}")
            s == null -> append("$name (${e.card}): no script. Write lib/effects/${e.card}.js.")
            else -> {
                val r = e.report
                append("Compiled $name (${e.card}): ${s.effects.size} effect${if (s.effects.size == 1) "" else "s"}")
                if (s.summon != null) append(", its summoning rules")
                append("; ${r?.errors?.size ?: 0} error${if (r?.errors?.size == 1) "" else "s"}, ${e.open.size} warning${if (e.open.size == 1) "" else "s"} open. Status: ${e.status.words}.")
                if (r != null && r.findings.isNotEmpty()) append("\n").append(r.words(e.review?.keys.orEmpty()))
            }
        }
    }

    /** `fx_state` without a card or a deck: the library's counts, and what is broken or warned. */
    fun describe(): String {
        val all = entries.values
        if (all.isEmpty()) return "The library of written effects is empty. A card's effect is lib/effects/<passcode>.js in any world, written with fx.*; fx_check compiles and checks it."
        val index = pool()
        val by = all.groupBy { it.status }
        return buildString {
            append("The library holds ${all.size} card${if (all.size == 1) "" else "s"}: ")
            append(FxStatus.entries.mapNotNull { st -> by[st]?.size?.let { "$it ${st.words}" } }.joinToString("; "))
            append(".")
            if (over.isNotEmpty()) append(" ${over.size} more are past the cap of ${FxPaths.MOST_SCRIPTS} and not read.")
            val trouble = all.filter { it.status == FxStatus.BROKEN || it.status == FxStatus.WARNED }.take(40)
            if (trouble.isNotEmpty()) {
                append("\nTo repair:")
                trouble.forEach { e -> append("\n- ${index.byId(CardId(e.card))?.name ?: e.card} (${e.card}): ${e.status.words}") }
            }
        }
    }

    /** `fx_state` for one card: its status, its script in words beside its printed text, and what the pass found. */
    fun describe(card: Card): String {
        val canon = card.id.value
        val e = entries[canon]
        return buildString {
            append("${card.name} (${canon}): ")
            append((e?.status ?: FxShelf.statusWithout(card)).words).append(".")
            if (card.passcodes.size > 1) append(" Every printing (${card.passcodes.joinToString { it.value.toString() }}) reads this one script.")
            append("\nPrinted text: ").append(card.description.ifBlank { "(none)" })
            if (e == null) {
                append("\nNo script yet: lib/effects/$canon.js.")
                return@buildString
            }
            val words = words(canon)
            if (words.isNotEmpty()) {
                append("\nThe script in words:")
                words.forEach { append("\n- ").append(it.toString()) }
            }
            append("\n").append(outcome(e))
        }
    }

    /** `fx_state` for a deck: each distinct card of the Main and Extra Deck with its status. */
    fun describeDeck(name: String, codes: List<Int>): String {
        val index = pool()
        val canon = canonical(index)
        val distinct = codes.map(canon).distinct()
        val rows = distinct.map { c -> c to status(c) }
        val counts = rows.groupBy { it.second }.mapValues { it.value.size }
        return buildString {
            append("$name: ${distinct.size} distinct cards in the Main and Extra Deck. ")
            append(FxStatus.entries.mapNotNull { st -> counts[st]?.let { "$it ${st.words}" } }.joinToString("; ")).append(".")
            rows.forEach { (c, st) -> append("\n- ${index.byId(CardId(c))?.name ?: c} ($c): ${st.words}") }
        }
    }

    // ---- Asking (D.md §3.1) --------------------------------------------------------------------------------------------

    /** The session writing what a go asked for: its request, the meter of its rounds, and what they are priced at. */
    class Authoring(val session: String, val request: FxRequest, val meter: FxMeter, val model: String?, val price: Prices.Price?)

    /** The effects session at work now, or null (`AiSession.MODE_EFFECTS`). On the main thread. */
    var authoring: Authoring? = null
        private set

    /** Every printing of [codes] as its card, in order, once each. */
    fun canonicalAll(codes: List<Int>): List<Int> {
        val canon = canonical(pool())
        return codes.map(canon).distinct()
    }

    /** What `fx_request` and every go offer for [cards]: sorted, priced at [model]'s own figure ([FxCost.perCard]) and [price]. */
    fun offer(id: String, what: String, deck: String?, cards: List<Int>, model: String?, price: Prices.Price?): FxOffer =
        FxOffers.of(id, what, deck, cards, canonical(pool()), ::status, FxCost.perCard(asked, model), price)

    /**
     * **The person's go** (D.md §3.1): [request]'s cards put on the asked list and kept, or null when [by] is not the person or
     * nothing is to write ([FxAsks.go]). On the main thread.
     */
    fun go(request: FxRequest, by: String): FxRequest? {
        val r = request.copy(cards = canonicalAll(request.cards))
        val next = FxAsks.go(asked, r, by) ?: return null
        asked = next
        revision++
        saveAsked()
        return r
    }

    /** A session begins writing [request]: its rounds from here are what the cards cost (measured from [spent]). */
    fun begin(session: String, request: FxRequest, spent: Usage, model: String?, price: Prices.Price?) {
        authoring?.let { ended(it.session) }
        authoring = Authoring(session, request, FxMeter(spent), model, price)
    }

    /** Ai began [card] (its first write). */
    private fun began(card: Int) {
        if (!FxAsks.asked(asked, card)) return
        val next = FxAsks.started(asked, card)
        if (next != asked) {
            asked = next
            saveAsked()
        }
    }

    /**
     * [card] was checked in [session] (`fx_check`): the rounds since the last check, at [spent] now, are what it cost, added to
     * its row in the asked list. Nothing outside an effects session, nor for a card that was not asked.
     */
    fun checked(card: Int, session: String?, spent: Usage) {
        val a = authoring?.takeIf { it.session == session } ?: return
        val canon = canonical(pool())(card)
        if (!FxAsks.asked(asked, canon)) return
        asked = FxAsks.spent(asked, canon, a.meter.since(spent), a.price, a.model)
        revision++
        saveAsked()
    }

    /** The effects session [session]'s run ended: the cards it never reached stay asked, marked not started (D.md §3.6). */
    fun ended(session: String) {
        val a = authoring?.takeIf { it.session == session } ?: return
        authoring = null
        val next = FxAsks.stopped(asked, a.request.id)
        if (next != asked) {
            asked = next
            saveAsked()
        }
    }

    /** What to write first for a deck ([FxSuggest]): never a card already written. */
    fun suggest(main: List<Int>, extra: List<Int>, combos: List<Collection<Int>>, engine: Collection<Int>, limit: Int = 12): List<FxSuggest.Pick> =
        FxSuggest.of(main, extra, combos, engine, ::status, canonical(pool()), limit)

    /** Edits to [asked] made here (on the main thread), and how many of them have reached the disk. */
    private var askedEdits = 0

    @Volatile
    private var askedSaved = 0

    /** The newest list to write, with its edit's number: each save writes the newest, so saves landing out of order never go back. */
    @Volatile
    private var askedPending: Pair<String, Int>? = null

    private fun saveAsked() {
        askedPending = FxAsks.encode(asked) to ++askedEdits
        scope.launch(Dispatchers.IO) {
            work.withLock {
                val (text, n) = askedPending ?: return@withLock
                if (n > askedSaved) {
                    write(file(FxPaths.ASKED), text)
                    askedSaved = n
                }
            }
        }
    }

    // ---- The goldfish (Phase D step 4, D.md §5, §11) ---------------------------------------------------------------------

    /**
     * The "played by you" marks (`<data>/effects/played.json`, [FxMarks]): each card used by Shortcut at the table and kept,
     * per card and script hash. Synced newer wins and backed up with the library; a changed script loses its mark.
     */
    var played by mutableStateOf(FxPlayed())
        private set

    /** A use the person made at the table kept ([kept]) or its group undone: the marks follow. On the main thread. */
    fun played(uses: List<FxPlayedUse>, kept: Boolean, at: Long = System.currentTimeMillis()) {
        if (uses.isEmpty()) return
        val next = if (kept) FxMarks.mark(played, uses, at) else FxMarks.unmark(played, uses)
        if (next == played) return
        played = next
        playedPending = FxMarks.encode(next) to ++playedEdits
        scope.launch(Dispatchers.IO) {
            work.withLock {
                val (text, n) = playedPending ?: return@withLock
                if (n > playedSaved) {
                    write(file(FxPaths.PLAYED), text)
                    playedSaved = n
                }
            }
        }
    }

    private var playedEdits = 0

    @Volatile
    private var playedSaved = 0

    @Volatile
    private var playedPending: Pair<String, Int>? = null

    /**
     * What the goldfish trusts now ([FxTrust], D.md §11): the library's entries — UNTESTED and WARNED used, broken,
     * unsupported and missing inert — with the marks, any printing resolved. A value: read it on the main thread and hand
     * it to a run on any other.
     */
    fun trust(): FxTrust = FxTrust(entries, played, canonical(pool()))

    /** The goldfish in the Effects app: its settings, the run in progress and the result on screen (agent (c)). */
    val goldfishRuns: GoldfishRuns by lazy { GoldfishRuns(this) }

    /** Moves on whenever a deck's goldfish file is written here: the pane reads the file again. */
    var goldfishRevision by mutableStateOf(0)
        private set

    /** [deckId]'s goldfish file (`goldfish/<deck>.json`): its targets and kept results; empty when it has none. Any thread. */
    fun goldfish(deckId: String): GoldfishDoc =
        GoldfishCodec.decode(file(GoldfishCodec.path(deckId)).takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() })
            .let { if (it.deck.isEmpty()) it.copy(deck = deckId) else it }

    /** [deckId]'s goldfish file changed by [change] and written, one change at a time; the file as written. */
    suspend fun updateGoldfish(deckId: String, change: (GoldfishDoc) -> GoldfishDoc): GoldfishDoc {
        val next = work.withLock {
            withContext(Dispatchers.IO) {
                val now = goldfish(deckId)
                val d = change(now)
                if (d != now) write(file(GoldfishCodec.path(deckId)), GoldfishCodec.encode(d))
                d
            }
        }
        withContext(Dispatchers.Main) { goldfishRevision++ }
        return next
    }

    /** A target named for [deckId] (the person's from the pane, Ai's from `fx_target`): put in, replacing one of its id. */
    suspend fun putTarget(deckId: String, target: EndBoard): GoldfishDoc = updateGoldfish(deckId) { GoldfishCodec.putTarget(it, target) }

    /** A result the person kept. */
    suspend fun keepResult(deckId: String, result: GoldfishResult): GoldfishDoc = updateGoldfish(deckId) { GoldfishCodec.keep(it, result) }

    /** A deck deleted: its goldfish file goes with it (D.md §6). */
    fun forgetDeck(deckId: String) {
        scope.launch {
            work.withLock { withContext(Dispatchers.IO) { file(GoldfishCodec.path(deckId)).delete() } }
            goldfishRevision++
        }
    }

    // ---- The mount ------------------------------------------------------------------------------------------------------

    /** The library as every world sees it, at `lib/effects/` (D.md §3.3). */
    val mount: WorldMount = object : WorldMount {
        override val prefix: String = FxPaths.MOUNT
        override val dir: File get() = this@Effects.dir
        override fun readable(rel: String): Boolean = FxPaths.readable(rel)
        override fun writable(rel: String): Boolean = FxPaths.writable(rel)

        override fun refuse(rel: String, by: String): String? {
            val card = FxPaths.sourceOf(rel)
            if (card != null) {
                val canon = canonical(pool())(card)
                if (canon != card) return "$card is an alternate artwork of $canon: write lib/effects/$canon.js — every printing reads its card's one script."
                if (card !in entries && entries.size >= FxPaths.MOST_SCRIPTS) return "The library holds at most ${FxPaths.MOST_SCRIPTS} scripts."
            }
            return gate(card, rel, by)
        }

        override fun listed(): List<String> =
            this@Effects.dir.listFiles().orEmpty().filter { it.isFile && FxPaths.writable(it.name) }.map { prefix + it.name }.sorted()

        override suspend fun changed(rel: String, by: String, deleted: Boolean): String? {
            val card = FxPaths.sourceOf(rel)
            return when {
                card != null && deleted -> { removed(card); "Removed $card's script from the library." }
                card != null -> {
                    if (by == WorldEvent.AI) withContext(Dispatchers.Main) { began(canonical(pool())(card)) }
                    compile(card, by).words
                }
                FxPaths.helper(rel) -> helperChanged(rel)
                else -> null
            }
        }
    }

    // ---- Files ---------------------------------------------------------------------------------------------------------

    private fun file(name: String) = File(dir, name)

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun host(index: CardIndex) = FxHost(
        byId = { index.byId(CardId(it)) },
        byName = { index.byName(it) },
        files = { path ->
            FxPaths.mounted(path)?.takeIf { FxPaths.writable(it) }?.let { rel -> file(rel).takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() } }
        },
    )

    private fun canonical(index: CardIndex): (Int) -> Int = { code -> index.byId(CardId(code))?.id?.value ?: code }

    companion object {
        /** `<data>/effects` and `<data>/fxcache` under [data]. */
        fun under(data: File): Effects = Effects(File(data, FxPaths.FOLDER), File(data, FxPaths.CACHE))
    }
}
