package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** How one picture moves this frame: what the mockup's shader read as uniforms, resolved per layer. */
class LayerPose {
    /** Its depth in front of the head's surface (its share of the turn). */
    var off = 0f

    /** Moves as one piece with its anchor ([ax], [ay]) instead of bending. */
    var rigid = false
    var ax = 0f
    var ay = 0f

    /** Its swing (sheet px), from [rootStart] down over [rootLen]: tips swing, roots stay. */
    var swingX = 0f
    var swingY = 0f
    var rootStart = -1e5f
    var rootLen = 1f

    /** A turn of its own about ([rcx], [rcy]): an ear's twitch, the bell's swing, the tongue's. */
    var rot = 0f
    var rcx = 0f
    var rcy = 0f

    /** Its share of the breath. */
    var bobShare = .4f

    /** Sized about [sizeX] by [sizeK] and moved down [sizeDy] first (the closed smile, kai's setting). */
    var sizeX = 0f
    var sizeK = 1f
    var sizeDy = 0f

    fun reset(): LayerPose {
        off = 0f; rigid = false; ax = 0f; ay = 0f; swingX = 0f; swingY = 0f; rootStart = -1e5f; rootLen = 1f
        rot = 0f; rcx = 0f; rcy = 0f; bobShare = .4f; sizeX = 0f; sizeK = 1f; sizeDy = 0f
        return this
    }
}

/**
 * Where a point of Chessy's sheet lands this frame, and how it is lit: the mockup's vertex and fragment shaders in
 * plain arithmetic, so a mesh of each picture can be bent on any canvas. The head turns as a sphere (each layer by its
 * depth), tilts about her neck, breathes; hair swings from its roots.
 */
object ChessyWarp {
    const val GROW = 1.18f

    /** The breath's share each layer takes (the head 0.4). */
    val BOB = mapOf("neck" to 1f, "bell" to 1f, "lock-l" to .8f, "lock-r" to .8f, "back" to .6f)
    val GROUP = mapOf(
        "side-l" to SwingGroup.SIDE, "side-r" to SwingGroup.SIDE, "lock-l" to SwingGroup.CURL, "lock-r" to SwingGroup.CURL,
        "back" to SwingGroup.BACK, "bow-lo" to SwingGroup.SIDE, "bow-up" to SwingGroup.SIDE,
    )

    /** The ribbons are clipped to the left side lock: they bend, sit and swing with it. */
    val CLIPPED = mapOf("bow-lo" to "side-l", "bow-up" to "side-l")

    /** [layerId]'s pose this frame ([pic] its picture's box; [face] the face shown, for the tongue's root). */
    fun pose(pack: ChessyPack, layerId: String, pic: Pic?, f: ChessyFrame, face: String, out: LayerPose): LayerPose {
        out.reset()
        val layer = pack.layer(layerId)
        val host = CLIPPED[layerId]?.let { pack.layer(it) }
        out.off = (host ?: layer)?.z ?: 0f
        out.rigid = host == null && layer?.rig == "rigid"
        layer?.anchor?.let { out.ax = it[0]; out.ay = it[1] }
        out.bobShare = BOB[layerId] ?: .4f
        GROUP[layerId]?.let { g ->
            out.swingX = f.swingX[g.ordinal]
            out.swingY = f.swingY[g.ordinal]
            val box = host?.pic ?: pic
            if (box != null) {
                when {
                    host != null || g == SwingGroup.SIDE -> { out.rootStart = box.y + box.h * .22f; out.rootLen = box.h * .78f }
                    g == SwingGroup.CURL -> { out.rootStart = box.y.toFloat(); out.rootLen = box.h.toFloat() }
                    g == SwingGroup.BACK -> { out.rootStart = box.y + box.h * .45f; out.rootLen = box.h * .55f }
                }
            }
        }
        when (layerId) {
            "bell" -> { out.rot = f.swingX[SwingGroup.BELL.ordinal]; out.rcx = out.ax; out.rcy = (pic?.y ?: 0).toFloat() }
            "tongue" -> pack.faces[face]?.tongueRoot?.let { out.rot = f.swingX[SwingGroup.TONGUE.ordinal]; out.rcx = it[0]; out.rcy = it[1] }
            "ear-l" -> { out.rot = -f.earL; out.rcx = out.ax; out.rcy = out.ay }
            "ear-r" -> { out.rot = f.earR; out.rcx = out.ax; out.rcy = out.ay }
        }
        return out
    }

