package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** The small ink sign beside the avatar for the kind of work (`DESKTOP.md` §5.7): its icon is [WorldIcons.sign]. */
enum class AvatarSign { WRITE, RUN, READ, BROWSE, SHOW, APP, PRESS, THINK, SAY, ASK, DONE, FAILED }

/**
 * What Ai is up to as the person sees it beside the avatar, apart from the avatar's place (`AvatarPilot`): a tool under
 * way, a question waiting, a reply streaming. Read off `AiState` by the page.
 */
data class AiNow(
    /** A turn is under way. */
    val running: Boolean = false,
    /** The tool running now, by name (`mcp__neue__` stripped or not); null between tools. */
    val tool: String? = null,
    /** A question (`ask_user`) or a confirmation waits on the person. */
    val asking: Boolean = false,
    /** Its reply is arriving. */
    val answering: Boolean = false,
)

/**
 * The avatar's status on the World desktop (`docs/world/DESKTOP.md` §5.7; kai: "so the user can track its status visually
 * better"): a few words of what it is doing now — [verb] and [subject], "Writing" `deck-odds.js` — a face from Ai's twenty
 * ([expression], never new geometry), and a small ink [sign]. One per activity ([Kind]); all of it pure and tested
 * (`AvatarStatusTest`), so the page only draws it.
 */
