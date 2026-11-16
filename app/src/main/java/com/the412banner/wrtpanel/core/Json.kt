package com.the412banner.wrtpanel.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val JsonCodec = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray

val JsonElement?.str: String?
    get() = when (this) {
        null, is JsonNull -> null
        is JsonPrimitive -> content
        else -> null
    }

val JsonElement?.long: Long?
    get() = when (this) {
        is JsonPrimitive -> if (this is JsonNull) null else longOrNull ?: doubleOrNull?.toLong() ?: content.toLongOrNull()
        else -> null
    }

val JsonElement?.int: Int? get() = long?.toInt()

val JsonElement?.double: Double?
    get() = when (this) {
        is JsonPrimitive -> if (this is JsonNull) null else doubleOrNull ?: content.toDoubleOrNull()
        else -> null
    }

val JsonElement?.bool: Boolean?
    get() = when (this) {
        is JsonPrimitive -> if (this is JsonNull) null else booleanOrNull ?: when (content) {
            "1", "yes", "on", "true", "enabled" -> true
            "0", "no", "off", "false", "disabled" -> false
            else -> null
        }
        else -> null
    }

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

/** String list from a JSON array, or a one-element list from a scalar. */
val JsonElement?.strList: List<String>
    get() = when (this) {
        is JsonArray -> mapNotNull { it.str }
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> listOf(content)
        else -> emptyList()
    }

fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.toJson() })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Array<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { it.first to it.second.toJson() })

val EmptyJson = JsonObject(emptyMap())
