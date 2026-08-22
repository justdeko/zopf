package com.dk.zopf.runtime

import com.dk.zopf.model.AgentCapabilities
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.capabilities

enum class PromptChannel { STDIN, ARGUMENT }

interface AgentProvider {
    val id: AgentProviderId

    val executable: String

    val promptChannel: PromptChannel

    val capabilities: AgentCapabilities get() = id.capabilities

    fun newSessionId(): String?

    fun command(
        invocation: AgentInvocation,
        resolvedExecutable: String,
    ): List<String>

    fun parse(line: String): AgentEvent?

    fun terminalArgs(sessionId: String?): List<String>
}

object AgentProviders {
    val all: List<AgentProvider> = listOf(ClaudeProvider, CodexProvider, DshProvider)

    private val onPath: Set<AgentProviderId> by lazy {
        all.filter { CommandLookup.which(it.executable) != null }.mapTo(mutableSetOf()) { it.id }
    }

    fun of(id: AgentProviderId): AgentProvider = all.first { it.id == id }

    fun isInstalled(id: AgentProviderId): Boolean = id in onPath
}
