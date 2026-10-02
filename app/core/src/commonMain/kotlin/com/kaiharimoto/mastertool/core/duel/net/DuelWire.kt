package com.kaiharimoto.mastertool.core.duel.net

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.LenientAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Two players over a network (1.0.77), as messages. The host's app holds the duel — the log, the seed,
 * both decks — and is the only one that rolls a die or shuffles; the guest's app holds only what its
 * seat may see: after every move the host sends it its own [DuelView] (hidden cards as veils) and the
 * new lines of the log as that seat would read them. The guest asks; the host decides.
 *
 * The same messages will run over a relay (R5), the relay taking the host's part.
 */
@Serializable
sealed class Wire {
    // ---- guest to host ------------------------------------------------------------------------------

    /** The guest arrives: who it is, its deck, the code that lets it in, and how it wants response windows. */
    @Serializable @SerialName("hello")
    data class Hello(
        val proto: Int = PROTO,
        val name: String = "",
        val main: List<Int> = emptyList(),
        val extra: List<Int> = emptyList(),
        val deckName: String = "",
        val secret: Int = 0,
        /** A seat token from an earlier connection: coming back after a drop. */
        val token: String? = null,
        val windows: String = Windows.ACTIVATIONS,
    ) : Wire()

    /** Moves the guest wants made, as one group, cards named by the refs in its view. */
    @Serializable @SerialName("intent")
    data class Intent(val seq: Int, val actions: List<@Serializable(with = LenientAction::class) DuelAction>, val force: Boolean = false) : Wire()

    /** Asks to take back the last move (the other player decides), or answers such a request. */
    @Serializable @SerialName("takeback")
    data class TakeBack(val ask: Boolean = true, val yes: Boolean = false) : Wire()

    /** The guest's own setting for response windows changed. */
    @Serializable @SerialName("windows")
    data class SetWindows(val windows: String) : Wire()

    @Serializable @SerialName("bye")
    data object Bye : Wire()

    // ---- host to guest ------------------------------------------------------------------------------

    @Serializable @SerialName("welcome")
    data class Welcome(val seat: Int, val token: String, val hostName: String = "", val proto: Int = PROTO) : Wire()

    /** The table as the guest's seat sees it now, the log lines it has not had, and whose response is awaited. */
    @Serializable @SerialName("update")
    data class Update(
        val cursor: Int,
        val view: DuelView,
        val lines: List<Line> = emptyList(),
        val waitingFor: Int? = null,
        val takeBackFrom: Int? = null,
    ) : Wire()

    /** The guest's intent [seq] was not made, and why. */
    @Serializable @SerialName("refused")
    data class Refused(val seq: Int, val reason: String) : Wire()

    /** The host will not have this guest (a wrong code, a full table, another version). */
    @Serializable @SerialName("rejected")
    data class Rejected(val reason: String) : Wire()

    companion object {
        /** Bumped when a message changes shape so an old app cannot read it. */
        const val PROTO = 1
    }
}

/** One line of the log, as one seat reads it. */
@Serializable
data class Line(val i: Int, val text: String, val seat: Int? = null, val chat: Boolean = false, val turn: Int = 0)

/**
 * Each player's response windows (kai: "optional response windows"): whether the other player's
 * activations, summons as well, or every move wait for this player to respond or pass.
 */
object Windows {
    const val OFF = "off"
    const val ACTIVATIONS = "activations"
    const val SUMMONS = "summons"
    const val ALWAYS = "always"
    val ALL = listOf(OFF, ACTIVATIONS, SUMMONS, ALWAYS)

    /** Whether a group of moves opens a window for a player whose setting is [setting]. */
    fun opens(actions: List<DuelAction>, setting: String): Boolean {
        if (setting == OFF) return false
        val table = actions.filterNot { it.social }
        if (table.isEmpty()) return false
        if (setting == ALWAYS) return true
        val activation = table.any { it is DuelAction.ChainAdd }
        val summon = table.any { it is DuelAction.Move && (it.how == "normal" || it.how == "special" || it.how == "tribute") } ||
            table.any { it is DuelAction.Token }
        return activation || (setting == SUMMONS && summon)
    }
}

object WireCodec {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        classDiscriminator = "t"
    }

    fun encode(w: Wire): String = json.encodeToString(Wire.serializer(), w)
    fun decode(text: String): Wire? = runCatching { json.decodeFromString(Wire.serializer(), text) }.getOrNull()
}
