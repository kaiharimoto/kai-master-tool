package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords

/*
 * What Gameplay Mapper's page shows at any moment (the design run, M.md §6½): the page's state, how dense the library is
 * drawn, what it is ordered by, what each board leads with, the sections it falls into and what a board trades against the
 * first. Pure, so the page, the phone and the studio read one answer; nothing here ranks a board by itself — the person's
 * weights and the order they chose do (Decision 5).
 */
object MapperView {
    // ---- the moment ----------------------------------------------------------------------------------------------

    /** Where the person is: what the page leads with. */
    enum class Moment {
        /** The builder's deck was never saved: nothing to keep boards with. */
        NO_DECK,

        /** The deck's files are being read. */
        LOADING,

        /** A file was written by a newer version. */
        UNREADABLE,

        /** No board yet on this side: the page is the two steps to the first. */
        FIRST_RUN,

        /** Boards, but no dealt hands counted on this deck: every share waits on one run, said once. */
        UNCOUNTED,

        /** Boards and their shares: the library leads, the runs fold into one line. */
        READY,
    }

    fun moment(hasDeck: Boolean, loaded: Boolean, unreadable: Boolean, boards: Int, counted: Boolean): Moment = when {
        !hasDeck -> Moment.NO_DECK
        !loaded -> Moment.LOADING
        unreadable -> Moment.UNREADABLE
        boards == 0 -> Moment.FIRST_RUN
        !counted -> Moment.UNCOUNTED
        else -> Moment.READY
    }

    // ---- density -------------------------------------------------------------------------------------------------

    /**
     * How much of each board is drawn, densest first — boards to a screen: [OVERVIEW] the field's art and the numbers asked
     * for, many to a row; [ROWS] one line a board with every number in a column; [CARDS] a tile with its measures in words
     * and its share.
     */
    enum class Density(val words: String) {
        OVERVIEW("Overview"),
        ROWS("Rows"),
        CARDS("Cards"),
        ;

        /** More boards to a screen, or this one at the end. */
        fun denser(): Density = entries[(ordinal - 1).coerceAtLeast(0)]

        /** Fewer boards, each drawn larger. */
        fun looser(): Density = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
    }

    /**
     * The density when the person has not chosen one: a library that fits a screen of tiles is read as tiles; a long one
     * opens as the overview, so the whole of it is seen before any one board. A phone reads tiles until the library is
     * long, since its overview holds three to a row.
     */
    fun autoDensity(boards: Int, phone: Boolean): Density = when {
        boards > (if (phone) 40 else AUTO_OVERVIEW) -> Density.OVERVIEW
        else -> Density.CARDS
    }

    const val AUTO_OVERVIEW = 40

    // ---- order ---------------------------------------------------------------------------------------------------

    /** What the library is ordered by: the person's weights, how often a board is made, or how short its line is. */
    enum class Order(val words: String, val help: String) {
        ASKED("What you asked", "By your weights: the boards with more of what you asked for first"),
        OFTEN("Most often", "By the share of dealt hands that make at least this much"),
        SHORTEST("Shortest line", "By the fewest moves to the board, then fewest starting cards"),
    }

    /** Moves a line takes, the passes and resolutions not counted: what a player would call its length. */
    fun moves(l: MapLine): Int = l.steps.count { it.kind != "x" && it.kind != "r" }

    /** The shortest line's moves, or null when no line on the deck as it is reaches it. */
    fun shortest(e: BoardEntry): Int? = e.lines.minOfOrNull(::moves)

    /**
     * [ranked] in [order]. [share] is a board's share of hands, null when uncounted (last, in the weights' order). Ties keep
     * the weights' order, so changing the order never shuffles boards it cannot tell apart.
     */
    fun order(ranked: List<BoardQuery.Ranked>, order: Order, share: (BoardEntry) -> Double?): List<BoardQuery.Ranked> = when (order) {
        Order.ASKED -> ranked
        Order.OFTEN -> ranked.sortedWith(compareBy { -(share(it.entry) ?: -1.0) })
        Order.SHORTEST -> ranked.sortedWith(compareBy({ shortest(it.entry) ?: Int.MAX_VALUE }, { it.entry.lines.minOfOrNull { l -> l.deal.hand.size } ?: Int.MAX_VALUE }))
    }

