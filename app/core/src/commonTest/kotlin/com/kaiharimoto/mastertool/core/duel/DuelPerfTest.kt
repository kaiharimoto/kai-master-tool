package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ASH
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.ai.Secrets
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 1.0.92, the performance release: every cache the duel gained gives the answer the work it saves gave. A catalog asks
 * once a card, a redactor's words are the old redaction's, the tables kept along a log are the fold from the deal, a
 * place indexed is a place walked, and a pile's hidden cards keep one depth.
 */
class DuelPerfTest {

    // ---- a catalog asked once a card ---------------------------------------------------------------------

    @Test
    fun aCachedCatalogAsksOnceACardAndAnswersTheSame() {
        var asked = 0
        val catalog = DuelCatalog.cached { code -> asked++; DuelFixtures.catalog.info(code) }
        val codes = listOf(ASH, DuelFixtures.DROLL, 999, ASH, 999, DuelFixtures.ZEUS, DuelFixtures.DROLL, 999)
        codes.forEach { assertEquals(DuelFixtures.catalog.info(it), catalog.info(it)) }
        // A miss is remembered too.
        assertEquals(codes.distinct().size, asked)
        assertEquals(codes.distinct().size, (catalog as CachedCatalog).asked)
        assertSame(catalog.info(ASH), catalog.info(ASH))
    }

    // ---- a redactor's words are the old redaction's ------------------------------------------------------

    /** The redaction as 1.0.81–1.0.91 made it: a pattern made per name per text. */
    private fun oldRedact(text: String, names: List<String>): Secrets.Redacted {
        var out = text
        val hidden = mutableListOf<String>()
        names.forEach { name ->
            val pattern = Regex("(\\[\\[)?(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])(\\]\\])?", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(out)) {
                hidden += name
                out = pattern.replace(out, "a card")
            }
        }
        return Secrets.Redacted(out, hidden)
    }

    @Test
    fun aRedactorSaysWhatTheRedactionSaid() {
        // Ai at seat 0 with its hand hidden from seat 1: the fixture's real names, and their short names.
        val g = DuelGame.start(header())
        val names = Secrets.names(g.state, 1, 0, DuelFixtures.catalog)
        assertTrue(names.isNotEmpty(), "Ai has hidden cards to keep")
        val words = names + listOf("Ash", "ash blossom", "[[Droll & Lock Bird]]", "POT OF PROSPERITY", "Pendulum Pals", "the", "draw", ",", "Filler")
        val r = Random(7)
        val redactor = Secrets.Redactor(names)
        repeat(300) {
            val text = List(1 + r.nextInt(12)) { words[r.nextInt(words.size)] }.joinToString(if (r.nextBoolean()) " " else ", ")
            val old = oldRedact(text, names)
            assertEquals(old, redactor.redact(text), text)
            assertEquals(old, redactor.once(text), text)
            // Asked again, the same answer, kept.
            assertEquals(old, redactor.redact(text), text)
            assertEquals(old, Secrets.redact(text, g.state, 1, 0, DuelFixtures.catalog), text)
        }
        // No names: the text as it came.
        assertEquals(Secrets.Redacted("Ash Blossom", emptyList()), Secrets.Redactor(emptyList()).redact("Ash Blossom"))
        assertEquals(Secrets.Redacted("Ash Blossom", emptyList()), Secrets.redact("Ash Blossom", g.state, 0, 0, DuelFixtures.catalog))
    }

    // ---- the tables kept along a log are the fold from the deal ----------------------------------------------

    private fun uid(r: Random): Int = if (r.nextBoolean()) DuelFixtures.uid(0, r.nextInt(42)) else DuelFixtures.uid(1, r.nextInt(41))

    private fun place(r: Random): Place {
        val seat = r.nextInt(2)
        return when (r.nextInt(6)) {
            0, 1 -> Place.Zone(seat, ZoneKind.MONSTER, r.nextInt(5))
            2 -> Place.Zone(seat, ZoneKind.SPELL, r.nextInt(5))
            3 -> Place.Zone(seat, ZoneKind.EMZ, r.nextInt(2))
            4 -> Place.Under(uid(r))
            else -> Place.Pile(seat, PileKind.entries[r.nextInt(PileKind.entries.size)], if (r.nextBoolean()) null else r.nextInt(3) - 1)
        }
    }

    /** A log of [n] moves after the deal, many of them refused by the table (a zone taken, a card not there). */
    private fun randomLog(seed: Int, n: Int): DuelRecord {
        val r = Random(seed)
        val h = header(seed = seed.toLong())
        val entries = DuelGame.start(h).entries.toMutableList()
        var group = entries.last().group + 1
        repeat(n) {
            val seat = r.nextInt(2)
            val a = when (r.nextInt(12)) {
                0 -> DuelAction.Draw(seat, 1 + r.nextInt(2))
                1 -> DuelAction.Shuffle(seat, salt = r.nextLong())
                2 -> DuelAction.Chat(seat, "hm")
                3 -> DuelAction.Phase(DuelPhase.entries[r.nextInt(DuelPhase.entries.size)])
                4 -> DuelAction.EndTurn
                5 -> DuelAction.Position(uid(r), CardPosition.entries[r.nextInt(CardPosition.entries.size)])
                6 -> DuelAction.Ping(seat, uid = uid(r))
                else -> DuelAction.Move(uid(r), place(r))
            }
            entries += DuelEntry(entries.size, seat = seat, group = group, action = a)
            if (r.nextInt(3) == 0) group++
        }
        return DuelRecord(h, entries, entries.size)
    }

    private fun fromTheDeal(h: DuelHeader, entries: List<DuelEntry>, n: Int): DuelState =
        DuelSetup.fold(h, entries.subList(0, n.coerceIn(0, entries.size))).first

