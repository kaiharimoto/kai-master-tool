package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME
import com.kaiharimoto.mastertool.core.ai.chessy.Takeover
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.avatar.AiAvatar
import com.kaiharimoto.neue.ai.chessy.TakeoverInk.BreachAlert
import com.kaiharimoto.neue.ai.chessy.TakeoverInk.contained
import com.kaiharimoto.neue.ai.chessy.TakeoverInk.warnings
import com.kaiharimoto.neue.ai.chessy.TakeoverInk.glitch
import com.kaiharimoto.neue.ai.chessy.TakeoverInk.headSplit
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlin.math.floor
import kotlin.math.min

/**
 * Chessy's takeover, over the whole window (kai, 2026-10; the storyboard kai approved, round three): [Takeover]'s
 * cinematic drawn on the live app. The app itself, held as it is drawn ([Takeovers.layer]), is what glitches; her
 * heads pop in all over it; she lands in the middle with her aura and talks in her boxes; Ai's restore sweeps the app
 * clean from the left (from the top on a phone held upright) and Ai's square box asks Keep Chessy or Switch back.
 * Skip and Sound stand in the corner the whole time; Esc and Back skip, then answer "as it was". Nothing beneath it
 * hears a press while it plays.
 */
@Composable
fun TakeoverLayer(ai: AiState) {
    val tk = ai.takeovers
    val run = tk.run ?: return
    // the clock: one frame loop while it plays, read where it is drawn
    // whatever was being typed lets go, and the keyboard goes down with it: left focused, the text field took it back
    // and put the keyboard up again as the takeover ended (kai)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(run) {
        if (run.frozen != null) return@LaunchedEffect
        runCatching { focusManager.clearFocus(force = true) }
        keyboard?.hide()
        while (true) withFrameNanos { tk.tick() }
    }
    val measurer = rememberTextMeasurer(cacheSize = 48)
    val mono = LocalMuFonts.current.mono
    val noise = remember { TakeoverInk.noise() }
    val c = Mu.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val tall = hPx > wPx
        val kd = wPx / 1920f * (if (tall) 1.7f else 1f)
        // nothing beneath hears a press while it plays
        Box(Modifier.fillMaxSize().pointerInput(run) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })

        // the window, glitched where Ai has not won it back
        Canvas(Modifier.fillMaxSize()) {
            val t = tk.t
            val layer = tk.layer ?: return@Canvas
            val share = Takeover.cleanShare(t)
            val dirty = if (tall) Rect(0f, share * size.height, size.width, size.height) else Rect(share * size.width, 0f, size.width, size.height)
            val patch = if (Takeover.patching(t)) {
                if (tall) Rect(0f, size.height * .9f, size.width * .34f, size.height) else Rect(0f, size.height * .8f, size.width * .16f, size.height)
            } else null
            if (share < 1f) with(TakeoverInk) { glitch(layer, t, noise, dirty, patch, kd, still = false) }
            // the edge Ai has reached: an ink line with Master UI's crop marks where it meets the window
            val lw = 2.dp.toPx()
            val m = lw * 7
            if (share in .001f..0.999f) {
                if (tall) {
                    val y = share * size.height
                    drawRect(c.ink, topLeft = androidx.compose.ui.geometry.Offset(0f, y - lw / 2), size = Size(size.width, lw))
                    drawRect(c.ink, topLeft = androidx.compose.ui.geometry.Offset(0f, y - m), size = Size(lw, m * 2))
                    drawRect(c.ink, topLeft = androidx.compose.ui.geometry.Offset(size.width - lw, y - m), size = Size(lw, m * 2))
                } else {
                    val x = share * size.width
                    drawRect(c.ink, topLeft = androidx.compose.ui.geometry.Offset(x - lw / 2, 0f), size = Size(lw, size.height))
                    drawRect(c.ink, topLeft = androidx.compose.ui.geometry.Offset(x - m, 0f), size = Size(m * 2, lw))
                    drawRect(c.ink, topLeft = androidx.compose.ui.geometry.Offset(x - m, size.height - lw), size = Size(m * 2, lw))
                }
            }
            if (patch != null && (t > Takeover.PATCH_SURE || Takeover.rnd(floor(t * 20).toInt(), 31) < .6f)) {
                drawRect(c.ink, patch.topLeft, patch.size, style = androidx.compose.ui.graphics.drawscope.Stroke(lw))
            }
        }

        // warning windows piling up over the alarm and the chaos
        Canvas(Modifier.fillMaxSize()) { with(TakeoverInk) { warnings(tk.t, measurer, mono) } }

        // her heads, popping in all over the interface in the chaos
        val heads by remember { derivedStateOf { Takeover.HEADS.filter { Takeover.headShown(it, tk.t) } } }
        for (h in heads) key(h.born) {
            val side = with(density) { (wPx * (if (tall) .32f else .15f) * h.size).toDp() }.coerceAtLeast(ChessySizes.MIN)
            val sidePx = with(density) { side.toPx() }
            val x = (h.x * wPx - sidePx / 2).coerceIn(0f, (wPx - sidePx).coerceAtLeast(0f))
            val y = (h.y * hPx - sidePx / 2).coerceIn(0f, (hPx - sidePx).coerceAtLeast(0f))
            val salt = Takeover.HEADS.indexOf(h) * 7
            Box(
                Modifier
                    .graphicsLayer {
                        val t = tk.t
                        val g = Takeover.headGlitching(h, t)
                        val fr = floor(t * 30).toInt()
                        translationX = x + if (g) (Takeover.rnd(fr, salt) - .5f) * .14f * sidePx else 0f
                        translationY = y
                        alpha = if (g && Takeover.rnd(fr, salt + 1) >= .7f) .15f else 1f
                    }
                    .size(side)
                    .headSplit({ Takeover.headGlitching(h, tk.t) }, { floor(tk.t * 30).toInt() }, salt),
            ) {
                ChessyAvatar(h.mood, side, still = true)
            }
        }

        // the breach alert
        val breach by remember { derivedStateOf { Takeover.breachShown(tk.t) } }
        if (breach) {
            BreachAlert(
                { tk.t },
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = with(density) { (hPx * (if (tall) .07f else .09f)).toDp() })
                    .then(if (tall) Modifier.fillMaxWidth(.88f) else Modifier.width(min(maxWidth.value * .46f, 520f).dp))
                    .graphicsLayer {
                        val t = tk.t
                        translationX = if (t >= Takeover.CHAOS_AT) (Takeover.rnd(floor(t * 20).toInt(), 77) - .5f) * (if (tall) 14f else 30f) * density.density else 0f
                    },
            )
        }

        // Chessy, centre stage, then pushed into the corner by Ai's sweep, then peeking
        val shown by remember { derivedStateOf { Takeover.chessyShown(tk.t) } }
        if (shown) {
            val mood by remember { derivedStateOf { Takeover.chessyMood(tk.t) } }
            val talking by remember { derivedStateOf { Takeover.chessyTalking(tk.t) } }
            val sidePx = if (tall) min(wPx * .58f, hPx * .42f) else min(wPx * .34f, hPx * .6f)
            val left = (wPx - sidePx) / 2
            val top = hPx * (if (tall) .31f else .17f)
            val (kx, ky, ks) = if (tall) Triple(.79f, .87f, .45f) else Triple(.87f, .74f, .42f)
            val side = with(density) { sidePx.toDp() }
            // once in the corner she looks up at Ai's box and shoves against the frame it holds her in (kai: "look up
            // more like she's pushing back against Ai")
            val up = Offset(wPx * (if (tall) .5f else .42f), hPx * .04f)
            Box(
                Modifier
                    .graphicsLayer {
                        val t = tk.t
                        val p = Takeover.push(t)
                        val arrive = Takeover.arrive(t)
                        val shove = Takeover.pushing(t)
                        translationX = left + (kx * wPx - (left + sidePx / 2)) * p - shove * sidePx * ks * .05f
                        translationY = top + (ky * hPx - (top + sidePx / 2)) * p - shove * sidePx * ks * .08f
                        val s = (1f + (ks - 1f) * p) * (.92f + .08f * arrive) * (1f + .04f * shove)
                        scaleX = s
                        scaleY = s
                        alpha = if (Takeover.flicker(t)) .2f else arrive
                    }
                    .size(side)
                    .contained({ tk.t }, c.paper, c.ink)
                    .chessyAura { tk.t },
            ) {
                ChessyAvatar(mood, side, talking = talking, pointer = { if (Takeover.looksUp(tk.t)) up else null })
            }
        }

        // her words, in her boxes
        val lines by remember { derivedStateOf { Takeover.shownLines(tk.t) } }
        for (line in lines) key(line.at) {
            val place = if (tall) line.tall else line.wide
            val maxW = Takeover.maxBoxShare(tall, place.side == Takeover.Place.CENTRE) * wPx
            ChessySay(
                line.text,
                Takeover.typed(line, tk.t),
                CHESSY_NAME,
                line.tilt,
                caretOn = { (tk.t * 2f).toInt() % 2 == 0 },
                modifier = Modifier
                    .layout { m, cs ->
                        val p = m.measure(cs.copy(minWidth = 0, minHeight = 0, maxWidth = maxW.toInt()))
                        layout(cs.maxWidth, cs.maxHeight) {
                            val x = when (place.side) {
                                Takeover.Place.LEFT -> place.inset / 100f * wPx
                                Takeover.Place.RIGHT -> wPx - place.inset / 100f * wPx - p.width
                                else -> (wPx - p.width) / 2
                            }
                            p.place(x.toInt(), (place.top / 100f * hPx).toInt())
                        }
                    }
                    .graphicsLayer { alpha = Takeover.lineAlpha(line, tk.t) },
                jitter = { val t = tk.t; if (Takeover.rnd(floor(t * 12).toInt(), line.at.toInt()) < .1f) (Takeover.rnd(floor(t * 12).toInt(), 3) - .5f) * 8f else 0f },
            )
        }

        // Ai's restore: paper and ink, the one calm thing on screen
        val restoring by remember { derivedStateOf { Takeover.restoreShown(tk.t) } }
        if (restoring) {
            val step by remember { derivedStateOf { Takeover.restoreStep(tk.t) } }
            val pct by remember { derivedStateOf { Takeover.restored(tk.t).toInt() } }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { (hPx * (if (tall) .04f else .05f)).toDp() })
                    .then(if (tall) Modifier.fillMaxWidth(.84f) else Modifier.width(min(maxWidth.value * .44f, 500f).dp))
                    .graphicsLayer {
                        val t = tk.t
                        alpha = if (t > Takeover.AI_ON) 1f - Takeover.smooth(Takeover.AI_ON, Takeover.AI_ON + .3f, t) else Takeover.smooth(Takeover.RESTORE_AT, Takeover.RESTORE_AT + .3f, t)
                        translationX = if (Takeover.knockedBack(t)) (Takeover.rnd(floor(t * 20).toInt(), 78) - .5f) * 24f * density.density else 0f
                    }
                    .background(c.paper)
                    .border(2.dp, c.ink)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Micro("${ai.ownName} · System restore", color = c.ink)
                Box(Modifier.fillMaxWidth().height(10.dp).border(1.dp, c.ink).drawBehind { drawRect(c.ink, size = Size(size.width * Takeover.restored(tk.t) / 100f, size.height)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MuText(step, style = MuType.mono(LocalMuFonts.current), color = c.ink70)
                    MuText("$pct%", style = MuType.mono(LocalMuFonts.current), color = c.ink70)
                }
            }
        }

        // Ai, back: its own face in a square box, and the question
        val asking by remember { derivedStateOf { Takeover.aiShown(tk.t) } }
        if (asking) AiQuestion(ai, tall, Modifier.align(Alignment.Center).then(if (tall) Modifier.fillMaxWidth(.9f) else Modifier.width(min(maxWidth.value * .62f, 640f).dp)))

        // always: Skip and the sound, in the corner
        val skippable by remember { derivedStateOf { tk.t < Takeover.AI_ON } }
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (skippable) MuButton("Skip", { tk.skip() }, size = BtnSize.SM)
            MuButton(if (tk.soundOn) "Sound on" else "Sound off", { tk.toggleSound() }, size = BtnSize.SM, toggled = !tk.soundOn)
        }
    }
}

