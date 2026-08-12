package com.dk.zopf.runtime

import com.dk.zopf.model.PermissionMode
import com.dk.zopf.model.Sandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.Collections
import java.util.UUID

data class AgentInvocation(
    val prompt: String,
    val cwd: Path,
    val sessionId: String? = null,
    val model: String? = null,
    val permissionMode: PermissionMode? = null,
    val sandbox: Sandbox? = null,
    val allowedTools: List<String> = emptyList(),
    val addDirs: List<Path> = emptyList(),
    val pluginDirs: List<Path> = emptyList(),
    val settingsFile: Path? = null,
    val jsonSchema: String? = null,
)

fun newSessionId(): String = UUID.randomUUID().toString()

class AgentRunner(
    private val spawn: (List<String>, Path, Map<String, String>, PromptChannel) -> Process = ::spawnFor,
) {
    fun start(
        provider: AgentProvider,
        invocation: AgentInvocation,
    ): AgentSession {
        val resolved =
            CommandLookup.which(provider.executable)
                ?: error("Couldn't find \"${provider.executable}\" on your PATH")
        val started = invocation.copy(sessionId = invocation.sessionId ?: provider.newSessionId())
        val command = provider.command(started, resolved.toString())
        return AgentSession(
            provider = provider,
            process = spawn(command, started.cwd, CommandLookup.childEnvironment(), provider.promptChannel),
            sessionId = started.sessionId,
            command = command,
            cwd = started.cwd,
        )
    }
}

class AgentSession internal constructor(
    private val provider: AgentProvider,
    private val process: Process,
    val sessionId: String?,
    val command: List<String>,
    val cwd: Path,
) {
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter()
    private val stderrLines = Collections.synchronizedList(mutableListOf<String>())

    init {
        Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { stderrLines.add(it) }
            }
        }.apply {
            isDaemon = true
            name = "${provider.id.cliValue}-stderr-${sessionId ?: cwd.fileName}"
        }.start()
    }

    val stderrText: String get() = synchronized(stderrLines) { stderrLines.joinToString("\n") }

    fun events(onRawLine: (String) -> Unit = {}): Flow<AgentEvent> =
        flow {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onRawLine(line)
                    provider.parse(line)?.let { emit(it) }
                }
            }
        }.flowOn(Dispatchers.IO)

    fun send(text: String): Result<Unit> {
        if (provider.promptChannel != PromptChannel.STDIN) {
            return Result.failure(
                UnsupportedOperationException("${provider.id.label} takes one turn and doesn't read stdin"),
            )
        }
        return runCatching {
            stdin.write(userMessage(text))
            stdin.newLine()
            stdin.flush()
        }
    }

    fun endInput() {
        runCatching { stdin.close() }
    }

    fun stop() {
        runCatching { stdin.close() }
        process.destroy()
    }

    fun kill() {
        runCatching { stdin.close() }
        process.destroyForcibly()
    }

    suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    private fun userMessage(text: String): String =
        buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                }
            }
        }.toString()
}

internal fun spawnFor(
    command: List<String>,
    cwd: Path,
    env: Map<String, String>,
    channel: PromptChannel,
): Process =
    when (channel) {
        PromptChannel.STDIN -> spawnProcess(command, cwd, env)
        PromptChannel.ARGUMENT -> spawnWithoutInput(command, cwd, env)
    }

internal fun spawnProcess(
    command: List<String>,
    cwd: Path,
    env: Map<String, String>,
): Process =
    ProcessBuilder(command)
        .directory(cwd.toFile())
        .apply { environment().putAll(env) }
        .start()

internal fun spawnWithoutInput(
    command: List<String>,
    cwd: Path,
    env: Map<String, String>,
): Process =
    ProcessBuilder(command)
        .directory(cwd.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        .apply { environment().putAll(env) }
        .start()
