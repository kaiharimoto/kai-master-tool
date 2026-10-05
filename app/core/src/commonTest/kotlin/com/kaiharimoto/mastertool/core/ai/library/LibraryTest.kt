package com.kaiharimoto.mastertool.core.ai.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryCatalogTest {
    private val files = MapLibraryFiles(
        mapOf(
            "ai/guides/d1.md" to "# How Branded plays\n\n- Open Aluber.",
            "ai/guides/d1.book.json" to "{}",
            "ai/decks/d1.md" to "- notes",
            "ai/reports/d1.json" to "[]",
            "ai/evidence/d1.json" to "[]",
            "ai/webs/w1.md" to "- the field",
            "ai/MEMORY.md" to "- lessons",
            "ai/SOUL.md" to "who I am",
            "ai/USER.md" to "- kai",
            "ai/credentials.json" to "secret",
            "ai/guides/gone.md" to "- an old deck's guide",
            "shootout/d1/alone.rubric.md" to "- rubric",
            "shootout/d1/d2.rubric.md" to "- against Snake-Eye",
            "shootout/d1/alone.json" to "{}",
        ),
    )
    private val cat = LibraryCatalog.build(files, mapOf("d1" to "Branded", "d2" to "Snake-Eye"), mapOf("w1" to "YCS Paris"))

    @Test
    fun theShelvesHoldWhatLivesWhereItLives() {
        val deck = cat.shelf(Shelf.THIS_DECK, "d1")
        assertEquals(
            setOf(LibraryKind.GUIDE, LibraryKind.BOOK, LibraryKind.NOTES, LibraryKind.REPORTS, LibraryKind.EVIDENCE, LibraryKind.RUBRIC),
            deck.map { it.kind }.toSet(),
        )
        assertEquals(2, deck.count { it.kind == LibraryKind.RUBRIC })
        assertTrue(deck.any { it.title == "Rubric · against Snake-Eye" })
        assertEquals("Guide · Branded", deck.first { it.kind == LibraryKind.GUIDE }.title)
        assertEquals(listOf("Notes · YCS Paris"), cat.shelf(Shelf.WEBS).map { it.title })
        assertEquals(setOf(LibraryKind.LESSONS, LibraryKind.SOUL, LibraryKind.USER), cat.shelf(Shelf.AI).map { it.kind }.toSet())
        // A deck that is gone keeps its file's name; a file that is not knowledge (keys) is never listed.
        assertTrue(cat.docs.any { it.scope == "deck:gone" })
        assertNull(cat.doc("ai/credentials.json"))
        assertNull(cat.doc("shootout/d1/alone.json"))
        assertEquals(cat.docs.size, cat.shelf(Shelf.EVERYTHING).size)
    }

    @Test
    fun theCatalogueReadsNoText() {
        assertEquals(0, files.opened)
    }

    @Test
    fun countsAreReadOffTheText() {
        assertEquals("Guide · 5 words", LibraryCatalog.count(cat.doc("ai/guides/d1.md")!!, "# How Branded plays\n\n- Open Aluber."))
        assertEquals("Evidence · 0 numbers", LibraryCatalog.count(cat.doc("ai/evidence/d1.json")!!, "[]"))
        assertEquals(12_345, LibraryCatalog.words("word ".repeat(12_345)))
    }

    @Test
    fun knowledgeReadsOnlyWhatIsCatalogued() {
        val k = LibraryKnowledge(files) { cat }
        assertEquals(cat.scoped("deck:d1"), k.list("deck:d1"))
        val (doc, page) = assertNotNull(k.read("ai/guides/d1.md", 0))
        assertEquals(LibraryKind.GUIDE, doc.kind)
        assertTrue("Aluber" in page.text)
        assertNull(k.read("ai/credentials.json", 0), "never a file the catalogue does not list")
        assertNull(k.read("../../etc/passwd", 0))
        assertEquals(listOf("ai/guides/d1.md"), k.search("aluber", null, 10).map { it.doc.path })
    }
}

class LibrarySectionsTest {
    @Test
    fun aDocumentSplitsAtItsHeadings() {
        val text = "Before.\n\n# One\n\nFirst.\n\n## Two\nSecond.\n# Three ###\n"
        val s = LibrarySections.split(text)
        assertEquals(listOf("", "One", "Two", "Three"), s.map { it.title })
        assertEquals(listOf(0, 1, 2, 1), s.map { it.level })
        assertEquals("First.", s[1].blocks.single().text)
        assertEquals(text.indexOf("First."), s[1].blocks.single().offset)
        assertTrue(s[3].blocks.isEmpty())
        assertEquals("#hashtag", LibrarySections.split("#hashtag").single().blocks.single().text, "no space, no heading")
    }

