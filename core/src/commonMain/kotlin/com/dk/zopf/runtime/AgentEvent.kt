package com.dk.zopf.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface AgentEvent {
    val sessionId: String?

    data class SystemInit(
        override val sessionId: String?,
        val model: String?,
        val cwd: String?,
        val permissionMode: String?,
        val version: String?,
        val tools: List<String>,
        val mcpServers: List<String>,
        val raw: JsonObject,
    ) : AgentEvent

    data class Notice(
        override val sessionId: String?,
        val kind: String,
        val detail: String?,
        val raw: JsonObject,
    ) : AgentEvent

    data class AssistantMessage(
        override val sessionId: String?,
        val model: String?,
        val blocks: List<ContentBlock>,
        val raw: JsonObject,
    ) : AgentEvent {
        val text: String get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
    }

    data class UserMessage(
        override val sessionId: String?,
        val blocks: List<ContentBlock>,
        val raw: JsonObject,
    ) : AgentEvent

    data class TextDelta(
        override val sessionId: String?,
        val text: String,
        val isThinking: Boolean,
    ) : AgentEvent

    data class Stream(
        override val sessionId: String?,
        val eventType: String?,
        val raw: JsonObject,
    ) : AgentEvent

    data class Result(
        override val sessionId: String?,
        val subtype: String?,
        val isError: Boolean,
        val text: String?,
        val costUsd: Double?,
        val tokens: Int?,
        val durationMs: Long?,
        val numTurns: Int?,
        val permissionDenials: List<PermissionDenial>,
        val fields: Map<String, String>,
        val raw: JsonObject,
    ) : AgentEvent

    data class Unknown(
        override val sessionId: String?,
        val type: String?,
        val raw: JsonObject,
    ) : AgentEvent

    data class NonJson(
        val line: String,
    ) : AgentEvent {
        override val sessionId: String? get() = null
    }
}

data class PermissionDenial(
    val toolName: String,
    val toolUseId: String,
    val summary: String,
)

sealed interface ContentBlock {
    data class Text(
        val text: String,
    ) : ContentBlock

    data class Thinking(
        val text: String,
    ) : ContentBlock

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlock {
        val summary: String
            get() =
                TOOL_SUMMARY_KEYS
                    .firstNotNullOfOrNull { key ->
                        (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    }
                    ?: input.keys.joinToString(", ")
    }

    data class ToolResult(
        val toolUseId: String,
        val text: String,
        val isError: Boolean,
    ) : ContentBlock

    data class Other(
        val type: String,
    ) : ContentBlock
}

internal val TOOL_SUMMARY_KEYS = listOf("command", "file_path", "pattern", "url", "description")
