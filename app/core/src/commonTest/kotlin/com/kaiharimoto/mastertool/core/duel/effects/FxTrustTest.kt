package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.NET
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the goldfish trusts (D.md §11, `FxTrust`): every script that compiles and passes `FxCheck` — UNTESTED, or WARNED with
 * its warnings named — and nothing broken, unsupported or missing; the "played by you" marks per card and script hash, lost
 * when the script changes; and the library's fingerprint, which moves with any trusted script.
 */
class FxTrustTest {
    private val caller = GoldfishFixtures.scripts.first { it.card == CALLER }
    private val net = GoldfishFixtures.scripts.first { it.card == NET }

    @Test
    fun untestedAndWarnedAreUsedAndBrokenUnsupportedAndMissingAreInert() {
        val broken = FxEntry(SAGE, compiled = FxRead.Script(GoldfishFixtures.scripts.first { it.card == SAGE }), report = FxReport(SAGE, listOf(FxFinding(FxLevel.ERROR, "x", "bad"))))
        val unsupported = FxEntry(WALL, compiled = FxRead.Script(CardScript(WALL, name = "Pond Wall", unsupported = listOf("all of it"))), report = FxReport(WALL, emptyList()))
        val missing = FxEntry(900_000_699, sourceHash = null, compiled = null)
        val trust = FxTrust(
            mapOf(
                CALLER to GoldfishFixtures.entry(caller),
                NET to GoldfishFixtures.entry(net, warnings = 1),
                SAGE to broken, WALL to unsupported, 900_000_699 to missing,
            ),
        )
        assertEquals(FxStatus.UNTESTED, trust.status(CALLER))
        assertEquals(FxStatus.WARNED, trust.status(NET))
        assertTrue(trust.trusted(CALLER) && trust.trusted(NET))
        listOf(SAGE, WALL, 900_000_699).forEach { assertFalse(trust.trusted(it), "$it") }
        assertEquals(setOf(CALLER, NET), trust.cards)
        assertEquals(setOf(CALLER, NET), trust.book.cards)
        assertTrue(trust.book.trustedOnly && !trust.book.verifiedOnly)
        assertEquals(1, trust.openWarnings(NET).size)
        assertTrue(trust.openWarnings(CALLER).isEmpty())
        // A warning the person accepted is no longer open: the next run does not name it.
        val accepted = GoldfishFixtures.entry(net, warnings = 1).copy(review = FxReview(card = NET, accepted = listOf(FxReview.Accepted("lint-0@e1", "fine"))))
        assertTrue(FxTrust(mapOf(NET to accepted)).openWarnings(NET).isEmpty())
        assertEquals(FxStatus.UNTESTED, FxTrust(mapOf(NET to accepted)).status(NET))
    }

    @Test
    fun aPlayedMarkHoldsForTheScriptItWasPlayedWithAndAnUndoTakesItBack() {
        val h = FxCodec.hash(caller)
        var played = FxMarks.mark(FxPlayed(), listOf(FxPlayedUse(CALLER, h, "e1")), at = 5)
        assertTrue(FxTrust(mapOf(CALLER to GoldfishFixtures.entry(caller)), played).playedByYou(CALLER))
        // A changed script loses its mark.
        val changed = caller.copy(effects = caller.effects.map { it.copy(label = "Call again") })
        assertNotEquals(h, FxCodec.hash(changed))
        assertFalse(FxTrust(mapOf(CALLER to GoldfishFixtures.entry(changed)), played).playedByYou(CALLER))
        // Two uses kept; one undone leaves the mark; both undone removes it.
        played = FxMarks.mark(played, listOf(FxPlayedUse(CALLER, h, "e1")), at = 6)
        assertEquals(2, played.of(CALLER, h)?.uses)
        played = FxMarks.unmark(played, listOf(FxPlayedUse(CALLER, h, "e1")))
        assertEquals(1, played.of(CALLER, h)?.uses)
        played = FxMarks.unmark(played, listOf(FxPlayedUse(CALLER, h, "e1")))
        assertNull(played.of(CALLER, h))
        assertTrue(played.marks.isEmpty())
    }

    @Test
    fun theUsesOfAShortcutAreReadOffItsTags() {
        val tags = listOf(
            FxTag(7, "e1", FxTag.COST, script = "aaa"),
            FxTag(7, "e1", FxTag.ACTIVATE, script = "aaa"),
            FxTag(7, "e1", FxTag.RESOLVE, script = "aaa"),
            FxTag(9, FxTag.PROC, FxTag.PROC, script = "bbb"),
            FxTag(0, FxTag.RULE, FxTag.RULE),
            null,
        )
        val uses = FxMarks.uses(tags) { uid -> when (uid) { 7 -> CALLER; 9 -> NET; else -> null } }
        assertEquals(listOf(FxPlayedUse(CALLER, "aaa", "e1"), FxPlayedUse(NET, "bbb", FxTag.PROC)), uses)
    }

    @Test
    fun theLibraryFingerprintMovesWithATrustedScriptAndNothingElse() {
        val a = FxTrust(mapOf(CALLER to GoldfishFixtures.entry(caller), NET to GoldfishFixtures.entry(net)))
        val deck = listOf(CALLER, NET, 900_000_602)
        val fp = a.library(deck)
        assertEquals(12, fp.length)
        assertEquals(fp, a.library(deck.reversed() + CALLER), "order and copies aside")
        val changed = FxTrust(mapOf(CALLER to GoldfishFixtures.entry(caller.copy(effects = caller.effects.map { it.copy(label = "x") })), NET to GoldfishFixtures.entry(net)))
        assertNotEquals(fp, changed.library(deck))
        // A card that is not in the deck does not move it.
        val more = FxTrust(mapOf(CALLER to GoldfishFixtures.entry(caller), NET to GoldfishFixtures.entry(net), WALL to GoldfishFixtures.entry(GoldfishFixtures.scripts.first { it.card == WALL })))
        assertEquals(fp, more.library(deck))
    }

    @Test
    fun theMarksFileReadsForgivingly() {
        val v1 = """{"marks":[{"card":900000601,"script":"abcdef012345","uses":2,"at":9,"effects":["e1"]}]}"""
        val p = FxMarks.decode(v1)
        assertEquals(2, p.of(900_000_601, "abcdef012345")?.uses)
        assertEquals(FxPlayed(), FxMarks.decode("{"))
        assertEquals(p, FxMarks.decode(FxMarks.encode(p)))
        assertTrue(FxPaths.syncs(FxPaths.PLAYED))
    }
}
