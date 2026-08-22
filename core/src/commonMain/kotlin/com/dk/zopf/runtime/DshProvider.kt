package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId

object DshProvider : AgentProvider {
    override val id = AgentProviderId.DSH

    override val executable = "dsh"

    override val promptChannel = PromptChannel.ARGUMENT

    override fun newSessionId(): String? = null

    override fun command(
        invocation: AgentInvocation,
        resolvedExecutable: String,
    ): List<String> =
        listOf(
            resolvedExecutable,
            "--profile",
            "headless",
            invocation.prompt,
        )

    override fun parse(line: String): AgentEvent? = DshEvents.parse(line)

    override fun terminalArgs(sessionId: String?): List<String> = emptyList()
}

object DshEvents {
    fun parse(line: String): AgentEvent = AgentEvent.TextDelta(sessionId = null, text = line + "\n", isThinking = false)

    fun parseAll(text: String): List<AgentEvent> = text.lineSequence().map(::parse).toList()
}