data class AvatarStatus(
    val kind: Kind,
    val verb: String,
    val subject: String? = null,
    /** [subject] is a file's own name: set as itself, in mono (READABILITY.md §3), and shortened by its parts. */
    val named: Boolean = false,
) {
    enum class Kind(val expression: Expression, val sign: AvatarSign) {
        /** Reasoning between tools: no tool running, no words arriving yet. */
        THINKING(Expression.THINKING, AvatarSign.THINK),

        /** Its reply streaming into Thoughts. */
        ANSWERING(Expression.SPEAKING, AvatarSign.SAY),

        /** Typing a file into the Editor: focused. */
        WRITING(Expression.WORKING, AvatarSign.WRITE),

        /** Making an app (its code typed, its icon pressed). */
        MAKING(Expression.WORKING, AvatarSign.APP),

        /** A run or an instrument under way: it watches the Terminal. */
        RUNNING(Expression.LISTENING, AvatarSign.RUN),

        /** Reading a file, the Library, a card's text, a ruling. */
        READING(Expression.READING, AvatarSign.READ),

        /** On the web, or in the Browser reading a page. */
        BROWSING(Expression.READING, AvatarSign.BROWSE),

        /** A page opened for the person: pleased with what it found. */
        SHOWING(Expression.FOUND, AvatarSign.SHOW),

        /** Opening one of its apps. */
        OPENING(Expression.WORKING, AvatarSign.APP),

        /** Pressing a widget of its app, watching what it does. */
        TRYING(Expression.LISTENING, AvatarSign.PRESS),

        /** Any other tool. */
        WORKING(Expression.WORKING, AvatarSign.THINK),

        /** A question or a confirmation waits on the person. */
        WAITING(Expression.WAITING, AvatarSign.ASK),

        /** A run that needs the person first: Python is off on this computer, and only the person turns it on. */
        BLOCKED(Expression.WAITING, AvatarSign.ASK),

        /** A run finished well. */
        SUCCEEDED(Expression.DONE, AvatarSign.DONE),

        /** A run failed: worried. */
        FAILED(Expression.OOPS, AvatarSign.FAILED),

        /** The turn ended. */
        DONE(Expression.DONE, AvatarSign.DONE),
    }

    val expression: Expression get() = kind.expression
    val sign: AvatarSign get() = kind.sign

    /** The words whole: "Writing deck-odds.js". */
    val text: String get() = subject?.let { "$verb $it" } ?: verb

    /** It holds the waiting pose: a slow pulse round it until the person answers (§5.7). */
    val pulses: Boolean get() = kind == Kind.WAITING || kind == Kind.BLOCKED

    /** The person is wanted, or should hear how a run went: shown even while their hands are on the page. */
    val speaksUp: Boolean get() = kind == Kind.WAITING || kind == Kind.BLOCKED || kind == Kind.FAILED || kind == Kind.SUCCEEDED || kind == Kind.DONE

    /** The words in at most [max] characters, shortened by words — never a stub (READABILITY.md §4). */
    fun words(max: Int): Words {
        val s = subject ?: return Words(verb, null, false)
        val room = max(max - verb.length - 1, TabTitles.FLOOR)
        if (s.length <= room) return Words(verb, s, named)
        return Words(verb, if (named) shortName(s, room) else TabTitles.short(s, room), named)
    }

    /** [verb] then [subject] (null for none), the subject in mono when [named]. */
    data class Words(val verb: String, val subject: String?, val named: Boolean) {
        val text: String get() = subject?.let { "$verb $it" } ?: verb
    }

    /** How the status stands beside the avatar now: nothing, the sign alone, or the sign and its words. */
    enum class Plate { NONE, SIGN, FULL }

    /** Where the plate stands: its top-left (dp, the page's) and whether it is on the avatar's right. */
    data class Spot(val x: Double, val y: Double, val right: Boolean)

    companion object {
        /** The longest caption, in characters: about 210 dp of the label tier on the desk; less on a phone's 360 dp. */
        const val CAPTION_DESK = 34
        const val CAPTION_PHONE = 26

        /** How long a run's end is shown before what Ai does next takes over. */
        const val SUCCEEDED_MS = 2_500L
        const val FAILED_MS = 8_000L

        /** The waiting pulse: one breath in and out. */
        const val BREATH_MS = 2_400L

        /** Between the avatar and its plate, and the plate and the page's edge (dp). */
        const val GAP = 6.0
        const val MARGIN = 4.0

        val THINK = AvatarStatus(Kind.THINKING, "Thinking")
        val ANSWER = AvatarStatus(Kind.ANSWERING, "Answering")
        /** The taskbar's own words for it ([AvatarPilot.WAITING]), and the face's: one phrase on the screen at once. */
        val WAITING = AvatarStatus(Kind.WAITING, AvatarPilot.WAITING)
        val DONE = AvatarStatus(Kind.DONE, "Done")

        /** Python is off here (`Worlds.runPython`'s words): the run waits on the person, not on Ai. */
        private const val PYTHON_OFF = "Python is off"

        /** What the avatar went to do for [does]; null for the walk home (it has nothing to say there). */
        fun of(does: AiDoes): AvatarStatus? = when (does) {
            AiDoes.TurnStart -> null
            is AiDoes.Write -> AvatarStatus(Kind.WRITING, "Writing", fileName(does.path), named = true)
            is AiDoes.Run -> AvatarStatus(Kind.RUNNING, "Running", fileName(does.label), named = looksLikeFile(fileName(does.label)))
            is AiDoes.Tool -> AvatarStatus(Kind.RUNNING, "Running", does.detail ?: does.name.replace('_', ' '))
            is AiDoes.Show -> AvatarStatus(Kind.SHOWING, "Showing", does.title?.let { TabTitles.clean(it) }?.ifEmpty { null } ?: "a page")
            is AiDoes.MakeApp -> AvatarStatus(Kind.MAKING, "Making", does.name)
            is AiDoes.OpenApp -> AvatarStatus(Kind.OPENING, "Opening", does.name)
            is AiDoes.Press -> AvatarStatus(Kind.TRYING, "Trying", does.name)
            is AiDoes.Read -> read(does.app, does.what)
            AiDoes.Question -> WAITING
            AiDoes.TurnEnd -> DONE
        }

        private fun read(app: AppRef, what: String): AvatarStatus {
            val builtIn = (app as? AppRef.BuiltIn)?.kind
            if (builtIn == BuiltInApp.BROWSER) return AvatarStatus(Kind.BROWSING, "Browsing")
            // "Reading Library" reads as a typo: a built-in named by its own title is "the Library".
            val subject = if (builtIn != null && what.equals(builtIn.title, ignoreCase = true)) "the ${builtIn.title}" else what
            return AvatarStatus(Kind.READING, "Reading", subject, named = looksLikeFile(subject))
        }

        /** A run's end: pleased, worried, or — Python off here — waiting on the person. */
        fun ran(label: String, ok: Boolean, error: String = ""): AvatarStatus {
            val name = fileName(label)
            val named = looksLikeFile(name)
            return when {
                ok -> AvatarStatus(Kind.SUCCEEDED, "Ran", name, named)
                error.trim().startsWith(PYTHON_OFF) -> AvatarStatus(Kind.BLOCKED, "Needs Python allowed")
                else -> AvatarStatus(Kind.FAILED, "Failed", name, named)
            }
        }

        /** Any tool that is not the World's own, in a few words: what it reads or searches. */
        fun forTool(tool: String): AvatarStatus {
            val t = tool.removePrefix("mcp__neue__")
            return when (t) {
                "card_info", "resolve_cards" -> AvatarStatus(Kind.READING, "Reading", "cards")
                "rulings", "duel_ruling" -> AvatarStatus(Kind.READING, "Reading", "rulings")
                "banlist" -> AvatarStatus(Kind.READING, "Reading", "the banlist")
                "archetype_guide" -> AvatarStatus(Kind.READING, "Reading", "an archetype guide")
                "get_deck", "get_web", "get_siding" -> AvatarStatus(Kind.READING, "Reading", "the deck")
                "memory_read", "recall", "session_search" -> AvatarStatus(Kind.READING, "Reading", "its notes")
                "skill_view" -> AvatarStatus(Kind.READING, "Reading", "a skill")
                "search_cards" -> AvatarStatus(Kind.READING, "Searching", "cards")
                "web_search" -> AvatarStatus(Kind.BROWSING, "Searching", "the web")
                "web_fetch" -> AvatarStatus(Kind.BROWSING, "Reading", "a web page")
                "watch_video" -> AvatarStatus(Kind.BROWSING, "Watching", "a video")
                "ygopro_tournament_decks", "ygopro_player", "ygopro_deck" -> AvatarStatus(Kind.BROWSING, "Reading", "decklists")
                "calculate", "hand_odds" -> AvatarStatus(Kind.WORKING, "Working out", "the odds")
                else -> AvatarStatus(Kind.WORKING, "Working")
            }
        }

        /** Whether [tool] is one of the World's own (its avatar already went where it works). */
        fun isWorldTool(tool: String): Boolean = tool.removePrefix("mcp__neue__").startsWith("world_")

        /**
         * What the avatar says now (§5.7), in order:
         * 1. a question or a confirmation waiting on the person;
         * 2. a run's end ([outcome] at [outcomeAt]) for its while — until what Ai went to do next ([doing], since
         *    [doingSince]) takes over, though the turn's Done never hides a failure;
         * 3. another tool than the World's, in its own words;
         * 4. Ai's reply streaming;
         * 5. between tools in a turn, thinking — what it went to do is done;
         * 6. what it went to do.
         * Null when there is nothing to say (the walk home, asleep).
         */
        fun resolve(doing: AvatarStatus?, doingSince: Long, outcome: AvatarStatus?, outcomeAt: Long, ai: AiNow, now: Long): AvatarStatus? {
            if (ai.asking) return WAITING
            if (outcome != null && now - outcomeAt < hold(outcome)) {
                val superseded = doing != null && doingSince > outcomeAt && !(doing.kind == Kind.DONE && outcome.kind != Kind.SUCCEEDED)
                if (!superseded) return outcome
            }
            val tool = ai.tool
            if (ai.running && tool != null && !isWorldTool(tool)) return forTool(tool)
            if (ai.running && ai.answering && tool == null) return ANSWER
            // A question answered is no longer waited on.
            val d = doing?.takeUnless { it.kind == Kind.WAITING }
            if (ai.running && tool == null && d?.kind != Kind.DONE) return THINK
            return d
        }

        /** How long [outcome] is shown at the most. */
        fun hold(outcome: AvatarStatus): Long = if (outcome.kind == Kind.SUCCEEDED) SUCCEEDED_MS else FAILED_MS

        /** When [outcome] stops being shown by time alone, for the page to look again; null when it never will. */
        fun expires(outcome: AvatarStatus?, outcomeAt: Long): Long? = outcome?.let { outcomeAt + hold(it) }

        /**
         * How the plate stands (§5.7): nothing with the avatar off ([WorldPrefs.avatar][com.kaiharimoto.mastertool.core.world.WorldPrefs.avatar]),
         * asleep, at home (the taskbar's line says it there) or with nothing to say. With recede on, the person's own
         * hands on the page during the turn ([personTookOver]) quiet it to the sign — the windows stop receding then
         * too (§6.2) — unless it [speaksUp][AvatarStatus.speaksUp]. With recede off nothing on the desk steps back by
         * itself, the plate included.
         */
        fun plate(status: AvatarStatus?, avatarOn: Boolean, asleep: Boolean, atHome: Boolean, recede: Boolean, personTookOver: Boolean): Plate = when {
            !avatarOn || asleep || atHome || status == null -> Plate.NONE
            status.speaksUp -> Plate.FULL
            recede && personTookOver -> Plate.SIGN
            else -> Plate.FULL
        }

        /**
         * Where a plate [w] × [h] stands beside the avatar at [at] of [size] (dp): on its right, its middle on the
         * avatar's; on its left when the right would pass [bounds]' edge; held inside [bounds] either way.
         */
        fun place(at: DeskPoint, size: Double, w: Double, h: Double, bounds: DeskRect?): Spot {
            val half = size / 2
            val rightX = at.x + half + GAP
            val leftX = at.x - half - GAP - w
            val y0 = at.y - h / 2
            val b = bounds ?: return Spot(rightX, y0, true)
            val fitsRight = rightX + w <= b.right - MARGIN
            val fitsLeft = leftX >= b.x + MARGIN
            val right = fitsRight || (!fitsLeft && b.right - (at.x + half) >= (at.x - half) - b.x)
            val x = (if (right) rightX else leftX).coerceIn(b.x + MARGIN, max(b.x + MARGIN, b.right - MARGIN - w))
            val y = y0.coerceIn(b.y + MARGIN, max(b.y + MARGIN, b.bottom - MARGIN - h))
            return Spot(x, y, right)
        }

        /**
         * Whether the avatar at [at] stands on the page: a phone's home is the face in Neue's bar, above it, and a plate
         * held on the page there would stand apart from the avatar it speaks for — so it is not drawn. An avatar of
         * [size] half over the edge (standing on a title bar at the page's top) is still on it.
         */
        fun onPage(at: DeskPoint, bounds: DeskRect?, size: Double = 0.0): Boolean {
            val b = bounds ?: return true
            val s = size / 2
            return at.x >= b.x - s && at.x <= b.right + s && at.y >= b.y - s && at.y <= b.bottom + s
        }

        /** The waiting pulse at [ms]: 0 drawn in, 1 breathed out, eased by a cosine and back, one breath a [BREATH_MS]. */
        fun breath(ms: Long): Float {
            val t = (((ms % BREATH_MS) + BREATH_MS) % BREATH_MS).toDouble() / BREATH_MS
            return ((1 - cos(2 * PI * t)) / 2).toFloat()
        }

        /** An instrument's few words: `openings` deals hands — "50,000 hands" — the rest by name. */
        fun instrument(name: String, args: JsonObject?): String {
            val n = name.trim().lowercase().replace('-', '_')
            if (n == "openings") {
                val trials = (args?.get("trials") as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() } ?: OPENINGS_TRIALS
                return if (trials > 0) "${grouped(trials)} hands" else "the opening odds"
            }
            return n.replace('_', ' ')
        }

        /** `openings`' trials when none are given (`HandInstruments`). */
        const val OPENINGS_TRIALS = 50_000L

        /** 50000 → "50,000". */
        fun grouped(n: Long): String {
            val s = kotlin.math.abs(n).toString()
            val out = StringBuilder()
            s.forEachIndexed { i, c ->
                if (i > 0 && (s.length - i) % 3 == 0) out.append(',')
                out.append(c)
            }
            return (if (n < 0) "-" else "") + out
        }

        private fun fileName(path: String) = path.substringAfterLast('/')

        private val FILE = Regex("""^[A-Za-z0-9_.\-]+\.[A-Za-z0-9]{1,5}$""")

        /** `deck-odds.js`, `snippet.py`: one token with an extension. */
        fun looksLikeFile(s: String): Boolean = FILE.matches(s)

        /**
         * A file's name in [room] characters, by its parts: the first parts and the last kept, the middle given as `…`
         * (`matchup-labrynth-…-v2.js`), the extension always; failing that cut inside, never under [TabTitles.FLOOR].
         */
        fun shortName(name: String, room: Int): String {
            val r = max(room, TabTitles.FLOOR)
            if (name.length <= r) return name
            val dot = name.lastIndexOf('.')
            val ext = if (dot > 0 && name.length - dot - 1 in 1..5) name.substring(dot) else ""
            val stem = name.substring(0, name.length - ext.length)
            val parts = Regex("""[^\-_. ]+""").findAll(stem).toList()
            if (parts.size >= 2) {
                val last = parts.last()
                val tail = stem.substring(last.range.first - 1) + ext
                for (k in parts.size - 2 downTo 1) {
                    val head = stem.substring(0, parts[k - 1].range.last + 2)
                    val candidate = "$head…$tail"
                    if (candidate.length <= r) return candidate
                }
            }
            val keep = (r - 1 - ext.length).coerceAtLeast(TabTitles.FLOOR - 1).coerceAtMost(stem.length)
            return stem.substring(0, keep) + "…" + ext
        }
    }
}