    // ---- what a board leads with ---------------------------------------------------------------------------------

    /** One number a board leads with: [head]'s [value], [asked] when the person weighted it. */
    data class Lead(val head: String, val value: Int, val asked: Boolean)

    /**
     * The numbers a board leads with: the traits the person weighted, heaviest first (a zero shown, since it was asked for),
     * then — with room left — the traits where more is plainly better that it has. At most [most]; interruptions when
     * nothing else is to say.
     */
    fun leads(t: BoardTraits, weights: Map<String, Double>, most: Int = 3): List<Lead> {
        val asked = weights.filterValues { it != 0.0 && it.isFinite() }.entries
            .sortedWith(compareBy({ -kotlin.math.abs(it.value) }, { order(it.key) }))
            .map { it.key }
        val out = ArrayList<Lead>()
        asked.forEach { h -> t[h]?.let { if (out.size < most) out += Lead(h, it.toInt(), asked = true) } }
        BoardQuery.plainlyBetter(emptyList()).forEach { h ->
            if (out.size < most && out.none { it.head == h }) t[h]?.toInt()?.takeIf { it > 0 }?.let { out += Lead(h, it, asked = false) }
        }
        if (out.isEmpty()) out += Lead("interruptions", t.interruptions, asked = false)
        return out
    }

    /** The rest of what a board measures, in words, zeros left out: "1 body, 2 set, 3 kept in hand". Empty when nothing. */
    fun rest(t: BoardTraits, leads: List<Lead>): String =
        t.heads().filter { h -> leads.none { it.head == h } }
            .mapNotNull { h -> t[h]?.toInt()?.takeIf { it > 0 }?.let { unit(h, it) } }
            .joinToString(", ")

    /** [n] of [head] as a phrase: "1 interruption", "2 negates", "1 body", "3 kept in hand", "2 hand traps kept". */
    fun unit(head: String, n: Int): String = "$n ${label(head, n)}"

    /** The words beside a number of [head]: "interruptions", "body", "kept in hand". */
    fun label(head: String, n: Int): String {
        val one = n == 1
        return when (head) {
            "interruptions" -> if (one) "interruption" else "interruptions"
            "negates" -> if (one) "negate" else "negates"
            "removal" -> "removal"
            "bodies" -> if (one) "body" else "bodies"
            "set" -> "set"
            "hand" -> "kept in hand"
            "gy" -> "in the GY"
            "banished" -> "banished"
            "handInterruptions" -> if (one) "hand trap kept" else "hand traps kept"
            else -> if (head.startsWith(BoardTraits.THROUGH)) "through ${head.removePrefix(BoardTraits.THROUGH)}" else head
        }
    }

    private fun order(head: String): Int = BoardTraits.HEADS.indexOf(head).let { if (it < 0) Int.MAX_VALUE else it }

    // ---- sections ------------------------------------------------------------------------------------------------

    /** A run of boards under one heading: [title] says what they share, [boards] in the order on screen. */
    data class Section(val title: String, val boards: List<BoardQuery.Ranked>)

