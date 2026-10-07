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
    /** How many characters one run may add to the deck's guide (1.0.66; the guide itself has no cap). */
    val guideBudget: Int,
) {
    // Mastery (1.1.43, kai: "the notes need to be thorough"): more thought, more rounds and more room at every level; what
    // is learned goes whole into the playbook, which has no room limit — the guide's room is the plan's.
    QUICK("quick", "Quick", 6, 20, "medium", "About five minutes", "A few minutes",
        "Every card's text and the archetype's page on Yugipedia.", 8_000),
    STANDARD("standard", "Standard", 12, 60, "high", "About fifteen minutes", "Ten minutes or so",
        "Adds the key cards' rulings and how recent tournament lists build it.", 20_000),
    DEEP("deep", "Deep", 20, 120, "xhigh", "Half an hour or more", "Half an hour or more",
        "Adds up to twenty tournament lists read by a helper, recent guides from the web, and a second pass checking its own guide against the cards.", 60_000),
    ;

    companion object {
        fun of(id: String?): TuneIntensity = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}
