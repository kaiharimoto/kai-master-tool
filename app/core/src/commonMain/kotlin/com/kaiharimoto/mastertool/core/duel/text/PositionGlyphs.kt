package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition

/**
 * The position glyphs (D.md §5¾.9, kai: "for card positions in the UI we should integrate small visual graphics"): three
 * line drawings on a 24-unit grid, stroked 1.5 units — square caps, mitre joins, no radius, no colour. **The hollow window
 * is face-up; the solid fill is the back**: that is the one difference to learn. A glyph never stands alone: its word
 * ([word]) stands beside it or under it.
 *
 * The geometry is here, in grid units, so every painter draws the same marks (`neue/duel/PositionGlyph.kt` in Compose)
 * and a test holds the strokes to whole pixels at 16, 20 and 24 dp.
 */
object PositionGlyphs {
    /** The grid a glyph is drawn on, and its stroke, in grid units. */
    const val GRID = 24f
    const val STROKE = 1.5f

    /** The sizes the window draws them at (dp): the log and the inspector, the desk's chips, the phone's chips, a lit zone. */
    val SIZES = listOf(16f, 20f, 24f, 30f)

    enum class Kind {
        /** A rectangle's outline, stroked. */
        OUTLINE,

        /** A filled rectangle: the card's back. */
        FILL,

        /** A line from ([Part.x], [Part.y]) by ([Part.w], [Part.h]). */
        RULE,
    }

    /** One mark of a glyph, in grid units: a rectangle at ([x], [y]) of [w] × [h], or a rule from there by ([w], [h]). */
    data class Part(val kind: Kind, val x: Float, val y: Float, val w: Float, val h: Float)

    private val ATTACK = listOf(
        Part(Kind.OUTLINE, 6.75f, 2.75f, 10.5f, 18.5f),
        Part(Kind.OUTLINE, 9f, 5f, 6f, 6f),
        Part(Kind.RULE, 9f, 15.5f, 6f, 0f),
    )
    private val DEFENSE = listOf(
        Part(Kind.OUTLINE, 2.75f, 6.75f, 18.5f, 10.5f),
        Part(Kind.OUTLINE, 5f, 9f, 6f, 6f),
        Part(Kind.RULE, 15.5f, 9f, 0f, 6f),
    )
    private val SET = listOf(
        Part(Kind.OUTLINE, 2.75f, 6.75f, 18.5f, 10.5f),
        Part(Kind.FILL, 5f, 9f, 14f, 6f),
    )
    private val DOWN_ATTACK = listOf(
        Part(Kind.OUTLINE, 6.75f, 2.75f, 10.5f, 18.5f),
        Part(Kind.FILL, 9f, 5f, 6f, 13f),
    )

    /** [pos]'s marks. */
    fun of(pos: CardPosition): List<Part> = when (pos) {
        CardPosition.FACE_UP_ATK -> ATTACK
        CardPosition.FACE_UP_DEF -> DEFENSE
        CardPosition.FACE_DOWN_DEF -> SET
        CardPosition.FACE_DOWN_ATK -> DOWN_ATTACK
    }

    /** The word that stands beside [pos]'s glyph. */
    fun word(pos: CardPosition): String = when (pos) {
        CardPosition.FACE_UP_ATK -> "Attack"
        CardPosition.FACE_UP_DEF -> "Defense"
        CardPosition.FACE_DOWN_DEF -> "Set"
        CardPosition.FACE_DOWN_ATK -> "Face-down"
    }

    /** The key that chooses [pos] in the Shortcut window: A, D, E (as the table's Set is E). */
    fun key(pos: CardPosition): String = when (pos) {
        CardPosition.FACE_UP_ATK -> "A"
        CardPosition.FACE_UP_DEF -> "D"
        CardPosition.FACE_DOWN_DEF, CardPosition.FACE_DOWN_ATK -> "E"
    }

    /** A filled rectangle of ink, in pixels: [l], [t] inclusive, [r], [b] exclusive. */
    data class Px(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
    }

    /** The stroke at [px] pixels a glyph: 1.5 grid units, rounded to whole pixels and never under one. */
    fun stroke(px: Float): Float = kotlin.math.round(STROKE * px / GRID).coerceAtLeast(1f)

    /**
     * [pos]'s glyph at [px] pixels square, as filled rectangles on **whole pixels** (D.md §5¾.9: "1 px at 16 dp, 2 px at
     * 32 dp"): each outline is its four edges, each rule a bar, each fill itself — the grid's edges rounded to the pixel and
     * the stroke to a whole number of them, square caps and mitre joins by construction. What every painter draws, so a
     * 1.5-unit line is never smeared across two pixels.
     */
    fun pixels(pos: CardPosition, px: Float): List<Px> {
        val u = px / GRID
        val s = stroke(px)
        fun r(v: Float) = kotlin.math.round(v)
        return of(pos).flatMap { p ->
            when (p.kind) {
                Kind.FILL -> listOf(Px(r(p.x * u), r(p.y * u), r((p.x + p.w) * u), r((p.y + p.h) * u)))
                Kind.OUTLINE -> {
                    // The outer edge of the ink, on the pixel; the stroke inward from it.
                    val l = r(p.x * u - s / 2f)
                    val t = r(p.y * u - s / 2f)
                    val rr = l + r(p.w * u) + s
                    val b = t + r(p.h * u) + s
                    listOf(Px(l, t, rr, t + s), Px(l, b - s, rr, b), Px(l, t, l + s, b), Px(rr - s, t, rr, b))
                }
                Kind.RULE -> {
                    // A square-capped line: as long as it is drawn, plus half the stroke at each end, the stroke thick.
                    val x0 = r(p.x * u - s / 2f)
                    val y0 = r(p.y * u - s / 2f)
                    listOf(Px(x0, y0, x0 + r(p.w * u) + s, y0 + r(p.h * u) + s))
                }
            }
        }
    }
}
