package com.kaiharimoto.mastertool.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.Density
import com.kaiharimoto.mastertool.core.motion.Vec2
import com.kaiharimoto.mastertool.core.render.Homography
import com.kaiharimoto.mastertool.ui.gpu.brush
import com.kaiharimoto.mastertool.ui.gpu.compileStageEffect
import com.kaiharimoto.mastertool.ui.gpu.compileStageShader
import com.kaiharimoto.mastertool.ui.gpu.effect
import com.kaiharimoto.mastertool.ui.gpu.runtimeShadersAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * The three things the widened seam rests on, asked rather than assumed.
 *
 * `docs/classic/PHOTOREAL.md` §0 names them as spikes because each is a premise several
 * later stages are built on, each is undocumented on at least one backend, and
 * each fails *silently* — a shader that quietly degrades produces a picture, and
 * a picture nobody compared is a premise nobody checked. This is the run that
 * makes them answerable in an afternoon rather than in a signed release.
 *
 * **(a) Does a shader brush survive a projective CTM?** `FeltWeave` has shipped
 * for several releases without ever answering it: it draws inside the mat's own
 * `graphicsLayer`, where the canvas is flat and the projection happens to the
 * layer *afterwards*. A card's surface is different — it is drawn inside
 * `withTransform { transform(homographyMatrix) }`, a genuinely projective map —
 * and the whole of stage 4 assumes `main(float2 p)` receives card-local
 * coordinates with the divide already done. If Skia degrades that to an affine
 * approximation the highlight is wrong in a way that is invisible overhead and
 * obvious from a chair.
 *
 * **(b) Does `RenderEffect` work on both backends?** Proven for `ShaderBrush`,
 * entirely unproven for the effect seam, and the whole photographic pass —
 * vignette, grain, tonemap, bloom — is blocked behind it.
 *
 * **(c) Does a child image shader honour LINEAR sampling at a computed
 * coordinate?** Undocumented on both. If it silently drops to nearest there is
 * no error, just aliasing, and the probe stage would spend a golden re-record
 * discovering it.
 *
 * Each writes a PNG, because the only honest answer to "did it degrade" is a
 * picture somebody looked at. Run with:
 *
 * ```
 * cd app && ./gradlew -Pmastertool.android=true -Pmastertool.studio=true :studio:spikeSeam
 * ```
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull()?.removePrefix("--out=") ?: "build/shots")
    out.mkdirs()

    println("[seam] runtimeShadersAvailable = $runtimeShadersAvailable")

    val checker = requireNotNull(compileStageShader(CHECKER)) { "the checker did not compile" }
    val sampler = requireNotNull(compileStageShader(SAMPLER)) { "the sampler did not compile" }
    val post = compileStageEffect(POST)
    println("[seam] (b) compileStageEffect -> ${if (post == null) "NULL" else "ok"}")

    // A four-by-four black and white tile, sampled far below its own resolution.
    // Nearest and linear are unmistakably different pictures at this size, which
    // is the point: (c) has no error to report, only an appearance.
    val tile = ImageBitmap(4, 4).also { bmp ->
        val px = IntArray(16) { i -> if (((i % 4) + (i / 4)) % 2 == 0) -0x1 else -0x1000000 }
        // `ImageBitmap` has no direct writer in common code; go through a canvas.
        androidx.compose.ui.graphics.Canvas(bmp).also { canvas ->
            val paint = androidx.compose.ui.graphics.Paint()
            for (y in 0 until 4) for (x in 0 until 4) {
                paint.color = Color(px[y * 4 + x])
                canvas.drawRect(
                    left = x.toFloat(), top = y.toFloat(),
                    right = x + 1f, bottom = y + 1f, paint = paint,
                )
            }
        }
    }

    // The projective map (a) is drawn through: a card-shaped rectangle taken to
    // a strongly keystoned quad, built by the same `Homography.rectToQuad` the
    // renderer uses. If the divide survives, the checker's squares converge
    // toward the short edge; if it degrades to affine they stay evenly spaced,
    // and no arithmetic in this file can tell the difference — only the picture.
    val quad = listOf(
        Vec2(200f, 120f), Vec2(1400f, 260f), Vec2(1180f, 820f), Vec2(420f, 700f),
    )
    val map = requireNotNull(Homography.rectToQuad(CARD_W, CARD_H, quad)) { "no map" }
    val values = FloatArray(16).also(map::writeComposeMatrix)

    runBlocking(Dispatchers.Swing) {
        fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
            val scene = ImageComposeScene(
                width = WIDTH, height = HEIGHT,
                density = Density(1f),
                coroutineContext = coroutineContext,
                content = content,
            )
            try {
                val png = scene.render().encodeToData(EncodedImageFormat.PNG)
                    ?: error("Skia declined to encode $name")
                File(out, "$name.png").writeBytes(png.bytes)
                println("[seam] $name.png  ${png.size / 1024} KiB")
            } finally {
                scene.close()
            }
        }

        // (a) — a shader filling a rectangle under a projective transform.
        shoot("seam-a-projective") {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFF101014))
                withTransform({ transform(Matrix(values)) }) {
                    drawRect(
                        brush = checker.brush {
                            float2("uCell", CARD_W / 8f, CARD_H / 11f)
                            colour("uInk", 0.92f, 0.94f, 1f)
                        },
                        size = Size(CARD_W, CARD_H),
                    )
                }
            }
        }

        // (b) — the same picture, read back and inverted by a RenderEffect. An
        // effect that silently does nothing shows the un-inverted picture, which
        // is why the shader inverts rather than tints: "no change" and "wrong
        // change" have to look different.
        shoot("seam-b-effect") {
            val re = post?.effect {
                float2("uRes", WIDTH.toFloat(), HEIGHT.toFloat())
            }
            Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = re }) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFF101014))
                    withTransform({ transform(Matrix(values)) }) {
                        drawRect(
                            brush = checker.brush {
                                float2("uCell", CARD_W / 8f, CARD_H / 11f)
                                colour("uInk", 0.92f, 0.94f, 1f)
                            },
                            size = Size(CARD_W, CARD_H),
                        )
                    }
                }
            }
        }

        // (c) — a 4x4 tile magnified enormously through a child image shader,
        // evaluated at a coordinate the shader computed. Linear gives soft
        // gradients between the sixteen texels; nearest gives sixteen hard
        // squares. There is no third possibility and no error either way.
        shoot("seam-c-sampling") {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFF101014))
                drawRect(
                    brush = sampler.brush {
                        image("uTile", tile)
                        float2("uOrigin", 200f, 120f)
                        float("uScale", 160f)
                    },
                    size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
                )
            }
        }
    }

    println(
        "[seam] look at the three PNGs. (a) squares must converge toward the short edge; " +
            "(b) must be inverted, not merely drawn; (c) must be smooth, not sixteen hard squares."
    )
}

