package com.kaiharimoto.mastertool.core.world.desk

/** The part of the desktop a target is (§5.2): reported by the page from layout as a rectangle, looked up by the avatar. */
enum class Anchor {
    /** The taskbar's Ai cell: where it sleeps. */
    HOME,

    /** An app's desktop icon or its taskbar cell, whichever is in view and nearer: resolved by [AvatarTargets.point]. */
    LAUNCH,

    /** An app's desktop icon. */
    ICON,

    /** An app's taskbar cell: the avatar stands on it. */
    CELL,

    /** A window's title bar. */
    TITLE,

    /** The Editor's caret: the avatar rides 8 dp to its right, on its line, never covering a word written. */
    CARET,

    /** The Terminal's line printing now. */
    LINE,

    /** The Browser's tab strip. */
    TABS,

    /** One tab ([AvatarTarget.key] is its id). */
    TAB,

    /** A page's head. */
    HEAD,

    /** A widget in an app's window ([AvatarTarget.key] is its id). */
    WIDGET,

    /** Thoughts' composer: Ai is waiting on the person. */
    COMPOSER,

    /** A window's body: what Ai reads. */
    BODY,

    /** Wherever the avatar is now (the turn's "Done", said where it stands). */
    HERE,
}

/**
 * One place the avatar goes (§5.2): the [anchor] in [app]'s window (its [AppRef.key]; null for [Anchor.HOME] and
 * [Anchor.HERE]), with [key] for a tab or a widget. [press] squashes the head once on arrival (an icon opened, a tab, a
 * button); [follow] rides a moving point until the next target is due. [status] is the taskbar's line meanwhile.
 */
data class AvatarTarget(
    val anchor: Anchor,
    val app: String? = null,
    val key: String? = null,
    val press: Boolean = false,
    val follow: Boolean = false,
    val status: String = "",
    /** How long it stays at the least, ms; null for the pilot's default. */
    val dwell: Long? = null,
)

/**
 * Where each target is on screen now, reported by the desktop from layout (`AvatarTargets.report`, never from
 * composition) and read by the avatar's loop. A plain map, on the frame thread only: nothing here recomposes.
 */
class AvatarTargets {
    private val rects = HashMap<String, DeskRect>()

    private fun k(app: String?, anchor: Anchor, key: String?) = "${app.orEmpty()}|${anchor.name}|${key.orEmpty()}"

    fun report(app: String?, anchor: Anchor, rect: DeskRect, key: String? = null) {
        rects[k(app, anchor, key)] = rect
    }

    /** [app]'s window and everything in it is gone (closed, or unplaced): its targets with it. Its icon and cell stay. */
    fun forget(app: String) {
        val keep = setOf(Anchor.ICON.name, Anchor.CELL.name)
        rects.keys.removeAll { it.startsWith("$app|") && it.split('|')[1] !in keep }
    }

    /** Unreported: an icon scrolled off, a cell gone. */
    fun drop(app: String?, anchor: Anchor, key: String? = null) {
        rects.remove(k(app, anchor, key))
    }

    fun rect(app: String?, anchor: Anchor, key: String? = null): DeskRect? = rects[k(app, anchor, key)]

    /**
     * Where the avatar's centre goes for [t], from [from] where it is now, at [size] dp; null when the target is not on
     * screen (the pilot then sends it to the window's taskbar cell, or leaves it where it is).
     */
    fun point(t: AvatarTarget, from: DeskPoint, size: Double = 28.0): DeskPoint? {
        if (t.anchor == Anchor.HERE) return from
        if (t.anchor == Anchor.LAUNCH) {
            val icon = rect(t.app, Anchor.ICON)?.let { stand(it, Anchor.ICON, size) }
            val cell = rect(t.app, Anchor.CELL)?.let { stand(it, Anchor.CELL, size) }
            return listOfNotNull(icon, cell).minByOrNull { it.distanceTo(from) }
        }
        val r = rect(t.app, t.anchor, t.key) ?: return null
        return stand(r, t.anchor, size)
    }

    companion object {
        /** The gap between the caret and the avatar. */
        const val CARET_GAP = 8.0

        /** Where an avatar of [size] stands on [r], a rectangle of the [anchor]'s kind. */
        fun stand(r: DeskRect, anchor: Anchor, size: Double): DeskPoint {
            val half = size / 2
            return when (anchor) {
                Anchor.CARET, Anchor.LINE -> DeskPoint(r.right + CARET_GAP + half, r.center.y)
                Anchor.CELL, Anchor.TAB -> DeskPoint(r.center.x, r.y - half + 4)
                Anchor.TITLE, Anchor.TABS -> DeskPoint(r.x + minOf(r.w * 0.3, 120.0), r.y - half + 6)
                Anchor.WIDGET -> DeskPoint(r.right + 4 + half, r.center.y)
                Anchor.HEAD, Anchor.COMPOSER -> DeskPoint(r.x + half, r.y - half + 4)
                Anchor.BODY -> DeskPoint(r.right - size, r.y + size)
                Anchor.HOME, Anchor.ICON, Anchor.LAUNCH, Anchor.HERE -> r.center
            }
        }
    }
}
