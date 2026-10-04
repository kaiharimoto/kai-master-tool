package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.update.CpuArch
import com.kaiharimoto.mastertool.core.update.DesktopOs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.kaiharimoto.neue.present.play.PresentAudience
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kaiharimoto.mastertool.core.data.CardRepository
import com.kaiharimoto.mastertool.core.data.DatabaseFactory
import com.kaiharimoto.mastertool.core.data.DeckRepository
import com.kaiharimoto.mastertool.core.data.PreferencesRepository
import com.kaiharimoto.mastertool.core.prefs.WindowBounds
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.core.update.GitHubReleaseApi
import com.kaiharimoto.mastertool.core.update.NeueUpdateChecker
import com.kaiharimoto.mastertool.core.update.Release
import com.kaiharimoto.mastertool.core.update.UpdateChecker
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.update.AppUpdater
import com.kaiharimoto.mastertool.ui.update.InstallOutcome
import com.kaiharimoto.neue.platform.NeueFileAccess
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.crashFile
import com.kaiharimoto.neue.platform.reportIssue
import com.kaiharimoto.neue.platform.writeCrash
import com.kaiharimoto.neue.update.NeueUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Neue Master Tool.
 *
 * If the last run crashed, the trace is shown first — in a window with no theme
 * and no custom font, because whatever broke may have been the theme — with
 * Copy and Report. Otherwise the builder opens where the window last was.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    if (Platform.os == DesktopOs.MAC) MacChrome.prepare()
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        Platform.writeCrash(error)
        exitProcess(1)
    }
    val previousCrash = Platform.crashFile.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    val deps = buildDependencies()

    application {
        var crash by remember { mutableStateOf(previousCrash) }
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory { window ->
                WindowExceptionHandler { error ->
                    Platform.writeCrash(error)
                    window.dispose()
                    exitProcess(1)
                }
            },
        ) {
            val shown = crash
            if (shown != null) {
                Window(onCloseRequest = ::exitApplication, title = "Neue Master Tool", state = rememberWindowState(size = DpSize(900.dp, 640.dp))) {
                    CrashReport(shown) {
                        Platform.crashFile.delete()
                        crash = null
                    }
                }
            } else {
                MainWindow(deps, ::exitApplication)
            }
        }
    }
}

