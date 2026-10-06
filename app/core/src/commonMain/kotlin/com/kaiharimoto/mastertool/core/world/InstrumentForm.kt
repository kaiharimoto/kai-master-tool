package com.kaiharimoto.mastertool.core.world

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What kind of field an instrument's argument is drawn as (§3, the Instruments app). */
enum class FieldKind {
    /** The library's decks, the open deck by default. */
    DECK,

    /** A condition, one line: `Starters>=1 & Hand traps>=1`. */
    CONDITION,

    /** Conditions, one a line. */
    CONDITIONS,

    /** A card's name. */
    CARD,

    /** A group's name. */
    GROUP,

    /** Cards, one a line: `2 Called by the Grave`. */
    CARDS,

    /** A whole number in [FormField.min]..[FormField.max]. */
    NUMBER,

    /** One of [FormField.options]. */
    CHOICE,

    /** Several of [FormField.options]. */
    CHOICES,

    /** On or off. */
    SWITCH,

    /** A JSON object or list, for the arguments whose shape is a map or a list: groups, roles, shares, combos. */
    JSON,

    /** A number, or a range `40-42`, or a list `1, 1, 2`. */
    NUMBERS,
}

/** One argument of an instrument as a field: [name] is the argument's key; [hint] what it takes, in the spec's words. */
data class FormField(
    val name: String,
    val label: String,
    val kind: FieldKind,
    val hint: String = "",
    val default: String = "",
    val min: Int = 0,
    val max: Int = Int.MAX_VALUE,
    val options: List<String> = emptyList(),
)

/**
 * An instrument as a form (§3's Instruments app, `InstrumentFormTest`): every argument its [Instruments.Spec] declares,
 * a field of the right kind, filled with its default; [args] turns what the person typed into the JSON the instrument
 * takes, a blank field left out so the instrument's own default holds.
 */
