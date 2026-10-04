package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.ai.DuelGuide
import com.kaiharimoto.mastertool.core.duel.ai.DuelMoves
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase C stage 2 (`docs/phases/C.md` §4): the table in full for Ai — the brief with every public card's printed facts,
 * priority and the turn's moves; the menu of legal moves; the guide at the table — and the last of stage 1's duel leads,
 * each verified first and then held shut.
 */
class DuelTableTest {
    private val ash = 101
    private val droll = 102
    private val pot = 103
    private val called = 104
    private val pendy = 107
    private val magi = 110
    private val nibiru = 111
    private val zeus = 105
    private val link = 108
    private val filler = 199

    private val catalog = DuelCatalog { code ->
        when (code) {
            ash -> DuelCardInfo("Ash Blossom & Joyous Spring", CardKind.MONSTER, handCost = PileKind.GY, atk = 0, def = 1800, level = 3, attribute = "FIRE", race = "Zombie", typeLine = "Tuner Effect Monster")
            droll -> DuelCardInfo("Droll & Lock Bird", CardKind.MONSTER, handCost = PileKind.GY, atk = 0, def = 0, level = 1, attribute = "WIND", race = "Winged Beast", typeLine = "Effect Monster")
            pot -> DuelCardInfo("Pot of Prosperity", CardKind.SPELL, sub = "Normal")
            called -> DuelCardInfo("Called by the Grave", CardKind.SPELL, sub = "Quick-Play")
            pendy -> DuelCardInfo("Pendulum Pal", CardKind.MONSTER, pendulum = true, atk = 1200, def = 1000, level = 4, attribute = "EARTH", race = "Beast", typeLine = "Pendulum Effect Monster", scale = 5)
            magi -> DuelCardInfo("Dark Magician", CardKind.MONSTER, atk = 2500, def = 2100, level = 7, attribute = "DARK", race = "Spellcaster", typeLine = "Normal Monster")
            nibiru -> DuelCardInfo("Nibiru, the Primal Being", CardKind.MONSTER, atk = 3000, def = 600, level = 11, attribute = "LIGHT", race = "Rock", typeLine = "Effect Monster")
            zeus -> DuelCardInfo("Divine Arsenal AA-ZEUS - Sky Thunder", CardKind.EXTRA_MONSTER, atk = 3000, def = 3000, level = 12, xyz = true, attribute = "DARK", race = "Machine", typeLine = "XYZ Effect Monster")
            link -> DuelCardInfo("Link Lad", CardKind.EXTRA_MONSTER, link = true, atk = 1500, linkRating = 2, attribute = "LIGHT", race = "Cyberse", typeLine = "Link Effect Monster")
            filler -> DuelCardInfo("Filler", CardKind.TRAP, sub = "Normal")
            else -> null
        }
    }

    private val seed = 42L

    /** Kai (seat 0) against Ai (seat 1): five cards each drawn in the written order, nothing shuffled. */
    private val header = DuelHeader(
        id = "t2",
        seed = seed,
        seats = listOf(
            SeatSetup("Kai", main = listOf(ash, droll, pot, called, pendy) + List(35) { filler }, extra = listOf(zeus, link)),
            SeatSetup("Ai", main = listOf(magi, ash, pot, called, nibiru) + List(35) { filler }, extra = listOf(zeus, link)),
        ),
    )

    private fun uid(seat: Int, n: Int) = 1 + seat * DuelState.SEAT_UIDS + n

    private fun ok(s: DuelState, a: DuelAction, by: Int = 0): DuelState = when (val o = DuelRules.apply(s, a, by)) {
        is Outcome.Ok -> o.state
        is Outcome.Refused -> error("Refused: ${o.reason} for $a")
    }

