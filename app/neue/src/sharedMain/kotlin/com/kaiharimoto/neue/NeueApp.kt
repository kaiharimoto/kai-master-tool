package com.kaiharimoto.neue

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import com.kaiharimoto.neue.effects.LocalEffectsHolders
import com.kaiharimoto.mastertool.core.deck.PlayChoice
import com.kaiharimoto.neue.builder.legalityRules
import com.kaiharimoto.neue.builder.eventForRules
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.data.PoolProgress
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.haptics.DeskEvent
import com.kaiharimoto.mastertool.core.input.ActionEcho
import com.kaiharimoto.mastertool.core.input.BackChain
import com.kaiharimoto.mastertool.core.input.BackFlags
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskMenuBar
import com.kaiharimoto.mastertool.core.input.DeskWords
import com.kaiharimoto.mastertool.core.input.Unwind
import com.kaiharimoto.mastertool.core.layout.FormFactor
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.mastertool.core.layout.Posture
import com.kaiharimoto.mastertool.core.library.StartingDeck
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.motion.Tilt
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.mastertool.core.offline.Offline
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.start.StartPrefs
import com.kaiharimoto.mastertool.core.start.StartSteps
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.configureImageLoader
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckLayoutState
import com.kaiharimoto.neue.ai.AiPanel
import com.kaiharimoto.neue.ai.AiSetupScreen
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.ContextPanel
import com.kaiharimoto.neue.ai.LivingDocDialog
import com.kaiharimoto.neue.ai.MemoryDialog
import com.kaiharimoto.neue.ai.PictureDialog
import com.kaiharimoto.neue.ai.ProfileLauncher
import com.kaiharimoto.neue.ai.QuickSettings
import com.kaiharimoto.neue.ai.ReviewDialog
import com.kaiharimoto.neue.ai.TuneLauncher
import com.kaiharimoto.neue.ai.VoiceDialog
import com.kaiharimoto.neue.ai.avatar.AiBadge
import com.kaiharimoto.neue.ai.avatar.AiFaceClock
import com.kaiharimoto.neue.ai.TrustDialog
import com.kaiharimoto.neue.ai.carryLearning
import com.kaiharimoto.neue.ai.foldIntoWeb
import com.kaiharimoto.neue.ai.forgetEverything
import com.kaiharimoto.neue.ai.reader.BookReader
import com.kaiharimoto.neue.ai.shutDown
import com.kaiharimoto.neue.art.ArtCropDialog
import com.kaiharimoto.neue.art.ArtLibrary
import com.kaiharimoto.neue.art.CustomArt
import com.kaiharimoto.neue.art.LocalArt
import com.kaiharimoto.neue.art.LocalCustomArt
import com.kaiharimoto.neue.backup.BackupCenter
import com.kaiharimoto.neue.builder.BuilderBar
import com.kaiharimoto.neue.builder.BuilderPage
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.builder.CardViewer
import com.kaiharimoto.neue.builder.CarriedCard
import com.kaiharimoto.neue.builder.NeueDrag
import com.kaiharimoto.neue.builder.SearchStudio
import com.kaiharimoto.neue.builder.rememberCarryMotion
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.LocalArtStep
import com.kaiharimoto.neue.cards.LocalArts
import com.kaiharimoto.neue.cards.LocalCardFoil
import com.kaiharimoto.neue.cards.LocalLimitMarks
import com.kaiharimoto.neue.cards.LocalNameStyle
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.CursorLayer
import com.kaiharimoto.neue.cursor.FamilyCursor
import com.kaiharimoto.neue.cursor.LocalCursor
import com.kaiharimoto.neue.duel.DuelBarItems
import com.kaiharimoto.neue.shootout.ShootoutBarItems
import com.kaiharimoto.neue.duel.DuelPage
import com.kaiharimoto.neue.duel.DuelVoice
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.duel.duelContext
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.LocalDeviceTilt
import com.kaiharimoto.neue.kit.LocalHardwareKeyboard
import com.kaiharimoto.neue.kit.LocalKeepCase
import com.kaiharimoto.neue.kit.LocalOverlays
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.LocalReasonNote
import com.kaiharimoto.neue.kit.LocalTextFocus
import com.kaiharimoto.neue.kit.LocalTilt
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MenuLayer
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.OverlayLayer
import com.kaiharimoto.neue.kit.Overlays
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.ProvideTextMenus
import com.kaiharimoto.neue.kit.TextFocus
import com.kaiharimoto.neue.kit.ToastBox
import com.kaiharimoto.neue.pages.DecksPage
import com.kaiharimoto.neue.pages.FormatPage
import com.kaiharimoto.neue.pages.SettingsHost
import com.kaiharimoto.neue.pages.SettingsPage
import com.kaiharimoto.neue.pages.SidingPage
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.reportIssue
import com.kaiharimoto.neue.prep.Prep
import com.kaiharimoto.neue.prep.PrepPage
import com.kaiharimoto.neue.present.PresentOverlay
import com.kaiharimoto.neue.present.PresentPage
import com.kaiharimoto.neue.present.Presentations
import com.kaiharimoto.neue.qr.QrDialog
import com.kaiharimoto.neue.shell.CommandPalette
import com.kaiharimoto.neue.shell.Drawers
import com.kaiharimoto.neue.shell.FoldedBars
import com.kaiharimoto.neue.shell.FrameMeter
import com.kaiharimoto.neue.shell.HelpDialog
import com.kaiharimoto.neue.shell.PhoneBar
import com.kaiharimoto.neue.shell.Rail
import com.kaiharimoto.neue.shell.ShellStatus
import com.kaiharimoto.neue.shell.TabBar
import com.kaiharimoto.neue.shell.TitleBar
import com.kaiharimoto.neue.shell.windowPointer
import com.kaiharimoto.neue.shot.DeckShots
import com.kaiharimoto.neue.start.StartScreen
import com.kaiharimoto.neue.sync.SyncCenter
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.theme.MuShell
import com.kaiharimoto.neue.theme.MuTheme
import com.kaiharimoto.neue.update.NeueUpdates
import com.kaiharimoto.neue.web.Webs
import com.kaiharimoto.neue.banlist.BanlistCenter
import com.kaiharimoto.neue.world.WorldBarItems
import com.kaiharimoto.neue.world.WorldPage
import com.kaiharimoto.neue.world.desk.WorldPhoneTitle
import com.kaiharimoto.neue.shootout.ShootoutPage
import com.kaiharimoto.neue.shootout.Shootouts
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.neue.effects.Effects
import com.kaiharimoto.neue.shootout.dismissShootout
import com.kaiharimoto.neue.world.WorldSnapshot
import com.kaiharimoto.neue.world.Worlds
import com.kaiharimoto.neue.zen.LocalZen
import com.kaiharimoto.neue.zen.ZenLayer
import com.kaiharimoto.neue.zen.ZenReset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        if (neue.page == Page.DUEL && duel.spotlight != null && com.kaiharimoto.neue.duel.dismissDuel(this)) return
        // On the Duel page Esc first lets go of the command line or the chat (1.0.78), so the keys go back to the table.
        if (neue.page == Page.DUEL && textFocus.any) { focus?.clearFocus(); return }
        if (com.kaiharimoto.neue.duel.dismissDuel(this)) return
        if (com.kaiharimoto.neue.world.dismissWorld(this)) return
        if (dismissShootout(this)) return
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
        if (com.kaiharimoto.neue.duel.dismissDuel(this)) return true
        if (com.kaiharimoto.neue.world.dismissWorld(this)) return true
        if (dismissShootout(this)) return true
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

