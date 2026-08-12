package com.dk.zopf.runtime

import androidx.compose.runtime.Stable
import com.dk.zopf.model.PermissionMode
import com.dk.zopf.store.AppPaths
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

val ASKABLE_TOOLS: List<String> = listOf("Bash", "Edit", "Write", "NotebookEdit", "WebFetch")

private val NON_EDIT_TOOLS: List<String> = listOf("Bash", "WebFetch")

const val APPROVAL_DEADLINE_SECONDS: Long = 540
private const val CURL_DEADLINE_SECONDS: Long = 570
private const val HOOK_TIMEOUT_SECONDS: Long = 600

data class PermissionRequest(
    val toolName: String,
    val summary: String,
    val toolUseId: String,
    val sessionId: String,
)

@Stable
class PendingPermission(
    val request: PermissionRequest,
    private val answer: CompletableFuture<Boolean>,
) {
    internal fun decide(allow: Boolean) {
        answer.complete(allow)
    }
}

fun askableTools(mode: PermissionMode?): List<String> =
    when (mode) {
        PermissionMode.BYPASS_PERMISSIONS, PermissionMode.DONT_ASK -> emptyList()

        PermissionMode.ACCEPT_EDITS -> NON_EDIT_TOOLS
        else -> ASKABLE_TOOLS
    }

class PermissionBridge(
    private val nodeBySession: (String) -> NodeRun?,
    private val onRequest: (NodeRun) -> Unit = {},
    private val dir: Path = AppPaths.appSupport,
    private val deadlineSeconds: Long = APPROVAL_DEADLINE_SECONDS,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val token: String = SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) }

    private val outstanding = ConcurrentHashMap.newKeySet<CompletableFuture<Boolean>>()

    private var server: HttpServer? = null
    private var stopped = false

    @Synchronized
    fun settingsFile(tools: List<String>): Path? {
        if (tools.isEmpty() || stopped) return null
        val port = start()
        val script = writeScript(port)
        val file = dir.resolve("zopf-hooks-${tools.joinToString("-").lowercase()}.json")
        file.writeText(hookSettings(script, tools).toString())
        runCatching { file.setPosixFilePermissions(OWNER_ONLY_FILE) }
        return file
    }

    @Synchronized
    fun denyOutstanding() {
        outstanding.forEach { it.complete(false) }
        outstanding.clear()
    }

    @Synchronized
    fun shutdown() {
        stopped = true
        denyOutstanding()

        server?.stop(1)
        server = null
    }

    private fun start(): Int {
        server?.let { return it.address.port }

        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.createContext("/permission") { exchange ->
            exchange.use { handle(it) }
        }

        created.executor =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "zopf-permission").apply { isDaemon = true }
            }
        created.start()
        server = created
        return created.address.port
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestHeaders.getFirst(TOKEN_HEADER) != token) {
            exchange.respond(403, "")
            return
        }
        val payload =
            runCatching {
                json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            }.getOrNull()
        val sessionId = payload?.text("session_id")
        val node = sessionId?.let(nodeBySession)

        if (payload == null || node == null) {
            exchange.respond(200, decisionJson(false, "zopf doesn't recognise this session"))
            return
        }

        val request =
            PermissionRequest(
                toolName = payload.text("tool_name") ?: "a tool",
                summary = payload["tool_input"]?.let { summarize(it.jsonObject) }.orEmpty(),
                toolUseId = payload.text("tool_use_id").orEmpty(),
                sessionId = sessionId,
            )
        exchange.respond(200, decisionJson(ask(node, request), null))
    }

    private fun ask(
        node: NodeRun,
        request: PermissionRequest,
    ): Boolean {
        if (request.toolName in node.autoAllowed) return true
        if (stopped || node.status.isFinished) return false

        val answer = CompletableFuture<Boolean>()
        outstanding.add(answer)
        val pending = PendingPermission(request, answer)
        node.beginPermission(pending)
        onRequest(node)

        val allowed =
            runCatching { answer.get(deadlineSeconds, TimeUnit.SECONDS) }
                .getOrElse {
                    node.notice("Nobody answered in time, so ${request.toolName} was denied", isWarning = true)
                    false
                }
        outstanding.remove(answer)
        node.endPermission(allowed, request)
        return allowed
    }

    private fun writeScript(port: Int): Path {
        val script = dir.resolve("zopf-approve.sh")
        script.writeText(
            """
            #!/bin/sh
            # Written by zopf. Asks the running app whether this tool call is allowed.
            #
            # Fail closed, deliberately: the CLI treats a hook that times out or errors as permission
            # granted, so anything short of a real answer from zopf has to be turned into a "deny"
            # here, while there is still something able to say it.
            RESPONSE=$(curl -sS --max-time $CURL_DEADLINE_SECONDS \
              -H '$TOKEN_HEADER: $token' \
              -H 'Content-Type: application/json' \
              --data-binary @- \
              'http://127.0.0.1:$port/permission' 2>/dev/null)

            if [ -z "${'$'}RESPONSE" ]; then
              printf '%s\n' '${decisionJson(false, DENY_UNREACHABLE)}'
            else
              printf '%s\n' "${'$'}RESPONSE"
            fi
            """.trimIndent() + "\n",
        )
        runCatching { script.setPosixFilePermissions(OWNER_ONLY_EXEC) }
        return script
    }

    private fun hookSettings(
        script: Path,
        tools: List<String>,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("hooks") {
                putJsonArray("PreToolUse") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("matcher", tool)
                            putJsonArray("hooks") {
                                addJsonObject {
                                    put("type", "command")
                                    put("command", script.toString())
                                    put("timeout", HOOK_TIMEOUT_SECONDS)
                                }
                            }
                        }
                    }
                }
            }
        }

    private companion object {
        const val TOKEN_HEADER = "X-Zopf-Token"

        const val DENY_UNREACHABLE = "zopf could not be reached, so the answer is no"

        val OWNER_ONLY_FILE: Set<java.nio.file.attribute.PosixFilePermission> =
            PosixFilePermissions.fromString("rw-------")
        val OWNER_ONLY_EXEC: Set<java.nio.file.attribute.PosixFilePermission> =
            PosixFilePermissions.fromString("rwx------")
    }
}

internal fun decisionJson(
    allow: Boolean,
    reason: String?,
): String =
    buildJsonObject {
        putJsonObject("hookSpecificOutput") {
            put("hookEventName", "PreToolUse")
            put("permissionDecision", if (allow) "allow" else "deny")
            reason?.let { put("permissionDecisionReason", it) }
        }
    }.toString()

private fun summarize(input: JsonObject): String =
    listOf("command", "file_path", "url", "pattern", "description")
        .firstNotNullOfOrNull { input.text(it) }
        .orEmpty()

private fun JsonObject.text(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

private fun HttpExchange.respond(
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.write(bytes)
}
