package com.kaiharimoto.mastertool.core.present.ai

/**
 * What "Restyle" hands Ai (1.0.72): the look the person described in their own words, how much of
 * the presentation it is for, whether readability is held, and whether a picture came with it —
 * turned into the first message of a restyle conversation, which changes the look and nothing else.
 */
data class RestyleBrief(
    val presentationId: String,
    /** What the person asked for, in their words. */
    val ask: String,
    /** One slide's id, or null for the whole presentation. */
    val slideId: String? = null,
    /** The slide's number, for the words. */
    val slideNumber: Int = 0,
    /** Hold every word at 4.5 : 1 against what is behind it. */
    val readable: Boolean = true,
    /** A picture (a logo, a banner) is attached to take the colors from. */
    val picture: Boolean = false,
) {
    companion object {
        /** The suggestions under the field: they fill it, the person can change them. */
        val SUGGESTIONS = listOf(
            "Match my channel's colors",
            "Dark and punchy",
            "Bright and clean",
            "Esports broadcast",
            "Calm and minimal",
        )
    }

    fun message(): String = buildString {
        appendLine("Restyle ${if (slideId != null) "slide $slideNumber (id $slideId)" else "the whole presentation"} in Present (presentation id $presentationId).")
        appendLine("The look I want: ${ask.trim().ifBlank { "your best idea for this deck" }}")
        if (picture) appendLine("- Take the colors from the picture I attached; keep the cards the hero.")
        if (slideId != null) appendLine("- Change only that slide: its background and its elements' colors, faces and fills. Leave the theme alone.")
        appendLine(if (readable) "- Keep every word easy to read: at least 4.5 to 1 against what is behind it." else "- Readability is my call; still tell me when words get hard to read.")
        appendLine("- Change the look only: never the words, the cards, the order of the slides or the speaker notes.")
        append("Follow the restyle skill, and check every slide you change with present_view before you finish.")
    }
}