/**
 * The window's content: title bar, rail, the page, and every layer above it —
 * drawers, dialogs, the palette, the menu, the card in the air, the toast.
 *
 * [launchEffects] brings the app's own lifetime with it ([NeueEffects]): right for
 * the tablet's one activity. A host that swaps windows — the desktop, for immersive
 * mode on Windows — composes [NeueEffects] once, outside its windows, and passes
 * false, so a window swapped in does not start the app over.
 */
@Composable
fun NeueRoot(h: NeueHolders, launchEffects: Boolean = true) {
    val focus = LocalFocusManager.current
    h.focus = focus
    // The window losing the keyboard ends what a held key started (1.0.87): its key-up will never come.
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) h.keysLost() }

    if (launchEffects) NeueEffects(h)

    NeueWindowContent(h)
}

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
            h.webs.onCopied = { from, to -> if (neue.prefs.ai.enabled) h.ai.carryLearning(from, to) }
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

@Composable
private fun NeueWindowContent(h: NeueHolders) {
    val neue = h.neue
    // The groups' palette: read wherever a group is coloured, so set once here.
    SideEffect { GroupMarkers.palette = GroupMarkers.byId(neue.prefs.groupPalette) }
    val base = LocalDensity.current
    // What the app is running on and which way round (the phone, v1.3.5): the window's size
    // in physical dp, before the interface scale — a phone does not become a tablet by zoom.
    Box(
        Modifier.fillMaxSize().onSizeChanged { px ->
            val w = px.width / base.density
            val h2 = px.height / base.density
            neue.form = neue.formOverride ?: FormFactor.of(w, h2, neue.touchFirst)
            neue.posture = Posture.of(w, h2)
        },
    ) {
    // The foil follows the phone's tilt (v1.3.6), while it is on and there is foil to light.
    val tilt = com.kaiharimoto.neue.kit.rememberDeviceTilt(
        on = neue.touchFirst && neue.prefs.foilTilt && neue.prefs.foil != Foils.OFF,
    )
    // Under the full-screen card the light holds where it was (1.0.92): nobody sees those cards until it closes.
    val shownTilt = remember(tilt) {
        var held: Tilt? = null
        derivedStateOf { if (neue.showcaseCovers) held else tilt.value.also { held = it } }
    }
    CompositionLocalProvider(LocalTilt provides shownTilt, LocalCardFoil provides neue.prefs.foil, LocalDeviceTilt provides tilt, LocalDensity provides Density(base.density * neue.prefs.scale, base.fontScale * neue.prefs.textScaleOn(neue.touchFirst, neue.phone)), LocalArt provides h.art, LocalNameStyle provides neue.prefs.foilNames, LocalLimitMarks provides neue.prefs.limitMarks, LocalZen provides h.zen, LocalCursor provides h.cursor, LocalOverlays provides h.overlays, LocalTouchFirst provides neue.touchFirst, LocalPhone provides neue.phone, LocalKeepCase provides (if (neue.prefs.ai.enabled) setOf(neue.prefs.ai.name.ifBlank { "Ai" }, "Ai", com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME) else emptySet()), com.kaiharimoto.neue.ai.chessy.LocalChessy provides (if (neue.prefs.ai.persona == com.kaiharimoto.mastertool.core.prefs.AiPrefs.PERSONA_CHESSY) remember(h.ai) { com.kaiharimoto.neue.ai.chessy.ChessyLook { h.ai.streaming.isNotEmpty() } } else null), LocalTextFocus provides h.textFocus, LocalHardwareKeyboard provides (!neue.touchFirst || neue.hardwareKeyboard), LocalReasonNote provides { reason: String -> neue.note = Note(reason) }, LocalArts provides neue.prefs.arts, LocalArtStep provides { card: com.kaiharimoto.mastertool.core.model.Card, by: Int ->
        neue.stepArt(card, by)
        // A finger stepping a card's art feels it turn over (touch swarm, rec 13).
        neue.actingBy(finger = neue.touchFirst) { neue.felt(DeskEvent.ART_STEPPED) }
    }, LocalCustomArt provides h.customArt, LocalEffectsHolders provides h) {
        MuTheme(ink = neue.prefs.theme == NeueTheme.INK, high = neue.prefs.contrast == NeuePreferences.CONTRAST_HIGH) {
            ProvideTextMenus {
                Shell(h)
            }
        }
    }
    }
}

