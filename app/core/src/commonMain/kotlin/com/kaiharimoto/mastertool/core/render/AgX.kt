package com.kaiharimoto.mastertool.core.render

import kotlin.math.log2
import kotlin.math.pow

/**
 * The film the Desk scenes are exposed on.
 *
 * Every other tone decision in this package is about one surface. This is about
 * the whole picture, once, after everything has been drawn — it is the *stock*,
 * not the paint, and it belongs beside [Tone] because it answers the question
 * [Tone] cannot: what happens to light past the top of the range.
 *
 * ## Why a tonemap at all, when this codebase refused one
 *
 * The refusal in `docs/classic/FIDELITY.md` was correct and it was about **ACES**. A
 * filmic curve that crushes the bottom of the range is a catastrophe on a stage
 * whose whole identity is true black and whose shadow edges, pile sides and felt
 * fall-off all live in the bottom hundredth. Measured at a linear 0.002 — a
 * shadow edge — plain sRGB gives code 6.16 and ACES gives **1.63**, which is the
 * edge disappearing.
 *
 * AgX gives **6.56**. It is within half a code of the identity through the
 * bottom of the range because it spends its accuracy at the *top*: a log2
 * encoding over about sixteen and a half stops, a sigmoid across it, and an
 * inset/outset pair that keeps a channel from clipping to a primary on its way
 * out. What it buys is the thing this stage has never had — somewhere for light
 * above 1.0 to go — which is what makes an exposure meaningful at all.
 *
 * ## True black is preserved by construction, not by luck
 *
 * `log2(0)` is negative infinity, [MIN_EV] clamps it, the normalisation sends
 * that to zero and [CONTRAST] evaluates to **−0.00232** there, which clamps to
 * zero. So a pixel that was `#000000` comes back `#000000` at every exposure.
 * That is the one property this app cannot trade, and it is the reason the
 * clamp is written before the divide rather than after it.
 *
 * ## The numbers it ships at
 *
 * [EXPOSURE_EV] of 1.5, measured against the stage's own codes rather than
 * chosen:
 *
 * | on the stage | plain | AgX at +1.5 EV |
 * |---|---|---|
 * | true black | 0 | **0** |
 * | felt, code 10 | 10 | 22 |
 * | desk, code 45 | 45 | 87 |
 * | wall, code 62 | 62 | 114 |
 * | a card's white bar, code 230 | 230 | 226 |
 *
 * The room comes up by about a stop and the cards do not move, which is exactly
 * the complaint: the desk scenes were a lit table in a room that had fallen off
 * the bottom of the range.
 *
 * ## And the grade that was planned and is not here
 *
 * `docs/classic/PHOTOREAL.md` asked for **+2 EV with a post-power of 1.2**, which is the
 * "punchy" finish every implementation of this ships some form of. Measured, it
 * is the ACES mistake in miniature. A power above one applies to the *display*
 * value, so it costs most exactly where the value is smallest: at a linear 0.002
 * — a shadow edge, a pile side — plain sRGB is code 6.59, AgX as published is
 * **6.56**, and AgX to the 1.2 is **3.15**. Half the bottom of the range, on the
 * stage whose identity is that range.
 *
 * So the power is one and the exposure carries the whole grade instead, and
 * +1.5 EV lands within three codes of every midtone that plan wanted. It costs
 * four codes at the top — a card's white bar at 226 rather than 230 — and that
 * is the trade, stated rather than hidden. [LOOK_POWER] stays as a named
 * constant at 1.0 so the next person to reach for it reads this first.
 *
 * ## One definition, two languages
 *
 * [sksl] emits the same arithmetic as [tonemap] from the same constants, which
 * is `docs/classic/PHOTOREAL.md` §0's answer to the one structural hole in this
 * project's fidelity discipline: shader text is unreachable from `commonTest`
 * on either platform, so a shader is the only lighting in the app with no test
 * under it. A generator does not fix that in general. It fixes it for anything
 * generated, because [AgXTest] tests the Kotlin and the shader cannot say
 * something else.
 */
object AgX {

    /** The bottom of the log2 window, in stops. Below it everything is black. */
    const val MIN_EV = -12.47393f

    /** And the top, which is a little over sixteen stops of range. */
    const val MAX_EV = 4.026069f

    /** How much light the Desk scenes are given, in stops. See the class note. */
    const val EXPOSURE_EV = 1.5f

    /**
     * The contrast the picture is finished on, as a power over the display value.
     *
     * **One, and it is measured rather than left at the default.** See the class
     * note: a power above one costs most where the display value is smallest, so
     * 1.2 halves a shadow edge — 6.56 to 3.15 at a linear 0.002 — which is the
     * one thing this stage cannot spend. The grade is carried by [EXPOSURE_EV].
     */
    const val LOOK_POWER = 1f

