package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldEvent

/**
 * [Desk.step]: one [DeskOp] at a time, pure. The rules it keeps (`DeskTest`): one window in front or none; a minimised
 * window never in front; at most [Desk.MAX_WINDOWS] open (and [Desk.MAX_APP_WINDOWS] of Ai's apps); windows move only
 * where they were told; the normal frame survives every snap and maximise.
 */
internal object DeskReducer {
    fun step(d: Desk, op: DeskOp, area: DeskArea): DeskStep = when (op) {
        is DeskOp.Open -> open(d, op.app, op.at, op.by, op.behind, op.size, area)
        is DeskOp.Focus -> DeskStep(focus(d, op.app, op.at, op.person))
        DeskOp.Unfocus -> DeskStep(d.copy(front = null))
        is DeskOp.DragStart -> DeskStep(dragStart(d, op, area))
        is DeskOp.Drag -> DeskStep(drag(d, op, area))
        is DeskOp.DragEnd -> DeskStep(dragEnd(d, op, area))
        is DeskOp.Resize -> DeskStep(resize(d, op, area))
        is DeskOp.ToggleMaximise -> DeskStep(toggleMax(d, op.app, op.at))
        is DeskOp.Minimise -> DeskStep(minimise(d, op.app))
        is DeskOp.SnapKey -> DeskStep(snapKey(d, op, area))
        is DeskOp.Close -> DeskStep(close(d, op.app, op.person))
        is DeskOp.Keep -> DeskStep(d.edit(op.app) { it.copy(kept = op.kept) })
        is DeskOp.Cycle -> DeskStep(cycle(d, op.forward, op.at))
        DeskOp.MinimiseAll -> DeskStep(d.copy(windows = d.windows.map { it.copy(minimised = true) }, front = null))
        DeskOp.PutAway -> DeskStep(d.windows.filterNot { it.kept }.fold(d) { acc, w -> close(acc, w.app, person = true) })
        is DeskOp.MoveIcon -> DeskStep(moveIcon(d, op))
        is DeskOp.TurnStart -> DeskStep(d.copy(turn = d.turn + 1, working = true, ai = null, closedThisTurn = emptySet(), facts = TurnFacts()))
        is DeskOp.Arrive -> arrive(d, op, area)
        DeskOp.AiShowed -> DeskStep(d.copy(facts = d.facts.copy(pages = true)))
        is DeskOp.AiMadeApp -> DeskStep(d.copy(facts = d.facts.copy(apps = d.facts.apps + op.slug)))
        is DeskOp.TurnEnd -> DeskStep(DeskTidy.endTurn(d))
        is DeskOp.Tabs -> DeskStep(d.copy(tabs = op.tabs))
    }

    private fun Desk.edit(app: String, f: (DeskWindow) -> DeskWindow): Desk =
        if (window(app) == null) this else copy(windows = windows.map { if (it.app == app) f(it) else it })

    /** [app] to the top of the stack. */
    private fun Desk.lift(app: String): Desk {
        val w = window(app) ?: return this
        return copy(windows = windows.filterNot { it.app == app } + w)
    }

    /** [app] just under the window in front (or on top when nothing is in front). */
    private fun Desk.tuck(app: String): Desk {
        val w = window(app) ?: return this
        val rest = windows.filterNot { it.app == app }
        val f = front?.takeIf { it != app } ?: return copy(windows = rest + w)
        val at = rest.indexOfFirst { it.app == f }.takeIf { it >= 0 } ?: return copy(windows = rest + w)
        return copy(windows = rest.subList(0, at) + w + rest.subList(at, rest.size))
    }

    /** The topmost window that is drawn, other than [except]: who comes to the front when the front one goes. */
    private fun Desk.nextFront(except: String): String? = windows.lastOrNull { !it.minimised && it.app != except }?.app