@Composable
private fun MainWindow(deps: AppDependencies, exit: () -> Unit) {
    val h = rememberHolders(deps) { scope ->
        NeueUpdates(
            NeueUpdateChecker(
                GitHubReleaseApi(HttpClientFactory.create()),
                Platform.version,
                Platform.os,
                CpuArch.of(System.getProperty("os.arch").orEmpty()),
            ),
            scope,
        )
    }
    val saved = h.neue.prefs.window
    val windowState = rememberWindowState(
        size = DpSize(1440.dp, 900.dp),
        position = WindowPosition.PlatformDefault,
    )
    // The stored bounds arrive a moment after the window opens (the database is
    // read off the UI thread), so they are applied once when they do.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (!placed && saved != null) {
            windowState.position = WindowPosition(saved.x.dp, saved.y.dp)
            windowState.size = DpSize(saved.width.dp, saved.height.dp)
            if (saved.maximised) windowState.placement = WindowPlacement.Maximized
            placed = true
        }
    }
    LaunchedEffect(windowState) {
        snapshotFlow { Triple(windowState.position, windowState.size, windowState.placement) }
            .collectLatest { (position, size, placement) ->
                delay(600)
                // Full screen is a mode, not a place to reopen at.
                if (position is WindowPosition.Absolute && placement != WindowPlacement.Fullscreen) {
                    h.neue.update(debounce = true) {
                        it.copy(
                            window = WindowBounds(
                                x = position.x.value,
                                y = position.y.value,
                                width = size.width.value,
                                height = size.height.value,
                                maximised = placement == WindowPlacement.Maximized,
                            ),
                        )
                    }
                }
            }
    }

    // The app's own lifetime — the pool, the preferences, the art library — is here,
    // outside the windows, so a window swapped in for immersive mode starts nothing over.
    NeueEffects(h)

    // Immersive mode is full screen, reached two ways.
    //
    // On Windows it is a second, borderless window laid exactly over the monitor the
    // builder is on (Windows treats such a window as full screen), swapped in for the
    // decorated one and back. Compose's own full screen there is the JDK's exclusive
    // mode (GraphicsDevice.setFullScreenWindow): with Java2D's Direct3D pipeline it
    // minimised the window the moment another monitor took focus, and without it the
    // window kept its title bar (1.0.12). A frame's decorations cannot change while
    // it is showing, so the swap is a new window; everything the builder knows lives
    // in NeueHolders, outside it, and carries over.
    //
    // The swap is a handover (1.0.24, kai: "the whole app disappears for a second").
    // It used to dispose the window on screen and then build the next, so for as long
    // as the next took to make — a native surface, a whole tree, its first frame —
    // there was no window at all. Now the window on screen is first frozen: its last
    // frame is kept as a picture and its tree let go, so nothing it registered (drop
    // targets, zen's slots) outlives it or is undone after the next has made its own.
    // The picture stays up while the next window is built and painted (Compose draws
    // a window's first frame before showing it), and goes only once the next is on
    // screen over it — or after [HANDOVER_MS], whatever happens.
    //
    // On macOS and Linux it is the window's own full screen, which does neither.
    // Leaving that goes through Floating first: Compose's `placement = Maximized`
    // only sets maximised — it never clears full screen — so a window that was
    // maximised before was left stuck full screen with the bars back. Floating
    // clears both; Maximized is re-applied a frame later.
    val borderless = Platform.os == DesktopOs.WINDOWS
    var host by remember { mutableStateOf<java.awt.Window?>(null) }
    // The windows on screen, the newest last: one, but for the moment of a handover.
    var shown by remember { mutableStateOf(listOf(Shown(0, null))) }
    var before by remember { mutableStateOf(WindowPlacement.Floating) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(h.neue.immersive) {
        if (borderless) {
            // The monitor the builder is on now, in the same units AWT places windows in.
            val target = if (h.neue.immersive) {
                host?.graphicsConfiguration?.bounds
                    ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
            } else {
                null
            }
            val current = shown.last()
            if (current.full == target) {
                // Pressed again before a handover finished: stay with the newest window.
                current.freeze = false
                withTimeoutOrNull(HANDOVER_MS) { snapshotFlow { current.ready }.first { it } }
                shown = listOf(current)
                return@LaunchedEffect
            }
            // 1. The window on screen keeps its last frame and lets its tree go.
            current.freeze = true
            withTimeoutOrNull(FREEZE_MS) { snapshotFlow { current.released }.first { it } }
            // 2. The next is built, painted and shown over it.
            val next = Shown(current.id + 1, target)
            shown = shown + next
            withTimeoutOrNull(HANDOVER_MS) { snapshotFlow { next.ready }.first { it } }
            // 3. Only then does the picture go.
            shown = listOf(next)
            return@LaunchedEffect
        }
        if (h.neue.immersive) {
            if (windowState.placement != WindowPlacement.Fullscreen) before = windowState.placement
            entered = true
            windowState.placement = WindowPlacement.Fullscreen
        } else if (entered) {
            entered = false
            windowState.placement = WindowPlacement.Floating
            if (before == WindowPlacement.Maximized) {
                delay(120)
                windowState.placement = WindowPlacement.Maximized
            }
        }
    }
    LaunchedEffect(windowState) {
        var last = windowState.placement
        snapshotFlow { windowState.placement }.collect { placement ->
            // Leaving full screen any other way (the green button, a window manager's key) leaves immersive too.
            if (!borderless && last == WindowPlacement.Fullscreen && placement != WindowPlacement.Fullscreen && h.neue.immersive) {
                entered = false
                h.neue.immersive = false
            }
            last = placement
        }
    }
    val mac = Platform.os == DesktopOs.MAC
    if (mac) {
        LaunchedEffect(Unit) {
            MacChrome.handleAppMenu(h) {
                h.neue.flush()
                h.prep.flush()
                h.flushDuel()
                exit()
            }
        }
    }
    // The presenter view (1.0.70): with a second screen, the slides go to a borderless window over
    // it while this window shows the notes, the next slide and the clock.
    LaunchedEffect(Unit) {
        while (true) {
            // Not while the window is down in the taskbar or the dock, unless slides are up on another
            // screen: nothing else shows the count there, and it is read again the moment the window is back.
            snapshotFlow { !windowState.isMinimized || h.present.playing != null }.first { it }
            h.present.screens = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size }.getOrDefault(1)
            delay(5_000)
        }
    }
    if (h.present.playing != null && h.present.audience && h.present.screens > 1) {
        val other = remember(host) {
            val devices = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            val mine = host?.graphicsConfiguration?.device
            (devices.firstOrNull { it != mine } ?: devices.last()).defaultConfiguration.bounds
        }
        val audienceState = remember(other) {
            WindowState(placement = WindowPlacement.Floating, position = WindowPosition(other.x.dp, other.y.dp), size = DpSize(other.width.dp, other.height.dp))
        }
        Window(
            onCloseRequest = { h.present.audience = false },
            title = "Neue Master Tool · Presentation",
            icon = painterResource("icons/neue.png"),
            state = audienceState,
            undecorated = true,
            resizable = false,
            focusable = false,
            onPreviewKeyEvent = h::onKey,
        ) {
            val pl = h.present.playing
            if (pl != null) PresentAudience(h, com.kaiharimoto.neue.present.rememberSlideContext(h, pl.show.presentation))
        }
    }
    for (me in shown) key(me.id) {
        val full = me.full
        val shownState = if (full == null) {
            windowState
        } else {
            remember(full) {
                WindowState(
                    placement = WindowPlacement.Floating,
                    position = WindowPosition(full.x.dp, full.y.dp),
                    size = DpSize(full.width.dp, full.height.dp),
                )
            }
        }
        Window(
            onCloseRequest = {
                h.neue.flush()
                h.prep.flush()
                h.flushDuel()
                exit()
            },
            title = "Neue Master Tool",
            icon = painterResource("icons/neue.png"),
            state = shownState,
            undecorated = full != null,
            // A borderless window that is resizable gets Compose's own resize border — an
            // invisible band round the edge that takes the pointer and drags the window.
            // In immersive that band sat exactly where the rail is revealed from (1.0.15).
            resizable = full == null,
            onPreviewKeyEvent = h::onKey,
        ) {
            LaunchedEffect(Unit) {
                host = window
                window.minimumSize = Dimension(1024, 680)
                window.background = if (h.neue.prefs.theme == NeueTheme.INK) java.awt.Color.BLACK else java.awt.Color.WHITE
                // On screen and painted: the window it takes over from may go.
                while (!window.isShowing) delay(16)
                withFrameNanos { }
                withFrameNanos { }
                me.ready = true
            }
            if (mac) {
                MacMenuBar(h)
                MacTitleBar(h.neue.prefs.theme)
            }
            Handover(me) { NeueRoot(h, launchEffects = false) }
        }
    }
}

