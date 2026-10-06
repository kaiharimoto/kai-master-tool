package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.prefs.AiPrefs

/**
 * Chessy (kai, 2026-10): a cat girl, a ghost in the system, who hacks the app and takes over Ai's role. These are
 * the rules of her story that are not a picture: the words typed to call her up, and when her takeover is due.
 */

/** Her name, shown wherever the assistant is named while she is the assistant (kai: "have the button … be called Chessy"). */
const val CHESSY_NAME = "Chessy"

/** What the chat's composer can say to the app itself rather than to the model: a whole message, one command. */
enum class SlashCommand(val word: String) {
    /** Makes her the assistant at once, with no cinematic (kai, 1.1.31); [TAKEOVER] is the cinematic. */
    CHESSY("chessy"),

    /** Plays the takeover now, whoever the assistant is (kai: "let me invoke the takeover cinematic with /takeover"). */
    TAKEOVER("takeover"),

    /** Chessy's full cat voice in her replies, on or off. */
    CAT_MODE("catmode"),

    /** Ai back as the assistant. */
    AI("ai");

    companion object {
        /**
         * The command a message is, or null: the whole message, after trimming, is a slash and a command's word, in
         * any case. Anything else (a slash inside a sentence, `/chessy please`) goes to the model as it is.
         */
        fun parse(text: String): SlashCommand? {
            val t = text.trim()
            if (!t.startsWith("/") || t.length < 2) return null
            val word = t.drop(1).lowercase()
            return entries.firstOrNull { it.word == word }
        }
    }
}

/** When Chessy's takeover plays by itself. */
object TakeoverGate {
    /** Replies Ai finishes before she breaks in (kai: after 5 uses). */
    const val USES = 5

    /**
     * Whether it is due now: the assistant is on, it has not played, enough replies are done, and nothing is under
     * way — a reply being written, a card carried, a key held. [busy] is the app's say on that last part; the
     * takeover never interrupts.
     */
    fun due(prefs: AiPrefs, busy: Boolean): Boolean =
        prefs.enabled && !busy && prefs.takeover == AiPrefs.TAKEOVER_NONE && prefs.uses >= USES
}

/**
 * Chessy's three sheet faces by name: the Grin her face layer wears, the Fangs and the Tongue. Her moods mix their
 * parts ([ChessyMoods]); [ofMood] is the whole face nearest a mood, worn when the mood parts are missing.
 */
object ChessyFaces {
    const val GRIN = "grin"
    const val FANGS = "fangs"
    const val TONGUE = "tongue"

    fun ofMood(m: ChessyMood): String = when {
        m.lips == ChessyLips.TONGUE || (m.eyeL == ChessyEye.SHUT && m.eyeR == ChessyEye.SHUT) -> TONGUE
        m.lips == ChessyLips.FANGS || m.eyeL == ChessyEye.WIDE -> FANGS
        else -> GRIN
    }
}
