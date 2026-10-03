package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.bare
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.play
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.CommandHelp
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import com.kaiharimoto.mastertool.core.duel.text.Spotlight
import com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode
import com.kaiharimoto.mastertool.core.duel.text.Spotlight.RowKind
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import com.kaiharimoto.mastertool.core.layout.DuelFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Spotlight (1.0.87, kai's direction C): what the box shows for a line — whole sentences with their consequence
 * and coordinates — the history ↑ walks, and when a digit picks a "did you mean".
 */
class SpotlightTest {

    private fun view(text: String, s: DuelState, history: List<String> = emptyList(), seat: Int = 0) =
        Spotlight.view(text, text.length, s, seat, catalog, history)

    /** Kai's Main Phase 1 with five cards in each hand. */
    private fun main(): DuelState = play(bare(), DuelAction.Draw(0, 5), DuelAction.Draw(1, 5), DuelAction.Phase(DuelPhase.MAIN1))

    @Test
    fun anAttackIsASentenceWithItsBattleAndItsCoordinates() {
        val v = view("a m1 om1", battle())
        val row = v.rows.first()
        assertEquals(RowKind.MOVE, row.kind)
        assertEquals(Spotlight.DO, v.label)
        assertEquals("Blue-Eyes White Dragon attacks Dark Magician", row.words)
        assertEquals("3000 vs 2500 · Dark Magician is destroyed, they take 500", row.consequence)
        assertEquals("m1 → om1", row.coords)
        assertEquals(uid(0, 5), row.uid)
        assertTrue(row.makes)
        // kai's own example: a monster too weak, and the price of it.
        val ash = play(battle(), DuelAction.Move(uid(0, 4), Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "special"))
        assertEquals("0 vs 2500 · Ash Blossom & Joyous Spring is destroyed, you take 2500", view("a m3 om1", ash).rows.first().consequence)
        // From the Extra Monster Zone, and directly.
        assertEquals("e1 → om1", view("a e1 om1", battle()).rows.first().coords)
        val direct = view("a m1 direct", battle()).rows.first()
        assertEquals("m1 → direct", direct.coords)
        assertEquals("3000 direct · they take 3000", direct.consequence)
    }

    @Test
    fun aFaceDownDefenderIsNeverNamed() {
        // Set from Rival's hand: no one at the table knows it.
        val set = play(battle(), DuelAction.Move(uid(1, 0), Place.Zone(1, ZoneKind.MONSTER, 1), CardPosition.FACE_DOWN_DEF, "set"))
        val row = view("a m1 om2", set).rows.first()
        assertEquals("3000 vs a face-down monster", row.consequence)
        assertEquals("Blue-Eyes White Dragon attacks the face-down card in om2", row.words)
    }

    @Test
    fun aMoveAndWhereItGoes() {
        val row = view("s h1 m3", main()).rows.first()
        assertEquals("Summon Blue-Eyes White Dragon from h1 to M3", row.words)
        assertEquals("h1 → m3", row.coords)
        assertNull(row.consequence)
        assertEquals("s h1 m3", row.line)
    }

    @Test
    fun severalMovesAreNumberedSteps() {
        val v = view("s h1 m3; e h3 s2", main())
        val steps = v.rows.filter { it.kind == RowKind.STEP }
        assertEquals(listOf(1, 2), steps.map { it.number })
        assertEquals("h1 → m3", steps[0].coords)
        // The second step is read against the table the first leaves: h3 is the card that is third then.
        assertEquals("h3 → s2", steps[1].coords)
        assertTrue(steps.all { it.line == "s h1 m3; e h3 s2" })
    }

    @Test
    fun didYouMeanIsNumberedAndTheDigitsPickIt() {
        val t = play(bare(), DuelAction.Move(uid(0, 4), Place.Pile(0, PileKind.HAND)), DuelAction.Phase(DuelPhase.MAIN1))
        val v = view("summon blossum joy", t)
        assertNotNull(v.problem)
        val fix = v.fixes.first()
        assertEquals(1, fix.number)
        assertEquals("summon ash blossom & joyous spring", fix.line)
        // A digit picks while the choices show and the word typed is no coordinate.
        assertTrue(Spotlight.digitPicks("summon blossum joy", 18, 1, v.fixes.size))
        assertFalse(Spotlight.digitPicks("summon blossum joy", 18, 3, 1), "only as many as are shown")
        // `m` then 3 is m3; `lp -` then 1 is a number; nothing picks without choices.
        assertFalse(Spotlight.digitPicks("s h1 m", 6, 1, 2))
        assertFalse(Spotlight.digitPicks("s oh", 4, 1, 2))
        assertFalse(Spotlight.digitPicks("lp -", 4, 1, 2))
        assertFalse(Spotlight.digitPicks("summon blossum", 14, 1, 0))
    }

    @Test
    fun completionsAreWholeSentencesWhenTheyWouldBeMoves() {
        val v = view("s h1 ", main())
        val whole = v.rows.filter { it.kind == RowKind.COMPLETE && it.makes }
        assertTrue(whole.any { it.words == "Summon Blue-Eyes White Dragon from h1 to M1" && it.coords == "h1 → m1" }, v.rows.toString())
        assertTrue(v.rows.size <= Spotlight.MOST)
    }

    @Test
    fun aQuestionIsAnsweredNotMade() {
        val v = view("their field", battle())
        assertEquals(Spotlight.ASK, v.label)
        assertTrue(v.rows.isEmpty())
        val answer = assertNotNull(v.answer)
        assertTrue(answer.startsWith("Their field:"), answer)
        // Never naming a hidden card: their set Mirror Force is "a face-down card".
        assertFalse(answer.contains("Mirror Force"), answer)
        assertEquals(Spotlight.ASK, view("?m1", battle()).label)
        assertEquals("Their hand: 4 cards.", view("their hand", battle()).answer)
    }

    @Test
    fun anEmptyBoxShowsRecentLinesAndLinesToTry() {
        val v = view("", main(), history = listOf("draw", "m1", "draw", "s h1 m3"))
        assertEquals(listOf("s h1 m3", "draw", "m1"), v.recent)
        assertTrue(v.rows.isEmpty())
        assertTrue(v.tries.size in 2..3, v.tries.toString())
        assertTrue(v.tries.all { DuelCommand.preview(it.line, main(), 0, catalog).ok }, v.tries.toString())
        assertTrue(v.tries.any { it.line.startsWith("s h") }, v.tries.toString())
        // In the Battle Phase, an attack to try.
        assertTrue(view("", battle()).tries.any { it.line.startsWith("a m") || it.line.startsWith("a e") }, view("", battle()).tries.toString())
    }

    @Test
    fun upWalksTheHistoryOnAnEmptyBoxAndTheRowsOtherwise() {
        val history = listOf("draw", "bp", "s h2 m3")
        var st = Spotlight.State()
        assertEquals(-1, st.chosen)
        st = Spotlight.up(st, 0, history)
        assertEquals("s h2 m3", st.text)
        assertEquals(0, st.historyAt)
        st = Spotlight.up(st, 1, history)
        assertEquals("bp", st.text)
        st = Spotlight.up(Spotlight.up(st, 1, history), 1, history)
        assertEquals("draw", st.text, "the oldest stays")
        st = Spotlight.down(st, 1, history)
        assertEquals("bp", st.text)
        st = Spotlight.down(Spotlight.down(st, 1, history), 1, history)
        assertEquals("", st.text)
        assertNull(st.historyAt)
        // A line being typed: ↑/↓ choose rows, and typing puts the choice back on the first.
        var typed = Spotlight.State().typed("s h1", 4)
        typed = Spotlight.down(Spotlight.down(typed, 3, history), 3, history)
        assertEquals(2, typed.chosen)
        assertEquals(2, Spotlight.down(typed, 3, history).chosen)
        assertEquals(1, Spotlight.up(typed, 3, history).chosen)
        assertEquals(0, typed.typed("s h1 ", 5).chosen)
        // An empty box's first ↓ inks its first line to try.
        assertEquals(0, Spotlight.down(Spotlight.State(), 3, history).chosen)
        assertEquals(Mode.LISTENING, Spotlight.State(mode = Mode.LISTENING).typed("x", 1).mode)
    }

    @Test
    fun theHistoryKeepsFiftyEachOnce() {
        var h = emptyList<String>()
        repeat(60) { h = Spotlight.remember(h, "line $it") }
        assertEquals(Spotlight.HISTORY, h.size)
        assertEquals("line 59", h.last())
        h = Spotlight.remember(h, "line 30")
        assertEquals("line 30", h.last())
        assertEquals(1, h.count { it == "line 30" })
        assertEquals(h, Spotlight.remember(h, "   "))
    }

    /** The typed take of the demo (NEUE.md §4p): the lines as typed, from a table dealt, each previewed then made. */
    @Test
    fun theDemosTypedTake() {
        var g = DuelGame.of(DuelRecord(CommandFixtures.header()))
        g = g.act(listOf(DuelAction.Draw(0, 5)), 0).game
        g = g.act(listOf(DuelAction.Draw(1, 5)), 1).game
        g = g.act(listOf(DuelAction.Phase(DuelPhase.MAIN1)), 0).game
        // Their monster to attack, face-up in their M1.
        g = g.act(listOf(DuelAction.Move(g.state.seats[1].hand[0], Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal")), 1).game
        fun make(line: String) {
            val v = Spotlight.view(line, line.length, g.state, 0, catalog)
            assertTrue(v.rows.first().makes, "“$line”: ${v.problem}")
            val p = DuelCommand.preview(line, g.state, 0, catalog)
            for (actions in p.parts.ifEmpty { listOf(p.actions) }) {
                val r = g.act(actions, 0)
                assertTrue(r.ok, "“$line”: ${r.problem}")
                g = r.game
            }
        }
        make("s h2 m3")
        make("bp")
        assertEquals("3000 vs 300 · Kuriboh is destroyed, they take 2700", Spotlight.view("a m3 om1", 8, g.state, 0, catalog).rows.first().consequence)
        make("a m3 om1")
        // The empty box offers the battle as typed words, first: the battle chip without the mouse.
        val battle = assertNotNull(DuelBattle.pending(g, catalog))
        val first = Spotlight.view("", 0, g.state, 0, catalog, battle = battle).tries.first()
        assertEquals("g om1; lp o -2700", first.line)
        assertEquals("Apply 2700 to Rival · Destroy Kuriboh", first.words)
        make(first.line)
        assertEquals(8000 - 2700, g.state.seats[1].lp)
        make("end")
        assertEquals(1, g.state.active)
    }

    /** The spoken take of the demo: each phrase as the transcriber writes it, understood, shown, confirmed with "yes". */
    @Test
    fun theDemosSpokenTake() {
        var g = DuelGame.of(DuelRecord(CommandFixtures.header()))
        g = g.act(listOf(DuelAction.Draw(0, 5)), 0).game
        g = g.act(listOf(DuelAction.Draw(1, 5)), 1).game
        g = g.act(listOf(DuelAction.Phase(DuelPhase.MAIN1)), 0).game
        g = g.act(listOf(DuelAction.Move(g.state.seats[1].hand[0], Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal")), 1).game
        var pending: String? = null
        for (said in listOf("Summon h2 to m3.", "Yes.", "Go to battle.", "Yes", "My monster three attacks their monster one.", "Yes.", "End turn.", "Yes.")) {
            when (val sp = DuelSpeech.classify(DuelSpeech.normalize(said), g.state, 0, catalog)) {
                is DuelSpeech.Spoken.Command -> {
                    assertTrue(DuelCommand.preview(sp.line, g.state, 0, catalog).ok, "“$said” → ${sp.line}")
                    pending = sp.line
                }
                DuelSpeech.Spoken.Confirm -> {
                    val line = assertNotNull(pending, "“$said” confirms nothing")
                    val p = DuelCommand.preview(line, g.state, 0, catalog)
                    for (actions in p.parts.ifEmpty { listOf(p.actions) }) g = g.act(actions, 0).also { assertTrue(it.ok, line) }.game
                    pending = null
                }
                else -> error("“$said” was $sp")
            }
        }
        assertEquals(1, g.state.active)
    }

    @Test
    fun theSpokenExamplesBecomeTheirLines() {
        CommandHelp.SPOKEN.forEach { (said, line) -> assertEquals(line, DuelSpeech.normalize(said), "“$said”") }
    }

    @Test
    fun theHelpPromisesOnlyWhatTheLineReads() {
        CommandHelp.notation.flatMap { it.left.split(' ') }.filter { it.any(Char::isDigit) }.forEach { c ->
            assertNotNull(DuelNotation.parse(c), c)
        }
        assertTrue(CommandHelp.letters.any { it.left == "s" && it.right == DuelVerb.SUMMON.label })
        assertTrue(CommandHelp.examples.first().left == "s h2 m3")
        assertTrue(CommandHelp.examples.all { it.right.isNotEmpty() }, CommandHelp.examples.filter { it.right.isEmpty() }.toString())
        assertTrue(CommandHelp.opening.any { it.action == com.kaiharimoto.mastertool.core.input.DeskAction.DUEL_COMMAND && it.chord.ctrl && it.chord.key == "l" })
    }

    /** One order for their hidden hand: what the table draws and walks, what the Line counts, what DuelView sends. */
    @Test
    fun theirHandIsOneOrderEverywhere() {
        val s = play(bare(), DuelAction.Draw(0, 5), DuelAction.Draw(1, 5))
        val secret = 77L
        val notation = DuelNotation.handOrder(s, 1, 0, secret)
        // A hidden hand (only the bottom seat's eyes), and a hot-seat with both hands face-up: the same order.
        for (eyes in listOf(DuelFocus.Eyes(setOf(0), secret, 0), DuelFocus.Eyes(setOf(0, 1), secret, 0))) {
            assertEquals(notation, (0 until 5).map { DuelFocus.uidAt(s, DuelFocus.Slot.HandCard(1, it), eyes) })
        }
        assertEquals(notation, DuelFocus.Eyes(setOf(0), secret).hand(s, 1), "the hidden hand already matched")
        assertEquals(
            DuelView.of(s, 0, secret).seats[1].hand.map { it.ref },
            notation.map { DuelView.veil(secret, it, s.epoch[it] ?: 0) },
        )
        // Drawn so too (the red team: both hands face-up drew their hand in its true order, while `oh3` read the veil's).
        val l = com.kaiharimoto.mastertool.core.layout.DuelLayouter.solve(1920f, 1032f, true)
        for (viewers in listOf(setOf(0), setOf(0, 1))) {
            val frames = com.kaiharimoto.mastertool.core.layout.DuelFrames.of(s, l, viewers, secret = secret, viewer = 0)
            val drawn = s.seats[1].hand.sortedBy { u -> frames.first { it.uid == u }.x }
            assertEquals(notation, drawn, "viewers $viewers")
        }
        // And each slot's label is the coordinate that finds its card.
        (0 until 5).forEach { i ->
            val label = DuelFocus.label(DuelFocus.Slot.HandCard(1, i), 0)
            assertEquals("oh${i + 1}", label)
            assertEquals(notation[i], DuelNotation.at(s, label, 0, secret))
        }
    }
}