    /**
     * Kai: Ash, Pendulum Pal in hand; Pot Set in S1, Droll face-up in M3, Called in the GY. Ai: Dark Magician in M1, Ash,
     * Pot, Called and Nibiru in hand, its Extra Deck face-down.
     */
    private fun table(): DuelState {
        var s = DuelSetup.initial(header)
        s = ok(s, DuelAction.Draw(0, 5))
        s = ok(s, DuelAction.Draw(1, 5), 1)
        s = ok(s, DuelAction.Move(uid(0, 2), Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"))
        s = ok(s, DuelAction.Move(uid(0, 1), Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal"))
        s = ok(s, DuelAction.Move(uid(0, 3), Place.Pile(0, PileKind.GY), how = "send"))
        s = ok(s, DuelAction.Move(uid(1, 0), Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal"), 1)
        return s
    }

    /** The same table on Ai's turn, in [phase]. */
    private fun aisTurn(phase: DuelPhase = DuelPhase.MAIN1) = table().copy(turn = 2, active = 1, phase = phase)

    @Test
    fun theBriefIsTheTableInFull() {
        val s = aisTurn()
        val brief = DuelBrief.describe(s, 1, catalog, seed, 1)
        // Every public card with what is printed on it, the field's by its coordinate from Ai's side.
        assertTrue("om3 #${uid(0, 1)} Droll & Lock Bird (Level 1 · WIND · Winged Beast · Effect · ATK 0 / DEF 0)" in brief, brief)
        assertTrue("m1 #${uid(1, 0)} Dark Magician (Level 7 · DARK · Spellcaster · Normal · ATK 2500 / DEF 2100)" in brief, brief)
        assertTrue("ogy1 #${uid(0, 3)} Called by the Grave (Quick-Play Spell)" in brief, brief)
        // Its own Extra Deck, every card: a Rank and a Link rating, no DEF on a Link.
        assertTrue("Rank 12 · DARK · Machine · Xyz Effect · ATK 3000 / DEF 3000" in brief, brief)
        assertTrue("Link-2 · LIGHT · Cyberse · Link Effect · ATK 1500)" in brief, brief)
        // Theirs face-down: counted, never listed.
        assertTrue("Extra Deck (2): unseen" in brief, brief)
        // Whose move it is.
        assertTrue("Priority: Seat 1 (Ai), the turn player" in brief, brief)
        val chained = ok(s, DuelAction.ChainAdd(1, uid(1, 0)), 1)
        assertTrue("Priority: Seat 0 (Kai) may chain to link 1 or pass" in DuelBrief.describe(chained, 1, catalog, seed, 1))
        // A token's own numbers, and the printed one beside a number the table changed.
        val view = ViewCard(uid(1, 0), magi, CardPosition.FACE_UP_ATK, 1, 1, atk = 3000)
        assertEquals("Level 7 · DARK · Spellcaster · Normal · ATK 3000 (printed 2500) / DEF 2100", DuelBrief.facts(view, catalog))
        // DuelCardInfo reads them off the card as printed.
        val card = Card(CardId(1), "Link Lad", "Link Effect Monster", "effect_link", race = "Cyberse", attribute = Attribute.LIGHT, atk = 1500, linkValue = 2)
        val info = DuelCardInfo.of(card)
        assertEquals(2, info.linkRating)
        assertNull(info.level)
        assertEquals("LIGHT", info.attribute)
        assertEquals("Cyberse", info.race)
    }

    @Test
    fun theBriefNeverNamesAHiddenCardThroughEitherSeat() {
        val s = aisTurn()
        // Ai's seat (1) and the person's (0) — the guest's, at a networked table — each read the other's hand and set cards hidden.
        val asAi = DuelBrief.describe(s, 1, catalog, seed, 1)
        assertFalse("Pendulum Pal" in asAi, asAi)
        assertFalse("Scale 5" in asAi, "a hidden card's facts would name it")
        assertTrue("os1 a face-down card [?" in asAi, asAi)
        val asKai = DuelBrief.describe(s, 0, catalog, seed, 0)
        assertFalse("Nibiru" in asKai, asKai)
        assertFalse("Rank 12" in asKai.substringAfter("Seat 1 (Ai) · 8000 LP"), "their face-down Extra Deck")
        assertTrue("Pendulum Pal" in asKai)
        // The turn's moves, as each saw them.
        var g = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        fun act(a: DuelAction, seat: Int) { g = g.act(a, seat).also { assertTrue(it.ok, it.problem) }.game }
        act(DuelAction.Draw(0, 5), 0)
        act(DuelAction.Move(uid(0, 4), Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_DOWN_DEF, "set"), 0)
        val theirs = DuelBrief.turnLines(g, 1, catalog)
        assertTrue(theirs.isNotEmpty() && theirs.none { "Pendulum Pal" in it }, theirs.toString())
        assertTrue(DuelBrief.turnLines(g, 0, catalog).any { "Pendulum Pal" in it })
        // A turn's lines start after its End Turn.
        act(DuelAction.EndTurn, 0)
        act(DuelAction.Lp(1, -100), 1)
        assertEquals(1, DuelBrief.turnLines(g, 1, catalog).size)
    }

    /** Every coordinate printed beside a card in [brief], with the card's #uid or veil. */
    private fun coordinates(brief: String): List<Triple<String, Int?, Int?>> =
        Regex("(?<![\\w#?])(o?(?:h|m|s|gy|ban|ex)\\d+|o?fz) (?:#(\\d+)|a face-down card \\[\\?(\\d+)\\])").findAll(brief).map { m ->
            Triple(m.groupValues[1], m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull())
        }.toList()

    @Test
    fun everyCoordinateTheBriefPrintsParsesBackToItsCard() {
        val s = aisTurn()
        // The lead held: the brief and DuelView deal the other seat's hand by its veils under the duel's seed, the
        // command line under 0 (ComboRunner.plan passed no secret) — so "oh2" was another card than the one shown there.
        val secret = (1L..500L).first { DuelNotation.handOrder(s, 0, 1, it) != DuelNotation.handOrder(s, 0, 1, 0L) }
        val before = ComboRunner.plan(s, 1, listOf("g oh1"), catalog).steps.single().second.single() as DuelAction.Move
        assertNotEquals(DuelNotation.at(s, "oh1", 1, secret), before.uid, "the lead, as it was: the old plan read oh1 under another order")
        // One convention now: every coordinate the brief prints is the card beside it, for Ai's own eyes and for full knowledge.
        for (viewer in listOf(1, null)) {
            val brief = DuelBrief.describe(s, viewer, catalog, secret, 1)
            val found = coordinates(brief)
            assertTrue(found.count { it.first.startsWith("oh") } == 2 && found.count { it.first.startsWith("h") } == 4, "$viewer: $found\n$brief")
            for ((coord, ref, veil) in found) {
                val at = DuelNotation.at(s, coord, 1, secret)
                if (ref != null) assertEquals(ref, at, "$viewer: $coord")
                else assertEquals(-veil!!, DuelView.veil(secret, at!!, s.epoch[at] ?: 0), "$viewer: $coord")
                // And a line naming it plans on that very card.
                if (coord.startsWith("oh") || coord.startsWith("os")) {
                    val step = ComboRunner.plan(s, 1, listOf("g $coord"), catalog, secret).steps.single().second.single() as DuelAction.Move
                    assertEquals(at, step.uid, "$viewer: g $coord")
                }
            }
        }
    }

    @Test
    fun theMenuOffersOnlyLinesThatPlayAndTouchNothingHidden() {
        val states = listOf(aisTurn(), aisTurn(DuelPhase.BATTLE), ok(aisTurn(), DuelAction.ChainAdd(0, uid(0, 1))), table())
        for (s in states) {
            val menu = DuelMoves.menu(s, 1, catalog, seed)
            val moves = menu.flatMap { it.moves }
            assertTrue(moves.isNotEmpty())
            assertEquals(moves.size, moves.map { it.line }.toSet().size, "each line once")
            for (m in moves) {
                val run = ComboRunner.plan(s, 1, listOf(m.line), catalog, seed)
                assertTrue(run.ok, "${m.line}: ${run.problem}")
                assertNull(ComboRunner.reach(s, 1, run), m.line)
                assertFalse(m.line.contains(Regex("\\boh\\d")), "never a card in their hand: ${m.line}")
            }
            val words = DuelMoves.words(menu, 600)
            assertFalse("Pendulum Pal" in words, words)
        }
        val main = DuelMoves.menu(aisTurn(), 1, catalog, seed).flatMap { it.moves }.map { it.line }
        assertTrue(listOf("bp", "end", "s h1", "g om3", "t os1", "ss ex1").all { it in main }, main.toString())
        val battle = DuelMoves.menu(aisTurn(DuelPhase.BATTLE), 1, catalog, seed).flatMap { it.moves }.map { it.line }
        assertTrue("a m1 om3" in battle && "a m1 direct" in battle, battle.toString())
        val chain = DuelMoves.menu(ok(aisTurn(), DuelAction.ChainAdd(0, uid(0, 1))), 1, catalog, seed).flatMap { it.moves }.map { it.line }
        assertTrue("resolve" in chain && "negate 1" in chain, chain.toString())
        // Not Ai's turn: no phase of theirs to move, only an ask.
        val off = DuelMoves.menu(table(), 1, catalog, seed).flatMap { it.moves }.map { it.line }
        assertFalse("bp" in off)
        // One card asked for: every free zone spelled out.
        val one = DuelMoves.menu(aisTurn(), 1, catalog, seed, only = uid(1, 1)).single().moves.map { it.line }
        assertTrue("s h1 m2" in one && "s h1 m5" in one && "e h1 m4" in one, one.toString())
        // Capped, the menu says how many it left out.
        assertTrue("more not shown" in DuelMoves.words(DuelMoves.menu(aisTurn(), 1, catalog, seed), 12))
    }

    @Test
    fun theGuideAtTheTableIsLeanAndSaysWhatItLeftOut() {
        val guide = (1..30).joinToString("\n") { "- Entry $it: " + "a long point about the deck ".repeat(40) }
        val combos = (1..12).map { Combo("c$it", "Line $it", steps = List(20) { k -> "step $k of a long combo" }, needs = listOf("Dark Magician")) }
        val block = DuelGuide.block("Dark Magician", guide, combos)
        assertTrue(block.length < DuelGuide.GUIDE_BUDGET + DuelGuide.COMBO_BUDGET + 400, "${block.length}")
        assertTrue("Your guide to how “Dark Magician” plays" in block)
        assertTrue(Regex("and \\d+ more entries of the guide").containsMatchIn(block), block)
        assertTrue("Line 1 (id c1; needs Dark Magician)" in block)
        assertTrue("duel_combo list names them all" in block)
        assertEquals("", DuelGuide.block("Empty", "", emptyList()))
        assertTrue("Their deck's guide" in DuelGuide.block("Theirs", "- one", emptyList(), theirs = true))
        // A guide's entry over more than one line stays one entry.
        assertEquals(listOf("first part and its second line", "next"), DuelGuide.entries("- first part\nand its second line\n- next"))
    }

    // ---- the leads (stage 1's report) -----------------------------------------------------------------

    @Test
    fun aisWordsAfterASemicolonAndInAComboAreKeptFromTheRecord() {
        val s = aisTurn()
        // The lead held: duel_act guarded an op only when it began with "say"; after a ";" — and in a combo's steps — Ai's
        // words reached the log as typed.
        val run = ComboRunner.plan(s, 1, listOf("draw; say I hold Nibiru, the Primal Being", "note Nibiru waits for five"), catalog, seed)
        assertTrue(run.ok, run.problem)
        val raw = run.steps.flatMap { it.second }
        assertTrue(raw.any { it is DuelAction.Chat && "Nibiru" in it.text }, "the lead, as it was")
        val kept = ComboRunner.redacted(s, 1, run, catalog)
        val said = kept.steps.flatMap { it.second }
        assertFalse(said.any { (it is DuelAction.Chat && "Nibiru" in it.text) || (it is DuelAction.Note && "Nibiru" in it.text) }, said.toString())
        assertTrue(kept.steps.none { "Nibiru" in it.first }, "the step's own words, as reported")
        // The moves are the same moves.
        assertEquals(raw.filter { !it.social }, said.filter { !it.social })
        // A name Kai can see anyway (Dark Magician on the field) stays.
        val open = ComboRunner.redacted(s, 1, ComboRunner.plan(s, 1, listOf("say Dark Magician attacks"), catalog, seed), catalog)
        assertTrue((open.steps.single().second.single() as DuelAction.Chat).text.contains("Dark Magician"))
    }

    @Test
    fun aPersonsMoveOnAisCardsReachesItsNextCue() {
        var g = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        fun act(a: DuelAction, seat: Int, by: Provenance?) { g = g.act(a, seat, by = by).also { assertTrue(it.ok, it.problem) }.game }
        act(DuelAction.Draw(0, 5), 0, null)
        act(DuelAction.Draw(1, 5), 1, null)
        act(DuelAction.Move(uid(1, 0), Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal"), 1, null)
        val from = g.cursor
        val person = Provenance(Provenance.PERSON, aiSeat = 1, aiKnows = "self")
        // Kai destroys Ai's Dark Magician: the page acts as the card's controller, so the entry is Ai's seat's.
        act(DuelAction.Move(uid(1, 0), Place.Pile(1, PileKind.GY), how = "send"), 1, person)
        act(DuelAction.Move(uid(1, 1), Place.Pile(1, PileKind.GY), how = "send"), 1, Provenance(Provenance.AI, aiSeat = 1, aiKnows = "self"))
        act(DuelAction.Draw(1, 1), 1, Provenance(Provenance.TABLE, aiSeat = 1))
        act(DuelAction.Lp(1, -100), 1, null)
        act(DuelAction.Lp(0, -100), 0, person)
        // The lead held: the cue kept only lines of the other seat, and dropped Kai's move on Ai's card.
        val old = DuelHost.lines(g, from, 1, catalog).filter { it.seat != 1 }
        assertEquals(1, old.size)
        val news = DuelBrief.since(g, from, 1, 1, catalog)
        assertEquals(listOf(from, from + 4), news.map { it.i }, news.toString())
        assertTrue("Dark Magician" in news.first().text, news.first().text)
    }
}
