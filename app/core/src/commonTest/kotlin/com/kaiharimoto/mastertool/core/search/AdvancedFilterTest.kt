package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancedFilterTest {

    private fun card(
        id: Int,
        name: String,
        type: String,
        frame: String,
        text: String = "",
        race: String? = null,
        atk: Int? = null,
        level: Int? = null,
        link: Int? = null,
        arrows: List<String> = emptyList(),
        scale: Int? = null,
    ) = Card(CardId(id), name, type, frame, text, race = race, atk = atk, level = level, linkValue = link, linkMarkers = arrows, pendulumScale = scale)

    private val ash = card(
        1, "Ash Blossom & Joyous Spring", "Tuner Effect Monster", "effect",
        "When a card or effect is activated that includes any of these effects (Quick Effect): You can discard this card; negate that effect. " +
            "● Add a card from the Deck to the hand. ● Special Summon from the Deck. ● Send a card from the Deck to the GY. You can only use this effect of \"Ash Blossom & Joyous Spring\" once per turn.",
        race = "Zombie", atk = 0, level = 3,
    )
    private val lady = card(
        2, "Lady Labrynth of the Silver Castle", "Effect Monster", "effect",
        "Cannot be destroyed by card effects during the turn it was Special Summoned. You can Set 1 Normal Trap directly from your Deck to your Spell & Trap Zone. " +
            "Each time a Normal Trap Card is activated, draw 1 card, also you can Special Summon 1 monster from your hand.",
        race = "Fiend", atk = 3000, level = 8,
    )
    private val potOfDesires = card(3, "Pot of Desires", "Spell Card", "spell", "Banish 10 cards from the top of your Deck, face-down; draw 2 cards.", race = "Normal")
    private val calledBy = card(4, "Called by the Grave", "Spell Card", "spell", "Target 1 monster in your opponent's GY; banish it, and if you do, until the end of the next turn, its effects are negated.", race = "Quick-Play")
    private val apollousa = card(
        5, "Apollousa, Bow of the Goddess", "Link Effect Monster", "link",
        "2+ monsters with different names, except Tokens. You can only control 1. The original ATK of this card becomes 800 x the number of Link Materials. " +
            "Once per Chain, when your opponent activates a monster effect (Quick Effect): You can negate the activation, and if you do, this card loses exactly 800 ATK.",
        race = "Fairy", atk = -1, link = 4, arrows = listOf("Bottom-Left", "Bottom", "Bottom-Right", "Top"),
    )
    private val searcher = card(
        6, "Labrynth Chandraglier", "Spell Card", "spell",
        "Add 1 \"Labrynth\" card from your Deck to your hand, except \"Labrynth Chandraglier\".", race = "Normal",
    )
    private val nibiru = card(
        7, "Nibiru, the Primal Being", "Effect Monster", "effect",
        "During the Main Phase, if your opponent Normal or Special Summoned 5 or more monsters this turn (Quick Effect): You can Tribute as many face-up monsters on the field as possible, " +
            "and if you do, Special Summon this card from your hand, then Special Summon 1 \"Primal Being Token\" to your opponent's field.",
        race = "Rock", atk = 3000, level = 11,
    )
    private val pendulum = card(8, "Pendulum Magician", "Pendulum Effect Monster", "effect_pendulum", "", race = "Spellcaster", atk = 1500, level = 4, scale = 8)
    private val all = listOf(ash, lady, potOfDesires, calledBy, apollousa, searcher, nibiru, pendulum)
    private val index = CardIndex.build(all)

    private fun names(filter: CardFilter) = index.search("", filter, limit = 50).cards.map { it.name }.toSet()

    @Test
    fun theEffectKindsReadTheTextAsAPlayerWould() {
        assertEquals(setOf(EffectKind.NEGATE, EffectKind.HAND_TRAP), EffectKinds.of(ash) intersect setOf(EffectKind.NEGATE, EffectKind.HAND_TRAP, EffectKind.SEARCH))
        assertTrue(EffectKind.SEARCH in EffectKinds.of(searcher))
        assertFalse(EffectKind.SEARCH in EffectKinds.of(lady))
        assertTrue(EffectKind.SET in EffectKinds.of(lady))
        assertTrue(EffectKind.DRAW in EffectKinds.of(lady))
        // "Cannot be destroyed" is protection, not destruction.
        assertTrue(EffectKind.PROTECTION in EffectKinds.of(lady))
        assertFalse(EffectKind.DESTROY in EffectKinds.of(lady))
        assertTrue(EffectKind.BANISH in EffectKinds.of(calledBy))
        assertTrue(EffectKind.TOKEN in EffectKinds.of(nibiru))
        assertTrue(EffectKind.SPECIAL_SUMMON in EffectKinds.of(nibiru))
    }

    @Test
    fun effectsAreAllOfAndFacetsAreOneOf() {
        assertEquals(setOf("Lady Labrynth of the Silver Castle"), names(CardFilter(effects = setOf(EffectKind.DRAW, EffectKind.SET))))
        assertEquals(
            setOf("Pot of Desires", "Lady Labrynth of the Silver Castle"),
            names(CardFilter(effects = setOf(EffectKind.DRAW))),
        )
    }

    @Test
    fun framesAbilitiesAndProperties() {
        assertEquals(setOf("Apollousa, Bow of the Goddess"), names(CardFilter(frames = setOf(MonsterFrame.LINK))))
        assertEquals(setOf("Ash Blossom & Joyous Spring"), names(CardFilter(abilities = setOf(MonsterAbility.TUNER))))
        assertEquals(setOf("Pendulum Magician"), names(CardFilter(frames = setOf(MonsterFrame.PENDULUM))))
        assertEquals(setOf("Called by the Grave"), names(CardFilter(properties = setOf("Quick-Play"))))
        // A monster type and a spell property together are one choice: either.
        assertEquals(setOf("Called by the Grave", "Nibiru, the Primal Being"), names(CardFilter(races = setOf("Rock"), properties = setOf("Quick-Play"))))
    }

    @Test
    fun linkArrowsMustAllBePointedAt() {
        assertEquals(setOf("Apollousa, Bow of the Goddess"), names(CardFilter(linkArrows = setOf("Top", "Bottom"))))
        assertTrue(names(CardFilter(linkArrows = setOf("Left"))).isEmpty())
        assertEquals(setOf("Apollousa, Bow of the Goddess"), names(CardFilter(linkRatings = setOf(4))))
        assertEquals(setOf("Pendulum Magician"), names(CardFilter(scales = setOf(8))))
    }

    @Test
    fun theListAndTheOrderAreNotFacets() {
        val listed = CardFilter(onlyIds = setOf(1, 7), sort = CardSort.ATK)
        assertEquals(0, listed.activeFacetCount)
        assertEquals(listOf("Nibiru, the Primal Being", "Ash Blossom & Joyous Spring"), index.search("", listed, limit = 10).cards.map { it.name })
        assertEquals(
            listOf("Ash Blossom & Joyous Spring", "Nibiru, the Primal Being"),
            index.search("", listed.copy(reverse = true), limit = 10).cards.map { it.name },
        )
        // Clearing keeps the list and the order.
        assertEquals(listed, listed.copy(effects = setOf(EffectKind.DRAW)).cleared())
    }
}

class CardListsTest {
    private val lists = listOf(
        com.kaiharimoto.mastertool.core.prefs.CardList("list-1", "Considering", listOf(1, 2)),
    )

    @Test
    fun aCardGoesOnAtTheEndAndComesOffWhereItWas() {
        val list = lists.first()
        assertEquals(listOf(1, 2, 3), com.kaiharimoto.mastertool.core.prefs.CardLists.toggle(list, 3).ids)
        assertEquals(listOf(2), com.kaiharimoto.mastertool.core.prefs.CardLists.toggle(list, 1).ids)
        assertEquals(list, com.kaiharimoto.mastertool.core.prefs.CardLists.add(list, 2))
    }

    @Test
    fun newListsNeverTakeANameOrIdInUse() {
        assertEquals("List 2", com.kaiharimoto.mastertool.core.prefs.CardLists.newName(lists))
        assertEquals("Considering", com.kaiharimoto.mastertool.core.prefs.CardLists.newName(emptyList()))
        assertEquals("list-2", com.kaiharimoto.mastertool.core.prefs.CardLists.newId(lists))
    }
}
