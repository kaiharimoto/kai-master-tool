package com.kaiharimoto.mastertool.core.ai.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * kai: "I don't want there to be a cap to the knowledge" — so the Library searches 50 MB of guide-shaped text by walking
 * it, and is held to it here (`docs/world/DESKTOP.md` §10.3): the first hit under 100 ms, the whole walk under 1.5 s.
 * The best of three passes is judged, so a busy runner does not fail an unchanged search.
 */
class LibraryScaleTest {
    private val docs: List<LibraryDoc>
    private val files: MapLibraryFiles

    init {
        val words = listOf(
            "Aluber", "the", "Despia", "fusion", "Branded", "searches", "Albaz", "and", "sends", "Mirrorjade", "to", "the", "GY",
            "going", "first", "hand", "trap", "Nibiru", "Ash", "Blossom", "Joyous", "Spring", "negates", "a", "search", "line",
        )
        val rnd = kotlin.random.Random(3)
        val map = LinkedHashMap<String, String>()
        val out = ArrayList<LibraryDoc>()
        repeat(DOCS) { d ->
            val sb = StringBuilder(PER_DOC + 200)
            sb.append("# How deck ").append(d).append(" plays\n\n")
            while (sb.length < PER_DOC) {
                if (rnd.nextInt(12) == 0) sb.append("\n## Section ").append(sb.length).append("\n\n")
                sb.append("- ")
                repeat(8 + rnd.nextInt(30)) { sb.append(words[rnd.nextInt(words.size)]).append(if (rnd.nextInt(9) == 0) ", " else " ") }
                sb.append("(${rnd.nextInt(100)} %).\n")
            }
            val path = "ai/guides/d$d.md"
            map[path] = sb.toString()
            out += LibraryDoc(path, LibraryKind.GUIDE, "deck:d$d", "Guide · $d", sb.length.toLong(), 0)
        }
        files = MapLibraryFiles(map)
        docs = out
    }

    @Test
    fun fiftyMegabytesWalkedInUnderASecondAndAHalf() {
        assertTrue(docs.sumOf { it.bytes } >= 50L * 1024 * 1024, "${docs.sumOf { it.bytes }}")
        var firstBest = Long.MAX_VALUE
        var wholeBest = Long.MAX_VALUE
        repeat(3) {
            // The first hit: a word near the top of the first document, as the person sees it while typing.
            val q = LibraryQuery.parse("mirrorjade gy")!!
            val start = TimeSource.Monotonic.markNow()
            var first = -1L
            LibrarySearch.walk(files, docs, q, limit = 1) { if (first < 0) first = start.elapsedNow().inWholeMilliseconds }
            firstBest = minOf(firstBest, first)
            // The whole walk: words that are nowhere, so every character of every document is read.
            val none = LibraryQuery.parse("\"zarc the supreme\"")!!
            val t = TimeSource.Monotonic.markNow()
            val n = LibrarySearch.walk(files, docs, none) { }
            wholeBest = minOf(wholeBest, t.elapsedNow().inWholeMilliseconds)
            assertEquals(0, n)
        }
        println("LibraryScaleTest: first hit ${firstBest} ms, whole 50 MB walk ${wholeBest} ms")
        assertTrue(firstBest in 0..100, "the first hit took $firstBest ms")
        assertTrue(wholeBest < 1_500, "the whole walk took $wholeBest ms")
    }

    private companion object {
        const val DOCS = 20
        const val PER_DOC = 50 * 1024 * 1024 / DOCS + 1
    }
}
