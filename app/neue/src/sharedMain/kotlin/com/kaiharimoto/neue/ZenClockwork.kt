package com.kaiharimoto.neue

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.kaiharimoto.mastertool.core.motion.ZenClock
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.neue.zen.ZenLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.core.deck.Lens
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.kaiharimoto.neue.theme.MuMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Zen's clockwork: the phase deepens with idleness (`ZenClock`), and two
 * amounts follow the phase — slowly in, and back "slowly" as kai asked, a little
 * slower than they went. They are written into [ZenLayer] frame by frame and read
 * only in layers and draw blocks, so nothing recomposes while they move. The
 * clock that floats the cards runs only while zen is deep.
 */
@Composable
internal fun ZenClockwork(h: NeueHolders) {
    val neue = h.neue
    // An empty deck has nothing to float (kai, 1.0.14): zen waits for a card.
    // Ai open is someone at work (1.0.46): the deck does not float away from a conversation.
    val eligible = neue.immersive && neue.page == Page.BUILDER && h.builder.deck.totalCards > 0 && !neue.aiDocked
    LaunchedEffect(eligible, h.zenAuto, neue.prefs.autoZen) {
        if (!eligible) {
            neue.zen = ZenPhase.AWAKE
            return@LaunchedEffect
        }
        // The bar's Zen switch (1.0.16): off, zen comes only when asked for (Z).
        if (!h.zenAuto || !neue.prefs.autoZen) return@LaunchedEffect
        h.lastInput = System.nanoTime()
        while (true) {
            // A menu, a dialog or a card in the hand is someone doing something.
            if (h.drag.held != null || neue.overlayOpen || h.updates.dialogOpen) h.lastInput = System.nanoTime()
            val idle = (System.nanoTime() - h.lastInput) / 1_000_000
            val phase = ZenClock.phase(idle)
            if (phase > neue.zen) neue.zen = phase
            val wait = ZenClock.untilNext(idle)
            if (wait == null) {
                snapshotFlow { neue.zen }.first { it != ZenPhase.DEEP }
            } else {
                delay(wait.coerceAtLeast(50))
            }
        }
    }

    // From where the amounts are, so a tree composed afresh mid-fade carries on from there.
    val quiet = remember { Animatable(h.zen.quiet) }
    val deep = remember { Animatable(h.zen.deep) }
    val phase = if (eligible) neue.zen else ZenPhase.AWAKE
    LaunchedEffect(phase) {
        // Deep is the pointer's: from this moment, nothing but the cards answers it.
        val begins = phase == ZenPhase.DEEP && !h.zen.asleep
        h.zen.asleep = phase == ZenPhase.DEEP
        // Every zen starts with the cards in their slots (1.0.24): the last one's are forgotten.
        if (begins) h.zen.begin()
        val q = if (phase != ZenPhase.AWAKE) 1f else 0f
        val d = if (phase == ZenPhase.DEEP) 1f else 0f
        coroutineScope {
            launch {
                quiet.animateTo(q, tween(if (q > 0f) ZEN_IN else ZEN_OUT, delayMillis = if (q > 0f) 0 else 400, easing = MuMotion.ease)) { h.zen.quiet = value }
            }
            launch {
                deep.animateTo(d, tween(if (d > 0f) ZEN_DEEP_IN else ZEN_OUT, easing = MuMotion.ease)) { h.zen.deep = value }
            }
        }
    }
    // Zen's pieces (1.0.15): on from the start when the builder had its groups on, so
    // the pieces the person was looking at stay open; asked for from the corner otherwise.
    LaunchedEffect(phase) {
        if (phase == ZenPhase.DEEP) {
            val on = h.builder.lens == Lens.ROLES
            h.zen.groups = on
            h.zen.groupsAmount = if (on) 1f else 0f
        } else {
            h.zen.groups = false
        }
    }
    val pieces = remember { Animatable(0f) }
    LaunchedEffect(h.zen.groups) {
        pieces.snapTo(h.zen.groupsAmount)
        pieces.animateTo(if (h.zen.groups) 1f else 0f, tween(ZEN_PIECES, easing = MuMotion.ease)) { h.zen.groupsAmount = value }
    }
    // The groups' names on their pieces (1.0.24): the corner's Labels switch, kept in the settings.
    val labels = remember { Animatable(h.zen.labelsAmount) }
    LaunchedEffect(neue.prefs.zenLabels) {
        labels.snapTo(h.zen.labelsAmount)
        labels.animateTo(if (neue.prefs.zenLabels) 1f else 0f, tween(MuMotion.SLOW, easing = MuMotion.ease)) { h.zen.labelsAmount = value }
    }
    // The Z key (1.0.15): immersive if it was not, and deep at once. A moment first when
    // immersive is only now coming on, so the deck has been laid out full screen before it
    // is measured for the middle of it.
    LaunchedEffect(h.zenRequest) {
        if (h.zenRequest == h.zenHandled) return@LaunchedEffect
        delay(if (h.zenWaitsForLayout) 450 else 0)
        h.zenWaitsForLayout = false
        h.zenHandled = h.zenRequest
        if (neue.immersive && neue.page == Page.BUILDER && h.builder.deck.totalCards > 0) {
            h.lastInput = System.nanoTime()
            neue.zen = ZenPhase.DEEP
        }
    }
    val floating by remember { derivedStateOf { h.zen.deep > 0f } }
    LaunchedEffect(floating) {
        var last = 0L
        while (floating && h.zen.deep > 0f) {
            withFrameNanos { now ->
                if (last != 0L) h.zen.time += ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
            }
        }
    }
}

/** Zen's fades, in milliseconds: a breath in, a longer one for the deck to come forward, and back a little slower. */
private const val ZEN_IN = 1400
private const val ZEN_DEEP_IN = 2600
private const val ZEN_OUT = 1600

/** How far the wheel may close and open zen's gaps, as multiples of the standard gap. */
internal const val ZEN_GAP_MIN = 0.3f
internal const val ZEN_GAP_MAX = 5f

/** The pieces opening or closing in zen: slow enough to watch the deck come apart. */
private const val ZEN_PIECES = 900
