package com.kaiharimoto.neue.browser

import com.kaiharimoto.mastertool.core.ai.course.Transcript
import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel

/** The study runs on the desk alone for now: a phone or tablet never listens to a course's videos. */
actual object VideoListening {
    actual fun missing(model: VoiceModel): String? = "Listening to a course's videos needs the desktop app."

    actual suspend fun transcribe(webm: ByteArray, model: VoiceModel, rate: Double): List<Transcript.Line> = emptyList()
}
