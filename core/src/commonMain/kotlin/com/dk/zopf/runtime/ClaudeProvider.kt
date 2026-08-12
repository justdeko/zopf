package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId

object ClaudeProvider : AgentProvider {
    override val id = AgentProviderId.CLAUDE

    override val executable = "claude"

    override val promptChannel = PromptChannel.STDIN

    override fun newSessionId(): String =
        com.dk.zopf.runtime
            .newSessionId()

    override fun command(
        invocation: AgentInvocation,
        resolvedExecutable: String,
    ): List<String> =
        buildList {
            add(resolvedExecutable)
            add("-p")
            add("--output-format")
            add("stream-json")
            add("--input-format")
            add("stream-json")
            add("--verbose")
            add("--include-partial-messages")
            invocation.sessionId?.let {
                add("--session-id")
                add(it)
            }
            invocation.model?.let {
                add("--model")
                add(it)
            }
            invocation.permissionMode?.let {
                add("--permission-mode")
                add(it.cliValue)
            }
            if (invocation.allowedTools.isNotEmpty()) {
                add("--allowedTools")
                addAll(invocation.allowedTools)
            }
            invocation.addDirs.forEach {
                add("--add-dir")
                add(it.toString())
            }
            invocation.pluginDirs.forEach {
                add("--plugin-dir")
                add(it.toString())
            }
            invocation.jsonSchema?.let {
                add("--json-schema")
                add(it)
            }
            invocation.settingsFile?.let {
                add("--settings")
                add(it.toString())

                add("--include-hook-events")
            }
        }

    override fun parse(line: String): AgentEvent? = ClaudeEvents.parse(line)

    override fun terminalArgs(sessionId: String?): List<String> = sessionId?.let { listOf("-r", it) }.orEmpty()
}