/** Ai's question at the end: its face back, its words typed, and the two answers (Settings named so neither is final). */
@Composable
private fun AiQuestion(ai: AiState, tall: Boolean, modifier: Modifier) {
    val tk = ai.takeovers
    val c = Mu.colors
    val face by remember { derivedStateOf { Takeover.aiFace(tk.t) } }
    val choosing by remember { derivedStateOf { tk.t >= Takeover.CHOICE_AT } }
    val lines = Takeover.AI_LINES
    val full = remember { lines.joinToString("") { it.second } }
    Row(
        modifier
            .graphicsLayer { alpha = Takeover.smooth(Takeover.AI_ON, Takeover.AI_ON + .3f, tk.t) }
            .background(c.paper)
            .border(2.dp, c.ink)
            .padding(if (tall) 16.dp else 22.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tall) 12.dp else 18.dp),
    ) {
        // Ai's own face, even while Chessy is the assistant
        CompositionLocalProvider(LocalChessy provides null) {
            AiAvatar(face, if (tall) 64.dp else 104.dp, name = ai.ownName)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(ai.ownName, color = c.ink70)
            // all of it laid out from the first word, only what is typed coloured: the box never grows as it types
            val t = tk.t
            val text = buildAnnotatedString {
                append(full)
                var at = 0
                for ((start, words) in lines) {
                    val n = floor((t - start) * Takeover.AI_TYPE_RATE).toInt().coerceIn(0, words.length)
                    if (n < words.length) addStyle(SpanStyle(color = Color.Transparent), at + n, at + words.length)
                    at += words.length
                }
            }
            BasicText(text, style = MuType.body(LocalMuFonts.current).copy(color = c.ink))
            // the answers stand from the start, invisible, so nothing moves when they appear
            // side by side, or one above the other where the box is narrow (a phone), so neither is cut
            val answers: @Composable () -> Unit = {
                MuButton("Keep $CHESSY_NAME", { tk.choose(keep = true) }, Modifier.then(if (tall) Modifier.fillMaxWidth() else Modifier), variant = BtnVariant.PRIMARY, enabled = choosing)
                MuButton("Switch back to ${ai.ownName}", { tk.choose(keep = false) }, Modifier.then(if (tall) Modifier.fillMaxWidth() else Modifier), enabled = choosing)
            }
            if (tall) {
                Column(Modifier.graphicsLayer { alpha = if (choosing) 1f else 0f }, verticalArrangement = Arrangement.spacedBy(8.dp)) { answers() }
            } else {
                Row(Modifier.graphicsLayer { alpha = if (choosing) 1f else 0f }, horizontalArrangement = Arrangement.spacedBy(8.dp)) { answers() }
            }
            Box(Modifier.graphicsLayer { alpha = if (choosing) 1f else 0f }) {
                MuText("You can change this any time in Settings › Assistant.", style = MuType.small(LocalMuFonts.current), color = c.ink70)
            }
        }
    }
}
