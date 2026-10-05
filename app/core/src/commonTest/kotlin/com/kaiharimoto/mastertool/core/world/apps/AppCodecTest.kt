package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.desk.DeskSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppCodecTest {
    @Test
    fun aManifestRoundTrips() {
        val m = AppManifest("hand-odds", "Hand odds", "calculator", "odds", "HO", "Odds of a card by hand size", DeskSize(520.0, 440.0), 3, 1, "ai", 10, 20)
        assertEquals(m, AppCodec.decode(AppCodec.encode(m), "hand-odds"))
    }

    @Test
    fun aBrokenManifestIsSalvagedToItsSlugAndName() {
        val m = assertNotNull(AppCodec.decode("""{"name":"Hand odds","kind":"calculator","size":"huge","version":"seven"""", "hand-odds"))
        assertEquals("hand-odds", m.slug)
        // Not JSON that closes: whatever reads is kept, the rest defaulted.
        val n = assertNotNull(AppCodec.decode("""{"name":"Hand odds","kind":"calculator","size":{"w":"x"}}""", "hand-odds"))
        assertEquals("Hand odds", n.name)
        assertEquals("calculator", n.kind)
        // No manifest at all: the folder's name.
        assertEquals(AppManifest("tracker"), AppCodec.decode(null, "tracker"))
        assertEquals("tracker", AppCodec.decode("¿", "tracker")!!.slug)
    }

    @Test
    fun theFolderIsTheSlug() {
        // A manifest copied into another folder names that folder's app, never the one it came from.
        assertEquals("copy", AppCodec.decode(AppCodec.encode(AppManifest("original", "Original")), "copy")!!.slug)
    }

    @Test
    fun slugRules() {
        listOf("hand-odds", "a", "x1", "a".repeat(32)).forEach { assertTrue(AppCodec.validSlug(it), it) }
        listOf("", "Hand", "a_b", "a b", "../x", "a".repeat(33), "café").forEach { assertTrue(!AppCodec.validSlug(it), it) }
        listOf("", "Hand", "a_b", "../x").forEach { assertNull(AppCodec.decode("{}", it), it) }
        assertEquals("hand-odds", AppCodec.slugOf("Hand odds!"))
        assertEquals("matchup-tracker-v2", AppCodec.slugOf("  Matchup -- tracker (v2) "))
        assertEquals("app", AppCodec.slugOf("—"))
        assertTrue(AppCodec.slugOf("x".repeat(80)).length <= 32)
    }

    @Test
    fun aNameIsOneCleanLine() {
        assertEquals("Hand odds", AppCodec.cleanName("Hand\u0007 odds\nSecond line"))
        assertEquals(AppLimits.NAME, AppCodec.cleanName("y".repeat(100)).length)
        assertEquals("Odds", AppCodec.cleanName("‮Odds"), "no direction overrides in a title bar")
        assertEquals("hand-odds", AppCodec.decode("""{"name":"\n\n"}""", "hand-odds")!!.name, "an empty name is the slug")
    }

    @Test
    fun anUnknownKindIsKeptAndDrawnAsAViewer() {
        val m = AppCodec.decode("""{"kind":"spreadsheet","glyph":"hologram"}""", "s")!!
        assertEquals("spreadsheet", m.kind)
        assertEquals(AppKind.VIEWER, m.appKind)
        assertEquals("spreadsheet", AppCodec.decode(AppCodec.encode(m), "s")!!.kind, "kept for the newer build that wrote it")
    }

    @Test
    fun aStateThatWillNotReadIsSetAside() {
        assertIs<StateRead.Fresh>(AppCodec.readState(null))
        assertIs<StateRead.Fresh>(AppCodec.readState("  "))
        assertEquals(StateRead.Ok("""{"a":1}"""), AppCodec.readState("""{"a":1}"""))
        assertIs<StateRead.Broken>(AppCodec.readState("""{"a":"""))
        assertIs<StateRead.Broken>(AppCodec.readState("\"" + "x".repeat(AppLimits.STATE + 10) + "\""))
        assertNotNull(AppCodec.stateProblem("x".repeat(AppLimits.STATE + 1)))
        assertNull(AppCodec.stateProblem("{}"))
    }

    @Test
    fun codeIsBounded() {
        assertNotNull(AppCodec.codeProblem(""))
        assertNotNull(AppCodec.codeProblem("x".repeat(AppLimits.CODE + 1)))
        assertNull(AppCodec.codeProblem("function init() { return {}; }"))
    }

    @Test
    fun theFilesLiveUnderApps() {
        assertEquals("apps/hand-odds/app.json", AppPaths.manifest("hand-odds"))
        assertEquals("apps/hand-odds/main.js", AppPaths.code("hand-odds"))
        assertEquals("apps/hand-odds/state.json", AppPaths.state("hand-odds"))
        assertEquals("apps/hand-odds/versions/2.js", AppPaths.version("hand-odds", 2))
    }
}