    @Test
    fun blocksAreAtMost4KbAtParagraphBreaks() {
        val para = "word ".repeat(60).trim()
        val text = (1..200).joinToString("\n\n") { "$it $para" }
        val blocks = LibrarySections.split(text).single().blocks
        assertTrue(blocks.size > 1)
        blocks.forEach { b ->
            assertTrue(b.text.length <= LibrarySections.BLOCK, "${b.text.length}")
            assertEquals(b.text, text.substring(b.offset, b.offset + b.text.length), "keyed by its offset")
            assertTrue(b.text.first().isDigit(), "starts at a paragraph")
        }
        // Every paragraph is in some block, once.
        assertEquals(200, blocks.sumOf { b -> b.text.split("\n\n").size })
    }

    @Test
    fun aLineLongerThanABlockIsCut() {
        val blocks = LibrarySections.blocks("y".repeat(10_000), 0, 10_000)
        assertEquals(listOf(4096, 4096, 1808), blocks.map { it.text.length })
    }
}

class LibrarySearchTest {
    private val doc = LibraryDoc("ai/guides/d1.md", LibraryKind.GUIDE, "deck:d1", "Guide", 0, 0)

    private fun hits(text: String, q: String): List<LibraryHit> {
        val files = MapLibraryFiles(mapOf(doc.path to text))
        val out = ArrayList<LibraryHit>()
        LibrarySearch.walk(files, listOf(doc), LibraryQuery.parse(q)!!) { out += it }
        return out
    }

    @Test
    fun wordsMatchWholeAndTheLastAsItIsTyped() {
        val text = "- Open with Aluber.\n- Ash Blossom & Joyous Spring stops it.\n- Darkness and Light."
        assertEquals(listOf(1), hits(text, "ash blossom").map { text.substring(0, it.offset.toInt()).count { c -> c == '\n' } })
        assertEquals(1, hits(text, "jOyOuS").size)
        assertEquals(1, hits(text, "joy").size, "the word being typed matches its start")
        assertEquals(0, hits(text, "joy ").size, "a finished word matches whole")
        assertEquals(0, hits(text, "and aluber").size, "every term on one line")
        assertEquals(1, hits(text, "darkness light").size)
        assertEquals(0, hits(text, "\"light and\"").size)
        assertEquals(1, hits(text, "\"darkness and light\"").size)
        assertEquals(1, hits(text, "\"blossom joyous\"").size, "punctuation is one break")
    }

    @Test
    fun aHitShowsItsLineWithTheMatch() {
        val h = hits("first\n- Ash Blossom & Joyous Spring stops it.", "joyous").single()
        assertEquals(6L, h.offset)
        assertEquals("joyous", h.line.substring(h.ranges[0]).lowercase())
        val long = "x ".repeat(2_000) + "needle " + "y ".repeat(2_000)
        val cut = hits(long, "needle").single()
        assertTrue(cut.line.length <= LibrarySearch.SHOWN)
        assertEquals("needle", cut.line.substring(cut.ranges[0]))
    }

    @Test
    fun aMatchAcrossAWindowIsFoundOnce() {
        // A line longer than a window, the word straddling the cut, and the same word where windows overlap.
        val pad = "a ".repeat(LibrarySearch.WINDOW / 2 - 3)
        val text = pad + "straddle " + "b ".repeat(LibrarySearch.WINDOW) + "end"
        assertEquals(1, hits(text, "straddle").size)
        val lines = (1..50_000).joinToString("\n") { if (it == 31_337) "here is the needle" else "line $it" }
        assertEquals(1, hits(lines, "needle").size)
    }

    @Test
    fun atMostTheLimit() {
        val text = (1..2_000).joinToString("\n") { "hit $it" }
        val files = MapLibraryFiles(mapOf(doc.path to text))
        val out = ArrayList<LibraryHit>()
        assertEquals(LibrarySearch.MAX_HITS, LibrarySearch.walk(files, listOf(doc), LibraryQuery.parse("hit")!!) { out += it })
        assertEquals(LibrarySearch.MAX_HITS, out.size)
    }

    @Test
    fun theNextKeystrokeCancels() = runTest {
        val text = (1..200_000).joinToString("\n") { "line $it with words" }
        val files = MapLibraryFiles(mapOf(doc.path to text))
        val job = Job()
        var seen = 0
        assertFailsWith<CancellationException> {
            withContext(job) {
                LibrarySearch.search(files, listOf(doc), LibraryQuery.parse("words")!!) {
                    seen++
                    if (seen == 3) job.cancel()
                }
            }
        }
        assertTrue(seen < LibrarySearch.MAX_HITS, "it stopped within a window: $seen")
    }

    @Test
    fun nothingToLookFor() {
        assertNull(LibraryQuery.parse(""))
        assertNull(LibraryQuery.parse("x"))
        assertNull(LibraryQuery.parse("  !! "))
        assertNotNull(LibraryQuery.parse("ok"))
    }
}
