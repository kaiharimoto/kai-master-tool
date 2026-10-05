package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.JsRuntime
import com.kaiharimoto.mastertool.core.world.WorldApi
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.WorldPrelude
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Undefined
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An app's three functions run on the World's Rhino (`docs/world/DESKTOP.md` §8.5, §8.6; `AppSandboxTest`):
 *
 * - **One sealed scope** holds the safe standard objects, `ygo.*` and `ui.*`, built once and sealed whole — every
 *   prototype, `ygo` and `ui` — so nothing an app does reaches the next call or another app.
 * - **Each call** runs in a fresh scope over it: the app's code (compiled once per version, cached) defines `init`,
 *   `view` and `on` there; the state goes in as JSON text and comes out as JSON text; `Math` is seeded for the call.
 *   When the call ends the scope is let go: no JavaScript survives between calls.
 * - **The World's cage**, tightened per call: no Java (a class shutter that refuses every class), no network, files only
 *   through `ygo.read`, `ygo.show` at most [AppLimits.SHOWS] a call; `init` [AppLimits.INIT_MS], `view`
 *   [AppLimits.VIEW_MS] and [AppLimits.VIEW_STEPS] steps, `on` [AppLimits.ON_MS]; [AppLimits.HEAP_MB] of heap; [stop].
 * - **Its own thread** per call, as a script's, so a call that will not stop is given up on and left to finish alone.
 */
