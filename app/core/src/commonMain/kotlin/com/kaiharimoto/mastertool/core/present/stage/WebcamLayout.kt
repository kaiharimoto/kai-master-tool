package com.kaiharimoto.mastertool.core.present.stage

import com.kaiharimoto.mastertool.core.present.Presentation
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** A rectangle in canvas units. */
@Serializable
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
    val area: Float get() = max(0f, w) * max(0f, h)

    fun inset(by: Float): Box = Box(x + by, y + by, max(0f, w - 2 * by), max(0f, h - 2 * by))

    fun contains(px: Float, py: Float): Boolean = px >= x && px <= right && py >= y && py <= bottom

    fun intersects(o: Box): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    fun intersection(o: Box): Box? {
        val l = max(x, o.x)
        val t = max(y, o.y)
        val r = min(right, o.right)
        val b = min(bottom, o.bottom)
        return if (r > l && b > t) Box(l, t, r - l, b - t) else null
    }

    fun union(o: Box): Box {
        val l = min(x, o.x)
        val t = min(y, o.y)
        return Box(l, t, max(right, o.right) - l, max(bottom, o.bottom) - t)
    }

    /** The largest box of [aspect] (width over height) centred in this one. */
    fun fitted(aspect: Float): Box {
        if (aspect <= 0f || w <= 0f || h <= 0f) return this
        val fw = min(w, h * aspect)
        val fh = fw / aspect
        return Box(x + (w - fw) / 2f, y + (h - fh) / 2f, fw, fh)
    }

    fun lerp(o: Box, t: Float): Box = Box(
        x + (o.x - x) * t, y + (o.y - y) * t, w + (o.w - w) * t, h + (o.h - h) * t,
    )

    companion object {
        val CANVAS = Box(0f, 0f, Presentation.WIDTH, Presentation.HEIGHT)

        fun around(boxes: List<Box>): Box? = boxes.reduceOrNull { a, b -> a.union(b) }
    }
}

/**
 * Where the creator's camera stands (kai, 1.0.70: "if a webcam option is on, the presentation
 * will allocate a zone for the webcam and build around it"). A corner, a column down one
 * side, or a box of the creator's own, in one of four shapes.
 */
@Serializable
data class WebcamZone(
    val enabled: Boolean = false,
    /** [TOP_LEFT], [TOP_RIGHT], [BOTTOM_LEFT], [BOTTOM_RIGHT], [LEFT_COLUMN], [RIGHT_COLUMN], [CUSTOM]. */
    val preset: String = BOTTOM_RIGHT,
    /** [SIZE_S], [SIZE_M], [SIZE_L]. */
    val size: String = SIZE_M,
    /** The zone when [preset] is [CUSTOM]. */
    val box: Box? = null,
    /** [SHAPE_RECT], [SHAPE_ROUNDED], [SHAPE_CIRCLE], [SHAPE_PILL]. */
    val shape: String = SHAPE_ROUNDED,
    /** A border in a theme colour, or none. */
    val border: String? = "@accent",
    val borderWidth: Float = 6f,
    /** The picture mirrored, as people expect to see themselves. */
    val mirror: Boolean = true,
    /** What stands in the zone before the camera is live: [FILL_NONE], [FILL_THEME], [FILL_CHROMA]. */
    val fill: String = FILL_THEME,
    /**
     * Which camera, by its name — read by nothing (1.1.13): a presentation travels between computers, which name their
     * cameras differently, so the camera is chosen per computer (`NeuePreferences.record`, `RecordPrefs.camera`).
     */
    val device: String? = null,
) {
    companion object {
        const val TOP_LEFT = "TOP_LEFT"
        const val TOP_RIGHT = "TOP_RIGHT"
        const val BOTTOM_LEFT = "BOTTOM_LEFT"
        const val BOTTOM_RIGHT = "BOTTOM_RIGHT"
        const val LEFT_COLUMN = "LEFT_COLUMN"
        const val RIGHT_COLUMN = "RIGHT_COLUMN"
        const val CUSTOM = "CUSTOM"
        val PRESETS = listOf(TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT_COLUMN, RIGHT_COLUMN, CUSTOM)

        const val SIZE_S = "S"
        const val SIZE_M = "M"
        const val SIZE_L = "L"

        const val SHAPE_RECT = "RECT"
        const val SHAPE_ROUNDED = "ROUNDED"
        const val SHAPE_CIRCLE = "CIRCLE"
        const val SHAPE_PILL = "PILL"
        val SHAPES = listOf(SHAPE_RECT, SHAPE_ROUNDED, SHAPE_CIRCLE, SHAPE_PILL)

        const val FILL_NONE = "NONE"
        const val FILL_THEME = "THEME"
        const val FILL_CHROMA = "CHROMA"

        /** Green for keying the camera in a recorder of the creator's own. */
        const val CHROMA = "#00B140"

        fun presetName(preset: String): String = when (preset) {
            TOP_LEFT -> "Top left"
            TOP_RIGHT -> "Top right"
            BOTTOM_LEFT -> "Bottom left"
            LEFT_COLUMN -> "Left column"
            RIGHT_COLUMN -> "Right column"
            CUSTOM -> "Custom"
            else -> "Bottom right"
        }

        fun shapeName(shape: String): String = when (shape) {
            SHAPE_RECT -> "Square corners"
            SHAPE_CIRCLE -> "Circle"
            SHAPE_PILL -> "Pill"
            else -> "Rounded"
        }
    }
}