private const val WIDTH = 1600
private const val HEIGHT = 1000
private const val CARD_W = 480f
private const val CARD_H = 700f

/** A checkerboard in the *shader's own* coordinates, so it reports what it was handed. */
private val CHECKER = """
    uniform float2 uCell;
    uniform float4 uInk;

    half4 main(float2 p) {
        float2 c = floor(p / uCell);
        float  on = mod(c.x + c.y, 2.0);
        float3 rgb = mix(float3(0.06, 0.06, 0.08), uInk.rgb, on);
        return half4(half3(rgb), 1.0);
    }
""".trimIndent()

/** A tiny image, magnified, sampled at a coordinate the shader worked out itself. */
private val SAMPLER = """
    uniform shader uTile;
    uniform float2 uOrigin;
    uniform float  uScale;

    half4 main(float2 p) {
        float2 uv = (p - uOrigin) / uScale;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 4.0 || uv.y > 4.0) {
            return half4(0.04, 0.04, 0.05, 1.0);
        }
        // Bitmap *pixel* coordinates on both platforms, not normalised.
        return uTile.eval(uv);
    }
""".trimIndent()

/** Reads what is underneath and inverts it, so "did nothing" cannot pass for "worked". */
private val POST = """
    uniform shader content;
    uniform float2 uRes;

    half4 main(float2 p) {
        half4 src = content.eval(p);
        return half4(half3(1.0) - src.rgb, src.a);
    }
""".trimIndent()
