package com.dk.zopf.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Path

data class ShellLine(
    val text: String,
    val isError: Boolean,
)

data class ShellInvocation(
    val command: String,
    val cwd: Path,
    val shell: List<String> = listOf("zsh", "-lc"),
) {
    fun argv(): List<String> = shell + command
}

class ShellRunner(
    private val spawn: (List<String>, Path, Map<String, String>) -> Process = ::spawnWithoutInput,
) {
    fun start(invocation: ShellInvocation): ShellSession =
        ShellSession(
            process = spawn(invocation.argv(), invocation.cwd, CommandLookup.childEnvironment()),
            command = invocation.command,
        )
}

class ShellSession internal constructor(
    private val process: Process,
    val command: String,
) {
    fun lines(): Flow<ShellLine> =
        channelFlow {
            suspend fun pump(
                stream: InputStream,
                isError: Boolean,
            ) {
                stream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
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

    fun kill() {
        process.destroyForcibly()
    }

    suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }
}
