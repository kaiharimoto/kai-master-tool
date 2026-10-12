package com.kaiharimoto.mastertool.core.ai

/**
 * Every mode a conversation with Ai can be in, and what it is (Phase I, I.5; F2, "one list per fact"): what it may not touch
 * and what the panel's head calls it. These were a `when` in [AiTools] and another in the panel, each with an `else` a new
 * mode fell into unseen; a new mode is a row here now, and `ArchitectureLawTest` fails on a mode constant without one.
 */
object AiModes {
    class Mode(
        val id: String,
        /** What the panel's head says while a conversation is in it, before the connection's name; null, the connection alone. */
        val heading: String? = null,
        /** It may change a deck ([AiTools.DECK_CHANGING]): never while Ai learns a deck, writes its guide or interviews. */
        val changesDecks: Boolean = true,
        /** It may open another deck ([AiTools.SWITCHING_DECKS]). */
        val opensOtherDecks: Boolean = true,
        /** From the card text and the rules alone: the web and the field closed ([AiTools.FIRST_PRINCIPLES_BARRED]). */
        val textAlone: Boolean = false,
    )

    val ALL: List<Mode> = listOf(
        Mode(AiSession.MODE_CHAT),
        // Fine Tuning, its own study, from first principles, rewriting the guide and writing the book: the deck stays as it is.
        Mode(AiSession.MODE_TUNE, changesDecks = false, opensOtherDecks = false),
        Mode(AiSession.MODE_STUDY, changesDecks = false, opensOtherDecks = false),
        Mode(AiSession.MODE_PRINCIPLES, changesDecks = false, opensOtherDecks = false, textAlone = true),
        Mode(AiSession.MODE_REFACTOR, changesDecks = false, opensOtherDecks = false),
        Mode(AiSession.MODE_WRITE, changesDecks = false, opensOtherDecks = false),
        // Learn About You: no deck changed, any deck read.
        Mode(AiSession.MODE_PROFILE, changesDecks = false),
        Mode(AiSession.MODE_PRESENT),
        Mode(AiSession.MODE_RESTYLE),
        Mode(AiSession.MODE_DUEL, heading = "At the duel table"),
        Mode(AiSession.MODE_WORLD, heading = "In Ai World"),
        // Shootout (Phase S stage 3): a hand judged and the rubric's interview change no deck and stay on the matchup.
        Mode(AiSession.MODE_SHOOTOUT, changesDecks = false, opensOtherDecks = false),
        Mode(AiSession.MODE_RUBRIC, heading = "Shootout interview", changesDecks = false, opensOtherDecks = false),
        // Writing effects (Phase D step 2): the asked cards' scripts and nothing else.
        Mode(AiSession.MODE_EFFECTS, heading = "Writing effects", changesDecks = false, opensOtherDecks = false),
        // A course study reads a guide and writes the deck's: it changes no deck and opens no other.
        Mode(AiSession.MODE_COURSE, changesDecks = false, opensOtherDecks = false),
        Mode(AiSession.MODE_MATCH),
    )

    private val byId: Map<String, Mode> = ALL.associateBy { it.id }

    /** [id]'s mode; a mode this build does not know (a newer one's session) is read as a chat, as it always was. */
    fun of(id: String): Mode = byId[id] ?: byId.getValue(AiSession.MODE_CHAT)
}
