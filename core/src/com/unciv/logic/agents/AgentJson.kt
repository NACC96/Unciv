package com.unciv.logic.agents

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Minimal JSON framing for the desktop↔supervisor stdio protocol and its
 * tests (AI_AGENT_PLAN.md §7.3). Gdx's own writers either drop
 * default-valued fields (prototypes) or tag Map values with classes, so
 * protocol frames use this explicit emitter/parser pair: String keys,
 * primitives, lists, and integral numbers preserved exactly.
 */
object AgentJson {
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        is Boolean -> value.toString()
        is Number -> {
            val long = value.toLong()
            if (long.toDouble() == value.toDouble()) long.toString() else value.toDouble().toString()
        }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            stringify(it.key.toString()) + ":" + stringify(it.value)
        }
        is List<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        else -> stringify(value.toString())
    }

    private val reader = JsonReader()

    fun parse(text: String): Any? = convert(reader.parse(text))

    private fun convert(node: JsonValue): Any? = when {
        node.isObject() -> LinkedHashMap<String, Any?>().also { map ->
            for (child in node) map[child.name] = convert(child)
        }
        node.isArray() -> ArrayList<Any?>().also { list ->
            for (child in node) list.add(convert(child))
        }
        node.isBoolean() -> node.asBoolean()
        node.isString() -> node.asString()
        node.isNull() -> null
        node.isNumber() -> run {
            val long = node.asLong()
            if (long.toDouble() == node.asDouble() && long in Int.MIN_VALUE..Int.MAX_VALUE) long.toInt()
            else node.asDouble()
        }
        else -> node.asString()
    }
}
