package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.AiModes
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The architecture's laws (Phase I, I.5; F2): read off the sources, as the Master UI laws are, so a seam is kept by a test
 * and not by memory.
 *
 * - **Every tool offered is answered**: a tool in `AiTools.all` that no handler answers falls through to "not in this
 *   version of the app yet" — a model would be offered something it can never use.
 * - **One list per fact**: the pages Ai may go to are the app's pages.
 * - **The duel reaches Ai through its seams**: the duel's screens that speak with Ai are named here; any other that
 *   imports from `neue.ai` has grown a new seam, which is a decision, not an accident.
 * - **`:core` never reaches the app**: the module graph refuses it at compile time; this says why, where it can be read.
 */
class ArchitectureLawTest {
    private val roots = listOf(File("src"), File("../table/src"))
    private val sources: List<File> =
        roots.flatMap { r -> r.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith("Main") } }
            .flatMap { File(it, "kotlin").walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    @Test
    fun theSourcesAreThere() = assertTrue(sources.size > 100, "read ${sources.size} files")

    @Test
    fun everyToolOfferedIsAnswered() {
        // A branch of a `when` on the tool's name ("name" ->, "a", "b" ->) or a test of it (name == "name").
        val branch = Regex(""""([a-z][a-z0-9_]*)"\s*(?:,\s*"[a-z][a-z0-9_]*"\s*)*->""")
        val listed = Regex("""((?:"[a-z][a-z0-9_]*"\s*,\s*)+"[a-z][a-z0-9_]*")\s*->""")
        val equals = Regex("""==\s*"([a-z][a-z0-9_]*)"""")
        val answered = HashSet<String>()
        sources.forEach { f ->
            val s = f.readText()
            branch.findAll(s).forEach { answered += it.groupValues[1] }
            listed.findAll(s).forEach { m -> Regex(""""([a-z0-9_]+)"""").findAll(m.groupValues[1]).forEach { answered += it.groupValues[1] } }
            equals.findAll(s).forEach { answered += it.groupValues[1] }
        }
        val offered = AiTools.all.map { it.name }
        val missing = offered.filter { it !in answered }
        assertTrue(offered.size > 100, "read ${offered.size} tools")
        assertTrue(missing.isEmpty(), "offered to Ai, answered by nothing: $missing")
    }

    @Test
    fun everyModeOfAConversationHasItsRow() {
        val modes = AiSession::class.java.declaredFields.filter { it.name.startsWith("MODE_") && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        assertTrue(modes.size > 10, "read ${modes.size} modes")
        assertEquals(modes.toSet(), AiModes.ALL.map { it.id }.toSet(), "every mode is a row of AiModes, one list per fact")
    }

    @Test
    fun thePagesAiMayGoToAreTheAppsPages() {
        assertEquals(Page.entries.map { it.name }.toSet(), AiTools.PAGES.toSet(), "AiTools.PAGES is Page, one list per fact")
    }

    /** The duel's screens that speak with Ai: the table's host for Ai, its cues, Ai vs Ai, voice, and Ai's name and face. */
    private val duelSeams = setOf("NeueTable.kt", "DuelAi.kt", "DuelAiCues.kt", "DuelMatches.kt", "DuelVoice.kt", "DuelPage.kt", "DuelReplay.kt")

    @Test
    fun theDuelReachesAiThroughItsSeams() {
        val duel = sources.filter { it.invariantSeparatorsPath.contains("/kotlin/com/kaiharimoto/neue/duel/") }
        assertTrue(duel.size > 10, "read ${duel.size} of the duel's files")
        val grown = duel.filter { f -> f.name !in duelSeams && f.readLines().any { it.startsWith("import com.kaiharimoto.neue.ai.") && !it.startsWith("import com.kaiharimoto.neue.ai.chessy.") } }
        if (grown.isNotEmpty()) fail("A duel screen reaches into Ai outside its seams (name it in duelSeams if it should): ${grown.map { it.path }}")
    }

    @Test
    fun coreNeverReachesTheApp() {
        val core = File("../core/src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(core.size > 100, "read ${core.size} of :core's files")
        val reaching = core.filter { f -> f.readLines().any { it.startsWith("import com.kaiharimoto.neue.") || it.startsWith("import com.kaiharimoto.mastertool.ui.") } }
        if (reaching.isNotEmpty()) fail(":core imports the app: ${reaching.map { it.path }}")
    }
}