    /**
     * The inset, row-major.
     *
     * A rotation toward the middle of the gamut before the curve, so that a
     * channel driven hard desaturates toward white instead of clipping to a
     * primary. It is what stops a bright saturated thing turning into a flat
     * shape of one colour, and it is the half of AgX that is a *look* rather
     * than a transfer.
     */
    val INSET = floatArrayOf(
        0.842479062253094f, 0.0784335999999992f, 0.0792237451477643f,
        0.0423282422610123f, 0.878468636469772f, 0.0791661274605434f,
        0.0423756549057051f, 0.0784336f, 0.879142973793104f,
    )

    /** And its inverse, applied after the curve. */
    val OUTSET = floatArrayOf(
        1.19687900512017f, -0.0980208811401368f, -0.0990297440797205f,
        -0.0528968517574562f, 1.15190312990417f, -0.0989611768448433f,
        -0.0529716355144438f, -0.0980434501171241f, 1.15107367264116f,
    )

    /**
     * The sigmoid, as a sixth-order polynomial in ascending order.
     *
     * A fit rather than the curve itself, and the fit is the published one. Its
     * value at zero is the constant term, which is why black survives: it is
     * negative, so the clamp that follows has something to clamp.
     */
    val CONTRAST = floatArrayOf(-0.00232f, 0.1191f, 0.4298f, -6.868f, 31.96f, -40.14f, 15.5f)

    /**
     * One linear colour, exposed and graded, as a display value in 0..1.
     *
     * [exposure] is a linear gain, not stops — the caller has usually already
     * multiplied something else into it.
     */
    fun tonemap(r: Float, g: Float, b: Float, exposure: Float = stops(EXPOSURE_EV)): FloatArray {
        val v = floatArrayOf(r * exposure, g * exposure, b * exposure)
        val inset = apply(INSET, v)
        for (i in 0..2) {
            val ev = log2(inset[i].coerceAtLeast(FLOOR)).coerceIn(MIN_EV, MAX_EV)
            inset[i] = curve((ev - MIN_EV) / (MAX_EV - MIN_EV))
        }
        val out = apply(OUTSET, inset)
        for (i in 0..2) {
            out[i] = out[i].coerceIn(0f, 1f).pow(LOOK_POWER)
        }
        return out
    }

    /** Stops as a linear gain. */
    fun stops(ev: Float): Float = 2f.pow(ev)

    /**
     * The same arithmetic as [tonemap], as an SkSL function named `agx`.
     *
     * Every literal in it comes from the constants above, so the shader and the
     * test cannot disagree. It takes and returns a `float3`, and the exposure is
     * the caller's — a lens has a vignette to apply between the two.
     */
    fun sksl(): String = """
        float3 agx(float3 v) {
            v = float3(
                ${row(INSET, 0)},
                ${row(INSET, 1)},
                ${row(INSET, 2)});
            v = clamp(log2(max(v, float3($FLOOR))), float3($MIN_EV), float3($MAX_EV));
            v = (v - float3($MIN_EV)) / float3(${MAX_EV - MIN_EV});
            v = ${poly()};
            v = float3(
                ${row(OUTSET, 0)},
                ${row(OUTSET, 1)},
                ${row(OUTSET, 2)});
            return pow(clamp(v, 0.0, 1.0), float3($LOOK_POWER));
        }
    """.trimIndent()

    /**
     * The smallest linear value the log is taken of.
     *
     * Not an epsilon picked to avoid a crash: it is below [MIN_EV]'s own
     * threshold — 2^-12.47 is 1.7e-4 — so every value it replaces was going to
     * be clamped to the bottom anyway, and the clamp is what black relies on.
     */
    private const val FLOOR = 1e-10f

    private fun row(m: FloatArray, i: Int) =
        "${m[i * 3]} * v.r + ${m[i * 3 + 1]} * v.g + ${m[i * 3 + 2]} * v.b"

    /** Horner, ascending, so the emitted expression is one line per coefficient. */
    private fun poly(): String {
        var out = "float3(${CONTRAST.last()})"
        for (i in CONTRAST.size - 2 downTo 0) {
            out = "($out * v + float3(${CONTRAST[i]}))"
        }
        return out
    }

    private fun curve(x: Float): Float {
        var out = CONTRAST.last()
        for (i in CONTRAST.size - 2 downTo 0) out = out * x + CONTRAST[i]
        return out
    }

    private fun apply(m: FloatArray, v: FloatArray) = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )
}
