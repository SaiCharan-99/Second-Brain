package com.secondbrain.agent

import com.anthropic.core.JsonValue
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The Jackson boundary.
 *
 * `:model` and the rest of this project are kotlinx-serialization. The Anthropic
 * SDK is Jackson, and its tool inputs and schema properties arrive as
 * `JsonValue`. Exactly one file converts between the two, and it is this one —
 * that conversion was the known cost of choosing the SDK (D-044), so it is
 * contained rather than spread across the module.
 *
 * `JsonValue.from(Object)` accepts plain Java/Kotlin collections, so the bridge
 * is Jackson-parse to a Map and back rather than anything clever.
 *
 * One SDK pitfall this exists to honour: tool-call `input` may come back with
 * different JSON string escaping (Unicode, forward slashes) than it went out
 * with. Tool inputs are therefore always *parsed*, never string-matched.
 */
internal object JsonBridge {

    private val mapper = ObjectMapper()

    /**
     * JSON object text to the key/value map the SDK's `Input` and `Properties`
     * builders want.
     *
     * `LinkedHashMap` order is preserved from the source text, which matters:
     * tool schemas render before everything else in the cache key, so a varying
     * property order is a silent cache invalidator (H6).
     */
    fun objectToJsonValues(jsonText: String): LinkedHashMap<String, JsonValue> {
        val out = LinkedHashMap<String, JsonValue>()
        if (jsonText.isBlank()) return out

        val node = mapper.readTree(jsonText)
        if (node !is ObjectNode) return out

        // properties() rather than the deprecated fields(); both preserve the
        // source text's order, which is what the cache key depends on.
        node.properties().forEach { entry ->
            out[entry.key] = JsonValue.fromJsonNode(entry.value)
        }
        return out
    }

    /** `JsonValue` back to compact JSON text, for persistence and logging. */
    fun toJsonText(value: JsonValue): String = value.toString()

    /** Reads a JSON string array. Used for a schema's `required` list. */
    fun stringArray(jsonText: String?): List<String> {
        if (jsonText.isNullOrBlank()) return emptyList()
        val node = mapper.readTree(jsonText)
        if (!node.isArray) return emptyList()
        return node.mapNotNull { if (it.isTextual) it.asText() else null }
    }

    /** The `properties` sub-object of a JSON Schema, as raw text, or null. */
    fun schemaSection(schemaJson: String, field: String): String? {
        val node = mapper.readTree(schemaJson)
        if (node !is ObjectNode) return null
        val section = node.get(field) ?: return null
        return section.toString()
    }
}
