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

    /** The table's common code (`:table`), compiled for the desktop, Android and the browser (the Lounge). */
    private val table = File("../table/src/commonMain/kotlin")

    private val forbidden = Regex(
        """^\s*import\s+(java\.awt|javax\.swing|javax\.imageio|java\.net\.http|org\.jetbrains\.skia|org\.jetbrains\.skiko|androidx\.compose\.ui\.window\.(Window|application|FrameWindowScope|WindowState|rememberWindowState|WindowPlacement|WindowPosition|DialogWindow|MenuBar)|androidx\.compose\.ui\.ImageComposeScene|androidx\.compose\.foundation\.(TooltipArea|VerticalScrollbar|rememberScrollbarAdapter|ContextMenu|LocalContextMenuRepresentation)|android\.|kotlinx\.coroutines\.swing)""",
    )
    private val forbiddenCalls = Regex("""\bProcessBuilder\(|\bRuntime\.getRuntime\(|java\.awt\.|org\.jetbrains\.skia\.|\bonPointerEvent\(""")

    @Test
    fun theSharedSourcesAreThere() {
        val count = shared.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue(count > 30, "Read $count files from ${shared.absolutePath}")
        val tableCount = table.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue(tableCount > 20, "Read $tableCount files from ${table.absolutePath}")
    }

    @Test
    fun nothingSharedIsOnePlatforms() {
        val breaches = (shared.walkTopDown() + table.walkTopDown()).filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (forbidden.containsMatchIn(code) || forbiddenCalls.containsMatchIn(code)) "${file.relativeTo(File("."))}:${i + 1} · ${line.trim()}" else null
            }
        }.toList()
        if (breaches.isNotEmpty()) fail(breaches.joinToString("\n", prefix = "\n"))
    }

    /** Every source an APK runs: the shared and Android code of each module it is built from. */
    private val apkSources = listOf(
        shared, table, File("src/androidMain/kotlin"), File("../core/src/commonMain/kotlin"),
        File("../builder/src/commonMain/kotlin"), File("../builder/src/androidMain/kotlin"), File("../table/src/androidMain/kotlin"),
    )

    /**
     * Android compiles a regex with ICU, which refuses what the JDK lets through: a bare `}`, a `{` that opens no
     * quantifier, and braces in a class (ICU reads `[{ab}]` as a string). The JDK's tests pass and the tablet dies the
     * first time the pattern is touched — `LimitationParser`'s `\{\{[^{}]*}}` did, at the banlist refresh. So every
     * brace in a pattern here is escaped, a quantifier or a `\p{…}`.
     */
    @Test
    fun everyPatternCompilesOnAndroid() {
        assertTrue(RegexBraces.problems("""\{\{[^{}]*}}""") > 0, "The check must see the pattern that crashed the APK")
        assertTrue(RegexBraces.problems("""\{\{[^\{\}]*\}\}""") == 0)
        assertTrue(RegexBraces.problems("""[^\p{L}\p{N}]+\d{1,3}x{2,}""") == 0)
        val breaches = apkSources.flatMap { it.walkTopDown() }.filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            RegexBraces.literals(text).filter { RegexBraces.problems(it.second) > 0 }.map { (at, pattern) ->
                "${file.relativeTo(File("."))}:${text.substring(0, at).count { it == '\n' } + 1} · $pattern"
            }
        }.toList()
        if (breaches.isNotEmpty()) fail(breaches.joinToString("\n", prefix = "\n"))
    }
}

/**
 * Every scope that outlives a screen is `Background.scope` (Phase H, H.1): a supervisor with a handler, so a job that throws is
 * written down and said, never the app's end. A bare `CoroutineScope(…)`, `MainScope()` or `GlobalScope` launches work whose
 * throw goes to the thread — on Android, and on the desk through the crash handler, that closes the app.
 */
class BackgroundScopeLawTest {
    private val roots = listOf(
        "src/sharedMain/kotlin", "src/jvmMain/kotlin", "src/androidMain/kotlin",
        "../table/src", "../builder/src", "../androidApp/src/main",
    ).map(::File)
    private val bare = Regex("""(?<![\w.])CoroutineScope\(|(?<!\w)MainScope\(\)|\bGlobalScope\b|kotlinx\.coroutines\.(CoroutineScope\(|MainScope\()""")

