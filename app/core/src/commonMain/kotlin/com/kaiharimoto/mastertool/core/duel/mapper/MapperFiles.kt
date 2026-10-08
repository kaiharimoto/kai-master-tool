package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * A deck's mapper files (M.md §7): under `<data>/effects/mapper/<deck>/` — the board libraries, the runs' counts, the starter
 * tables and the presets — synced newer wins, backed up, and deleted with the deck. `train/` beside them (records,
 * checkpoints, the trainer's logs) never travels: it is large and remade from the library.
 */

object MapperPaths {
    /** The folder under `effects/`. */
    const val DIR = "mapper"

    /** A deck's training folder: never synced or backed up. */
    const val TRAIN = "train"

    /** [deckId]'s folder, under `effects/`. */
    fun deck(deckId: String): String = "$DIR/${AiMemory.safeId(deckId).ifBlank { "deck" }}"

    /** The board library going first or second (`BoardLibrary`). */
    fun library(deckId: String, first: Boolean): String = "${deck(deckId)}/${side("library", first)}"

    /** The last run's counts going first or second ([MapperRun]). */
    fun run(deckId: String, first: Boolean): String = "${deck(deckId)}/${side("run", first)}"

    /** The starter table going first or second ([StarterRun]). */
    fun starters(deckId: String, first: Boolean): String = "${deck(deckId)}/${side("starters", first)}"

    /** The deck's saved presets ([MapperPresets]). */
    fun presets(deckId: String): String = "${deck(deckId)}/presets.json"

    private fun side(name: String, first: Boolean) = if (first) "$name.json" else "$name-2nd.json"

    /** Whether [rel] (under `effects/`) is a mapper file that travels: `mapper/<deck>/<name>.json`, never `train/`. */
    fun syncs(rel: String): Boolean {
        val p = rel.split('/')
        return p.size == 3 && p[0] == DIR && p[1].isNotEmpty() && !p[1].startsWith(".") && p[2].endsWith(".json") && !p[2].startsWith(".")
    }
}

/**
 * A starter table as it is kept (`starters.json`, `starters-2nd.json`): its rows and what it was run with. [deck] and
 * [library] are the deck's fingerprint and the trusted scripts' when it ran: either moving makes it [stale].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StarterRun(
    val version: Int = 1,
    val deckId: String = "",
    val deck: String = "",
    val library: String = "",
    val first: Boolean = true,
    val seed: Long = 1L,
    val budget: Int = 0,
    val pairs: Boolean = true,
    val rows: List<StarterTable.Row> = emptyList(),
    val moves: Long = 0L,
    val ms: Long = 0L,
    val at: Long = 0L,
    /** Stopped before every starter was mapped: the starters left out have no row. */
    val stopped: Boolean = false,
    /** The [BoardKey.VERSION] the rows' boards are keyed by. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val keys: Int = BoardKey.VERSION,
) {
    /** Whether the table describes another deck or other scripts than [deck] and [library]. */
    fun stale(deck: String, library: String): Boolean = deck != this.deck || library != this.library || keys != BoardKey.VERSION

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Read forgivingly; an unreadable file is null. */
        fun decode(text: String): StarterRun? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** A deck's saved presets (`presets.json`): the person's and Ai's, by id, and which one the page last chose. */
@Serializable
data class MapperPresets(
    val version: Int = 1,
    val presets: List<BoardPreset> = emptyList(),
    val chosen: String = "",
) {
    /** [p] saved, replacing one of its id. */
    fun put(p: BoardPreset): MapperPresets = copy(presets = presets.filterNot { it.id == p.id } + p)

    fun remove(id: String): MapperPresets = copy(presets = presets.filterNot { it.id == id }, chosen = chosen.takeUnless { it == id }.orEmpty())

    /** [BoardPreset.DEFAULT] then the saved ones. */
    val all: List<BoardPreset> get() = listOf(BoardPreset.DEFAULT) + presets.filterNot { it.id == BoardPreset.DEFAULT.id }

    fun byId(id: String): BoardPreset? = all.firstOrNull { it.id == id }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Read forgivingly; an unreadable file is null. */
        fun decode(text: String): MapperPresets? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}