    private fun open(d: Desk, ref: AppRef, at: Long, by: String, behind: Boolean, size: DeskSize?, area: DeskArea): DeskStep {
        val key = ref.key
        val person = by == WorldEvent.YOU
        val existing = d.window(key)
        if (existing != null) {
            var next = d.edit(key) { it.copy(minimised = false, touched = it.touched || person, used = if (behind) it.used else at) }
            next = if (behind && d.front != null && d.front != key) next.tuck(key) else next.lift(key).copy(front = key)
            return DeskStep(next)
        }
        var desk = d
        val evicted = mutableListOf<String>()
        fun evict(pool: (DeskWindow) -> Boolean) {
            val victim = desk.windows.filter { w ->
                pool(w) && !w.kept && w.app != desk.front && w.app != desk.ai && !(w.by == WorldEvent.AI && desk.working && w.turn == desk.turn)
            }.minByOrNull { it.used } ?: return
            desk = close(desk, victim.app, person = false)
            evicted += victim.app
        }
        if (ref is AppRef.Made && desk.windows.count { it.ref is AppRef.Made } >= Desk.MAX_APP_WINDOWS) evict { it.ref is AppRef.Made }
        if (desk.windows.size >= Desk.MAX_WINDOWS) evict { true }
        val rect = DeskPlacer.place(ref, area, desk.cascade, size)
        val w = DeskWindow(
            app = key,
            frame = area.frame(rect),
            by = by,
            turn = if (by == WorldEvent.AI) desk.turn else 0,
            touched = person,
            used = at,
        )
        desk = desk.copy(windows = desk.windows + w, cascade = desk.cascade + 1)
        desk = if (behind && desk.front != null) desk.tuck(key) else desk.copy(front = key)
        return DeskStep(desk, evicted)
    }

    private fun focus(d: Desk, app: String, at: Long, person: Boolean): Desk {
        val w = d.window(app) ?: return d
        if (w.minimised) return d
        return d.edit(app) { it.copy(used = at, touched = it.touched || person) }.lift(app).copy(front = app)
    }

    private fun dragStart(d: Desk, op: DeskOp.DragStart, area: DeskArea): Desk {
        val w = d.window(op.app) ?: return d
        val focused = focus(d, op.app, op.at, person = true)
        if (w.mode == WindowMode.NORMAL) return focused
        // Off a snap or maximise: back to its normal size, the pointer keeping its place along the title bar.
        val now = w.rect(area)
        val normal = area.rect(w.frame).inside(area.full, DeskPlacer.MIN)
        val along = if (now.w > 0) ((op.pointer.x - now.x) / now.w).coerceIn(0.0, 1.0) else 0.5
        val r = DeskRect(op.pointer.x - along * normal.w, op.pointer.y - TITLE_GRIP, normal.w, normal.h).inside(area.full, DeskPlacer.MIN)
        return focused.edit(op.app) { it.copy(mode = WindowMode.NORMAL, snap = null, frame = area.frame(r)) }
    }

    private fun drag(d: Desk, op: DeskOp.Drag, area: DeskArea): Desk = d.edit(op.app) { w ->
        if (w.mode != WindowMode.NORMAL) return@edit w
        val r = area.rect(w.frame).moved(op.dx, op.dy)
        // Free under the hand, but a grip of the title bar always stays on the desktop.
        val x = r.x.coerceIn(area.full.x - r.w + GRIP, area.full.right - GRIP)
        val y = r.y.coerceIn(area.full.y, area.full.bottom - TITLE_GRIP * 2)
        w.copy(frame = area.frame(r.copy(x = x, y = y)))
    }

    private fun dragEnd(d: Desk, op: DeskOp.DragEnd, area: DeskArea): Desk = d.edit(op.app) { w ->
        when (val z = SnapZones.zone(op.pointer, area.full)) {
            null -> w
            Snap.TOP -> w.copy(mode = WindowMode.MAXIMISED, snap = null)
            else -> w.copy(mode = WindowMode.SNAPPED, snap = z)
        }
    }

    private fun resize(d: Desk, op: DeskOp.Resize, area: DeskArea): Desk = d.edit(op.app) { w ->
        val r0 = w.rect(area)
        var x = r0.x
        var y = r0.y
        var right = r0.right
        var bottom = r0.bottom
        val e = op.edge
        if (e.left) x = (x + op.dx).coerceIn(area.full.x, right - DeskPlacer.MIN.w)
        if (e.right) right = (right + op.dx).coerceIn(x + DeskPlacer.MIN.w, area.full.right)
        if (e.top) y = (y + op.dy).coerceIn(area.full.y, bottom - DeskPlacer.MIN.h)
        if (e.bottom) bottom = (bottom + op.dy).coerceIn(y + DeskPlacer.MIN.h, area.full.bottom)
        w.copy(mode = WindowMode.NORMAL, snap = null, frame = area.frame(DeskRect(x, y, right - x, bottom - y)))
    }

