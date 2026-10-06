package com.kaiharimoto.neue.ai.chessy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME
import com.kaiharimoto.mastertool.core.ai.chessy.Takeover
import com.kaiharimoto.mastertool.core.audio.TakeoverSound
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.platform.Playing
import com.kaiharimoto.neue.platform.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chessy's takeover playing (kai, 2026-10): its clock, the skip to Ai's question, the choice that ends it, and its
 * sound. The cinematic is [Takeover]'s pure functions of [t]; [TakeoverLayer] draws them over the live app, which
 * [layer] holds as it is drawn while the takeover plays. The sound is rendered whole on the first play (a moment, off
 * the frame thread) and streamed from wherever the clock is, so it follows a skip and stops when turned off.
 */
class Takeovers(private val ai: AiState) {
    /** A run of the cinematic: its clock started at [startNanos] from [from] seconds; [frozen] holds it still (the studio). */
    class Run(val startNanos: Long, val from: Float, val frozen: Float? = null)

    var run by mutableStateOf<Run?>(null)
        private set

    /** The clock now, in seconds, written by the layer's frame loop and read where it is drawn. */
    var t by mutableFloatStateOf(0f)

    /** The app as drawn while the takeover plays, for the takeover to glitch. */
    var layer: GraphicsLayer? = null

    private var pcm: ShortArray? = null
    private var rendering: Job? = null
    private var playing: Playing? = null

    val soundOn: Boolean get() = ai.prefs.takeoverSound

    /** The clock as it stands this moment. */
    fun now(): Float {
        val r = run ?: return 0f
        return r.frozen ?: (r.from + (System.nanoTime() - r.startNanos) / 1e9f).coerceAtMost(Takeover.END)
    }

    /** Plays it from the start (or from [at]). */
    fun start(at: Float = 0f) {
        run = Run(System.nanoTime(), at)
        t = at
        ai.h.neue.menu = null
        sound()
    }

    /** Holds it still at [at] seconds: the studio's photographs. */
    fun freeze(at: Float) {
        run = Run(System.nanoTime(), at, frozen = at)
        t = at
    }

    /** Straight to Ai's question (Skip, Esc, Back). */
    fun skip() {
        if (run == null || now() >= Takeover.AI_ON) return
        run = Run(System.nanoTime(), Takeover.AI_ON)
        t = Takeover.AI_ON
        sound()
    }

    /** The question answered: Chessy stays as the assistant, or Ai comes back. Either way it has been seen. */
    fun choose(keep: Boolean) {
        stopSound()
        run = null
        layer = null
        val persona = if (keep) AiPrefs.PERSONA_CHESSY else AiPrefs.PERSONA_AI
        ai.h.neue.update { it.copy(ai = it.ai.copy(persona = persona, takeover = AiPrefs.TAKEOVER_SEEN)) }
        ai.h.neue.note = Note(
            if (keep) "$CHESSY_NAME is your assistant now. Settings › Assistant changes it back"
            else "${ai.ownName} is back. Settings › Assistant brings $CHESSY_NAME in, or type /chessy",
        )
    }

    /** Esc or Back at the question: whoever the assistant was before stays. */
    fun dismiss() = choose(keep = ai.prefs.persona == AiPrefs.PERSONA_CHESSY)

    fun toggleSound() {
        val on = !soundOn
        ai.h.neue.update { it.copy(ai = it.ai.copy(takeoverSound = on)) }
        if (on) sound() else stopSound()
    }

    private fun stopSound() {
        playing?.stop()
        playing = null
    }

    /** The soundtrack from where the clock is, once it is rendered; nothing while the sound is off or held still. */
    private fun sound() {
        stopSound()
        val r = run ?: return
        if (!soundOn || r.frozen != null) return
        val p = pcm
        if (p == null) {
            if (rendering == null) {
                rendering = ai.scope.launch {
                    pcm = withContext(Dispatchers.Default) { TakeoverSound.render() }
                    rendering = null
                    sound()
                }
            }
            return
        }
        val at = now()
        if (at < Takeover.END) playing = Speaker.play(p, TakeoverSound.RATE, TakeoverSound.sampleAt(at))
    }
}
