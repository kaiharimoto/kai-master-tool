package com.kaiharimoto.mastertool.core.sync

import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.UiPreferences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Where this device syncs to (1.0.68): a field of [NeuePreferences] with a default, never synced
 * itself. Passwords and tokens are never here — they are in the device's `SecretStore`.
 */
@Serializable
data class SyncPrefs(
    /** One of [SERVICES]; [OFF] syncs nothing. */
    val service: String = OFF,
    /** A folder on the computer, or on Android a folder the system's picker granted (a `content://` tree). */
    val folder: String? = null,
    /** A WebDAV folder's address, and who signs in to it. */
    val webdavUrl: String = "",
    val webdavUser: String = "",
    /** The account a sign-in service is signed in as, shown under its name. */
    val account: String = "",
    /** What the other devices call this one; empty is the system's own name. */
    val deviceName: String = "",
    /** Sync on opening, after saving and every few minutes; off, only when asked. */
    val auto: Boolean = true,
) {
    companion object {
        const val OFF = "off"
        const val FOLDER = "folder"
        const val WEBDAV = "webdav"
        const val GOOGLE_DRIVE = "gdrive"
        val SERVICES = listOf(OFF, FOLDER, WEBDAV, GOOGLE_DRIVE)
    }
}

/**
 * The settings that travel (1.0.68) — what is about the person, not the machine. The look, how the
 * deck is laid out, the default deck, covers, chosen artworks and card lists go to every device; the
 * window, pane widths, zoom, orientation, text size and what is open stay where they are. Every field
 * is in one list or the other, held by `SyncedPrefsTest`, so a new setting cannot slip through unsorted.
 */
object SyncedPrefs {
    const val PATH = "prefs/neue.json"
    const val FORMAT_PATH = "prefs/format.json"

    val SYNCED = setOf(
        "theme", "foil", "foilNames", "limitMarks", "contrast", "groupPalette", "groupArrangement", "slidesAutoplay",
        "autoSaveOn", "shotStyle", "sidingView", "sidingExtra", "autoZen", "zenLabels", "poolToSide",
        "defaultDeckId", "covers", "arts", "cardLists", "activeList", "ai", "present", "duel",
    )

    val DEVICE = setOf(
        "scale", "poolVisible", "inspectorVisible", "poolWidth", "inspectorWidth", "poolColumns", "filtersOpen",
        "sound", "railPinned", "hdArt", "lensKeys", "groupsPanel", "inspectorFolded", "window", "extraSideVisible",
        "extraVisible", "sideVisible", "deckZoom", "groupGap", "touchIntroSeen", "poolList", "textScale",
        "orientation", "phoneDockStop", "foilTilt", "sync", "start",
    )

    /** Ai's settings that travel; its connections are this device's (their keys never leave it). */
    val AI_SYNCED = setOf("enabled", "name", "effort", "alwaysAllow", "showReasoning", "tuneIntensity", "speakReplies", "speechRate", "factCheck")
    val AI_DEVICE = setOf("connections", "active", "panelOpen", "panelWidth", "introSeen", "voiceModel")

    /** From the builder's shared document, only what is about the deck. */
    val FORMAT_SYNCED = setOf("format", "searchEffects")

    fun extract(prefs: NeuePreferences): ByteArray {
        val all = encode(NeuePreferences.serializer(), prefs)
        val out = all.filterKeys { it in SYNCED }.toMutableMap()
        all["ai"]?.jsonObject?.let { ai -> out["ai"] = JsonObject(ai.filterKeys { it in AI_SYNCED }) }
        return bytes(JsonObject(out))
    }

    fun apply(prefs: NeuePreferences, synced: ByteArray): NeuePreferences {
        val incoming = runCatching { Sync.json.parseToJsonElement(synced.decodeToString()).jsonObject }.getOrNull() ?: return prefs
        val all = encode(NeuePreferences.serializer(), prefs).toMutableMap()
        incoming.forEach { (k, v) ->
            when {
                k == "ai" -> {
                    val mine = all["ai"]?.jsonObject.orEmpty().toMutableMap()
                    (v as? JsonObject)?.forEach { (ak, av) -> if (ak in AI_SYNCED) mine[ak] = av }
                    all["ai"] = JsonObject(mine)
                }
                k in SYNCED -> all[k] = v
            }
        }
        return runCatching { Sync.json.decodeFromJsonElement(NeuePreferences.serializer(), JsonObject(all)).sanitised() }.getOrElse { prefs }
    }

    fun extractFormat(ui: UiPreferences): ByteArray = bytes(JsonObject(encode(UiPreferences.serializer(), ui).filterKeys { it in FORMAT_SYNCED }))

    fun applyFormat(ui: UiPreferences, synced: ByteArray): UiPreferences {
        val incoming = runCatching { Sync.json.parseToJsonElement(synced.decodeToString()).jsonObject }.getOrNull() ?: return ui
        val all = encode(UiPreferences.serializer(), ui).toMutableMap()
        incoming.forEach { (k, v) -> if (k in FORMAT_SYNCED) all[k] = v }
        return runCatching { Sync.json.decodeFromJsonElement(UiPreferences.serializer(), JsonObject(all)) }.getOrElse { ui }
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject = Sync.json.encodeToJsonElement(serializer, value).jsonObject

    /** Keys in order, so the same settings are always the same bytes and the same hash. */
    private fun bytes(o: JsonObject): ByteArray = Sync.json.encodeToString(JsonObject.serializer(), JsonObject(o.toSortedMap())).encodeToByteArray()

    /** The field names of [AiPrefs], for the test. */
    val aiFields: List<String> get() = (0 until AiPrefs.serializer().descriptor.elementsCount).map { AiPrefs.serializer().descriptor.getElementName(it) }
    val fields: List<String> get() = (0 until NeuePreferences.serializer().descriptor.elementsCount).map { NeuePreferences.serializer().descriptor.getElementName(it) }
}
