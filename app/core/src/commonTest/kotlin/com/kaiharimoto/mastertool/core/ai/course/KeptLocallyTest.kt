package com.kaiharimoto.mastertool.core.ai.course

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The guide kept on this computer, and the replay library (1.1.48). */
class KeptLocallyTest {
    private val start = "https://metafy.gg/@joe/guides/x"

    @Test
    fun aPagesPicturesAreKeptWhenTheyHoldSomething() {
        val images = listOf(
            PageImage(1, "https://cdn.metafy.gg/avatar.png", w = 64, h = 64),
            PageImage(2, "https://cdn.metafy.gg/combo.png", "Aluber line", w = 1200, h = 700),
            PageImage(3, "https://cdn.metafy.gg/combo.png#again", w = 1200, h = 700),
            PageImage(4, "data:image/png;base64,AAAA", w = 400, h = 400),
            PageImage(5, "https://cdn.metafy.gg/board.jpg", w = 900, h = 500),
        )
        assertEquals(listOf(2, 5), PageSnapshots.worth(images).map { it.n })
        assertEquals("png", PageSnapshots.extension(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("jpg", PageSnapshots.extension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertNull(PageSnapshots.extension("<html>".encodeToByteArray()))
        assertEquals("image/webp", PageSnapshots.mediaType("3.webp"))
        assertEquals("[Picture 2: Aluber line]", PageSnapshots.marker(2, "Aluber line"))
        assertEquals(setOf(2, 5), PageSnapshots.marked("Start\n[Picture 2: Aluber line]\nthen [Picture 5]"))
        val snap = PageSnapshot(start + "/1", "Lines", 1, listOf(SavedLink("Replay", "https://www.duelingbook.com/replay?id=1")), listOf(SavedPicture(2, "2.png", "Aluber line")), video = true)
        assertEquals(snap, PageSnapshots.read(PageSnapshots.write(snap)))
    }

    private fun ch(n: Int, state: Chapter.State, saved: Boolean, depth: Int = CourseDepth.CURRENT) =
        Chapter(n, "Ch $n", "$start/$n", state = state, scanned = true, depth = depth, videoChecked = true, watched = true, saved = saved)

    @Test
    fun aPageReadBeforeIsKeptOnceAndThenNeverOpenedAgain() {
        val c = Course("c", start, listed = true)
        // Read but not yet noted: kept first, so its notes are taken with its pictures.
        assertEquals(StudyQueue.Step.Save(1), StudyQueue.next(c.copy(chapters = listOf(ch(1, Chapter.State.READ, saved = false)))))
        assertEquals(StudyQueue.Step.Notes(1), StudyQueue.next(c.copy(chapters = listOf(ch(1, Chapter.State.READ, saved = true)))))
        // Noted before pages were kept: kept, then nothing more of it.
        val noted = c.copy(chapters = listOf(ch(1, Chapter.State.NOTED, saved = false), ch(2, Chapter.State.NOTED, saved = true)))
        assertEquals(StudyQueue.Step.Save(1), StudyQueue.next(noted))
        assertEquals("Keeping chapter 1 of 2 on this computer", StudyQueue.line(noted))
        assertEquals(StudyQueue.Step.Consolidate, StudyQueue.next(noted.copy(chapters = noted.chapters.map { it.copy(saved = true) })))
        // A page that was never read is read, which keeps it.
        assertEquals(StudyQueue.Step.Read(1), StudyQueue.next(c.copy(chapters = listOf(ch(1, Chapter.State.PENDING, saved = false)))))
        // An older build's chapter reads unkept.
        val older = assertNotNull(CourseCodec.read("""{"id":"c","start":"$start","chapters":[{"n":1,"title":"A","url":"$start/1","state":"NOTED"}]}"""))
        assertFalse(older.chapters.single().saved)
        assertEquals(0, older.chapters.single().pictures)
    }

    @Test
    fun theCopyHasThePicturesInPlaceAndNeverTheExamsDuels() {
        val course = Course("c", start, title = "Branded Masterclass", author = "Joe", deckName = "Branded")
        val html = CourseExport.html(
            course,
            listOf(CourseExport.ChapterDoc(1, "Lines <1>", "$start/1", "# Going first\n\nOpen with Aluber.\n\n[Picture 2: Aluber line]\n\n- one\n- two\n\n[Picture 9]", mapOf(2 to "data:image/png;base64,AAAA"))),
            listOf(
                CourseExport.ReplayDoc(1, "Joe vs Ann", "https://www.duelingbook.com/replay?id=1", "Game 1 …", heldOut = false),
                CourseExport.ReplayDoc(2, "Joe vs Bob", "https://www.duelingbook.com/replay?id=2", "SECRET", heldOut = true),
            ),
        )
        assertTrue("<img src=\"data:image/png;base64,AAAA\" alt=\"Aluber line\">" in html, html)
        assertTrue("Lines &lt;1&gt;" in html)
        assertTrue("<li>one</li>" in html && "<h3>Going first</h3>" in html, html)
        // A picture not kept stays a marker; the exam's duel is named, never written.
        assertTrue("[Picture 9]" in html)
        assertFalse("SECRET" in html)
        assertTrue("Held out for Ai's exam" in html)
        assertEquals("Branded Masterclass.html", CourseExport.fileName(course))
    }

    @Test
    fun theLibraryHoldsEveryKeptReplayOnceAndKnowsTheExamsOwn() {
        val r1 = "https://www.duelingbook.com/replay?id=111"
        val r2 = "https://www.duelingbook.com/replay?id=222"
        val r3 = "https://www.duelingbook.com/replay?id=333"
        val a = Course("a", start, title = "A", updatedAt = 2, replays = listOf(
            ReplayRef(1, r1, 4, state = Chapter.State.NOTED, players = "Joe vs Ann", games = 3),
            ReplayRef(2, r2, 4, state = Chapter.State.READ, players = "Joe vs Bob", exam = true),
            ReplayRef(3, r3, 5, state = Chapter.State.PENDING),
        ))
        var added = ReplayLibrary.add(emptyList(), r1, null, now = 5)
        added = ReplayLibrary.add(added, "https://duelingbook.com/replay?id=444", null, now = 6, note = "Locals top cut")
        val all = ReplayLibrary.entries(listOf(a), added)
        // The course's own first (it knows the chapter), one entry a duel, never one not read yet.
        assertEquals(listOf("111", "222", "444"), all.map { it.id })
        assertEquals("a", all.first().course)
        assertTrue(all.first { it.id == "222" }.heldOut)
        assertTrue(all.first { it.id == "444" }.added)
        assertEquals(listOf("444"), ReplayLibrary.search(all, "locals").map { it.id })
        assertEquals(listOf("111"), ReplayLibrary.search(all, "ann").map { it.id })
        assertEquals(listOf("111"), ReplayLibrary.search(all, "branded fusion") { e -> if (e.id == "111") listOf("Branded Fusion") else emptyList() }.map { it.id })
        assertEquals(listOf("111"), ReplayLibrary.remove(added, "444").map { it.id })
        assertEquals(added, ReplayLibrary.readIndex(ReplayLibrary.writeIndex(added)))
    }

    @Test
    fun aReplayIsReadTurnByTurn() {
        val raw = """
            {"player1":{"username":"Joe"},"player2":{"username":"Ann"},
             "plays":[
              {"play":"Enter M1","username":"Joe"},
              {"play":"Normal Summon","username":"Joe","log":{"public_log":"Normal Summoned \"Aluber the Jester of Despia\""}},
              {"play":"Duel message","username":"Ann","message":"nice"},
              {"play":"End turn","username":"Joe"},
              {"play":"Enter DP","username":"Ann"},
              {"play":"Activate","username":"Ann","log":{"public_log":"Activated a card","private_log":"Activated \"Snake-Eye Ash\""}}
             ]}
        """.trimIndent()
        val r = assertNotNull(DbReplays.parse(raw))
        val reading = ReplayReading.of(r)
        val turns = reading.games.single().turns
        assertTrue(turns.any { t -> t.lines.any { it.chat && it.text == "nice" } })
        assertTrue(turns.flatMap { it.lines }.any { "Snake-Eye Ash" in it.text })
        assertEquals(listOf("Aluber the Jester of Despia" to 1), reading.cards["Joe"])
        // What the other player saw: the public words alone.
        assertFalse(ReplayReading.of(r, private = false).games.single().turns.flatMap { it.lines }.any { "Snake-Eye Ash" in it.text })
        assertTrue("Aluber the Jester of Despia" in ReplayReading.cards(r))
    }
}
