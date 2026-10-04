package com.kaiharimoto.mastertool.core.world

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Undefined
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A world's JavaScript (1.0.95): Mozilla Rhino, interpreted (Android cannot load generated classes), shut in.
 *
 * - **Nothing of Java**: only the safe standard objects, no `Packages`, `java` or `JavaAdapter`, and a class shutter
 *   that refuses every class, so even a Java object that leaked in could not be reached through.
 * - **One door**: `ygo.*` over [WorldApi.call], and `print`. No files, no network, no clock but `Date`.
 * - **A budget**: the interpreter counts instructions and stops the script past [Limits.instructions], past
 *   [Limits.millis], past [Limits.heapMb] of new heap, or when the person presses Stop. What the count cannot see —
 *   one native call that builds something huge — the prelude guards ([WorldPrelude]), and running out of memory or
 *   stack is caught and reported, never let through to the app.
 * - **Its own thread**, a daemon with a deep stack, so the app's never waits on a script: a run that will not stop
 *   (a regular expression backtracking inside Java, which no count reaches) is given up on and left to finish alone.
 */
class JsRuntime(private val limits: Limits = Limits()) {
    data class Limits(
        val instructions: Long = 4_000_000_000L,
        val millis: Long = 30_000L,
        val output: Int = 64_000,
        val heapMb: Long = 256L,
    )

    data class Result(
        val ok: Boolean,
        val out: String,
        val err: String,
        val ms: Long,
        /** True when the output went past [Limits.output] and the rest was dropped. */
        val cut: Boolean,
        /** The last expression's value, when it had one. */
        val value: String?,
    )

    /** Why a run was stopped: thrown inside the interpreter, past any `catch` a script writes. */
    private class Stop(message: String) : Error(message)

    private class Budget(val limits: Limits, val stop: () -> Boolean) {
        val started = System.currentTimeMillis()
        val heapAtStart = used()
        val heapCap = minOf(limits.heapMb * MB, Runtime.getRuntime().maxMemory() / 4)
        var count = 0L

        fun check(more: Int) {
            count += more
            when {
                stop() -> throw Stop("Stopped.")
                count > limits.instructions -> throw Stop("Stopped: past ${limits.instructions / 1_000_000} million steps. Fewer trials, or a cheaper loop.")
                System.currentTimeMillis() - started > limits.millis -> throw Stop("Stopped: past ${limits.millis / 1000} seconds.")
                used() - heapAtStart > heapCap -> throw Stop("Stopped: past ${heapCap / MB} MB of memory. Keep less at once.")
            }
        }

        private fun used(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    private object Factory : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 10_000
            maximumInterpreterStackDepth = 2_000
            setClassShutter { false }
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            (cx.getThreadLocal(BUDGET) as? Budget)?.check(instructionCount)
        }
    }

    /**
     * Runs [code] (named [name] in errors) with `ygo` over [api]. Each line printed goes to [onLine] as it is printed,
     * from the script's thread. [stop] is asked as the script runs. Blocks the caller until the script ends or is
     * given up on.
     */
    fun run(code: String, name: String, api: WorldApi, onLine: (String) -> Unit = {}, stop: () -> Boolean = { false }): Result {
        val out = StringBuilder()
        var cut = false
        val lock = Any()
        val given = AtomicBoolean(false)
        fun print(line: String) = synchronized(lock) {
            if (given.get()) return@synchronized
            if (out.length + line.length + 1 > limits.output) {
                if (!cut) {
                    cut = true
                    onLine("… (output past ${limits.output / 1000}k characters is not kept)")
                }
                return@synchronized
            }
            out.append(line).append('\n')
            onLine(line)
        }
        var err = ""
        var value: String? = null
        var ok = false
        val started = System.currentTimeMillis()
        val budget = Budget(limits) { stop() || given.get() }
        api.print = { text -> text.lines().forEach(::print) }
        val worker = Thread(null, {
            val cx = Factory.enterContext()
            try {
                cx.putThreadLocal(BUDGET, budget)
                val scope = cx.initSafeStandardObjects(null, false)
                ScriptableObject.putProperty(scope, "__ygo", Door(api))
                ScriptableObject.putProperty(scope, "__print", Printer { text -> text.lines().forEach(::print) })
                cx.evaluateString(scope, WorldPrelude.JS, "ygo", 1, null)
                val result = cx.evaluateString(scope, code, name, 1, null)
                if (result != null && result !is Undefined && result !is org.mozilla.javascript.Function) {
                    value = Context.toString(result).take(2_000)
                }
                ok = true
            } catch (e: Stop) {
                err = e.message.orEmpty()
            } catch (e: RhinoException) {
                err = describe(e)
            } catch (e: StackOverflowError) {
                err = "Too deep: the script called itself too many times."
            } catch (e: OutOfMemoryError) {
                err = "Out of memory: the script kept too much at once."
            } catch (e: Throwable) {
                err = "The script failed: ${e.message ?: e::class.simpleName}"
            } finally {
                Context.exit()
            }
        }, "world-js", STACK)
        worker.isDaemon = true
        worker.start()
        worker.join(limits.millis + GRACE)
        if (worker.isAlive) {
            given.set(true)
            worker.interrupt()
            return Result(false, synchronized(lock) { out.toString() }, "Stopped: past ${limits.millis / 1000} seconds inside one call (a regular expression, most likely); it is left to finish on its own.", System.currentTimeMillis() - started, cut, null)
        }
        return Result(ok, synchronized(lock) { out.toString() }, err, System.currentTimeMillis() - started, cut, value)
    }

    /** The one door: `__ygo(name, json)` → JSON text, or a JavaScript `Error` a script can catch. */
    private class Door(private val api: WorldApi) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            val name = args.getOrNull(0)?.let(Context::toString).orEmpty()
            val raw = args.getOrNull(1)?.let(Context::toString) ?: "{}"
            val json = try {
                WorldCodec.json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
            return try {
                api.call(name, json).toString()
            } catch (e: IllegalArgumentException) {
                throw ScriptRuntime.constructError("Error", e.message ?: "ygo.$name failed")
            } catch (e: IllegalStateException) {
                throw ScriptRuntime.constructError("Error", e.message ?: "ygo.$name failed")
            }
        }
    }

    private class Printer(private val sink: (String) -> Unit) : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
            sink(args.joinToString(" ") { Context.toString(it) })
            return Undefined.instance
        }
    }

    companion object {
        private val BUDGET = Any()
        private const val MB = 1024L * 1024L
        private const val STACK = 16L * MB
        private const val GRACE = 2_000L

        /** A Rhino error in words, with where: "TypeError: x is undefined (sim.js, line 12)". */
        fun describe(e: RhinoException): String {
            val what = e.details().ifBlank { e.message.orEmpty() }
            val where = buildString {
                e.sourceName()?.takeIf { it.isNotBlank() && it != "ygo" }?.let { append(it) }
                if (e.lineNumber() > 0) append(if (isEmpty()) "line ${e.lineNumber()}" else ", line ${e.lineNumber()}")
            }
            val code = e.lineSource()?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n  $it" }.orEmpty()
            return what + (if (where.isNotEmpty()) " ($where)" else "") + code
        }
    }
}
