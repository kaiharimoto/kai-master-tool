package com.kaiharimoto.mastertool.core.layout

/**
 * The card's text first, the picture in what is left (1.0.88, kai: "prioritize the effect text and try its
 * best to fit all of it in the inspector so the user doesn't have to scroll down").
 *
 * A card read beside the deck is a picture over its words. The words are measured first: the picture
 * takes the height they leave, between [fit]'s `artMin` (a deck card's size — still known on sight) and
 * `artMax` (the column's width at 59:86). Only when the words do not fit beside the least picture does
 * the text step down a size at a time, to a floor that stays readable; only past the floor does the
 * column scroll. Everything is in pixels and comes from measurements, so a card in a column of a given
 * size always lays out the same — moving from card to card changes nothing a card's own text does not.
 */
object TextFirst {
    /**
     * @property art the picture's height.
     * @property size the text size chosen, one of the sizes offered.
     * @property whole whether the picture, the words and [fit]'s `fixed` all fit the room — false only
     *   when the text is at its floor and the picture at its least and they still do not.
     */
    data class Fit(val art: Int, val size: Int, val whole: Boolean)

    /**
     * @param room the height that is seen without scrolling, or null when there is no limit.
     * @param fixed everything else that must be seen whole: the name, the type line, the numbers and gaps.
     * @param artMin the picture's least height; never more than [artMax].
     * @param artMax the picture's natural height.
     * @param sizes the text sizes to try, largest first (the normal size, then a step at a time to the floor).
     * @param textHeight the text's measured height at a size.
     */
    fun fit(room: Int?, fixed: Int, artMin: Int, artMax: Int, sizes: List<Int>, textHeight: (Int) -> Int): Fit {
        require(sizes.isNotEmpty()) { "no text sizes" }
        val most = artMax.coerceAtLeast(0)
        val least = artMin.coerceIn(0, most)
        if (room == null) return Fit(most, sizes.first(), true)
        for (size in sizes) {
            val spare = room - fixed - textHeight(size)
            if (spare >= least) return Fit(spare.coerceAtMost(most), size, true)
        }
        return Fit(least, sizes.last(), false)
    }

    /** The sizes from [normal] down to [floor] a step of one at a time; just [normal] when it is the floor or under. */
    fun steps(normal: Int, floor: Int): List<Int> = if (normal <= floor) listOf(normal) else (normal downTo floor).toList()
}
