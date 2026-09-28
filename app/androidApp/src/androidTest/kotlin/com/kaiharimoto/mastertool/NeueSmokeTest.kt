package com.kaiharimoto.mastertool

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
