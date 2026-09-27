package com.kaiharimoto.mastertool.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CardArtTest {

    private val card = Card(
        id = CardId(46986414),
        name = "Dark Magician",
        type = "Normal Monster",
        frameType = "normal",
        imageUrl = "https://images.ygoprodeck.com/images/cards/46986414.jpg",
        imageUrlSmall = "https://images.ygoprodeck.com/images/cards_small/46986414.jpg",
        alternateIds = listOf(CardId(46986414), CardId(36996508), CardId(46986415)),
    )

    @Test
    fun aCardsArtsAreItsOwnFirstThenTheRest() {
        assertEquals(listOf(CardId(46986414), CardId(36996508), CardId(46986415)), CardArt.arts(card))
    }

    @Test
    fun anotherArtIsTheSameCardWithThatPicture() {
        val alt = CardArt.show(card, CardId(36996508))
        assertEquals(CardId(36996508), alt.id)
        assertEquals("https://images.ygoprodeck.com/images/cards/36996508.jpg", alt.imageUrl)
        assertEquals("https://images.ygoprodeck.com/images/cards_small/36996508.jpg", alt.imageUrlSmall)
        assertEquals(card.name, alt.name)
        assertEquals(card.alternateIds, alt.alternateIds)
    }

    @Test
    fun itsOwnArtOrOneItDoesNotHaveIsTheCardItself() {
        assertSame(card, CardArt.show(card, null))
        assertSame(card, CardArt.show(card, card.id))
        assertSame(card, CardArt.show(card, CardId(1)))
    }

    @Test
    fun steppingWrapsBothWays() {
        assertEquals(CardId(36996508), CardArt.step(card, null, 1))
        assertEquals(CardId(46986414), CardArt.step(card, CardId(46986415), 1))
        assertEquals(CardId(46986415), CardArt.step(card, card.id, -1))
    }

    @Test
    fun anAddressWithoutThePasscodeFallsBackToYgoprodecks() {
        assertEquals(
            "https://images.ygoprodeck.com/images/cards/5.jpg",
            CardArt.swap("https://example.com/art/other.png", CardId(4), CardId(5), "https://images.ygoprodeck.com/images/cards/"),
        )
        assertEquals("https://x/y/5.png", CardArt.swap("https://x/y/4.png", CardId(4), CardId(5), "unused/"))
    }
}
