package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalCommandTest {
    @Test
    fun theCommands() {
        assertEquals(TerminalCommand.Run("openings.js"), TerminalCommand.parse("run openings.js"))
        assertEquals(TerminalCommand.Run("lib/sim.py"), TerminalCommand.parse("  run   lib/sim.py "))
        assertEquals(TerminalCommand.Js("1 - ygo.atLeast(40, 3, 5, 1)"), TerminalCommand.parse("js 1 - ygo.atLeast(40, 3, 5, 1)"))
        assertEquals(TerminalCommand.Py("print(1 + 1)"), TerminalCommand.parse("py print(1 + 1)"))
        val tool = TerminalCommand.parse("""tool openings {"deck":"open","trials":0}""")
        assertIs<TerminalCommand.Tool>(tool)
        assertEquals("openings", tool.name)
        assertEquals(JsonPrimitive("open"), tool.args["deck"])
        assertEquals(TerminalCommand.Tool("composition", JsonObject(emptyMap())), TerminalCommand.parse("tool composition"))
        assertEquals(TerminalCommand.Open(WorldAddress.Board("b3-9f2k")), TerminalCommand.parse("open world://boards/b3-9f2k"))
        assertEquals(TerminalCommand.Ls(null), TerminalCommand.parse("ls"))
        assertEquals(TerminalCommand.Ls("lib"), TerminalCommand.parse("ls lib"))
        assertEquals(TerminalCommand.Cat("notes/plan.md"), TerminalCommand.parse("cat notes/plan.md"))
        assertEquals(TerminalCommand.Clear, TerminalCommand.parse("clear"))
        assertEquals(TerminalCommand.Help, TerminalCommand.parse("HELP"))
        assertEquals(TerminalCommand.Blank, TerminalCommand.parse("   "))
    }

    @Test
    fun wrongLinesSayWhatWould() {
        listOf(
            "run" to "run needs a file",
            "run notes.md" to "not code",
            "run ../x.js" to "not a path",
            "tool" to "tool needs an instrument",
            "tool nothing" to "no instrument",
            "tool openings {not json" to "JSON object",
            "open https://example.com" to "no page",
            "cat /etc/passwd" to "not a path",
            "rm -rf /" to "no command",
            "js" to "js needs code",
        ).forEach { (line, why) ->
            val c = TerminalCommand.parse(line)
            assertIs<TerminalCommand.Wrong>(c, line)
            assertTrue(why in c.why, "“$line”: ${c.why}")
        }
    }

    @Test
    fun tabCompletesFilesAndInstruments() {
        val files = listOf("openings.js", "openings_old.js", "notes/plan.md", "lib/sim.py")
        assertEquals("run openings", TerminalCommand.complete("run op", files).line)
        assertEquals(listOf("openings.js", "openings_old.js"), TerminalCommand.complete("run op", files).choices)
        assertEquals("run lib/sim.py", TerminalCommand.complete("run lib", files).line)
        assertTrue(TerminalCommand.complete("run lib", files).choices.isEmpty())
        assertEquals("cat notes/plan.md", TerminalCommand.complete("cat no", files).line)
        assertEquals("tool card_web ", TerminalCommand.complete("tool card", files).line)
        assertEquals("tool com", TerminalCommand.complete("tool co", files).line, "combos and composition share com")
        assertEquals("run ", TerminalCommand.complete("ru", files).line)
        assertEquals("open world://files/notes/plan.md", TerminalCommand.complete("open world://files/n", files).line)
    }

    @Test
    fun upRecalls() {
        val h = TerminalHistory()
        h.push("ls")
        h.push("run a.js")
        h.push("run a.js")
        assertEquals(listOf("ls", "run a.js"), h.all, "a line twice in a row is kept once")
        assertEquals("run a.js", h.up())
        assertEquals("ls", h.up())
        assertNull(h.up())
        assertEquals("run a.js", h.down())
        assertEquals("", h.down())
        assertNull(h.down())
    }

    @Test
    fun theHelpNamesEveryCommand() {
        listOf("run", "js", "py", "tool", "open", "ls", "cat", "clear", "help").forEach { v ->
            assertTrue(TerminalCommand.HELP.any { it.first.startsWith(v) }, v)
        }
    }
}
