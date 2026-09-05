package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

object CodexProvider : AgentProvider {
    override val id = AgentProviderId.CODEX

    override val executable = "codex"

    override val promptChannel = PromptChannel.ARGUMENT

    override fun newSessionId(): String? = null

    override fun command(
        invocation: AgentInvocation,
        resolvedExecutable: String,
    ): List<String> =
        buildList {
            add(resolvedExecutable)
            add("--ask-for-approval")
            add("never")
            invocation.model?.let {
                add("--model")
                add(it)
            }
            invocation.sandbox?.let {
                add("--sandbox")
                add(it.cliValue)
            }
            add("exec")
            add("--json")
            add("--skip-git-repo-check")
            invocation.jsonSchema?.let {
                add("--output-schema")
                add(schemaFile(it).toString())
            }
            add(invocation.prompt)
        }

    internal fun schemaFile(schema: String): Path =
        Files.createTempFile("zopf-schema-", ".json").also {
            it.writeText(schema)
            it.toFile().deleteOnExit()
        }

    override fun parse(line: String): AgentEvent? = CodexEvents.parse(line)

    override fun terminalArgs(sessionId: String?): List<String> = sessionId?.let { listOf("resume", it) }.orEmpty()
}