    /** The head's turn moving a point: a sphere turning, the layer's depth adding to it below the eyes. */
    private fun disp(px: Float, py: Float, off: Float, f: ChessyFrame, s: Sphere, out: FloatArray) {
        val qx = (px - s.cx) / (s.rx * GROW)
        val qy = (py - s.cy) / (s.ry * GROW)
        var z = (1f - (qx * qx + qy * qy)).coerceIn(0f, 1f)
        var t = ((py - 400f) / 260f).coerceIn(0f, 1f)
        t = t * t * (3f - 2f * t)
        z *= 1f + off * t
        out[0] = s.rx * sin(f.yaw) * z + (px - s.cx) * (cos(f.yaw) - 1f)
        out[1] = -s.ry * sin(f.pitch) * z + (py - s.cy) * (cos(f.pitch) - 1f)
    }

    private val d = FloatArray(2)

    /** Where sheet point ([x], [y]) of a picture posed [p] lands this frame: written to [out] at [at] and at + 1. */
    fun place(x: Float, y: Float, p: LayerPose, f: ChessyFrame, s: Sphere, neckX: Float, neckY: Float, out: FloatArray, at: Int) {
        // sized and moved (the closed smile), then its own turn
        var px = p.sizeX + (x - p.sizeX) * p.sizeK
        var py = y + p.sizeDy
        if (p.rot != 0f) {
            val dx = px - p.rcx
            val dy = py - p.rcy
            val c = cos(p.rot)
            val sn = sin(p.rot)
            px = p.rcx + dx * c - dy * sn
            py = p.rcy + dx * sn + dy * c
        }
        // hair: tips swing, roots stay
        val w = ((y - p.rootStart) / p.rootLen).coerceIn(0f, 1f)
        px += p.swingX * w * w
        py += p.swingY * w * w
        // the head's turn: a rigid piece moves with its anchor
        if (p.rigid) disp(p.ax, p.ay, p.off, f, s, d) else disp(px, py, p.off, f, s, d)
        var qx = px + d[0]
        var qy = py + d[1]
        // its tilt about the neck, then the breath
        if (f.roll != 0f) {
            val dx = qx - neckX
            val dy = qy - neckY
            val c = cos(f.roll)
            val sn = sin(f.roll)
            qx = neckX + dx * c - dy * sn
            qy = neckY + dx * sn + dy * c
        }
        out[at] = qx
        out[at + 1] = qy + f.bob * p.bobShare
    }

    /**
     * The light at sheet point ([x], [y]): its normal on the head's sphere, turned with her, lit from the upper left;
     * a little darker on the side turned away. 1 is the picture as painted; it stays within about ±8 %.
     */
    fun shade(x: Float, y: Float, f: ChessyFrame, s: Sphere): Float {
        val qx = (x - s.cx) / (s.rx * GROW)
        val qy = (y - s.cy) / (s.ry * GROW)
        val r2 = qx * qx + qy * qy
        var nx = qx
        var ny = qy
        var nz = sqrt(max(0f, 1f - min(r2, 1f))) + .15f
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= len; ny /= len; nz /= len
        val cy = cos(f.yaw)
        val sy = sin(f.yaw)
        val cp = cos(f.pitch)
        val sp = sin(f.pitch)
        val x1 = nx * cy + nz * sy
        val z1 = -nx * sy + nz * cy
        val y2 = ny * cp - z1 * sp
        val z2 = ny * sp + z1 * cp
        val lam = x1 * L[0] + y2 * L[1] + z2 * L[2]
        val rim = (1f - z2.coerceIn(0f, 1f)).pow(2) * (if (x1 >= 0f) 1f else 0f)
        return 1f + .10f * (lam - .62f) - .05f * rim
    }

    private val L = run {
        val v = floatArrayOf(-.45f, -.55f, .70f)
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }
}
