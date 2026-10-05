package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.DeclareKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import com.kaiharimoto.mastertool.core.duel.effects.Purpose
import com.kaiharimoto.mastertool.core.duel.text.AnswerChooser
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAnswers
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The red team on the Shortcut line's chooser (D.md §9, lens 5): a name never guessed, a target read by what it is for,
 * `zone=` and `pos=` kept in step with the decisions — those asked and those settled without asking — and a declaration of
 * any card that exists.
 */
class ShortcutRedTeamTest {
    private val catalog = DuelCatalog { code -> FxRef.cards.firstOrNull { CardId(code) in it.passcodes }?.let { DuelCardInfo.of(it) } }
    private val atk = CardPosition.FACE_UP_ATK
    private val def = CardPosition.FACE_UP_DEF

    private fun table() = FxRef.table(Side(field = listOf(Slot(FxRef.LAMP, 0)), gy = listOf(FxRef.SCOUT, FxRef.TINKER), deck = listOf(FxRef.PAWN))).state

    private fun pick(s: DuelState, vararg codes: Int) = codes.map { c -> s.cards.values.first { it.owner == 0 && FxRef.book.canonical(it.code) == c && s.placeOf(it.uid) is Place.Pile && (s.placeOf(it.uid) as Place.Pile).kind == PileKind.GY }.uid }

    /**
     * Agents (b) and (c) reported the chooser taking a word for whichever card it named first at a score of 70 or more:
     * "Example" took Example Scout over Example Tinker. A word that names two cards' names equally well is asked back with
     * both, as `DuelCommand.lookup` does; an exact name settles it.
     */
    @Test
    fun anAmbiguousNameIsListedNeverGuessed() {
        val s = table()
        val among = pick(s, FxRef.SCOUT, FxRef.TINKER)
        val d = Decision.Cards("Special Summon · Example Lamp", among, 1, 1, Purpose.SUMMON)
        val vague = AnswerChooser(ShortcutAnswers(pick = listOf("Example")), s, 0, catalog)
        assertEquals(listOf(-1), vague.choose(d))
        val q = assertNotNull(vague.question)
        assertTrue("Example Scout" in q && "Example Tinker" in q && q.startsWith("“Example” could be"), q)
        assertEquals(listOf(1), AnswerChooser(ShortcutAnswers(pick = listOf("Example Tinker")), s, 0, catalog).choose(d))
        assertEquals(listOf(1), AnswerChooser(ShortcutAnswers(pick = listOf("tinker")), s, 0, catalog).choose(d), "one name it starts a word of")
    }

    /** The coordinator's review: a target is a [Purpose.TARGET] pick, whatever its words — never read off `why`. */
    @Test
    fun aTargetIsReadByWhatItIsFor() {
        val s = table()
        val among = pick(s, FxRef.SCOUT, FxRef.TINKER)
        val chooser = AnswerChooser(ShortcutAnswers(pick = listOf("Example Scout"), target = listOf("Example Tinker")), s, 0, catalog)
        assertEquals(listOf(1), chooser.choose(Decision.Cards("Choose 1 in a GY · Example Lamp", among, 1, 1, Purpose.TARGET)))
        assertEquals(listOf(0), chooser.choose(Decision.Cards("Target practice · Example Lamp", among, 1, 1, Purpose.SUMMON)), "a pick whose words begin with Target is no target")
    }

    /**
     * `pos=` paired with `zone=` when one position is fixed: a Link Monster (Attack only) to e1, then a monster with a
     * choice to m3. A line that gives a position only where there was a choice (`pos=def`), and a combo that writes every
     * one (`pos=atk,def`), both put the second in Defense; before, the first zone swallowed the "def".
     */
    @Test
    fun aFixedPositionDoesNotSwallowTheNextOnesWord() {
        val s = table()
        val e1 = Place.Zone(0, ZoneKind.EMZ, 0)
        val m3 = Place.Zone(0, ZoneKind.MONSTER, 2)
        val m4 = Place.Zone(0, ZoneKind.MONSTER, 3)
        for (pos in listOf(listOf("def"), listOf("atk", "def"))) {
            val c = AnswerChooser(ShortcutAnswers(zone = listOf("e1", "m3"), pos = pos), s, 0, catalog)
            assertEquals(listOf(0), c.choose(Decision.Zone(listOf(e1, m4), 9, listOf(atk))))
            c.told(Decision.Position(9, listOf(atk)), listOf(0)) // fixed: never put
            assertEquals(listOf(0), c.choose(Decision.Zone(listOf(m3, m4), 10, listOf(atk, def))))
            assertEquals(listOf(1), c.choose(Decision.Position(10, listOf(atk, def))), "pos=$pos")
        }
    }

