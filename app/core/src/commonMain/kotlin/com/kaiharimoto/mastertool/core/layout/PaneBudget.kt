package com.kaiharimoto.mastertool.core.layout

/**
 * How the builder's width is shared between the index, the pool, the deck and
 * the inspector (touch swarm, rec 1).
 *
 * On a 1280dp tablet the desktop's widths — a 232 index forced out, a 440 pool,
 * a 400 inspector and two 7 rules — left the deck **194dp**: cards three
 * millimetres wide under a finger, and with Groups on (a fixed 288 panel in the
 * deck's own row) no deck at all. "Every pixel of chrome is a pixel off every
 * card" (NEUE.md §3) is the rule this restores, on touch, without touching the
 * desk:
 *
 * - the index is a strip of numerals, [TOUCH_RAIL] wide;
 * - the pool and the inspector default to [TOUCH_PANE];
 * - with Groups on, the Groups panel takes the inspector's place — on touch,
 *   reading a card is the hold's job (the viewer);
 * - the deck keeps at least [DECK_FLOOR]: the inspector yields first, then the
 *   pool narrows toward [POOL_MIN].
 *
 * Every width here is **physical**: dp at an interface scale of one. The screen
 * divides by the scale when it lays them out, so the interface scale grows what
 * is inside the panes and never the panes themselves — which is what
 * `NeuePreferences` already says of its widths, and what the code did not do.
 */
object PaneBudget {
    const val DESK_RAIL = 232f
    const val TOUCH_RAIL = 56f
    const val TOUCH_PANE = 320f
    const val RULE = 7f
    const val GROUPS = 288f
    const val DECK_FLOOR = 520f
    const val POOL_MIN = 280f

    data class Panes(
        /** The index when it takes room from the page; zero when it folds away or comes out over it. */
        val rail: Float,
        /** Zero when hidden. */
        val pool: Float,
        /** Zero when hidden, or when it yielded — to the Groups panel, or to the deck's floor. */
        val inspector: Float,
        /** The Groups panel, when it is out; on the desk it stands in the deck's own row. */
        val groups: Float,
        /** The deck's own width: what is left, the Groups panel already taken out. */
        val deck: Float,
        /** The inspector was asked for and gave its room up — the screen should say where it went. */
        val inspectorYielded: Boolean,
    )

    fun solve(
        window: Float,
        touch: Boolean,
        railOut: Boolean,
        groupsOn: Boolean,
        poolVisible: Boolean,
        inspectorVisible: Boolean,
        poolPref: Float,
        inspectorPref: Float,
    ): Panes {
        val rail = if (!railOut) 0f else if (touch) TOUCH_RAIL else DESK_RAIL
        var pool = if (!poolVisible) 0f else if (touch) TOUCH_PANE else poolPref
        var inspector = if (!inspectorVisible) 0f else if (touch) TOUCH_PANE else inspectorPref
        val groups = if (groupsOn) GROUPS else 0f
        var yielded = false
        // On touch the Groups panel takes the inspector's slot.
        if (touch && groupsOn && inspector > 0f) {
            inspector = 0f
            yielded = true
        }
        fun deck() = window - rail - rules(pool, inspector) - pool - inspector - groups
        if (touch) {
            if (deck() < DECK_FLOOR && inspector > 0f) {
                inspector = 0f
                yielded = true
            }
            if (deck() < DECK_FLOOR && pool > POOL_MIN) {
                pool = (pool - (DECK_FLOOR - deck())).coerceAtLeast(POOL_MIN)
            }
        }
        return Panes(rail, pool, inspector, groups, deck().coerceAtLeast(0f), yielded)
    }

    private fun rules(pool: Float, inspector: Float) =
        (if (pool > 0f) RULE else 0f) + (if (inspector > 0f) RULE else 0f)
}