class JsApp : AppRunner {
    private val stops = ConcurrentHashMap<String, AtomicBoolean>()
    private val compiled = object : LinkedHashMap<String, Script>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Script>?): Boolean = size > CACHE
    }

    @Volatile
    private var shared: ScriptableObject? = null

    override fun init(app: AppCode, host: WorldHost): AppCall<String> =
        call(app, "init()", AppLimits.INIT_MS, AppLimits.CALL_STEPS, host, seq = 0L) { cx, scope, fn ->
            val r = fn("init").call(cx, scope, scope, emptyArray())
            if (r == null || r is Undefined) throw AppError("init() returned nothing: return the first state, like { count: 0 }")
            stateOut(cx, scope, r)
        }

    override fun view(app: AppCode, state: String, host: WorldHost): AppCall<UiTree> =
        call(app, "view(state)", AppLimits.VIEW_MS, AppLimits.VIEW_STEPS, host, seq = 0L) { cx, scope, fn ->
            val r = fn("view").call(cx, scope, scope, arrayOf(parse(cx, scope, state)))
            if (r == null || r is Undefined) throw AppError("view(state) returned nothing: return ui.col([...])")
            UiTree.parse(stringify(cx, scope, r) ?: "null")
        }

    override fun on(app: AppCode, state: String, event: UiEvent, host: WorldHost): AppCall<String> =
        call(app, "on(${event.type} ${event.id})", AppLimits.ON_MS, AppLimits.CALL_STEPS, host, seq = event.seq) { cx, scope, fn ->
            val s = parse(cx, scope, state)
            val r = fn("on").call(cx, scope, scope, arrayOf(s, parse(cx, scope, event.json())))
            // An `on` that changed the state in place and forgot to return it: the state it changed is the next one.
            stateOut(cx, scope, if (r == null || r is Undefined) s else r)
        }

    override fun migrate(app: AppCode, state: String, from: Int, host: WorldHost): AppCall<String> =
        call(app, "migrate(state, $from)", AppLimits.ON_MS, AppLimits.CALL_STEPS, host, seq = 0L) { cx, scope, _ ->
            val f = ScriptableObject.getProperty(scope, "migrate") as? Function ?: return@call state
            val s = parse(cx, scope, state)
            val r = f.call(cx, scope, scope, arrayOf(s, from))
            stateOut(cx, scope, if (r == null || r is Undefined) s else r)
        }

    override fun stop(slug: String) {
        stops[slug]?.set(true)
    }

    // ---- The call --------------------------------------------------------------------------------------------------

    private class AppError(message: String) : RuntimeException(message)

    private fun <T> call(
        app: AppCode,
        label: String,
        millis: Long,
        steps: Long,
        host: WorldHost,
        seq: Long,
        body: (Context, Scriptable, (String) -> Function) -> T,
    ): AppCall<T> {
        AppCodec.codeProblem(app.source)?.let { return AppCall.Failed(label, it) }
        val stop = AtomicBoolean(false)
        stops[app.slug] = stop
        val api = WorldApi(host, WorldApi.Limits(shows = AppLimits.SHOWS))
        val printed = ArrayList<String>()
        api.print = { line -> synchronized(printed) { if (printed.size < MAX_PRINTED) printed += line } }
        val limits = JsRuntime.Limits(instructions = steps, millis = millis, heapMb = AppLimits.HEAP_MB)
        val budget = JsRuntime.Budget(limits) { stop.get() }
        var result: AppCall<T>? = null
        val started = System.currentTimeMillis()
        val worker = Thread(null, {
            val cx = JsRuntime.Factory.enterContext()
            try {
                val base = sharedScope(cx)
                cx.putThreadLocal(JsRuntime.BUDGET, budget)
                CURRENT.set(Current(api, printed))
                val scope = cx.newObject(base) as ScriptableObject
                scope.prototype = base
                scope.parentScope = null
                ScriptableObject.putProperty(scope, "Math", (ScriptableObject.getProperty(base, AppPrelude.MATH_FOR) as Function).call(cx, base, base, arrayOf(AppPrelude.seed(app.version, seq))))
                script(cx, app).exec(cx, scope)
                val fn = { name: String ->
                    ScriptableObject.getProperty(scope, name) as? Function ?: throw AppError("the app has no $name(): an app defines init(), view(state) and on(state, event)")
                }
                val v = body(cx, scope, fn)
                result = AppCall.Ok(v, System.currentTimeMillis() - started, api.shown.toList(), synchronized(printed) { printed.toList() })
            } catch (e: JsRuntime.Stop) {
                result = AppCall.Failed(label, e.message.orEmpty(), null, System.currentTimeMillis() - started, stopped = true)
            } catch (e: RhinoException) {
                result = AppCall.Failed(label, JsRuntime.describe(e).lineSequence().first(), e.lineNumber().takeIf { it > 0 && e.sourceName() == CODE_NAME }, System.currentTimeMillis() - started)
            } catch (e: AppError) {
                result = AppCall.Failed(label, e.message.orEmpty(), null, System.currentTimeMillis() - started)
            } catch (e: StackOverflowError) {
                result = AppCall.Failed(label, "Too deep: the app called itself too many times.", null, System.currentTimeMillis() - started)
            } catch (e: OutOfMemoryError) {
                result = AppCall.Failed(label, "Out of memory: the app kept too much at once.", null, System.currentTimeMillis() - started)
            } catch (e: Throwable) {
                result = AppCall.Failed(label, "The app failed: ${e.message ?: e::class.simpleName}", null, System.currentTimeMillis() - started)
            } finally {
                CURRENT.remove()
                Context.exit()
            }
        }, "world-app-${app.slug}", JsRuntime.STACK)
        worker.isDaemon = true
        worker.start()
        worker.join(millis + JsRuntime.GRACE)
        if (worker.isAlive) {
            stop.set(true)
            worker.interrupt()
            return AppCall.Failed(label, "Stopped: past ${millis / 1000.0} seconds inside one call; it is left to finish on its own.", null, System.currentTimeMillis() - started, stopped = true)
        }
        stops.remove(app.slug, stop)
        return result ?: AppCall.Failed(label, "The app did not answer.")
    }

    private fun script(cx: Context, app: AppCode): Script {
        val key = "${app.slug}@${app.version}#${app.source.hashCode()}#${app.source.length}"
        synchronized(compiled) { compiled[key] }?.let { return it }
        val s = cx.compileString(app.source, CODE_NAME, 1, null)
        synchronized(compiled) { compiled[key] = s }
        return s
    }

    private fun parse(cx: Context, scope: Scriptable, json: String): Any = NativeJSON.parse(cx, scope, json, NO_REVIVER)

    private fun stringify(cx: Context, scope: Scriptable, v: Any?): String? =
        NativeJSON.stringify(cx, scope, v, null, null).takeIf { it !is Undefined }?.let { Context.toString(it) }

    private fun stateOut(cx: Context, scope: Scriptable, v: Any?): String {
        val text = stringify(cx, scope, v) ?: throw AppError("the state must be JSON: plain objects, arrays, strings, numbers, true, false and null")
        AppCodec.stateProblem(text)?.let { throw AppError(it) }
        return text
    }

    // ---- The sealed scope, built once ---------------------------------------------------------------------------

    private fun sharedScope(cx: Context): ScriptableObject {
        shared?.let { return it }
        synchronized(this) {
            shared?.let { return it }
            val scope = cx.initSafeStandardObjects(null, false)
            ScriptableObject.putProperty(scope, "__ygo", Door)
            ScriptableObject.putProperty(scope, "__print", Printer)
            cx.evaluateString(scope, WorldPrelude.JS, "ygo", 1, null)
            cx.evaluateString(scope, AppPrelude.JS, "ui", 1, null)
            seal(scope, IdentityHashMap())
            shared = scope
            return scope
        }
    }

    /** [o], everything reachable from its properties and its prototypes, sealed: an app can change none of it. */
    private fun seal(o: Any?, seen: IdentityHashMap<Any, Boolean>) {
        if (o !is ScriptableObject || seen.put(o, true) != null) return
        for (id in o.allIds) {
            // A getter on a prototype may refuse to run with the prototype as `this`: what it guards is not a value to seal.
            val v = runCatching {
                when (id) {
                    is String -> ScriptableObject.getProperty(o, id)
                    is Int -> ScriptableObject.getProperty(o, id)
                    else -> null
                }
            }.getOrNull()
            seal(v, seen)
        }
        seal(o.prototype, seen)
        o.sealObject()
    }

    private class Current(val api: WorldApi, val printed: MutableList<String>)

    /** The one door, as a script's: `__ygo(name, json)` → JSON text, routed to the call under way on this thread. */
    private object Door : BaseFunction() {
        private fun readResolve(): Any = Door

        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val api = CURRENT.get()?.api ?: throw ScriptRuntime.constructError("Error", "ygo is only open inside a call")
            val name = args.getOrNull(0)?.let(Context::toString).orEmpty()
            val raw = args.getOrNull(1)?.let(Context::toString) ?: "{}"
            val json = try {
                WorldCodec.json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
            if (name in REFUSED) throw ScriptRuntime.constructError("Error", "an app cannot use ygo.$name")
            return try {
                api.call(name, json).toString()
            } catch (e: IllegalArgumentException) {
                throw ScriptRuntime.constructError("Error", e.message ?: "ygo.$name failed")
            } catch (e: IllegalStateException) {
                throw ScriptRuntime.constructError("Error", e.message ?: "ygo.$name failed")
            }
        }
    }

    private object Printer : BaseFunction() {
        private fun readResolve(): Any = Printer

        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val sink = CURRENT.get()?.printed ?: return Undefined.instance
            synchronized(sink) { if (sink.size < MAX_PRINTED) sink += args.joinToString(" ") { Context.toString(it) } }
            return Undefined.instance
        }
    }

    companion object {
        /** The name an app's code runs under, so its errors say `main.js, line 41`. */
        const val CODE_NAME = "main.js"
        private const val CACHE = 64
        private const val MAX_PRINTED = 200
        private val CURRENT = ThreadLocal<Current?>()
        private val NO_REVIVER = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? = args.getOrNull(1)
        }

        /** Door calls an app may not make: `use` evaluates code into the sealed scope. */
        private val REFUSED = setOf("file")
    }
}
