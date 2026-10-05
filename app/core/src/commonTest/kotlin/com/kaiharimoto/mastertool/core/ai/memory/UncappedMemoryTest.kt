package com.kaiharimoto.mastertool.core.ai.memory

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ContextBreakdown
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.sync.SyncMerges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * No cap on what Ai knows of the game (1.1.9, kai: "I don't want there to be a cap to the knowledge"): its notes, a deck's
 * and a web's notes and a deck's guide keep everything; the prompt reads them within a budget, and the memory tool and
 * recall reach the rest.
 */
class UncappedMemoryTest {
    /** The caps each kind had before 1.1.9. */
    private val oldCaps = mapOf(MemoryKind.AGENT to 2_000, MemoryKind.DECK to 4_000, MemoryKind.WEB to 6_000, MemoryKind.GUIDE to 10_000)

    private fun line(n: Int, label: String = "Lines") =
        "$label: line $n — [[Card $n]] searches [[Piece ${n % 7}]], then a step of the combo, " + "and on into the end board, ".repeat(7)

    @Test
    fun aWritePastTheOldCapIsAcceptedForEveryKindButTheProfile() {
        oldCaps.forEach { (kind, old) ->
            assertTrue(!kind.bounded, "$kind has no cap")
            var doc = MemoryDoc.blank("x")
            var n = 0
            while (doc.used <= old * 3) {
                doc = assertIs<MemoryWrite.Done>(AiMemory.add(doc, line(n++), kind.limit, kind.entryLimit), "$kind entry $n").doc
            }
            assertTrue(doc.used > old * 3, "$kind holds ${doc.used}, past its old cap of $old")
            // A long, legitimate note — a whole line written out — fits one entry; one past the ceiling does not.
            val long = "Lines: " + "the full line, step by step, with every choice and why. ".repeat(110)
            assertTrue(long.length in 5_001..ENTRY_CEILING, "${long.length}")
            assertIs<MemoryWrite.Done>(AiMemory.add(doc, long, kind.limit, kind.entryLimit))
            assertIs<MemoryWrite.Refused>(AiMemory.add(doc, "x".repeat(ENTRY_CEILING + 1), kind.limit, kind.entryLimit))
            assertTrue(assertIs<MemoryWrite.Done>(AiMemory.replace(doc, "line 0 ", line(9_999), kind.limit, kind.entryLimit)).doc.used > old)
        }
        // The profile is always in the prompt whole: it keeps its cap.
        val user = MemoryKind.USER
        assertTrue(user.bounded)
        var doc = MemoryDoc.blank("you")
        var refused = false
        repeat(40) { n ->
            when (val w = AiMemory.add(doc, "Preferences: note $n " + "x".repeat(200), user.limit, user.entryLimit)) {
                is MemoryWrite.Done -> doc = w.doc
                is MemoryWrite.Refused -> refused = true
            }
        }
        assertTrue(refused && doc.used <= user.limit)
    }

    @Test
    fun theRoomIsAShareOfTheWindowBetweenTheOldCapAndACeiling() {
        assertEquals(80_000, MemoryBudget.chars(MemoryKind.GUIDE, 200_000))
        assertEquals(20_000, MemoryBudget.chars(MemoryKind.AGENT, 200_000))
        assertEquals(200_000, MemoryBudget.chars(MemoryKind.GUIDE, 1_000_000), "a ceiling, even on a million tokens")
        // A small local model still gets what the old caps put in front of it.
        assertEquals(4_000, MemoryBudget.chars(MemoryKind.DECK, 8_000))
        assertEquals(6_000, MemoryBudget.chars(MemoryKind.WEB, 8_000))
        assertEquals(2_000, MemoryBudget.chars(MemoryKind.AGENT, 8_000))
        assertEquals(MemoryBudget.chars(MemoryKind.GUIDE, MemoryBudget.DEFAULT_WINDOW), MemoryBudget.chars(MemoryKind.GUIDE, 0), "no window known")
        assertEquals(MemoryKind.USER.limit, MemoryBudget.chars(MemoryKind.USER, 1_000_000))
    }

