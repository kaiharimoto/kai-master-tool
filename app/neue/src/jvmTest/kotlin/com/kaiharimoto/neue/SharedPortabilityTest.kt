package com.kaiharimoto.neue

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `sharedMain` is compiled for the desktop *and* for Android, and on both it
 * sees the JDK — so the compiler lets through an AWT dialog, a `java.net.http`
 * client or a Skia call that would then die on a tablet with a
 * `NoClassDefFoundError`. This reads the shared sources and refuses them: those
 * belong behind a seam, with the desktop's half in `jvmMain` and Android's in
 * `androidMain`.
 */
class SharedPortabilityTest {

    private val shared = File("src/sharedMain/kotlin")

    private val forbidden = Regex(
        """^\s*import\s+(java\.awt|javax\.swing|javax\.imageio|java\.net\.http|org\.jetbrains\.skia|org\.jetbrains\.skiko|androidx\.compose\.ui\.window\.(Window|application|FrameWindowScope|WindowState|rememberWindowState|WindowPlacement|WindowPosition|DialogWindow|MenuBar)|androidx\.compose\.ui\.ImageComposeScene|androidx\.compose\.foundation\.(TooltipArea|VerticalScrollbar|rememberScrollbarAdapter|ContextMenu|LocalContextMenuRepresentation)|android\.|kotlinx\.coroutines\.swing)""",
    )
    private val forbiddenCalls = Regex("""\bProcessBuilder\(|\bRuntime\.getRuntime\(|java\.awt\.|org\.jetbrains\.skia\.|\bonPointerEvent\(""")

    @Test
    fun theSharedSourcesAreThere() {
        val count = shared.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue(count > 30, "Read $count files from ${shared.absolutePath}")
    }

    @Test
    fun nothingSharedIsOnePlatforms() {
        val breaches = shared.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (forbidden.containsMatchIn(code) || forbiddenCalls.containsMatchIn(code)) "${file.relativeTo(File("."))}:${i + 1} · ${line.trim()}" else null
            }
        }.toList()
        if (breaches.isNotEmpty()) fail(breaches.joinToString("\n", prefix = "\n"))
    }
}
