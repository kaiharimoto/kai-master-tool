package com.kaiharimoto.neue.cards

import androidx.compose.ui.graphics.Color
import com.kaiharimoto.mastertool.core.deck.KeyPaint
import com.kaiharimoto.mastertool.core.deck.KeyTone

/**
 * The colours of the deck's own markers — the second and last place this app
 * may use colour, granted by kai: *"colors are allowed for deckbuilding markers
 * only (like card groups in the deck itself assigned by the user)"*.
 *
 * So a group the user drew wears its hue, in the deck and in its own key, and
 * nothing else in the window does. The six hues are the tablet's, so a deck
 * grouped on one device reads the same on the other.
 *
 * Lenses that are not the user's own partition — type, legality, copies — are
 * facts, not markers, and are told apart by ink weight instead of hue, which is
 * how Master UI says everything else (law 4).
 */
object GroupMarkers {
    val hues = listOf(
        Color(0xFFFF4D6A), // red
        Color(0xFFFFB020), // amber
        Color(0xFF2CE08B), // green
        Color(0xFF35E0FF), // cyan
        Color(0xFF8A7CFF), // violet
        Color(0xFFFF5FD2), // magenta
    )

    fun hue(index: Int): Color = hues[((index % hues.size) + hues.size) % hues.size]

    /**
     * A lens key's paint. Hues are nominal (the user's roles, archetypes) and
     * take colour; tones and greys are facts, and take an alpha of the ink.
     */
    fun paint(paint: KeyPaint, ink: Color): Color = when (paint) {
        is KeyPaint.Hue -> hue(paint.prismIndex)
        is KeyPaint.Grey -> ink.copy(alpha = (0.25f + 0.75f * paint.luminance).coerceIn(0.2f, 1f))
        is KeyPaint.Tone -> ink.copy(
            alpha = when (paint.tone) {
                KeyTone.MONSTER, KeyTone.FORBIDDEN -> 1f
                KeyTone.SPELL, KeyTone.LIMITED -> 0.55f
                KeyTone.TRAP, KeyTone.SEMI_LIMITED -> 0.25f
            },
        )
    }
}
