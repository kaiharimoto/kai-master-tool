package com.kaiharimoto.mastertool.core.layout

/**
 * Where the frame around a card's artwork is printed, as fractions of the
 * card's own width and height — so it holds at any size the card is drawn.
 *
 * Measured, not estimated, off YGOPRODeck's card renders (813 × 1185; the
 * small 268 × 391 renders are the same template scaled), by masking the grey
 * bevel that surrounds every picture and reading its edges. Three templates
 * cover every card that has an art box:
 *
 * - **Standard** — every monster, spell, trap and token frame: a square bevel
 *   from (86, 205) to (728, 846), ten pixels wide. Normal, effect, ritual,
 *   fusion, synchro, Xyz, spell and trap renders agree to the pixel.
 * - **Pendulum** — wider, and taller than the picture: on a pendulum card the
 *   art runs on behind the translucent pendulum-effect box, so its bevel wraps
 *   both, from (44, 204) to (769, 887), eight pixels wide. Xyz and effect
 *   pendulums agree.
 * - **Link** — the standard square, with the eight link-arrow sockets printed
 *   over it: a triangle at each corner and an arrow at each edge's middle.
 *   Those are part of the card, so the frame is [interrupted] there.
 *
 * Skill cards are a different image entirely and have no frame here.
 */
data class ArtFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Bevel width, as a fraction of the card's width. */
    val bevel: Float,
    /** Whether the link-arrow sockets interrupt the frame. */
    val interrupted: Boolean = false,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        private const val W = 813f
        private const val H = 1185f

        /**
         * How far the link-arrow sockets reach, as fractions of the card's
         * width: a corner triangle covers this much of both edges from the
         * frame's outer corner, and an edge arrow this much either side of the
         * edge's middle.
         */
        const val LINK_CORNER = 60f / W
        const val LINK_EDGE_HALF = 70f / W

        val STANDARD = ArtFrame(86f / W, 205f / H, 729f / W, 847f / H, bevel = 10f / W)
        val PENDULUM = ArtFrame(44f / W, 204f / H, 769f / W, 887f / H, bevel = 8f / W)
        val LINK = STANDARD.copy(interrupted = true)

        /** The template a YGOPRODeck `frameType` prints with, or null for a card with no art box. */
        fun of(frameType: String): ArtFrame? {
            val type = frameType.lowercase()
            return when {
                type == "skill" -> null
                type.contains("pendulum") -> PENDULUM
                type == "link" -> LINK
                else -> STANDARD
            }
        }
    }
}