    @Test
    fun aFileWithinItsRoomGoesInWholeWithNoIndexLine() {
        val entries = (1..10).map(::line)
        val shown = MemoryBudget.pick(entries, 100_000, kind = MemoryKind.GUIDE, scope = "guide")
        assertNull(shown.index)
        assertEquals(entries, shown.entries)
        assertEquals(entries.joinToString("\n") { "- $it" }, shown.lines())
    }

    @Test
    fun theBudgetPicksTheRelevantEntriesAndSaysHowToReadTheRest() {
        val entries = (0 until 600).map { n ->
            when (n) {
                120 -> "Weak points: [[Nibiru, the Primal Being]] after the fifth summon ends the line; play around it by stopping at four."
                300 -> "Sources: an old article."
                else -> line(n, if (n % 50 == 0) "Game plan" else if (n % 3 == 0) "Sources" else "Lines")
            }
        }
        val budget = 6_000
        val shown = MemoryBudget.pick(entries, budget, MemoryBudget.Focus("What do I do about Nibiru?", listOf("Labrynth")), MemoryKind.GUIDE, "guide")
        val index = assertNotNull(shown.index)
        assertTrue(entries[120] in shown.entries, "the entry about what the person asked")
        assertTrue(shown.lines().length <= budget, "${shown.lines().length} within $budget")
        assertEquals(shown.indices.sorted(), shown.indices, "in the file's own order")
        assertTrue(index.startsWith("(" + MemoryBudget.INDEX_MARK), index)
        assertTrue("${entries.size - shown.entries.size} more of ${entries.size} entries" in index, index)
        assertTrue("memory_read scope guide" in index && "recall scope memory" in index, index)
        assertTrue("Lines" in index && "Sources" in index, "the labels left out: $index")
        assertTrue(shown.lines().endsWith(index), "the index line comes last")
        // With nothing asked, the game plan comes before old sources, and the newest before the oldest.
        val plain = MemoryBudget.pick(entries, budget, kind = MemoryKind.GUIDE, scope = "guide")
        val plans = entries.indices.filter { entries[it].startsWith("Game plan") }
        assertTrue(plans.all { it in plain.indices }, "every game plan entry: ${plain.indices}")
        assertTrue(plain.indices.none { entries[it].startsWith("Sources") }, "no sources while lines are left out")
        val lines = plain.indices.filter { entries[it].startsWith("Lines") }
        assertTrue(lines.isNotEmpty() && lines.min() > 300, "the newest lines first: $lines")
    }

    @Test
    fun aWebsNotesPreferTheOpenDeck() {
        val entries = (0 until 200).map { n -> if (n % 10 == 0) "[Branded] side note $n " + "x".repeat(80) else "[Maliss] note $n " + "y".repeat(80) }
        val shown = MemoryBudget.pick(entries, 2_000, MemoryBudget.Focus("", listOf("Branded")), MemoryKind.WEB, "web")
        assertTrue(shown.entries.all { it.startsWith("[Branded]") }, shown.entries.toString())
        assertTrue("memory_read scope web" in shown.index!!)
    }

