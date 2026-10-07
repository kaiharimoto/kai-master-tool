package com.kaiharimoto.neue.browser

import com.kaiharimoto.mastertool.core.ai.course.Transcript
import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel

/**
 * A video chapter's sound turned into its words (Phase 2 of the course study): the webm the page recorded, decoded and
 * transcribed on the computer with the voice model the person downloaded for talking to Ai — nothing leaves the device.
 */
expect object VideoListening {
    /** Why this device cannot listen to a video with [model], or null when it can. */
    fun missing(model: VoiceModel): String?

    /** [webm]'s words, each piece of about half a minute with when it was said, at [rate] the speed it was played at. */
    suspend fun transcribe(webm: ByteArray, model: VoiceModel, rate: Double): List<Transcript.Line>
}
