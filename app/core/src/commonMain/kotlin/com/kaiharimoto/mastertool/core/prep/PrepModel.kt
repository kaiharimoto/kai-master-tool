package com.kaiharimoto.mastertool.core.prep

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An event you are preparing for: when, how big, what you are bringing and what
 * the organiser asks of you before it starts.
 *
 * Strings rather than enums for [decklist], [date] and the like, because this is
 * stored as JSON in the preferences table: a value a later build adds must not
 * make an older build throw the whole document away.
 */
@Serializable
data class PrepEvent(
    val id: String,
    val name: String,
    /** ISO `yyyy-mm-dd`. */
    val date: String,
    /** Konami's tier, 1 (locals) to 4 (Worlds): it decides decklists, sleeves and rounds ([Policy]). */
    val tier: Int = 1,
    /** Expected or announced players; 0 when not known yet. */
    val attendance: Int = 0,
    /** The web of decks expected there (Format page). */
    val webId: String? = null,
    /** The deck you are registering. */
    val deckId: String? = null,
    /** How the list is handed in: [DECKLIST_NEURON], [DECKLIST_ONLINE] or [DECKLIST_PAPER]. */
    val decklist: String = DECKLIST_PAPER,
    /** When the list is due, as the organiser wrote it (ISO date or date and time). */
    val deadline: String? = null,
    /** When check-in opens or closes, as the organiser wrote it. */
    val checkIn: String? = null,
    val notes: String = "",
    /** Ids of the checklist items ticked off. */
    val checked: List<String> = emptyList(),
    /** The share of the room the web's decks do not stand for, in percent (Phase G, G.5: the red team's M4); 0 for none. */
    val otherShare: Int = 0,
    /** The match win to expect against that rest of the room, in percent. */
    val otherWin: Int = 50,
    /** Whether a match too long for the round counts as the loss it is (§V.B) in the expected win. */
    val countTime: Boolean = false,
) {
    companion object {
        const val DECKLIST_NEURON = "NEURON"
        const val DECKLIST_ONLINE = "ONLINE"
        const val DECKLIST_PAPER = "PAPER"
    }
}

/**
 * One test game, as logged straight after it: who against, which turn, before or
 * after siding, the result, why it went that way, and how long it took — the
 * last because a matchup that runs long loses matches to the clock, not the
 * opponent ([TestStats.timeRisk]).
 */
@Serializable
data class TestGame(
    val id: String,
    /** Epoch milliseconds. */
    val at: Long,
    /** The deck you played. */
    val deckId: String?,
    /** A web deck's id, or the name typed when the opponent is in no web. */
    val opponent: String,
    val opponentName: String,
    /** [FIRST] or [SECOND]: your turn in this game. */
    val turn: String,
    /** 1 before siding, 2 or 3 after. */
    val game: Int = 1,
    /** [WIN], [LOSS] or [DRAW]. */
    val result: String,
    /** Why: [REASON_BRICK], [REASON_INTERRUPTED], [REASON_OUTPLAYED], [REASON_TIME], [REASON_OTHER]. */
    val reason: String? = null,
    /** Passcodes of the cards that decided it. */
    val keyCards: List<Int> = emptyList(),
    val minutes: Int? = null,
    val note: String = "",
    /** The event this game was played at, when it was a real round. */
    val eventId: String? = null,
    val round: Int? = null,
    /**
     * The deck's print as it was played (`Ledger.fingerprint`; 2026-10, recorded from Phase G's first release so a deck's
     * versions can be told apart later); null for a game logged before, or with no deck.
     */
    val deckPrint: String? = null,
    /** Who the game was against: [SOURCE_PERSON], [SOURCE_AI] (Ai held a seat), [SOURCE_SELF] (both seats one person's); null before. */
    val source: String? = null,
    /**
     * The opponent's list as it was, its distinct Main and Extra Deck cards by card (Phase G, G.8: recorded when logged), so
     * a re-imported list or a new web still finds its games ([OpponentMatch]); null before, or for an opponent with no list.
     */
    val opponentCards: List<Int>? = null,
    /** The opening hand by passcode, when the game came from the Duel page (its replay's deal); null otherwise. */
    val opening: List<Int>? = null,
) {
    val postSide: Boolean get() = game > 1

    companion object {
        const val FIRST = "FIRST"
        const val SECOND = "SECOND"
        const val WIN = "W"
        const val LOSS = "L"
        const val DRAW = "D"
        const val REASON_BRICK = "BRICK"
        const val REASON_INTERRUPTED = "INTERRUPTED"
        const val REASON_OUTPLAYED = "OUTPLAYED"
        const val REASON_TIME = "TIME"
        const val REASON_OTHER = "OTHER"
        const val SOURCE_PERSON = "person"
        const val SOURCE_AI = "ai"
        const val SOURCE_SELF = "self"
        /** A Lounge game ([MatchupLedger]: read off the Lounge's records, never stored on a game). */
        const val SOURCE_LOUNGE = "lounge"
        /** An Ai vs Ai game ([MatchupLedger]: read off its record, never stored on a game). */
        const val SOURCE_AI_VS_AI = "ai-vs-ai"
    }
}

