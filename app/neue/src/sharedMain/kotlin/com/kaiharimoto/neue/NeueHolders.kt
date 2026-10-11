package com.kaiharimoto.neue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kaiharimoto.neue.field.FieldCache
import com.kaiharimoto.neue.versions.DeckVersionStore
import com.kaiharimoto.mastertool.core.deck.DeckVersions
import com.kaiharimoto.neue.lounge.LoungeCenter
import com.kaiharimoto.neue.duel.NeueTableHost
import com.kaiharimoto.neue.duel.TableHost
import com.kaiharimoto.neue.mapper.Mappers
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.deck.PlayChoice
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusManager
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.input.ActionEcho
import com.kaiharimoto.mastertool.core.input.BackChain
import com.kaiharimoto.mastertool.core.input.BackFlags
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.Unwind
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.mastertool.core.start.StartPrefs
import com.kaiharimoto.mastertool.core.start.StartSteps
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckLayoutState
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.art.ArtLibrary
import com.kaiharimoto.neue.art.CustomArt
import com.kaiharimoto.neue.backup.BackupCenter
import com.kaiharimoto.neue.builder.NeueDrag
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cursor.FamilyCursor
import com.kaiharimoto.neue.mapper.dismissMapper
import com.kaiharimoto.neue.duel.DuelVoice
import com.kaiharimoto.neue.duel.Ladders
import com.kaiharimoto.neue.duel.Reviews
import com.kaiharimoto.neue.duel.Series
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.duel.TurnSolver
import com.kaiharimoto.neue.duel.SearchSeat
import com.kaiharimoto.neue.duel.flushNow
import com.kaiharimoto.neue.duel.duelContext
import com.kaiharimoto.neue.kit.Overlays
import com.kaiharimoto.neue.kit.TextFocus
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.prep.Prep
import com.kaiharimoto.neue.present.Presentations
import com.kaiharimoto.neue.shot.DeckShots
import com.kaiharimoto.neue.sync.SyncCenter
import com.kaiharimoto.neue.update.NeueUpdates
import com.kaiharimoto.neue.web.Webs
import com.kaiharimoto.neue.banlist.BanlistCenter
import com.kaiharimoto.neue.shootout.Shootouts
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.neue.effects.Effects
import com.kaiharimoto.neue.shootout.dismissShootout
import com.kaiharimoto.neue.world.WorldSnapshot
import com.kaiharimoto.neue.world.Worlds
import com.kaiharimoto.neue.zen.ZenLayer
import kotlinx.coroutines.flow.first

