package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.search.CardIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpponentGuessTest {

    private var next = 1

    private fun effect(name: String, archetype: String?, text: String = "") =
        Card(CardId(next++), name, "Effect Monster", "effect", description = text, archetype = archetype)

    private fun normal(name: String, archetype: String?) = Card(CardId(next++), name, "Normal Monster", "normal", archetype = archetype)
    private fun link(name: String, archetype: String?) = Card(CardId(next++), name, "Link Monster", "link", archetype = archetype)
    private fun spell(name: String, archetype: String?, text: String = "") =
        Card(CardId(next++), name, "Spell Card", "spell", description = text, archetype = archetype)

    private fun trap(name: String, archetype: String?) = Card(CardId(next++), name, "Trap Card", "trap", archetype = archetype)

    private val cards = listOf(
        // Snake-Eye, out of order on purpose.
        spell("Original Sinful Spoils - Snake-Eye", "Snake-Eye"),
        link("Snake-Eyes Doomed Dragon", "Snake-Eye"),
        effect("Snake-Eye Ash", "Snake-Eye"),
        effect("Diabellstar the Black Witch", "Snake-Eye"),
        effect("Snake-Eye Oak", "Snake-Eye"),
        // Fire King, and a longer archetype that contains its words.
        effect("Fire King Avatar Arvata", "Fire King"),
        effect("Fire King High Avatar Garunix", "Fire King"),
        trap("Fire King Sanctuary", "Fire King"),
        effect("Fire King Avatar Kirin", "Fire King Avatar"),
        // Yubel, which is also a card's name.
        effect("Yubel", "Yubel", "Cannot be destroyed by battle."),
        effect("Yubel - Terror Incarnate", "Yubel", "Special Summon 1 \"Yubel\"."),
        spell("Nightmare Pain", "Yubel", "Add 1 \"Yubel\" from your Deck."),
        effect("Spirit of Yubel", "Yubel"),
        normal("Samsara D Lotus", null),
        // Ryzeal and Mitsurugi.
        effect("Ryzeal Detonator", "Ryzeal"),
        effect("Ryzeal Plasma Hole", "Ryzeal"),
        effect("Mitsurugi no Mikoto, Aramasa", "Mitsurugi"),
        effect("Mitsurugi no Mikoto, Saji", "Mitsurugi"),
        // Light and Darkness Ritual: not an archetype, but "Darkness" is one.
        spell("Light and Darkness Ritual", null, "Ritual Summon 1 Ritual Monster."),
        effect("Chaos Hunter", null, "Add 1 \"Light and Darkness Ritual\" from your Deck to your hand."),
        effect("Umbral Servant", null, "Send 1 card to the GY; add \"Light and Darkness Ritual\" from your Deck."),
        effect("Dark Necrofear Darkness", "Darkness"),
        // A pair for the fallback.
        effect("Maxx \"C\"", null, "Draw 1 card."),
    )
    private val index = CardIndex.build(cards)

    private fun names(list: List<Card>) = list.map { it.name }

    @Test
    fun archetypesAreFoundAsWholeWordsWhateverTheCaseOrHyphen() {
        val all = index.archetypes
        assertEquals(listOf("Fire King", "Snake-Eye"), archetypesSorted("Snake-Eye Fire King", all))
        assertEquals(listOf("Snake-Eye"), OpponentGuess.archetypesIn("snake eye", all))
        assertEquals(listOf("Snake-Eye"), OpponentGuess.archetypesIn("SNAKE-EYE combo", all))
        // Whole words only: "Yubels" is not "Yubel", "Snake" alone is not "Snake-Eye".
        assertEquals(emptyList(), OpponentGuess.archetypesIn("Yubels", all))
        assertEquals(emptyList(), OpponentGuess.archetypesIn("Snake", all))
        // In a row: the words scattered do not count.
        assertEquals(emptyList(), OpponentGuess.archetypesIn("Fire Snake King", listOf("Fire King")))
        // Apostrophes do not split a word.
        assertEquals(listOf("Dragon's Breath"), OpponentGuess.archetypesIn("dragons breath", listOf("Dragon's Breath")))
    }

    private fun archetypesSorted(name: String, all: List<String>) = OpponentGuess.archetypesIn(name, all).sorted()

    @Test
    fun theLongerArchetypeSwallowsTheOneInsideIt() {
        val all = index.archetypes
        assertEquals(listOf("Fire King Avatar"), OpponentGuess.archetypesIn("Fire King Avatar", all))
        // Longest first, then in the order written.
        assertEquals(listOf("Fire King Avatar", "Ryzeal"), OpponentGuess.archetypesIn("Ryzeal Fire King Avatar", all))
        assertEquals(listOf("Ryzeal", "Mitsurugi"), OpponentGuess.archetypesIn("Ryzeal Mitsurugi", all))
    }

    @Test
    fun anArchetypesFacesComeFirst() {
        val snakeEye = names(OpponentGuess.suggest("Snake-Eye", index))
        assertEquals(
            listOf(
                "Snake-Eye Ash", "Snake-Eye Oak", // effect monsters carrying the name
                "Diabellstar the Black Witch", // other main-deck monsters
                "Snake-Eyes Doomed Dragon", // extra deck
                "Original Sinful Spoils - Snake-Eye", // spells and traps
            ),
            snakeEye,
        )
    }

    @Test
    fun twoArchetypesTakeTurns() {
        val mix = names(OpponentGuess.suggest("Ryzeal Mitsurugi", index))
        assertEquals(
            listOf("Ryzeal Detonator", "Mitsurugi no Mikoto, Aramasa", "Ryzeal Plasma Hole", "Mitsurugi no Mikoto, Saji"),
            mix,
        )
        val snakeFire = names(OpponentGuess.suggest("Snake-Eye Fire King", index, limit = 4))
        assertEquals(listOf("Snake-Eye Ash", "Fire King Avatar Arvata", "Snake-Eye Oak", "Fire King High Avatar Garunix"), snakeFire)
    }

    @Test
    fun aNameThatIsAlsoAnArchetypeIsReadAsTheArchetype() {
        val yubel = names(OpponentGuess.suggest("yubel", index))
        assertEquals(listOf("Spirit of Yubel", "Yubel", "Yubel - Terror Incarnate", "Nightmare Pain"), yubel)
    }

    @Test
    fun aDeckNamedAfterACardIsTheCardsThatQuoteIt() {
        val ritual = names(OpponentGuess.suggest("Light and Darkness Ritual", index))
        assertEquals("Light and Darkness Ritual", ritual.first())
        assertTrue("Chaos Hunter" in ritual && "Umbral Servant" in ritual, "$ritual")
        assertTrue("Dark Necrofear Darkness" !in ritual, "the Darkness archetype is not what was meant: $ritual")
    }

    @Test
    fun noArchetypeFallsBackToTheTextThenTheNames() {
        val maxx = names(OpponentGuess.suggest("Maxx", index))
        assertEquals(listOf("Maxx \"C\""), maxx)
        assertEquals(emptyList(), OpponentGuess.suggest("   ", index))
        assertEquals(emptyList(), OpponentGuess.suggest("Nothing at all like it", index))
    }

    @Test
    fun theLimitAndDedupingHold() {
        val two = OpponentGuess.suggest("Snake-Eye", index, limit = 2)
        assertEquals(2, two.size)
        val all = OpponentGuess.suggest("Snake-Eye Snake-Eye", index)
        assertEquals(all.map { it.id }.distinct(), all.map { it.id })
    }
}