    @Test
    fun theMemoryToolReadsByQueryLabelAndRange() {
        val entries = (1..400).map { n -> if (n == 233) "Weak points: [[Nibiru, the Primal Being]] stops the line at five summons." else line(n, if (n % 4 == 0) "Card roles" else "Lines") }
        val doc = MemoryDoc(listOf("# How Labrynth plays"), entries)
        val title = "How Labrynth plays"

        val first = MemoryQuery.read(doc, title)
        assertTrue(first.startsWith("How Labrynth plays — 400 entries"), first)
        assertTrue("[1] " in first && first.length <= MemoryQuery.PAGE, "${first.length}")
        val next = Regex("read on from (\\d+)").find(first)!!.groupValues[1].toInt()
        val second = MemoryQuery.read(doc, title, from = next)
        assertTrue("[$next] " in second && "[${next - 1}] " !in second)

        val range = MemoryQuery.read(doc, title, from = 10, count = 3)
        assertTrue("[10] " in range && "[12] " in range && "[13] " !in range && "[9] " !in range, range)
        assertTrue("read on from 13" in range, range)

        val found = MemoryQuery.read(doc, title, query = "Nibiru")
        assertTrue("[233] Weak points: [[Nibiru" in found, found)
        assertTrue(MemoryQuery.read(doc, title, query = "Kashtira").contains("No entry holds"))

        val roles = MemoryQuery.read(doc, title, label = "card roles")
        assertTrue("Entries under “card roles”: 100" in roles, roles.take(200))
        assertTrue("[4] Card roles:" in roles && "[5] Lines:" !in roles)
        // An alias reads as its section: "combos" are the guide's Lines.
        assertTrue("[1] Lines:" in MemoryQuery.read(doc, title, label = "combos"))
        val missing = MemoryQuery.read(doc, title, label = "Side deck")
        assertTrue("No entry is under" in missing && "Card roles 100" in missing, missing)

        assertTrue("There is no entry 999" in MemoryQuery.read(doc, title, from = 999))
        assertTrue("(empty)" in MemoryQuery.read(MemoryDoc.blank(title), title))
    }

    @Test
    fun recallFindsAnEntryInAnyMemoryFile() {
        val files = mapOf(
            "MEMORY.md" to MemoryDoc.blank("notes").copy(entries = listOf("Check the banlist date before quoting limits.")).render(),
            "guides/d1.md" to MemoryDoc.blank("How Labrynth plays").copy(entries = (1..300).map(::line) + "Weak points: Nibiru at five summons.").render(),
            "webs/w1.md" to MemoryDoc.blank("YCS").copy(entries = listOf("[Maliss] Expect Nibiru in the side.")).render(),
        )
        val hits = MemoryQuery.search(files, "nibiru summons")
        assertEquals("guides/d1.md", hits.first().path)
        assertEquals(301, hits.first().number)
        assertTrue(hits.any { it.path == "webs/w1.md" })
        assertTrue(MemoryQuery.search(files, "banlist").single().path == "MEMORY.md")
    }

    @Test
    fun aMegabyteGuideStaysFastToBudgetReadAndMerge() {
        val entries = (0 until 4_000).map { line(it, listOf("Lines", "Card roles", "Weak points", "Insights", "Notes")[it % 5]) + " #$it" }
        val doc = MemoryDoc(listOf("# How Labrynth plays"), entries)
        val text = doc.render()
        assertTrue(text.length >= 1_000_000, "${text.length}")
        val theirs = doc.copy(entries = entries.drop(40) + (0 until 60).map { "Insights: learned on the phone $it" }).render().encodeToByteArray()
        val mine = doc.copy(entries = entries.dropLast(25) + (0 until 60).map { "Insights: learned on the desk $it" }).render().encodeToByteArray()

        fun timed(block: () -> Unit): Long {
            // The best of three, so a cold start or a busy machine never fails it.
            return (1..3).minOf {
                val mark = TimeSource.Monotonic.markNow()
                block()
                mark.elapsedNow().inWholeMilliseconds
            }
        }
        val budget = timed {
            val shown = MemoryBudget.pick(AiMemory.parse(text).entries, 80_000, MemoryBudget.Focus("how do I beat Nibiru going second", listOf("Labrynth")), MemoryKind.GUIDE, "guide")
            assertNotNull(shown.index)
        }
        val merge = timed {
            val merged = AiMemory.parse(SyncMerges.entries(text.encodeToByteArray(), mine, theirs, mineNewer = true)!!.decodeToString()).entries
            assertEquals(entries.size - 40 - 25 + 120, merged.size)
        }
        val review = timed { assertEquals(1, MemoryReview.diff(mapOf("g" to text), mapOf("g" to mine.decodeToString())).size) }
        val read = timed { assertTrue("[2000]" in MemoryQuery.read(doc, "How Labrynth plays", from = 2_000)) }
        val search = timed { assertTrue(MemoryQuery.search(mapOf("g.md" to text), "card 1234").isNotEmpty()) }
        // Generous: each takes tens of milliseconds; entries × entries work would take seconds.
        listOf("budget" to budget, "merge" to merge, "review" to review, "read" to read, "search" to search).forEach { (what, ms) ->
            assertTrue(ms < 1_500, "$what took $ms ms on a megabyte guide")
        }
    }

