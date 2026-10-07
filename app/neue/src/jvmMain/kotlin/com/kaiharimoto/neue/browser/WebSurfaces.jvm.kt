package com.kaiharimoto.neue.browser

import com.kaiharimoto.mastertool.core.ai.course.PageElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * On the desk the study's browser is the person's own Chrome or Edge (or Chromium, Brave), run by the app in a profile of
 * its own and driven over the Chrome DevTools Protocol: nothing is bundled, and a real browser plays what a page plays.
 */
actual object WebSurfaces {
    actual fun missing(custom: String): String? =
        if (ChromeFinder.find(custom) == null) "No Chrome, Edge or Chromium was found on this computer. Install one, or name yours in Settings." else null

    actual suspend fun launch(profile: File, start: String, custom: String, visible: Boolean): WebSurface =
        ChromeSurface.launch(ChromeFinder.find(custom) ?: error(missing(custom)!!), profile, start, visible)
}

/** Where a Chromium browser lives on this desk, the person's own choice first. */
object ChromeFinder {
    fun find(custom: String): File? {
        if (custom.isNotBlank()) return File(custom).takeIf { it.canExecute() }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val candidates = when {
            "win" in os -> listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")).flatMap { root ->
                listOf(
                    "$root\\Google\\Chrome\\Application\\chrome.exe",
                    "$root\\Microsoft\\Edge\\Application\\msedge.exe",
                    "$root\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                    "$root\\Chromium\\Application\\chrome.exe",
                )
            }
            "mac" in os -> listOf("Google Chrome", "Microsoft Edge", "Chromium", "Brave Browser").flatMap { app ->
                listOf("/Applications/$app.app/Contents/MacOS/$app", System.getProperty("user.home") + "/Applications/$app.app/Contents/MacOS/$app")
            }
            else -> listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "microsoft-edge-stable", "brave-browser")
                .flatMap { name -> (System.getenv("PATH").orEmpty().split(File.pathSeparator) + "/snap/bin").map { "$it/$name" } }
        }
        return candidates.asSequence().map(::File).firstOrNull { it.isFile && it.canExecute() }
    }
}

/**
 * One browser and its one page, over the DevTools Protocol: the browser's own WebSocket from `DevToolsActivePort`, the
 * page attached as a flat session. Commands wait for their answers; the page's events are not needed and are let go.
 */
class ChromeSurface private constructor(private val process: Process, private val socket: WebSocket, private val inbox: Inbox) : WebSurface {
    private var session: String = ""
    private val ids = AtomicInteger(0)
    private var ratio = 1.0

    override val alive: Boolean get() = process.isAlive && !inbox.closed

    /** Every answer to a command, by its id; the text of a message arrives in parts. */
    class Inbox : WebSocket.Listener {
        val waiting = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val part = StringBuilder()
        @Volatile var closed = false

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            part.append(data)
            if (last) {
                val text = part.toString()
                part.setLength(0)
                runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { m ->
                    m["id"]?.jsonPrimitive?.int?.let { id -> waiting.remove(id)?.complete(m) }
                }
            }
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, status: Int, reason: String): CompletionStage<*>? {
            closed = true
            waiting.values.forEach { it.completeExceptionally(IllegalStateException("The browser closed.")) }
            return null
        }

