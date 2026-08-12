package com.dk.zopf.store

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

internal val zopfYaml =
    Yaml(
        configuration =
            YamlConfiguration(
                encodeDefaults = false,
                strictMode = false,
                singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
                multiLineStringStyle = MultiLineStringStyle.Literal,
                sequenceBlockIndent = 2,
                breakScalarsAt = Int.MAX_VALUE,
            ),
    )

internal fun <T> encodeYaml(
    serializer: SerializationStrategy<T>,
    value: T,
): String = zopfYaml.encodeToString(serializer, value).trimEnd() + "\n"

internal val zopfJson =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
