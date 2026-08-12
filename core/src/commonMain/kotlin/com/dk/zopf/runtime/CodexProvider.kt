package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId

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
            add(invocation.prompt)
        }

    override fun parse(line: String): AgentEvent? = CodexEvents.parse(line)

    override fun terminalArgs(sessionId: String?): List<String> = emptyList()
}
