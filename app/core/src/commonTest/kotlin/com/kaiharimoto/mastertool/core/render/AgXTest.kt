package com.kaiharimoto.mastertool.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the film may do to the picture.
 *
 * The one lighting change in this app whose arithmetic runs on a GPU, so this
 * is also the first half of `docs/classic/PHOTOREAL.md` §0's answer to that: the Kotlin
 * is tested here and [AgX.sksl] is generated from the same constants, which is
 * the only form of parity available when the shader text cannot be evaluated
 * from `commonTest` on either platform.
 */
class AgXTest {

    private fun code(c: Int, exposure: Float = AgX.stops(AgX.EXPOSURE_EV)): Float {
        val linear = Tone.toLinear(c / 255f)
        return AgX.tonemap(linear, linear, linear, exposure)[0] * 255f
    }

    @Test
    fun blackComesBackBlackAtEveryExposureThereIs() {
        // The property this app cannot trade, and the reason AgX is allowed in
        // at all where ACES was refused. It is not an approximation that happens
        // to land near zero: `log2(0)` clamps to MIN_EV, the normalisation sends
        // that to zero, and the polynomial's constant term is negative there —
        // so the clamp after it has something to clamp and the answer is exact.
        listOf(-4f, 0f, 2f, 6f, 12f).forEach { ev ->
            val out = AgX.tonemap(0f, 0f, 0f, AgX.stops(ev))
            out.forEach { channel ->
                assertEquals(0f, channel, 0f, "black is not black at $ev EV")
            }
        }
    }

    @Test
    fun theRoomComesUpAStopAndTheCardsStayWhereTheyAre() {
        // The whole point, as the numbers the class KDoc ships. The desk scenes
        // were a lit table in a room that had fallen off the bottom of the
        // range; the room rises by about a stop and a card's white bar does not
        // move, because that is what a shoulder is for.
        assertEquals(22f, code(10), 1f, "the felt")
        assertEquals(87f, code(45), 1.5f, "the desk")
        assertEquals(114f, code(62), 1.5f, "the wall")
        assertEquals(226f, code(230), 2f, "a card's white bar")
    }

    @Test
    fun nothingInTheBottomOfTheRangeIsCrushedTheWayACinematicCurveWouldCrushIt() {
        // The measurement the refusal in `docs/classic/FIDELITY.md` was written from,
        // now stated as the reason the refusal does not extend to this curve.
        // At a linear 0.002 — a shadow edge, a pile side — plain sRGB is code
        // 6.16 and ACES is 1.63. Asked at unity exposure, because the claim is
        // about the curve rather than about how much light is on it.
        // At the curve as published it is 6.56 against 6.59 — three hundredths
        // of a code. The tolerance is half a code rather than a fraction,
        // because what would break this is a *look* being reintroduced on top
        // of the curve, and every one of those costs whole codes here. AgX to
        // the 1.2 — which is what the plan asked for — scores 3.15.
        val shadowEdge = 0.002f
        val plain = Tone.toSrgb(shadowEdge) * 255f
        val agx = AgX.tonemap(shadowEdge, shadowEdge, shadowEdge, 1f)[0] * 255f
        assertTrue(agx > plain - 0.5f, "a shadow edge is crushed: $agx against $plain")
    }

    @Test
    fun moreLightIsNeverLessPicture() {
        // A tonemap that folded back would make a brighter surface darker, which
        // no amount of tuning downstream could recover. Swept across the whole
        // range the stage can produce, at the exposure it ships at.
        var last = -1f
        (0..255).forEach { c ->
            val out = code(c)
            assertTrue(out >= last - 1e-4f, "the curve folds back at code $c: $out after $last")
            last = out
        }
    }

    @Test
    fun theShaderIsWrittenFromTheSameNumbersTheTestJustChecked() {
        // Not a test of the shader — nothing here can run one. It is a test that
        // there is no *second* copy of these numbers to drift: every constant
        // the Kotlin used appears in the generated source, so moving one in one
        // place and not the other is not a thing that can be done.
        val source = AgX.sksl()
        (AgX.INSET.toList() + AgX.OUTSET.toList() + AgX.CONTRAST.toList() +
            listOf(AgX.MIN_EV, AgX.MAX_EV, AgX.LOOK_POWER)).forEach { value ->
            assertTrue(value.toString() in source, "$value is not in the generated shader")
        }
        assertTrue("float3 agx(float3 v)" in source, "the generated source has no agx function")
    }
}
