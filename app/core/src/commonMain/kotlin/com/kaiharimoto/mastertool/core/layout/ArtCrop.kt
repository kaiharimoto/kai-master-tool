package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The picture itself, inside an [ArtFrame]'s bevel: where a person's own art
 * goes when they crop a picture into a card (kai, 1.0.34: "the rest of the card
 * is the same on the original, we are only replacing the card art").
 *
 * Fractions of the card's width and height, like the frame. Measured off the
 * same renders:
 *
 * - **Standard** — the bevel's inside, a square.
 * - **Pendulum** — the bevel's inside down to the top of the pendulum-effect
 *   box (row 736 of 1185). The art runs on behind that box, but the box is
 *   translucent and its text is printed on it, so the new art stops at its
 *   bevel, where the seam is a line the card already has.
 * - **Link** — the standard square with its corners cut: the link-arrow
 *   triangle at each corner reaches [cornerLeg] along both edges into the
 *   picture. The arrows at the edges' middles sit on the bevel, outside it.
 */
data class ArtWindow(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** How far a corner's cut runs along each edge, as a fraction of the card's width; 0 for square corners. */
    val cornerLeg: Float = 0f,
) {
    /** Width over height, in pixels, on a card drawn [cardWidth] × [cardHeight]. */
    fun aspect(cardWidth: Float, cardHeight: Float): Float =
        ((right - left) * cardWidth) / ((bottom - top) * cardHeight)

    /**
     * The window's outline in pixels, clockwise from the top left: four corners,
     * or eight points where the corners are cut.
     */
    fun outline(cardWidth: Float, cardHeight: Float): List<Pair<Float, Float>> {
        val l = left * cardWidth
        val t = top * cardHeight
        val r = right * cardWidth
        val b = bottom * cardHeight
        val k = cornerLeg * cardWidth
        if (k <= 0f) return listOf(l to t, r to t, r to b, l to b)
        return listOf(
            l + k to t, r - k to t,
            r to t + k, r to b - k,
            r - k to b, l + k to b,
            l to b - k, l to t + k,
        )
    }

    companion object {
        private const val W = 813f
        private const val H = 1185f

        /** Where the pendulum-effect box's bevel begins. */
        private const val PENDULUM_BOX = 736f

        /**
         * A link socket's diagonal edge: the pixels whose offsets from the frame's
         * outer corner add up to less than this are the socket's (the dark
         * triangle and its light outline).
         */
        private const val LINK_SOCKET = 41f

        /** The window inside [frame]. */
        fun of(frame: ArtFrame): ArtWindow {
            val bx = frame.bevel
            val by = frame.bevel * W / H
            val bottom = if (frame == ArtFrame.PENDULUM) PENDULUM_BOX / H else frame.bottom - by
            // The cut line x + y = socket from the outer corner meets the window's edge
            // this far along it, the bevel having taken the rest on each axis.
            val leg = if (frame.interrupted) (LINK_SOCKET / W - 2 * bx).coerceAtLeast(0f) else 0f
            return ArtWindow(frame.left + bx, frame.top + by, frame.right - bx, bottom, leg)
        }
    }
}

/** A crop of a picture, in the picture's own pixels. */
data class CropBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/** A corner of a [CropBox], for a handle dragged to resize it. */
enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/** How a picture is fitted into a view: scaled by [scale], its origin at ([dx], [dy]). */
data class ViewFit(val scale: Float, val dx: Float, val dy: Float) {
    fun toView(x: Float, y: Float): Pair<Float, Float> = (dx + x * scale) to (dy + y * scale)
    fun toImage(x: Float, y: Float): Pair<Float, Float> = ((x - dx) / scale) to ((y - dy) / scale)
}

/**
 * The arithmetic of cropping a picture to an art window: a box of the window's
 * [aspect] that stays inside the picture however it is moved or resized, so
 * what is kept is always all picture — never a margin the card would show.
 */
object ArtCrop {
    /** The smallest crop, in picture pixels: a handful of pixels stretched over an art box is only blur. */
    const val MIN_WIDTH = 24f

    /** The largest crop of [aspect] the picture holds, in its middle: where every crop starts. */
    fun initial(imageWidth: Float, imageHeight: Float, aspect: Float): CropBox {
        val w = min(imageWidth, imageHeight * aspect)
        val h = w / aspect
        return CropBox((imageWidth - w) / 2f, (imageHeight - h) / 2f, w, h)
    }

    /** [box] moved by ([dx], [dy]), stopping at the picture's edges. */
    fun moved(box: CropBox, dx: Float, dy: Float, imageWidth: Float, imageHeight: Float): CropBox =
        box.copy(
            x = (box.x + dx).coerceIn(0f, max(0f, imageWidth - box.width)),
            y = (box.y + dy).coerceIn(0f, max(0f, imageHeight - box.height)),
        )

    /**
     * [box] grown by [factor] (a wheel, a pinch) about ([anchorX], [anchorY]),
     * which stays where it is on the picture, as far as the edges allow.
     */
    fun scaled(box: CropBox, factor: Float, anchorX: Float, anchorY: Float, imageWidth: Float, imageHeight: Float): CropBox {
        val aspect = box.width / box.height
        val largest = initial(imageWidth, imageHeight, aspect).width
        val w = (box.width * factor).coerceIn(min(MIN_WIDTH, largest), largest)
        val k = w / box.width
        val grown = CropBox(anchorX - (anchorX - box.x) * k, anchorY - (anchorY - box.y) * k, w, w / aspect)
        return moved(grown, 0f, 0f, imageWidth, imageHeight)
    }

    /**
     * [box] with [corner] dragged to ([px], [py]): the opposite corner stays put,
     * the window's shape is kept, and the box never leaves the picture.
     */
    fun resized(box: CropBox, corner: CropCorner, px: Float, py: Float, imageWidth: Float, imageHeight: Float): CropBox {
        val aspect = box.width / box.height
        val right = corner == CropCorner.TOP_RIGHT || corner == CropCorner.BOTTOM_RIGHT
        val down = corner == CropCorner.BOTTOM_LEFT || corner == CropCorner.BOTTOM_RIGHT
        // The corner that stays.
        val fx = if (right) box.x else box.right
        val fy = if (down) box.y else box.bottom
        val roomW = if (right) imageWidth - fx else fx
        val roomH = if (down) imageHeight - fy else fy
        val most = min(roomW, roomH * aspect)
        val wanted = max(abs(px - fx), abs(py - fy) * aspect)
        val w = wanted.coerceIn(min(MIN_WIDTH, most), most)
        val h = w / aspect
        return CropBox(if (right) fx else fx - w, if (down) fy else fy - h, w, h)
    }

    /** The picture fitted whole into a [viewWidth] × [viewHeight] view, centred. */
    fun fit(imageWidth: Float, imageHeight: Float, viewWidth: Float, viewHeight: Float): ViewFit {
        val s = min(viewWidth / imageWidth, viewHeight / imageHeight)
        return ViewFit(s, (viewWidth - imageWidth * s) / 2f, (viewHeight - imageHeight * s) / 2f)
    }

    /** Which corner of [box] (in view pixels, through [fit]) is within [reach] of ([x], [y]), if any. */
    fun cornerAt(box: CropBox, fit: ViewFit, x: Float, y: Float, reach: Float): CropCorner? =
        CropCorner.entries.firstOrNull { corner ->
            val (cx, cy) = when (corner) {
                CropCorner.TOP_LEFT -> fit.toView(box.x, box.y)
                CropCorner.TOP_RIGHT -> fit.toView(box.right, box.y)
                CropCorner.BOTTOM_RIGHT -> fit.toView(box.right, box.bottom)
                CropCorner.BOTTOM_LEFT -> fit.toView(box.x, box.bottom)
            }
            abs(cx - x) <= reach && abs(cy - y) <= reach
        }
}
