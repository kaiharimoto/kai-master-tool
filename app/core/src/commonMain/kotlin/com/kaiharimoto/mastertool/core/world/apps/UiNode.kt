package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** A choice in a select, a segmented control or a list of checks: what the app gets back ([value]) and what it shows. */
data class UiOption(val value: String, val label: String)

/** How a line of words reads (§8.4): body, muted (ink-45) or strong. */
enum class Tone { BODY, MUTED, STRONG }

/**
 * One widget of an app's screen (`docs/world/DESKTOP.md` §8.4), as [UiTree.parse] reads it from the plain data an app's
 * `view` returns. Every kind is a Neue component the kit already has, so an app looks like the app it lives in; there is
 * no colour, font, size or position anywhere — a key the model does not know is ignored, and a tree that asks for a colour
 * gets ink. [weight] is a child's share of a row or column, 1–12.
 */
sealed class UiNode {
    open val weight: Int? get() = null

    // ---- Layout ----
    data class Col(val children: List<UiNode>, val gap: Int = 2, override val weight: Int? = null) : UiNode()
    data class Row(val children: List<UiNode>, val gap: Int = 2, override val weight: Int? = null) : UiNode()
    data class Grid(val children: List<UiNode>, val columns: Int = 2, val gap: Int = 2, override val weight: Int? = null) : UiNode()

    /** A micro-caps title and a rule, then its children. */
    data class Section(val title: String, val children: List<UiNode>, override val weight: Int? = null) : UiNode()
    data class Divider(override val weight: Int? = null) : UiNode()

    /** Room, on the spacing scale `s1`–`s6`. */
    data class Space(val size: Int = 2, override val weight: Int? = null) : UiNode()

    // ---- Words ----
    data class Text(val text: String, val tone: Tone = Tone.BODY, val mono: Boolean = false, override val weight: Int? = null) : UiNode()

    /** Label and value rows. */
    data class Kv(val rows: List<Pair<String, String>>, override val weight: Int? = null) : UiNode()

    /** The World's stat: a big number and its label. */
    data class Stat(val value: String, val label: String = "", val note: String = "", override val weight: Int? = null) : UiNode()

    /** A hint line. */
    data class Note(val text: String, override val weight: Int? = null) : UiNode()

    /** The chat's markdown; only `world://` addresses and `[[Card]]` are links ([AppLinks]). */
    data class Markdown(val text: String, override val weight: Int? = null) : UiNode()

    // ---- Controls ----

    /** [primary] is the screen's one primary (a second is drawn subtle). [copy] and [open] are done by the desktop on the person's press. */
    data class Button(
        val id: String,
        val label: String,
        val primary: Boolean = false,
        val copy: String? = null,
        val open: String? = null,
        val disabled: Boolean = false,
        override val weight: Int? = null,
    ) : UiNode()

    /** A text or number field: committed with Enter or on leaving it; [live] sends as it is typed. Never a password field. */
    data class Input(
        val id: String,
        val label: String = "",
        val value: String = "",
        val number: Boolean = false,
        val placeholder: String = "",
        val min: Double? = null,
        val max: Double? = null,
        val live: Boolean = false,
        val hint: String = "",
        override val weight: Int? = null,
    ) : UiNode()

    data class Stepper(
        val id: String,
        val label: String = "",
        val value: Double = 0.0,
        val min: Double = 0.0,
        val max: Double = 100.0,
        val step: Double = 1.0,
        val hint: String = "",
        override val weight: Int? = null,
    ) : UiNode()

    data class Slider(
        val id: String,
        val label: String = "",
        val value: Double = 0.0,
        val min: Double = 0.0,
        val max: Double = 1.0,
        val step: Double = 0.0,
        val live: Boolean = false,
        val hint: String = "",
        override val weight: Int? = null,
    ) : UiNode()

    data class Select(val id: String, val label: String = "", val value: String? = null, val options: List<UiOption>, override val weight: Int? = null) : UiNode()

