package com.kaiharimoto.mastertool.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Which part of the app a tool reaches, for the prompt's map and the tests' coverage. */
enum class ToolGroup { LOOK, CARDS, BUILD, FORMAT, APP, MEMORY, ASK, META }

/**
 * One thing Ai can do in the app: a name, what it is for, the JSON Schema of its
 * input, and whether it destroys something ([destructive]), which makes the app
 * ask the person before it runs.
 *
 * The same spec serves every backend: sent as a function to an API, and listed by
 * the app's own MCP server to a CLI. There is one implementation of each tool.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val group: ToolGroup,
    val destructive: Boolean = false,
    /** The release phase that brings it (1: the harness, 2: the meta, 3: learning); the app offers what it has shipped. */
    val phase: Int = 1,
)

/** Builds an object schema: `schema { string("name", "The deck's name", required = true) }`. */
fun schema(build: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(build).build()

class SchemaBuilder {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun prop(name: String, required: Boolean, body: JsonObject) {
        properties[name] = body
        if (required) this.required += name
    }

    fun string(name: String, description: String, required: Boolean = false) =
        prop(name, required, buildJsonObject { put("type", "string"); put("description", description) })

    fun integer(name: String, description: String, required: Boolean = false, min: Int? = null, max: Int? = null) =
        prop(name, required, buildJsonObject {
            put("type", "integer")
            put("description", description)
            if (min != null) put("minimum", min)
            if (max != null) put("maximum", max)
        })

    fun boolean(name: String, description: String, required: Boolean = false) =
        prop(name, required, buildJsonObject { put("type", "boolean"); put("description", description) })

    fun enum(name: String, description: String, values: List<String>, required: Boolean = false) =
        prop(name, required, buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", JsonArray(values.map(::JsonPrimitive)))
        })

    fun strings(name: String, description: String, required: Boolean = false, values: List<String>? = null) =
        prop(name, required, buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject {
                put("type", "string")
                if (values != null) put("enum", JsonArray(values.map(::JsonPrimitive)))
            })
        })

    fun integers(name: String, description: String, required: Boolean = false) =
        prop(name, required, buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "integer") })
        })

    fun objects(name: String, description: String, required: Boolean = false, item: SchemaBuilder.() -> Unit) =
        prop(name, required, buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", schema(item))
        })

    fun obj(name: String, description: String, required: Boolean = false, body: SchemaBuilder.() -> Unit) =
        prop(name, required, JsonObject(schema(body) + ("description" to JsonPrimitive(description))))

    /** A value of any JSON type (a setting's). */
    fun any(name: String, description: String, required: Boolean = false) =
        prop(name, required, buildJsonObject { put("description", description) })

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
}

/** Reading a tool's input leniently: models send "3" for 3 and a lone string for a list. */
object ToolArgs {
    fun string(input: JsonObject, key: String): String? =
        (input[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

    fun int(input: JsonObject, key: String): Int? {
        val p = input[key] as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.doubleOrNull?.toInt() ?: p.contentOrNull?.trim()?.toIntOrNull()
    }

    fun bool(input: JsonObject, key: String): Boolean? {
        val p = input[key] as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase()) {
            "yes", "on", "1" -> true
            "no", "off", "0" -> false
            else -> null
        }
    }

    fun strings(input: JsonObject, key: String): List<String> = when (val v = input[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }?.let { s ->
            if (s.contains('\n')) s.lines().map(String::trim).filter(String::isNotEmpty) else listOf(s)
        } ?: emptyList()
        else -> emptyList()
    }

    fun ints(input: JsonObject, key: String): List<Int> = when (val v = input[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull() } }
        is JsonPrimitive -> listOfNotNull(v.intOrNull ?: v.contentOrNull?.trim()?.toIntOrNull())
        else -> emptyList()
    }

    fun objects(input: JsonObject, key: String): List<JsonObject> = when (val v = input[key]) {
        is JsonArray -> v.filterIsInstance<JsonObject>()
        is JsonObject -> listOf(v)
        else -> emptyList()
    }

    fun obj(input: JsonObject, key: String): JsonObject? = input[key] as? JsonObject

    fun element(input: JsonObject, key: String): JsonElement? = input[key]

    /**
     * What is wrong with [input] against [spec]: a required field missing, or a
     * field the spec does not name. Null when it will do. A streamed input can
     * arrive cut short, so every tool's input is checked before it runs.
     */
    fun problem(spec: ToolSpec, input: JsonObject): String? {
        val props = spec.schema["properties"] as? JsonObject ?: return null
        val required = (spec.schema["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val missing = required.filter { input[it] == null || input[it] is JsonNull }
        if (missing.isNotEmpty()) return "Missing ${missing.joinToString()} for ${spec.name}."
        val unknown = input.keys.filter { it !in props }
        if (unknown.isNotEmpty()) return "${spec.name} takes no ${unknown.joinToString()}. It takes: ${props.keys.joinToString()}."
        return null
    }
}
