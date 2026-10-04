package com.kaiharimoto.mastertool.core.world

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ai World (1.0.97, kai: "a free environment to build using coding tools … that the user can see and watch live"):
 * a small computer of Ai's own. It writes files, runs them — JavaScript everywhere, Python on the desk — and pins
 * what it found to the world's boards, and the person watches every keystroke, run and board as it happens.
 *
 * A world is a folder, `<data>/world/<id>/`: this record as `world.json`, the code in `files/`, pictures a run
 * saved in `out/`, and every action in `log.jsonl` ([WorldEvent]). The files are the files — the record never
 * holds their text — so a world can be opened, copied and synced like any folder.
 */
@Serializable
data class World(
    val id: String,
    val title: String = "World",
    /** What it is about, when it is about something: `deck:<id>` or `web:<id>`. */
    val scope: String? = null,
    val created: Long = 0L,
    val updated: Long = 0L,
    /** What Ai chose to show, in the order it was pinned. */
    val boards: List<Board> = emptyList(),
    /** The file the editor last had open. */
    val open: String? = null,
) {
    fun board(id: String): Board? = boards.firstOrNull { it.id == id }

    /** [b] in place of the board with its id, or added at the end. */
    fun put(b: Board): World =
        if (boards.any { it.id == b.id }) copy(boards = boards.map { if (it.id == b.id) b else it }) else copy(boards = boards + b)

    fun drop(id: String): World = copy(boards = boards.filterNot { it.id == id })

    companion object {
        const val MAX_BOARDS = 60
        const val SCOPE_DECK = "deck:"
        const val SCOPE_WEB = "web:"
    }
}

/**
 * What a board draws. Its [Board.payload] is the kind's own text: JSON for the drawn kinds, the chat's fences' text for
 * cards. Kept in the file by [id], as a word: a kind a newer build adds survives an older build's save untouched.
 */
@Serializable
enum class BoardKind {
    /** The chat's markdown, its tables and fenced blocks too. */
    @SerialName("markdown") MARKDOWN,

    /** [WorldChart]: bar, line, stacked, scatter, heatmap. */
    @SerialName("chart") CHART,

    /** [WorldGraph] drawn as a web: every node where a force layout leaves it. */
    @SerialName("graph") GRAPH,

    /** [WorldGraph] drawn as a flowchart: layered, top to bottom. */
    @SerialName("flow") FLOW,

    /** [WorldTable]. */
    @SerialName("table") TABLE,

    /** A big number with its label: [WorldStat]. */
    @SerialName("stat") STAT,

    /** Card art: the payload is a ```cards fence's text. */
    @SerialName("cards") CARDS,

    /** A field: a ```board fence's text. */
    @SerialName("board") BOARD,

    /** A line of play: a ```line fence's text. */
    @SerialName("line") LINE,

    /** A picture a run saved; the payload is its path under the world's folder. */
    @SerialName("image") IMAGE,
    ;

    /** The word in the file. */
    val id: String get() = name.lowercase()

    companion object {
        fun of(id: String): BoardKind? = entries.firstOrNull { it.id == id.lowercase() }
    }
}

@Serializable
data class Board(
    val id: String,
    val title: String = "",
    /** A [BoardKind.id]; one this build does not know is kept as it is and drawn as "made by a newer version". */
    val kind: String = "markdown",
    val payload: String = "",
    /** Where it stands on the canvas, in the canvas's own units; [WorldCanvas] places a new one. */
    val x: Double = 0.0,
    val y: Double = 0.0,
    val w: Double = WorldCanvas.WIDTH,
    val h: Double = WorldCanvas.HEIGHT,
    /** The file whose run made it, when a run did. */
    val source: String? = null,
    val updated: Long = 0L,
    /** Ai's one line on what it shows and why. */
    val note: String = "",
) {
    /** What it draws, or null for a kind from a newer build. */
    val type: BoardKind? get() = BoardKind.of(kind)
}

/** One thing that happened in a world, as `log.jsonl` keeps it: the Activity pane, and Ai's own memory of the world. */
@Serializable
data class WorldEvent(
    val t: Long = 0L,
    val kind: Kind = Kind.NOTE,
    /** Who did it: [AI] or [YOU]. */
    val by: String = AI,
    val path: String? = null,
    val board: String? = null,
    /** One line, in words. */
    val text: String = "",
    val run: RunRecord? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("new") NEW,
        @SerialName("write") WRITE,
        @SerialName("delete") DELETE,
        @SerialName("run") RUN,
        @SerialName("show") SHOW,
        @SerialName("unshow") UNSHOW,
        @SerialName("note") NOTE,
    }

    companion object {
        const val AI = "ai"
        const val YOU = "you"
    }
}

/** A run, kept: what ran, how it ended and what it said (capped), and the boards it pinned. */
@Serializable
data class RunRecord(
    val lang: String = "js",
    val path: String? = null,
    val ok: Boolean = true,
    val ms: Long = 0L,
    val out: String = "",
    val err: String = "",
    val boards: List<String> = emptyList(),
    /** True when the output was longer than was kept. */
    val cut: Boolean = false,
)

/** Where new boards go: a grid of cards of one size, filled left to right. */
object WorldCanvas {
    const val WIDTH = 520.0
    const val HEIGHT = 380.0
    const val GAP = 32.0
    const val COLUMNS = 3

    /** The place for the [n]th board. */
    fun slot(n: Int): Pair<Double, Double> =
        (n % COLUMNS) * (WIDTH + GAP) to (n / COLUMNS) * (HEIGHT + GAP)

    /** The first slot no board covers. */
    fun free(boards: List<Board>): Pair<Double, Double> {
        var n = 0
        while (true) {
            val (x, y) = slot(n)
            if (boards.none { overlaps(it, x, y) }) return x to y
            n++
        }
    }

    private fun overlaps(b: Board, x: Double, y: Double): Boolean =
        x < b.x + b.w && b.x < x + WIDTH && y < b.y + b.h && b.y < y + HEIGHT
}
