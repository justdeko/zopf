package com.dk.zopf.runtime

import com.dk.zopf.model.DEFAULT_CONNECTOR_TIMEOUT_SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable

const val CONNECTOR_TIMEOUT_EXIT = 124

data class ConnectorInvocation(
    val script: Path,
    val cwd: Path,
    val inputs: Map<String, String>,
    val timeoutSeconds: Int = DEFAULT_CONNECTOR_TIMEOUT_SECONDS,
    val env: Map<String, String> = emptyMap(),
) {
    fun argv(): List<String> = listOf(script.toString())

    fun stdinJson(): String = buildJsonObject { inputs.forEach { (key, value) -> put(key, value) } }.toString()
}

class ConnectorRunner(
    private val spawn: (List<String>, Path, Map<String, String>) -> Process = ::spawnProcess,
) {
    fun start(invocation: ConnectorInvocation): ConnectorSession {
        if (!invocation.script.isExecutable()) {
            invocation.script.toFile().setExecutable(true)
        }

        val process =
            spawn(
                invocation.argv(),
                invocation.cwd,
                CommandLookup.childEnvironment() + invocation.env,
            )
        val session = ConnectorSession(process, invocation.argv(), invocation.timeoutSeconds)
        session.writeInputs(invocation.stdinJson())
        return session
    }
}

class ConnectorSession internal constructor(
    private val process: Process,
    val command: List<String>,
    private val timeoutSeconds: Int,
) {
    private val stdout = Collections.synchronizedList(mutableListOf<String>())

    var timedOut: Boolean = false
        private set

    fun lines(): Flow<ShellLine> =
        channelFlow {
            suspend fun pump(
                stream: InputStream,
                isError: Boolean,
            ) {
                stream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!isError) stdout.add(line)
                        send(ShellLine(line, isError))
                    }
                }
            }

            val out = launch(Dispatchers.IO) { pump(process.inputStream, isError = false) }
            val err = launch(Dispatchers.IO) { pump(process.errorStream, isError = true) }
            out.join()
            err.join()
        }

    fun stop() {
        process.destroy()
    }

    suspend fun awaitExit(): Int =
        withContext(Dispatchers.IO) {
            if (process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                process.exitValue()
            } else {
                timedOut = true
                process.destroy()

                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                CONNECTOR_TIMEOUT_EXIT
            }
        }

    fun output(): ConnectorOutput = ConnectorOutput.parse(synchronized(stdout) { stdout.toList() })

    internal fun writeInputs(json: String) {
        Thread {
            runCatching {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(json)
                    writer.newLine()
                }
            }
        }.apply {
            isDaemon = true
            name = "zopf-connector-stdin"
        }.start()
    }
}

data class ConnectorOutput(
    val result: String,
    val error: String?,
    val sawJson: Boolean,
    val fields: Map<String, String> = emptyMap(),
) {
    companion object {
        private val lenient =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        fun parse(lines: List<String>): ConnectorOutput {
            val text = lines.joinToString("\n").trim()

            val json =
                lines.asReversed().firstNotNullOfOrNull { asObject(it) } ?: asObject(text)
                    ?: return ConnectorOutput(result = text, error = null, sawJson = false)

            return ConnectorOutput(
                result = if ("result" in json) json.getValue("result").asFieldText().orEmpty() else json.toString(),
                error = json["error"]?.asFieldText()?.takeIf { it.isNotBlank() },
                sawJson = true,
                fields = json.toFields(exclude = setOf("result", "error")),
            )
        }

        private fun asObject(line: String): JsonObject? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return null
            return runCatching { lenient.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        }
    }
}