    /**
     * A combo writes every zone it took, those the engine settled without asking too. A zone never put used to stay in the
     * queue with its position, and the next monster took that position: now the engine tells the chooser ([Chooser.told])
     * and the line stays in step.
     */
    @Test
    fun aZoneAndAPickSettledWithoutAskingSpendTheirWords() {
        val s = table()
        val m2 = Place.Zone(0, ZoneKind.MONSTER, 1)
        val m3 = Place.Zone(0, ZoneKind.MONSTER, 2)
        val m4 = Place.Zone(0, ZoneKind.MONSTER, 3)
        val c = AnswerChooser(ShortcutAnswers(zone = listOf("m2", "m3"), pos = listOf("def", "atk")), s, 0, catalog)
        c.told(Decision.Zone(listOf(m2), 9, listOf(atk, def)), listOf(0)) // the only zone left: never put
        assertEquals(listOf(1), c.choose(Decision.Position(9, listOf(atk, def))), "its own word: def")
        assertEquals(listOf(0), c.choose(Decision.Zone(listOf(m3, m4), 10, listOf(atk, def))))
        assertEquals(listOf(0), c.choose(Decision.Position(10, listOf(atk, def))), "and the next its own: atk")
        // A pick settled without asking spends the word that named it, so the next pick reads the next word.
        val (scout, tinker) = pick(s, FxRef.SCOUT, FxRef.TINKER)
        val p = AnswerChooser(ShortcutAnswers(pick = listOf("Example Scout", "Example Tinker")), s, 0, catalog)
        p.told(Decision.Cards("Send", listOf(scout), 1, 1), listOf(0))
        assertEquals(listOf(1), p.choose(Decision.Cards("Summon", listOf(scout, tinker), 1, 1)), "Tinker, not the Scout already spent")
    }

    /**
     * The coordinator's review: `declare=` takes any card of the pool by its exact name (Yugipedia, "Declare": any existing
     * card), lists the choices when a name is ambiguous, and never guesses.
     */
    @Test
    fun aDeclarationNamesAnyCardOfThePool() {
        val s = table()
        val pool = mapOf("example colossus" to listOf(FxRef.COLOSSUS), "twin" to listOf(FxRef.COLOSSUS, FxRef.KNIGHT))
        val names = { w: String -> pool[w.lowercase()].orEmpty() }
        val d = Decision.Declare(DeclareKind.NAME, listOf("Example Lamp", "Example Pawn"), open = true)
        val c = AnswerChooser(ShortcutAnswers(declare = listOf("Example Colossus")), s, 0, catalog, names = names)
        assertEquals(FxRef.COLOSSUS, c.name(d))
        val onTable = AnswerChooser(ShortcutAnswers(declare = listOf("example lamp")), s, 0, catalog, names = names)
        assertNull(onTable.name(d), "a name on the table is answered from the list")
        assertEquals(listOf(0), onTable.choose(d))
        val twin = AnswerChooser(ShortcutAnswers(declare = listOf("twin")), s, 0, catalog, names = names)
        assertNull(twin.name(d))
        assertEquals(listOf(-1), twin.choose(d))
        assertTrue(twin.question!!.startsWith("“twin” is the name of more than one card"), twin.question)
        // Not a near name off the table: "Example Col" is no card's exact name, and the list holds none like it.
        val near = AnswerChooser(ShortcutAnswers(declare = listOf("Example Col")), s, 0, catalog, names = names)
        assertNull(near.name(d))
        assertEquals(listOf(-1), near.choose(d))
    }
}
