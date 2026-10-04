package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.mcp.McpServerCore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerCoreTest {
    private val calls = mutableListOf<Part.ToolUse>()
    private val server = McpServerCore(
        tools = { listOf(AiTools.appState, AiTools.deleteDeck) },
        call = { c -> calls += c; Part.ToolResult(c.id, c.name, "state!", isError = c.name == "delete_deck") },
        instructions = "Use the app.",
    )

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun initializeAnswersTheVersionAskedWhenItKnowsIt() = runTest {
        val reply = server.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{}}}""")
        assertEquals(200, reply.status)
        val result = json(reply.body!!)["result"]!!.jsonObject
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("Use the app.", result["instructions"]!!.jsonPrimitive.content)
        val unknown = server.handle("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2099-01-01"}}""")
        assertEquals(McpServerCore.VERSIONS.first(), json(unknown.body!!)["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationsAreAcknowledgedWithNoBody() = runTest {
        val reply = server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, reply.status)
        assertNull(reply.body)
    }

    @Test
    fun toolsAreListedWithTheirSchemasAndDestruction() = runTest {
        val tools = json(server.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").body!!)["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("app_state", "delete_deck"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(tools[1].jsonObject["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.boolean)
        assertEquals("object", tools[0].jsonObject["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCallRunsTheToolAndCarriesItsError() = runTest {
        val ok = json(server.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"app_state","arguments":{}}}""").body!!)
        assertEquals("state!", ok["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val bad = json(server.handle("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"delete_deck","arguments":{"deck_id":"x"}}}""").body!!)
        assertTrue(bad["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertEquals("x", calls.last().input["deck_id"]!!.jsonPrimitive.content)
        val missing = json(server.handle("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"nope"}}""").body!!)
        assertEquals(McpServerCore.INVALID_PARAMS, missing["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun batchesAndGarbage() = runTest {
        val batch = server.handle("""[{"jsonrpc":"2.0","id":7,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/cancelled"}]""")
        assertEquals(1, Json.parseToJsonElement(batch.body!!).jsonArray.size)
        assertEquals(400, server.handle("not json").status)
        assertTrue(McpServerCore.authorised("Bearer abc", "abc"))
        assertTrue(!McpServerCore.authorised("Bearer abd", "abc"))
        assertTrue(!McpServerCore.authorised(null, "abc"))
    }

    @Test
    fun theOriginIsParsedWholeNeverSearched() {
        // No Origin is a CLI's request; an exact loopback origin is allowed.
        listOf(null, "http://127.0.0.1", "http://127.0.0.1:53682", "https://localhost", "http://LOCALHOST:8080", "http://[::1]:9000")
            .forEach { assertTrue(McpServerCore.originAllowed(it), "$it should pass") }
        // 1.0.99, the red team: the old check was a substring match, and each of these contains a loopback name.
        listOf(
            "http://127.0.0.1.evil.com", "http://localhost.attacker", "http://localhost.attacker:80", "https://evil.com/127.0.0.1",
            "http://evil.com?localhost", "http://127.0.0.1@evil.com", "http://localhost:80@evil.com", "http://evil-localhost",
            "http://127.0.0.1:99999", "http://127.0.0.1:0", "http://127.0.0.1:", "http://localhost/", "ws://localhost",
            "file://localhost", "null", "", " http://localhost", "http://127.0.0.2", "http://0.0.0.0",
        ).forEach { assertTrue(!McpServerCore.originAllowed(it), "$it should be refused") }
    }
}