data class InstrumentForm(val instrument: String, val question: String, val fields: List<FormField>) {
    /** The arguments from [values] (field name → what was typed); a field that will not read says so. */
    fun args(values: Map<String, String>): Result<JsonObject> = runCatching {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in fields) {
            val raw = values[f.name]?.trim().orEmpty()
            if (raw.isEmpty()) continue
            out[f.name] = when (f.kind) {
                FieldKind.DECK, FieldKind.CONDITION, FieldKind.CARD, FieldKind.GROUP, FieldKind.CHOICE -> JsonPrimitive(raw)
                FieldKind.CONDITIONS, FieldKind.CARDS -> JsonArray(raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
                FieldKind.CHOICES -> JsonArray(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
                FieldKind.SWITCH -> JsonPrimitive(raw.lowercase() in setOf("true", "yes", "on", "1"))
                FieldKind.NUMBER -> {
                    val n = raw.toLongOrNull() ?: throw IllegalArgumentException("${f.label}: a whole number")
                    require(n in f.min..f.max) { "${f.label}: from ${f.min} to ${f.max}" }
                    JsonPrimitive(n)
                }
                FieldKind.NUMBERS -> numbers(f, raw)
                FieldKind.JSON -> try {
                    WorldCodec.json.parseToJsonElement(raw).also { require(it is JsonObject || it is JsonArray) }
                } catch (e: Exception) {
                    throw IllegalArgumentException("${f.label}: JSON, like ${f.hint.substringBefore(';').ifEmpty { "{\"a\": 1}" }}")
                }
            }
        }
        JsonObject(out)
    }

    private fun numbers(f: FormField, raw: String): JsonElement {
        raw.toDoubleOrNull()?.let { return JsonPrimitive(it.toLong()) }
        Regex("""^(\d+)\s*[-–]\s*(\d+)$""").find(raw)?.let { m -> return JsonArray(listOf(JsonPrimitive(m.groupValues[1].toLong()), JsonPrimitive(m.groupValues[2].toLong()))) }
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val ns = parts.map { it.toLongOrNull() ?: throw IllegalArgumentException("${f.label}: a number, a range like 40-42, or a list like 1, 1, 2") }
        return JsonArray(ns.map(::JsonPrimitive))
    }

    companion object {
        private val deck = FormField("deck", "Deck", FieldKind.DECK, "the open deck by default")
        private val groups = FormField("groups", "Groups", FieldKind.JSON, "{\"Starters\": [\"Card A\", \"Card B\"]}; the deck's own groups by default")
        private val conditions = FormField("conditions", "Conditions", FieldKind.CONDITIONS, "one a line: Starters>=1 & Hand traps>=1; one per group by default")

        val ALL: List<InstrumentForm> = listOf(
            InstrumentForm(
                "openings", "How often does the deck open what it needs?",
                listOf(
                    deck, conditions, groups,
                    FormField("goal", "Break down", FieldKind.NUMBER, "which condition, from 1", "1", 1, 50),
                    FormField("trials", "Trials", FieldKind.NUMBER, "simulated hands each way; 0 for exact only", "50000", 0, 1_000_000),
                    FormField("seed", "Seed", FieldKind.NUMBER, "the same seed, the same hands", "1", 0, Int.MAX_VALUE),
                    FormField("samples", "Sample hands", FieldKind.NUMBER, "hands shown", "3", 0, 6),
                ),
            ),
            InstrumentForm(
                "ratios", "What is a card's or a group's copies worth?",
                listOf(
                    deck,
                    FormField("condition", "Condition", FieldKind.CONDITION, "Starters>=1"),
                    FormField("card", "Card", FieldKind.CARD, "a Main Deck card, swept 0 to 3 copies"),
                    FormField("group", "Or a group", FieldKind.GROUP, "swept around its count"),
                    FormField("grow", "Or blanks to add", FieldKind.NUMBER, "the deck growing", "", 0, 20),
                    FormField("from", "From", FieldKind.NUMBER, "copies or count", "", 0, 60),
                    FormField("to", "To", FieldKind.NUMBER, "copies or count", "", 0, 60),
                    FormField("cut", "Cut", FieldKind.CARD, "a card or group that makes room"),
                    FormField("also", "Also watch", FieldKind.CONDITION, "a second condition"),
                    FormField("keep_size", "Keep the size", FieldKind.SWITCH, "", "true"),
                    groups,
                ),
            ),
            InstrumentForm(
                "optimize", "Which role counts open best?",
                listOf(
                    FormField("roles", "Roles", FieldKind.JSON, "{\"Starters\": [8, 15], \"Hand traps\": [9, 12], \"Bricks\": 2}"),
                    FormField("goal", "Goal", FieldKind.CONDITION, "a condition over the roles"),
                    FormField("versus", "Versus", FieldKind.CONDITION, "a second goal: the frontier between the two"),
                    FormField("size", "Deck size", FieldKind.NUMBERS, "40, or 40-42", "40"),
                    FormField("turn", "Turn", FieldKind.CHOICE, "", "both", options = listOf("first", "second", "both")),
                    FormField("top", "Rows", FieldKind.NUMBER, "", "10", 1, 100),
                    deck,
                ),
            ),
            InstrumentForm(
                "draws", "How likely is it seen by turn N?",
                listOf(
                    deck,
                    FormField("target", "Card or group", FieldKind.CARD, "at least one"),
                    conditions.copy(hint = "or conditions, one a line"),
                    FormField("turns", "Turns", FieldKind.NUMBER, "", "5", 1, 20),
                    FormField("extra", "Extra seen", FieldKind.NUMBERS, "beyond the draw each turn: a number, or a list per turn"),
                    groups,
                ),
            ),
            InstrumentForm(
                "combos", "How often does each combo open?",
                listOf(deck, FormField("combos", "Combos", FieldKind.JSON, "[{\"name\": \"…\", \"needs\": [\"Card A\", \"Starters\"]}]; the deck's saved combos by default"), groups),
            ),
            InstrumentForm(
                "siding", "What does a side plan do to the odds?",
                listOf(
                    deck,
                    FormField("out", "Out", FieldKind.CARDS, "one a line: 2 Called by the Grave"),
                    FormField("in", "In", FieldKind.CARDS, "one a line, from the Side Deck"),
                    conditions, groups,
                ),
            ),
            InstrumentForm(
                "card_web", "Who searches or summons whom?",
                listOf(deck, FormField("include", "Include", FieldKind.CHOICES, "", "main, extra", options = listOf("main", "extra", "side"))),
            ),
            InstrumentForm("composition", "What is the deck made of?", listOf(deck)),
            InstrumentForm(
                "matchups", "How do the logged games read?",
                listOf(
                    FormField("deck", "Deck", FieldKind.DECK, "whose games; all for every deck"),
                    FormField("shares", "Field", FieldKind.JSON, "{\"Snake-Eye\": 30, \"Tenpai\": 20}; the event's field by default"),
                ),
            ),
            InstrumentForm(
                "goldfish", "How often do the written effects reach an end board?",
                listOf(
                    deck,
                    FormField("target", "Target", FieldKind.CONDITION, "a kept target's name (fx_target names one)"),
                    FormField("going", "Going", FieldKind.CHOICE, "", "first", options = listOf("first", "second")),
                    FormField("hands", "Hands", FieldKind.NUMBER, "dealt from the seed", "2000", 1, 20_000),
                    FormField("seed", "Seed", FieldKind.NUMBER, "the same seed, the same hands", "1", 0, Int.MAX_VALUE),
                    FormField("budget", "Budget", FieldKind.NUMBER, "engine moves a hand", "20000", 100, 1_000_000),
                    FormField("combo", "This line", FieldKind.CONDITION, "a saved combo's name: that line only; empty to search"),
                ),
            ),
        )

        fun of(name: String): InstrumentForm? = ALL.firstOrNull { it.instrument == name }

        /** The argument names [spec] declares, read off its words (`InstrumentFormTest` holds every one to a field). */
        fun declared(spec: Instruments.Spec): Set<String> {
            val names = LinkedHashSet<String>()
            var depth = 0
            val clause = StringBuilder()
            fun flush() {
                val c = clause.toString().trim()
                clause.clear()
                c.split(" or ").forEach { alt -> alt.trim().split(" and ").forEach { w -> Regex("^[a-z_]+$").find(w.trim())?.let { names += it.value } } }
            }
            for (ch in spec.args) {
                when (ch) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) {
                        flush()
                        continue
                    }
                }
                if (depth == 0 && ch !in "()[]{}") clause.append(ch)
            }
            flush()
            return names
        }
    }
}
