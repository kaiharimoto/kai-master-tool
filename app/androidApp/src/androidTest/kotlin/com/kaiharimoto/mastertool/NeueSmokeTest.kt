package com.kaiharimoto.mastertool

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.run
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The APK is Neue now (v1.3.0), and the one thing it must not get wrong is a
 * tablet's decks: they are in `kai_master_tool.db`, written by the tablet app,
 * and Neue must open onto them. So this writes a deck the way the tablet app
 * did — through the same repository, into the same database — launches the
 * activity, and waits for Neue's builder to have opened it (`StartingDeck`: the
 * one saved last). It photographs the screen on the way out, for the run's
 * artifacts, and fails on a crash the activity recorded.
 */
@RunWith(AndroidJUnit4::class)
class NeueSmokeTest {

    // A hang is a failure with a trace, not a job that runs out its hour.
    @Test(timeout = 600_000)
    fun neueOpensOntoTheTabletsDeck() {
        val app = ApplicationProvider.getApplicationContext<MasterToolApplication>()
        skipStart(app)
        val deck = Deck(
            main = List(40) { CardId(listOf(14558127, 23434538, 27204311, 81497285)[it % 4]) },
            extra = emptyList(),
            side = emptyList(),
        )
        runBlocking { app.deckRepository.save("smoke-deck", "Smoke deck", deck, null) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var opened: String? = null
            repeat(120) {
                if (opened == "Smoke deck") return@repeat
                Thread.sleep(250)
                opened = readActivity { activity -> activity.neue?.builder?.deckName }
            }
            // Long enough for the card pool to arrive and draw, for the picture.
            Thread.sleep(6000)
            val shot: Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("no screenshot", shot)
            val out = File(app.getExternalFilesDir(null), "neue-smoke.png")
            out.outputStream().use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }

            assertFalse("the activity recorded a crash", File(app.filesDir, "last-crash.txt").exists())
            assertEquals("Neue did not open the deck the tablet app saved", "Smoke deck", opened)
        }
    }

    /**
     * The touch swarm's first release (v1.3.1), walked by a finger on the emulator:
     * the deck has the tablet's width, Groups no longer takes it, Back closes what
     * is open and brings a page home before it leaves the app, and a tap on the
     * paper along the top brings the bar out in immersive. Each step is
     * photographed, numbered, for the run's artifacts.
     */
    @Test(timeout = 600_000)
    fun aFingerFindsRoomAndWaysOut() {
        val app = ApplicationProvider.getApplicationContext<MasterToolApplication>()
        skipStart(app)
        // The tablet's walk taps where a tablet's controls are; a phone has its own walk.
        org.junit.Assume.assumeFalse("a phone: see aPhoneHeldUpright", isPhone(app))
        val deck = Deck(
            main = List(40) { CardId(listOf(14558127, 23434538, 27204311, 81497285)[it % 4]) },
            extra = emptyList(),
            side = emptyList(),
        )
        runBlocking { app.deckRepository.save("touch-deck", "Touch deck", deck, null) }
        // The walk's cards in the pool before the app opens: on a slow emulator the pool's
        // download can outlast the walk, and a card missing from the pool is a placeholder
        // that no gesture reaches. The app's own sync replaces these when it lands.
        seedPool(app, listOf(14558127, 23434538, 27204311, 81497285))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Android's own "Viewing full screen" note appears the first time any app goes
        // immersive and takes the first Back for itself; it is the system's, not Neue's.
        instrumentation.uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed").close()
        // A slow emulator's own apps stall ("Pixel Launcher isn't responding"), and the
        // system's dialog for it takes the next Back; it is the emulator's, not Neue's.
        instrumentation.uiAutomation.executeShellCommand("settings put global hide_error_dialogs 1").close()

        // The walk waits by the clock, never for the app to fall idle: a focused field's caret
        // blinks forever, and on a slow emulator waitForIdleSync then never returns.
        // Not `use`: closing a scenario waits for the app to fall idle, which it never does on
        // the CI emulator, and a failure inside the walk became a ten-minute timeout. The
        // walk finishes the activity itself, in `finally`, so its own failure is the one reported.
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
          try {
            fun <T> on(read: (MainActivity) -> T): T = readActivity(read) ?: error("no resumed activity to read")
            // A slow emulator's frames run to 250 ms and more: what the app should come to,
            // given four seconds to come to it, rather than read once after a fixed sleep.
            fun until(cond: () -> Boolean): Boolean {
                repeat(40) {
                    if (cond()) return true
                    Thread.sleep(100)
                }
                return cond()
            }
            fun shoot(name: String) {
                Thread.sleep(1200)
                val shot = instrumentation.uiAutomation.takeScreenshot() ?: return
                File(app.getExternalFilesDir(null), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            fun back() {
                // Every Back here has something to close: wait for the app to have seen it open
                // (its Back handler follows what is open a frame later), up to three seconds.
                // Neue's own chain must have something to close, and the activity's handler must
                // have followed it — another enabled callback is not Neue's.
                repeat(30) {
                    if (on { it.neue!!.canGoBack() && it.onBackPressedDispatcher.hasEnabledCallbacks() }) return@repeat
                    Thread.sleep(100)
                }
                // Any system dialog that came up anyway is put away first.
                instrumentation.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
                answerSystemDialogs()
                Thread.sleep(300)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                Thread.sleep(400)
            }
            fun tap(xDp: Float, yDp: Float) {
                answerSystemDialogs()
                val density = app.resources.displayMetrics.density
                val x = xDp * density
                val y = yDp * density
                val t = SystemClock.uptimeMillis()
                // A finger's, said so: the short MotionEvent.obtain leaves the tool type
                // unknown, and the app tells a finger from a mouse by it.
                val finger = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 60).forEach { (action, at) ->
                    val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                    val e = MotionEvent.obtain(t, at, action, 1, arrayOf(finger), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    instrumentation.uiAutomation.injectInputEvent(e, true)
                    e.recycle()
                }
                Thread.sleep(400)
            }

            // Several fingers at once, each a finger's: down one by one, up in reverse, [holdMs] apart.
            // [startAt]: when the gesture began, for a gesture timed against another one — a
            // double-tap's second tap is stamped from the first's clock, so a busy emulator that
            // is slow to take the injection cannot stretch the gap past the double-tap window.
            fun fingers(points: List<Pair<Float, Float>>, holdMs: Long = 60, settle: Boolean = true, startAt: Long? = null) {
                // Not between a double-tap's two taps: the first tap's clock is already running.
                if (startAt == null) answerSystemDialogs()
                val density = app.resources.displayMetrics.density
                val t = startAt ?: SystemClock.uptimeMillis()
                val props = points.indices.map { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = points.map { (x, y) -> MotionEvent.PointerCoords().apply { this.x = x * density; this.y = y * density; pressure = 1f; size = 1f } }
                fun send(action: Int, at: Long, count: Int) {
                    val e = MotionEvent.obtain(t, at, action, count, props.take(count).toTypedArray(), coords.take(count).toTypedArray(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    instrumentation.uiAutomation.injectInputEvent(e, true)
                    e.recycle()
                }
                val n = points.size
                send(MotionEvent.ACTION_DOWN, t, 1)
                for (i in 1 until n) send(MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + i * 10L, i + 1)
                for (i in n - 1 downTo 1) send(MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + holdMs + (n - i) * 10L, i + 1)
                send(MotionEvent.ACTION_UP, t + holdMs + n * 10L, 1)
                if (settle) {
                    Thread.sleep(400)
                }
            }

            repeat(120) {
                if (on { it.neue?.builder?.deckName } == "Touch deck") return@repeat
                Thread.sleep(250)
            }
            Thread.sleep(4000)
            shoot("01-builder.png")

            // Groups on: the panel takes the inspector's place, and the deck stays.
            on { it.neue!!.builder.useLens(Lens.ROLES) }
            shoot("02-groups.png")
            on { it.neue!!.builder.useLens(Lens.DECK) }

            // Back closes the help, and the app is still here. (Not the palette: it
            // raises the soft keyboard, which takes the first Back for itself.)
            on { it.neue!!.neue.helpOpen = true }
            back()
            val helpClosed = until { !on { it.neue!!.neue.helpOpen } }
            if (!helpClosed) shoot("help-stuck.png")
            assertTrue(
                "Back left the help open: " + on { a ->
                    val h = a.neue!!
                    "canGoBack=${h.canGoBack()} callbacks=${a.onBackPressedDispatcher.hasEnabledCallbacks()} " +
                        "update=${h.updates.dialogOpen} overlays=${h.overlays.isOpen} menu=${h.neue.menu != null} " +
                        "palette=${h.neue.paletteOpen} viewing=${h.neue.viewing != null} keyboard=${h.neue.hardwareKeyboard}"
                },
                helpClosed,
            )
            assertFalse("Back closed the app with the help open", on { it.isFinishing })

            // From another page, Back comes home to the builder before it leaves.
            on { it.neue!!.neue.go(Page.DECKS) }
            shoot("03-decks.png")
            back()
            assertTrue("Back did not bring the Decks page home", until { on { it.neue!!.neue.page } == Page.BUILDER })
            assertFalse("Back left the app from the Decks page", on { it.isFinishing })

            // Immersive: a tap on the paper strip along the top brings the bar out.
            on { it.neue!!.neue.immersive = true }
            // The system bars go on the system's time, and on a slow emulator in more than a
            // second: Neue drops its inset padding at once, so until they are gone the strip is
            // under the status bar, and a tap there is the system's, not Neue's.
            fun barsGone() = on { a ->
                a.window.decorView.rootWindowInsets?.let {
                    androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, a.window.decorView)
                        .isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                } == false
            }
            // Not `repeat`: its `return@repeat` only skips a turn, and ten seconds idle is deep zen,
            // where a tap is the cards' and the bar never comes.
            var waited = 0
            while (!barsGone() && waited < 50) {
                Thread.sleep(100)
                waited++
            }
            Thread.sleep(500)
            tap(640f, 12f)
            val barOut = until { on { it.neue!!.neue.revealed.top } }
            if (!barOut) shoot("04-immersive-stuck.png")
            val bars = if (barOut) true else barsGone()
            assertTrue(
                "a tap on the top strip did not bring the bar out: " + on { a ->
                    val h = a.neue!!
                    "immersive=${h.neue.immersive} barsGone=$bars zen=${h.neue.zen} revealed=${h.neue.revealed} " +
                        "held=${h.drag.held != null} menu=${h.neue.menu != null} page=${h.neue.page} update=${h.updates.dialogOpen}"
                },
                barOut,
            )
            shoot("04-immersive-bar.png")
            back()
            assertTrue("Back did not leave immersive", until { !on { it.neue!!.neue.immersive } })

            // v1.3.2: the pool's search takes a finger's tap and raises the keyboard, over the
            // pool and never over the deck; a tap on the deck lets it go again.
            Thread.sleep(800)
            tap(200f, 102f)
            Thread.sleep(1200)
            shoot("05-keyboard.png")
            assertTrue("the search field did not take the tap", until { on { it.neue!!.textFocus.any } })
            // High on the deck: the keyboard covers the lower half, and a tap there is the keyboard's.
            tap(760f, 180f)
            Thread.sleep(800)
            assertTrue("a tap on the deck kept the keyboard", until { !on { it.neue!!.textFocus.any } })

            // v1.3.3: a double-tap that drifts onto the neighbour is still the first card's, and
            // removes a copy; two fingers tapped together undo it, and three redo it.
            fun mainCount() = on { it.neue!!.builder.deck.main.size }
            // The count the app should reach, given three seconds to reach it.
            fun countBecomes(expected: Int): Int {
                repeat(30) {
                    if (mainCount() == expected) return expected
                    Thread.sleep(100)
                }
                return mainCount()
            }
            val before = mainCount()
            answerSystemDialogs()
            val tapped = SystemClock.uptimeMillis()
            fingers(listOf(760f to 420f), settle = false, startAt = tapped)
            // The second tap waits for its own time as well as carrying it: sent at once, it can
            // reach the card before the card is listening for its next press, and be lost.
            (tapped + 180 - SystemClock.uptimeMillis()).takeIf { it > 0 }?.let(Thread::sleep)
            fingers(listOf(772f to 424f), startAt = tapped + 180)
            assertEquals("a drifting double-tap did not remove a copy", before - 1, countBecomes(before - 1))
            // Clear of the double-tap's window, so the next fingers start a gesture of their own.
            Thread.sleep(500)
            fingers(listOf(700f to 400f, 820f to 400f))
            assertEquals("a two-finger tap did not undo", before, countBecomes(before))
            fingers(listOf(660f to 400f, 760f to 400f, 860f to 400f))
            assertEquals("a three-finger tap did not redo", before - 1, countBecomes(before - 1))
            fingers(listOf(700f to 400f, 820f to 400f))
            assertEquals(before, countBecomes(before))
            shoot("06-undone.png")

            // v1.3.4: the pool's filters at a finger's size, and Settings with whole-row switches.
            on { it.neue!!.neue.update { p -> p.copy(filtersOpen = true) } }
            shoot("07-filters.png")
            on { it.neue!!.neue.update { p -> p.copy(filtersOpen = false) } }
            on { it.neue!!.neue.go(Page.SETTINGS) }
            shoot("08-settings.png")
            on { it.neue!!.neue.go(Page.BUILDER) }

            // Deep zen keeps the screen on, and waking lets the system's timeout back.
            on { it.neue!!.neue.immersive = true }
            Thread.sleep(800)
            on { it.neue!!.run(com.kaiharimoto.mastertool.core.input.DeskAction.ZEN) }
            var kept = false
            repeat(40) {
                if (kept) return@repeat
                Thread.sleep(250)
                kept = on { (it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0 }
            }
            assertTrue("deep zen did not keep the screen on", kept)
            shoot("09-zen.png")

            assertFalse("the activity recorded a crash", File(app.filesDir, "last-crash.txt").exists())
          } finally {
            readActivity { it.finish() }
          }
        }
    }

    /**
     * The phone (v1.3.5), walked by a finger: Neue stands upright with the tabs along
     * the bottom and the pool docked under the deck; a double-tap on the deck removes a
     * copy and two fingers undo it; a tap opens the card large and Back closes it; the
     * overflow opens; the update dialog's Download is on the screen (it was off it, and a
     * phone could not update); the screen turns when told to. Photographed at each step.
     */
    @Test(timeout = 600_000)
    fun aPhoneHeldUpright() {
        val app = ApplicationProvider.getApplicationContext<MasterToolApplication>()
        skipStart(app)
        org.junit.Assume.assumeTrue("a tablet: see aFingerFindsRoomAndWaysOut", isPhone(app))
        val deck = Deck(
            main = List(40) { CardId(listOf(14558127, 23434538, 27204311, 81497285)[it % 4]) },
            extra = emptyList(),
            side = emptyList(),
        )
        runBlocking { app.deckRepository.save("phone-deck", "Phone deck", deck, null) }
        seedPool(app, listOf(14558127, 23434538, 27204311, 81497285))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("settings put global hide_error_dialogs 1").close()

        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
          try {
            fun <T> on(read: (MainActivity) -> T): T = readActivity(read) ?: error("no resumed activity to read")
            fun until(tries: Int = 40, cond: () -> Boolean): Boolean {
                repeat(tries) {
                    if (cond()) return true
                    Thread.sleep(100)
                }
                return cond()
            }
            fun shoot(name: String) {
                Thread.sleep(1200)
                val shot = instrumentation.uiAutomation.takeScreenshot() ?: return
                File(app.getExternalFilesDir(null), "phone-$name").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            fun back() {
                repeat(30) {
                    if (on { it.neue!!.canGoBack() && it.onBackPressedDispatcher.hasEnabledCallbacks() }) return@repeat
                    Thread.sleep(100)
                }
                instrumentation.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
                answerSystemDialogs()
                Thread.sleep(300)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                Thread.sleep(400)
            }
            // Where Neue's window starts and ends, in dp: under the status bar, over the navigation bar.
            fun insets(): Pair<Float, Float> = on { a ->
                val d = a.resources.displayMetrics.density
                val i = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                ((i?.top ?: 0) / d) to ((i?.bottom ?: 0) / d)
            }
            // The whole window, bars and all: Neue draws edge to edge and pads itself.
            fun screenDp(): Pair<Float, Float> = on { a ->
                val d = a.resources.displayMetrics.density
                (a.window.decorView.width / d) to (a.window.decorView.height / d)
            }
            // [startAt]: when the gesture began, for a gesture timed against another one — a
            // double-tap's second tap is stamped from the first's clock, so a busy emulator that
            // is slow to take the injection cannot stretch the gap past the double-tap window.
            fun fingers(points: List<Pair<Float, Float>>, holdMs: Long = 60, settle: Boolean = true, startAt: Long? = null) {
                // Not between a double-tap's two taps: the first tap's clock is already running.
                if (startAt == null) answerSystemDialogs()
                val density = app.resources.displayMetrics.density
                val t = startAt ?: SystemClock.uptimeMillis()
                val props = points.indices.map { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = points.map { (x, y) -> MotionEvent.PointerCoords().apply { this.x = x * density; this.y = y * density; pressure = 1f; size = 1f } }
                fun send(action: Int, at: Long, count: Int) {
                    val e = MotionEvent.obtain(t, at, action, count, props.take(count).toTypedArray(), coords.take(count).toTypedArray(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    instrumentation.uiAutomation.injectInputEvent(e, true)
                    e.recycle()
                }
                val n = points.size
                send(MotionEvent.ACTION_DOWN, t, 1)
                for (i in 1 until n) send(MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + i * 10L, i + 1)
                for (i in n - 1 downTo 1) send(MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + holdMs + (n - i) * 10L, i + 1)
                send(MotionEvent.ACTION_UP, t + holdMs + n * 10L, 1)
                if (settle) Thread.sleep(400)
            }
            fun tap(x: Float, y: Float) = fingers(listOf(x to y))
            fun mainCount() = on { it.neue!!.builder.deck.main.size }
            fun countBecomes(expected: Int): Int {
                until(30) { mainCount() == expected }
                return mainCount()
            }

            repeat(120) {
                if (on { it.neue?.builder?.deckName } == "Phone deck") return@repeat
                Thread.sleep(250)
            }
            // Upright, and drawn as a phone.
            assertEquals(
                "a phone did not stand upright",
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                on { it.requestedOrientation },
            )
            assertTrue("Neue did not see a phone", until { on { it.neue!!.neue.phone && it.neue!!.neue.posture.isTall } })
            Thread.sleep(4000)
            shoot("01-builder.png")

            // The deck's second row, its fifth card: under the bar (48), the lens row (40) and a row of
            // cards, ten across the width less 16 each side (v1.3.6: the decklist's 10×4).
            val (top, bottom) = insets()
            val (w, h) = screenDp()
            val card = (w - 32f) / 10f
            val deckY = top + 48f + 40f + 6f + card / 0.686f * 1.5f
            val deckX = w / 2f - card / 2f
            val before = mainCount()
            answerSystemDialogs()
            val tapped = SystemClock.uptimeMillis()
            fingers(listOf(deckX to deckY), settle = false, startAt = tapped)
            (tapped + 180 - SystemClock.uptimeMillis()).takeIf { it > 0 }?.let(Thread::sleep)
            fingers(listOf(deckX to deckY), startAt = tapped + 180)
            assertEquals(
                "a double-tap on a phone's deck did not remove a copy: " + on { it.neue!!.neue.viewTrace.joinToString(" | ") },
                before - 1,
                countBecomes(before - 1),
            )
            Thread.sleep(500)
            assertTrue(
                "the double-tap opened the viewer before the undo: " + on { it.neue!!.neue.viewTrace.joinToString(" | ") },
                on { it.neue!!.neue.viewing == null },
            )
            Thread.sleep(500)
            fingers(listOf(deckX - 3 * card to deckY, deckX + 3 * card to deckY))
            assertEquals("a two-finger tap did not undo", before, countBecomes(before))
            assertTrue(
                "the double-tap opened the viewer: " + on { it.neue!!.neue.viewTrace.joinToString(" | ") },
                on { it.neue!!.neue.viewing == null },
            )

            // One tap opens the card large; Back closes it.
            Thread.sleep(500)
            tap(deckX, deckY)
            assertTrue("a tap on a phone did not open the card", until { on { it.neue!!.neue.viewing != null } })
            shoot("02-viewer.png")
            // v1.3.6: full screen from the viewer, the screen kept on for it, and Back back to the viewer.
            on { it.neue!!.neue.showcase = it.neue!!.neue.viewing!!.card }
            assertTrue(
                "the showcase did not keep the screen on",
                until { on { (it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0 } },
            )
            shoot("02b-showcase.png")
            back()
            assertTrue("Back did not close the showcase", until { on { it.neue!!.neue.showcase == null } })
            assertTrue("Back from the showcase closed the viewer too", on { it.neue!!.neue.viewing != null })
            back()
            assertTrue("Back did not close the viewer", until { on { it.neue!!.neue.viewing == null } })
            assertFalse("Back left the app from the viewer", on { it.isFinishing })

            // The overflow, top right.
            tap(w - 24f, top + 24f)
            assertTrue("the overflow did not open", until { on { it.neue!!.neue.menu != null } })
            shoot("03-menu.png")
            back()
            assertTrue("Back did not close the overflow", until { on { it.neue!!.neue.menu == null } })

            // The update dialog: its Download must be on the screen, and Later must close it.
            on { it.neue!!.updates.offer(it.neue!!.updates.sample()) }
            Thread.sleep(1500)
            shoot("04-update.png")
            val density = app.resources.displayMetrics.density
            val screen = android.graphics.Rect(0, 0, (w * density).toInt(), (h * density).toInt())
            fun find(label: String): android.graphics.Rect? {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
                val queue = ArrayDeque(listOf(root))
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    val text = (node.text ?: node.contentDescription)?.toString().orEmpty()
                    if (text.equals(label, ignoreCase = true)) return android.graphics.Rect().also(node::getBoundsInScreen)
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                }
                return null
            }
            var download: android.graphics.Rect? = null
            until(50) { download = find("Download"); download != null }
            assertNotNull("the update dialog has no Download a finger can find", download)
            assertTrue("the update's Download is off the screen: $download in $screen", screen.contains(download!!))
            var later: android.graphics.Rect? = null
            until(20) { later = find("Later"); later != null }
            assertNotNull("the update dialog has no Later", later)
            tap(later!!.exactCenterX() / density, later!!.exactCenterY() / density)
            assertTrue("Later did not close the update dialog", until { !on { it.neue!!.updates.dialogOpen } })

            // The tabs along the bottom: Decks, first of five (Decks, Builder, Siding, Format, Settings).
            tap(w / 10f, h - bottom - 28f)
            assertTrue("the Decks tab did not open Decks", until { on { it.neue!!.neue.page } == Page.DECKS })
            shoot("05-decks.png")
            on { it.neue!!.neue.go(Page.SETTINGS) }
            shoot("06-settings.png")
            on { it.neue!!.neue.go(Page.SIDING) }
            shoot("07-siding.png")
            on { it.neue!!.neue.go(Page.BUILDER) }

            // Turned: Landscape asks the screen to lie down, and Neue lays itself out for it.
            on { it.neue!!.neue.update { p -> p.copy(orientation = "landscape") } }
            assertTrue(
                "Landscape did not turn the screen",
                until(60) { on { it.requestedOrientation } == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE },
            )
            until(60) { on { !it.neue!!.neue.posture.isTall } }
            shoot("08-landscape.png")
            on { it.neue!!.neue.update { p -> p.copy(orientation = null) } }
            assertTrue(
                "the phone did not stand up again",
                until(60) { on { it.requestedOrientation } == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT },
            )

            assertFalse("the activity recorded a crash", File(app.filesDir, "last-crash.txt").exists())
          } finally {
            readActivity { it.finish() }
          }
        }
    }
}

/** A phone, as Android draws the line: a smallest width under 600dp. */
private fun isPhone(app: MasterToolApplication) = app.resources.configuration.smallestScreenWidthDp < 600

/**
 * Reads the resumed activity on its own thread. `ActivityScenario.onActivity` first
 * waits for the app to fall idle, which a blinking caret on a slow emulator never
 * lets happen; this runs between frames instead. Null before the activity resumes.
 */
private fun <T> readActivity(read: (MainActivity) -> T): T? {
    var out: T? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .firstOrNull()
        if (activity != null) out = read(activity)
    }
    return out
}

/**
 * The setup offered on opening (1.0.69) marked as seen, so the walk starts on the app itself: it would
 * otherwise cover the page, since a seeded deck reads as someone updating.
 */
private fun skipStart(app: MasterToolApplication) = runBlocking {
    val prefs = app.preferencesRepository.loadNeue()
    app.preferencesRepository.saveNeue(prefs.copy(start = com.kaiharimoto.mastertool.core.start.StartPrefs(seen = "999.0.0")))
}

/** Plain effect monsters under [ids], written into the card pool the way a sync writes them. */
private fun seedPool(app: MasterToolApplication, ids: List<Int>) {
    val database = com.kaiharimoto.mastertool.core.data.DatabaseFactory.create(AndroidDatabaseDriverFactory(app))
    database.transaction {
        ids.forEach { id ->
            database.cardQueries.insert(
                id = id.toLong(), name = "Card $id", type = "Effect Monster", frameType = "effect",
                description = "", race = "Spellcaster", attribute = "DARK", atk = 0L, def = 0L, level = 4L,
                linkValue = null, linkMarkers = "", pendulumScale = null, archetype = null,
                imageUrl = null, imageUrlSmall = null, tcgBanStatus = "UNLIMITED", ocgBanStatus = "UNLIMITED",
                alternateIds = "$id",
            )
        }
    }
}

/**
 * The emulator's own launcher stalls on a slow CI machine, and Android puts up "Pixel
 * Launcher isn't responding" over whatever is in front — it took the walk's taps and
 * Backs while hide_error_dialogs was set. It is the emulator's, not Neue's: the walk
 * answers it, Wait, before each gesture and each Back. True when there was one.
 */
private fun answerSystemDialogs(): Boolean {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    var answered = false
    repeat(3) {
        val root = automation.rootInActiveWindow ?: return answered
        if (root.packageName?.toString() == "com.kaiharimoto.mastertool") return answered
        val wait = root.findAccessibilityNodeInfosByText("Wait").firstOrNull { it.isClickable || it.parent?.isClickable == true }
            ?: return answered
        (if (wait.isClickable) wait else wait.parent).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        answered = true
        Thread.sleep(500)
    }
    return answered
}