    /**
     * The ordered library cut into sections a reader can name. By what was asked: boards that measure the same on every
     * weighted trait ("2 interruptions · 1 negate", n ways to make it). Most often: by how often ("In most hands", …). By the
     * shortest line: by its length. Consecutive only — a section never pulls a board out of the order — and a library of
     * one section is not cut at all. [coarse] (the overview, where a section a board is a row a board) cuts by what was
     * asked the most alone, and the tiles carry the rest.
     */
    fun sections(
        boards: List<BoardQuery.Ranked>,
        order: Order,
        weights: Map<String, Double>,
        coarse: Boolean = false,
        share: (BoardEntry) -> Double?,
    ): List<Section> {
        if (boards.isEmpty()) return emptyList()
        val key: (BoardQuery.Ranked) -> String = when (order) {
            Order.ASKED -> { r ->
                val l = leads(r.entry.traits, weights).filter { it.asked }.ifEmpty { leads(r.entry.traits, weights).take(1) }
                (if (coarse) l.take(1) else l).joinToString(" · ") { unit(it.head, it.value) }
            }
            Order.OFTEN -> { r -> often(share(r.entry)) }
            Order.SHORTEST -> { r -> length(shortest(r.entry)) }
        }
        val out = ArrayList<Section>()
        var title = key(boards[0])
        var run = ArrayList<BoardQuery.Ranked>()
        boards.forEach { r ->
            val k = key(r)
            if (k != title && run.isNotEmpty()) {
                out += Section(title, run)
                run = ArrayList()
            }
            title = k
            run += r
        }
        out += Section(title, run)
        return if (out.size == 1) listOf(Section("", out[0].boards)) else out
    }

    /** How often, in a reader's words: "In most hands · 50 % or more". */
    fun often(share: Double?): String = when {
        share == null -> "Not counted yet"
        share >= 0.5 -> "In most hands · 50 % or more"
        share >= 0.2 -> "Often · 20 to 50 %"
        share >= 0.05 -> "Sometimes · 5 to 20 %"
        share > 0.0 -> "Rarely · under 5 %"
        else -> "Not seen in the dealt hands"
    }

    private fun length(moves: Int?): String = when {
        moves == null -> "No line on the deck as it is"
        moves <= 3 -> "Up to 3 moves"
        moves <= 6 -> "4 to 6 moves"
        moves <= 10 -> "7 to 10 moves"
        else -> "11 moves or more"
    }

    // ---- against the first ---------------------------------------------------------------------------------------

    /**
     * What [board] trades against [top] (the first board on screen), the weighted traits first: "1 fewer interruption,
     * 2 more kept in hand". Empty when they measure the same.
     */
    fun versus(board: BoardTraits, top: BoardTraits, weights: Map<String, Double>): List<String> {
        val heads = board.heads().sortedWith(compareBy({ if ((weights[it] ?: 0.0) != 0.0) 0 else 1 }, { order(it) }))
        return heads.mapNotNull { h ->
            val d = ((board[h] ?: 0.0) - (top[h] ?: 0.0)).toInt()
            when {
                d > 0 -> "$d more ${label(h, d)}"
                d < 0 -> "${-d} ${if (h in COUNTABLE) "fewer" else "less"} ${label(h, -d)}"
                else -> null
            }
        }
    }

    private val COUNTABLE = setOf("interruptions", "negates", "bodies", "handInterruptions", "hand", "set", "gy", "banished")

    /** A share as the tile writes it: "28 %", one decimal under ten. */
    fun pct(share: Double): String {
        val p = share * 100
        return if (p >= 10 || p == 0.0) "${kotlin.math.round(p).toInt()} %" else GoldfishWords.pct(share)
    }

    // ---- what to ask -----------------------------------------------------------------------------------------------

    /**
     * The questions the library answers in one press, each a preset of weights the person can then move: the first is
     * [BoardPreset.DEFAULT]. Never saved, never Ai's: they are how the page asks "what do you want the board to do?".
     */
    val ASKS: List<BoardPreset> = listOf(
        BoardPreset.DEFAULT.copy(name = "Most interruptions"),
        BoardPreset("ask:negates", "Most negates", weights = mapOf("negates" to 2.0, "interruptions" to 1.0)),
        BoardPreset("ask:spare", "Cards to spare", weights = mapOf("interruptions" to 1.0, "hand" to 1.0)),
        BoardPreset("ask:handtraps", "Hand traps kept", weights = mapOf("interruptions" to 1.0, "handInterruptions" to 1.0)),
        BoardPreset("ask:bodies", "Most bodies", weights = mapOf("bodies" to 1.0, "interruptions" to 0.5)),
    )

    /** The ask [weights] are, or null when the person has moved them. */
    fun askOf(weights: Map<String, Double>): BoardPreset? {
        val w = weights.filterValues { it != 0.0 }
        return ASKS.firstOrNull { it.weights == w }
    }
}
