package com.kaiharimoto.mastertool.core.layout

/**
 * Which way round the window is, and therefore which builder gets drawn.
 *
 * ## One rule, no thresholds
 *
 * A window taller than it is wide is [TALL]; everything else is [WIDE]. That is
 * the whole test, and it is deliberately not a width threshold: a width
 * threshold has to be chosen, defended and re-chosen every time a device with a
 * new dp box turns up, and it gets the foldables wrong in both directions. The
 * aspect ratio is the thing the layout actually cares about, because the two
 * arrangements differ in what they put *beside* what.
 *
 * - [WIDE] is the tablet builder: the pool down the left, the deck down the
 *   right, side by side, because there is width to divide.
 * - [TALL] is the phone builder: the deck above, the pool docked along the
 *   bottom under the thumbs, because there is not.
 *
 * A portrait tablet gets [TALL] too, and that is correct rather than
 * incidental — a 924×1480 window has the same problem a phone has, and the
 * thumbs are in the same place.
 *
 * ## What it is measured in
 *
 * Density-independent pixels, like everything else compared against a size in
 * this app (`docs/classic/DEVICES.md` §2). It happens not to matter for a ratio, since
 * the density cancels, but taking pixels here would be the one place in the
 * layout where a number means something different on a phone — and that is
 * exactly the habit that put a pixel floor in `BoardLayouter`.
 */
enum class Posture {
    WIDE,
    TALL;

    val isTall: Boolean get() = this == TALL

    companion object {
        fun of(widthDp: Float, heightDp: Float): Posture =
            if (heightDp > widthDp) TALL else WIDE
    }
}