    @Test
    fun theTableAtAnyEntryIsTheFoldFromTheDeal() {
        for (seed in 1..6) {
            val rec = randomLog(seed, 40 + seed * 37)
            assertTrue(DuelSetup.fold(rec.header, rec.entries).second.isNotEmpty(), "Some entries are refused")
            val g = DuelGame.of(rec)
            assertEquals(fromTheDeal(rec.header, g.entries, g.cursor), g.state)
            val r = Random(seed)
            (0..g.entries.size + 1).shuffled(r).forEach { n -> assertEquals(fromTheDeal(rec.header, g.entries, n), g.stateAt(n), "seed $seed, at $n") }
        }
    }

    @Test
    fun undoAndUndoOverTalkLeaveTheFoldFromTheDeal() {
        for (seed in 11..16) {
            val rec = randomLog(seed, 60 + seed * 13)
            var g = DuelGame.of(rec)
            while (g.canUndo) {
                g = g.undo()
                assertEquals(fromTheDeal(g.header, g.entries, g.cursor), g.state, "undo, seed $seed at ${g.cursor}")
            }
            // Back to the end, then the undo that steps over talk all the way.
            while (g.canRedo) g = g.redo()
            assertEquals(fromTheDeal(g.header, g.entries, g.cursor), g.state)
            var steps = 0
            while (g.canUndoMove && steps++ < 400) {
                g = g.undoMove()
                assertEquals(fromTheDeal(g.header, g.entries, g.cursor), g.state, "undoMove, seed $seed at ${g.cursor}")
            }
            // A new move after an undo, and undone again: still the fold.
            g = g.act(DuelAction.Chat(0, "again"), 0).game.act(DuelAction.Draw(0), 0).game
            assertEquals(fromTheDeal(g.header, g.entries, g.cursor), g.state)
            val u = g.undo()
            assertEquals(fromTheDeal(u.header, u.entries, u.cursor), u.state)
        }
    }

    @Test
    fun anUndoNearTheEndFoldsFromTheTableKeptNotTheDeal() {
        val g = DuelGame.of(randomLog(21, 300))
        val before = DuelCheckpoints.applied
        g.stateAt(g.cursor - 1)
        assertTrue(DuelCheckpoints.applied - before < DuelCheckpoints.EVERY, "folded ${DuelCheckpoints.applied - before} entries")
    }

    // ---- a place indexed is a place walked -------------------------------------------------------------

    @Test
    fun aPlaceIndexedIsThePlaceWalked() {
        for (seed in 31..34) {
            val rec = randomLog(seed, 220)
            var s = DuelSetup.initial(rec.header)
            rec.entries.forEachIndexed { k, e ->
                (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.let { s = it.state }
                if (k % 7 == 0) {
                    val fresh = s.copy()
                    // Asked more than the walks before the index: every answer from the index too.
                    repeat(3) { s.cards.keys.forEach { u -> assertEquals(fresh.scan(u, fresh.cards.getValue(u)), s.placeOf(u), "seed $seed, entry $k, uid $u") } }
                    assertEquals(null, s.placeOf(-5))
                    // Never part of the table: its equality, its copy, its file.
                    assertEquals(fresh, s)
                    assertEquals(fresh.hashCode(), s.hashCode())
                    assertEquals(Json.encodeToString(DuelState.serializer(), fresh), Json.encodeToString(DuelState.serializer(), s))
                }
            }
        }
    }

    @Test
    fun materialsAndTheExtraMonsterZoneAreFoundByTheIndex() {
        var s = DuelFixtures.bare()
        val zeus = DuelFixtures.uid(0, 40)
        val a = DuelFixtures.uid(0, 5)
        val b = DuelFixtures.uid(0, 6)
        s = DuelFixtures.ok(s, DuelAction.Move(zeus, Place.Zone(0, ZoneKind.EMZ, 1), CardPosition.FACE_UP_ATK))
        s = DuelFixtures.ok(s, DuelAction.Move(a, Place.Under(zeus)))
        s = DuelFixtures.ok(s, DuelAction.Move(b, Place.Under(zeus)))
        repeat(3) {
            assertEquals(Place.Zone(0, ZoneKind.EMZ, 1), s.placeOf(zeus))
            assertEquals(s.scan(a, s.cards.getValue(a)), s.placeOf(a))
            assertEquals(s.scan(b, s.cards.getValue(b)), s.placeOf(b))
            assertTrue(s.placeOf(a) is Place.Under)
        }
    }

    // ---- a pile's hidden cards keep one depth ----------------------------------------------------------

    @Test
    fun aDrawLeavesThePilesHiddenCardsAsTheyWere() {
        val layout = DuelLayouter.solve(1400f, 900f, twoSided = true)
        val g = DuelGame.start(header())
        val before = DuelFrames.of(g.state, layout, setOf(0))
        val drawn = g.act(DuelAction.Draw(0), 0).game
        val after = DuelFrames.of(drawn.state, layout, setOf(0))
        val deck = drawn.state.seats[0].deck
        // The top shown at the pile's depth, the rest hidden at one depth under it.
        val top = after.first { it.uid == deck[0] }
        assertTrue(top.shown)
        assertEquals(DuelFrames.Z_PILE, top.z)
        deck.drop(1).forEach { u ->
            val f = after.first { it.uid == u }
            assertFalse(f.shown)
            assertEquals(DuelFrames.Z_PILE_HIDDEN, f.z)
            // Under the new top, a hidden card's frame is the frame it had: nothing about it changed.
            if (u != g.state.seats[0].deck.getOrNull(0)) assertEquals(before.first { it.uid == u }, f)
        }
    }
}
