package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Every setting of the app, as Ai reads and changes it (`get_settings`,
 * `set_setting`): the [NeuePreferences] document by its JSON keys, `ai.…` for the
 * assistant's own, plus the two settings the builder keeps elsewhere ([FORMAT],
 * [SEARCH_EFFECTS]). A key is described here or listed in [INTERNAL]; the test
 * holds every field of the document to that, so a setting added later is either
 * reachable by Ai or deliberately not.
 */
object AiSettings {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = true }

    const val FORMAT = "format"
    const val SEARCH_EFFECTS = "searchEffects"

    /** What each setting does, in the words Ai reads. */
    val described: Map<String, String> = linkedMapOf(
        "theme" to "PAPER (light) or INK (dark).",
        "scale" to "Interface scale: 0.875, 1, 1.125, 1.25, 1.5, 1.75 or 2.",
        "textScale" to "Text size apart from the interface: 1, 1.15 or 1.3; null is the device's default.",
        "contrast" to "\"standard\" or \"high\" (darker greys and lines).",
        "poolVisible" to "The card pool beside the deck is shown.",
        "inspectorVisible" to "The inspector (the card read large) is shown.",
        "poolWidth" to "The pool's width in dp (280–960).",
        "inspectorWidth" to "The inspector's width in dp (320–640).",
        "poolColumns" to "Cards per row in the pool (2–12); 0 fits them to the pane.",
        "filtersOpen" to "The pool's filters are open.",
        "foil" to "The foil on card faces: \"holo\", \"classic\" or \"off\".",
        "foilNames" to "How card names are drawn: \"foil\", \"outline\" or \"printed\".",
        "foilTilt" to "On a phone or tablet, the foil follows the device's tilt.",
        "sound" to "Sounds on.",
        "railPinned" to "The page rail stays out instead of folding away.",
        "hdArt" to "Download every card's full-size artwork in the background (about 2 GB).",
        "groupsPanel" to "The Groups panel beside the deck is open.",
        "extraVisible" to "The extra deck is shown under the main deck.",
        "sideVisible" to "The side deck is shown under the main deck.",
        "groupPalette" to "The palette groups are coloured from: prism, bauhaus, pastel, earth, ocean, neon or vintage.",
        "deckZoom" to "How large the deck is drawn, as a share of the size that fills its column (0.4–1).",
        "groupGap" to "Gap between groups, as a multiple of the standard gap (0.4–3).",
        "groupArrangement" to "How groups lay out: AS_IS, FITTED or SEPARATE.",
        "slidesAutoplay" to "The Groups column's slides turn by themselves.",
        "autoSaveOn" to "Save the deck by itself a moment after every change.",
        "shotStyle" to "The deck's screenshot: \"picture\" or \"list\".",
        "sidingExtra" to "Siding shows the Extra Deck to side out from (offered only when the Side Deck holds Extra Deck cards).",
        "sidingView" to "How Siding and its PDF guide show the cards a plan moves: \"art\" (a picture per copy) or \"list\" (names and counts).",
        "autoZen" to "In immersive mode, zen comes by itself after idle seconds.",
        "zenLabels" to "Each group's name is written on its piece in zen.",
        "defaultDeckId" to "The deck the builder opens with, by id; null opens the last saved.",
        "covers" to "The cards each deck is shown by in the library: {deckId: [passcodes, up to 3]}.",
        "arts" to "The artwork chosen for a card: {passcode: alternate passcode}.",
        "poolToSide" to "Right-click and Enter in the pool add to the side deck instead of the main.",
        "cardLists" to "Lists of cards kept for consideration: [{id, name, ids: [passcodes]}].",
        "poolList" to "The list the pool shows instead of every card, by id; null shows every card.",
        "activeList" to "The list a card goes onto with L, by id.",
        "orientation" to "Which way a phone's or tablet's screen may turn: portrait, landscape or auto; null is the device's default.",
        "phoneDockStop" to "Where the phone's pool dock rests: PEEK, HALF or FULL.",
        "ai.enabled" to "The assistant is on. Off hides every trace of it (and ends this conversation).",
        "ai.name" to "The assistant's name.",
        "ai.active" to "The connection in use, by id (see ai.connections in get_settings).",
        "ai.effort" to "How hard the model thinks where it can be told: \"\", low, medium, high, xhigh, max.",
        "ai.alwaysAllow" to "Deleting decks and webs runs without asking the person first.",
        "ai.panelOpen" to "The assistant's panel is open.",
        "ai.panelWidth" to "The assistant panel's width in dp (320–720).",
        "ai.tuneIntensity" to "How long and hard Fine Tuning goes: quick, standard or deep.",
        "ai.showReasoning" to "How the model's thinking shows in the chat: folded (first lines), open, or hidden.",
        "ai.voiceModel" to "The speech model the desktop transcribes the microphone with, on the computer: tiny.en (fast), base.en (standard), small.en (accurate) or base (any language).",
        "ai.speakReplies" to "Whether replies are spoken aloud: talk (in talk mode) or never.",
        "ai.speechRate" to "How fast replies are spoken aloud, 0.5 to 2; 1 is the voice's own pace.",
        "ai.factCheck" to "Whether each answer's claims about cards, rulings and numbers are checked against the card text once written, and corrected if wrong.",
        FORMAT to "The banlist the builder checks against: TCG or OCG.",
        SEARCH_EFFECTS to "Card searches read the printed text as well as names.",
    )

    /**
     * Keys Ai may read but not set, or not see: where the window was, the tablet's
     * first-run note, fields read by nothing since older releases, and the saved
     * connections (Ai switches between them with ai.active, never edits them), and where
     * the device syncs to (set up by the person in Settings, never by Ai).
     */
    val INTERNAL = setOf(
        "window", "touchIntroSeen", "lensKeys", "extraSideVisible", "inspectorFolded",
        "ai.connections", "ai.introSeen", "sync", "start", "present",
    )

    /** The settings as Ai reads them: each key's value, then what it does. */
    fun describe(prefs: NeuePreferences, format: String, searchEffects: Boolean): JsonObject {
        val flat = flatten(prefs)
        return buildJsonObject {
            described.forEach { (key, words) ->
                val value = when (key) {
                    FORMAT -> JsonPrimitive(format)
                    SEARCH_EFFECTS -> JsonPrimitive(searchEffects)
                    else -> flat[key] ?: return@forEach
                }
                put(key, buildJsonObject {
                    put("value", value)
                    put("means", words)
                })
            }
            // The connections by id and provider, never more, so ai.active can name one.
            put("ai.connections", JsonPrimitive(prefs.ai.connections.joinToString { "${it.id} (${it.provider}${it.model.takeIf(String::isNotBlank)?.let { m -> ", $m" } ?: ""})" }))
        }
    }

    /** The document's keys as `a` and `a.b`, values as JSON. */
    fun flatten(prefs: NeuePreferences): Map<String, JsonElement> {
        val root = json.encodeToJsonElement(NeuePreferences.serializer(), prefs).jsonObject
        return buildMap {
            root.forEach { (k, v) ->
                if (k == "ai" && v is JsonObject) v.forEach { (k2, v2) -> put("ai.$k2", v2) } else put(k, v)
            }
        }
    }

    /**
     * [prefs] with [key] set to [value], sanitised, or why not. [FORMAT] and
     * [SEARCH_EFFECTS] are not in the document; the app sets those itself.
     */
    fun set(prefs: NeuePreferences, key: String, value: JsonElement): Result<NeuePreferences> = runCatching {
        require(key in described && key != FORMAT && key != SEARCH_EFFECTS) {
            if (key in INTERNAL) "$key is not a setting Ai changes." else "There is no setting $key. get_settings lists them."
        }
        val root = json.encodeToJsonElement(NeuePreferences.serializer(), prefs).jsonObject.toMutableMap()
        val coerced = coerce(root, key, value)
        if (key.startsWith("ai.")) {
            val ai = (root["ai"] as JsonObject).toMutableMap()
            ai[key.removePrefix("ai.")] = coerced
            root["ai"] = JsonObject(ai)
        } else {
            root[key] = coerced
        }
        val next = json.decodeFromJsonElement(NeuePreferences.serializer(), JsonObject(root)).sanitised()
        next
    }.recoverCatching { e ->
        throw IllegalArgumentException(e.message?.takeIf { it.length < 300 } ?: "That value does not fit $key.")
    }

    /** A value in the shape the key already has: "true" for true, "1.25" for 1.25. */
    private fun coerce(root: Map<String, JsonElement>, key: String, value: JsonElement): JsonElement {
        val current = if (key.startsWith("ai.")) (root["ai"] as? JsonObject)?.get(key.removePrefix("ai.")) else root[key]
        val p = value as? JsonPrimitive ?: return value
        val cur = current as? JsonPrimitive ?: return value
        if (p.isString && !cur.isString) {
            val s = p.contentOrNull?.trim() ?: return value
            s.toBooleanStrictOrNull()?.let { return JsonPrimitive(it) }
            s.toLongOrNull()?.let { return JsonPrimitive(it) }
            s.toDoubleOrNull()?.let { return JsonPrimitive(it) }
            if (s == "null") return kotlinx.serialization.json.JsonNull
        }
        if (!p.isString && cur.isString && p !is kotlinx.serialization.json.JsonNull) return JsonPrimitive(p.content)
        return value
    }
}
