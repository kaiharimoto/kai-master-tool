package com.kaiharimoto.mastertool

import com.kaiharimoto.neue.onKey
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.core.update.GitHubReleaseApi
import com.kaiharimoto.mastertool.core.update.NeueUpdateChecker
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.DeckFileAccess
import com.kaiharimoto.mastertool.ui.ImportedFile
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.NeueRoot
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.platform.PickedFile
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.QrScan
import com.kaiharimoto.neue.rememberHolders
import com.kaiharimoto.neue.update.NeueUpdates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Deliberately theme-free: it must render even when the theme cannot. */
@Composable
private fun CrashReportScreen(trace: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        BasicText(
            "Neue Master Tool crashed last time it ran",
            style = TextStyle(color = Color.Black, fontSize = 22.sp),
        )
        BasicText(
            "Share this report so it can be fixed, then continue.",
            style = TextStyle(color = Color.Black.copy(alpha = 0.7f), fontSize = 14.sp),
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )

        Row {
            BasicText(
                "SHARE REPORT",
                style = TextStyle(color = Color.Black, fontSize = 16.sp),
                modifier = Modifier.clickable(onClick = onShare).padding(10.dp),
            )
            Spacer(Modifier.width(20.dp))
            BasicText(
                "CONTINUE",
                style = TextStyle(color = Color.Black, fontSize = 16.sp),
                modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp),
            )
        }

        SelectionContainer(
            Modifier
                .padding(top = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BasicText(
                trace,
                style = TextStyle(color = Color.Black, fontSize = 11.sp),
            )
        }
    }
}

/**
 * The APK is Neue Master Tool (v1.3.0): the same app as the desktop's, built on
 * the same `:core` and `:builder`, reading the same `kai_master_tool.db` the
 * tablet app kept — so an installed tablet updates onto it with its decks.
 *
 * What the desktop's `Main.kt` does for its window, this does for the activity:
 * the crash report, the files (the Storage Access Framework rather than a file
 * dialog), the keyboard (a hardware keyboard's keys reach Neue's key table
 * before anything else, as the window's do), full screen, and the back gesture
 * (Esc: it closes what is on top, and leaves only when nothing is).
 */
class MainActivity : ComponentActivity(), DeckFileAccess {

