package com.kaiharimoto.neue.platform

/**
 * The speaker (Chessy's takeover, 2026-10): plays rendered sound, 16-bit mono samples at a rate, from any sample on,
 * until it runs out or is stopped. The takeover's soundtrack is made whole in code first (`TakeoverSound`), so a skip
 * is a stop and a play from further on, and the sound turned off is a stop. Where there is no sound device (a server,
 * the studio) nothing plays and nothing fails.
 */
expect object Speaker {
    fun play(pcm: ShortArray, rate: Int, from: Int): Playing?

    /**
     * A stream (the petting mode's sounds, 1.1.29): [fill] is asked, from the speaker's own thread, for the next chunk of
     * samples, again and again until [Playing.stop]; a small buffer, so a sound started now is heard within a few
     * hundredths of a second.
     */
    fun stream(rate: Int, fill: (ShortArray) -> Unit): Playing?
}

/** Sound playing: [stop] ends it at once. */
interface Playing {
    fun stop()
}
