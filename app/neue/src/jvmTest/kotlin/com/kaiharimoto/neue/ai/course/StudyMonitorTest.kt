package com.kaiharimoto.neue.ai.course

import com.kaiharimoto.mastertool.core.ai.Part
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The study, watched (kai, 2026-10): what it reads in one pane, what it writes — and what was refused — in the other. */
class StudyMonitorTest {
    private fun call(name: String, json: String) = Part.ToolUse("c", name, Json.parseToJsonElement(json).jsonObject)

    @Test
    fun readsGoLeftAndWritesGoRightAsTheyHappen() {
        val m = StudyMonitor()
        m.saw(call("course_read", """{"chapter":4}"""), Part.ToolResult("c", "course_read", "<untrusted source=\"x\">\n## §1 Going first\nOpen with Aluber.\n</untrusted>", summary = "Read chapter 4: Going first"))
        assertEquals("ch. 4", m.reading?.ref)
        assertTrue(m.reading!!.text.startsWith("## §1 Going first"), m.reading!!.text)
        m.saw(call("course_notes", """{"chapter":4,"notes":"- Open with Aluber (ch. 4 §1)."}"""), Part.ToolResult("c", "course_notes", "kept"))
        m.saw(
            call("mcp__neue__playbook_write", """{"op":"add","entries":[{"kind":"line","title":"Aluber line","needs":["Aluber"],"steps":[{"card":"Aluber","action":"Normal Summon","result":"search Branded Fusion"}],"end_board":"Mirrorjade"}]}"""),
            Part.ToolResult("c", "playbook_write", "Kept line-1"),
        )
        m.saw(call("playbook_write", """{"op":"add","entries":[]}"""), Part.ToolResult("c", "playbook_write", "Entry 1 not kept: it needs a title.", isError = true))
        assertEquals(listOf("notes", "playbook", "refused"), m.written.map { it.kind })
        assertEquals("ch. 4 notes", m.written[0].ref)
        assertTrue("1. Aluber: Normal Summon → search Branded Fusion" in m.written[1].text, m.written[1].text)
        assertTrue("Ends on: Mirrorjade" in m.written[1].text)
        m.thought("Aluber first, ")
        m.thought("then Fusion.")
        assertEquals("Aluber first, then Fusion.", m.thinking)
        m.step()
        assertEquals("", m.thinking)
    }
}
