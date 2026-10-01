package com.kaiharimoto.mastertool.core.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Two edits to one JSON document merged key by key (1.0.68): a key only one side changed keeps that
 * side's value, a key both changed goes to the newer, and keys are compared one level down where both
 * hold objects — so the theme changed on the laptop and a card list made on the phone are both kept.
 */
object JsonMerge {
    fun threeWay(base: JsonObject?, local: JsonObject, remote: JsonObject, localNewer: Boolean, depth: Int = 2): JsonObject {
        val keys = (local.keys + remote.keys + base?.keys.orEmpty()).toList().distinct()
        val out = LinkedHashMap<String, JsonElement>()
        keys.forEach { k ->
            val b = base?.get(k)
            val l = local[k]
            val r = remote[k]
            val v = when {
                l == r -> l
                l == b -> r
                r == b -> l
                depth > 1 && l is JsonObject && r is JsonObject -> threeWay(b as? JsonObject, l, r, localNewer, depth - 1)
                else -> if (localNewer) l else r
            }
            if (v != null) out[k] = v
        }
        return JsonObject(out)
    }
}
