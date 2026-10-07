package com.kaiharimoto.guest

import com.kaiharimoto.mastertool.core.duel.lounge.LoungeCodec
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import org.w3c.dom.url.URL
import org.w3c.fetch.Response
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.FileReader
import org.w3c.files.get

/** The page's own address, `https://duel.labrynth.info`: art and cards are asked of it. */
val origin: String get() = window.location.origin

/** The Lounge's socket to kai's computer: what it hears decoded, what is sent encoded. */
class LoungeSocket(onOpen: () -> Unit, private val onHear: (LoungeWire) -> Unit, onClose: (String) -> Unit) {
    private val ws: WebSocket

    init {
        val scheme = if (window.location.protocol == "https:") "wss" else "ws"
        ws = WebSocket("$scheme://${window.location.host}/ws")
        ws.onopen = { onOpen() }
        ws.onmessage = { event: MessageEvent ->
            event.data?.toString()?.let(LoungeCodec::decode)?.let(onHear)
        }
        ws.onclose = { onClose("The connection to kai's computer closed") }
    }

    fun send(w: LoungeWire) {
        if (ws.readyState == WebSocket.OPEN) ws.send(LoungeCodec.encode(w))
    }

    fun close() = ws.close()
}

/** A request to kai's computer: its status, and its text. */
suspend fun ask(path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
    val r: Response = fetchText(path, method, body).await()
    val text = r.text().await<JsString>().toString()
    return r.status.toInt() to text
}

/**
 * `fetch` with only what is given: Kotlin's `RequestInit(…)` writes every field it leaves out as `null`, and the
 * browser refuses `mode: null`.
 */
@Suppress("UNUSED_PARAMETER")
private fun fetchText(path: String, method: String, body: String?): Promise<Response> =
    js("fetch(path, body == null ? { method: method } : { method: method, headers: { 'Content-Type': 'application/json' }, body: body })")

object Kept {
    fun get(key: String): String? = runCatching { localStorage.getItem(key) }.getOrNull()
    fun put(key: String, value: String?) {
        runCatching { if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value) }
    }
}

/** The browser's file chooser: the text of the file chosen, handed to [onText]. */
fun chooseText(accept: String, onText: (name: String, text: String) -> Unit) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.accept = accept
    // In the document, hidden: Safari opens no chooser for an input that is not.
    input.style.display = "none"
    document.body?.appendChild(input)
    input.onchange = {
        input.remove()
        val file = input.files?.get(0)
        if (file != null) {
            val reader = FileReader()
            reader.onload = { onText(file.name, reader.result?.toString().orEmpty()) }
            reader.readAsText(file)
        }
    }
    input.click()
}

/** [text] saved as a file named [name] by the browser. */
fun saveText(name: String, text: String) {
    val parts = JsArray<JsAny?>()
    parts[0] = text.toJsString()
    val blob = Blob(parts, BlobPropertyBag(type = "text/plain"))
    val url = URL.createObjectURL(blob)
    val a = document.createElement("a") as HTMLAnchorElement
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
}

/** Whether the screen is touched rather than pointed at: a phone or a tablet. */
val coarsePointer: Boolean get() = window.matchMedia("(pointer: coarse)").matches

/** The browser asks for a dark page. */
val prefersDark: Boolean get() = window.matchMedia("(prefers-color-scheme: dark)").matches

/**
 * At the table ([on]), the duel's keys are the page's, not the browser's: Alt ←/→ (the ordering strip; the browser's
 * Back and Forward), F1 (the command help) and Ctrl/⌘ L (the command line; the address bar) are kept from the browser,
 * a right-click opens no browser menu over the table, and leaving or reloading the page asks first.
 */
@Suppress("UNUSED_PARAMETER")
fun holdTableKeys(on: Boolean): Unit = js(
    """{
    if (!window.__loungeKeys) {
        window.__loungeKeys = true;
        window.addEventListener('keydown', (e) => {
            if (!window.__loungeOn) return;
            const k = e.key;
            if ((e.altKey && (k === 'ArrowLeft' || k === 'ArrowRight')) || k === 'F1' || ((e.ctrlKey || e.metaKey) && (k === 'l' || k === 'L'))) e.preventDefault();
        }, true);
        window.addEventListener('contextmenu', (e) => { if (window.__loungeOn) e.preventDefault(); }, true);
        window.addEventListener('beforeunload', (e) => { if (window.__loungeOn) { e.preventDefault(); e.returnValue = ''; } });
    }
    window.__loungeOn = on;
}"""
)
