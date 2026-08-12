package com.dk.zopf.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
enum class SchemaFieldType(
    val jsonType: String,
) {
    @SerialName("string")
    STRING("string"),

    @SerialName("number")
    NUMBER("number"),

    @SerialName("boolean")
    BOOLEAN("boolean"),

    @SerialName("object")
    OBJECT("object"),

    @SerialName("array")
    ARRAY("array"),
}

@Serializable
data class SchemaField(
    val name: String,
    val type: SchemaFieldType = SchemaFieldType.STRING,
    val description: String = "",
    val required: Boolean = true,
) {
    val isNested: Boolean get() = type == SchemaFieldType.OBJECT || type == SchemaFieldType.ARRAY
}

fun List<SchemaField>.toJsonSchema(): JsonObject {
    val fields = filter { it.name.isNotBlank() }
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            fields.forEach { field ->
                putJsonObject(field.name) {
                    put("type", field.type.jsonType)
                    if (field.description.isNotBlank()) put("description", field.description)
                }
            }
        }
        putJsonArray("required") {
            fields.filter { it.required }.forEach { add(it.name) }
        }
        put("additionalProperties", false)
    }
}
