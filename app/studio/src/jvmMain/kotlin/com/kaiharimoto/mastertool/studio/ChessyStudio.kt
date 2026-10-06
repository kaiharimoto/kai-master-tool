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
 * live avatar at a few sizes (below 104 dp, `ChessySizes.MIN`, the app shows her ears mark instead).
 */
fun chessyShot(map: Map<String, String>, out: File, name: String) {
    if (map["chessy"] == "moods") return chessyMoods(map, out, name)
    if (map["chessy"] == "reel") return chessyReel(map, out, name)
    if (map["chessy"] == "eyes") return chessyEyes(map, out, name)
    val assets = runBlocking { ChessyAssets.load() } ?: error("Chessy's pack did not load")
    // a pose: a rig stepped toward an aim for a while, then a mouth, a blink or a swing set by hand
    fun pose(ax: Float, ay: Float, mouth: ChessyMouth = ChessyMouth.OWN, blink: Boolean = false, swing: Float = 0f): ChessyFrame {
        val rig = ChessyRig(seed = 2)
        repeat(160) { rig.step(16f, ax, ay, talking = false, blinks = false) }
        return rig.frame.also { f ->
            f.mouth = mouth
            f.blink = blink
            f.blinkOpen = if (blink) 0f else 1f
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
    // --density=2: drawn at twice the pixels, as a 2x display would (the review page's tiles)
    val density = (map["density"] ?: "1").toFloat()
    // --only=speaking,wink: those moods alone, in that order (a big sheet of all twenty runs out of memory)
    val moods = map["only"]?.split(",")?.mapNotNull { Expression.byId(it.trim()) } ?: Expression.entries
    val cols = minOf(5, moods.size)
    val rows = (moods.size + cols - 1) / cols
    val width = ((cols * (cell + 16) + 24) * density).toInt()
    val height = ((rows * (cell + 40) + 24) * density).toInt()
    val frames = (map["frames"] ?: "1").toInt()
    val every = (map["every"] ?: "4").toInt()
    runBlocking(Dispatchers.Swing) {
        // --transparent=true: no paper and no names, for pictures laid over something else (the takeover storyboard);
        // --ai-faces=true: Ai's own faces in the same grid
        val clear = map["transparent"] == "true"
        val ai = map["ai-faces"] == "true"
        val scene = ImageComposeScene(width, height, Density(density), coroutineContext = coroutineContext) {
            Column(Modifier.fillMaxSize().background(if (clear) Color.Transparent else Color.White).padding(12.dp)) {
                for (r in 0 until rows) Row {
                    for (c in 0 until cols) {
                        val e = moods.getOrNull(r * cols + c)
                        if (e != null) Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (ai) com.kaiharimoto.neue.ai.avatar.AiAvatar(e, cell.dp) else ChessyAvatar(e, cell.dp, talking = e == Expression.SPEAKING)
                            BasicText(if (clear) " " else "${e.title}  ${e.kaomoji}", style = TextStyle(fontSize = 13.sp))
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

/**
 * `tools/shoot.sh --chessy=reel` (the rig red team): one scripted twelve seconds of her, live and paced in real time
 * (so her calm steps are taken as the app takes them), written as `<name>-000.png …` at 30 a second. The same script
 * runs on any build, so a reel before a change and one after it are the same moments side by side.
 *
 * 0–2.5 s left alone; 2.5–4 the pointer sweeps across her, then jumps back; angry, surprised, shy; 7.5–10 she says a
 * reply streamed at reading speed (a code block in the middle); delighted, with a hop; asleep. Her bell is flicked at 3 s.
 */
private fun chessyReel(map: Map<String, String>, out: File, name: String) {
    runBlocking { ChessyAssets.load() } ?: error("Chessy's pack did not load")
    val size = (map["cell"] ?: "360").toInt()
    val density = (map["density"] ?: "1").toFloat()
    val w = size * 2
    val h = (size * 1.5f).toInt()
    val seconds = (map["seconds"] ?: "12").toFloat()
    val reply = "Nya~ so you want to side out Ash Blossom, huh? Fine, fine. Here is the plan:\n```cards\nGhost Belle\nDroll & Lock Bird\n```\n" +
        "Bring the Belles in on the play, and keep two Ash for the mirror. Trust me, nya. "
    var clock = 0f
    val mood = androidx.compose.runtime.mutableStateOf(Expression.IDLE)
    val talking = androidx.compose.runtime.mutableStateOf(false)
    var flicked = false
    fun pointer(): androidx.compose.ui.geometry.Offset? = when {
        clock < 2.5f -> null
        clock < 4f -> androidx.compose.ui.geometry.Offset(w * (clock - 2.5f) / 1.5f, h * .35f)
        clock < 7.5f -> androidx.compose.ui.geometry.Offset(w * .1f, h * .4f)
        else -> androidx.compose.ui.geometry.Offset(w * .5f, h * .9f)
    }
    fun spoken(): String = if (clock < 7.5f) "" else reply.take(((clock - 7.5f) * 60f).toInt())
    runBlocking(Dispatchers.Swing) {
        val scene = ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density), coroutineContext = coroutineContext) {
            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                ChessyAvatar(
                    mood.value, size.dp, talking = talking.value, pointer = { pointer() },
                    rigHook = { rig -> if (!flicked && clock >= 3f) { rig.ring(1.2f); flicked = true } },
                    spoken = { spoken() },
                )
            }
        }
        try {
            val start = System.nanoTime()
            var nanos = 0L
            val frames = (seconds * 60).toInt()
            for (i in 0 until frames) {
                clock = nanos / 1e9f
                mood.value = when {
                    clock < 4.5f -> Expression.IDLE
                    clock < 5.5f -> Expression.ANGRY
                    clock < 6.5f -> Expression.SURPRISED
                    clock < 7.5f -> Expression.SHY
                    clock < 10f -> Expression.SPEAKING
                    clock < 11f -> Expression.DELIGHTED
                    else -> Expression.SLEEPING
                }
                talking.value = clock in 7.5f..10f
                // in real time: her calm steps wait on the clock, as they do in the app
                val due = start + nanos
                val wait = (due - System.nanoTime()) / 1_000_000
                // and always yield the thread: rendering is slower than real time, and her frame loop's calm wait (a
                // timer on this same thread) would otherwise never be let back in
                if (wait > 0) kotlinx.coroutines.delay(wait) else kotlinx.coroutines.yield()
                val img = scene.render(nanos)
                if (i % 2 == 0) {
                    val png = img.encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
                    File(out, "$name-${(i / 2).toString().padStart(3, '0')}.png").writeBytes(png.bytes)
                }
                nanos += 16_666_667L
            }
            println("[neue-studio] chessy reel: ${frames / 2} frames → $out")
        } finally {
            scene.close()
        }
    }
}

/**
 * `tools/shoot.sh --chessy=eyes` (round two of the rig red team): her half-lids through the app's renderer, her head
 * large, each open eye (the Grin's sly, the Fangs' wide) at openings 1, 0.75, 0.5, 0.25 and shut; then the moods that
 * wear a squint or a sleepy lid, live.
 */
private fun chessyEyes(map: Map<String, String>, out: File, name: String) {
    val assets = runBlocking { ChessyAssets.load() } ?: error("Chessy's pack did not load")
    val cell = (map["cell"] ?: "300").toInt()
    val opens = listOf(1f, .75f, .5f, .25f, 0f)
    val kinds = listOf(
        com.kaiharimoto.mastertool.core.ai.chessy.ChessyEye.SLY to com.kaiharimoto.mastertool.core.ai.chessy.ChessyLips.SMILE,
        com.kaiharimoto.mastertool.core.ai.chessy.ChessyEye.WIDE to com.kaiharimoto.mastertool.core.ai.chessy.ChessyLips.SMILE,
    )
    val moods = listOf(Expression.THINKING, Expression.ANGRY, Expression.WAKING, Expression.WINK, Expression.SAD)
    val width = opens.size * (cell + 8) + 40
    val height = (kinds.size + 1) * (cell + 30) + 40
    runBlocking(Dispatchers.Swing) {
        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
            Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((eye, lips) in kinds) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in opens) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val mood = com.kaiharimoto.mastertool.core.ai.chessy.ChessyMood(
                            eye, eye, lips, if (eye == com.kaiharimoto.mastertool.core.ai.chessy.ChessyEye.WIDE) ChessyFaces.FANGS else ChessyFaces.GRIN,
                            openL = o, openR = o,
                        )
                        Canvas(Modifier.size(cell.dp)) { drawChessy(assets, ChessyFrame(), mood, head = true) }
                        BasicText("${eye.name.lowercase()} $o", style = TextStyle(fontSize = 12.sp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (e in moods) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(cell.dp)) { ChessyAvatar(e, cell.dp, still = true) }
                        BasicText(e.title, style = TextStyle(fontSize = 12.sp))
                    }
                }
            }
        }
        try {
            var nanos = 0L
            repeat((map["settle"] ?: "60").toInt()) { scene.render(nanos); nanos += 16_666_667L }
            val png = scene.render(nanos).encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
            File(out, "$name.png").writeBytes(png.bytes)
            println("[neue-studio] chessy eyes → ${File(out, "$name.png")}")
        } finally {
            scene.close()
        }
    }
}
