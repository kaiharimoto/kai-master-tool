package com.kaiharimoto.mastertool.core.render

import kotlin.math.abs

/**
 * How much bite a card loses for being far away.
 *
 * ## It is not depth of field and it is not pretending to be
 *
 * There is no blur on this stage and there is not going to be one:
 * `BlurEffect` is API 31 against a `minSdk` of 26 and degrades to a silent
 * no-op below it, and a `renderEffect` per card is the one shape of change
 * `docs/classic/PHOTOREAL.md` measured and named fatal.
 *
 * A blur would also buy less than it costs. The board's whole depth span is 55
 * mat pixels at the reading seat and 425 at the seated one, so a
 * physically-tuned circle of confusion is **zero pixels overhead and under
 * three seated**, on a card 102 pixels wide.
 *
 * What defocus destroys first is micro-contrast, long before sharpness, and at
 * three pixels of blur that is the whole of the visible effect. So this returns
 * a *contrast* falloff, applied as one more rounded rectangle over a card the
 * renderer already draws two or three of. Zero layers, zero render passes, and
 * it reaches API 26.
 *
 * ## Depth is measured against the board, and that took two goes
 *
 * `StagePlane.project` reports a depth in the same units as its
 * `cameraDistance`, and the ratio between them is the only scale-free thing
 * available at a card's draw site. So that is what arrives here — and then it is
 * divided by `StagePlane.depthReach`, the board's own half-depth in the same
 * units, so that ±1 on the dial is the far corner and the near one.
 *
 * **The second division is the whole of why this shipped switched off and
 * looking broken.** Without it the focus plane travelled ±0.5 of the camera
 * distance while the board occupied ±0.027 overhead, ±0.124 at the table seat
 * and ±0.209 seated: between three quarters and nineteen twentieths of the
 * knob's travel moved a plane already past every card on the table, and the
 * usable part was too short to find. The falloff had the same fault from the
 * other end — the number quoted at f/8 put the far edge at nine per cent of the
 * dial at the table seat and one per cent overhead, so there was no aperture at
 * which the far edge of the board was visibly softer than the near one.
 *
 * Both are now solved against the projection rather than chosen: the plane
 * spans the reach, and the ramp is calibrated so [WIDE_F] just saturates at the
 * far corner. `DefocusTest` measures both at every seat.
 *
 * ## What it will and will not look like
 *
 * The far half of the board loses its bite: whites come toward the felt, the
 * black frame lines around distant art go grey, and the board reads as
 * receding. Every edge stays razor sharp. There is no feathering of a near card
 * over what is behind it and there is no bokeh, because both of those need a
 * layer, which is the thing this exists to avoid.
 */
object Defocus {

    /**
     * How far from the focus plane a card may be before anything happens, as a
     * **fraction of the board's own depth**.
     *
     * A dead zone rather than a curve through zero, because the alternative is
     * every card on the table being fractionally hazed — which is not a shallow
     * depth of field, it is a dirty screen.
     *
     * It used to be 0.012 of the *camera distance*, which is a tenth of the
     * board at the table seat and nearly half of it overhead — so the same
     * constant meant "a hair" at one seat and "most of the table" at another.
     * A twentieth of the reach is a hair at every seat, which is what a dead
     * zone is for.
     */
    const val GATE = 0.05f

    /**
     * The aperture at which the far edge of the board just saturates.
     *
     * The calibration, and the whole of what the aperture dial means: **at f/2 a
     * card at the far corner gives up all of [hazeAt]'s strength, and every
     * longer stop is proportionally gentler.** So f/4 reaches half, f/8 — the
     * shipped stop — a quarter, and f/22 a twelfth. Depth of field going as the
     * reciprocal of the f-number is not a metaphor here; it is what an f-number
     * is.
     *
     * Quoted at the *widest* stop rather than at the shipped one on purpose. A
     * gradient pinned at f/8 has to be read off some far-edge fraction somebody
     * chose, and the number that got chosen (3.2 per unit of camera distance)
     * put the far edge at nine per cent of the dial at the table seat and one
     * per cent overhead — a dial with no setting at which it did anything. Pin
     * the end of the range instead and every stop in front of it follows.
     */
    const val WIDE_F = 2f

    /** The stop the dial ships at, and a deep one. Quarter of the bite of [WIDE_F]. */
    const val REFERENCE_F = 8f

    /**
     * The haze over something, as an alpha in 0..1.
     *
     * ## Everything here is measured against the board, not against the lens
     *
     * [depth] and [cameraDistance] arrive in the projection's own units, and the
     * first thing this does is divide out [reach] — the board's own half-depth,
     * from `StagePlane.depthReach`. So the focus plane travels from the far
     * corner to the near one across the dial's whole travel, and it means the
     * same thing at every seat: at the reading seat the board is a twenty-seventh
     * of the camera distance deep and at the POV chair it is a third, and the
     * knob should not be a different instrument at each.
     *
     * That is the fix. The old version compared against the camera distance
     * directly, with a plane reach of a half, which put every card inside the
     * first eighth of the dial and the rest of it past the end of the table.
     *
     * @param depth the thing's depth, from `StagePlane.project`.
     * @param cameraDistance the plane's own, which is what [depth] is measured
     *   against. Zero or less means there is no camera yet — the first
     *   composition of the play screen builds exactly such a plane — and the
     *   honest answer there is none rather than a division.
     * @param reach the board's own half-depth in the same units,
     *   `StagePlane.depthReach`. Zero or less means a stage with no depth at
     *   all, which is a table seen flat on and has nothing to defocus.
     * @param focus where the sharp plane sits: −1 at the far corner of the
     *   board, 0 at what the camera is aimed at, +1 at the near corner.
     * @param fNumber the aperture. Smaller is shallower; [WIDE_F] saturates at
     *   the far corner.
     * @param strength the alpha at full defocus. Zero switches the whole thing
     *   off, and is the default everywhere.
     */
    fun hazeAt(
        depth: Float,
        cameraDistance: Float,
        reach: Float,
        focus: Float,
        fNumber: Float,
        strength: Float,
    ): Float {
        if (strength <= 0f || cameraDistance <= 0f || reach <= 0f) return 0f
        if (!depth.isFinite() || !focus.isFinite() || !strength.isFinite()) return 0f
        if (!reach.isFinite()) return 0f

        // In units of the board's own half-depth, which is the one scale-free
        // pair of numbers available where a card is drawn *and* the only one
        // that means the same thing at every seat.
        val at = depth / cameraDistance / reach
        val plane = focus.coerceIn(-1f, 1f)
        val away = abs(at - plane)
        if (away <= GATE) return 0f

        val stop = if (fNumber.isFinite() && fNumber > 0.1f) fNumber else REFERENCE_F
        // One at the far corner at the widest stop, and reciprocal in the
        // f-number from there. `1 - GATE` rather than `1` because the dead zone
        // is spent before the ramp starts, and a ramp that ignored it would
        // arrive at the corner a gate short of saturating.
        val gradient = (WIDE_F / stop) / (1f - GATE)
        return ((away - GATE) * gradient).coerceIn(0f, 1f) * strength.coerceIn(0f, 1f)
    }
}