    private var holders: NeueHolders? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            holders?.back()
        }
    }

    /** Neue's state, once it is composed: for the instrumented smoke test, which reads it. */
    internal val neue: NeueHolders? get() = holders

    private var pendingImport: CompletableDeferred<ImportedFile?>? = null
    private var pendingExport: CompletableDeferred<Boolean>? = null
    private var pendingExportContent: String? = null
    private var pendingPick: CompletableDeferred<PickedFile?>? = null

    /** A deck file handed to the app from another (VIEW), waiting for the builder to ask for it. */
    private var incoming: ImportedFile? = null

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val deferred = pendingImport
            pendingImport = null
            deferred?.complete(uri?.let(::readDeckFile))
        }

    private var pendingTree: CompletableDeferred<String?>? = null

    /**
     * The folder sync meets the other devices in (1.0.68): the system's folder picker. The grant is
     * kept, so the folder stays the app's to read and write after a restart.
     */
    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val deferred = pendingTree
            pendingTree = null
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
            deferred?.complete(uri?.toString())
        }

    private suspend fun pickFolder(): String? {
        pendingTree?.complete(null)
        val deferred = CompletableDeferred<String?>()
        pendingTree = deferred
        pickTree.launch(null)
        return deferred.await()
    }

    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val deferred = pendingPick
            pendingPick = null
            deferred?.complete(uri?.let(::readPicked))
        }

    private var pendingScan: CompletableDeferred<QrScan>? = null

    /**
     * The camera, reading a deck's QR code (v1.3.7) or all the parts of a split one
     * (1.0.32): [ScanActivity], which asks for the camera itself the first time.
     * Refused, it comes back saying so.
     */
    private val scanCode =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val deferred = pendingScan
            pendingScan = null
            val text = result.data?.getStringExtra(ScanActivity.TEXT)
            deferred?.complete(
                when {
                    result.resultCode == RESULT_OK && text != null -> QrScan.Read(listOf(text))
                    result.data?.getBooleanExtra(ScanActivity.NO_CAMERA, false) == true -> QrScan.NoCamera
                    else -> QrScan.Cancelled
                },
            )
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_TYPE)) { uri ->
            val deferred = pendingExport
            val content = pendingExportContent
            pendingExport = null
            pendingExportContent = null

            val written = if (uri != null && content != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                }.isSuccess
            } else {
                false
            }
            deferred?.complete(written)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge to edge, said out loud rather than inherited (targeting SDK 35+
        // forces it anyway); Neue pads itself against the system bars below.
        // Paper by default, so the bars' icons are dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        // A crash on a tablet with no adb is a crash nobody can read. Persist
        // any uncaught exception; the next launch shows it with a share button
        // instead of dying silently again.
        val crashFile = File(filesDir, "last-crash.txt")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashFile.writeText(Platform.systemLine() + "\n\n" + error.stackTraceToString()) }
            previousHandler?.uncaughtException(thread, error)
        }
        val pendingCrash = crashFile.takeIf { it.exists() }?.readText()

        // Upright on a phone, lying down on a tablet, before the first frame: the stored
        // choice replaces it once the settings are read (v1.3.5).
        applyOrientation(null)

        com.kaiharimoto.neue.sync.SyncPlatform.attach(this) { pickFolder() }
        Platform.attach(
            this,
            picker = { types -> pick(types) },
            scanner = { scan() },
            camera = { photo() },
            permission = { permission(it) },
            // Ai at work out of sight (1.0.61): the foreground service keeps the answer alive when
            // another app comes up, and a notification says when it has answered.
            work = { on, title, line ->
                AiWorkService.set(this, on, title, line)
                if (on) askNotificationsOnce()
            },
            answer = { title, line ->
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) AiWorkService.answered(this, title, line)
            },
            // "Lists and cards as of → A day" (1.1.8): Android's own date picker, not a field of dashes.
            dayPicker = { initial, dark -> pickDay(initial, dark) },
        )

        val app = application as MasterToolApplication
        val deps = AppDependencies(
            cardRepository = app.cardRepository,
            deckRepository = app.deckRepository,
            preferencesRepository = app.preferencesRepository,
            fileAccess = this,
            updateChecker = app.updateChecker,
            updater = AndroidAppUpdater(this, app.httpClient),
            newDeckId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
            imageCacheDir = cacheDir.resolve("card_art").absolutePath,
        )

        // Back is Esc (touch swarm, rec 2): one layer at a time, through the same chain
        // (`BackChain`). The callback is enabled only while there is something to
        // close, so with nothing open the system's own back — and its predictive
        // preview of going home — plays, rather than the app vanishing.
        onBackPressedDispatcher.addCallback(this, backCallback)

        incoming = intent?.data?.let(::readDeckFile)

        setContent {
            var showCrash by remember { mutableStateOf(pendingCrash != null) }
            if (showCrash && pendingCrash != null) {
                // Raw primitives on purpose: if the crash lives in the theme or
                // font pipeline, this screen must not share its fate.
                CrashReportScreen(
                    trace = pendingCrash,
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, pendingCrash)
                        }
                        startActivity(Intent.createChooser(send, "Share crash report"))
                    },
                    onDismiss = {
                        crashFile.delete()
                        showCrash = false
                    },
                )
            } else {
                val h = rememberHolders(deps) { scope ->
                    NeueUpdates(NeueUpdateChecker(GitHubReleaseApi(app.httpClient), Platform.version, DesktopOs.ANDROID), scope)
                }
                SideEffect {
                    holders = h
                    h.neue.hardwareKeyboard = hasHardwareKeyboard(resources.configuration)
                }
                LaunchedEffect(h) {
                    androidx.compose.runtime.snapshotFlow { h.canGoBack() }.collect { backCallback.isEnabled = it }
                }
                // The Screen setting (v1.3.5): Portrait, Landscape or Auto, one tap from the bar's menu.
                // Present lies down whatever the setting (1.0.70): its slides are 16:9.
                val presenting = h.neue.page == com.kaiharimoto.neue.Page.PRESENT || h.present.playing != null
                LaunchedEffect(h.neue.ready, h.neue.prefs.orientation, presenting) {
                    if (h.neue.ready) applyOrientation(h.neue.prefs.orientation, presenting)
                }
                // Immersive mode is the system bars hidden, swiped back in from an edge.
                LaunchedEffect(h.neue.immersive) { showImmersive(h.neue.immersive) }
                // Deep zen is a picture to be looked at: the screen stays on for it (touch swarm,
                // rec 30), and the system's timeout returns when zen wakes or immersive ends.
                // A card shown full screen is looked at too (v1.3.6), and a presentation is talked over
                // for longer than any screen timeout (Present's audit, B9).
                val keepOn = h.neue.immersive && h.neue.zen == com.kaiharimoto.mastertool.core.motion.ZenPhase.DEEP ||
                    h.neue.showcase != null || h.present.playing != null
                LaunchedEffect(keepOn) {
                    if (keepOn) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                // Deep zen drifts slowly and is only looked at (1.0.92): on a 90 or 120 Hz screen it asks for 60,
                // half the work for a picture nobody can tell apart; everything else keeps the screen's own rate.
                val calm = h.neue.immersive && h.neue.zen == com.kaiharimoto.mastertool.core.motion.ZenPhase.DEEP && h.neue.showcase == null
                LaunchedEffect(calm) {
                    window.attributes = window.attributes.apply { preferredRefreshRate = if (calm) 60f else 0f }
                }
                // The bars' icons follow the theme: dark on Paper, light on Ink.
                val paper = h.neue.prefs.theme == com.kaiharimoto.mastertool.core.prefs.NeueTheme.PAPER
                LaunchedEffect(paper) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = paper
                        isAppearanceLightNavigationBars = paper
                    }
                }
                // A deck opened from another app, once the builder is there to take it.
                LaunchedEffect(h) {
                    if (incoming != null) {
                        h.neue.page = Page.BUILDER
                        h.builder.importFromFile()
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        // The system bars and a camera cutout, but not the soft keyboard: the
                        // search fields are at the top, and a window that shrank under the
                        // keyboard would re-fit the deck and move every card.
                        .let { if (h.neue.immersive) it else it.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)) },
                ) {
                    NeueRoot(h)
                }
            }
        }
    }

    /** A keyboard cover or a paired keyboard came or went: key hints follow it (touch swarm, rec 16). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        holders?.neue?.hardwareKeyboard = hasHardwareKeyboard(newConfig)
    }

    private fun hasHardwareKeyboard(config: android.content.res.Configuration) =
        config.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

    /**
     * Leaving the app saves the deck (touch swarm, rec 5): a tablet is put down,
     * swiped away and killed in the background, and nothing warns you first.
     */
    override fun onStart() {
        super.onStart()
        // Back in sight: the "Ai answered" notice has done its job.
        AiWorkService.seen(this)
        // onStop let the screen sleep; a presentation still playing keeps it awake again (B9).
        if (holders?.present?.playing != null) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Notifications, asked for once (Android 13), the first time Ai works: they say it is working
     * out of sight and when it has answered. The service runs whatever the answer.
     */
    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("neue.android", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        asking.launch { permission(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    private val asking = kotlinx.coroutines.MainScope()

    override fun onStop() {
        super.onStop()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val h = holders ?: return
        val state = h.builder
        if (state.dirty && (state.deckId != null || state.deck.totalCards > 0)) state.save(quiet = true)
        // A slide's last edit, before the tablet is put away and the app killed in the background (B12).
        h.flushPresent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Back from signing in to a cloud (sync, 1.0.68): the app is in front again, and that is all.
        if (intent.data?.scheme == "neuemastertool") return
        val file = intent.data?.let(::readDeckFile) ?: return
        incoming = file
        holders?.let { h ->
            h.neue.page = Page.BUILDER
            h.builder.importFromFile()
        }
    }

    /**
     * A hardware keyboard's keys go to Neue's key table first, as a window's do on
     * the desktop (`Window(onPreviewKeyEvent = …)`), whatever has focus.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val h = holders
        // Back, while Neue has something open, is Neue's chain's (touch swarm, rec 2). With a
        // keyboard attached a control can hold focus, and Compose spends a Back on clearing
        // that focus: the help, a menu or the viewer stayed open under a pressed Back.
        if (h != null && event.keyCode == android.view.KeyEvent.KEYCODE_BACK && h.canGoBack()) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) h.back()
            return true
        }
        if (h != null && h.onKey(KeyEvent(event))) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Which way the screen may turn (v1.3.5): the stored `ScreenOrientation`, else the
     * device's default — a phone (smallest width under 600dp) upright, a tablet lying
     * down. Each respects the rotation lock: the user-flavoured orientations do.
     */
    private fun applyOrientation(stored: String?, landscape: Boolean = false) {
        val form = com.kaiharimoto.mastertool.core.layout.FormFactor.ofSmallestWidth(
            resources.configuration.smallestScreenWidthDp.toFloat(),
            touch = true,
        )
        requestedOrientation = when (com.kaiharimoto.mastertool.core.layout.ScreenOrientation.resolve(stored, form, landscape)) {
            com.kaiharimoto.mastertool.core.layout.ScreenOrientation.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            com.kaiharimoto.mastertool.core.layout.ScreenOrientation.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            com.kaiharimoto.mastertool.core.layout.ScreenOrientation.AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    private fun showImmersive(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- DeckFileAccess ----------------------------------------------------

    override suspend fun importDeck(): ImportedFile? {
        // A file handed over by another app is the answer the first time it is asked.
        incoming?.let { file ->
            incoming = null
            return file
        }
        // Only one picker may be open at a time; abandon any previous request.
        pendingImport?.complete(null)

        val deferred = CompletableDeferred<ImportedFile?>()
        pendingImport = deferred
        // .ydk has no registered MIME type, so the picker has to allow anything.
        openDocument.launch(arrayOf("*/*"))
        return deferred.await()
    }

    override suspend fun exportDeck(suggestedName: String, content: String): Boolean {
        pendingExport?.complete(false)

        val deferred = CompletableDeferred<Boolean>()
        pendingExport = deferred
        pendingExportContent = content
        createDocument.launch(suggestedName)
        return deferred.await()
    }

    override suspend fun shareDeck(suggestedName: String, content: String) {
        val uri = withContext(Dispatchers.IO) {
            val shareDir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(shareDir, suggestedName).apply { writeText(content) }
            FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, suggestedName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share deck"))
    }

    /** Neue's file picker (a picture for a card): the system's document picker. */
    private suspend fun pick(types: Array<String>): PickedFile? {
        pendingPick?.complete(null)
        val deferred = CompletableDeferred<PickedFile?>()
        pendingPick = deferred
        pickDocument.launch(types)
        return deferred.await()
    }

    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val deferred = pendingPermission
            pendingPermission = null
            deferred?.complete(granted)
        }

    /** A runtime permission, asked for when it is not held yet (1.0.55: the camera; 1.0.57: the microphone). */
    private suspend fun permission(name: String): Boolean {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, name) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        pendingPermission?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        runCatching { askPermission.launch(name) }.onFailure {
            pendingPermission = null
            return false
        }
        return deferred.await()
    }

    private var pendingPhoto: CompletableDeferred<Boolean>? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val deferred = pendingPhoto
            pendingPhoto = null
            deferred?.complete(taken)
        }

    /**
     * A photo for Ai to see (1.0.55), taken with the device's own camera app into the cache's
     * `shared/` folder, turned upright by its EXIF orientation. The camera permission comes with
     * the QR scanner, so it is asked for first: holding it undeclared-but-refused makes the
     * camera app's intent throw.
     */
    private suspend fun photo(): PickedFile? {
        if (!permission(android.Manifest.permission.CAMERA)) return null
        val file = java.io.File(cacheDir, "shared/photo-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingPhoto?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingPhoto = deferred
        runCatching { takePicture.launch(uri) }.onFailure {
            pendingPhoto = null
            return null
        }
        val taken = deferred.await()
        return try {
            if (taken && file.length() > 0) PickedFile("photo.jpg", upright(file)) else null
        } finally {
            file.delete()
        }
    }

    /**
     * Android's own date picker (1.1.8, the 1.1.2 design review, finding 6, kai's choice), in paper or ink to match the
     * app (`Theme.MasterTool.Day*`: the dialog's accent is ink on paper, paper on ink). Opens on [initial] or today; the
     * day chosen as `yyyy-MM-dd`, or null when it is put away. OK fires the date before the dismiss, so the first
     * completion is the answer.
     */
    private suspend fun pickDay(initial: String?, dark: Boolean): String? = withContext(Dispatchers.Main) {
        val start = initial?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: java.time.LocalDate.now()
        val chosen = CompletableDeferred<String?>()
        val dialog = android.app.DatePickerDialog(
            this@MainActivity,
            if (dark) R.style.Theme_MasterTool_DayInk else R.style.Theme_MasterTool_DayPaper,
            { _, year, month, day -> chosen.complete(java.time.LocalDate.of(year, month + 1, day).toString()) },
            start.year,
            start.monthValue - 1,
            start.dayOfMonth,
        )
        dialog.setOnDismissListener { chosen.complete(null) }
        dialog.show()
        try {
            chosen.await()
        } finally {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    /** A camera's JPEG, turned as its EXIF says it was held; the bytes as they are when it needs no turn. */
    private fun upright(file: java.io.File): ByteArray {
        val bytes = file.readBytes()
        val degrees = runCatching {
            when (android.media.ExifInterface(file.absolutePath).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return bytes
        return runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val turned = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
            java.io.ByteArrayOutputStream().also { turned.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }.getOrDefault(bytes)
    }

    /** A deck's QR code, off another screen: the desk's Export → QR code, or anyone's `ydke://` code. */
    private suspend fun scan(): QrScan {
        pendingScan?.complete(QrScan.Cancelled)
        val deferred = CompletableDeferred<QrScan>()
        pendingScan = deferred
        runCatching { scanCode.launch(Intent(this, ScanActivity::class.java)) }.onFailure {
            pendingScan = null
            return QrScan.NoCamera
        }
        return deferred.await()
    }

    // ---- helpers -----------------------------------------------------------

    private fun readDeckFile(uri: Uri): ImportedFile? = runCatching {
        val name = displayName(uri) ?: "deck.ydk"
        val content = contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return null
        ImportedFile(name, content)
    }.getOrNull()

    private fun readPicked(uri: Uri): PickedFile? = runCatching {
        val name = displayName(uri) ?: "picture.${contentResolver.getType(uri)?.substringAfter('/') ?: "jpg"}"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        PickedFile(name, bytes)
    }.getOrNull()

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private companion object {
        const val MIME_TYPE = "text/plain"
    }
}
