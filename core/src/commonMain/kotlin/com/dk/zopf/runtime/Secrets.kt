package com.dk.zopf.runtime

import com.dk.zopf.model.ConnectorSecret
import java.util.concurrent.TimeUnit

enum class SecretSource {
    ENVIRONMENT,
    KEYCHAIN,
    MISSING,
    ;

    val label: String
        get() =
            when (this) {
                ENVIRONMENT -> "environment"
                KEYCHAIN -> "keychain"
                MISSING -> "not set"
            }
}

class ResolvedSecret(
    val declared: ConnectorSecret,
    val value: String?,
    val source: SecretSource,
) {
    val name: String get() = declared.name
    val isMissing: Boolean get() = value == null

    override fun toString(): String = "$name (${source.label})"
}

class SecretResolver(
    private val fromEnvironment: (String) -> String? = CommandLookup::lookupEnv,
    private val fromKeychain: (String) -> String? = Keychain::read,
) {
    fun resolve(declared: List<ConnectorSecret>): List<ResolvedSecret> =
        declared.map { secret ->
            val fromEnv = fromEnvironment(secret.name)?.takeIf { it.isNotEmpty() }
            if (fromEnv != null) return@map ResolvedSecret(secret, fromEnv, SecretSource.ENVIRONMENT)

            val fromChain = secret.keychain?.let(fromKeychain)?.takeIf { it.isNotEmpty() }
            if (fromChain != null) return@map ResolvedSecret(secret, fromChain, SecretSource.KEYCHAIN)

            ResolvedSecret(secret, null, SecretSource.MISSING)
        }
}

object Keychain {
    fun read(service: String): String? =
        runCatching {
            val process =
                ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", service, "-w")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                output.ifEmpty { null }
            } else {
                null
            }
        }.getOrNull()
}
