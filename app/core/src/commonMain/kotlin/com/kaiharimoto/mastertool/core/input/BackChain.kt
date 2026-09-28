package com.kaiharimoto.mastertool.core.input

/**
 * What Esc and Android's Back unwind, and in what order (touch swarm, rec 2).
 *
 * Back used to call only `NeueState.dismissTop` — the menu, the viewer, the
 * palette and a handful more — and when that found nothing, the app went home:
 * from the goal editor, from a group being drafted, from the cover picker, from
 * immersive and from deep zen. Esc's own chain knew better, and the two had
 * drifted. Both read this now.
 *
 * They differ in one way on purpose. Esc may also drop focus, an isolated group
 * and the selection, because on a desk those are one more thing the key clears.
 * Back never does: a selection is not a place, and the soft keyboard consumes
 * its own first Back anyway. Back does one thing Esc does not — from any page
 * but the builder, it goes to the builder before it leaves the app.
 */
enum class Unwind {
    UPDATE_DIALOG,
    OVERLAY,
    TOP,
    COVER_PICKER,
    GOAL,
    DRAFT,
    FOCUS,
    ISOLATION,
    SELECTION,
    IMMERSIVE,
    TO_BUILDER,
}

/** What is open, as the chain reads it. */
data class BackFlags(
    val updateDialog: Boolean = false,
    val overlay: Boolean = false,
    /** Anything `dismissTop` closes: a menu, the viewer, the palette, a confirm, help, a drawer, the search pop-out. */
    val top: Boolean = false,
    val coverPicker: Boolean = false,
    val goal: Boolean = false,
    val draft: Boolean = false,
    val focus: Boolean = false,
    val isolation: Boolean = false,
    val selection: Boolean = false,
    val immersive: Boolean = false,
    val offBuilder: Boolean = false,
)

object BackChain {

    private val ESC = listOf(
        Unwind.UPDATE_DIALOG, Unwind.OVERLAY, Unwind.TOP, Unwind.COVER_PICKER, Unwind.GOAL, Unwind.DRAFT,
        Unwind.FOCUS, Unwind.ISOLATION, Unwind.SELECTION, Unwind.IMMERSIVE,
    )

    private val BACK = listOf(
        Unwind.UPDATE_DIALOG, Unwind.OVERLAY, Unwind.TOP, Unwind.COVER_PICKER, Unwind.GOAL, Unwind.DRAFT,
        Unwind.IMMERSIVE, Unwind.TO_BUILDER,
    )

    /** What Esc closes next, or null when there is nothing. */
    fun esc(flags: BackFlags): Unwind? = ESC.firstOrNull { flags.has(it) }

    /** What Back closes next, or null — then the system's own back (home, with its predictive preview) is right. */
    fun back(flags: BackFlags): Unwind? = BACK.firstOrNull { flags.has(it) }

    private fun BackFlags.has(step: Unwind): Boolean = when (step) {
        Unwind.UPDATE_DIALOG -> updateDialog
        Unwind.OVERLAY -> overlay
        Unwind.TOP -> top
        Unwind.COVER_PICKER -> coverPicker
        Unwind.GOAL -> goal
        Unwind.DRAFT -> draft
        Unwind.FOCUS -> focus
        Unwind.ISOLATION -> isolation
        Unwind.SELECTION -> selection
        Unwind.IMMERSIVE -> immersive
        Unwind.TO_BUILDER -> offBuilder
    }
}
