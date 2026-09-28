package com.kaiharimoto.mastertool.ui.play

import androidx.compose.ui.graphics.RenderEffect
import com.kaiharimoto.mastertool.ui.gpu.StageEffect
import com.kaiharimoto.mastertool.ui.gpu.compileStageEffect
import com.kaiharimoto.mastertool.core.render.AgX
import com.kaiharimoto.mastertool.ui.gpu.effect

/**
 * The camera the Desk scenes are seen through.
 *
 * Everything else on this stage colours a *surface*. This colours the picture,
 * once, above everything — which is what a lens does, and why `DESIGN.md` §6
 * had to be widened before it could exist. Three artefacts, and each is there
 * because its absence is what makes a render look rendered:
 *
 * **Vignette**, as `cos⁴` about the true optical axis, in linear. Not a taste
 * dial: every real lens does this and the falloff is the projection's own. At
 * the reference stage the horizontal edge is `t = 800/1450`, which is
 * **0.77 EV**, and the corner is 1.02 — a full stop. That is stronger than a
 * survey would budget and it is what the arithmetic says, so it ships at the
 * arithmetic and [Lens.vignette] is there to argue with rather than to tune to
 * taste.
 *
 * **Grain**, and the envelope is the whole reason it can exist here at all.
 * `σ(u) = σmax · 2√(u(1−u))` is exactly zero at code 0 and at code 255 **by
 * construction**, so it cannot speckle a true black — which uniform-amplitude
 * grain does, and which would end this app's identity in one release. It is
 * also, incidentally, a dither: below code 3 there is nothing left to band, so
 * the pool stops ringing without a blue-noise tile.
 *
 * **Chromatic aberration**, as a *magnification* difference and never a
 * constant offset — a real lens's lateral CA scales from the axis. DXOMark
 * measures it in µm on a 24×36 frame and calls 10 µm "a typical level of
 * acceptability"; [Lens.aberration] at 0.0003 is **0.45 px at the corner of a
 * 2560×1600 panel**, which is under a pixel on purpose. It is deliberately
 * invisible, which is the opposite of `Prismatic.kt`'s fringes — those are a
 * design device that is deliberately *visible*, and letting one become the
 * other would lose both.
 *
 * **Exposure and the film**, which is [AgX] and is the only part of this pass
 * that changes what the picture is *made of* rather than what a lens did to it.
 * It goes last of the light terms and first of the encoding ones: the exposure
 * and the vignette are both facts about how much light reached the film, so
 * both are in linear and both are before the curve; the grain is a fact about
 * the film's own grain, so it is after.
 *
 * It landed here rather than in `Tone` on purpose, and that is a correction to
 * the plan. `docs/classic/PHOTOREAL.md` made AgX wait for `Tone.veil` to die, because it
 * assumed the curve would be applied per surface — where a veil that has to
 * compensate for it goes negative, and a black overlay can only darken. A grade
 * over the finished picture has no such problem: it never meets `Tone` at all.
 * The veil's own error is still real and still owed (`AAA.md` N2); it is a
 * correctness debt rather than a blocker, which is what a measurement is for.
 *
 * ## What it deliberately is not
 *
 * No bloom and no depth of field yet: both want a second pass or a layer per
 * card, and this one is the free one.
 *
 * And **nothing here idles**. The grain is a function of the pixel, not of the
 * clock: it is fixed in the frame like grain on a plate, and a tile that
 * crawled would be the one thing `AAA.md` #60 refuses.
 */
internal object StageLens {

    /** Compiled once per screen; null wherever the platform has no shader. */
    fun compile(): StageEffect? = compileStageEffect(SOURCE)

    /**
     * The graded picture, or null to draw the plain one.
     *
     * @param width the glass, in pixels — passed rather than inferred, because
     *   `content.eval` expects RenderNode-local coordinates on Android and
     *   device space on Skia, and a shader that guesses gets one of them wrong.
     */
    fun effectFor(
        lens: StageEffect?,
        width: Float,
        height: Float,
        focal: Float,
    ): RenderEffect? {
        if (lens == null || width <= 0f || height <= 0f || focal <= 0f) return null
        return lens.effect {
            float2("uRes", width, height)
            // (width/2) / cameraDistance: the tangent of the half-angle the
            // glass subtends, which is what turns a radius into an off-axis
            // angle and therefore what makes the falloff the *lens's* rather
            // than a radial gradient somebody liked.
            float("uTanHalf", (width * 0.5f) / focal)
            float("uVignette", VIGNETTE)
            float("uExposure", AgX.stops(AgX.EXPOSURE_EV))
            float("uGrain", GRAIN)
            float("uAberration", ABERRATION)
        }
    }

    /** Full strength, because `cos⁴` is not a dial. See the class KDoc. */
    private const val VIGNETTE = 1f

    /** Peak sigma, in codes of 255, at mid grey — where the envelope is widest. */
    private const val GRAIN = 2.4f

    /** Magnification difference. 0.45 px at the corner of a Tab S11. */
    private const val ABERRATION = 0.0003f

    private val SOURCE = """
        uniform shader content;
        uniform float2 uRes;
        uniform float  uTanHalf;
        uniform float  uVignette;
        uniform float  uExposure;
        uniform float  uGrain;
        uniform float  uAberration;

${AgX.sksl().prependIndent("        ")}

        // Integer-free, so it is ES2-safe on both backends.
        float hash12(float2 p) {
            float3 q = fract(float3(p.x, p.y, p.x) * float3(0.1031, 0.1030, 0.0973));
            q += dot(q, float3(q.y, q.z, q.x) + 33.33);
            return fract((q.x + q.y) * q.z);
        }

        float toLinear(float c) {
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
        }

        half4 main(float2 p) {
            float2 centre = uRes * 0.5;

            // Lateral CA as a magnification difference about the axis. Green is
            // the reference because it is where the eye's acuity is.
            float2 offset = p - centre;
            float3 rgb = float3(
                content.eval(centre + offset * (1.0 + uAberration)).r,
                content.eval(p).g,
                content.eval(centre + offset * (1.0 - uAberration)).b);

            // Everything that is about light reaching the film, in linear and
            // in one place: the exposure, then cos^4 of the off-axis angle.
            float r = length(offset) / (uRes.x * 0.5);
            float t = r * uTanHalf;
            float q = 1.0 + t * t;
            float fall = mix(1.0, 1.0 / (q * q), uVignette);
            float gain = uExposure * fall;
            float3 lin = float3(toLinear(rgb.r), toLinear(rgb.g), toLinear(rgb.b)) * gain;

            // And the film. `agx` hands back a display value, already carrying
            // the transfer the panel wants, so there is no `toSrgb` after it —
            // adding one would apply the curve twice and wash the whole picture.
            rgb = agx(lin);

            // Grain last, in the space being quantised, under an envelope that
            // is zero at both ends by construction. Two hashes so it is TPDF —
            // one is uniform noise and reads as dirt.
            float lume = dot(rgb, float3(0.2126, 0.7152, 0.0722));
            float sigma = (uGrain / 255.0) * 2.0 * sqrt(max(lume * (1.0 - lume), 0.0));
            rgb += (hash12(p) - hash12(p + 17.31)) * sigma;

            return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);
        }
    """.trimIndent()
}