    private fun toggleMax(d: Desk, app: String, at: Long): Desk {
        val w = d.window(app) ?: return d
        val mode = if (w.mode == WindowMode.MAXIMISED) WindowMode.NORMAL else WindowMode.MAXIMISED
        return focus(d.edit(app) { it.copy(mode = mode, snap = null, minimised = false) }, app, at, person = true)
    }

    private fun minimise(d: Desk, app: String): Desk {
        if (d.window(app) == null) return d
        val next = d.edit(app) { it.copy(minimised = true) }
        return if (d.front == app) next.copy(front = next.nextFront(app)) else next
    }

    private fun snapKey(d: Desk, op: DeskOp.SnapKey, area: DeskArea): Desk {
        val w = d.window(op.app) ?: return d
        val next = when (op.direction) {
            DeskOp.Direction.UP -> return toggleMax(d, op.app, op.at)
            DeskOp.Direction.LEFT -> if (w.mode == WindowMode.SNAPPED && w.snap == Snap.RIGHT) w.copy(mode = WindowMode.NORMAL, snap = null) else w.copy(mode = WindowMode.SNAPPED, snap = Snap.LEFT)
            DeskOp.Direction.RIGHT -> if (w.mode == WindowMode.SNAPPED && w.snap == Snap.LEFT) w.copy(mode = WindowMode.NORMAL, snap = null) else w.copy(mode = WindowMode.SNAPPED, snap = Snap.RIGHT)
            DeskOp.Direction.DOWN -> if (w.mode != WindowMode.NORMAL) w.copy(mode = WindowMode.NORMAL, snap = null) else return minimise(d, op.app)
        }
        return focus(d.edit(op.app) { next }, op.app, op.at, person = true)
    }

    fun close(d: Desk, app: String, person: Boolean): Desk {
        if (d.window(app) == null) return d
        val rest = d.copy(windows = d.windows.filterNot { it.app == app })
        val front = if (d.front == app) rest.nextFront(app) else d.front
        val closed = if (person && d.working) d.closedThisTurn + app else d.closedThisTurn
        return rest.copy(front = front, closedThisTurn = closed, ai = if (d.ai == app) null else d.ai)
    }

    private fun cycle(d: Desk, forward: Boolean, at: Long): Desk {
        val order = d.recent
        if (order.size < 2 && d.front != null) return d
        if (order.isEmpty()) return d
        val i = order.indexOfFirst { it.app == d.front }
        val target = when {
            i < 0 -> order.first()
            forward -> order[(i + 1) % order.size]
            else -> order[(i - 1 + order.size) % order.size]
        }
        return focus(d.edit(target.app) { it.copy(minimised = false) }, target.app, at, person = true)
    }

    private fun moveIcon(d: Desk, op: DeskOp.MoveIcon): Desk {
        val col = op.col.coerceAtLeast(0)
        val row = op.row.coerceAtLeast(0)
        val mine = d.icons.firstOrNull { it.app == op.app }
        val there = d.icons.firstOrNull { it.col == col && it.row == row && it.app != op.app }
        val rest = d.icons.filterNot { it.app == op.app || it == there }
        val swapped = if (there != null && mine != null) listOf(there.copy(col = mine.col, row = mine.row)) else listOfNotNull(there?.copy(row = there.row + 1))
        return d.copy(icons = rest + swapped + IconCell(op.app, col, row))
    }

    private fun arrive(d: Desk, op: DeskOp.Arrive, area: DeskArea): DeskStep {
        val key = op.app.key
        val marked = d.copy(ai = key, facts = d.facts.copy(last = key))
        return when (op.decision) {
            FocusDecision.MARK -> DeskStep(marked)
            FocusDecision.RAISE -> open(marked, op.app, op.at, WorldEvent.AI, behind = false, size = op.size, area = area)
            FocusDecision.BEHIND -> open(marked, op.app, op.at, WorldEvent.AI, behind = true, size = op.size, area = area)
        }
    }

    /** How far below the pointer a window's top stands when it comes off a snap: the middle of a 32 dp title bar. */
    private const val TITLE_GRIP = 16.0

    /** How much of a dragged window must stay on the desktop, across. */
    private const val GRIP = 96.0
}