        override fun onError(ws: WebSocket, error: Throwable) {
            closed = true
            waiting.values.forEach { it.completeExceptionally(error) }
        }
    }

    /** Sends [method] and waits for its answer; to the page unless [browser]. */
    private suspend fun send(method: String, params: JsonObject = JsonObject(emptyMap()), browser: Boolean = false, timeoutMs: Long = 30_000): JsonObject {
        if (inbox.closed) error("The browser was closed.")
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        inbox.waiting[id] = answer
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
            if (!browser && session.isNotEmpty()) put("sessionId", session)
        }
        socket.sendText(message.toString(), true).await()
        val reply = try {
            withTimeout(timeoutMs) { answer.await() }
        } finally {
            inbox.waiting.remove(id)
        }
        reply["error"]?.let { error("$method: " + (it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it.toString())) }
        return reply["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /** [expression] run in the page, its value as a string (JSON when the script made it so). */
    private suspend fun eval(expression: String): String {
        val r = send("Runtime.evaluate", buildJsonObject {
            put("expression", expression)
            put("returnByValue", true)
            put("awaitPromise", true)
        })
        r["exceptionDetails"]?.let { error("The page's script failed: " + (it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "")) }
        val v = r["result"]?.jsonObject?.get("value") ?: return ""
        return (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
    }

    private suspend fun attach() {
        val targets = send("Target.getTargets", browser = true)["targetInfos"]?.let { it as? kotlinx.serialization.json.JsonArray }.orEmpty()
        val page = targets.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "page" }
            ?.get("targetId")?.jsonPrimitive?.contentOrNull
            ?: send("Target.createTarget", buildJsonObject { put("url", "about:blank") }, browser = true)["targetId"]!!.jsonPrimitive.content
        session = send("Target.attachToTarget", buildJsonObject {
            put("targetId", page)
            put("flatten", true)
        }, browser = true)["sessionId"]!!.jsonPrimitive.content
        send("Page.enable")
        send("Runtime.enable")
        ratio = eval(PageScripts.RATIO).toDoubleOrNull() ?: 1.0
    }

    override suspend fun open(url: String): WebSurface.Loaded {
        send("Page.navigate", buildJsonObject { put("url", url) })
        settle()
        return here()
    }

    /** Waits until the page has loaded and its text has stopped growing (a page that draws itself with scripts). */
    private suspend fun settle() {
        delay(400)
        var last = -1
        var steady = 0
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            val s = runCatching { Json.parseToJsonElement(eval(PageScripts.SETTLED)).jsonObject }.getOrNull()
            val ready = s?.get("ready")?.jsonPrimitive?.contentOrNull == "complete"
            val size = s?.get("size")?.jsonPrimitive?.int ?: -1
            steady = if (ready && size == last) steady + 1 else 0
            if (steady >= 3) break
            last = size
            delay(500)
        }
        ratio = runCatching { eval(PageScripts.RATIO).toDouble() }.getOrDefault(ratio)
    }

    override suspend fun here(): WebSurface.Loaded {
        val o = Json.parseToJsonElement(eval(PageScripts.HERE)).jsonObject
        return WebSurface.Loaded(o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    override suspend fun html(): String = eval(PageScripts.HTML)

    override suspend fun links(): List<Pair<String, String>> =
        Json.parseToJsonElement(eval(PageScripts.LINKS)).let { it as kotlinx.serialization.json.JsonArray }.map { pair ->
            val p = pair as kotlinx.serialization.json.JsonArray
            p[0].jsonPrimitive.content to p[1].jsonPrimitive.content
        }

    override suspend fun elements(): List<PageElement> = json.decodeFromString(ListSerializer(PageElement.serializer()), eval(PageScripts.ELEMENTS))

    override suspend fun elementAt(x: Int, y: Int): PageElement? {
        val raw = eval(PageScripts.at(x / ratio, y / ratio))
        return if (raw == "null" || raw.isBlank()) null else json.decodeFromString(PageElement.serializer(), raw)
    }

    override suspend fun click(ref: Int) {
        val raw = eval(PageScripts.centre(ref))
        if (raw == "null" || raw.isBlank()) error("That element is gone: read the page's elements again.")
        val at = Json.parseToJsonElement(raw).jsonObject
        val x = at["x"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val y = at["y"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        delay(150)
        listOf("mouseMoved", "mousePressed", "mouseReleased").forEach { type ->
            send("Input.dispatchMouseEvent", buildJsonObject {
                put("type", type)
                put("x", x)
                put("y", y)
                if (type != "mouseMoved") {
                    put("button", "left")
                    put("clickCount", 1)
                }
            })
        }
        settle()
    }

    override suspend fun scroll(down: Boolean) {
        eval(PageScripts.scroll(down))
        delay(700)
    }

    override suspend fun screenshot(): ByteArray {
        val data = send("Page.captureScreenshot", buildJsonObject { put("format", "png") })["data"]?.jsonPrimitive?.content ?: error("No picture.")
        return Base64.getDecoder().decode(data)
    }

    override suspend fun hasVideo(): Boolean = eval(PageScripts.VIDEO) == "true"

    override fun close() {
        runCatching { socket.sendText(buildJsonObject { put("id", ids.incrementAndGet()); put("method", "Browser.close") }.toString(), true) }
        runCatching { socket.abort() }
        runCatching { if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroy() }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** The flags the study's browser runs with: its own profile, a debugging port only it knows, nothing on first run. */
        fun flags(profile: File, start: String, visible: Boolean): List<String> = listOfNotNull(
            "--user-data-dir=${profile.absolutePath}",
            "--remote-debugging-port=0",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-sync",
            "--disable-features=Translate",
            if (visible) null else "--headless=new",
            "--window-size=1280,900",
            start,
        )

        suspend fun launch(executable: File, profile: File, start: String, visible: Boolean, extra: List<String> = emptyList()): ChromeSurface = withContext(Dispatchers.IO) {
            profile.mkdirs()
            val port = File(profile, "DevToolsActivePort")
            port.delete()
            val process = ProcessBuilder(listOf(executable.absolutePath) + extra + flags(profile, start, visible))
                .redirectErrorStream(true)
                .redirectOutput(File(profile.parentFile ?: profile, "browser.log"))
                .start()
            // The browser writes the port it chose, and its WebSocket's path, once it listens.
            val until = System.currentTimeMillis() + 30_000
            while (!port.isFile || port.readLines().size < 2) {
                if (!process.isAlive) error("The browser closed as it started (is another window of it using this profile?).")
                if (System.currentTimeMillis() > until) {
                    process.destroy()
                    error("The browser did not open in time.")
                }
                delay(150)
            }
            val (p, path) = port.readLines().let { it[0].trim() to it[1].trim() }
            val inbox = Inbox()
            val socket = HttpClient.newBuilder().build().newWebSocketBuilder()
                .buildAsync(URI("ws://127.0.0.1:$p$path"), inbox).await()
            ChromeSurface(process, socket, inbox).also { it.attach() }
        }
    }
}
