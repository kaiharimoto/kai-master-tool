package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.ASH
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.CHAOS
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.actions
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.ok
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.parse
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.play
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.layout.CardLook
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** kai's notes from the table (1.0.87): hand activations, the Deck's three ends, chance, and a monster put on a monster. */
class TableNotesTest {
    private val s = battle()
    private val bewd = uid(0, 5)
    private val snake = uid(0, 7)
    private val pot = uid(0, 2)
    private val ash = uid(0, 4)
    private val fader = uid(0, 9)
    private val chaos = uid(0, 41)
    private val m1 = Place.Zone(0, ZoneKind.MONSTER, 0)

    private fun words(before: DuelState, a: DuelAction, viewer: Int?): String {
        val after = DuelRules.applyAll(before, listOf(a), 0).first!!
        return DuelWords.say(before, after, DuelEntry(0, 0L, 0, 0, a), viewer, catalog)
    }

    // ---- "activating a card effect of a monster in the hand doesn't necessarily discard to the graveyard" ----

    @Test
    fun aMonsterActivatedFromTheHandIsShownThereWhileTheChainStands() {
        val acts = actions("activate h5", s)
        assertEquals(listOf(DuelAction.ChainAdd(0, fader)), acts)
        val chained = play(s, *acts.toTypedArray())
        assertTrue(fader in chained.seats[0].hand)
        assertTrue(DuelSight.sees(chained, fader, 1), "the other seat sees it while the chain stands")
        // A second link on top: the first stays shown, resolved or not, until the chain is over.
        val two = play(chained, DuelAction.ChainAdd(1, null), DuelAction.ChainResolve)
        assertTrue(DuelSight.sees(two, fader, 1))
        val over = play(two, DuelAction.ChainResolve)
        assertFalse(DuelSight.sees(over, fader, 1), "back in the hand, its owner's alone")
        // And it cannot be followed back in: its veil is new.
        assertEquals((s.epoch[fader] ?: 0) + 1, over.epoch[fader])
        assertFalse(DuelSight.sees(play(chained, DuelAction.ChainClear), fader, 1))
    }

    @Test
    fun onlyACardWhoseTextPaysWithItGoesToTheGy() {
        val ashText = "When a card or effect is activated that includes any of these effects (Quick Effect): You can discard this card; negate that activation."
        val maxxText = "During either player's turn: You can send this card from your hand to the Graveyard; this turn, each time your opponent Special Summons a monster(s), immediately draw 1 card."
        val nibiruText = "During the Main Phase, if your opponent Normal or Special Summoned 5 or more monsters this turn (Quick Effect): You can Tribute as many face-up monsters on the field as possible, and if you do, Special Summon this card from your hand."
        assertEquals(PileKind.GY, DuelCardInfo.handCost(ashText))
        assertEquals(PileKind.GY, DuelCardInfo.handCost(maxxText))
        assertEquals(PileKind.BANISHED, DuelCardInfo.handCost("You can banish this card from your hand; draw 1 card."))
        assertNull(DuelCardInfo.handCost(nibiruText))
        val paying = DuelCatalog { c -> catalog.info(c)?.let { if (c == ASH) it.copy(handCost = PileKind.GY) else it } }
        val p = DuelCommand.parse("activate h4", s, 0, paying) as DuelCommand.Parsed.Actions
        assertEquals(listOf(DuelAction.Move(ash, Place.Pile(0, PileKind.GY), null, "activate"), DuelAction.ChainAdd(0, ash)), p.actions)
    }

    // ---- "when i place a card to the deck it places face up" ----

    @Test
    fun aKnownCardPutOnTheDeckIsABack() {
        val back = ok(s, DuelAction.Move(bewd, Place.Pile(0, PileKind.DECK, Place.TOP), how = "return"))
        val frames = DuelFrames.of(back, DuelLayouter.solve(1920f, 1032f, true), setOf(0, 1))
        assertEquals(CardLook.BACK, frames.first { it.uid == bewd }.look)
    }

    // ---- "a way to put cards on the top or bottom … and some shuffle to deck" ----