/** Theme-free on purpose: this must render when the thing that broke was the theme. */
@Composable
private fun CrashReport(trace: String, onContinue: () -> Unit) {
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
    val sans = TextStyle(fontSize = 14.sp, color = Color.Black)
    Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicText("Neue Master Tool closed unexpectedly last time.", style = sans.copy(fontSize = 24.sp))
        BasicText("This is what it was doing. Copy it, or report it with the version already filled in.", style = sans)
        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, Color.Black).padding(12.dp)) {
            SelectionContainer {
                BasicText(trace, Modifier.verticalScroll(rememberScrollState()), style = mono)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlainButton("Copy") {
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(trace), null) }
            }
            PlainButton("Report →") { Platform.reportIssue("Neue crash", trace) }
            PlainButton("Continue →", onContinue)
        }
    }
}

@Composable
private fun PlainButton(label: String, onClick: () -> Unit) {
    Box(Modifier.border(1.dp, Color.Black).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        BasicText(label.uppercase(), style = TextStyle(fontSize = 12.sp, color = Color.Black))
    }
}

private fun buildDependencies(): AppDependencies {
    val database = DatabaseFactory.create(
        NeueDatabaseDriverFactory(File(Platform.dataDir, DatabaseFactory.DATABASE_NAME).absolutePath),
    )
    // One client for both: the tablet's update checker below is present and never asked, so it
    // shares the pool's rather than building one of its own at launch (1.0.92).
    val http = HttpClientFactory.create()
    val api = YgoProDeckApi(http)
    return AppDependencies(
        cardRepository = CardRepository(database = database, api = api, clock = System::currentTimeMillis, ioDispatcher = Dispatchers.IO),
        deckRepository = DeckRepository(database = database, clock = System::currentTimeMillis, ioDispatcher = Dispatchers.IO),
        preferencesRepository = PreferencesRepository(database = database, ioDispatcher = Dispatchers.IO),
        fileAccess = NeueFileAccess(),
        // The shared state holders take the tablet's updater types; Neue has its
        // own (`NeueUpdates`), so these are present and never asked.
        updateChecker = UpdateChecker(GitHubReleaseApi(http), Platform.version),
        updater = ReleasePageUpdater,
        newDeckId = { UUID.randomUUID().toString() },
        now = System::currentTimeMillis,
        imageCacheDir = File(Platform.dataDir, "card-art").absolutePath,
    )
}

private object ReleasePageUpdater : AppUpdater {
    override val currentVersionName: String get() = Platform.version
    override val canInstallInPlace: Boolean = false
    override suspend fun downloadAndInstall(release: Release, onProgress: (Float?) -> Unit): InstallOutcome {
        Platform.browse(release.htmlUrl)
        return InstallOutcome.HandedToInstaller
    }
    override fun openReleasePage(url: String) = Platform.browse(url)
}
