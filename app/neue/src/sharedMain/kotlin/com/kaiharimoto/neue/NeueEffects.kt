package com.kaiharimoto.neue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.deck.DeckDependents
import com.kaiharimoto.mastertool.core.duel.effects.EngineWarmup
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.ai.cutBreaks
import com.kaiharimoto.neue.platform.DiagnosticLog
import com.kaiharimoto.neue.prep.withOpponentCards
import com.kaiharimoto.neue.versions.carryMeasurements
import com.kaiharimoto.neue.versions.keepVersion
import com.kaiharimoto.neue.versions.sweepVersions
import com.kaiharimoto.mastertool.core.deck.PlayChoice
import com.kaiharimoto.neue.builder.legalityRules
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.input.DeskMenuBar
import com.kaiharimoto.mastertool.core.library.StartingDeck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.ui.configureImageLoader
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.carryLearning
import com.kaiharimoto.neue.ai.foldIntoWeb
import com.kaiharimoto.neue.ai.shutDown
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.sync.SyncCenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What lives as long as the app, not as long as a window: the database, the card
 * pool, the preferences, the update check, the art library, and the deck to open
 * with. Until 1.0.24 these were the window's, and on Windows every trip into or out
 * of immersive mode — a new window — stopped them all and started them again: the
 * image loader made afresh, so every card's picture was read again, the pool
 * reloaded, the art library restarted. That was much of the moment in which the
 * whole app went blank (kai: "the whole app disappears for a second").
 */
