package com.dk.zopf.runtime

import com.dk.zopf.store.DEFAULT_TERMINAL_APP
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText

object TerminalLauncher {
    private val scriptPermissions = PosixFilePermissions.fromString("rwx------")

    fun takeOver(
        provider: AgentProvider,
        sessionId: String,
        cwd: Path,
        terminalApp: String = DEFAULT_TERMINAL_APP,
    ): Result<Unit> = open(script(cwd, provider.terminalArgs(sessionId), provider.executable), terminalApp)

    fun openIn(
        cwd: Path,
        terminalApp: String = DEFAULT_TERMINAL_APP,
        prompt: String? = null,
        executable: String = "claude",
    ): Result<Unit> = open(script(cwd, listOfNotNull(prompt?.trim()?.ifBlank { null }), executable), terminalApp)

    private fun open(
        contents: String,
        terminalApp: String,
    ): Result<Unit> =
        runCatching {
            val script = writeScript(contents)
            val open =
                ProcessBuilder("open", "-a", terminalApp, script.toString())
                    .apply { environment().putAll(CommandLookup.childEnvironment()) }
                    .start()
            val exit = open.waitFor()
            check(exit == 0) {
                "Couldn't open $terminalApp: " +
                    open.errorStream
                        .bufferedReader()
                        .readText()
                        .trim()
            }
        }

    internal fun script(
        cwd: Path,
        args: List<String> = emptyList(),
        executable: String = "claude",
    ): String {
        val tail = args.joinToString("") { " " + shellQuote(it) }
        return """
            #!/bin/zsh
            cd ${shellQuote(cwd.toString())} || exit 1
            exec ${shellQuote(executable)}$tail
            """.trimIndent() + "\n"
    }

    private fun writeScript(contents: String): Path {
        val file = Files.createTempFile("zopf-agent-", ".command")
        file.writeText(contents)
        Files.setPosixFilePermissions(file, scriptPermissions)

        return file
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
