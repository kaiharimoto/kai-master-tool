package com.kaiharimoto.mastertool.core.ai.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YgoOrgTest {
    private val index = YgoOrg.index(YgoOrgFixture.INDEX).getOrThrow()
    private val name: (Int) -> String? = { index.name(it) }

    @Test
    fun theIndexFindsANameExactlyThenLoosely() {
        assertEquals(12950, index.id("Ash Blossom & Joyous Spring"))
        assertEquals(12950, index.id("ash blossom & joyous spring"))
        assertEquals(13619, index.id("  Called  by the grave "))
        // The quoted forms and the no-break space are found by the name as a player writes it.
        assertEquals(15287, index.id("Infernoble Arms - Durendal"))
        assertEquals(21385, index.id("A Case for K9"))
        assertEquals(12950, index.id("AshBlossom&JoyousSpring"))
        assertNull(index.id("Not A Card"))
        assertNull(index.id("   "))
    }

    @Test
    fun anIdIsNamedByTheNameTheAppKnows() {
        assertEquals(listOf("Ash Blossom & Joyous Spring", "Ghost Ash & Beautiful Spring"), index.names(12950))
        assertEquals("Ghost Ash & Beautiful Spring", index.name(12950) { it.startsWith("Ghost") })
        assertEquals("Ash Blossom & Joyous Spring", index.name(12950))
        // Never a no-break space in a name written out.
        assertEquals("Infernoble Arms", YgoOrg.index("{\"Infernoble\\u00a0Arms\":[7]}").getOrThrow().name(7))
        assertNull(index.name(1))
    }

    @Test
    fun aCardsFaqSkipsWhatKonamiNoLongerHasAndMarksWhatIsUntranslated() {
        val ash = YgoOrg.card(YgoOrgFixture.ASH).getOrThrow()
        assertEquals(12950, ash.id)
        assertEquals("Ash Blossom & Joyous Spring", ash.name)
        assertEquals("2022-03-02", ash.faqDate)
        assertEquals("2020-04-22", ash.translatedDate)
        assertEquals(2, ash.outdatedNotes, "section 0's English-only notes are Konami's no longer")
        assertEquals(1, ash.sections.size)
        val s = ash.sections.single()
        assertEquals("About this card's ①\u2009st effect:", s.label, "the section's own heading, not a note")
        assertEquals(4, s.notes.size)
        assertEquals(listOf(true, false, true, false), s.notes.map { it.translated })
        assertTrue(s.notes[1].text.startsWith("●のいずれか"))
        assertEquals(listOf(6417, 11022, 23940, 24162, 24394), ash.qaIds)

        val called = YgoOrg.card(YgoOrgFixture.CALLED).getOrThrow()
        assertEquals("2025-02-21", called.faqDate)
        assertNull(called.translatedDate)
        assertEquals(0, called.outdatedNotes)
    }

    @Test
    fun aQaReadsWithItsKonamiDateAndTranslationStatus() {
        val q = YgoOrg.qa(YgoOrgFixture.QA_24394).getOrThrow()
        assertEquals(24394, q.id)
        assertEquals(listOf(12950, 23585), q.cards)
        assertEquals(YgoOrg.Status.CONFIRMED, q.status)
        assertTrue(q.translated)
        assertEquals("2026-09-25", q.date, "Konami's date, from the Japanese, not the translation's")
        assertTrue(q.question.startsWith("Can the effect of <<12950>>"))

        val old = YgoOrg.qa(YgoOrgFixture.QA_6417).getOrThrow()
        assertEquals(YgoOrg.Status.UNCONFIRMABLE, old.status)
        assertTrue(old.translated, "an unconfirmable translation is still shown, marked")
        assertEquals("2017-08-04", old.date)
    }

    @Test
    fun anOutdatedTranslationGivesWayToKonamisJapaneseAndNotesAreKept() {
        val q = YgoOrg.qa(YgoOrgFixture.QA_NOTES).getOrThrow()
        assertEquals(YgoOrg.Status.OUTDATED, q.status)
        assertFalse(q.translated)
        assertEquals("できます。", q.answer)
        assertEquals("2026-10-01", q.date)
        assertEquals(listOf("The TCG's text of <<23585>> reads differently here."), q.tcg)
        assertEquals(listOf("responding to a Set card's activation"), q.precedent)

        val gone = YgoOrg.qa(YgoOrgFixture.RETRACTED).getOrThrow()
        assertEquals(YgoOrg.Status.RETRACTED, gone.status)
        assertFalse(gone.status.shown)
    }

    @Test
    fun referencesAreNamed() {
        val text = "Can <<12950>> respond to <<23585>> or <<99999>>?"
        assertEquals("Can Ash Blossom & Joyous Spring respond to Flashforce Sword or card #99999?", YgoOrg.names(text, name))
        assertEquals("no refs", YgoOrg.names("no refs", name))
    }

    @Test
    fun theNewestAreReadFirstAndAPartnerNarrowsThem() {
        val ids = listOf(6417, 24394, 11022, 23940, 24162, 24162)
        assertEquals(listOf(24394, 24162, 23940), YgoOrg.pick(ids, max = 3))
        assertEquals(listOf(24162, 6417), YgoOrg.pick(ids, with = listOf(6417, 24162, 1)))
        assertEquals(emptyList(), YgoOrg.pick(ids, with = emptyList()))
        assertEquals(YgoOrg.MAX_QAS, YgoOrg.pick((1..40).toList()).size)
    }

    @Test
    fun badShapesAreWordsNotCrashes() {
        listOf("", "not json", "[1,2]", "{}", """{"cardId":"x"}""", """{"qaData":{"en":{"id":1}}}""").forEach { bad ->
            val card = YgoOrg.card(bad)
            val qa = YgoOrg.qa(bad)
            assertTrue(card.isFailure && qa.isFailure, bad)
            listOf(card.exceptionOrNull()!!, qa.exceptionOrNull()!!).forEach { e ->
                assertTrue(e is IllegalStateException, "$bad: ${e::class}")
                assertTrue(e.message!!.startsWith("YGOrganization's"), e.message)
            }
        }
        assertTrue(YgoOrg.index("{}").isFailure)
        assertTrue(YgoOrg.index("""{"a":"b"}""").exceptionOrNull()!!.message!!.contains("without any cards"))
        // A card with nothing but its id still reads.
        val bare = YgoOrg.card("""{"cardId":5,"faqData":{"entries":{"1":"oops"}},"qaIndex":["x",3]}""").getOrThrow()
        assertEquals(emptyList(), bare.sections)
        assertEquals(listOf(3), bare.qaIds)
    }

    @Test
    fun theTextSaysWhereEachRulingStands() {
        val ash = YgoOrg.card(YgoOrgFixture.ASH).getOrThrow()
        val qas = listOf(YgoOrgFixture.QA_6417, YgoOrgFixture.QA_24394, YgoOrgFixture.QA_NOTES, YgoOrgFixture.RETRACTED)
            .map { YgoOrg.qa(it).getOrThrow() }
        val text = YgoOrg.text(ash, qas, total = 81, name = name)
        assertTrue(text.startsWith("Konami's OCG documentation for “Ash Blossom & Joyous Spring” — https://db.ygoresources.com/card#12950"))
        assertTrue("FAQ notes (Konami's FAQ updated 2022-03-02, translated 2020-04-22):" in text, text)
        assertTrue("- [untranslated, Konami's Japanese] ●のいずれか" in text)
        assertTrue("(2 older notes no longer in Konami's database left out)" in text)
        assertTrue("Q&A: 3 of 81, newest first (the rest not read):" in text, text)
        // Newest first by Konami's date: the hand-built 2026-10-01, then 24394, then 2017's.
        val order = listOf("#24395", "#24394", "#6417").map { text.indexOf("Q&A $it") }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), text)
        assertTrue("Q&A #24394 — Konami 2026-09-25; translation up to date — https://db.ygoresources.com/qa#24394" in text, text)
        assertTrue("translation unconfirmable (a legacy translation" in text)
        assertTrue("Q: 「Flashforce Sword」に「Ash Blossom & Joyous Spring」をチェーンできますか？" in text, text)
        assertTrue("  TCG caveat (YGOrganization): The TCG's text of Flashforce Sword reads differently here." in text)
        assertTrue("  Precedent for: responding to a Set card's activation" in text)
        assertTrue("(1 retracted by Konami, left out)" in text)
        assertFalse("Gone?" in text)
        assertFalse("<<" in text, "every reference named")

        val shared = YgoOrg.text(ash, emptyList(), total = 0, name = name, with = "Flashforce Sword")
        assertTrue("Q&A shared with “Flashforce Sword”: none." in shared, shared)
    }

    @Test
    fun datesKonamiStampedByMistakeAreSaid() {
        assertTrue(YgoOrg.dateWords("2017-03-24").contains("before Master Rule 4"))
        assertTrue(YgoOrg.dateWords("2022-12-30").contains("by mistake"))
        assertEquals("2025-01-02", YgoOrg.dateWords("2025-01-02"))
    }

    @Test
    fun theCaveatSaysWhichGameAndWhoDecides() {
        listOf("OCG", "TCG", "translated by YGOrganization", "head judge", YgoOrg.NOT_TCG).forEach {
            assertTrue(it in YgoOrg.CAVEAT, it)
        }
        assertTrue("db.ygoresources.com" in YgoOrg.ATTRIBUTION)
        assertEquals("https://db.ygoresources.com/data/card/12950", YgoOrg.cardUrl(12950))
        assertEquals("https://db.ygoresources.com/data/qa/24394", YgoOrg.qaUrl(24394))
    }
}
