package com.kaiharimoto.mastertool.core.ai.course

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Every DuelingBook replay kept on this computer, in one place (1.1.51, kai: "have the replays saved and build a replay
 * library interface"): the replays the courses' chapters link to, read by the studies, and replays the person adds by
 * their address. Each is kept as DuelingBook sent it — the page's own response, read in the browser, never asked for by
 * the app ([DbReplays]) — so it is read, searched and studied again without opening DuelingBook.
 *
 * A replay a course holds out for its exam is in the library for the person, marked; Ai never reads one through it.
 */
object ReplayLibrary {
    const val DIR = "replays"

    /** The replays the person added: `replays/library.json`, each kept as `replays/<id>.json` (and in words, `.md`). */
    fun index(): String = "$DIR/library.json"
    fun raw(id: String): String = "$DIR/${AiMemory.safeId(id)}.json"
    fun text(id: String): String = "$DIR/${AiMemory.safeId(id)}.md"

    /** One replay in the library, wherever it was kept. */
    data class Entry(
        /** DuelingBook's id for it: the same duel is one entry, whoever kept it. */
        val id: String,
        val url: String,
        val players: String = "",
        val games: Int = 0,
        /** Where it was kept: a course's replay, or added by the person. */
        val course: String = "",
        val courseLabel: String = "",
        /** Its number in the course, and the chapter that links to it. */
        val n: Int = 0,
        val chapter: Int = 0,
        /** The study's state with it: read, noted, failed — or added. */
        val state: String = "",
        /** Held out for a course's exam: the person may read it; Ai never does through the library. */
        val heldOut: Boolean = false,
        val addedAt: Long = 0,
        val note: String = "",
    ) {
        val added: Boolean get() = course.isBlank()
        val label: String get() = players.ifBlank { "Replay $id" }
    }

    /** A replay the person added by its address. */
    @Serializable
    data class Added(val id: String, val url: String, val players: String = "", val games: Int = 0, val addedAt: Long = 0, val note: String = "")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    fun readIndex(text: String?): List<Added> = if (text.isNullOrBlank()) emptyList() else
        runCatching { json.decodeFromString(ListSerializer(Added.serializer()), text) }.getOrDefault(emptyList())

    fun writeIndex(added: List<Added>): String = json.encodeToString(ListSerializer(Added.serializer()), added)

    /**
     * The library: every course's replays that were read, and every replay added — one entry a duel, a course's first (it
     * knows its chapter); newest course first, then added ones newest first. A replay any course holds out is held out.
     */
    fun entries(courses: List<Course>, added: List<Added>): List<Entry> {
        val held = courses.flatMap { c -> c.replays.filter { it.exam }.mapNotNull { DbReplays.id(it.url) } }.toSet()
        val out = LinkedHashMap<String, Entry>()
        courses.sortedByDescending { it.updatedAt }.forEach { c ->
            c.replays.filter { it.state == Chapter.State.READ || it.state == Chapter.State.NOTED }.sortedBy { it.n }.forEach { r ->
                val id = DbReplays.id(r.url) ?: return@forEach
                if (id !in out) out[id] = Entry(id, r.url, r.players, r.games, c.id, c.label, r.n, r.chapter, r.state.name.lowercase(), heldOut = id in held)
            }
        }
        added.sortedByDescending { it.addedAt }.forEach { a ->
            if (a.id !in out) out[a.id] = Entry(a.id, a.url, a.players, a.games, state = "added", heldOut = a.id in held, addedAt = a.addedAt, note = a.note)
        }
        return out.values.toList()
    }

    /**
     * [entries] matching [query] — players, course, note, or (given [cards]) a card played in it; all when blank. Each
     * word of the query begins a word of the entry's ("ash" finds Ash Blossom, never Flash); a word of digits alone also
     * finds the replay by its id, whole or its start ("1" alone never finds most of the library inside their ids).
     */
    fun search(entries: List<Entry>, query: String, cards: (Entry) -> Collection<String> = { emptyList() }): List<Entry> {
        val words = norm(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return entries
        return entries.filter { e ->
            val hay = norm(listOf(e.players, e.courseLabel, e.note, if (e.chapter > 0) "ch ${e.chapter}" else "").joinToString(" ") + " " + cards(e).joinToString(" "))
                .split(' ').filter { it.isNotBlank() }
            words.all { w ->
                if (w.all(Char::isDigit)) e.id.startsWith(w) || w in e.id.split('-') || w in hay
                else hay.any { it.startsWith(w) }
            }
        }
    }

    /** [entry] added: the address kept, its players and games once read. Already there, it is the same entry. */
    fun add(added: List<Added>, url: String, replay: DbReplay?, now: Long, note: String = ""): List<Added> {
        val id = DbReplays.id(url) ?: return added
        val one = Added(id, DbReplays.normal(url) ?: url, replay?.players?.joinToString(" vs ").orEmpty(), replay?.games?.size ?: 0, now, note)
        return added.filter { it.id != id } + one
    }

    fun remove(added: List<Added>, id: String): List<Added> = added.filter { it.id != id }

    private fun norm(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
}

/**
 * A replay laid out to be read (1.1.51): its games, each game's turns, each turn's lines — what was done, what was said,
 * and the phases — with who won and who went first, and the cards each player played, most first.
 */
object ReplayReading {
    data class Line(val who: String, val text: String, val chat: Boolean = false, val phase: Boolean = false)

    data class Turn(val n: Int, val player: String, val lines: List<Line>)

    data class Game(val n: Int, val first: String?, val loser: String?, val turns: List<Turn>)

    data class Reading(val players: List<String>, val format: String, val games: List<Game>, val cards: Map<String, List<Pair<String, Int>>>)

    fun of(r: DbReplay, private: Boolean = true): Reading {
        val games = r.games.map { g ->
            Game(g.n, g.first, g.loser, g.turns.map { t ->
                Turn(t.n, t.player, t.actions.mapNotNull { a ->
                    val text = (if (private) a.words else a.public).ifBlank { a.play }.replace('\n', ' ').trim()
                    if (text.isBlank()) null else Line(a.player, text, chat = a.chat, phase = a.phase.isNotEmpty())
                })
            })
        }
        val cards = r.players.associateWith { p ->
            r.games.flatMap { g -> g.turns.flatMap { t -> t.actions.filter { it.player == p && !it.chat }.mapNotNull { it.cards.firstOrNull() } } }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        }
        return Reading(r.players, r.format, games, cards)
    }

    /** Every card [r] names, for searching the library by a card. */
    fun cards(r: DbReplay): Set<String> = r.games.flatMap { g -> g.turns.flatMap { t -> t.actions.flatMap { it.cards } } }.toSet()

    /** Who won each game, in words: "Game 1: Joe won (Joe went first)". */
    fun results(reading: Reading): List<String> = reading.games.map { g ->
        val winner = g.loser?.let { l -> reading.players.firstOrNull { it != l } }
        "Game ${g.n}: " + (winner?.let { "$it won" } ?: "result not recorded") + (g.first?.let { " ($it went first)" } ?: "")
    }
}