/** The window's state holders, remembered together so the key handler and the tree share them. */
class NeueHolders(
    val deps: AppDependencies,
    val builder: DeckBuilderState,
    val layout: DeckLayoutState,
    val neue: NeueState,
    val drag: NeueDrag,
    val updates: NeueUpdates,
    val art: ArtLibrary,
    val shots: DeckShots,
    /** The webs of decks, for Format and the builder's switcher (1.0.33). */
    val webs: Webs,
    /** Tournament prep (1.0.50): events, the test games log, drills. */
    val prep: Prep,
) {
    /** Keys down now; the key handler itself is `NeueKeys.kt`. */
    internal val held = mutableSetOf<androidx.compose.ui.input.key.Key>()
    var focus: FocusManager? = null

    /** Zen's amounts and clock, shared with everything that fades or floats. */
    val zen = ZenLayer()

    /** Pictures the person added to cards themselves (1.0.18). */
    val customArt = CustomArt(java.io.File(Platform.dataDir, "custom-art")).also { neue.customArt = it }

    /** Present (1.0.70): the presentations, the one open in the editor, and the one playing. */
    private val presentHolder = lazy { Presentations(java.io.File(Platform.dataDir, "present")) }
    val present: Presentations by presentHolder

    /** The presentation being made written now, when Present was opened: the app closing (the editor's audit, B12). */
    fun flushPresent() {
        if (presentHolder.isInitialized()) present.flushNow()
    }

    /** Duel (1.0.74): the duel in play, its table, its log; kept in `<data>/duel/`. */
    private val duelHolder = lazy {
        Duels(java.io.File(Platform.dataDir, "duel")).also { d ->
            d.context = { duelContext(this) }
            // Shortcut at the table (Phase D §5½): the effects library's book and the pool's facts, rebuilt when the book
            // moves on (`Effects.revision`) or the pool changes; none written, nothing offered.
            var memo: Pair<Pair<Int, Any>, Shortcuts?>? = null
            // "Played by you" (Phase D step 4): a Shortcut the person made and kept marks its card; an undo takes it back.
            d.shortcutPart.onPlayed = { uses, kept -> effects.played(uses, kept) }
            // Cards that play themselves (Phase D §5½ 4, `DuelPrefs.autoEffects`): a script plays itself once trusted, with no
            // open warning, and played by you (`FxTrust.playsItself`); read again when the library or the marks move on.
            var trustMemo: Pair<Triple<Int, Any, Any>, (Int) -> Boolean>? = null
            d.autoPlays = {
                if (!neue.prefs.duel.autoEffects) null
                else {
                    val e = effects
                    val key = Triple(e.revision, e.played as Any, builder.index as Any)
                    trustMemo?.takeIf { it.first == key }?.second
                        ?: e.trust().let { t -> { code: Int -> t.playsItself(code) } }.also { trustMemo = key to it }
                }
            }
            d.writtenEffects = {
                val e = effects
                val key = e.revision to (builder.index as Any)
                memo?.takeIf { it.first == key }?.second
                    ?: (if (e.book.size == 0) null else Shortcuts.written(e.book, e.facts(), names = e.names()))
                        .also { memo = key to it }
            }
        }
    }
    val duel: Duels by duelHolder

    /** Whether the duel holder exists this run (Phase C stage 3: a World's fork reads the page's duel, else the one on disk). */
    val duelStarted: Boolean get() = duelHolder.isInitialized()

    /** Ai World (1.0.97): Ai's own computer — its files, runs and boards — in `<data>/world/`. */
    private val worldHolder = lazy {
        Worlds(java.io.File(Platform.dataDir, "world")).also { w ->
            w.host = { WorldSnapshot.of(this) }
            w.prefs = { neue.prefs.world }
            w.comeForward = { if (neue.page != Page.WORLD) neue.go(Page.WORLD) }
            // What the person is doing as Ai arrives (DESKTOP.md §6.1): typing, or a menu, a dialog or the palette open.
            w.desk.person = {
                com.kaiharimoto.mastertool.core.world.desk.PersonState(
                    typing = textFocus.any,
                    overlay = neue.hasTop || overlays.isOpen || w.desk.launcherOpen || w.desk.dialog != null,
                    now = System.currentTimeMillis(),
                )
            }
            // The effects library, at lib/effects/ in every world (Phase D step 2).
            w.mounts += effects.mount
            w.load()
        }
    }
    val world: Worlds by worldHolder

    /** Whether Ai World has been opened this run: what only touches it when it exists asks this first. */
    val worldStarted: Boolean get() = worldHolder.isInitialized()

    /** Shootout (1.1.2, Phase S): hands judged, cards rated; its trials in `<data>/shootout/<deck>/`. */
    private val shootoutHolder = lazy { Shootouts(Platform.dataDir, this) }
    val shootout: Shootouts by shootoutHolder

    /** Whether Shootout has been opened this run. */
    val shootoutStarted: Boolean get() = shootoutHolder.isInitialized()

    /** Gameplay Mapper (Phase M): the open deck's board libraries, runs and starter tables in `<data>/effects/mapper/<deck>/`. */
    private val mapperHolder = lazy { Mappers(java.io.File(Platform.dataDir, FxPaths.FOLDER)) }
    val mapper: Mappers by mapperHolder

    /** Whether the mapper has been opened this run. */
    val mapperStarted: Boolean get() = mapperHolder.isInitialized()

    /**
     * Effects as code (Phase D step 2): the library of written effects in `<data>/effects/`, compiled, checked, and the
     * book the engine and the table read; mounted in every world at `lib/effects/`. `<data>/fxcache/` is this device's alone.
     */
    private val effectsHolder = lazy {
        Effects.under(Platform.dataDir).also { e ->
            e.pool = { builder.index }
            e.onChange = { if (worldStarted) world.refreshListing() }
            e.load()
        }
    }
    val effects: Effects by effectsHolder

    /** Whether the effects library has been read this run: a sync or a restore reloads it only then. */
    val effectsStarted: Boolean get() = effectsHolder.isInitialized()

    /** Command mode's voice (1.0.87): hold M, or the microphone beside the command line, to speak a move. */
    val duelVoice: DuelVoice by lazy { DuelVoice(this) }
    /** The duel table's view of the app (`TableHost`): what the table reaches, here and in the Lounge's browser. */
    val table: TableHost by lazy { NeueTableHost(this) }
    /** The Lounge (`docs/LOUNGE.md`): friends duel from a browser on this computer. Opened from Settings › Lounge. */
    val lounge: LoungeCenter by lazy { LoungeCenter(this) }

    /** The duel in play written now, when there is one: the app closing (1.0.85; the last moves were lost in the save's debounce). */
    fun flushDuel() {
        if (duelHolder.isInitialized()) duel.flushNow()
    }

    /**
     * Every Forbidden & Limited list by date (1.1.1): read from Yugipedia into `<data>/banlists/`, a cache this device
     * keeps for itself — for Ai's `banlist`, `validate_deck`'s `as_of` and a world's `ygo.banlist`.
     */
    val banlists: BanlistCenter by lazy { BanlistCenter(java.io.File(Platform.dataDir, "banlists")) }

    /** Backups (1.0.69): made when a new version first opens and weekly; exported, restored. */
    val backups: BackupCenter by lazy { BackupCenter(this) }

    /** Solve this turn (Phase E, E.2): the best whole turn for the seat whose turn it is, searched off the frame thread. */
    val solver: TurnSolver by lazy { TurnSolver(this) }

    /** Search in Ai's seat at kai's own table (Phase E, E.3b). */
    val searchSeat: SearchSeat by lazy { SearchSeat(this) }

    /** The ladder (Phase E, E.4): the app's own players rated on the bench, `<data>/duel/ladder.json`. */
    private val ladderHolder = lazy { Ladders(java.io.File(Platform.dataDir, "duel")) }
    val ladder: Ladders by ladderHolder

    /** Whether the ladder was ever read this run (sync reads it again only then). */
    val ladderStarted: Boolean get() = ladderHolder.isInitialized()

    /** Reviews of replays (Phase F, F.1): the misplay finder's books, `<data>/duel/coach/`. */
    private val reviewsHolder = lazy { Reviews(this) }
    val reviews: Reviews by reviewsHolder

    /** Whether reviews were ever read this run (sync reads them again only then). */
    val reviewsStarted: Boolean get() = reviewsHolder.isInitialized()

    /** Simulated series (Phase G part two, G2.1): versions of a deck compared by matches, `<data>/duel/series/`. */
    private val seriesHolder = lazy { Series(this) }
    val series: Series by seriesHolder

    /** Whether series were ever read this run (sync reads them again only then). */
    val seriesStarted: Boolean get() = seriesHolder.isInitialized()

    /** The field as last read (Phase G, G.5): `<data>/field/`, this device's cache of tournament lists. */
    val field: FieldCache by lazy { FieldCache(java.io.File(Platform.dataDir, "field"), this) }

    /** Every deck's versions (Phase G, G.8): `<data>/deckversions/<deck>/<print>.json`, synced and backed up. */
    val versions: DeckVersionStore by lazy { DeckVersionStore(java.io.File(Platform.dataDir, DeckVersions.FOLDER)) }

    /** Whether the library held a deck as the app opened: someone new has none (1.0.69, the setup). */
    var decksKnown = false

    /** Offers the setup: what is still to do since the version last opened here, or with [again] every step not done yet. */
    suspend fun offerStart(again: Boolean = false) {
        decksKnown = deps.deckRepository.hasAny()
        val state = com.kaiharimoto.neue.start.startState(this)
        val android = Platform.os == DesktopOs.ANDROID
        val steps = if (again) {
            StartSteps.pending(Platform.version, StartPrefs(), state, android)
        } else {
            StartSteps.pending(Platform.version, neue.prefs.start, state, android)
        }
        if (steps.isEmpty()) {
            if (neue.prefs.start.seen != Platform.version) neue.update { it.copy(start = it.start.copy(seen = Platform.version)) }
        } else {
            neue.startSteps = steps
        }
    }

    /** Sync across devices (1.0.68): where to, what the last sync did, signing in. */
    val sync: SyncCenter by lazy { SyncCenter(this) }

    /** The assistant (Ai, 1.0.43): the conversation, its model and its tools, for the app's lifetime. */
    val ai: AiState by lazy { AiState(this) }

    /** The family pointer, Crop caption: one per window. */
    val cursor = FamilyCursor()

    /** Anchored surfaces in the window's own layer (a select's list), under the cursor. */
    val overlays = Overlays()

    /** Bumped by the Z key: zen, now (`ZenClockwork` carries it out). */
    var zenRequest by mutableStateOf(0)
    var zenWaitsForLayout = false

    /**
     * The last Z carried out. Plain: the clockwork reads it when [zenRequest] moves.
     * A tree composed afresh — a window swapped in for immersive mode on Windows —
     * would otherwise read a Z pressed long ago as pressed now, and go straight to zen.
     */
    var zenHandled = 0

    /** When the person last did anything, in `System.nanoTime`. */
    var lastInput = System.nanoTime()

    /** Whether idleness deepens into zen by itself. The studio turns it off and sets the phase by hand. */
    var zenAuto = true

    /** Something happened: zen, if it had begun, ends. Returns the phase it woke from. */
    fun wake(): ZenPhase {
        lastInput = System.nanoTime()
        val was = neue.zen
        if (was != ZenPhase.AWAKE) neue.zen = ZenPhase.AWAKE
        if (was == ZenPhase.DEEP) zen.forget()
        return was
    }
    var decksReload by mutableStateOf(0)

    fun setFormat(format: Format) {
        builder.onFormatChange(format)
        layout.update { it.copy(format = format) }
    }

    /**
     * What the person plays (the 1.1.2 design review, finding 3): the bar's `TCG | OCG | Genesys`. Genesys turns the
     * Genesys switch on and keeps the stored region TCG, so an older build reads a valid TCG; TCG or OCG turns it off.
     */
    fun setPlay(choice: PlayChoice) {
        if (builder.format != choice.format) setFormat(choice.format)
        if (neue.prefs.genesys != choice.genesys) neue.update { it.copy(genesys = choice.genesys) }
    }

    /** What the bar shows now. */
    val play: PlayChoice get() = PlayChoice.of(builder.format, neue.prefs.genesys)

    fun setSearchEffects(on: Boolean) {
        builder.onSearchEffectsChange(on)
        layout.update { it.copy(searchEffects = on) }
    }

    /** Keys down for a held row (1.0.87), and the action each one started. */
    internal val holding = mutableMapOf<androidx.compose.ui.input.key.Key, DeskAction>()

    /** Every kit text field's focus, reported by the fields themselves (touch swarm, rec 6). */
    val textFocus = TextFocus()

    internal val echo = ActionEcho()

    /**
     * [id] on the builder, the deck there saved first (1.0.33: "saved as you
     * switch"): the web's switcher and the Format page's tiles both come here. A deck
     * never saved and holding cards is saved to the library rather than dropped.
     */
    fun openDeck(id: String) {
        val state = builder
        neue.go(Page.BUILDER)
        if (id == state.deckId) return
        if (state.dirty && (state.deckId != null || !state.deck.isEmpty)) {
            state.save(quiet = true) {
                decksReload++
                state.load(id)
            }
        } else {
            state.load(id)
        }
    }

    /** The deck [step] along in the web of the deck on the builder: `Alt ←`/`Alt →`, and the bar's ‹ ›. */
    fun stepWeb(step: Int) {
        val id = builder.deckId ?: return
        val next = webs.webOf(id)?.neighbour(id, step) ?: return
        openDeck(next)
    }

    /**
     * The Groups button (1.0.15): the Roles lens and the panel beside the deck,
     * together — on, the deck breaks into its groups and they can be edited; off,
     * it is the plain deck again, with no gaps and no colour.
     */
    fun setGroups(on: Boolean) {
        val state = builder
        if (on) {
            state.useLens(Lens.ROLES)
        } else {
            state.cancelGroupDraft()
            state.useLens(Lens.DECK)
        }
    }

    /** The foil on every card face, on or off (the shiny button beside Groups, 1.0.15). */
    fun toggleFoil() = neue.update { it.copy(foil = if (it.foil == Foils.OFF) Foils.HOLO else Foils.OFF) }

    /** What is open, as `BackChain` reads it (touch swarm, rec 2): Esc and Android's Back share one chain. */
    private fun backFlags() = BackFlags(
        updateDialog = updates.dialogOpen,
        overlay = overlays.isOpen,
        top = neue.hasTop,
        coverPicker = neue.coverPicking != null,
        goal = builder.editingGoal != null,
        draft = builder.groupDraft != null,
        focus = textFocus.any || builder.textInputFocused || neue.searchFocused,
        // Siding is a page of its own (1.0.40): Back leaves it as it leaves any page.
        siding = false,
        palettes = neue.groupPalettesOpen,
        isolation = builder.isolatedKey != null,
        selection = neue.selection != null,
        immersive = neue.immersive,
        offBuilder = neue.page != Page.BUILDER,
    )

    /** Esc unwinds one layer at a time, from the top: overlays, then modes, then focus, then selection. */
    internal fun dismiss() {
        // Chessy's takeover, over everything: Esc skips to Ai's question, then answers it as it was
        if (takeoverPlaying) { takeoverBack(); return }
        // Chessy's petting mode, over everything: she goes back to the chat box first
        if (amieOpen && ai.giftDrawer) { ai.giftDrawer = false; return }
        if (amieOpen) { ai.closeAmie(); return }
        if (com.kaiharimoto.neue.present.dismissPresent(this, esc = true)) return
        // The Spotlight closes first, its field with it (1.0.87).
        if (neue.page == Page.DUEL && duel.spotlight != null && com.kaiharimoto.neue.duel.dismissDuel(table)) return
        // On the Duel page Esc first lets go of the command line or the chat (1.0.78), so the keys go back to the table.
        if (neue.page == Page.DUEL && textFocus.any) { focus?.clearFocus(); return }
        if (com.kaiharimoto.neue.duel.dismissDuel(table)) return
        if (com.kaiharimoto.neue.world.dismissWorld(this)) return
        if (dismissShootout(this)) return
        if (dismissMapper(this)) return
        BackChain.esc(backFlags())?.let(::unwind)
    }

    /** Whether Back has anything to close; with nothing, the system's own back (and its predictive preview) is right. */
    /** Chessy's petting mode is open (read without making the assistant when it was never opened). */
    private val amieOpen: Boolean get() = neue.prefs.ai.persona == com.kaiharimoto.mastertool.core.prefs.AiPrefs.PERSONA_CHESSY && ai.amie != null

    /** Chessy's takeover is playing (read without making the assistant when it never played). */
    private val takeoverPlaying: Boolean get() = neue.prefs.ai.enabled && ai.takeovers.run != null

    private fun takeoverBack() {
        if (ai.takeovers.now() < com.kaiharimoto.mastertool.core.ai.chessy.Takeover.AI_ON) ai.takeovers.skip() else ai.takeovers.dismiss()
    }

    fun canGoBack(): Boolean = takeoverPlaying || amieOpen || present.playing != null || (neue.page == Page.PRESENT && present.open != null) || BackChain.back(backFlags()) != null

    /** Android's Back: one layer, as Esc — never focus or the selection. Returns false when there was nothing. */
    fun back(): Boolean {
        wake()
        if (takeoverPlaying) { takeoverBack(); return true }
        if (amieOpen && ai.giftDrawer) { ai.giftDrawer = false; return true }
        if (amieOpen) { ai.closeAmie(); return true }
        if (com.kaiharimoto.neue.present.dismissPresent(this, esc = false)) return true
        if (com.kaiharimoto.neue.duel.dismissDuel(table)) return true
        if (com.kaiharimoto.neue.world.dismissWorld(this)) return true
        if (dismissShootout(this)) return true
        if (dismissMapper(this)) return true
        val step = BackChain.back(backFlags()) ?: return false
        unwind(step)
        return true
    }

    private fun unwind(step: Unwind) {
        val state = builder
        when (step) {
            Unwind.UPDATE_DIALOG -> updates.dialogOpen = false
            Unwind.OVERLAY -> overlays.dismiss()
            Unwind.TOP -> neue.dismissTop()
            Unwind.COVER_PICKER -> neue.coverPicking = null
            Unwind.GOAL -> state.cancelGoal()
            Unwind.DRAFT -> state.cancelGroupDraft()
            Unwind.FOCUS -> focus?.clearFocus()
            Unwind.SIDING -> {
                webs.sidingDeckId = null
                webs.sidingAgainst = null
            }
            Unwind.PALETTES -> neue.groupPalettesOpen = false
            Unwind.ISOLATION -> state.isolatedKey?.let(state::toggleIsolation)
            Unwind.SELECTION -> neue.selection = null
            Unwind.IMMERSIVE -> run(DeskAction.IMMERSIVE)
            Unwind.TO_BUILDER -> neue.go(Page.BUILDER)
        }
    }
}

@Composable
fun rememberHolders(deps: AppDependencies, makeUpdates: (kotlinx.coroutines.CoroutineScope) -> NeueUpdates): NeueHolders {
    val scope = rememberCoroutineScope()
    return remember {
        val builder = DeckBuilderState(deps, scope)
        val art = ArtLibrary(java.io.File(Platform.dataDir, "card-art-hd"), scope)
        NeueHolders(
            deps = deps,
            builder = builder,
            layout = DeckLayoutState(deps.preferencesRepository, scope),
            neue = NeueState(deps.preferencesRepository, scope),
            drag = NeueDrag(builder),
            updates = makeUpdates(scope),
            art = art,
            shots = DeckShots(art, scope),
            webs = Webs(deps, scope),
            prep = Prep(deps, scope),
        )
    }
}
