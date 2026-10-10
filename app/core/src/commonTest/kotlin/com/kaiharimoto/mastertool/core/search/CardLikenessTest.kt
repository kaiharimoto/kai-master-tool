package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.cards.BanSpell
import com.kaiharimoto.mastertool.core.cards.BanlistWords
import com.kaiharimoto.mastertool.core.deck.DeckRules
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cards like this one (Phase G, G.7), on real texts. */
class CardLikenessTest {
    private fun monster(id: Int, name: String, text: String, level: Int, attr: Attribute, race: String, ban: BanStatus = BanStatus.UNLIMITED, archetype: String? = null) =
        Card(id = CardId(id), name = name, type = "Effect Monster", frameType = "effect", description = text, level = level, attribute = attr, race = race, tcgBanStatus = ban, archetype = archetype)

    private fun spell(id: Int, name: String, text: String, race: String = "Normal") =
        Card(id = CardId(id), name = name, type = "Spell Card", frameType = "spell", description = text, race = race)

    private val ash = monster(14558127, "Ash Blossom & Joyous Spring",
        "When a card or effect is activated that includes any of these effects (Quick Effect): You can discard this card; negate that effect. ● Add a card from the Deck to the hand. ● Special Summon from the Deck. ● Send a card from the Deck to the GY. You can only use this effect of \"Ash Blossom & Joyous Spring\" once per turn.",
        3, Attribute.FIRE, "Zombie")
    private val belle = monster(73642296, "Ghost Belle & Haunted Mansion",
        "When a card or effect is activated that includes any of these effects (Quick Effect): You can discard this card; negate the activation. ● Add a card from the GY to the hand, Deck, and/or Extra Deck. ● Special Summon a monster(s) from the GY. ● Banish a card(s) from the GY. You can only use this effect of \"Ghost Belle & Haunted Mansion\" once per turn.",
        3, Attribute.EARTH, "Zombie")
    private val veiler = monster(97268402, "Effect Veiler",
        "During your opponent's Main Phase (Quick Effect): You can send this card from your hand to the GY, then target 1 Effect Monster your opponent controls; negate the effects of that face-up monster your opponent controls, until the end of this turn.",
        1, Attribute.LIGHT, "Spellcaster")
    private val droll = monster(94145021, "Droll & Lock Bird",
        "If a card(s) is added from the Main Deck or GY to your opponent's hand, except during the Draw Phase (Quick Effect): You can send this card from your hand to the GY; for the rest of this turn, cards cannot be added from either player's Main Deck or GY to the hand.",
        1, Attribute.WIND, "Spellcaster")
    private val maxx = monster(23434538, "Maxx \"C\"",
        "During either player's turn (Quick Effect): You can send this card from your hand to the GY; this turn, each time your opponent Special Summons a monster(s), immediately draw 1 card. You can only use 1 \"Maxx \"C\"\" per turn.",
        2, Attribute.EARTH, "Insect", ban = BanStatus.FORBIDDEN)
    private val prosperity = spell(84211599, "Pot of Prosperity",
        "Banish 3 or 6 cards of your choice from your Extra Deck, face-down; for the rest of this turn after this card resolves, any damage your opponent takes is halved. Excavate cards from the top of your Deck equal to the number of cards banished, add 1 excavated card to your hand, place the rest on the bottom of your Deck in any order. You can only activate 1 \"Pot of Prosperity\" per turn. You cannot draw cards by card effects the turn you activate this card.")
    private val stovie = monster(74018812, "Labrynth Stovie Torbie",
        "If a Normal Trap Card is activated (except during the Damage Step): You can Special Summon this card from your hand or GY, but banish it when it leaves the field. You can only use this effect of \"Labrynth Stovie Torbie\" once per turn.",
        4, Attribute.DARK, "Fiend", archetype = "Labrynth")
    private val chandraglier = monster(37629703, "Labrynth Chandraglier",
        "If a Normal Trap Card is activated (except during the Damage Step): You can Special Summon this card from your hand or GY, but banish it when it leaves the field. You can only use this effect of \"Labrynth Chandraglier\" once per turn.",
        4, Attribute.DARK, "Fiend", archetype = "Labrynth")
    private val pool = listOf(belle, veiler, droll, maxx, prosperity, stovie, chandraglier)
    private val tcg = CardLikeness.Room(DeckRules(format = Format.TCG), "2026-10-10")

    @Test
    fun handTrapsFindHandTraps() {
        val found = CardLikeness.similar(listOf(ash), pool, tcg)
        assertEquals(setOf("Ghost Belle & Haunted Mansion", "Effect Veiler", "Droll & Lock Bird"), found.take(3).map { it.card.name }.toSet(), found.toString())
        assertEquals("Ghost Belle & Haunted Mansion", found.first().card.name, "the same words, the same Level, the same race")
        assertTrue(found.none { it.card.name == "Pot of Prosperity" } || found.last().card.name == "Pot of Prosperity")
        // A Labrynth monster finds the other one, its archetype and its words.
        assertEquals("Labrynth Chandraglier", CardLikeness.similar(listOf(stovie), pool, tcg).first().card.name)
    }

    @Test
    fun nothingTheRulesBarIsOfferedNorACardAlreadyAtItsLimit() {
        val found = CardLikeness.similar(listOf(ash), pool, tcg)
        assertTrue(found.none { it.card.name == "Maxx \"C\"" }, "Forbidden in the TCG")
        val full = CardLikeness.Room(DeckRules(format = Format.TCG), "2026-10-10", held = mapOf(belle.id to 3))
        assertTrue(CardLikeness.similar(listOf(ash), pool, full).none { it.card.id == belle.id }, "three already in the deck")
        // A group's profile: its members' mean, the members themselves never offered.
        val group = CardLikeness.similar(listOf(ash, veiler), pool, tcg)
        assertTrue(group.none { it.card.id == veiler.id })
        assertTrue(group.first().card.name in setOf("Ghost Belle & Haunted Mansion", "Droll & Lock Bird"))
    }

    @Test
    fun thePoolListsThemMostAlikeFirst() {
        val found = CardLikeness.similar(listOf(ash), pool, tcg).map { it.card.id.value }
        val index = CardIndex.build(pool + ash)
        val shown = index.search("", CardFilter(onlyIds = found.toSet(), ranked = found)).cards.map { it.id.value }
        assertEquals(found, shown, "the likeness's order, not the names'")
        // Without it, by name as ever.
        assertEquals(shown.map { id -> pool.first { it.id.value == id }.name }.sorted(), index.search("", CardFilter(onlyIds = found.toSet())).cards.map { it.name })
    }

    @Test
    fun aBanlistHistoryIsOneLineOfYears() {
        val spells = listOf(
            BanSpell(BanStatus.LIMITED, "2019-01-28", "2021-03-14", listOf("a")),
            BanSpell(BanStatus.UNLIMITED, "2021-03-15", "2023-01-31", listOf("b")),
            BanSpell(BanStatus.SEMI_LIMITED, "2023-02-01", null, listOf("c")),
        )
        assertEquals("Limited 2019–21 · Semi-Limited 2023–now", BanlistWords.line(spells))
        assertNull(BanlistWords.line(listOf(BanSpell(BanStatus.UNLIMITED, "2019-01-01", null, listOf("a")))))
    }
}
