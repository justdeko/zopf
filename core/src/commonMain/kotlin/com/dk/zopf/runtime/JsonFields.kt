package com.dk.zopf.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonElement.asFieldText(): String? =
    when {
        this is JsonNull -> null
        this is JsonPrimitive && isString -> content
        else -> toString()
    }

internal fun JsonObject.toFields(exclude: Set<String> = emptySet()): Map<String, String> =
    filterKeys { it !in exclude }
        .mapNotNull { (key, value) -> value.asFieldText()?.let { key to it } }
        .toMap()

internal fun JsonElement.asObject(): JsonObject? = runCatching { jsonObject }.getOrNull()

internal fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement.booleanOrNull(): Boolean? = runCatching { jsonPrimitive.booleanOrNull }.getOrNull()

internal fun JsonElement.doubleOrNull(): Double? = runCatching { jsonPrimitive.doubleOrNull }.getOrNull()

internal fun JsonElement.intOrNull(): Int? = runCatching { jsonPrimitive.intOrNull }.getOrNull()

internal fun JsonObject.string(key: String): String? = this[key]?.stringOrNull()

internal fun JsonObject.strings(key: String): List<String> =
    this[key]
        ?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.mapNotNull { element -> element.stringOrNull() ?: element.asObject()?.string("name") }
        .orEmpty()
