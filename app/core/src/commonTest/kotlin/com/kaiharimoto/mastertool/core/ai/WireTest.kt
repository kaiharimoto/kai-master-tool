package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.cli.ClaudeCli
import com.kaiharimoto.mastertool.core.ai.cli.ClaudeStream
import com.kaiharimoto.mastertool.core.ai.cli.CliCarry
import com.kaiharimoto.mastertool.core.ai.cli.CliWeb
import com.kaiharimoto.mastertool.core.ai.cli.CodexCli
import com.kaiharimoto.mastertool.core.ai.cli.CodexStream
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiChatBackend
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiEndpoint
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiStream
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiWire
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTest {

    @Test
    fun toolCallsArriveInPiecesAndComeOutWhole() {
        val s = OpenAiStream()
        listOf(
            """data: {"choices":[{"delta":{"content":"Adding "}}]}""",
            """data: {"choices":[{"delta":{"content":"Ash."}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"edit_","arguments":"{\"ops\":"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"deck","arguments":"[]}"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"app_state","arguments":""}}]}}]}""",
            """data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":80}}}""",
            "data: [DONE]",
        ).flatMap { s.line(it) }.let { events ->
            assertEquals("Adding Ash.", events.filterIsInstance<BackendEvent.TextDelta>().joinToString("") { it.text })
        }
        val done = s.finish()
        assertIs<BackendEvent.Finished>(done)
        assertEquals(StopReason.TOOL_USE, done.stop)
        val calls = done.turn!!.toolUses
        assertEquals(listOf("edit_deck", "app_state"), calls.map { it.name })
        assertEquals("call_1", calls[0].id)
        assertEquals(0, calls[0].input["ops"]!!.jsonArray.size)
        assertEquals(JsonObject(emptyMap()), calls[1].input)
        assertEquals(80, done.usage!!.cacheRead)
    }

    @Test
    fun cutShortArgumentsAreMarkedNotGuessed() {
        val s = OpenAiStream()
        s.line("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"new_deck","arguments":"{\"name\": \"Bra"}}]}}]}""")
        val call = (s.finish() as BackendEvent.Finished).turn!!.toolUses.single()
        assertTrue(OpenAiStream.BROKEN_ARGS in call.input)
    }

    @Test
    fun theHistoryBecomesChatMessages() {
        val history = listOf(
            ChatTurn.user("Hi", context = "Page: Builder"),
            ChatTurn(Role.ASSISTANT, listOf(Part.Text("Looking."), Part.ToolUse("c1", "app_state", JsonObject(emptyMap())))),
            ChatTurn(Role.USER, listOf(Part.ToolResult("c1", "app_state", "{}"))),
            ChatTurn.assistant("You are on the builder."),
        )
        val messages = OpenAiWire.messages("You are Ai.", history)
        val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant"), roles)
        val first = messages[1].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(first.startsWith("<app_context>") && first.endsWith("Hi"))
        val call = messages[2].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("app_state", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("c1", messages[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun theBodyCarriesToolsAndOnlyTheEffortTheEndpointTakes() {
        val req = TurnRequest("s", listOf(ChatTurn.user("x")), listOf(AiTools.appState), model = "gpt-x", effort = "high")
        val openai = OpenAiWire.body(req, OpenAiEndpoint("https://api.openai.com/v1", sendsEffort = true))
        assertEquals("high", openai["reasoning_effort"]!!.jsonPrimitive.content)
        val local = OpenAiWire.body(req, OpenAiEndpoint("http://localhost:11434/v1"))
        assertNull(local["reasoning_effort"])
        assertEquals("app_state", local["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun theBackendStreamsOverHttp() = runTest {
        var sentBody = ""
        var auth: String? = null
        val engine = MockEngine { request ->
            sentBody = String(request.body.toByteArray())
            auth = request.headers[HttpHeaders.Authorization]
            respond(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}\n\ndata: [DONE]\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val backend = OpenAiChatBackend(HttpClientFactory.create(engine), OpenAiEndpoint("https://example.test/v1", apiKey = "k"))
        val events = backend.turn(TurnRequest("sys", listOf(ChatTurn.user("hi")), emptyList(), model = "m")).toList()
        assertEquals("Bearer k", auth)
        assertTrue("\"model\":\"m\"" in sentBody)
        val done = events.last()
        assertIs<BackendEvent.Finished>(done)
        assertEquals("Hello", done.turn!!.text)
    }

    @Test
    fun aRejectedKeyIsAnAuthFailure() = runTest {
        val engine = MockEngine { respond("""{"error":{"message":"Incorrect API key"}}""", HttpStatusCode.Unauthorized) }
        val backend = OpenAiChatBackend(HttpClientFactory.create(engine), OpenAiEndpoint("https://example.test/v1", apiKey = "bad"))
        val failed = backend.turn(TurnRequest("s", listOf(ChatTurn.user("x")), emptyList())).toList().single()
        assertIs<BackendEvent.Failed>(failed)
        assertTrue(failed.auth)
        assertTrue("Incorrect API key" in failed.message)
    }

    @Test
    fun modelListsAreReadInEveryShape() {
        assertEquals(listOf("gpt-a", "gpt-b"), OpenAiWire.modelIds("""{"object":"list","data":[{"id":"gpt-a"},{"id":"gpt-b"}]}"""))
        assertEquals(listOf("gemini-x"), OpenAiWire.modelIds("""{"data":[{"id":"models/gemini-x"}]}"""))
    }

    @Test
    fun claudeCodeStreamsTextAndEndsWithItsResult() {
        val s = ClaudeStream()
        val lines = listOf(
            """{"type":"system","subtype":"init","session_id":"abc","mcp_servers":[{"name":"neue","status":"connected"}]}""",
            """{"type":"stream_event","event":{"type":"message_start"}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me "}}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"look."}}}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Let me look."},{"type":"tool_use","id":"t1","name":"mcp__neue__app_state","input":{}},{"type":"tool_use","id":"t2","name":"WebSearch","input":{"query":"YCS Paris top 8"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"{}"}]}}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."}]}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"Done.","session_id":"abc","total_cost_usd":0.012,"usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        val events = lines.flatMap { s.line(it) }
        assertEquals("abc", events.filterIsInstance<BackendEvent.Session>().single().id)
        val seen = events.filterIsInstance<BackendEvent.ToolSeen>()
        assertEquals(listOf("WebSearch"), seen.map { it.name }, "the app's own tools are the app's to show")
        val done = s.finished
        assertIs<BackendEvent.Finished>(done)
        assertEquals("Let me look.\n\nDone.", done.text)
        assertEquals(0.012, done.usage!!.costUsd)
    }

    @Test
    fun claudeCodeNotLoggedInIsAnAuthFailure() {
        val s = ClaudeStream()
        s.line("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key · Please run /login"}""")
        val failed = s.finished
        assertIs<BackendEvent.Failed>(failed)
        assertTrue(failed.auth)
        assertEquals(true, ClaudeCli.loggedIn("""{"loggedIn": true, "authMethod": "oauth_token"}"""))
        assertEquals(false, ClaudeCli.loggedIn("""{"loggedIn": false}"""))
    }

    @Test
    fun claudeLaunchesWithTheAppsToolsOnly() {
        val l = ClaudeCli.launch("claude", "hi", "/tmp/s.md", "/tmp/m.json", model = "opus", effort = "high", resume = "s1", web = CliWeb(search = true, fetch = true))
        val a = l.args
        assertEquals("hi", l.stdin)
        assertTrue(a.containsAll(listOf("-p", "--strict-mcp-config", "--permission-prompts", "none")))
        assertEquals("WebSearch,WebFetch", a[a.indexOf("--tools") + 1])
        assertEquals("mcp__neue,WebSearch,WebFetch", a[a.indexOf("--allowedTools") + 1])
        assertTrue("--disallowedTools" !in a)
        assertEquals("s1", a[a.indexOf("--resume") + 1])
        assertTrue("Bearer tok" in ClaudeCli.mcpConfig("http://127.0.0.1:5/mcp", "tok"))
    }

    @Test
    fun theClisWebFollowsTheAppsOwn() {
        // A turn offered the web: the CLI's own web tools with it.
        val all = AiTools.all.map { it.name }
        assertEquals(CliWeb(search = true, fetch = true), CliWeb.offered(all))
        // From first principles bars the web: the CLI's goes with it.
        val principles = CliWeb.offered(all.filter { it !in AiTools.FIRST_PRINCIPLES_BARRED })
        assertEquals(CliWeb.NONE, principles)
        assertEquals(CliWeb(search = true), CliWeb.offered(listOf("web_search", "card_info")))

        val closed = ClaudeCli.launch("claude", "hi", "/s", "/m", "", "", null, web = principles).args
        assertTrue("--tools=" in closed, "every built-in tool off, in one argument with no quotes: $closed")
        assertTrue("--tools" !in closed)
        assertTrue(closed.none { it.isEmpty() }, "no empty argument for a .cmd shim to mangle")
        assertEquals("mcp__neue", closed[closed.indexOf("--allowedTools") + 1])
        assertEquals("WebSearch,WebFetch", closed[closed.indexOf("--disallowedTools") + 1])
        assertTrue(closed.none { "WebSearch" in it && it != "WebSearch,WebFetch" })

        // Nothing said, nothing open: the default is closed.
        assertEquals(closed, ClaudeCli.launch("claude", "hi", "/s", "/m", "", "", null).args)

        val searchOnly = ClaudeCli.launch("claude", "hi", "/s", "/m", "", "", null, web = CliWeb(search = true)).args
        assertEquals("WebSearch", searchOnly[searchOnly.indexOf("--tools") + 1])
        assertEquals("mcp__neue,WebSearch", searchOnly[searchOnly.indexOf("--allowedTools") + 1])
        assertEquals("WebFetch", searchOnly[searchOnly.indexOf("--disallowedTools") + 1])

        val codexOpen = CodexCli.launch("codex", "hi", "/w", "u", "t", "", "", null, web = CliWeb(search = true, fetch = true)).args
        assertTrue("web_search=live" in codexOpen, codexOpen.toString())
        val codexClosed = CodexCli.launch("codex", "hi", "/w", "u", "t", "", "", null, web = principles).args
        assertTrue("web_search=disabled" in codexClosed, codexClosed.toString())
        assertTrue(codexClosed.none { it == "web_search=live" })
        assertEquals(codexClosed, CodexCli.launch("codex", "hi", "/w", "u", "t", "", "", null).args)
    }

    @Test
    fun theTokenNeverRidesOnTheCommandLine() {
        // 1.0.99, the red team: anything on the command line is in every process listing.
        val codex = CodexCli.launch("codex", "hi", "/run", "http://127.0.0.1:5/mcp", "secret-token", "", "", null)
        assertTrue(codex.args.none { "secret-token" in it }, codex.args.toString())
        assertEquals("secret-token", codex.env[CodexCli.TOKEN_ENV])
        // The strictest sandbox Codex has, in the working folder it was given.
        assertEquals("read-only", codex.args[codex.args.indexOf("-s") + 1])
        assertEquals("/run", codex.args[codex.args.indexOf("-C") + 1])
        val claude = ClaudeCli.launch("claude", "hi", "/run/system-1.md", "/run/mcp-1.json", "", "", null)
        assertTrue(claude.args.none { "Bearer" in it }, claude.args.toString())
    }

    @Test
    fun aConversationTheCliLostIsCarriedIntoANewSession() {
        assertTrue(CliCarry.lostSession("No conversation found with session ID: 1b2c"))
        assertTrue(!CliCarry.lostSession("Invalid API key"))
        assertTrue(!CliCarry.lostSession(null))
        val history = listOf(
            ChatTurn.user("Build me a deck", "Page: Builder"),
            ChatTurn(Role.ASSISTANT, listOf(Part.Text("Looking."), Part.ToolUse("t1", "app_state", JsonObject(emptyMap())))),
            ChatTurn(Role.USER, listOf(Part.ToolResult("t1", "app_state", "{\"secret\":1}"))),
            ChatTurn(Role.ASSISTANT, listOf(Part.Text("Here it is."))),
            ChatTurn.user("Now side it"),
        )
        val carried = CliCarry.message(history, "Now side it")
        assertTrue(carried.endsWith("Now side it"), carried)
        assertTrue("Person: Build me a deck" in carried && "Ai: Looking." in carried && "Ai: Here it is." in carried, carried)
        // Tool traffic and the page's context stay behind; the new message is not carried twice.
        assertTrue("secret" !in carried && "Page: Builder" !in carried, carried)
        assertEquals(1, Regex("Now side it").findAll(carried).count())
        // Nothing before: the message alone. Over the cap: the newest kept.
        assertEquals("hi", CliCarry.message(listOf(ChatTurn.user("hi")), "hi"))
        val capped = CliCarry.message(history, "Now side it", cap = 30)
        assertTrue("Ai: Here it is." in capped && "Build me a deck" !in capped, capped)
    }

    @Test
    fun codexStreamsItsItemsAndFailures() {
        val s = CodexStream()
        val events = listOf(
            """{"type":"thread.started","thread_id":"th-1"}""",
            """{"type":"turn.started"}""",
            """{"type":"item.started","item":{"id":"i0","type":"mcp_tool_call","server":"neue","tool":"edit_deck","arguments":{}}}""",
            """{"type":"item.completed","item":{"id":"i1","type":"agent_message","text":"Built it."}}""",
            """{"type":"turn.completed","usage":{"input_tokens":5,"cached_input_tokens":2,"output_tokens":3}}""",
        ).flatMap { s.line(it) }
        assertEquals("th-1", events.filterIsInstance<BackendEvent.Session>().single().id)
        assertTrue(events.none { it is BackendEvent.ToolSeen }, "the app's own tool is the app's to show")
        val done = s.finished
        assertIs<BackendEvent.Finished>(done)
        assertEquals("Built it.", done.text)

        val f = CodexStream()
        f.line("""{"type":"error","message":"Reconnecting... 2/5 (unexpected status 401 Unauthorized)"}""")
        f.line("""{"type":"turn.failed","error":{"message":"unexpected status 401 Unauthorized: Missing bearer"}}""")
        val failed = f.finished
        assertIs<BackendEvent.Failed>(failed)
        assertTrue(failed.auth)
    }

    @Test
    fun codexLaunchesReadOnlyWithTheAppsServer() {
        val l = CodexCli.launch("codex", "hi", "/data/ai", "http://127.0.0.1:9/mcp", "tok", model = "", effort = "low", resume = "th-1")
        val a = l.args
        assertEquals(listOf("codex", "exec", "--json"), a.take(3))
        assertEquals("read-only", a[a.indexOf("-s") + 1])
        assertTrue(a.contains("approval_policy=never"))
        assertTrue(a.none { '"' in it }, "no quotes for a .cmd shim to mangle")
        assertEquals(listOf("resume", "th-1", "-"), a.takeLast(3))
        assertEquals("tok", l.env[CodexCli.TOKEN_ENV])
        assertEquals(false, CodexCli.loggedIn(1, "Not logged in"))
        assertEquals(true, CodexCli.loggedIn(0, "Logged in using ChatGPT"))
    }

    @Suppress("unused")
    private fun p(s: String) = JsonPrimitive(s)

    @Test
    fun aTurnThatWasAllThoughtIsNotSentAsNullContent() {
        val history = listOf(
            ChatTurn(Role.USER, listOf(Part.Text("Hi"))),
            ChatTurn(Role.ASSISTANT, listOf(Part.Reasoning("thinking, cut off"))),
            ChatTurn(Role.USER, listOf(Part.Text("Still there?"))),
        )
        val messages = OpenAiWire.messages("", history)
        assertEquals(2, messages.size, messages.toString())
        assertTrue("null" !in messages.toString(), messages.toString())
    }
}
