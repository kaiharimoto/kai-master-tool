package com.kaiharimoto.neue.ai.chessy

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * One of Chessy's pictures drawn as a mesh of triangles bent by her rig: [positions] are the vertices where they land
 * on the canvas (x, y pairs), [texs] where each samples the picture (its own pixels), [colors] each vertex's light
 * (ARGB, white is the picture as painted), [indices] the triangles. [alpha] fades the whole picture.
 *
 * Skia's drawVertices on the desk; Android's Canvas.drawVertices where it is drawn on the GPU (Android 10 up), else
 * the picture flat, moved with its mesh's middle.
 */
internal expect fun DrawScope.drawMesh(
    image: ImageBitmap,
    positions: FloatArray,
    texs: FloatArray,
    colors: IntArray,
    indices: ShortArray,
    vertexCount: Int,
    alpha: Float,
)
