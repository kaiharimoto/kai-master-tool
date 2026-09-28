package com.kaiharimoto.neue.cards

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    /** A set of six group colours, by [id], shown as [name]. */
    class Palette(val id: String, val name: String, val colors: List<Color>)

    /**
     * The palettes a deck's groups can be coloured from (kai, 1.0.17: "more
     * choices in color palettes … a different palette from designer choices").
     * Six each, because a group's colour is stored as an index (`DeckGroup.color`)
     * and a palette is only a reading of it: switching palettes recolours every
     * group at once and changes nothing in the deck file. Each is chosen to hold
     * six distinct hues on paper and on ink alike.
     */
    val palettes = listOf(
        Palette(
            "prism", "Prism",
            listOf(Color(0xFFFF4D6A), Color(0xFFFFB020), Color(0xFF2CE08B), Color(0xFF35E0FF), Color(0xFF8A7CFF), Color(0xFFFF5FD2)),
        ),
        Palette(
            "bauhaus", "Bauhaus",
            listOf(Color(0xFFE63322), Color(0xFFF5B700), Color(0xFF1D5FBF), Color(0xFF2B9348), Color(0xFFF26B21), Color(0xFF7A4FA0)),
        ),
        Palette(
            "pastel", "Pastel",
            listOf(Color(0xFFFF9AA2), Color(0xFFFFD6A5), Color(0xFFB5EAD7), Color(0xFFA0C4FF), Color(0xFFCDB4DB), Color(0xFFFDFFB6)),
        ),
        Palette(
            "earth", "Earth",
            listOf(Color(0xFFC8553D), Color(0xFFD4A373), Color(0xFF8A9A5B), Color(0xFF588B8B), Color(0xFF9C6644), Color(0xFFB5838D)),
        ),
        Palette(
            "ocean", "Ocean",
            listOf(Color(0xFF0096C7), Color(0xFF48CAE4), Color(0xFF2EC4B6), Color(0xFF3A86FF), Color(0xFF90E0EF), Color(0xFF5E60CE)),
        ),
        Palette(
            "neon", "Neon",
            listOf(Color(0xFFFF2E63), Color(0xFFFFE600), Color(0xFF39FF14), Color(0xFF00F0FF), Color(0xFFB026FF), Color(0xFFFF7A00)),
        ),
        Palette(
            "vintage", "Vintage",
            listOf(Color(0xFFA4493D), Color(0xFFD9A441), Color(0xFF7D9D72), Color(0xFF4F6D7A), Color(0xFF8C6A93), Color(0xFFC97C5D)),
        ),
    )

    /**
     * The palette in use. Snapshot state, so everything that reads a group's
     * colour — in composition or in a draw block — follows a change of palette.
     */
    var palette by androidx.compose.runtime.mutableStateOf(palettes.first())

    fun byId(id: String): Palette = palettes.firstOrNull { it.id == id } ?: palettes.first()

    val hues: List<Color> get() = palette.colors

    fun hue(index: Int): Color = hues[((index % hues.size) + hues.size) % hues.size]

    /**
     * [color] turned a little way round the colour wheel and back as [phase] runs:
     * a group's own colour, catching the light the way the foil does (zen's piece
     * outlines, 1.0.15). At most [SHIMMER] degrees either side, so it never reads
     * as another group's colour.
     */
    fun shimmer(color: Color, phase: Float): Color {
        val hsv = com.kaiharimoto.mastertool.core.model.Hsb.fromRgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        val turn = SHIMMER / 360f * kotlin.math.sin(phase)
        val rgb = com.kaiharimoto.mastertool.core.model.Hsb.toRgb(((hsv[0] + turn) % 1f + 1f) % 1f, hsv[1], hsv[2])
        return Color(rgb).copy(alpha = color.alpha)
    }

    const val SHIMMER = 22f

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
