package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.ai.library.DiskLibraryFiles
import com.kaiharimoto.mastertool.core.ai.library.LibraryCatalog
import com.kaiharimoto.mastertool.core.ai.library.LibraryKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStoreTest {
    private val dir: File = Files.createTempDirectory("world").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun anAppIsItsFolder() {
        val store = AppStore(dir)
        store.make(AppManifest("hand-odds", "Hand odds", "calculator", "odds", "HO"), "function init(){return {};}", 5).getOrThrow()
        assertTrue(File(dir, "apps/hand-odds/app.json").isFile)
        assertTrue(File(dir, "apps/hand-odds/main.js").isFile)
        assertEquals(listOf("hand-odds"), store.list().map { it.slug })
        assertEquals(AppCode("hand-odds", 1, "function init(){return {};}"), store.appCode("hand-odds"))
        assertTrue(store.make(AppManifest("hand-odds"), "x", 6).isFailure, "a slug is one app's")
        assertTrue(store.make(AppManifest("Bad Slug"), "x", 6).isFailure)
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") }, "nothing half-written is left")
    }

    @Test
    fun aStateThatWillNotReadIsSetAsideAndStartFreshKeepsTheOld() {
        val store = AppStore(dir)
        store.make(AppManifest("t"), "function init(){return {};}", 1).getOrThrow()
        assertIs<StateRead.Fresh>(store.readState("t"))
        store.writeState("t", """{"games":[1,2]}""")
        assertEquals(StateRead.Ok("""{"games":[1,2]}"""), store.readState("t"))
        File(dir, "apps/t/state.json").writeText("{ broken")
        assertIs<StateRead.Broken>(store.readState("t"))
        assertEquals("{ broken", File(dir, "apps/t/state.broken.json").readText(), "never lost")
        assertIs<StateRead.Fresh>(store.readState("t"))
        store.writeState("t", """{"v":1}""")
        store.startFresh("t")
        assertEquals("""{"v":1}""", File(dir, "apps/t/state.prev.json").readText())
        assertIs<StateRead.Fresh>(store.readState("t"))
        assertTrue(runCatching { store.writeState("t", "x".repeat(AppLimits.STATE + 1)) }.isFailure)
    }

    @Test
    fun aFolderWithoutCodeIsNoApp() {
        File(dir, "apps/empty").mkdirs()
        File(dir, "apps/empty/app.json").writeText("""{"name":"Empty"}""")
        File(dir, "apps/Not-A-Slug").mkdirs()
        assertTrue(AppStore(dir).list().isEmpty())
        assertNull(AppStore(dir).manifest("empty"))
    }

    @Test
    fun theLibraryReadsTheDataFolderAndNothingOutsideIt() {
        val data = File(dir, "data")
        File(data, "ai/guides").mkdirs()
        File(data, "ai/guides/d1.md").writeText("# Guide\n\n- Open Aluber.")
        File(data, "ai/guides/d1.md.tmp").writeText("half")
        File(dir, "outside.md").writeText("secret")
        val files = DiskLibraryFiles(data)
        assertEquals(listOf("ai/guides/d1.md"), files.list("ai").map { it.path })
        assertEquals("# Guide\n\n- Open Aluber.", files.text("ai/guides/d1.md"))
        listOf("../outside.md", "ai/../../outside.md", "/etc/passwd", "ai\\guides\\d1.md", "C:/x").forEach { assertNull(files.reader(it), it) }
        assertTrue(files.list("..").isEmpty())
        val cat = LibraryCatalog.build(files, mapOf("d1" to "Branded"))
        assertEquals(LibraryKind.GUIDE, cat.docs.single().kind)
    }
}
