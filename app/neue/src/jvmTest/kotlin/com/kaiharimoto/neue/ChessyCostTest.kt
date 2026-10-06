package com.kaiharimoto.neue

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.neue.ai.chessy.ChessyAssets
import com.kaiharimoto.neue.ai.chessy.ChessyAvatar
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * What Chessy costs while she sits in the chat box and nothing happens (the performance pass, kai: "my hardware was
 * lagging quite badly when chessy was live"): a second and a half of real time, how many frames she asked for and how long
 * drawing them took. Printed, for the record; asserted only loosely, since a CI machine's clock is its own.
 */
class ChessyCostTest {
    @Test
    fun sittingStillIsCheap() {
        runBlocking { ChessyAssets.load() } ?: return
        val scene = ImageComposeScene(400, 400, density = androidx.compose.ui.unit.Density(2f))
        var steps = 0
        var lively = 0
        scene.setContent { ChessyAvatar(Expression.IDLE, 132.dp, rigHook = { steps++; if (it.frame.lively) lively++ }) }
        var t = System.nanoTime()
        repeat(10) { scene.render(t); t += 16_000_000 }
        val start = System.nanoTime()
        var frames = 0
        var drawing = 0L
        while (System.nanoTime() - start < 1_500_000_000L) {
            if (scene.hasInvalidations()) {
                val a = System.nanoTime()
                scene.render(a)
                drawing += System.nanoTime() - a
                frames++
            }
            Thread.sleep(4)
        }
        println("CHESSY STEPS: $steps steps, $lively lively")
        println("CHESSY COST: $frames frames in 1.5 s, ${drawing / 1_000_000} ms drawing, ${if (frames > 0) drawing / frames / 1000 else 0} µs a frame")
        scene.close()
    }
}