@Composable
private fun Shell(h: NeueHolders) {
    val neue = h.neue
    val state = h.builder
    val c = Mu.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val immersive = neue.immersive
    // The tablet's haptics: nothing on the desk (touch swarm, rec 13).
    neue.feel = com.kaiharimoto.neue.kit.rememberFeel()
    val pinned = neue.railPinned && !immersive
    // How tall the folded bars are when out, measured, so the pointer knows when it has left them.
    val measured = remember { FoldedBars() }

    val status = when {
        state.isSyncing -> ShellStatus("Syncing card pool", running = true)
        state.index.size == 0 -> ShellStatus("No card pool", running = false)
        else -> null
    }
    val titleBar: @Composable () -> Unit = {
        // What is being fetched, and how far it has got (kai: "a progress bar indicating if the
        // program is downloading images or updating the card pool"). Read here, in the bar's
        // own scope, so the art's arrivals redraw the bar and not the window.
        val work = Offline.readout(
            pool = state.poolProgress ?: if (state.isSyncing) PoolProgress.Asking else null,
            art = h.art.count,
            artRunning = h.art.running && neue.prefs.hdArt,
            problem = h.art.problem,
        )
        if (neue.phone) {
            PhoneBar(
                neue = neue,
                state = state,
                update = h.updates.available?.versionName,
                onUpdate = { h.updates.dialogOpen = true },
                menu = { h.phoneMenu(it) },
                working = work != null,
                // On the World page the bar's face is the avatar's home, gone while it works in the app on screen (§5.6).
                ai = if (neue.prefs.ai.enabled && (neue.page != Page.WORLD || com.kaiharimoto.neue.world.desk.worldFaceHome(h))) {
                    { _ -> AiBadge(h, height = 40.dp) }
                } else {
                    null
                },
                title = if (neue.page == Page.WORLD) {
                    { WorldPhoneTitle(h) }
                } else {
                    null
                },
            )
        } else TitleBar(
            neue = neue,
            update = h.updates.available?.versionName,
            onUpdate = { h.updates.dialogOpen = true },
            onImmersive = { h.run(DeskAction.IMMERSIVE) },
            work = work,
            onWork = { neue.go(Page.SETTINGS) },
            // On the Duel page the bar is the duel's (1.0.78): full screen is in its row, Ai in the log.
            switches = neue.page != Page.DUEL,
            trailing = {
                // On the World page the taskbar's Ai cell is Ai's place: one face on screen (DESKTOP.md §2.2).
                if (neue.prefs.ai.enabled && neue.page != Page.DUEL && neue.page != Page.WORLD) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Ai's face and name in a box on every platform (1.0.63, kai: "on desktop the app is not
                        // the marquee"); its brain is in its panel now, not beside it in the bar.
                        AiBadge(h, height = 32.dp)
                    }
                }
            },
        ) { narrow ->
            if (neue.page == Page.BUILDER) {
                BuilderBar(state, neue, h.play, h::setPlay, onScreenshot = { h.run(DeskAction.SCREENSHOT) }, onSave = { h.run(DeskAction.SAVE) }, narrow = narrow, webs = h.webs, onStepWeb = h::stepWeb, onOpenDeck = h::openDeck)
            } else if (neue.page == Page.DUEL) {
                DuelBarItems(h, narrow)
            } else if (neue.page == Page.WORLD) {
                WorldBarItems(h, narrow)
            } else if (neue.page == Page.SHOOTOUT && h.shootout.running) {
                // A Shootout session's header folds into the bar (design review, 1.1.6), as Duel's does. The page makes the
                // holder anyway; reading it here (never "started?") lets the bar hear the session begin.
                ShootoutBarItems(h, narrow)
            } else {
                Box(Modifier.weight(1f))
            }
        }
    }
    val rail: @Composable () -> Unit = {
        Rail(
            neue = neue,
            version = Platform.version,
            counts = mapOf(Page.BUILDER to state.deck.main.size.toString()),
            status = status,
            art = h.art.progressLine,
        )
    }

    // The soft keyboard put away by its own key or a swipe: typing is over, so the
    // field lets go too, and the keys go back to the deck (touch swarm, rec 10).
    val imeOpen = com.kaiharimoto.neue.kit.softKeyboardVisible()
    var imeWas by remember { mutableStateOf(false) }
    LaunchedEffect(imeOpen) {
        if (imeWas && !imeOpen && neue.touchFirst) h.focus?.clearFocus()
        imeWas = imeOpen
    }

    // The tablet's first run (touch swarm, rec 20): the three things a finger does to a
    // card, once, with the way to the rest.
    LaunchedEffect(neue.ready) {
        if (neue.ready && neue.touchFirst && !neue.prefs.touchIntroSeen) {
            neue.note = Note(DeskWords.TOUCH_INTRO, "All gestures", lastsMs = 12_000) { neue.helpOpen = true }
            neue.update { it.copy(touchIntroSeen = true) }
        }
    }

    ZenClockwork(h)
    AutoSave(h)
    PoolSource(h)
    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .onSizeChanged { h.zen.window = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            // The family cursor draws the pointer; the system's is hidden everywhere in the
            // window, over every child's own icon, unless the cursor has stepped aside.
            // Read through a derived state, so the shell recomposes when it changes, not on every move.
            .pointerHoverIcon(if (h.cursor.nativeNow.value) PointerIcon.Default else FamilyCursor.BLANK, overrideDescendants = true)
            .windowPointer(h, neue, state, measured),
    ) {
        // The app drawn as it is, and held while Chessy's takeover plays: the takeover glitches the live app (kai, 2026-10).
        val takeoverLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
        Box(
            Modifier.fillMaxSize().drawWithContent {
                if (h.ai.takeovers.run != null) {
                    // the window's paper too, which the shell draws outside this box: bare paper must glitch as paper
                    takeoverLayer.record { drawRect(c.paper); this@drawWithContent.drawContent() }
                    h.ai.takeovers.layer = takeoverLayer
                    drawLayer(takeoverLayer)
                } else {
                    drawContent()
                }
            },
        ) {
            // A phone (v1.3.5): the slim bar, and the pages as tabs along the bottom — or,
            // lying down, as a strip down the left, where the height is the deck's.
            val phone = neue.phone
            val phoneTall = phone && neue.posture.isTall
            Column(Modifier.fillMaxSize()) {
                if (!immersive) titleBar()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (phone && !phoneTall && !immersive) {
                        TabBar(neue, vertical = true, onSearch = { neue.paletteOpen = true })
                    } else if (pinned && !phone) rail()
                    Box(Modifier.weight(1f)) {
                        // Siding asked for from anywhere (the builder's web switch, a matchup, the editor's
                        // own deck menu) opens the Siding page (1.0.40).
                        LaunchedEffect(h.webs.sidingAsked) { if (h.webs.sidingAsked > 0) neue.go(Page.SIDING) }
                        // Another deck on the builder: the Siding page sides it, not the one asked for before (1.0.42).
                        LaunchedEffect(state.deckId) { if (h.webs.sidingDeckId != null && h.webs.sidingDeckId != state.deckId) h.webs.sidingDeckId = null }
                        Crossfade(neue.page, animationSpec = tween(MuMotion.PAGE, easing = MuMotion.ease), label = "page") { page ->
                            when (page) {
                                Page.DECKS -> DecksPage(h.deps, state, neue, h.decksReload, hidden = h.webs.library.deckIds, onDuplicated = { from, to -> if (neue.prefs.ai.enabled) h.ai.carryLearning(from, to) })
                                Page.BUILDER -> BuilderPage(state, neue, h.drag, h::setSearchEffects)
                                Page.SIDING -> SidingPage(h.webs, state, neue, h.decksReload, onSave = { h.run(DeskAction.SAVE) })
                                Page.FORMAT -> FormatPage(h.deps, h.webs, state, neue, h.decksReload, onOpenDeck = h::openDeck)
                                Page.PREP -> PrepPage(h.prep, h.webs, state, neue, h.decksReload) { day -> h.legalityRules(neue.prefs.copy(legalAsOf = day), state.format) }
                                Page.PRESENT -> PresentPage(h)
                                Page.DUEL -> DuelPage(h)
                                Page.WORLD -> WorldPage(h)
                                Page.SHOOTOUT -> ShootoutPage(h)
                                Page.SETTINGS -> SettingsPage(
                                    state,
                                    neue,
                                    SettingsHost(
                                        version = Platform.version,
                                        dataDir = Platform.dataDir.absolutePath,
                                        updateStatus = h.updates.status,
                                        checking = h.updates.checking,
                                        onCheckUpdates = { h.updates.check(userInitiated = true) },
                                        onReportIssue = { Platform.reportIssue() },
                                        onOpenDataDir = { Platform.open(Platform.dataDir) },
                                        onSearchEffects = h::setSearchEffects,
                                        art = h.art,
                                        ai = h.ai,
                                        sync = h.sync,
                                        backups = h.backups,
                                        onSetupAgain = { scope.launch { h.offerStart(again = true) } },
                                    ),
                                )
                            }
                        }
                        Drawers(state, neue, eventForRules(h.prep.doc.events, h.prep.doc.activeEvent, state.today))
                    }
                    // Ai's panel (1.0.43): docked beside every page, the page re-fitting beside it —
                    // in immersive mode too (1.0.46); on a phone it is a sheet.
                    if (neue.aiDocked) {
                        AiPanel(h, Modifier.width((neue.prefs.ai.panelWidth / neue.prefs.scale).dp).fillMaxHeight())
                    }
                }
                // Put away while the keyboard is up: the dock's field sits on the keyboard, not on the tabs.
                if (phoneTall && !immersive && !imeOpen) TabBar(neue)
            }

            // The bars that fold away slide over the page rather than pushing it:
            // a bar that pushed would re-fit the deck, and every card would jump.
            val out = neue.revealed.let { if (neue.railHeld) it.copy(left = true) else it }
            if (immersive) {
                val top by animateFloatAsState(if (out.top) 1f else 0f, tween(MuMotion.BASE, easing = MuMotion.ease), label = "top")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { measured.top = it.height }
                        .offset { IntOffset(0, (-(1f - top) * (measured.top + 2)).toInt()) }
                        .background(c.paper),
                ) {
                    titleBar()
                }
            }
            if (!pinned) {
                val left by animateFloatAsState(if (out.left) 1f else 0f, tween(MuMotion.BASE, easing = MuMotion.ease), label = "rail")
                val railPx = with(density) { (if (neue.touchFirst) MuShell.strip else MuShell.rail).roundToPx() }
                if (left > 0.001f) {
                    Box(
                        Modifier
                            .padding(top = if (immersive) 0.dp else MuShell.top)
                            .fillMaxHeight()
                            .offset { IntOffset((-(1f - left) * (railPx + 2)).toInt(), 0) },
                    ) {
                        rail()
                    }
                }
            }

            // The card in the air: drawn where the pointer is, lifted off the page and
            // leaning back against the motion (DeskLean.carried) — kai's one
            // exception to Master UI's stillness, and only ever on a card.
            // A finger's card rides above the finger, where it can be seen, and lands where it
            // is drawn (touch swarm, rec 12: CarryOffset); a mouse's is centred on the pointer.
            // Its own composable (1.0.92): the pointer is read as it is placed, not here, so the
            // shell does not recompose on every move of a carried card.
            val carry = rememberCarryMotion(h.drag)
            h.drag.held?.let { held -> CarriedCard(h.drag, held, carry, state.format, neue.prefs.foil, state.marks) }

            // Ai on a phone: the whole screen, over the page and under its dialogs (1.0.43).
            if (neue.aiSheet) AiPanel(h, Modifier.fillMaxSize(), phone = true)
            // The setup offered on opening (1.0.69): under Ai's own setup, which its Ai step can open.
            if (neue.starting) StartScreen(h, Modifier.fillMaxSize())
            // Ai's first setup takes the whole window, bars and all (1.0.45).
            if (neue.aiSetup) AiSetupScreen(h.ai, Modifier.fillMaxSize())
            // The reader's guide, read as a book over the whole window (1.0.67); the card viewer opens over it.
            neue.reading?.let { BookReader(h, it, Modifier.fillMaxSize()) }
            if (neue.prefs.ai.enabled) {
                AiFaceClock(h.ai)
                MemoryDialog(h.ai)
                ReviewDialog(h.ai)
                TuneLauncher(h.ai)
                ProfileLauncher(h.ai)
                LivingDocDialog(h.ai)
                TrustDialog(h.ai)
                PictureDialog(h.ai)
                ContextPanel(h.ai)
                VoiceDialog(h.ai)
                QuickSettings(h.ai)
            } else {
                // The duel's push-to-talk asks for the speech model whether or not Ai is on (1.0.87).
                if (h.ai.voiceForDuel) VoiceDialog(h.ai)
            }
            if (neue.prefs.ai.enabled) {
                if (h.ai.forgetAsked) {
                    MuDialog(
                        title = "Forget everything",
                        onDismiss = { h.ai.forgetAsked = false },
                        width = 384.dp,
                        description = "${h.ai.name}'s memory, the skills it wrote and every conversation will be deleted. Its connections stay. This cannot be undone.",
                        footer = {
                            MuButton("Cancel", { h.ai.forgetAsked = false }, variant = BtnVariant.GHOST)
                            MuButton("Forget", {
                                h.ai.forgetAsked = false
                                h.ai.forgetEverything()
                                neue.note = Note("${h.ai.name} forgot everything")
                            }, variant = BtnVariant.PRIMARY)
                        },
                    ) {}
                }
            }
            if (neue.helpOpen) HelpDialog { neue.helpOpen = false }
            neue.qr?.let { shown ->
                QrDialog(
                    shown,
                    onCopy = {
                        CardActions.copy(shown.ydke)
                        neue.note = com.kaiharimoto.neue.Note("YDKe code copied")
                    },
                    onDismiss = { neue.qr = null },
                )
            }
            neue.confirmRemoveArt?.let { (card, k) ->
                MuDialog(
                    title = "Remove your picture",
                    onDismiss = { neue.confirmRemoveArt = null },
                    width = 384.dp,
                    description = "Your picture for “${card.name}” will be deleted from this ${if (neue.touchFirst) "tablet" else "computer"}. This cannot be undone.",
                    footer = {
                        MuButton("Cancel", { neue.confirmRemoveArt = null }, variant = BtnVariant.GHOST)
                        MuButton("Remove", {
                            neue.confirmRemoveArt = null
                            neue.chooseArt(card, card.id.value)
                            h.customArt.remove(card.id.value, k)
                        }, variant = BtnVariant.PRIMARY)
                    },
                ) {}
            }
            neue.confirmDelete?.let { (id, name) ->
                MuDialog(
                    title = "Delete deck",
                    onDismiss = { neue.confirmDelete = null },
                    width = 384.dp,
                    description = "“$name” will be removed from this computer. This cannot be undone.",
                    footer = {
                        MuButton("Cancel", { neue.confirmDelete = null }, variant = BtnVariant.GHOST)
                        MuButton("Delete", {
                            neue.confirmDelete = null
                            scope.launch {
                                h.deps.deckRepository.delete(id)
                                // Ai's notes on the deck go with it (1.0.43).
                                h.ai.files.delete(AiMemory.path(MemoryKind.DECK, id))
                                h.ai.files.delete(AiMemory.path(MemoryKind.GUIDE, id))
                                h.ai.files.delete(GuideBook.path(id))
                                h.ai.files.deleteReports(id)
                                // Its Shootout trials too (1.1.2), and its goldfish's targets and results (Phase D step 4).
                                h.shootout.forgetDeck(id)
                                h.effects.forgetDeck(id)
                                if (neue.prefs.defaultDeckId == id || id in neue.prefs.covers) {
                                    neue.update { it.copy(defaultDeckId = it.defaultDeckId?.takeIf { d -> d != id }, covers = it.covers - id) }
                                }
                                // The builder is never left empty while the library has a deck to open.
                                if (state.deckId == id) {
                                    val next = StartingDeck.pick(h.deps.deckRepository.all().map { it.entry }, neue.prefs.defaultDeckId)
                                    if (next != null) state.load(next) else state.newDeck()
                                }
                                h.decksReload++
                            }
                        }, variant = BtnVariant.PRIMARY)
                    },
                ) {}
            }
            if (h.updates.dialogOpen) UpdateDialog(h.updates)
            if (neue.paletteOpen) CommandPalette(h::commands) { neue.paletteOpen = false }
            if (immersive) {
                ZenReset(
                    h.zen,
                    hasGroups = state.groups.groups.isNotEmpty(),
                    onLeave = { h.wake() },
                    labels = neue.prefs.zenLabels,
                    onLabels = { neue.update { it.copy(zenLabels = !it.zenLabels) } },
                    modifier = Modifier.align(Alignment.BottomEnd),
                    always = neue.touchFirst,
                    onLeaveFullScreen = if (neue.touchFirst) ({ h.wake(); h.run(DeskAction.IMMERSIVE) }) else null,
                )
            }
            // The box being dragged over the table in deep zen: a hairline and the faintest wash.
            h.zen.marquee?.let { box ->
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(c.ink06, box.topLeft, box.size)
                    drawRect(c.ink, box.topLeft, box.size, style = Stroke(1.dp.toPx()))
                }
            }
            SearchStudio(state, neue)
            CardViewer(state, neue)
            // Over the viewer it was opened from (v1.3.6).
            com.kaiharimoto.neue.builder.Showcase(state, neue)
            // Over the viewer and the pop-out, whose art row opens it (1.0.34).
            neue.cropping?.let { (card, picture) ->
                ArtCropDialog(
                    card = card,
                    initial = picture,
                    // The printing whose frame is kept: the artwork chosen, when the pool knows it.
                    base = CardArt.show(
                        card,
                        neue.prefs.arts[card.id.value]?.takeIf { it > 0 }?.let { CardId(it) },
                    ),
                    custom = h.customArt,
                    library = h.art,
                    touch = neue.touchFirst,
                    onChosen = { choice ->
                        neue.cropping = null
                        neue.chooseArt(card, choice)
                    },
                    onNote = { neue.note = Note(it) },
                    onDismiss = { neue.cropping = null },
                )
            }
            // A presentation playing, over everything but its own menus (1.0.70).
            PresentOverlay(h)
            MenuLayer(neue.menu) { neue.menu = null }
            OverlayLayer(h.overlays)

            Toasts(h, Modifier.align(Alignment.BottomEnd).imePadding().padding(end = 24.dp, bottom = 24.dp))
            if (neue.frameMeter) FrameMeter(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp))

            // Long jobs the whole window waits on: the cursor ticks and says so.
            val job = when {
                h.updates.downloading -> "Downloading" to h.updates.progress?.let { it * 100f }
                h.shots.taking -> "Exporting" to null
                else -> null
            }
            LaunchedEffect(job) { if (job == null) h.cursor.clearBusy() else h.cursor.setBusy(job.first, job.second) }
            // Chessy's copies, over the page and its menus, under the cursor (kai, 2026-10)
            if (neue.prefs.ai.enabled && neue.prefs.ai.persona == com.kaiharimoto.mastertool.core.prefs.AiPrefs.PERSONA_CHESSY) {
                com.kaiharimoto.neue.ai.chessy.ChessyCrewLayer(h.ai.crew)
                com.kaiharimoto.neue.ai.chessy.ChessyAmieLayer(h.ai)
            }
        }
        // Chessy's takeover, over the whole app, under the cursor
        if (neue.prefs.ai.enabled) com.kaiharimoto.neue.ai.chessy.TakeoverLayer(h.ai)
        // Last in the window, over everything in it.
        CursorLayer(h.cursor)
    }
}

