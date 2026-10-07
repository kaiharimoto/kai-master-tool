package com.kaiharimoto.neue.cards

import androidx.compose.runtime.staticCompositionLocalOf
import com.kaiharimoto.mastertool.core.model.Card

/**
 * Where a card's original artwork comes from, when anywhere: on the desk and Android the art library of
 * originals (`ArtLibrary`, about 2 GB on disk); in the Lounge's browser table, nothing yet (the small render).
 * The card reads [version] so it draws again as pictures arrive.
 */
interface ArtSource {
    val version: Int

    /** What the image loader is given for [id]'s original, or null while there is none. */
    fun fileFor(id: Int): Any?

    /** [card]'s original is wanted soon: fetch it ahead of its turn. */
    fun want(card: Card)
}

/** The person's own pictures for cards (`CustomArt`): a choice is a passcode, or −k for an own picture. */
interface CustomPictures {
    val version: Int

    /** [card] as it is drawn with [choice]: the same card, or one standing for an own picture. */
    fun drawn(card: Card, choice: Int?): Card

    /** Every picture [card] can be drawn with, as choices. */
    fun choices(card: Card): List<Int>
}

/** The originals the card on screen draws from, when there are any. The studio has none, and draws the small renders. */
val LocalArtSource = staticCompositionLocalOf<ArtSource?> { null }

val LocalCustomPictures = staticCompositionLocalOf<CustomPictures?> { null }