/**
 * The webcam's zone and the room it leaves. Pure arithmetic, so the editor, the presenter
 * and the recorder agree to the pixel.
 */
object WebcamLayout {
    /** The margin kept round the canvas: titles and cards stay inside it. */
    const val SAFE = 48f

    /** Room between the zone and what is laid out beside it. */
    const val GAP = 32f

    /** The safe area of the canvas. */
    val safe: Box get() = Box.CANVAS.inset(SAFE)

    /**
     * The zone on the canvas, or null when the camera is off or hidden on this slide. A slide whose
     * [camera] is `CUSTOM` stands it in [slideBox], its own; without one, in the presentation's box.
     */
    fun zone(z: WebcamZone, camera: String = "DEFAULT", slideBox: Box? = null): Box? {
        if (!z.enabled || camera == "HIDDEN") return null
        val preset = if (camera != "DEFAULT" && camera in WebcamZone.PRESETS) camera else z.preset
        val canvas = Box.CANVAS
        if (preset == WebcamZone.CUSTOM) {
            val b = (if (camera == WebcamZone.CUSTOM) slideBox else null) ?: z.box ?: return zone(z.copy(preset = WebcamZone.BOTTOM_RIGHT))
            val w = b.w.coerceIn(120f, canvas.w)
            val h = b.h.coerceIn(120f, canvas.h)
            return Box(b.x.coerceIn(0f, canvas.w - w), b.y.coerceIn(0f, canvas.h - h), w, h)
        }
        val width = when (z.size) {
            WebcamZone.SIZE_S -> 384f
            WebcamZone.SIZE_L -> 640f
            else -> 512f
        }
        val square = z.shape == WebcamZone.SHAPE_CIRCLE
        val w = if (square) width * 0.62f else width
        val h = if (square) w else width * 9f / 16f
        val m = SAFE / 2f
        return when (preset) {
            WebcamZone.TOP_LEFT -> Box(m, m, w, h)
            WebcamZone.TOP_RIGHT -> Box(canvas.w - m - w, m, w, h)
            WebcamZone.BOTTOM_LEFT -> Box(m, canvas.h - m - h, w, h)
            WebcamZone.LEFT_COLUMN, WebcamZone.RIGHT_COLUMN -> {
                val cw = when (z.size) {
                    WebcamZone.SIZE_S -> canvas.w * 0.22f
                    WebcamZone.SIZE_L -> canvas.w * 0.34f
                    else -> canvas.w * 0.28f
                }
                // Inset like the corners, so a border and its rounding show on every side.
                val x = if (preset == WebcamZone.LEFT_COLUMN) m else canvas.w - cw - m
                Box(x, m, cw, canvas.h - 2 * m)
            }
            else -> Box(canvas.w - m - w, canvas.h - m - h, w, h)
        }
    }

    /**
     * The room for content beside [zone]: of the four bands round it — left, right, above,
     * below, each within the safe area — the one where content of [aspect] (width over
     * height) would be drawn largest; for [aspect] null, the largest band. A deck passes its
     * shape, so a wide deck takes the band above a corner camera rather than the narrow one
     * beside it. With no zone, the safe area.
     */
    fun stage(zone: Box?, aspect: Float? = null, area: Box = safe): Box {
        if (zone == null || !zone.intersects(area)) return area
        val bands = bands(zone, area)
        if (bands.isEmpty()) return area
        return if (aspect == null || aspect <= 0f) {
            bands.maxBy { it.area }
        } else {
            bands.maxWith(compareBy<Box> { it.fitted(aspect).area }.thenBy { it.area })
        }
    }

    /**
     * The four bands round [zone] inside [area] — left, right, above, below — that are large
     * enough to hold anything; the whole [area] when there is no zone.
     */
    fun bands(zone: Box?, area: Box = safe): List<Box> {
        if (zone == null || !zone.intersects(area)) return listOf(area)
        return listOf(
            Box(area.x, area.y, zone.x - GAP - area.x, area.h),
            Box(zone.right + GAP, area.y, area.right - zone.right - GAP, area.h),
            Box(area.x, area.y, area.w, zone.y - GAP - area.y),
            Box(area.x, zone.bottom + GAP, area.w, area.bottom - zone.bottom - GAP),
        ).filter { it.w > 80f && it.h > 80f }.ifEmpty { listOf(area) }
    }

    /** The stage of [slideCamera] on [p]'s zone, for content of [aspect]. */
    fun stageOf(p: Presentation, slideCamera: String, aspect: Float? = null): Box =
        stage(zone(p.webcam, slideCamera), aspect)
}
