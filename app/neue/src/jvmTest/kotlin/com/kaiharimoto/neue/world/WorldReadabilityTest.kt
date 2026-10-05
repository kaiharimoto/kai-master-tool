package com.kaiharimoto.neue.world

import com.kaiharimoto.neue.world.type.MEASURE_CHARS
import com.kaiharimoto.neue.world.type.WorldType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Ai World's readability rules, held (`docs/world/READABILITY.md`; kai: "set visual guidelines and rules on text sizes
 * and layouts to ensure that items are easy to read at a glance"). A law-style reader of every file under `neue/world/`,
 * beside `MasterUiLawTest`:
 *
 * - every size of text comes from the World's scale (`WorldType.kt`): no raw `.sp` anywhere else there;
 * - no kit text helper smaller than the scale (`kit.Small`, `kit.Help`, `kit.Mono`, `kit.Micro`, `kit.Body`) is imported
 *   there, and no kit style below the floors (`MuType.small`, `help`, `mono`, `micro`, `row`, `body`) is reached for;
 * - the faintest inks (`ink25`, `ink12`) never colour text, and `ink45` never sets words of a reading tier;
 * - the scale itself keeps its floors: body 13 on the desk and 14 on a phone, nothing under 11.
 */
class WorldReadabilityTest {
    private val root = File("src")

    private val world: List<File> =
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith("Main") }
            .flatMap { File(it, "kotlin").walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
            .filter { it.invariantSeparatorsPath.contains("/neue/world/") }

    /** The scale's own file, and the icon painter, whose monogram is sized by the icon's grid, not read as text. */
    private val exempt = setOf("WorldType.kt", "IconPaint.kt")

    private data class Breach(val file: File, val line: Int, val text: String, val rule: String) {
        override fun toString() = "${file.name}:$line · $rule · ${text.trim()}"
    }

    private fun scan(rule: String, pattern: Regex, skip: Set<String> = exempt): List<Breach> =
        world.filter { it.name !in skip }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (!code.trimStart().startsWith("import ") && pattern.containsMatchIn(code)) Breach(file, i + 1, line, rule) else null
            }
        }

    private fun assertNone(breaches: List<Breach>) {
        if (breaches.isNotEmpty()) fail(breaches.joinToString("\n", prefix = "\n"))
    }

    @Test
    fun theFilesAreThere() {
        assertTrue(world.size > 20, "Read ${world.size} files under neue/world/: the test is looking in the wrong place")
    }

    @Test
    fun everySizeIsOnTheScale() = assertNone(scan("§1 · sizes come from WorldType", Regex("""\.sp\b""")))

    @Test
    fun noKitTextBelowTheScale() {
        val imports = Regex("""^import com\.kaiharimoto\.neue\.kit\.(Small|Help|Mono|Micro|Body|MicroLink)$""")
        val breaches = world.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> if (imports.matches(line.trim())) Breach(file, i + 1, line, "§1 · the World's own Small, Help, Mono, Micro, Body") else null }
        }
        assertNone(breaches + scan("§1 · MuType below the floor", Regex("""MuType\.(small|help|mono|micro|row|body)\(""")))
    }

    @Test
    fun faintInkNeverColoursText() = assertNone(
        scan(
            "§5 · ink25 and ink12 are for rules and disabled marks, never words",
            Regex("""\b(Small|Help|Mono|Micro|Body|Heading|MicroLink|MonoLink|MuText|RowText)\([^\n]*color\s*=\s*[\w.]*\bink(25|12)\b"""),
        ),
    )

    @Test
    fun faintInkNeverSetsWordsToRead() = assertNone(
        // ink45 is for meta — a placeholder, a line number, a time, a kind — never for the words of a reading tier.
        scan("§5 · body, label and heading words are ink or ink70", Regex("""\b(Small|Help|Body|Heading)\([^\n]*color\s*=\s*[\w.]*\bink45\b""")),
    )

    @Test
    fun theScaleKeepsItsFloors() {
        val (bodyDesk, bodyPhone) = WorldType.tiers.getValue("body")
        assertTrue(bodyDesk >= WorldType.BODY_FLOOR_DESK && WorldType.BODY_FLOOR_DESK >= 13, "body on the desk: $bodyDesk")
        assertTrue(bodyPhone >= WorldType.BODY_FLOOR_PHONE && WorldType.BODY_FLOOR_PHONE >= 14, "body on a phone: $bodyPhone")
        WorldType.tiers.forEach { (tier, sizes) ->
            assertTrue(sizes.first >= WorldType.FLOOR && sizes.second >= WorldType.FLOOR, "$tier under ${WorldType.FLOOR}: $sizes")
            assertTrue(sizes.second >= sizes.first, "$tier is smaller on a phone than on the desk: $sizes")
        }
        assertTrue(WorldType.MICRO >= 11, "micro caps under 11")
        assertTrue(MEASURE_CHARS in 45..75, "the measure is 45 to 75 characters")
    }
}
