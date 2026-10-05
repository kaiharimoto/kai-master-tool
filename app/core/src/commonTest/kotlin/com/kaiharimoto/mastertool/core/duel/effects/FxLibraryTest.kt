package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.sync.InboundPath
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The library's arithmetic (Phase D step 2, D.md §3.3, §6, §7): what a world may write under `lib/effects/`, what travels,
 * what is compiled again (never over a newer build's script), each card's status, the book the table reads — an alternate
 * artwork reading its card's script — and the person's review, which Ai can never write.
 */
class FxLibraryTest {
    private val scout = FxRef.script(FxRef.SCOUT)

    @Test
    fun aWorldWritesSourcesAndHelpersOnly() {
        assertTrue(FxPaths.writable("900000001.js"))
        assertTrue(FxPaths.writable("_example.js"))
        // The compiled script, the person's review and the asked list are the app's own: a script never vouches for itself.
        listOf("900000001.json", "900000001.review.json", "asked.json", "decks/d1.tests.json", "x.js", "../x.js", "a/900000001.js", "0123.js")
            .forEach { assertFalse(FxPaths.writable(it), it) }
        assertTrue(FxPaths.readable("900000001.json"))
        assertFalse(FxPaths.readable("900000001.review.json"))
        assertEquals("900000001.js", FxPaths.mounted("lib/effects/900000001.js"))
        assertNull(FxPaths.mounted("lib/other.js"))
        assertEquals(900_000_001, FxPaths.sourceOf("900000001.js"))
        assertEquals(900_000_001, FxPaths.compiledOf("900000001.json"))
        assertEquals(900_000_001, FxPaths.reviewOf("900000001.review.json"))
    }

    @Test
    fun theLibraryTravelsAndItsCacheNever() {
        listOf("900000001.js", "900000001.json", "900000001.review.json", "_example.js", "asked.json", "decks/d1.tests.json", "goldfish/d1.json")
            .forEach { assertTrue(FxPaths.syncs(it), it) }
        listOf("900000001.js.tmp", "notes.md", "decks/a/b.json", ".hidden.js", "run.py").forEach { assertFalse(FxPaths.syncs(it), it) }
        // fxcache/ is this device's alone: a planted "verified" never arrives by sync or in a backup.
        listOf("fxcache/verdicts.json", "fxcache/900000001.json").forEach { assertNull(InboundPath.safe(it), it) }
        assertEquals("effects/900000001.json", InboundPath.safe("effects/900000001.json"))
        assertTrue(FxPaths.CACHE in InboundPath.DEVICE_FOLDERS)
    }

    @Test
    fun aSourceIsCompiledAgainOnlyWhenItChangedAndNeverOverANewerScript() {
        val compiled = FxRead.Script(scout.copy(source = "aaaaaaaaaaaa"))
        assertFalse(FxShelf.needsCompile(null, null), "no source, nothing to compile")
        assertTrue(FxShelf.needsCompile("aaaaaaaaaaaa", null))
        assertFalse(FxShelf.needsCompile("aaaaaaaaaaaa", compiled))
        assertTrue(FxShelf.needsCompile("bbbbbbbbbbbb", compiled), "a source synced in is compiled and checked again")
        assertTrue(FxShelf.needsCompile("aaaaaaaaaaaa", FxRead.Bad("broken")))
        assertFalse(FxShelf.needsCompile("bbbbbbbbbbbb", FxRead.Newer(2, JsonObject(emptyMap()))), "a newer build's script is kept untouched")
    }

    @Test
    fun aCardsStatus() {
        val ok = FxReport(FxRef.SCOUT, emptyList())
        val warned = FxReport(FxRef.SCOUT, listOf(FxFinding(FxLevel.WARNING, "lint-quick", "x", "e2")))
        val broken = FxReport(FxRef.SCOUT, listOf(FxFinding(FxLevel.ERROR, "ref", "x", "e2")))
        assertEquals(FxStatus.MISSING, FxEntry(FxRef.SCOUT).status)
        assertEquals(FxStatus.UNTESTED, FxEntry(FxRef.SCOUT, "h", FxRead.Script(scout), report = ok).status)
        assertEquals(FxStatus.WARNED, FxEntry(FxRef.SCOUT, "h", FxRead.Script(scout), report = warned).status)
        val accepted = FxReview(card = FxRef.SCOUT, accepted = listOf(FxReview.Accepted("lint-quick@e2", "the text's Quick Effect is the other card's")))
        assertEquals(FxStatus.UNTESTED, FxEntry(FxRef.SCOUT, "h", FxRead.Script(scout), report = warned, review = accepted).status)
        assertEquals(FxStatus.BROKEN, FxEntry(FxRef.SCOUT, "h", FxRead.Script(scout), report = broken).status)
        assertEquals(FxStatus.BROKEN, FxEntry(FxRef.SCOUT, "h", FxRead.Bad("x")).status)
        assertEquals(FxStatus.BROKEN, FxEntry(FxRef.SCOUT, "h", FxRead.Script(scout), compileError = "SyntaxError").status)
        assertEquals(FxStatus.BROKEN, FxEntry(FxRef.SCOUT, "h", FxRead.Newer(2, JsonObject(emptyMap()))).status)
        assertEquals(FxStatus.UNSUPPORTED, FxEntry(FxRef.SCOUT, "h", FxRead.Script(scout.copy(effects = emptyList(), unsupported = listOf("a coin"))), report = ok).status)
        assertEquals(FxStatus.NONE, FxShelf.statusWithout(FxRef.card(FxRef.PAWN)))
        assertEquals(FxStatus.MISSING, FxShelf.statusWithout(FxRef.card(FxRef.LAMP)))
    }

    @Test
    fun theBookHoldsWhatChecksAndEveryPrintingReadsIt() {
        val ok = FxEntry(FxRef.SCOUT, "h", FxRead.Script(scout), report = FxReport(FxRef.SCOUT, emptyList()))
        val lamp = FxRef.script(FxRef.LAMP)
        val broken = FxEntry(FxRef.LAMP, "h", FxRead.Script(lamp), report = FxReport(FxRef.LAMP, listOf(FxFinding(FxLevel.ERROR, "ref", "x"))))
        val book = FxShelf.book(listOf(ok, broken), FxRef.facts::canonical)
        assertSame(scout, book.script(FxRef.SCOUT))
        assertSame(scout, book.script(FxRef.SCOUT_ALT), "an alternate artwork reads its card's script")
        assertNull(book.script(FxRef.LAMP), "a broken script is never played")
        assertEquals(setOf(FxRef.SCOUT), book.cards)
    }

    @Test
    fun onlyThePersonAcceptsAWarningAndAlwaysWithWhy() {
        val r = FxReview(card = FxRef.SCOUT)
        assertNull(FxReviews.accept(r, "lint-quick@e2", "it reads fine", FxReviews.AI, 1), "Ai never accepts a warning")
        assertNull(FxReviews.accept(r, "lint-quick@e2", "  ", FxReviews.PERSON, 1), "an acceptance says why")
        val a = assertNotNull(FxReviews.accept(r, "lint-quick@e2", "the Quick Effect is the other card's", FxReviews.PERSON, 1))
        assertEquals(setOf("lint-quick@e2"), a.keys)
        val again = assertNotNull(FxReviews.accept(a, "lint-quick@e2", "better words", FxReviews.PERSON, 2))
        assertEquals("better words", again.accepted.single().why)
        assertEquals(emptySet(), FxReviews.withdraw(again, "lint-quick@e2").keys)
        assertEquals(again, FxReviews.decode(FxReviews.encode(again)))
    }

    @Test
    fun theLibraryIsCapped() {
        val (kept, over) = FxShelf.capped((1..2_005).toList().reversed())
        assertEquals(FxPaths.MOST_SCRIPTS, kept.size)
        assertEquals(listOf(2_001, 2_002, 2_003, 2_004, 2_005), over)
    }

    @Test
    fun fillingMakesAScriptWhole() {
        val card = FxRef.card(FxRef.SCOUT).copy(description = "Some words.\r\n")
        val f = FxShelf.fill(scout.copy(name = ""), card, "fx.card(1, {})")
        assertEquals("Example Scout", f.name)
        assertEquals(FxCodec.textOf("Some words."), f.text)
        assertEquals(FxCodec.short("fx.card(1, {})"), f.source)
        // The script's own hash never reads the source's.
        assertEquals(FxCodec.hash(f.copy(source = "")), FxCodec.hash(f))
    }
}
