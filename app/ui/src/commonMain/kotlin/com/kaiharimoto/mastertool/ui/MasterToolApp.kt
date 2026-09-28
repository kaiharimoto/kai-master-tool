package com.kaiharimoto.mastertool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toPath
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderScreen
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckLayoutState
import com.kaiharimoto.mastertool.ui.components.CardBackChoice
import com.kaiharimoto.mastertool.ui.components.LocalCardBack
import com.kaiharimoto.mastertool.ui.fx.LocalFeedback
import com.kaiharimoto.mastertool.ui.fx.defaultFeedbackEnabled
import com.kaiharimoto.mastertool.ui.fx.rememberFeedback
import com.kaiharimoto.mastertool.ui.library.DeckLibraryScreen
import com.kaiharimoto.mastertool.ui.play.PlayScreen
import com.kaiharimoto.mastertool.ui.theme.LocalPrismaticCards
import com.kaiharimoto.mastertool.ui.theme.MasterToolPalette
import com.kaiharimoto.mastertool.ui.theme.MasterToolTheme
import com.kaiharimoto.mastertool.ui.update.UpdateDialog
import com.kaiharimoto.mastertool.ui.update.UpdateState

/**
 * Which screen is showing.
 *
 * A handful of destinations do not justify a navigation library; a sealed type
 * keeps the whole routing story visible in one place.
 */
private sealed interface Screen {
    data object DeckBuilder : Screen
    data object Library : Screen
    data object Play : Screen
}

@Composable
fun MasterToolApp(deps: AppDependencies) {
    val scope = rememberCoroutineScope()
    val builderState = remember { DeckBuilderState(deps, scope) }
    val layoutState = remember { DeckLayoutState(deps.preferencesRepository, scope) }
    val updateState = remember { UpdateState(deps.updateChecker, deps.updater, scope) }
    var screen by remember { mutableStateOf<Screen>(Screen.DeckBuilder) }

    DisposableEffect(Unit) {
        configureImageLoader(deps.imageCacheDir)
        // The format is a layout preference on disk but belongs to the builder at
        // runtime, so it is handed over once the stored settings arrive.
        layoutState.start { preferences ->
            builderState.onFormatChange(preferences.format)
            builderState.onSearchEffectsChange(preferences.searchEffects)
        }
        builderState.start()
        // Silent on launch: it only interrupts if there is something to install.
        updateState.check(userInitiated = false)
        // The last layout change before the window closes is exactly the one
        // the user quit to keep; without this it was still waiting out its
        // save debounce when the scope died.
        onDispose { layoutState.flush() }
    }

    val feedback = rememberFeedback(
        enabled = {
            layoutState.preferences.feedbackEnabled ?: defaultFeedbackEnabled()
        },
    )

    MasterToolTheme(mode = layoutState.preferences.themeMode) {
        CompositionLocalProvider(
            LocalFeedback provides feedback,
            LocalCardBack provides CardBackChoice(layoutState.preferences.cardBackUrl),
            LocalPrismaticCards provides layoutState.preferences.prismaticCards,
        ) {
        SafeArea {
        when (screen) {
            Screen.DeckBuilder -> DeckBuilderScreen(
                state = builderState,
                layout = layoutState,
                updateState = updateState,
                onOpenLibrary = { screen = Screen.Library },
                onOpenPlay = { screen = Screen.Play },
            )

            Screen.Play -> PlayScreen(
                state = builderState,
                prefs = layoutState,
                onBack = { screen = Screen.DeckBuilder },
            )

            Screen.Library -> DeckLibraryScreen(
                deps = deps,
                onOpenDeck = { id ->
                    builderState.load(id)
                    screen = Screen.DeckBuilder
                },
                onBack = { screen = Screen.DeckBuilder },
            )
        }

        updateState.pendingUpdate?.let { release ->
            UpdateDialog(
                release = release,
                currentVersionName = updateState.currentVersionName,
                manualOnly = updateState.pendingUpdateIsManual,
                isDownloading = updateState.isDownloading,
                progress = updateState.downloadProgress,
                onInstall = updateState::install,
                onOpenInBrowser = updateState::openInBrowser,
                onDismiss = updateState::dismiss,
            )
        }
        }
        }
    }
}

/**
 * Everything the app draws, kept clear of the system's own furniture.
 *
 * Android 15 made edge-to-edge the only option for anything targeting SDK 35 or
 * later: `windowIsTranslucent`, `statusBarColor` and the rest of the old opt-out
 * are ignored, and every app draws under the status and navigation bars whether
 * it has planned for it or not. This app had not, which on a Tab S11 in
 * landscape meant the play stage's toolbar and the system clock occupied the
 * same twenty-four density-independent pixels.
 *
 * Done once, here, rather than per screen — four screens with their own
 * `statusBarsPadding` is four places for a fifth screen to be forgotten, and
 * this is exactly the class of bug that gets noticed on a device and nowhere
 * else. The Ink background is drawn *behind* the padding so the bars sit on the
 * app's own black rather than on a gap.
 *
 * The insets are the system bars and any display cutout, deliberately **not**
 * `safeDrawing`: that one includes the on-screen keyboard, and padding the whole
 * app by the keyboard means every deck pane re-solves its layout the moment
 * anybody types in the search field.
 *
 * On desktop every one of these is zero and this is an empty box.
 *
 * Public because [MasterToolApp] is no longer the only entry point that stands
 * a screen up: `:studio` composes the play stage headlessly and has to provide
 * the identical scaffolding, since a harness that assembles the app slightly
 * differently is a harness whose pictures are of a slightly different app.
 */
@Composable
fun SafeArea(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MasterToolPalette.Ink)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    ) {
        content()
    }
}

