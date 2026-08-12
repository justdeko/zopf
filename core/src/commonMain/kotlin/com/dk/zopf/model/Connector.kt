package com.dk.zopf.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

const val DEFAULT_CONNECTOR_TIMEOUT_SECONDS = 120

@Serializable
data class ConnectorManifest(
    val name: String = "",
    val description: String = "",
    val run: String = "run.sh",
    val inputs: List<ConnectorInput> = emptyList(),
    val env: List<ConnectorSecret> = emptyList(),
    val outputs: List<ConnectorOutputField> = emptyList(),
    val timeoutSeconds: Int = DEFAULT_CONNECTOR_TIMEOUT_SECONDS,
) {
    fun input(name: String): ConnectorInput? = inputs.firstOrNull { it.name == name }

    fun output(name: String): ConnectorOutputField? = outputs.firstOrNull { it.name == name }

    val summary: String
        get() =
            description.ifBlank {
                if (inputs.isEmpty()) "No declared inputs" else inputs.joinToString { it.name }
            }
}

@Serializable
data class ConnectorInput(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
    val default: String = "",
)

@Serializable(with = ConnectorOutputFieldSerializer::class)
data class ConnectorOutputField(
    val name: String,
    val description: String = "",
)

@Serializable(with = ConnectorSecretSerializer::class)
data class ConnectorSecret(
    val name: String,
    val description: String = "",
    val keychain: String? = null,
    val required: Boolean = true,
) {
    val sourceLabel: String get() = keychain?.let { "environment, or keychain \"$it\"" } ?: "environment"
}

@Serializable
@SerialName("ConnectorSecret")
private data class SecretObject(
    val name: String,
    val description: String = "",
    val keychain: String? = null,
    val required: Boolean = true,
)

object ConnectorSecretSerializer : KSerializer<ConnectorSecret> {
    private val objectSerializer = SecretObject.serializer()

    override val descriptor: SerialDescriptor = objectSerializer.descriptor

    override fun deserialize(decoder: Decoder): ConnectorSecret {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(objectSerializer).toSecret()
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> ConnectorSecret(name = element.content)
            else -> json.json.decodeFromJsonElement(objectSerializer, element).toSecret()
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: ConnectorSecret,
    ) {
        if (value.keychain == null && value.description.isBlank() && value.required) {
            encoder.encodeString(value.name)
        } else {
            encoder.encodeSerializableValue(
                objectSerializer,
                SecretObject(value.name, value.description, value.keychain, value.required),
            )
        }
    }

    private fun SecretObject.toSecret() = ConnectorSecret(name, description, keychain, required)
}

@Serializable
@SerialName("ConnectorOutputField")
private data class OutputObject(
    val name: String,
    val description: String = "",
)

object ConnectorOutputFieldSerializer : KSerializer<ConnectorOutputField> {
    private val objectSerializer = OutputObject.serializer()

    override val descriptor: SerialDescriptor = objectSerializer.descriptor

    override fun deserialize(decoder: Decoder): ConnectorOutputField {
        val json =
            decoder as? JsonDecoder
                ?: return decoder.decodeSerializableValue(objectSerializer).toField()
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> ConnectorOutputField(name = element.content)
            else -> json.json.decodeFromJsonElement(objectSerializer, element).toField()
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: ConnectorOutputField,
    ) {
        if (value.description.isBlank()) {
            encoder.encodeString(value.name)
        } else {
            encoder.encodeSerializableValue(objectSerializer, OutputObject(value.name, value.description))
        }
    }

    private fun OutputObject.toField() = ConnectorOutputField(name, description)
}
