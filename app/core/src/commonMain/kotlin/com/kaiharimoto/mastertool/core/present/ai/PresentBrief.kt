package com.kaiharimoto.mastertool.core.present.ai

import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.modules.Modules

/**
 * What "Build with Ai" hands Ai (1.0.71): the person's answers to the launcher — how long the
 * video runs, its tone, the modules, whether to write the script — turned into the first message
 * of a Present conversation, so the `deck-profile` skill starts with what it would otherwise ask.
 */
data class PresentBrief(
    /** The presentation to build in; null when Ai is to make one. */
    val presentationId: String? = null,
    val deckId: String? = null,
    val deckName: String = "",
    val style: String = Presentation.STYLE_SPOTLIGHT,
    val length: String = STANDARD,
    val tone: String = TONE_TEACHING,
    val modules: List<String> = emptyList(),
    val script: Boolean = true,
    /** Anything the person typed for Ai. */
    val extra: String = "",
) {
    companion object {
        const val SHORT = "SHORT"
        const val STANDARD = "STANDARD"
        const val DEEP = "DEEP"
        val LENGTHS = listOf(SHORT, STANDARD, DEEP)

        const val TONE_TEACHING = "TEACHING"
        const val TONE_HYPE = "HYPE"
        const val TONE_CALM = "CALM"
        val TONES = listOf(TONE_TEACHING, TONE_HYPE, TONE_CALM)

        /** Minutes of video each length aims at. */
        fun minutes(length: String): IntRange = when (length) {
            SHORT -> 3..5
            DEEP -> 15..25
            else -> 8..12
        }

        fun lengthName(length: String): String = when (length) {
            SHORT -> "Short"
            DEEP -> "Deep dive"
            else -> "Standard"
        }

        fun lengthLine(length: String): String = minutes(length).let { "${it.first}–${it.last} minutes" }

        fun toneName(tone: String): String = when (tone) {
            TONE_HYPE -> "Hype"
            TONE_CALM -> "Calm"
            else -> "Teaching"
        }

        /** What a tone means for the words, said to Ai. */
        private fun toneLine(tone: String): String = when (tone) {
            TONE_HYPE -> "energetic and punchy, short sentences, excitement about the plays"
            TONE_CALM -> "relaxed and conversational, like talking a friend through the deck"
            else -> "clear and teaching, every choice explained so a newer player follows"
        }

        /** The modules offered at launch: the ones Ai can fill from the app's own data, then the person's. */
        val OFFERED = listOf(Modules.SIDING, Modules.MATCHUPS, Modules.ODDS, Modules.TOURNAMENT, Modules.PERFORMERS, Modules.TECH, Modules.SHOUTOUTS, Modules.GET_THE_DECK)
    }

    /** About how many slides fit the length, for the skill to aim at. */
    val slides: IntRange get() = when (length) {
        SHORT -> 6..9
        DEEP -> 18..30
        else -> 10..16
    }

    fun message(): String = buildString {
        appendLine("Build a deck profile for my video in Present.")
        if (presentationId != null) {
            appendLine("Work in the open presentation (id $presentationId); read it with present_state first and keep what I already made.")
        } else {
            appendLine("Make it with present_edit create, deck_id ${deckId ?: "the deck on the builder"}${if (deckName.isNotBlank()) " (“$deckName”)" else ""}, style ${Presentation.styleName(style).lowercase()}.")
        }
        val m = minutes(this@PresentBrief.length)
        appendLine("- Length: ${m.first} to ${m.last} minutes, about ${slides.first} to ${slides.last} slides.")
        appendLine("- Tone: ${toneLine(tone)}.")
        if (modules.isNotEmpty()) {
            appendLine("- Modules: " + modules.joinToString { Modules.name(it) } + ". Ask me for the picks the app does not know.")
        } else {
            appendLine("- No modules unless the deck's data makes one obviously worth it; ask first.")
        }
        appendLine(if (script) "- Write my script in each slide's speaker notes, fitted to the length." else "- Keep the speaker notes to a few cue words per slide; I talk freely.")
        if (extra.isNotBlank()) appendLine("- Also: ${extra.trim()}")
        append("Follow the deck-profile and slide-design skills, and check every slide with present_view before you finish.")
    }
}
