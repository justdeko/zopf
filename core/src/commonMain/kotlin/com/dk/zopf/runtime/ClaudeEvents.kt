package com.dk.zopf.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object ClaudeEvents {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parse(line: String): AgentEvent? {
        if (line.isBlank()) return null
        val obj =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return AgentEvent.NonJson(line)
        val sessionId = obj.string("session_id")

        return when (val type = obj.string("type")) {
            "system" ->
                when (obj.string("subtype")) {
                    "init" ->
                        AgentEvent.SystemInit(
                            sessionId = sessionId,
                            model = obj.string("model"),
                            cwd = obj.string("cwd"),
                            permissionMode = obj.string("permissionMode"),
                            version = obj.string("claude_code_version"),
                            tools = obj.strings("tools"),
                            mcpServers = obj.strings("mcp_servers"),
                            raw = obj,
                        )

                    else ->
                        AgentEvent.Notice(
                            sessionId = sessionId,
                            kind = obj.string("subtype") ?: "system",
                            detail = obj.string("status") ?: obj.string("hook_name"),
                            raw = obj,
                        )
                }

            "rate_limit_event" ->
                AgentEvent.Notice(
                    sessionId = sessionId,
                    kind = "rate_limit",
                    detail = obj["rate_limit_info"]?.asObject()?.string("status"),
                    raw = obj,
                )

            "assistant" -> {
                val message = obj["message"]?.asObject()
                AgentEvent.AssistantMessage(
                    sessionId = sessionId,
                    model = message?.string("model"),
                    blocks = message.contentBlocks(),
                    raw = obj,
                )
            }

            "user" ->
                AgentEvent.UserMessage(
                    sessionId = sessionId,
                    blocks = obj["message"]?.asObject().contentBlocks(),
                    raw = obj,
                )

            "stream_event" -> parseStreamEvent(obj, sessionId)

            "result" ->
                AgentEvent.Result(
                    sessionId = sessionId,
                    subtype = obj.string("subtype"),
                    isError = obj["is_error"]?.booleanOrNull() ?: false,
                    text = obj.string("result"),
                    costUsd = obj["total_cost_usd"]?.doubleOrNull(),
                    tokens = null,
                    durationMs = obj["duration_ms"]?.doubleOrNull()?.toLong(),
                    numTurns = obj["num_turns"]?.intOrNull(),
                    permissionDenials = obj.permissionDenials(),
                    fields = obj["structured_output"]?.asObject()?.toFields().orEmpty(),
                    raw = obj,
                )

            else -> AgentEvent.Unknown(sessionId, type, obj)
        }
    }

    fun parseAll(text: String): List<AgentEvent> = text.lineSequence().mapNotNull(::parse).toList()

    private fun parseStreamEvent(
        obj: JsonObject,
        sessionId: String?,
    ): AgentEvent {
        val event = obj["event"]?.asObject() ?: return AgentEvent.Stream(sessionId, null, obj)
        val delta = event["delta"]?.asObject()
        val deltaType = delta?.string("type")
        val text = delta?.string("text") ?: delta?.string("thinking")

        return if (event.string("type") == "content_block_delta" && text != null) {
            AgentEvent.TextDelta(sessionId, text, isThinking = deltaType == "thinking_delta")
        } else {
            AgentEvent.Stream(sessionId, event.string("type"), obj)
        }
    }

    private fun JsonObject.permissionDenials(): List<PermissionDenial> {
        val array = runCatching { this["permission_denials"]?.jsonArray }.getOrNull().orEmpty()
        return array.mapNotNull { entry ->
            val obj = entry.asObject() ?: return@mapNotNull null
            PermissionDenial(
                toolName = obj.string("tool_name") ?: return@mapNotNull null,
                toolUseId = obj.string("tool_use_id").orEmpty(),
                summary = obj["tool_input"]?.asObject()?.toContentBlockInputSummary().orEmpty(),
            )
        }
    }

    private fun JsonObject.toContentBlockInputSummary(): String = TOOL_SUMMARY_KEYS.firstNotNullOfOrNull { string(it) }.orEmpty()

    private fun JsonObject?.contentBlocks(): List<ContentBlock> {
        val content = this?.get("content") ?: return emptyList()

        content.stringOrNull()?.let { return listOf(ContentBlock.Text(it)) }
        val array = runCatching { content.jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { it.asObject()?.toContentBlock() }
    }

    private fun JsonObject.toContentBlock(): ContentBlock =
        when (string("type")) {
            "text" -> ContentBlock.Text(string("text").orEmpty())
            "thinking" -> ContentBlock.Thinking(string("thinking").orEmpty())
            "tool_use" ->
                ContentBlock.ToolUse(
                    id = string("id").orEmpty(),
                    name = string("name").orEmpty(),
                    input = this["input"]?.asObject() ?: JsonObject(emptyMap()),
                )

            "tool_result" ->
                ContentBlock.ToolResult(
                    toolUseId = string("tool_use_id").orEmpty(),
                    text = this["content"].flattenText(),
                    isError = this["is_error"]?.booleanOrNull() ?: false,
                )

            else -> ContentBlock.Other(string("type").orEmpty())
        }

    private fun JsonElement?.flattenText(): String {
        if (this == null) return ""
        stringOrNull()?.let { return it }
        val array = runCatching { jsonArray }.getOrNull() ?: return toString()
        return array.joinToString("\n") { it.asObject()?.string("text") ?: "" }.trim()
    }
}
