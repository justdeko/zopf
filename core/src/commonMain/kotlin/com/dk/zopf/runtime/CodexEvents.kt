package com.dk.zopf.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object CodexEvents {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val TOOL_ITEMS = setOf("command_execution", "file_change", "mcp_tool_call", "web_search")

    fun parse(line: String): AgentEvent? {
        if (line.isBlank()) return null
        val obj =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return AgentEvent.NonJson(line)
        val threadId = obj.string("thread_id")

        return when (val type = obj.string("type")) {
            "thread.started" -> {
                AgentEvent.SystemInit(
                    sessionId = threadId,
                    model = null,
                    cwd = null,
                    permissionMode = null,
                    version = null,
                    tools = emptyList(),
                    mcpServers = emptyList(),
                    raw = obj,
                )
            }

            "turn.started" -> {
                AgentEvent.Notice(threadId, "turn_started", null, obj)
            }

            "item.started", "item.updated", "item.completed" -> {
                itemEvent(type, obj, threadId)
            }

            "turn.completed" -> {
                val usage = obj["usage"]?.asObject()
                AgentEvent.Result(
                    sessionId = threadId,
                    subtype = "success",
                    isError = false,
                    text = null,
                    costUsd = null,
                    tokens = usage?.totalTokens(),
                    durationMs = null,
                    numTurns = null,
                    permissionDenials = emptyList(),
                    fields = emptyMap(),
                    raw = obj,
                )
            }

            "turn.failed", "error" -> {
                AgentEvent.Result(
                    sessionId = threadId,
                    subtype = type,
                    isError = true,
                    text = obj.errorMessage(),
                    costUsd = null,
                    tokens = null,
                    durationMs = null,
                    numTurns = null,
                    permissionDenials = emptyList(),
                    fields = emptyMap(),
                    raw = obj,
                )
            }

            else -> {
                AgentEvent.Unknown(threadId, type, obj)
            }
        }
    }

    fun parseAll(text: String): List<AgentEvent> = text.lineSequence().mapNotNull(::parse).toList()

    private fun itemEvent(
        type: String,
        obj: JsonObject,
        threadId: String?,
    ): AgentEvent {
        val item = obj["item"]?.asObject() ?: return AgentEvent.Unknown(threadId, type, obj)
        val itemType = item.string("item_type") ?: item.string("type")
        val id = item.string("id").orEmpty()
        val complete = type == "item.completed"

        return when {
            itemType == "agent_message" && complete -> {
                AgentEvent.AssistantMessage(threadId, null, listOf(ContentBlock.Text(item.text())), obj)
            }

            itemType == "reasoning" && complete -> {
                AgentEvent.AssistantMessage(threadId, null, listOf(ContentBlock.Thinking(item.text())), obj)
            }

            itemType in TOOL_ITEMS && type == "item.started" -> {
                AgentEvent.AssistantMessage(
                    threadId,
                    null,
                    listOf(ContentBlock.ToolUse(id, itemType.orEmpty().toolName(), item.toolInput())),
                    obj,
                )
            }

            itemType in TOOL_ITEMS && complete -> {
                AgentEvent.UserMessage(
                    threadId,
                    listOf(
                        ContentBlock.ToolResult(
                            toolUseId = id,
                            text = item.string("aggregated_output") ?: item.string("output").orEmpty(),
                            isError = item.string("status") == "failed" || (item.intOrNull("exit_code") ?: 0) != 0,
                        ),
                    ),
                    obj,
                )
            }

            else -> {
                AgentEvent.Unknown(threadId, "$type:$itemType", obj)
            }
        }
    }

    private fun String.toolName(): String =
        split('_').joinToString("") { part ->
            part.replaceFirstChar { it.uppercase() }
        }

    private fun JsonObject.text(): String = string("text") ?: string("content").orEmpty()

    private fun JsonObject.toolInput(): JsonObject =
        buildJsonObject {
            string("command")?.let { put("command", it) }
            (string("path") ?: changedPaths())?.let { put("file_path", it) }
            string("query")?.let { put("pattern", it) }
            string("server")?.let { put("description", "$it.${string("tool").orEmpty()}") }
        }

    private fun JsonObject.changedPaths(): String? =
        this["changes"]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { it.asObject()?.string("path") }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString()

    private fun JsonObject.totalTokens(): Int? {
        val input = intOrNull("input_tokens")
        val output = intOrNull("output_tokens")
        if (input == null && output == null) return null
        return (input ?: 0) + (output ?: 0)
    }

    private fun JsonObject.errorMessage(): String? = string("message") ?: this["error"]?.asObject()?.string("message")

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.intOrNull()
}
