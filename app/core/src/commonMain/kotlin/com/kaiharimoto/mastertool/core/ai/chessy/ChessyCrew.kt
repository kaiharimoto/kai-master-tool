package com.kaiharimoto.mastertool.core.ai.chessy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min

/**
 * Chessy the busy one (kai, 2026-10: "a super active assistant, spawning copies to teleport across the screen to point
 * at things, instead of just staying still in the chat box"). While she works, a copy of her blinks in beside what
 * the work touches — the deck she is editing, the pool she is searching, the button she is pressing — looks at it,
 * and says what she is doing in a box. The one in the chat box stays put.
 *
 * This is the arithmetic of it: which named spot each tool points at ([ChessyPoint]), where a copy stands beside its
 * target ([ChessyPlace]), and how long each copy lives ([ChessyCrewPlan]). The app registers the spots by name.
 */
object ChessyPoint {
    /** The spots the app names, so a tool's target and the screen agree on a word. */
    const val DECK = "deck"
    const val POOL = "pool"
    const val INSPECTOR = "inspector"
    const val GROUPS = "groups"
    const val UNDO = "undo"
    const val IMPORT = "import"
    const val EXPORT = "export"
    const val BAR = "bar"

    /** A page's place on the index rail: `page.decks`, `page.format` …. */
    fun page(id: String) = "page.$id"

    /**
     * Where [tool] points, best first: the first spot on screen is the one a copy goes to. Empty when the tool's work
     * has no place on screen (her memory, the web, a calculation) and the copy in the chat box says it alone.
     */
    fun spotsFor(tool: String, input: JsonObject? = null): List<String> = when (tool) {
        "search_cards", "show_in_pool", "resolve_cards" -> listOf(POOL, page("builder"))
        "card_info" -> listOf(INSPECTOR, POOL, page("builder"))
        "edit_deck", "new_deck", "open_deck", "rename_deck", "save_deck", "validate_deck", "get_deck" -> listOf(DECK, page("builder"))
        "set_groups" -> listOf(GROUPS, DECK, page("builder"))
        "undo" -> listOf(UNDO, DECK)
        "import_deck" -> listOf(IMPORT, page("decks"))
        "export_deck" -> listOf(EXPORT, page("decks"))
        "list_decks", "delete_deck" -> listOf(page("decks"))
        "list_webs", "get_web", "create_web", "add_deck_to_web", "set_web_entry", "set_web_notes", "remove_from_web", "delete_web" ->
            listOf(page("format"))
        "get_siding", "set_siding_plan" -> listOf(page("siding"), page("format"))
        "present_state", "present_edit", "present_view" -> listOf(page("present"))
        "navigate" -> listOfNotNull((input?.get("page") as? JsonPrimitive)?.content?.lowercase()?.let(::page))
        "set_setting", "get_settings" -> listOf(page("settings"), BAR)
        "run_action" -> listOf(BAR)
        else -> emptyList()
    }
}

/** A box on the window, in pixels. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2f
    val cy get() = (t + b) / 2f
    fun overlaps(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    fun grown(d: Float) = Box(l - d, t - d, r + d, b + d)
}

/** Which side of its target a copy stands on. */
enum class Side { RIGHT, LEFT, BELOW, ABOVE, OVER }

/** Where a copy stands ([at], the square she is drawn in) and the side of her target she took. */
data class Placed(val at: Box, val side: Side)

object ChessyPlace {
    /**
     * A square of [size] beside [target], [gap] clear of it, inside [window], overlapping none of [taken] (other copies,
     * the chat box) where it can: the side with the most room first. A target that fills the window (the deck, wide)
     * gets her over its corner instead, inside it, so she is never off screen.
     */
    fun place(target: Box, window: Box, size: Float, gap: Float, taken: List<Box> = emptyList()): Placed {
        fun fit(x: Float, y: Float) = Box(
            x.coerceIn(window.l, max(window.l, window.r - size)), y.coerceIn(window.t, max(window.t, window.b - size)),
            0f, 0f,
        ).let { Box(it.l, it.t, it.l + size, it.t + size) }
        val room = mapOf(
            Side.RIGHT to window.r - target.r, Side.LEFT to target.l - window.l,
            Side.BELOW to window.b - target.b, Side.ABOVE to target.t - window.t,
        )
        val candidates = Side.entries.filter { it != Side.OVER }.sortedByDescending { room.getValue(it) }.map { side ->
            val at = when (side) {
                Side.RIGHT -> fit(target.r + gap, target.cy - size / 2f)
                Side.LEFT -> fit(target.l - gap - size, target.cy - size / 2f)
                Side.BELOW -> fit(target.cx - size / 2f, target.b + gap)
                Side.ABOVE -> fit(target.cx - size / 2f, target.t - gap - size)
                Side.OVER -> error("unreachable")
            }
            Placed(at, side)
        }
        val clear = candidates.firstOrNull { p -> room.getValue(p.side) >= size + gap && taken.none { it.overlaps(p.at) } && !p.at.overlaps(target) }
        if (clear != null) return clear
        // inside the target's top-right corner, stepped down past copies already there
        var y = target.t + gap
        var over = fit(target.r - gap - size, y)
        while (taken.any { it.overlaps(over) } && y + size < min(target.b, window.b)) {
            y += size / 2f
            over = fit(target.r - gap - size, y)
        }
        return Placed(over, Side.OVER)
    }
}

/**
 * The copies' lives: one for each tool that ran with a spot on screen, [MAX] at once (the oldest blinks out first),
 * each staying [LINGER_MS] after its tool finished so its last words can be read, [BLINK_MS] to blink in or out.
 */
object ChessyCrewPlan {
    const val MAX = 3
    const val LINGER_MS = 3500L
    const val BLINK_MS = 220L

    /** How much of a copy shows at [now]: 0 to 1 in as she arrives, 1 to 0 as she leaves, given when she [came] and [goes]. */
    fun shown(now: Long, came: Long, goes: Long?): Float {
        val inn = ((now - came).toFloat() / BLINK_MS).coerceIn(0f, 1f)
        val out = if (goes == null) 1f else ((goes - now).toFloat() / BLINK_MS).coerceIn(0f, 1f)
        return min(inn, out)
    }

    /** When a copy whose tool finished at [done] leaves. */
    fun leaves(done: Long): Long = done + LINGER_MS
}