    /** Two to five choices side by side. */
    data class Segmented(val id: String, val label: String = "", val value: String? = null, val options: List<UiOption>, override val weight: Int? = null) : UiNode()
    data class Toggle(val id: String, val label: String = "", val value: Boolean = false, override val weight: Int? = null) : UiNode()

    /** Several of a list: [values] are the ones checked. */
    data class Checks(val id: String, val label: String = "", val options: List<UiOption>, val values: List<String> = emptyList(), override val weight: Int? = null) : UiNode()

    /** The pool's own search: the app gets a passcode. */
    data class CardPicker(val id: String, val label: String = "", val value: Int? = null, override val weight: Int? = null) : UiNode()

    /** The library's decks: the app gets a deck's id. */
    data class DeckPicker(val id: String, val label: String = "", val value: String? = null, override val weight: Int? = null) : UiNode()

    // ---- Data ----

    /** The World's table: rows past [AppLimits.ROWS] are counted in [more], paged by the window. A [pickable] row sends `pick`. */
    data class Table(
        val id: String? = null,
        val columns: List<String>,
        val rows: List<List<String>>,
        val more: Int = 0,
        val pickable: Boolean = false,
        /** A row's address, opened on the person's press (`open` per row, §8.4). */
        val open: List<String?> = emptyList(),
        override val weight: Int? = null,
        /** The columns whose cells are cards' names (`cards: [0]` or the columns' names): drawn with the card's art. Never guessed. */
        val cardColumns: List<Int> = emptyList(),
    ) : UiNode()

    /**
     * One card, by name or passcode, as its art (`ui.card('Ash Blossom & Joyous Spring', { size: 'large' })`): [large] a
     * card a reader can study, else a small one beside its [label] (the card's own name when blank). A pointer on it reads
     * it in the inspector; a click opens it large. [pickable] sends `pick` with the card.
     */
    data class Card(
        val card: String,
        val large: Boolean = false,
        val label: String = "",
        val id: String? = null,
        val pickable: Boolean = false,
        override val weight: Int? = null,
    ) : UiNode()

    /** A strip of card art: passcodes or names. */
    data class Cards(val id: String? = null, val cards: List<String>, val pickable: Boolean = false, override val weight: Int? = null) : UiNode()

    /** A board's payload, checked by `ShowSpec.parse` and painted by `WorldPaint`, so a chart in an app is the same chart as on a page. */
    data class Board(val kind: BoardKind, val payload: String, val title: String = "", override val weight: Int? = null) : UiNode()

    /** The kit's meter, [value] 0 to 1. */
    data class Progress(val value: Double, val label: String = "", override val weight: Int? = null) : UiNode()

    /** The kit's empty state. */
    data class Empty(val text: String, override val weight: Int? = null) : UiNode()

    // ---- What will not read ----

    /** A node drawn in its place as one line saying why; the rest of the tree drawn (the boards' rule). */
    data class Broken(val kind: String, val why: String, override val weight: Int? = null) : UiNode()

    /** A widget from a newer build: kept, and drawn as "made by a newer version". */
    data class Unknown(val kind: String, override val weight: Int? = null) : UiNode()
}

/** A person's press, change or pick, sent to the app's `on(state, event)` (§8.3). */
@Serializable
data class UiEvent(val id: String, val type: String, val value: JsonElement = JsonNull, val seq: Long = 0L) {
    fun json(): String = WorldCodec.json.encodeToString(serializer(), this)

    companion object {
        const val PRESS = "press"
        const val CHANGE = "change"
        const val PICK = "pick"
    }
}

/** What an app's words may link to (§8.6 point 4): `world://` addresses that are real addresses; anything else is text. */
object AppLinks {
    fun allowed(url: String): Boolean =
        url.trim().startsWith(WorldAddress.SCHEME, ignoreCase = true) && WorldAddress.parse(url) !is WorldAddress.Unknown
}
