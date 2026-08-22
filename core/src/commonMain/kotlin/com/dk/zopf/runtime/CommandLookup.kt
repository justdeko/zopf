package com.dk.zopf.runtime

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

private const val MARKER = "__zopf_env__"
private const val PROBE_TIMEOUT_SECONDS = 10L
private val ASSIGNMENT = Regex("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")

object CommandLookup {
    private val shellEnvironment: Map<String, String> by lazy {
        probe("-lic") ?: probe("-lc") ?: emptyMap()
    }

    val loginEnvironment: Map<String, String>
        get() = shellEnvironment

    val loginPath: String by lazy {
        val inherited = System.getenv("PATH").orEmpty()
        listOfNotNull(
            shellEnvironment["PATH"]?.takeIf { it.isNotBlank() },
            inherited.takeIf { it.isNotBlank() },
        ).joinToString(":")
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

    fun lookupEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: loginEnvironment[name]?.takeIf { it.isNotEmpty() }

    fun childEnvironment(): Map<String, String> {
        val own = System.getenv()
        return loginEnvironment.filterKeys { it !in own } + mapOf("PATH" to loginPath)
    }

    private fun probe(flags: String): Map<String, String>? =
        runCatching {
            val process =
                ProcessBuilder("zsh", flags, "printf '\\n$MARKER\\n'; printenv; printf '$MARKER\\n'")
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val watchdog =
                Thread {
                    if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
                }.apply {
                    isDaemon = true
                    start()
                }
            val lines = process.inputStream.bufferedReader().readLines()
            watchdog.join()
            marked(lines)
        }.getOrNull()

    private fun marked(lines: List<String>): Map<String, String>? {
        val first = lines.indexOf(MARKER)
        val last = lines.lastIndexOf(MARKER)
        if (first !in 0..<last) return null
        return lines
            .subList(first + 1, last)
            .mapNotNull { ASSIGNMENT.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
            .takeIf { it.isNotEmpty() }
    }
}
