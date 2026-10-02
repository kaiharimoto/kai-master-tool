package com.kaiharimoto.mastertool.core.compat

import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.backup.BackupManifest
import com.kaiharimoto.mastertool.core.backup.Backups
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.sync.Manifest
import com.kaiharimoto.mastertool.core.sync.Sync
import com.kaiharimoto.mastertool.core.sync.SyncPrefs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What older versions wrote still reads, and what newer ones write does not break an older reader
 * (1.0.69, kai: "progress in my current version … might be outdated by changes we make in the future").
 * Each stored shape is held here as an older build left it; a change that stops one reading fails here
 * before it reaches a device. A release that changes what is stored adds its old shape to this file.
 */
class OldDataTest {
    /** As `PreferencesRepository` reads its documents. */
    private val prefs = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun settingsFromTheFirstDesktopBuildsStillRead() {
        // 1.0.9: a handful of keys, the old autosave name, a field read by nothing since.
        val old = """{"theme":"INK","scale":1.25,"poolVisible":false,"lensKeys":true,"autoSaveOn":false}"""
        val p = prefs.decodeFromString(NeuePreferences.serializer(), old).sanitised()
        assertEquals(NeueTheme.INK, p.theme)
        assertEquals(1.25f, p.scale)
        assertEquals(false, p.autoSave)
        // Everything added since comes in at its default.
        assertEquals(SyncPrefs.OFF, p.sync.service)
        assertEquals("", p.start.seen)
        assertTrue(p.ai.enabled)
    }

    @Test
    fun settingsFromALaterVersionStillReadHere() {
        val future = """{"theme":"PAPER","somethingNew":{"a":1},"ai":{"name":"Kai","futureField":true},"sync":{"service":"folder","folder":"/x","later":2}}"""
        val p = prefs.decodeFromString(NeuePreferences.serializer(), future)
        assertEquals("Kai", p.ai.name)
        assertEquals("/x", p.sync.folder)
    }

    @Test
    fun syncFilesFromALaterVersionStillRead() {
        val m = Sync.json.decodeFromString(Manifest.serializer(), """{"device":"d1","items":{"decks/a.json":{"hash":"ab","at":5,"device":"d1","signature":"x"}},"format":2,"extra":[]}""")
        assertEquals("ab", m.items.getValue("decks/a.json").hash)
        val b = Backups.json.decodeFromString(BackupManifest.serializer(), """{"format":2,"version":"1.0.90","at":1,"reason":"Weekly","encrypted":false}""")
        assertEquals("1.0.90", b.version)
    }

    @Test
    fun aReadersGuideFrom1067StillReads() {
        val old = """{"title":"Labrynth","bigIdea":"Set and pass.","chapters":[{"id":"lessons","title":"Lessons","sections":[
            {"id":"lessons/a","title":"A","blocks":[{"type":"text","text":"Words"},{"type":"lesson","maxim":"Open Welcome","card":"Welcome Labrynth","number":"74%"}]}]}]}"""
        val book = assertNotNull(GuideBook.read(old))
        assertEquals(2, book.chapters.single().sections.single().blocks.size)
    }

    @Test
    fun aPresentationFrom1070StillReads() {
        // 1.0.70: the first shape Present wrote, a deck slide and a freeform one.
        val old = """{"id":"pabc","name":"Labrynth profile","style":"BUILD_UP","theme":"arena",
            "deck":{"deckId":"d1","name":"Labrynth","main":[1,1,2],"groups":[{"id":"g","name":"Engine"}],"assignments":{"1":"g"}},
            "webcam":{"enabled":true,"preset":"RIGHT_COLUMN"},
            "slides":[{"id":"s1","layout":"DECK","deck":{"groups":["g"],"note":"Open these"}},
            {"id":"s2","elements":[{"id":"e","type":"TEXT","x":0.1,"y":0.1,"w":0.8,"h":0.2,"anchor":"STAGE",
              "paras":[{"runs":[{"text":"Hi","style":{"weight":700,"color":"@accent"}}]}],
              "animations":[{"id":"a","effect":"RISE"}]}],"notes":"Say hello"}]}"""
        val p = assertNotNull(PresentCodec.decode(old))
        assertEquals("BUILD_UP", p.style)
        assertEquals("g", p.deck?.groupOf(1))
        assertEquals("Open these", p.slides[0].deck?.note)
        assertEquals("Hi", p.slides[1].elements[0].plainText)
        assertEquals("Say hello", p.slides[1].notes)
        assertTrue(p.webcam.enabled)
    }
}
