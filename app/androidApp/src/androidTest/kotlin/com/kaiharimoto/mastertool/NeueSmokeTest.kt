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

    @Test
    fun neueOpensOntoTheTabletsDeck() {
        val app = ApplicationProvider.getApplicationContext<MasterToolApplication>()
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
                scenario.onActivity { activity -> opened = activity.neue?.builder?.deckName }
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
    @Test
    fun aFingerFindsRoomAndWaysOut() {
        val app = ApplicationProvider.getApplicationContext<MasterToolApplication>()
        val deck = Deck(
            main = List(40) { CardId(listOf(14558127, 23434538, 27204311, 81497285)[it % 4]) },
            extra = emptyList(),
            side = emptyList(),
        )
        runBlocking { app.deckRepository.save("touch-deck", "Touch deck", deck, null) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Android's own "Viewing full screen" note appears the first time any app goes
        // immersive and takes the first Back for itself; it is the system's, not Neue's.
        instrumentation.uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed").close()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun <T> on(read: (MainActivity) -> T): T {
                var out: T? = null
                scenario.onActivity { out = read(it) }
                @Suppress("UNCHECKED_CAST")
                return out as T
            }
            fun shoot(name: String) {
                instrumentation.waitForIdleSync()
                Thread.sleep(1200)
                val shot = instrumentation.uiAutomation.takeScreenshot() ?: return
                File(app.getExternalFilesDir(null), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            fun back() {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                Thread.sleep(400)
            }
            fun tap(xDp: Float, yDp: Float) {
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
                instrumentation.waitForIdleSync()
                Thread.sleep(400)
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
            instrumentation.waitForIdleSync()
            back()
            assertFalse("Back left the help open", on { it.neue!!.neue.helpOpen })
            assertFalse("Back closed the app with the help open", on { it.isFinishing })

            // From another page, Back comes home to the builder before it leaves.
            on { it.neue!!.neue.go(Page.DECKS) }
            shoot("03-decks.png")
            back()
            assertEquals(Page.BUILDER, on { it.neue!!.neue.page })
            assertFalse("Back left the app from the Decks page", on { it.isFinishing })

            // Immersive: a tap on the paper strip along the top brings the bar out.
            on { it.neue!!.neue.immersive = true }
            Thread.sleep(1500)
            tap(640f, 12f)
            assertTrue("a tap on the top strip did not bring the bar out", on { it.neue!!.neue.revealed.top })
            shoot("04-immersive-bar.png")
            back()
            assertFalse("Back did not leave immersive", on { it.neue!!.neue.immersive })

            assertFalse("the activity recorded a crash", File(app.filesDir, "last-crash.txt").exists())
        }
    }
}
