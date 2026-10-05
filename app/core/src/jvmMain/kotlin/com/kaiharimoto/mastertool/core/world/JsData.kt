package com.kaiharimoto.mastertool.core.world

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * A script's value as **data** (Phase D step 2, `FxCompile`): read in Kotlin, walking Rhino's own objects, never through
 * `JSON.stringify` — which drops a function in silence, and which a script could replace. Only plain objects, arrays,
 * strings, finite numbers, booleans and null pass; a function, a getter or setter, a symbol, a date, a regular
 * expression, a cycle, a value nested deeper than [DEPTH] or more than [NODES] values is [Refused] with where it lies
 * ("effects[0].does[1].op"), so a script builds data and never hides code in it.
 *
 * Called on the script's own thread, inside its context, so a walk is still under the run's budget.
 */
internal object JsData {
    const val DEPTH = 48
    const val NODES = 50_000

    class Refused(message: String) : Exception(message)

    fun json(value: Any?): JsonElement {
        var nodes = 0
        val open = HashSet<Any>()
        fun walk(v: Any?, path: String, depth: Int): JsonElement {
            if (++nodes > NODES) throw Refused("The value holds more than ${NODES / 1000}k parts.")
            if (depth > DEPTH) throw Refused("The value nests deeper than $DEPTH at ${path.ifEmpty { "the top" }}.")
            val at = path.ifEmpty { "the top" }
            return when (v) {
                null, is Undefined -> JsonNull
                is Function -> throw Refused("A function at $at: an effect is built as data, never code. Call the builder, do not pass it.")
                is CharSequence -> JsonPrimitive(v.toString())
                is Boolean -> JsonPrimitive(v)
                is Number -> {
                    val d = v.toDouble()
                    if (!d.isFinite()) throw Refused("$d at $at: a number must be finite.")
                    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
                }
                is NativeArray -> {
                    if (!open.add(v)) throw Refused("A cycle at $at: the value refers back to itself.")
                    val n = v.length
                    if (n > NODES) throw Refused("An array of $n at $at.")
                    val out = ArrayList<JsonElement>(n.toInt())
                    for (i in 0 until n.toInt()) out += walk(v.get(i, v), "$path[$i]", depth + 1)
                    open.remove(v)
                    JsonArray(out)
                }
                is NativeObject -> {
                    if (v.javaClass != NativeObject::class.java) throw Refused("${v.className} at $at: only plain objects are data.")
                    if (!open.add(v)) throw Refused("A cycle at $at: the value refers back to itself.")
                    val out = LinkedHashMap<String, JsonElement>()
                    for (id in v.ids) {
                        val key = id.toString()
                        val there = if (path.isEmpty()) key else "$path.$key"
                        fun accessor(setter: Boolean) = (if (id is Int) v.getGetterOrSetter(null, id, v, setter) else v.getGetterOrSetter(key, 0, v, setter)) is Function
                        if (accessor(false) || accessor(true)) {
                            throw Refused("A getter at $there: an effect is built as data, never code.")
                        }
                        val item = if (id is Int) v.get(id, v) else v.get(key, v)
                        if (item is Undefined) continue
                        out[key] = walk(item, there, depth + 1)
                    }
                    open.remove(v)
                    JsonObject(out)
                }
                is ScriptableObject -> throw Refused("${v.className} at $at: only plain objects, arrays, words, numbers and true/false are data.")
                else -> throw Refused("${v::class.simpleName} at $at: not data.")
            }
        }
        return walk(value, "", 0)
    }
}
