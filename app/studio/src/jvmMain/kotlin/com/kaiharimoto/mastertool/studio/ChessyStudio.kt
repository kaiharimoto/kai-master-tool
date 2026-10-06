package com.kaiharimoto.mastertool.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFaces
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFrame
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMoods
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyMouth
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyRig
import com.kaiharimoto.neue.ai.chessy.ChessyAssets
import com.kaiharimoto.neue.ai.chessy.ChessyAvatar
import com.kaiharimoto.neue.ai.chessy.drawChessy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `tools/shoot.sh --chessy` (Chessy, Phase 2): her pictures through the app's own renderer, no app around them.
 * A sheet: each face at rest, turned each way, talking open and shut, blinking and with her hair swung — then the
 * live avatar at the sizes the app draws it (the bar's 28, the composer's 44, the greeting's 120).
 */
fun chessyShot(map: Map<String, String>, out: File, name: String) {
    if (map["chessy"] == "moods") return chessyMoods(map, out, name)
    val assets = runBlocking { ChessyAssets.load() } ?: error("Chessy's pack did not load")
    // a pose: a rig stepped toward an aim for a while, then a mouth, a blink or a swing set by hand
    fun pose(ax: Float, ay: Float, mouth: ChessyMouth = ChessyMouth.OWN, blink: Boolean = false, swing: Float = 0f): ChessyFrame {
        val rig = ChessyRig(seed = 2)
        repeat(160) { rig.step(16f, ax, ay, talking = false, blinks = false) }
        return rig.frame.also { f ->
            f.mouth = mouth
            f.blink = blink
            if (swing != 0f) for (i in f.swingX.indices) f.swingX[i] = if (i >= 4) swing * .01f else swing
            f.rimMix = if (swing != 0f) 1f else 0f
            f.bob = 0f
        }
    }
    val poses = listOf(
        pose(0f, 0f), pose(-1f, -.6f), pose(1f, .6f),
        pose(0f, 0f, ChessyMouth.OPEN), pose(0f, 0f, ChessyMouth.CLOSED), pose(0f, 0f, blink = true), pose(.4f, 0f, swing = 30f),
    )
    val faces = (map["chessy"]?.takeIf { it != "true" }?.split(",")) ?: listOf("grin", "fangs", "tongue")
    // a whole sheet face, as a mood that wears it
    fun face(f: String) = when (f) {
        ChessyFaces.FANGS -> ChessyMoods.of(Expression.FOUND)
        ChessyFaces.TONGUE -> ChessyMoods.of(Expression.LOVE)
        else -> ChessyMoods.of(Expression.WORKING)
    }
    val cell = (map["cell"] ?: "240").toInt()
    val width = poses.size * cell + 40
    val height = faces.size * (cell * 1740 / 1320) + 260
    runBlocking(Dispatchers.Swing) {
        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (face in faces) Row {
                    for (f in poses) Canvas(Modifier.size(cell.dp, (cell * 1740 / 1320).dp)) { drawChessy(assets, f, face(face)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                    for (s in listOf(28, 44, 120, 200)) Box { ChessyAvatar(Expression.IDLE, s.dp) }
                    ChessyAvatar(Expression.SPEAKING, 120.dp, talking = true)
                }
            }
        }
        try {
            var nanos = 0L
            repeat((map["settle"] ?: "90").toInt()) { scene.render(nanos); nanos += 16_666_667L }
            val png = scene.render(nanos).encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
            File(out, "$name.png").writeBytes(png.bytes)
            println("[neue-studio] chessy: ${faces.size} faces × ${poses.size} poses → ${File(out, "$name.png")}")
        } finally {
            scene.close()
        }
    }
}

/**
 * `tools/shoot.sh --chessy=moods` (Phase 3): all twenty of Ai's moods as Chessy wears them, live, each named — the
 * review sheet. `--frames=N --every=K` writes N pictures K frames apart (`<name>-000.png` …) for a moving sheet.
 */
private fun chessyMoods(map: Map<String, String>, out: File, name: String) {
    runBlocking { ChessyAssets.load() } ?: error("Chessy's pack did not load")
    val cell = (map["cell"] ?: "220").toInt()
    val cols = 5
    val rows = (Expression.entries.size + cols - 1) / cols
    val width = cols * (cell + 16) + 24
    val height = rows * (cell + 40) + 24
    val frames = (map["frames"] ?: "1").toInt()
    val every = (map["every"] ?: "4").toInt()
    runBlocking(Dispatchers.Swing) {
        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
                for (r in 0 until rows) Row {
                    for (c in 0 until cols) {
                        val e = Expression.entries.getOrNull(r * cols + c)
                        if (e != null) Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ChessyAvatar(e, cell.dp, talking = e == Expression.SPEAKING)
                            BasicText("${e.title}  ${e.kaomoji}", style = TextStyle(fontSize = 13.sp))
                        }
                    }
                }
            }
        }
        try {
            var nanos = 0L
            repeat((map["settle"] ?: "90").toInt()) { scene.render(nanos); nanos += 16_666_667L }
            for (i in 0 until frames) {
                val png = scene.render(nanos).encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
                val file = if (frames == 1) File(out, "$name.png") else File(out, "$name-${i.toString().padStart(3, '0')}.png")
                file.writeBytes(png.bytes)
                repeat(every) { nanos += 16_666_667L; scene.render(nanos) }
            }
            println("[neue-studio] chessy moods: ${Expression.entries.size} moods, $frames frame(s) → $out")
        } finally {
            scene.close()
        }
    }
}
