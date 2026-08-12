package com.dk.zopf.runtime

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

object CommandLookup {
    val loginPath: String by lazy {
        val inherited = System.getenv("PATH").orEmpty()
        val fromShell =
            runCatching {
                val process =
                    ProcessBuilder("zsh", "-lc", "printf %s \"\$PATH\"")
                        .redirectErrorStream(false)
                        .start()
                val out =
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                process.waitFor(5, TimeUnit.SECONDS)
                out
            }.getOrNull()

        listOfNotNull(fromShell?.takeIf { it.isNotBlank() }, inherited.takeIf { it.isNotBlank() })
            .joinToString(":")
            .ifBlank { "/usr/bin:/bin" }
    }

    fun which(name: String): Path? {
        if (name.contains('/')) return Paths.get(name).takeIf { it.isExecutable() }
        return loginPath
            .split(':')
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Paths.get(it).resolve(name) }
            .firstOrNull { it.isRegularFile() && it.isExecutable() }
    }

    val loginEnvironment: Map<String, String> by lazy {
        val assignment = Regex("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
        runCatching {
            val process =
                ProcessBuilder("zsh", "-lc", "printenv")
                    .redirectErrorStream(false)
                    .start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(5, TimeUnit.SECONDS)
            lines
                .mapNotNull { assignment.find(it) }
                .associate { it.groupValues[1] to it.groupValues[2] }
        }.getOrDefault(emptyMap())
    }

    fun lookupEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: loginEnvironment[name]?.takeIf { it.isNotEmpty() }

    fun childEnvironment(): Map<String, String> {
        val own = System.getenv()
        return loginEnvironment.filterKeys { it !in own } + mapOf("PATH" to loginPath)
    }
}