/**
 * The pool shows the list it was switched to (1.0.19): the list is the filter's
 * `onlyIds`, kept in step here — so a list changed from a menu, or a filter
 * replaced whole by a click in the inspector, still leaves the pool on the list.
 */
@Composable
private fun PoolSource(h: NeueHolders) {
    val state = h.builder
    val wanted = h.neue.list(h.neue.prefs.poolList)?.ids?.toSet()
    LaunchedEffect(wanted, state.filter.onlyIds) {
        if (state.filter.onlyIds != wanted) state.onFilterChange(state.filter.copy(onlyIds = wanted))
    }
}

/**
 * Auto save (kai, 1.0.18): while it is on, a deck that has changed is saved a
 * second and a half after the last change — quietly, because a toast after every
 * edit would be noise. An empty deck that was never saved is left alone, or
 * starting a new deck would put an empty one in the library.
 */
@Composable
private fun AutoSave(h: NeueHolders) {
    val state = h.builder
    val on = h.neue.prefs.autoSave
    LaunchedEffect(on, state.dirty, state.deck, state.deckName, state.groups, state.goals) {
        if (!on || !state.dirty) return@LaunchedEffect
        if (state.deckId == null && state.deck.totalCards == 0) return@LaunchedEffect
        delay(1_500)
        state.save(quiet = true) { h.decksReload++ }
    }
}

