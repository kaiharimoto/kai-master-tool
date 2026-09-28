package com.kaiharimoto.neue

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Master UI's linter (`check.mjs`), for Kotlin: it reads every source file in
 * Neue and fails on the things §15 forbids. The kit's own linter reads CSS and
 * TSX; this is the same list of laws, spelled the way Compose spells them.
 *
 * Two files may name a colour, and only two, because kai granted exactly two
 * exceptions: the foil on a card's face (content, §17) and the markers the user
 * draws on their own deck.
 */
class MasterUiLawTest {

    // Every source set's Kotlin: the shared code and each platform's own (1.0.20).
    private val root = File("src")
    private val colourAllowed = setOf("Foil.kt", "Holo.kt", "GroupMarkers.kt")

    private val sources: List<File> =
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith("Main") }
            .flatMap { File(it, "kotlin").walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    /** A file's name for the allow-lists: a platform's half of `ZenShadows.kt` (`ZenShadows.jvm.kt`) is still that file. */
    private val File.unit: String get() = name.substringBefore('.') + ".kt"

    private data class Breach(val file: File, val line: Int, val text: String, val law: String) {
        override fun toString() = "${file.relativeTo(File("."))}:$line · $law · ${text.trim()}"
    }

    private fun scan(law: String, pattern: Regex, allowIn: Set<String> = emptySet()): List<Breach> =
        sources.filter { it.unit !in allowIn }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (pattern.containsMatchIn(code)) Breach(file, i + 1, line, law) else null
            }
        }

    private fun assertNone(breaches: List<Breach>) {
        if (breaches.isNotEmpty()) fail(breaches.joinToString("\n", prefix = "\n"))
    }

    @Test
    fun theSourcesAreThere() {
        assertTrue(sources.size > 20, "Read ${sources.size} files from ${root.absolutePath}; the test is looking in the wrong place")
    }

    @Test
    fun zeroRadius() = assertNone(
        scan("§1 law 2 · zero radius", Regex("""RoundedCornerShape|CircleShape|CutCornerShape|CornerRadius\(|drawRoundRect|clip\(\s*RoundedCorner""")),
    )

    @Test
    fun noDepth() = assertNone(
        // kai's one exception: a card floating in zen casts a shadow, drawn in that file alone.
        scan(
            "§1 law 3 · no shadows or blur",
            Regex("""\.shadow\(|shadowElevation|\.blur\(|BlurEffect|MaskFilter\.makeBlur|BlurMaskFilter|elevation\s*="""),
            allowIn = setOf("ZenShadows.kt"),
        ),
    )

    @Test
    fun noGradientsOutsideTheFoil() = assertNone(
        scan("§1 law 3 · no gradients", Regex("""Brush\.(linear|radial|sweep|horizontal|vertical)Gradient"""), allowIn = colourAllowed),
    )

    @Test
    fun twoFillsOnly() {
        // Black, white, and alphas of them — written as copies of Color.Black / Color.White.
        val literal = Regex("""Color\(\s*0x([0-9A-Fa-f]{8})\s*\)""")
        val breaches = sources.filter { it.unit !in colourAllowed }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                literal.findAll(line.substringBefore("//")).map { it.groupValues[1].uppercase().drop(2) }
                    .firstOrNull { it != "000000" && it != "FFFFFF" }
                    ?.let { Breach(file, i + 1, line, "§1 law 1 · two fills") }
            }
        } + scan(
            "§1 law 1 · two fills",
            Regex("""Color\.(Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\b|Color\(\s*red\s*=|Color\(\s*[0-9.]+f?\s*,"""),
            allowIn = colourAllowed,
        )
        assertNone(breaches)
    }

    @Test
    fun noMaterial() = assertNone(
        scan("§15 · the family builds its own components", Regex("""import androidx\.compose\.material|MasterToolPalette|MasterToolTheme""")),
    )

    @Test
    fun weightsAre400500And700() = assertNone(
        scan("§3 · weights", Regex("""FontWeight\.(SemiBold|ExtraBold|Black|Thin|ExtraLight|Light|W600|W800|W900|W100|W200|W300)""")),
    )

    @Test
    fun noSpringsOrBounces() = assertNone(
        scan("§7 · one easing, no springs", Regex("""\bspring\(|Spring\.|FastOutSlowInEasing|scaleIn\(|scaleOut\(""")),
    )

    @Test
    fun aQuietVoice() {
        // Exclamation marks and the Mac command glyph in string literals (§9).
        val literal = Regex(""""([^"\\]|\\.)*"""")
        val breaches = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                literal.findAll(code).map { it.value }.firstOrNull { s ->
                    (s.contains("!") && !s.contains("!=") && s.trimEnd('"').endsWith("!")) || s.contains("⌘")
                }?.let { Breach(file, i + 1, line, "§9 · no exclamation marks, no ⌘") }
            }
        }
        assertNone(breaches)
    }
}
