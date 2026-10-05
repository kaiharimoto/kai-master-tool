package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.Format

/**
 * What the person plays, as the builder's bar offers it (the 1.1.2 design review, finding 3, kai's option A):
 * `TCG | OCG | Genesys`. Genesys is a format a player chooses, not a way of checking, so it stands where the region
 * does.
 *
 * It is stored as the two values older builds already read: the region (`UiPreferences.format`) and
 * `NeuePreferences.genesys`. Genesys keeps the region **TCG** — Genesys is TCG cards — so a build from before reads a
 * valid TCG deck builder with its Genesys switch on; choosing TCG or OCG turns Genesys off. No new preference.
 */
enum class PlayChoice(val label: String) {
    TCG("TCG"),
    OCG("OCG"),
    GENESYS("Genesys"),
    ;

    /** The region stored for this choice: Genesys is TCG cards. */
    val format: Format get() = if (this == OCG) Format.OCG else Format.TCG

    /** Whether this choice turns Genesys on. */
    val genesys: Boolean get() = this == GENESYS

    /** The next one along, for a control that steps through them (a key, a voice, a row tapped again). */
    fun next(): PlayChoice = entries[(ordinal + 1) % entries.size]

    companion object {
        /** What the bar shows for the stored values: Genesys wins, whatever region an older build left beside it. */
        fun of(format: Format, genesys: Boolean): PlayChoice = when {
            genesys -> GENESYS
            format == Format.OCG -> OCG
            else -> TCG
        }

        /** "TCG", "ocg", "genesys": the choice a word names, else null. */
        fun parse(word: String): PlayChoice? = entries.firstOrNull { it.label.equals(word.trim(), ignoreCase = true) }

        /**
         * The region to keep beside [genesys]: Genesys holds it at TCG, so a value an older build or another device
         * wrote ("Genesys" and OCG at once) settles to the one thing it can mean. Null when [format] is already right.
         */
        fun settledFormat(format: Format, genesys: Boolean): Format? = if (genesys && format != Format.TCG) Format.TCG else null
    }
}
