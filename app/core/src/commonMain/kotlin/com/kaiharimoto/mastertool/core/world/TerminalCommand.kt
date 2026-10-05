package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A line typed at the World's Terminal (`docs/world/DESKTOP.md` §3.1, `TerminalCommandTest`): a terminal you cannot type
 * in reads as a picture of one. Parsed here; the Terminal runs it as the person's own (`by: you`). Ai never types here.
 *
 * | Line | Does |
 * |---|---|
 * | `run openings.js` | runs a file |
 * | `js 1 - ygo.atLeast(40, 3, 5, 1)` | evaluates JavaScript in the world and prints the value |
 * | `py …` | the same in Python, where Python is allowed on this computer |
 * | `tool openings {"deck":"open"}` | runs an instrument |
 * | `open world://boards/b3-9f2k` | opens an address |
 * | `ls`, `ls lib`, `cat notes/plan.md` | the tree, a file |
 * | `clear`, `help` | |
 */
sealed interface TerminalCommand {
    data class Run(val path: String) : TerminalCommand
    data class Js(val code: String) : TerminalCommand
    data class Py(val code: String) : TerminalCommand
    data class Tool(val name: String, val args: JsonObject) : TerminalCommand
    data class Open(val address: WorldAddress) : TerminalCommand
    data class Ls(val path: String?) : TerminalCommand
    data class Cat(val path: String) : TerminalCommand
    data object Clear : TerminalCommand
    data object Help : TerminalCommand

    /** Nothing typed. */
    data object Blank : TerminalCommand

    /** A line that does not read: [why], in words that say what would. */
    data class Wrong(val why: String) : TerminalCommand

    /** What Tab did: the line completed as far as it is sure, and the choices when more than one is left. */
    data class Completion(val line: String, val choices: List<String>)

    companion object {
        /** The help, as the Terminal prints it: each command and what it does. */
        val HELP: List<Pair<String, String>> = listOf(
            "run <file>" to "Runs a .js or .py file of this world",
            "js <code>" to "Evaluates JavaScript here and prints the value",
            "py <code>" to "The same in Python, where Python is allowed on this computer",
            "tool <name> {json}" to "Runs an instrument with its arguments",
            "open <address>" to "Opens a page, a file or an app: world://home, world://boards/<id>, world://files/<path>",
            "ls [folder]" to "Lists the world's files",
            "cat <file>" to "Prints a file",
            "clear" to "Clears the Terminal",
            "help" to "This list",
        )

        private val VERBS = listOf("run", "js", "py", "tool", "open", "ls", "cat", "clear", "help")

        fun parse(line: String): TerminalCommand {
            val s = line.trim()
            if (s.isEmpty()) return Blank
            val verb = s.substringBefore(' ').lowercase()
            val rest = s.substringAfter(' ', "").trim()
            return when (verb) {
                "run" -> when {
                    rest.isEmpty() -> Wrong("run needs a file: run openings.js")
                    else -> WorldPaths.safe(rest)?.let { p ->
                        if (WorldPaths.lang(p) == null) Wrong("$p is not code: only .js and .py files run") else Run(p)
                    } ?: Wrong("“$rest” is not a path inside the world")
                }
                "js", "node" -> if (rest.isEmpty()) Wrong("js needs code: js ygo.atLeast(40, 3, 5, 1)") else Js(rest)
                "py", "python", "python3" -> if (rest.isEmpty()) Wrong("py needs code: py print(1 + 1)") else Py(rest)
                "tool", "instrument" -> tool(rest)
                "open" -> if (rest.isEmpty()) Wrong("open needs an address: open world://home") else when (val a = WorldAddress.parse(rest)) {
                    is WorldAddress.Unknown -> Wrong("no page at $rest: ${a.why}")
                    else -> Open(a)
                }
                "ls", "dir" -> if (rest.isEmpty()) Ls(null) else WorldPaths.safe(rest)?.let { Ls(it) } ?: Wrong("“$rest” is not a folder inside the world")
                "cat", "type" -> if (rest.isEmpty()) Wrong("cat needs a file: cat notes/plan.md") else WorldPaths.safe(rest)?.let(::Cat) ?: Wrong("“$rest” is not a path inside the world")
                "clear", "cls" -> Clear
                "help", "?" -> Help
                else -> Wrong("no command “$verb” — ${VERBS.joinToString(", ")}; help lists them")
            }
        }

        private fun tool(rest: String): TerminalCommand {
            if (rest.isEmpty()) return Wrong("tool needs an instrument: tool openings {\"deck\":\"open\"} — ${Instruments.ALL.joinToString { it.name }}")
            val name = rest.substringBefore(' ').substringBefore('{').trim()
            val json = rest.removePrefix(name).trim()
            val args = if (json.isEmpty()) JsonObject(emptyMap()) else try {
                WorldCodec.json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                return Wrong("the arguments are a JSON object: tool $name {\"deck\":\"open\"}")
            }
            if (Instruments.ALL.none { it.name == name } && name !in setOf("guide", "list")) {
                return Wrong("no instrument “$name” — ${Instruments.ALL.joinToString { it.name }}")
            }
            return Tool(name, args)
        }

        /**
         * Tab on [line]: the verb, a file name (for run, cat, ls and `open world://files/`) or an instrument (for tool),
         * completed as far as every choice agrees.
         */
        fun complete(line: String, files: List<String>, instruments: List<String> = Instruments.ALL.map { it.name }): Completion {
            val lead = line.trimStart()
            if (' ' !in lead) return finish("", lead, VERBS.map { "$it " })
            val verb = lead.substringBefore(' ').lowercase()
            val word = lead.substringAfter(' ').trimStart()
            val head = lead.substring(0, lead.length - word.length)
            val pool = when (verb) {
                "run" -> files.filter { WorldPaths.lang(it) != null }
                "cat", "ls" -> files
                "tool", "instrument" -> instruments.map { "$it " }
                "open" -> files.map { WorldAddress.File(it).format() } + WorldAddress.HOME
                else -> emptyList()
            }
            return finish(head, word, pool)
        }

        private fun finish(head: String, word: String, pool: List<String>): Completion {
            val choices = pool.filter { it.startsWith(word) }.sorted()
            if (choices.isEmpty()) return Completion(head + word, emptyList())
            val common = choices.reduce { a, b -> a.commonPrefixWith(b) }
            return Completion(head + common, if (choices.size > 1) choices.map { it.trimEnd() } else emptyList())
        }
    }
}

/** The Terminal's ↑ and ↓: lines typed before, newest last, at most [MAX]. */
class TerminalHistory(lines: List<String> = emptyList()) {
    private val lines = ArrayList(lines.takeLast(MAX))
    private var at = this.lines.size

    val all: List<String> get() = lines

    fun push(line: String) {
        val s = line.trim()
        if (s.isNotEmpty() && lines.lastOrNull() != s) lines += s
        while (lines.size > MAX) lines.removeAt(0)
        at = lines.size
    }

    /** The line before, or null at the oldest. */
    fun up(): String? {
        if (at <= 0) return null
        at--
        return lines[at]
    }

    /** The line after; "" past the newest. */
    fun down(): String? {
        if (at >= lines.size) return null
        at++
        return if (at == lines.size) "" else lines[at]
    }

    companion object {
        const val MAX = 200
    }
}