@Composable
fun NeueEffects(h: NeueHolders) {
    val neue = h.neue
    val state = h.builder
    // Work in the background that fails is written down and said quietly, never the app's end (Phase H, H.1).
    LaunchedEffect(Unit) {
        DiagnosticLog.watchBackground()
        Background.failed.filterNotNull().collect { f ->
            neue.note = Note("Something in ${f.where} failed: ${f.what}. The app carries on, and it is written in the log.", lastsMs = 9000)
        }
    }
    // What a cut breaks (Phase G, G.9; A4): an edit that takes a card's last copy out names the combos, mapped boards and
    // playbook lines that used it, with Undo. Only an edit of the deck on the builder: opening another deck is no cut.
    LaunchedEffect(Unit) {
        var last: Pair<String?, Deck>? = null
        snapshotFlow { state.deckId to state.deck }.collect { (id, deck) ->
            val before = last
            last = id to deck
            if (before == null || before.first != id || id == null || before.second === deck) return@collect
            val broke = h.cutBreaks(id, before.second, deck)
            if (broke.isNotEmpty()) neue.note = Note(broke.joinToString(" · ") { DeckDependents.words(it) }, action = "Undo") { state.undo() }
        }
    }
    // The engine's first pass played before anyone waits on it (Phase I, I.1): once, on the desk, the first time a page that
    // searches opens. A search a moment long (a turn solved, a Search seat's answer) then runs at the steady rate, not the cold.
    LaunchedEffect(Unit) {
        if (Platform.os == DesktopOs.ANDROID) return@LaunchedEffect
        snapshotFlow { neue.page }.first { it == Page.DUEL || it == Page.MAPPER }
        withContext(Dispatchers.Default) { EngineWarmup.run(stop = { !isActive }) }
    }
    // Every deck with a version once the pool is read, since a print is by card (Phase G, G.8).
    LaunchedEffect(Unit) {
        snapshotFlow { state.index.cards.isNotEmpty() }.first { it }
        withContext(Dispatchers.IO) { runCatching { h.sweepVersions() } }
    }
    run {
        DisposableEffect(Unit) {
            configureImageLoader(java.io.File(Platform.dataDir, "card-art").absolutePath)
            h.layout.start { prefs ->
                state.onFormatChange(prefs.format)
                state.onSearchEffectsChange(prefs.searchEffects)
            }
            neue.start()
            state.start()
            h.webs.load()
            h.prep.load()
            // A .ydkw opened through a deck's Import (or handed over by another app) is a web: to Format with it.
            state.onWebFile = { text ->
                h.webs.open(text) { made ->
                    if (made != null) {
                        neue.go(Page.FORMAT)
                        neue.note = Note("Opened “${made.name}”: ${made.entries.size} decks")
                    }
                }
            }
            h.updates.check(userInitiated = false)
            h.art.start()
            // Ai's notes follow a deck into a web, and go with a web that is deleted (1.0.43).
            h.webs.onJoined = { from, name, web -> if (neue.prefs.ai.enabled) h.ai.foldIntoWeb(from, name, web) }
            h.webs.onCopied = { from, to ->
                if (neue.prefs.ai.enabled) h.ai.carryLearning(from, to)
                h.carryMeasurements(from, to)
            }
            // Each deck's versions (Phase G, G.8): a save that changes the cards by card keeps one.
            state.afterEverySave = { id, name, deck -> h.keepVersion(id, name, deck) }
            // A game logged keeps its opponent's list, so a re-imported list still finds it (G.8).
            h.prep.stamp = { g -> h.withOpponentCards(g) }
            h.webs.onDeleted = { web -> h.ai.files.delete(AiMemory.path(MemoryKind.WEB, web)) }
            onDispose {
                h.art.stop()
                h.layout.flush()
                neue.flush()
                h.prep.flush()
                h.flushDuel()
                h.flushPresent()
            }
        }
    }

    run {
        // What the builder checks the deck against (1.1.1): a day's list, or Genesys, from the person's choice.
        val p = neue.prefs
        LaunchedEffect(p.legalAsOf, p.genesys, p.genesysCap, state.format, state.index) {
            state.rules = h.legalityRules(p, state.format)
        }
        // Genesys is TCG cards (1.1.8): however Genesys came on — the drawer, Ai, another device, a 1.1.1 build that
        // kept OCG beside it — the region settles to TCG, so the pool, the bar and the check say one thing.
        LaunchedEffect(p.genesys, state.format, neue.ready) {
            if (neue.ready) PlayChoice.settledFormat(state.format, p.genesys)?.let(h::setFormat)
        }
        // Where cards are printed, a second opinion (1.1.1): the pool alone called Trap Holic OCG-only a year after its
        // TCG print. Laid over the pool once read; a pool loaded later is built with it already.
        LaunchedEffect(Unit) {
            val regions = h.banlists.regions() ?: return@LaunchedEffect
            state.adoptIndex(h.deps.cardRepository.useRegions(regions.names()))
        }
    }

    run {
        // The deck to open with (kai, 1.0.14): the default, else the one saved last — and
        // only onto an empty builder, so an import made while the library was opening wins.
        LaunchedEffect(Unit) {
            snapshotFlow { neue.ready }.first { it }
            if (state.deckId != null || !state.deck.isEmpty) return@LaunchedEffect
            val id = StartingDeck.pick(h.deps.deckRepository.all().map { it.entry }, neue.prefs.defaultDeckId)
            if (id != null && state.deckId == null && state.deck.isEmpty) state.load(id)
        }
        // The art library takes the pool in its own order, and what is on screen first.
        LaunchedEffect(state.index) { if (state.index.size > 0) h.art.catalogue(state.index.cards) }
        LaunchedEffect(state.deck, state.index) {
            h.art.want(DeckSection.entries.flatMap { state.deck[it] }.distinct().mapNotNull(state.index::byId))
        }
        LaunchedEffect(state.results) { h.art.want(state.results.take(48)) }
        // Ai off (Settings → Assistant): every trace gone — the menu's item, the key, and
        // anything running or listening (1.0.43).
        LaunchedEffect(neue.prefs.ai.enabled) {
            DeskMenuBar.aiShown = neue.prefs.ai.enabled
            if (!neue.prefs.ai.enabled) h.ai.shutDown()
        }
        LaunchedEffect(neue.prefs.ai.name) { DeskMenuBar.aiName = neue.prefs.ai.name }
        // Study a course: a study left going when the app closed goes on, and one finished meanwhile is offered for review.
        LaunchedEffect(neue.ready, neue.prefs.ai.enabled) {
            if (neue.ready && neue.prefs.ai.enabled && com.kaiharimoto.neue.ai.AiState.PHASE >= 3) h.ai.courses.reopen()
        }
        LaunchedEffect(neue.inspected) { neue.inspected?.let(h.art::want) }
        // As the app opens (1.0.69): a backup first when this version is new here, then the setup still to do.
        LaunchedEffect(neue.ready) {
            if (!neue.ready) return@LaunchedEffect
            h.backups.onOpen()
            h.offerStart()
        }
        // Sync (1.0.68): once everything is read, then every few minutes while the app is open…
        LaunchedEffect(neue.ready, neue.prefs.sync.service, neue.prefs.sync.auto) {
            if (!neue.ready || !neue.prefs.sync.auto) return@LaunchedEffect
            snapshotFlow { h.webs.loaded && h.prep.loaded }.first { it }
            while (true) {
                h.sync.syncNow(quiet = true)
                kotlinx.coroutines.delay(SyncCenter.EVERY_MS)
            }
        }
        // …and a little after anything that travels changes: a deck saved, a setting, a web, prep, Ai's notes.
        LaunchedEffect(neue.ready) {
            if (!neue.ready) return@LaunchedEffect
            h.sync.followChanges()
        }
        // The ~2 GB library waits for Wi-Fi on a tablet (touch swarm, rec 27); looked at again each half minute.
        LaunchedEffect(neue.prefs.hdArt) {
            while (true) {
                val free = Platform.onUnmeteredNetwork()
                neue.waitingForWifi = neue.prefs.hdArt && !free
                h.art.enable(neue.prefs.hdArt && free)
                if (!neue.prefs.hdArt) break
                kotlinx.coroutines.delay(30_000)
            }
        }
    }
}