    @Test
    fun theMemoryShownInFrontOfAMessageIsCountedAsMemory() {
        val notes = (1..50).joinToString("\n") { "- " + line(it) }
        val block = PromptBuilder.context(listOf("Page: Builder"), MemoryScope(MemoryKind.DECK, "d1", "Labrynth"), notes, scopeChanged = true)
        assertTrue("<memory file=\"decks/d1.md\">" in block && "</memory>" in block)
        assertEquals(MemoryBudget.tagged("decks/d1.md", notes).length, MemoryBudget.taggedChars(block))
        val session = AiSession("s", system = "## Memory\nnone", turns = listOf(ChatTurn.user("hi", block)))
        val slices = ContextBreakdown.of(session, emptyList()).associate { it.label to it.tokens }
        assertTrue((slices["Memory"] ?: 0) >= notes.length / 4, slices.toString())
        assertTrue((slices["What the app showed"] ?: 0) < 50, slices.toString())
    }

    @Test
    fun aSummaryKeepsTheIndexLine() {
        val entries = (1..400).map(::line)
        val shown = MemoryBudget.pick(entries, 5_000, kind = MemoryKind.GUIDE, scope = "guide")
        // What compaction puts after a summary: the guide's block, read again within its room.
        val standing = "\n\n(Still in force after the summary.)\n\n" + MemoryBudget.tagged("guides/d1.md", shown.lines())
        val turns = listOf(ChatTurn.user("one"), ChatTurn.assistant("a"), ChatTurn.user("two"), ChatTurn.assistant("b"))
        val s = AiSession("s", turns = turns, summary = "We studied the deck." + standing, summarized = 2)
        val sent = s.sent.first().parts.filterIsInstance<Part.Context>().joinToString { it.text }
        assertTrue(MemoryBudget.INDEX_MARK in sent && shown.index!! in sent)
    }

    @Test
    fun aRunThatFillsItsRoomLeavesTheRestForTheNext() {
        val refusal = GuideBudget.refusal(0, 20_001, 20_000, "Deep")!!
        assertTrue(GuideBudget.NEXT_RUN in refusal && "no cap" in refusal, refusal)
        val report = SessionReport("d1", "Labrynth", 1, "study", openQuestions = listOf("Is Arias a 2-of?", "Next run: the Snake-Eye matchup.", "next run: side deck vs Maliss"))
        assertEquals(listOf("the Snake-Eye matchup.", "side deck vs Maliss"), GuideBudget.leftOver(report))
        val carry = GuideBudget.carryOver(report)!!
        assertTrue("Snake-Eye matchup" in carry && "side deck vs Maliss" in carry, carry)
        assertNull(GuideBudget.carryOver(report.copy(openQuestions = listOf("Is Arias a 2-of?"))))
        assertNull(GuideBudget.carryOver(null))
        assertTrue("20,000" in GuideBudget.filled(20_000, "Deep"))
    }

    @Test
    fun everyReportIsKept() {
        var log = emptyList<SessionReport>()
        repeat(90) { log = ReportLog.add(log, SessionReport("d1", "Labrynth", it.toLong(), "study")) }
        assertEquals(90, log.size, "the newest 60 before 1.1.9")
    }

    @Test
    fun foldingALongDeckFileIntoAWebKeepsEveryEntryOnce() {
        val web = MemoryDoc(listOf("# YCS"), (1..2_000).map { "web note $it" })
        val deck = MemoryDoc(listOf("# Branded"), (1..2_000).map { "deck note $it" })
        val folded = AiMemory.fold(web, deck, "Branded")
        assertEquals(4_000, folded.entries.size)
        assertEquals(folded, AiMemory.fold(folded, deck, "Branded"))
    }
}