    @Test
    fun everyLongLivedScopeIsBackgrounds() {
        assertTrue(roots.sumOf { r -> r.walkTopDown().count { it.isFile && it.extension == "kt" } } > 300, "read the shipped sources")
        val breaches = roots.flatMap { it.walkTopDown() }.filter { it.isFile && it.extension == "kt" && "/test/" !in it.path && "Test/" !in it.path }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (bare.containsMatchIn(code)) "${file.relativeTo(File("."))}:${i + 1} · ${line.trim()}" else null
            }
        }.toList()
        if (breaches.isNotEmpty()) fail("Make it Background.scope(\"what it is\", dispatcher):" + breaches.joinToString("\n", prefix = "\n"))
    }

    /**
     * A bare `Thread(…)`, `Thread { … }` or `thread(…)` hands what its body throws to the default handler, which writes a crash
     * and closes the app — the microphone's write on a full disk did, and Chessy's sounds' render could (the red team, 2026-10).
     * Every thread is `Background.thread("what it is") { … }`, and an executor's are `Background.threads("…")`: what they
     * throw is written down and said.
     */
    private val bareThread = Regex(
        """(?<![\w.])Thread\s*[({]|(?<![\w.])thread(\(|\s*\{)|java\.lang\.Thread\s*[({]|kotlin\.concurrent\.thread\s*[({]""",
    )

    /**
     * Files that may make a thread of their own, each because it already catches everything its threads throw. None today: every
     * one became `Background.thread`. A file added here says why in its line.
     */
    private val threadsAllowed: Map<String, String> = emptyMap()

    @Test
    fun everyThreadIsBackgrounds() {
        // The check sees what it is for, and passes what it is not.
        listOf("Thread({ loop() }, \"x\")", "Thread { run() }", "thread(isDaemon = true) {", "thread {", "object : Thread(\"x\") {", "java.lang.Thread(r)")
            .forEach { assertTrue(bareThread.containsMatchIn(it), it) }
        listOf("Background.thread(\"x\") { }", "Thread.sleep(20)", "Thread.currentThread()", "newFixedThreadPool(2, f)", "ThreadFactory { r -> }", "off the main thread (1.0.92)")
            .forEach { assertTrue(!bareThread.containsMatchIn(it), it) }
        val breaches = roots.flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" && "/test/" !in it.path && "Test/" !in it.path && it.name !in threadsAllowed }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    val prose = code.trimStart().let { it.startsWith("*") || it.startsWith("/*") }
                    if (!prose && bareThread.containsMatchIn(code)) "${file.relativeTo(File("."))}:${i + 1} · ${line.trim()}" else null
                }
            }.toList()
        if (breaches.isNotEmpty()) fail("Make it Background.thread(\"what it is\") { … } (an executor's: Background.threads(\"…\")):" + breaches.joinToString("\n", prefix = "\n"))
    }
}

/** The regex literals in Kotlin source, and the braces in a pattern Android's ICU would refuse. */
internal object RegexBraces {
    private val RAW = Regex("""(?:Regex|Pattern\.compile)\(\s*\"\"\"(.*?)\"\"\"""", RegexOption.DOT_MATCHES_ALL)
    private val PLAIN = Regex("""(?:Regex|Pattern\.compile)\(\s*"((?:[^"\\]|\\.)*)"""")
    private val TEMPLATE = Regex("""(?<!\\)\$(\{[^}]*\}|[A-Za-z_]\w*)""")
    private val QUANTIFIER = Regex("""\{\d+(,\d*)?\}""")

    /** Each literal's offset in [text] and its pattern, string templates taken out (what they put in is not a literal). */
    fun literals(text: String): List<Pair<Int, String>> =
        RAW.findAll(text).map { it.range.first to it.groupValues[1].replace(TEMPLATE, "") }.toList() +
            PLAIN.findAll(text).map { it.range.first to unescape(it.groupValues[1].replace(TEMPLATE, "")) }.toList()

    /** How many braces in [pattern] are neither escaped, a quantifier nor a `\p{…}`-style escape. */
    fun problems(pattern: String): Int {
        var i = 0
        var inClass = false
        var n = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1)
                    i += if (next != null && next in "pPxN" && pattern.getOrNull(i + 2) == '{') {
                        (pattern.indexOf('}', i + 3).takeIf { it >= 0 } ?: pattern.length) - i + 1
                    } else 2
                    continue
                }
                inClass && c == ']' -> inClass = false
                inClass && (c == '{' || c == '}') -> n++
                !inClass && c == '[' -> {
                    inClass = true
                    if (pattern.getOrNull(i + 1) == '^') i++
                    if (pattern.getOrNull(i + 1) == ']') i++
                }
                !inClass && c == '{' -> {
                    val q = QUANTIFIER.find(pattern, i)?.takeIf { it.range.first == i }
                    if (q != null) {
                        i = q.range.last + 1
                        continue
                    }
                    n++
                }
                !inClass && c == '}' -> n++
            }
            i++
        }
        return n
    }

    /** A plain Kotlin string's escapes, read as the compiler does. */
    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    'u' -> {
                        append(s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> append(e)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
