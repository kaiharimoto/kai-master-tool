package com.kaiharimoto.mastertool.core.ai.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ai's replies laid out as cards (1.0.55): the ```deck, ```compare, ```line and ```board blocks, and grouped ```cards. */
class CardBlocksTest {
    private fun only(text: String): Block = ChatMarkdown.parse(text).single()

    @Test
    fun cardsGroupUnderTheirLabels() {
        val b = assertIs<Block.Cards>(only("```cards\n## Starters\n3 Snake-Eye Ash\n2 Snake-Eye Oak\n## Hand traps\nAsh Blossom & Joyous Spring x3\n```"))
        assertEquals(listOf("Starters", "Hand traps"), b.groups.map { it.label })
        assertEquals(5, b.groups[0].count)
        assertEquals(3, b.lines.size, "every card, in order, for the old readers")
        val plain = assertIs<Block.Cards>(only("```cards\n3 Nibiru, the Primal Being\n```"))
        assertEquals(listOf(""), plain.groups.map { it.label })
    }

    @Test
    fun aDeckIsReadSectionBySectionInTheUsualOrder() {
        val d = assertIs<Block.Deck>(
            only("```deck\nSide Deck (3):\n3 Droll & Lock Bird\nMain Deck (4):\n3 Snake-Eye Ash\n1 [[Snake-Eye Oak]]\nExtra:\n1 S:P Little Knight\n```"),
        )
        assertEquals(listOf("Main Deck", "Extra", "Side Deck"), d.sections.map { it.label })
        assertEquals(8, d.total)
        assertEquals("Snake-Eye Oak", d.sections[0].lines[1].name)
        val bare = assertIs<Block.Deck>(only("```deck\n3 Snake-Eye Ash\n```"))
        assertEquals("Main", bare.sections.single().label)
    }

    @Test
    fun aChangeIsWhatGoesOutAndWhatComesIn() {
        val c = assertIs<Block.Compare>(only("```compare\nOut:\n1 Nibiru, the Primal Being\nIn:\n2 Infinite Impermanence\n```"))
        assertEquals(1, c.out.count)
        assertEquals(2, c.into.count)
        val signed = assertIs<Block.Compare>(only("```compare\n-1 Droll & Lock Bird\n+1 Evenly Matched\n```"))
        assertEquals("Droll & Lock Bird", signed.out.lines.single().name)
        assertEquals("Evenly Matched", signed.into.lines.single().name)
    }

    @Test
    fun aLineIsItsStepsEachWithItsCard() {
        val l = assertIs<Block.Line>(
            only("```line\n1. [[Snake-Eye Ash]] — Normal Summon; search [[Snake-Eye Oak]].\n2. [[Snake-Eye Oak]]: Special Summon it.\n3. Link into I:P Masquerena.\n```"),
        )
        assertEquals(listOf("Snake-Eye Ash", "Snake-Eye Oak", null), l.steps.map { it.card })
        assertTrue(l.steps[0].action.any { it is Inline.Card && it.name == "Snake-Eye Oak" }, "the other cards stay links in the words")
        assertEquals("Special Summon it.", (l.steps[1].action.single() as Inline.Text).text)
    }

    @Test
    fun aBoardIsTheFieldZoneByZone() {
        val b = assertIs<Block.Board>(
            only(
                "```board\nMonsters: Fiendsmith's Lacrima, -, [[Snake-Eyes Poplar]]\nExtra Monster: -, S:P Little Knight\n" +
                    "Spells/Traps: Fiendsmith's Tract (set), -\nField: Fiendsmith's Sequence\nHand: Ash Blossom & Joyous Spring\nGY: 2 Fiendsmith Engraver\n```",
            ),
        ).board
        assertEquals(5, b.monsters.size)
        assertEquals("Fiendsmith's Lacrima", b.monsters[0]?.name)
        assertNull(b.monsters[1])
        assertEquals("Snake-Eyes Poplar", b.monsters[2]?.name)
        assertNull(b.extraMonsters[0])
        assertEquals("S:P Little Knight", b.extraMonsters[1]?.name)
        assertTrue(b.spells[0]!!.set)
        assertEquals("Fiendsmith's Sequence", b.field?.name)
        assertEquals(2, b.graveyard.single().count)
    }

    @Test
    fun blocksStillBeingWrittenWaitQuietlyAndNonsenseShowsAsCode() {
        assertEquals(Block.Pending("Setting the board"), ChatMarkdown.parse("```board\nMonsters: A", streaming = true).single())
        assertEquals(Block.Pending("Drawing the line"), ChatMarkdown.parse("```line\n1. [[A]]", streaming = true).single())
        assertIs<Block.Code>(only("```board\nnothing here\n```"))
    }
}
