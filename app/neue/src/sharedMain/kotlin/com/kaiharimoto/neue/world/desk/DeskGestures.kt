package com.kaiharimoto.neue.world.desk

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.kaiharimoto.mastertool.core.input.DeskTouch
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.byFinger

/*
 * The desk's pointer grammar (`docs/world/DESKTOP.md` §9.2–§9.3, `WorldMouse`/`WorldTouch` in core): one handler per
 * target that tells a click from a double-click, a middle-click and a drag, for a mouse and a finger alike. Presses are
 * found by any button (`CardPointer.awaitAnyDown`'s rule: Compose's `awaitFirstDown` hears only the primary); a
 * right-click or a held finger is the kit's `onContextMenu`, worn beside this.
 */

/** A drag the desk follows: [start] once past the slop, [move] by each step, [end] where it was let go (local px). */
internal interface DeskDrag {
    fun start(local: Offset)
    fun move(delta: Offset, local: Offset)
    fun end(local: Offset)
    fun cancel() = Unit
}

/**
 * Click, double-click, middle-click and drag on one target. [onPress] hears every primary press as it goes down (a
 * window comes to the front, nothing spent). A finger's tap is a tap only when it lifted inside the hold and the slop
 * (`DeskTouch.isTap`'s rule), so a resting thumb fires nothing. [fingerDrags] false leaves a finger's drag to what is
 * under (a list's scroll).
 */
internal fun Modifier.deskPointer(
    key: Any?,
    onTap: ((finger: Boolean) -> Unit)? = null,
    onDouble: (() -> Unit)? = null,
    onMiddle: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    drag: DeskDrag? = null,
    fingerDrags: Boolean = true,
): Modifier = pointerInput(key) {
    var lastTap = 0L
    var lastAt = Offset.Zero
    awaitEachGesture {
        var down: PointerInputChange? = null
        var primary = false
        var middle = false
        while (down == null) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press && e.changes.isNotEmpty() && e.changes.all { it.changedToDown() }) {
                val c = e.changes[0]
                primary = c.byFinger || e.buttons.isPrimaryPressed
                middle = !c.byFinger && e.buttons.isTertiaryPressed
                down = c
            }
        }
        val first = down
        if (!primary && !middle) return@awaitEachGesture
        if (primary) onPress?.invoke()
        val finger = first.byFinger
        val slop = viewConfiguration.touchSlop
        val canDrag = primary && drag != null && (!finger || fingerDrags)
        var travelled = Offset.Zero
        var dragging = false
        var up: PointerInputChange? = null
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull { it.id == first.id } ?: break
            val d = c.positionChange()
            travelled += d
            if (!dragging && canDrag && travelled.getDistance() > slop) {
                dragging = true
                drag?.start(first.position)
            }
            if (dragging) {
                c.consume()
                if (!c.pressed) {
                    drag?.end(c.position)
                    return@awaitEachGesture
                }
                drag?.move(d, c.position)
                continue
            }
            if (!c.pressed) {
                up = c
                break
            }
        }
        if (dragging) {
            drag?.cancel()
            return@awaitEachGesture
        }
        val lifted = up ?: return@awaitEachGesture
        if (lifted.isConsumed || travelled.getDistance() > slop) return@awaitEachGesture
        if (finger && lifted.uptimeMillis - first.uptimeMillis > DeskTouch.holdMs(viewConfiguration.longPressTimeoutMillis)) return@awaitEachGesture
        if (middle) {
            onMiddle?.invoke()
            return@awaitEachGesture
        }
        val t = lifted.uptimeMillis
        if (onDouble != null && t - lastTap < viewConfiguration.doubleTapTimeoutMillis && (lifted.position - lastAt).getDistance() < slop * 2) {
            lastTap = 0L
            onDouble()
        } else {
            lastTap = t
            lastAt = lifted.position
            onTap?.invoke(finger)
        }
    }
}

/** Any press inside, heard before the children (a window's body, the desktop): never spent. */
internal fun Modifier.onAnyPress(key: Any?, onPress: () -> Unit): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.type == PointerEventType.Press) onPress()
        }
    }
}

/** Reports where this element is to the avatar, as [anchor] of [app] (`AvatarTargets.report`, from layout, never composition). */
internal fun Modifier.deskTarget(h: NeueHolders, app: String?, anchor: Anchor, key: String? = null): Modifier =
    onGloballyPositioned { c -> h.world.desk.report(app, anchor, c, key) }

/** A rectangle in window pixels as dp on the World page. */
internal fun WorldDeskState.rectOf(c: LayoutCoordinates): DeskRect {
    val b = c.boundsInWindow()
    val d = density
    return DeskRect(((b.left - origin.x) / d).toDouble(), ((b.top - origin.y) / d).toDouble(), (b.width / d).toDouble(), (b.height / d).toDouble())
}

internal fun WorldDeskState.report(app: String?, anchor: Anchor, c: LayoutCoordinates, key: String? = null) {
    if (!c.isAttached) return
    avatar.report(app, anchor, rectOf(c), key)
}
