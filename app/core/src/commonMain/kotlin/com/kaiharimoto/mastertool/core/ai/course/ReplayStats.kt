package com.kaiharimoto.mastertool.core.ai.course

/**
 * What a course's DuelingBook replays show taken together, counted by the app (so its numbers are computed, never
 * quoted): whose replays they are, how the games went first and second, which cards opened and which recurred, and what
 * the opponents played. The player counted is the one in the most replays — a guide's replays are its author's — unless
 * the study names another.
 */
object ReplayStats {
    /** A replay with its number in the course and the chapter that linked it. */
    data class Entry(val n: Int, val chapter: Int, val replay: DbReplay)

    /** The player in the most of [entries], the one a guide's replays are about; null with none. */
    fun focus(entries: List<Entry>): String? =
        entries.flatMap { it.replay.players.distinct() }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key

    data class Record(val games: Int, val known: Int, val won: Int) {
        val lost: Int get() = known - won
        fun words(): String = if (known == 0) "$games games, results not recorded" else "$games games: won $won, lost $lost" +
            if (known < games) " (${games - known} without a recorded result)" else ""
    }

    data class Summary(
        val focus: String?,
        val replays: Int,
        val games: Int,
        val all: Record,
        val first: Record,
        val second: Record,
        /** Cards in the focus's first turn when it went first: in how many such games each appeared. */
        val openers: List<Pair<String, Int>>,
        /** Cards the focus used: in how many games each appeared. */
        val used: List<Pair<String, Int>>,
        /** Cards their opponents used: in how many games each appeared. */
        val faced: List<Pair<String, Int>>,
        /** Lines the focus said in chat, all told. */
        val talk: Int,
    )

    fun of(entries: List<Entry>, who: String? = null, top: Int = 25): Summary {
        val focus = who?.takeIf { w -> entries.any { w in it.replay.players } } ?: focus(entries)
        val games = entries.flatMap { e -> e.replay.games.map { e.replay to it } }
        val mine = games.filter { (r, _) -> focus != null && focus in r.players }
        fun record(of: List<Pair<DbReplay, DbReplay.Game>>): Record {
            val known = of.filter { (_, g) -> g.loser != null }
            return Record(of.size, known.size, known.count { (_, g) -> g.loser != focus })
        }
        fun count(pick: (DbReplay, DbReplay.Game) -> Collection<String>, among: List<Pair<DbReplay, DbReplay.Game>>): List<Pair<String, Int>> =
            among.flatMap { (r, g) -> pick(r, g).toSet() }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(top).map { it.key to it.value }
        val wentFirst = mine.filter { (_, g) -> g.first == focus }
        val wentSecond = mine.filter { (_, g) -> g.first != null && g.first != focus }
        return Summary(
            focus = focus,
            replays = entries.size,
            games = games.size,
            all = record(mine),
            first = record(wentFirst),
            second = record(wentSecond),
            openers = count({ _, g -> g.turns.firstOrNull { it.n == 1 && it.player == focus }?.actions?.filter { !it.chat && it.player == focus }?.flatMap { it.cards }.orEmpty() }, wentFirst),
            used = count({ _, g -> g.turns.flatMap { t -> t.actions.filter { !it.chat && it.player == focus }.flatMap { it.cards } } }, mine),
            faced = count({ _, g -> g.turns.flatMap { t -> t.actions.filter { !it.chat && it.player.isNotBlank() && it.player != focus }.flatMap { it.cards } } }, mine),
            talk = mine.sumOf { (_, g) -> g.turns.sumOf { t -> t.actions.count { it.chat && it.player == focus } } },
        )
    }

    /** [s] in words, with [entries]' index: what `course_replays` answers. */
    fun words(s: Summary, entries: List<Entry>): String = buildString {
        appendLine("${s.replays} replays read, ${s.games} games (counted by the app from the replays' own records).")
        if (s.focus == null) {
            appendLine("No player could be named in them.")
        } else {
            appendLine("Counted for ${s.focus}, the player in the most replays:")
            appendLine("- All: ${s.all.words()}.")
            appendLine("- Going first: ${s.first.words()}.")
            appendLine("- Going second: ${s.second.words()}.")
            appendLine("- Lines said in chat: ${s.talk}.")
            fun list(title: String, of: List<Pair<String, Int>>, among: Int) {
                if (of.isEmpty()) return
                appendLine()
                appendLine("$title (in how many of $among games):")
                of.forEach { (card, n) -> appendLine("- $card: $n") }
            }
            list("Cards in ${s.focus}'s first turn going first", s.openers, s.first.games)
            list("Cards ${s.focus} used", s.used, s.all.games)
            list("Cards their opponents used", s.faced, s.all.games)
        }
        appendLine()
        appendLine("The replays:")
        entries.sortedBy { it.n }.forEach { e ->
            val r = e.replay
            val results = r.games.mapNotNull { g -> g.loser?.let { l -> r.opponentOf(l) } }
            appendLine("- Replay ${e.n} (ch. ${e.chapter}): ${r.players.joinToString(" vs ").ifBlank { "players unknown" }}, ${r.games.size} game${if (r.games.size == 1) "" else "s"}" +
                if (results.isNotEmpty()) ", won by " + results.joinToString(", ") else "")
        }
    }.trimEnd()
}