/**
 * What a Konami decklist asks for about you, kept once so every sheet is filled
 * in. Your CARD GAME ID is the ten-digit number NEURON shows.
 */
@Serializable
data class PrepProfile(
    val name: String = "",
    val cardGameId: String = "",
    val country: String = "",
)

/**
 * Tournament prep, whole: events, the test games log, who you are, and how each
 * siding drill is going. One JSON document in the preferences table under
 * [KEY] — a row, not a schema change, so the database stays at version 3.
 */
@Serializable
data class PrepDoc(
    val events: List<PrepEvent> = emptyList(),
    val games: List<TestGame> = emptyList(),
    val profile: PrepProfile = PrepProfile(),
    /** Drill progress by `"<matchupId>:<FIRST|SECOND>"` ([Drill.next]). */
    val drills: Map<String, DrillStat> = emptyMap(),
    /** The event being prepared for. */
    val active: String? = null,
    /**
     * The ledger's sources counted beside the people's games (Phase G, G.8; [MatchupLedger]): any of [TestGame.SOURCE_AI],
     * [TestGame.SOURCE_SELF], [TestGame.SOURCE_LOUNGE]'s Ai seats and [TestGame.SOURCE_AI_VS_AI]. Empty: people only.
     */
    val sources: List<String> = emptyList(),
    /** Games against an earlier list of the same strategy count for today's ([OpponentMatch]). */
    val earlier: Boolean = true,
) {
    fun event(id: String?): PrepEvent? = id?.let { wanted -> events.firstOrNull { it.id == wanted } }

    val activeEvent: PrepEvent? get() = event(active)

    /** [event] in place of the one with its id, else added; events stay in date order. */
    fun put(event: PrepEvent): PrepDoc {
        val others = events.filterNot { it.id == event.id }
        return copy(events = (others + event).sortedBy { it.date })
    }

    /** Without the event [id]; its games stay, since they are still test games. */
    fun removeEvent(id: String): PrepDoc = copy(
        events = events.filterNot { it.id == id },
        active = active.takeUnless { it == id },
    )

    /** [game] logged, or put in place of the one with its id. Newest last. */
    fun record(game: TestGame): PrepDoc {
        val others = games.filterNot { it.id == game.id }
        return copy(games = (others + game).sortedBy { it.at })
    }

    fun removeGame(id: String): PrepDoc = copy(games = games.filterNot { it.id == id })

    companion object {
        const val KEY = "neue.prep"
        val EMPTY = PrepDoc()
    }
}

/**
 * [PrepDoc] to and from its stored JSON. Reading forgives: unknown keys from a
 * newer build are skipped, a null where a default stands takes the default, and
 * anything that is not a document at all reads as an empty one — a broken row
 * must never keep the page from opening.
 */
object PrepCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun encode(doc: PrepDoc): String = json.encodeToString(PrepDoc.serializer(), doc)

    fun decode(text: String?): PrepDoc {
        if (text.isNullOrBlank()) return PrepDoc.EMPTY
        return try {
            json.decodeFromString(PrepDoc.serializer(), text)
        } catch (e: Exception) {
            PrepDoc.EMPTY
        }
    }
}
