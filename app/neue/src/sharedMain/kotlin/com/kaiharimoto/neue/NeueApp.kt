package com.kaiharimoto.neue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.report.book.BookNotes
import com.kaiharimoto.neue.versions.VersionsDialog
import com.kaiharimoto.neue.versions.carryMeasurements
import com.kaiharimoto.neue.versions.forgetVersions
import com.kaiharimoto.neue.ai.course.CourseMonitor
import com.kaiharimoto.neue.ai.course.ReplayLibraryDialog
import com.kaiharimoto.neue.ai.course.playDbReplay
import com.kaiharimoto.neue.cards.LocalArtSource
import com.kaiharimoto.neue.cards.LocalCustomPictures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import com.kaiharimoto.neue.effects.LocalEffectsHolders
import com.kaiharimoto.neue.mapper.MapperPage
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.kaiharimoto.mastertool.core.haptics.DeskEvent
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskWords
import com.kaiharimoto.mastertool.core.layout.FormFactor
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.mastertool.core.layout.Posture
import com.kaiharimoto.mastertool.core.library.StartingDeck
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.motion.Tilt
import com.kaiharimoto.mastertool.core.offline.Offline
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.ai.AiPanel
import com.kaiharimoto.neue.ai.AiSetupScreen
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
import com.kaiharimoto.neue.ai.forgetEverything
import com.kaiharimoto.neue.ai.reader.BookReader
import com.kaiharimoto.neue.art.ArtCropDialog
import com.kaiharimoto.neue.art.LocalArt
import com.kaiharimoto.neue.art.LocalCustomArt
import com.kaiharimoto.neue.builder.BuilderBar
import com.kaiharimoto.neue.builder.BuilderPage
import com.kaiharimoto.neue.builder.GroupSetRenameDialog
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.builder.CardViewer
import com.kaiharimoto.neue.builder.CarriedCard
import com.kaiharimoto.neue.builder.SearchStudio
import com.kaiharimoto.neue.builder.rememberCarryMotion
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.LocalArtStep
import com.kaiharimoto.neue.cards.LocalArts
import com.kaiharimoto.neue.cards.LocalCardFoil
import com.kaiharimoto.neue.cards.LocalLimitMarks
import com.kaiharimoto.neue.cards.LocalNameStyle
import com.kaiharimoto.neue.cursor.CursorLayer
import com.kaiharimoto.neue.cursor.FamilyCursor
import com.kaiharimoto.neue.cursor.LocalCursor
import com.kaiharimoto.neue.duel.DuelBarItems
import com.kaiharimoto.neue.shootout.ShootoutBarItems
import com.kaiharimoto.neue.duel.DuelPage
import com.kaiharimoto.neue.prep.rememberMistakes
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
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.ProvideTextMenus
import com.kaiharimoto.neue.kit.ToastBox
import com.kaiharimoto.neue.pages.DecksPage
import com.kaiharimoto.neue.pages.FormatPage
import com.kaiharimoto.neue.pages.SettingsHost
import com.kaiharimoto.neue.pages.SettingsPage
import com.kaiharimoto.neue.pages.SidingPage
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.reportIssue
import com.kaiharimoto.neue.prep.PrepPage
import com.kaiharimoto.neue.present.PresentOverlay
import com.kaiharimoto.neue.present.PresentPage
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
import com.kaiharimoto.neue.start.StartScreen
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.theme.MuShell
import com.kaiharimoto.neue.theme.MuTheme
import com.kaiharimoto.neue.update.NeueUpdates
import com.kaiharimoto.neue.world.WorldBarItems
import com.kaiharimoto.neue.world.WorldPage
import com.kaiharimoto.neue.world.desk.WorldPhoneTitle
import com.kaiharimoto.neue.shootout.ShootoutPage
import com.kaiharimoto.neue.zen.LocalZen
import com.kaiharimoto.neue.zen.ZenReset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    CompositionLocalProvider(LocalTilt provides shownTilt, LocalCardFoil provides neue.prefs.foil, LocalDeviceTilt provides tilt, LocalDensity provides Density(base.density * neue.prefs.scale, base.fontScale * neue.prefs.textScaleOn(neue.touchFirst, neue.phone)), LocalArt provides h.art, LocalArtSource provides h.art, LocalNameStyle provides neue.prefs.foilNames, LocalLimitMarks provides neue.prefs.limitMarks, LocalZen provides h.zen, LocalCursor provides h.cursor, LocalOverlays provides h.overlays, LocalTouchFirst provides neue.touchFirst, LocalPhone provides neue.phone, LocalKeepCase provides (if (neue.prefs.ai.enabled) setOf(neue.prefs.ai.name.ifBlank { "Ai" }, "Ai", com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME) else emptySet()), com.kaiharimoto.neue.ai.chessy.LocalChessy provides (if (neue.prefs.ai.persona == com.kaiharimoto.mastertool.core.prefs.AiPrefs.PERSONA_CHESSY) remember(h.ai) { com.kaiharimoto.neue.ai.chessy.ChessyLook({ h.ai.streaming.isNotEmpty() }, { h.ai.streaming }) } else null), LocalTextFocus provides h.textFocus, LocalHardwareKeyboard provides (!neue.touchFirst || neue.hardwareKeyboard), LocalReasonNote provides { reason: String -> neue.note = Note(reason) }, LocalArts provides neue.prefs.arts, LocalArtStep provides { card: com.kaiharimoto.mastertool.core.model.Card, by: Int ->
        neue.stepArt(card, by)
        // A finger stepping a card's art feels it turn over (touch swarm, rec 13).
        neue.actingBy(finger = neue.touchFirst) { neue.felt(DeskEvent.ART_STEPPED) }
    }, LocalCustomArt provides h.customArt, LocalCustomPictures provides h.customArt, LocalEffectsHolders provides h) {
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
                                Page.DECKS -> DecksPage(h.deps, state, neue, h.decksReload, hidden = h.webs.library.deckIds, onDuplicated = { from, to ->
                                    if (neue.prefs.ai.enabled) h.ai.carryLearning(from, to)
                                    h.carryMeasurements(from, to)
                                })
                                Page.BUILDER -> BuilderPage(state, neue, h.drag, h::setSearchEffects)
                                Page.SIDING -> SidingPage(h.webs, state, neue, h.decksReload, onSave = { h.run(DeskAction.SAVE) }, holders = h)
                                Page.FORMAT -> FormatPage(h.deps, h.webs, state, neue, h.decksReload, onOpenDeck = h::openDeck)
                                Page.PREP -> PrepPage(h.prep, h.webs, state, neue, h.decksReload, { day -> h.legalityRules(neue.prefs.copy(legalAsOf = day), state.format) }, rememberMistakes(h))
                                Page.PRESENT -> PresentPage(h)
                                Page.DUEL -> DuelPage(h)
                                Page.WORLD -> WorldPage(h)
                                Page.SHOOTOUT -> ShootoutPage(h)
                                Page.MAPPER -> MapperPage(h)
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
                                        lounge = h.lounge,
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
                CourseMonitor(h.ai)
                ReplayLibraryDialog(h.ai) { e, n -> h.playDbReplay(e, n) }
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
            GroupSetRenameDialog(state, neue)
            neue.versionsOf?.let { (id, name) -> VersionsDialog(h, id, name) { neue.versionsOf = null } }
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
                                h.ai.files.delete(BookNotes.path(id))
                                h.ai.files.deleteReports(id)
                                // Its Shootout trials too (1.1.2), and its goldfish's targets and results (Phase D step 4).
                                h.shootout.forgetDeck(id)
                                h.effects.forgetDeck(id)
                                if (h.mapperStarted) h.mapper.forgetDeck(id)
                                // And its versions (Phase G, G.8).
                                h.forgetVersions(id)
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
            // The question being written (Phase G, G.3): over the builder, under the viewer's own windows.
            com.kaiharimoto.neue.builder.GoalDialog(state)
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
    // "Like this" (Phase G, G.7) stands in the list's place while it is shown.
    val like = h.neue.likeness
    val wanted = like?.ids?.toSet() ?: h.neue.list(h.neue.prefs.poolList)?.ids?.toSet()
    val ranked = like?.ids
    LaunchedEffect(wanted, ranked, state.filter.onlyIds, state.filter.ranked) {
        if (state.filter.onlyIds != wanted || state.filter.ranked != ranked) state.onFilterChange(state.filter.copy(onlyIds = wanted, ranked = ranked))
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
