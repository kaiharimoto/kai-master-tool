package com.kaiharimoto.neue.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.AiDemo
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * "What can you do?" (1.0.46, kai: "teach the user the capabilities of Ai with an animated
 * demo"): a scripted conversation per capability (`AiDemo`, core), played in the panel as
 * the real thing plays — the person's line, what Ai did as it did it, then its answer
 * written out, tables and charts and card art included. No model is called, so it costs
 * nothing and says the same thing every time. Only words appear; nothing moves.
 */
@Composable
internal fun AiDemoView(ai: AiState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val scenes = AiDemo.scenes
    var at by remember { mutableIntStateOf(ai.demoStill ?: 0) }
    val scene = scenes[at.coerceIn(0, scenes.lastIndex)]
    // Where the scene is: 0 the person's line; then one step per activity line; then the reply, a letter at a time.
    val still = ai.demoStill != null
    var lines by remember(at) { mutableIntStateOf(if (still) scene.activity.size else 0) }
    var shown by remember(at) { mutableIntStateOf(if (still) scene.reply.length else 0) }
    val reply = scene.reply
    LaunchedEffect(at) {
        if (still) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        repeat(scene.activity.size) {
            kotlinx.coroutines.delay(420)
            lines = it + 1
        }
        kotlinx.coroutines.delay(300)
        var start = -1L
        while (shown < reply.length) {
            withFrameMillis { now ->
                if (start < 0) start = now
                // About 260 letters a second: quick enough to read along, slow enough to see it write.
                shown = ((now - start) * 0.26).toInt().coerceAtMost(reply.length)
            }
        }
    }
    val done = shown >= reply.length
    val scroll = rememberScrollState()
    LaunchedEffect(shown / 120, lines) { scroll.scrollTo(scroll.maxValue) }
    Column(modifier) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono("${(at + 1).toString().padStart(2, '0')} / ${scenes.size.toString().padStart(2, '0')}", color = c.ink45, modifier = Modifier.weight(1f))
                com.kaiharimoto.neue.kit.MicroLink("Skip", { ai.demoOpen = false })
            }
            MuText(scene.title, style = MuType.h2(LocalMuFonts.current), color = c.ink)
            Help(scene.caption, color = c.ink70)
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.fillMaxWidth(0.88f).background(c.ink06).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Small(scene.person, color = c.ink)
                }
            }
            scene.activity.take(lines).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Mono("→", color = c.ink45)
                    Mono(line, color = c.ink70)
                }
            }
            if (shown > 0) {
                val text = reply.take(shown)
                val blocks = remember(text, done) { ChatMarkdown.parse(text, streaming = !done) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kaiharimoto.neue.ai.avatar.AiName(ai.name, c.ink45)
                    blocks.forEach { MarkdownBlock(ai, it) }
                }
            }
        }
        // The steps along the foot, and the way on.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                scenes.indices.forEach { i ->
                    Box(Modifier.width(18.dp).height(3.dp).background(if (i <= at) c.ink else c.ink12))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (at > 0) MuButton("Back", { at -= 1 }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                Box(Modifier.weight(1f))
                if (at < scenes.lastIndex) {
                    MuButton("Next", { at += 1 }, variant = if (done) BtnVariant.PRIMARY else BtnVariant.SECONDARY, size = BtnSize.SM, arrow = true)
                } else {
                    MuButton("Start chatting", { ai.demoOpen = false; ai.focusTick++ }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, arrow = true)
                }
            }
        }
    }
}
