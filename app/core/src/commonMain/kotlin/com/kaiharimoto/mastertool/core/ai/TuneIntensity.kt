package com.kaiharimoto.mastertool.core.ai

/**
 * How long and how hard Fine Tuning goes (1.0.48, kai: "the user can set the intensity of how
 * long it takes the AI and how much effort"). One setting for both ways of it: when the
 * person teaches, how many questions; when Ai studies alone, how many rounds of tools, which
 * sources it reads and how hard the model thinks.
 */
enum class TuneIntensity(
    val id: String,
    val label: String,
    /** About how many questions Ai asks when the person teaches it. */
    val questions: Int,
    /** The most rounds of tools a study may take. */
    val steps: Int,
    /** How hard the model thinks, where it can be told. */
    val effort: String,
    /** What the person is told it costs them, in time. */
    val teachTime: String,
    val studyTime: String,
    /** What a study reads at this intensity, in the person's words. */
    val studies: String,
) {
    QUICK("quick", "Quick", 6, 14, "low", "About five minutes", "A minute or two",
        "Every card's text and the archetype's page on Yugipedia."),
    STANDARD("standard", "Standard", 12, 32, "medium", "About fifteen minutes", "A few minutes",
        "Adds the key cards' rulings and how recent tournament lists build it."),
    DEEP("deep", "Deep", 20, 64, "high", "Half an hour or more", "Ten minutes or more",
        "Adds up to twenty tournament lists read by a helper, recent guides from the web, and a second pass checking its own guide against the cards."),
    ;

    companion object {
        fun of(id: String?): TuneIntensity = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}