@Composable
private fun Toasts(h: NeueHolders, modifier: Modifier) {
    val toast = h.builder.toast
    val note = h.updates.message
    val own = h.neue.note
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (note != null) {
            LaunchedEffect(note) { delay(4000); h.updates.message = null }
            ToastBox(note, null, {})
        }
        if (own != null) {
            LaunchedEffect(own.id) { delay(own.lastsMs); if (h.neue.note?.id == own.id) h.neue.note = null }
            ToastBox(own.message, own.action, {
                own.onAction()
                h.neue.note = null
            })
        }
        if (toast != null) {
            LaunchedEffect(toast.id) { delay(4000); h.builder.consumeToast() }
            ToastBox(
                message = toast.message,
                action = toast.undo?.let { "Undo" },
                onAction = {
                    toast.undo?.invoke()
                    h.builder.consumeToast()
                },
            )
        }
    }
}

@Composable
private fun UpdateDialog(updates: NeueUpdates) {
    val update = updates.available ?: return
    val c = Mu.colors
    val inPlace = update.installer != null && Platform.os == DesktopOs.WINDOWS
    MuDialog(
        title = "Neue Master Tool ${update.versionName}",
        onDismiss = { updates.dialogOpen = false },
        width = 672.dp,
        // The notes scroll themselves, so the footer's Install is never scrolled away.
        scrolls = false,
        description = "You have ${Platform.version}. " + when {
            update.installer == null -> "This release has no installer for this system yet."
            inPlace -> "It installs over this one and reopens."
            else -> "The installer downloads and opens."
        },
        footer = {
            MuButton("Later", { updates.dialogOpen = false }, variant = BtnVariant.GHOST)
            MuButton("Release page", updates::openReleasePage, variant = BtnVariant.SUBTLE)
            if (update.installer != null) {
                MuButton(
                    if (updates.downloading) "Downloading" else if (inPlace) "Install" else "Download",
                    updates::install,
                    variant = BtnVariant.PRIMARY,
                    arrow = true,
                    enabled = !updates.downloading,
                )
            }
        },
    ) {
        Box(Modifier.heightIn(max = 360.dp).fillMaxWidth()) {
            SelectionContainer {
                Body(
                    update.release.notes.ifBlank { "No notes for this release." },
                    Modifier.verticalScroll(rememberScrollState()),
                    color = c.ink70,
                )
            }
        }
        if (updates.downloading) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Progress(updates.progress)
                Mono(updates.progress?.let { "${(it * 100).toInt()}%" } ?: "Starting")
            }
        }
    }
}

/** An arrangement in the words its switch shows. */
internal fun arrangementWords(a: GroupArrangement): String = when (a) {
    GroupArrangement.AS_IS -> "As is"
    GroupArrangement.FITTED -> "Fitted"
    GroupArrangement.SEPARATE -> "Separate"
}
