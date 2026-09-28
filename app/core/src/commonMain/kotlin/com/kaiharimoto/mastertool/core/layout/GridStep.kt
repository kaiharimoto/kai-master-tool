package com.kaiharimoto.mastertool.core.layout

/** A direction the arrow keys move a selection in. */
enum class StepDirection { LEFT, RIGHT, UP, DOWN }

/**
 * Where the arrow keys take a selected card in a grid of [count] cards in rows of
 * [columns] (kai, 1.0.18: "once a player selects a card … let them move the
 * selection with the arrow keys"): one card along, or one row up or down. Left
 * and right run on through the row ends, as reading does; up from the top row
 * and down past the bottom stay put rather than wrapping, and down into a short
 * last row lands on its last card rather than on nothing. Null when there is
 * nowhere to go, so the caller can hand the key to the next section.
 */
object GridStep {
    fun move(index: Int, count: Int, columns: Int, direction: StepDirection): Int? {
        if (count <= 0 || columns <= 0 || index !in 0 until count) return null
        return when (direction) {
            StepDirection.LEFT -> (index - 1).takeIf { it >= 0 }
            StepDirection.RIGHT -> (index + 1).takeIf { it < count }
            StepDirection.UP -> (index - columns).takeIf { it >= 0 }
            StepDirection.DOWN -> {
                val below = index + columns
                when {
                    below < count -> below
                    // A short last row: the nearest card in it, if there is a row below at all.
                    (index / columns) < (count - 1) / columns -> count - 1
                    else -> null
                }
            }
        }
    }
}