    @Test
    fun theDeckHasATopABottomAndAMiddleThatShufflesIn() {
        val shuffled = listOf(DuelAction.Move(pot, Place.Pile(0, PileKind.DECK, Place.TOP), null, "shuffle"), DuelAction.Shuffle(0, PileKind.DECK))
        assertEquals(shuffled, actions("ks h3", s))
        assertEquals(shuffled, actions("spin h3", s))
        assertEquals(DeckPart.TOP, DeckPart.at(10f, 120f))
        assertEquals(DeckPart.SHUFFLE, DeckPart.at(60f, 120f))
        assertEquals(DeckPart.BOTTOM, DeckPart.at(110f, 120f))
        val middle = DuelDrop.intent(s, pot, DropSpot.Pile(0, PileKind.DECK, DeckPart.SHUFFLE), catalog)
        assertEquals("Shuffle into the Deck", middle.label)
        assertEquals(shuffled, middle.actions)
        val bottom = DuelDrop.intent(s, pot, DropSpot.Pile(0, PileKind.DECK, DeckPart.BOTTOM), catalog)
        assertEquals(Place.Pile(0, PileKind.DECK, Place.BOTTOM), (bottom.actions.single() as DuelAction.Move).to)
        assertEquals("Top of the Deck", DuelDrop.intent(s, pot, DropSpot.Pile(0, PileKind.DECK, DeckPart.TOP), catalog).label)
        // Without a part (a key, the old drop): Shift the bottom, Alt shuffled in.
        assertEquals("Shuffle into the Deck", DuelDrop.intent(s, pot, DropSpot.Pile(0, PileKind.DECK), catalog, alt = true).label)
        assertTrue(DuelVerb.DECK_SHUFFLE in DuelVerbs.offered(s, 0, pot, catalog))
    }

    // ---- "card effects that banish, discard, or shuffle/bottom of deck randomly" ----

    @Test
    fun aCardAtRandomIsChanceStampedOnCommit() {
        val discard = actions("discard random", s).single()
        assertIs<DuelAction.Pick>(discard)
        assertEquals(Place.Pile(0, PileKind.HAND), discard.from)
        assertEquals(PileKind.GY, discard.to.kind)
        val hand = s.seats[0].hand
        val after = DuelRules.applyAll(s, listOf(discard.copy(salt = 42L)), 0).first!!
        assertEquals(hand.size - 1, after.seats[0].hand.size)
        val gone = hand.single { it !in after.seats[0].hand }
        assertEquals(after.seats[0].gy.first(), gone)
        // The same salt, the same card; the words name it as it lands in the GY.
        assertEquals(after, DuelRules.applyAll(s, listOf(discard.copy(salt = 42L)), 0).first)
        assertTrue(words(s, discard.copy(salt = 42L), 1).startsWith("Kai discards a card at random"), words(s, discard.copy(salt = 42L), 1))
        // Committed, the salt is stamped: the log keeps the chance.
        val g = DuelGame.start(CommandFixtures.header(), 0L)
        val drawn = g.act(listOf(DuelAction.Draw(0, 5)), 0).game!!
        val made = drawn.act(listOf(actions("discard random", drawn.state).single()), 0).game!!
        assertTrue((made.entries.last().action as DuelAction.Pick).salt != 0L)

        val theirs = actions("random oh to gy", s).single() as DuelAction.Pick
        assertEquals(Place.Pile(1, PileKind.HAND), theirs.from)
        val extra = actions("banish random ex down", s).single() as DuelAction.Pick
        assertEquals(Place.Pile(0, PileKind.EXTRA), extra.from)
        assertEquals(CardPosition.FACE_DOWN_DEF, extra.pos)
        val two = actions("discard 2 random", s).single() as DuelAction.Pick
        assertEquals(2, two.n)
        // Named cards to the bottom of the Deck in a random order.
        val order = actions("random h1 h3 kb", s).single() as DuelAction.Pick
        assertEquals(listOf(uid(0, 0), pot), order.among)
        assertEquals(Place.Pile(0, PileKind.DECK, Place.BOTTOM), order.to)
        val bottom = DuelRules.applyAll(s, listOf(order.copy(salt = 7L)), 0).first!!
        assertEquals(setOf(uid(0, 0), pot), bottom.seats[0].deck.takeLast(2).toSet())
        // Too many, or nowhere.
        assertNull(DuelRules.applyAll(s, listOf(two.copy(n = 30)), 0).first)
        assertIs<DuelCommand.Parsed.Problem>(parse("random", s))
    }

    // ---- "when I drag a monster atop another card, it should overlay on top of it" ----

    @Test
    fun aMonsterPutOnAMonsterTakesItAndItsMaterialsBeneath() {
        val i = DuelDrop.intent(s, chaos, DropSpot.Zone(m1), catalog)
        assertEquals("On top of Blue-Eyes White Dragon", i.label)
        val after = DuelRules.applyAll(s, i.actions, 0).first!!
        assertEquals(chaos, after.at(m1))
        assertEquals(listOf(bewd, snake), after.cards.getValue(chaos).under)
        assertTrue(after.cards.getValue(bewd).under.isEmpty())
        assertTrue(after.cards.getValue(bewd).counters.isEmpty())
        assertEquals("Kai Special Summons Chaos Angel from the Extra Deck to M1 on top of Blue-Eyes White Dragon", words(s, i.actions.single(), 0))
        // Never into a Spell & Trap Zone, and never without asking for it.
        assertNull(DuelRules.applyAll(s, listOf(DuelAction.Move(chaos, Place.Zone(0, ZoneKind.SPELL, 0), over = true)), 0).first)
        assertNull(DuelRules.applyAll(s, listOf(DuelAction.Move(chaos, m1)), 0).first)
        assertNotNull(CHAOS)
    }
}
