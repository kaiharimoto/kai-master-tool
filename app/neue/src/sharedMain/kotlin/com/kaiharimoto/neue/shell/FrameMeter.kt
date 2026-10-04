package com.kaiharimoto.neue.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.motion.FrameStats
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.theme.Mu

/**
 * The frame meter (1.0.92), from the palette's "Show frame times": how fast the window is drawing, read off the frame
 * clock — frames a second, the typical frame, the slow one in twenty and the worst of the last few seconds. Ink on paper
 * in the corner, written twice a second. It asks for every frame while it is shown (a meter has to), so it is for
 * looking at the app, never left on; nothing about it is stored.
 */
@Composable
fun FrameMeter(modifier: Modifier = Modifier) {
    val c = Mu.colors
    val stats = remember { FrameStats() }
    var line by remember { mutableStateOf("—") }
    LaunchedEffect(stats) {
        var written = 0L
        while (true) {
            withFrameNanos { now ->
                stats.feed(now)
                if (now - written >= 500_000_000L) {
                    written = now
                    stats.reading()?.let { r ->
                        line = "${r.fps.toInt()} fps · ${ms(r.medianMs)} · p95 ${ms(r.p95Ms)} · worst ${ms(r.worstMs)}"
                    }
                }
            }
        }
    }
    Box(modifier.background(c.paper).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Mono(line, color = c.ink)
    }
}

private fun ms(v: Float): String {
    val tenths = (v * 10f + 0.5f).toInt()
    return "${tenths / 10}.${tenths % 10} ms"
}
