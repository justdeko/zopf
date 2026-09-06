package com.dk.zopf.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class RunStatus {
    QUEUED,

    STARTING,
    RUNNING,

    WAITING,
    SUCCEEDED,
    FAILED,

    STOPPED,

    SKIPPED,

    DETACHED,

    ;

    val isActive: Boolean
        get() = this == QUEUED || this == STARTING || this == RUNNING || this == WAITING

    val isFinished: Boolean get() = !isActive

    val showsProgress: Boolean get() = this == STARTING || this == RUNNING

    val label: String
        get() =
            when (this) {
                QUEUED -> "Queued"
                STARTING -> "Starting"
                RUNNING -> "Running"
                WAITING -> "Waiting for you"
                SUCCEEDED -> "Done"
                FAILED -> "Failed"
                STOPPED -> "Stopped"
                SKIPPED -> "Skipped"
                DETACHED -> "In Terminal"
            }
}

sealed class ConsoleEntry {
    abstract val key: Long

    class Message(
        override val key: Long,
        initial: String,
        val isThinking: Boolean = false,
        isStreaming: Boolean = true,
    ) : ConsoleEntry() {
        var text by mutableStateOf(initial)
            internal set

        var isStreaming by mutableStateOf(isStreaming)
            internal set
    }

    class ToolCall(
        override val key: Long,
        val toolUseId: String,
        val name: String,
        val summary: String,
        result: String? = null,
        isError: Boolean = false,
    ) : ConsoleEntry() {
        var result by mutableStateOf(result)
            internal set
        var isError by mutableStateOf(isError)
            internal set
    }

    class Output(
        override val key: Long,
        val text: String,
        val isError: Boolean,
    ) : ConsoleEntry()

    class Notice(
        override val key: Long,
        val text: String,
        val isWarning: Boolean = false,
    ) : ConsoleEntry()

    class Prompt(
        override val key: Long,
        val text: String,
    ) : ConsoleEntry()

    class Summary(
        override val key: Long,
        val text: String?,
        val costUsd: Double?,
        val durationMs: Long?,
        val isError: Boolean,
        val tokens: Int? = null,
    ) : ConsoleEntry()
}

internal interface LiveProcess {
    fun stop()

    fun send(text: String): Result<Unit>

    fun endInput()
}

@Stable
class NodeRun(
    val id: String,
    val workflowName: String,
    val nodeId: String,
    val nodeTitle: String,
    val nodeType: NodeType,
    val cwd: Path?,
    val startedAt: Instant = Instant.now(),
    val provider: AgentProviderId? = null,
) {
    var status by mutableStateOf(RunStatus.QUEUED)
        internal set
    var sessionId by mutableStateOf<String?>(null)
        internal set
    var model by mutableStateOf<String?>(null)
        internal set
    var costUsd by mutableStateOf<Double?>(null)
        internal set
    var tokens by mutableStateOf<Int?>(null)
        internal set
    var exitCode by mutableStateOf<Int?>(null)
        internal set
    var finishedAt by mutableStateOf<Instant?>(null)
        internal set
    var command by mutableStateOf<List<String>>(emptyList())
        internal set

    val entries = mutableStateListOf<ConsoleEntry>()

    var onEntrySettled: ((ConsoleEntry) -> Unit)? = null

    private val outputBuffer = StringBuilder()

    private var outputFields: Map<String, String> = emptyMap()
    private val keys = AtomicLong(0)

    private val nextKey: Long get() = keys.getAndIncrement()

    internal var live: LiveProcess? = null

    internal var approval: CompletableDeferred<Boolean>? = null

    var pendingQuestion by mutableStateOf<PendingQuestion?>(null)
        internal set

    var pendingPermission by mutableStateOf<PendingPermission?>(null)
        internal set

    internal val autoAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal var stopping: Boolean = false

    internal var detached: Boolean = false

    val agent: AgentProvider?
        get() =
            if (nodeType != NodeType.AGENT) null else AgentProviders.of(provider ?: AgentProviderId.CLAUDE)

    val canTakeOver: Boolean
        get() = agent?.capabilities?.resumeInTerminal == true && sessionId != null && cwd != null

    val canFollowUp: Boolean get() = agent?.capabilities?.followUps == true

    val isAwaitingApproval: Boolean
        get() = nodeType == NodeType.GATE && status == RunStatus.WAITING

    val isAwaitingPermission: Boolean get() = pendingPermission != null

    val isAwaitingInput: Boolean get() = pendingQuestion != null

    fun elapsed(now: Instant = Instant.now()): Duration = Duration.between(startedAt, finishedAt ?: now)

    @Synchronized
    fun output(): NodeOutput =
        NodeOutput(
            result = outputBuffer.toString().trim(),
            exitCode = exitCode,
            sessionId = sessionId,
            costUsd = costUsd,
            extras = outputFields,
        )

    @Synchronized
    fun consume(event: AgentEvent) {
        if (status == RunStatus.QUEUED || status == RunStatus.STARTING) status = RunStatus.RUNNING
        event.sessionId?.let { sessionId = it }

        when (event) {
            is AgentEvent.SystemInit -> {
                model = event.model ?: model
                notice("Session started · ${model ?: "default model"} · ${event.cwd ?: cwd}")
            }

            is AgentEvent.TextDelta -> appendDelta(event.text, event.isThinking)

            is AgentEvent.AssistantMessage ->
                event.blocks.forEach { block ->
                    when (block) {
                        is ContentBlock.Text -> closeStreaming(block.text, isThinking = false)
                        is ContentBlock.Thinking -> closeStreaming(block.text, isThinking = true)
                        is ContentBlock.ToolUse ->
                            add(
                                ConsoleEntry.ToolCall(nextKey, block.id, block.name, block.summary),
                            )

                        else -> Unit
                    }
                }

            is AgentEvent.UserMessage ->
                event.blocks
                    .filterIsInstance<ContentBlock.ToolResult>()
                    .forEach { attachResult(it) }

            is AgentEvent.Result -> {
                costUsd = event.costUsd ?: costUsd
                tokens = event.tokens ?: tokens
                closeStreaming(null, isThinking = false)

                event.permissionDenials.forEach {
                    notice("Denied: ${it.toolName} ${it.summary}".trimEnd(), isWarning = true)
                }

                event.text?.takeIf { it.isNotBlank() }?.let {
                    outputBuffer.setLength(0)
                    outputBuffer.append(it)
                }
                if (event.fields.isNotEmpty()) outputFields = event.fields
                if (!repeatsLastSummary(event)) {
                    add(
                        ConsoleEntry.Summary(
                            key = nextKey,
                            text = event.text,
                            costUsd = event.costUsd,
                            durationMs = event.durationMs,
                            isError = event.isError,
                            tokens = event.tokens,
                        ),
                    )
                }

                status =
                    when {
                        event.isError -> RunStatus.FAILED
                        canFollowUp -> RunStatus.WAITING
                        else -> RunStatus.RUNNING
                    }
            }

            is AgentEvent.Notice ->
                when {
                    event.kind == "rate_limit" && event.detail != "allowed" ->
                        notice("Rate limit: ${event.detail}", isWarning = true)

                    event.kind == "error" ->
                        event.detail?.takeIf { it.isNotBlank() }?.let { notice(it, isWarning = true) }

                    else -> Unit
                }

            is AgentEvent.NonJson -> notice(event.line, isWarning = true)

            is AgentEvent.Stream, is AgentEvent.Unknown -> Unit
        }
    }

    @Synchronized
    fun consume(line: ShellLine) {
        if (status == RunStatus.QUEUED || status == RunStatus.STARTING) status = RunStatus.RUNNING
        if (!line.isError) outputBuffer.appendLine(line.text)
        add(ConsoleEntry.Output(nextKey, line.text, line.isError))
    }

    @Synchronized
    internal fun produce(
        text: String,
        fields: Map<String, String> = emptyMap(),
    ) {
        outputBuffer.setLength(0)
        outputBuffer.append(text)
        outputFields = fields
    }

    @Synchronized
    internal fun reset() {
        entries.clear()
        outputBuffer.setLength(0)
        outputFields = emptyMap()
    }

    @Synchronized
    internal fun restore(settled: RunStatus) {
        closeStreaming(null, isThinking = false)
        status = settled
        pendingPermission = null
        pendingQuestion = null
        live = null
    }

    @Synchronized
    internal fun carryOver(output: NodeOutput) {
        produce(output.result, output.extras)
        sessionId = output.sessionId
        notice("Carried over from the previous attempt")
        finish(RunStatus.SUCCEEDED, output.exitCode)
    }

    @Synchronized
    internal fun beginPermission(pending: PendingPermission) {
        pendingPermission = pending
        status = RunStatus.WAITING
        notice("${pending.request.toolName} wants to run: ${pending.request.summary}".trimEnd(':', ' '))
    }

    @Synchronized
    internal fun endPermission(
        allowed: Boolean,
        request: PermissionRequest,
    ) {
        pendingPermission = null
        if (status == RunStatus.WAITING) status = RunStatus.RUNNING
        notice(
            if (allowed) "Allowed ${request.toolName}" else "Denied ${request.toolName}",
            isWarning = !allowed,
        )
    }

    @Synchronized
    internal fun takeFieldsFromJsonResult() {
        if (outputFields.isNotEmpty()) return
        val answer = outputBuffer.toString().trim()
        val obj = runCatching { Json.parseToJsonElement(answer).jsonObject }.getOrNull() ?: return

        outputFields = obj.toFields()
    }

    private fun repeatsLastSummary(event: AgentEvent.Result): Boolean {
        val last = entries.lastOrNull() as? ConsoleEntry.Summary ?: return false
        return last.isError && event.isError && last.text == event.text
    }

    @Synchronized
    fun notice(
        text: String,
        isWarning: Boolean = false,
    ) {
        add(ConsoleEntry.Notice(nextKey, text, isWarning))
    }

    @Synchronized
    fun prompt(text: String) {
        add(ConsoleEntry.Prompt(nextKey, text))
    }

    @Synchronized
    fun echoFollowUp(text: String) {
        add(ConsoleEntry.Notice(nextKey, "You: $text"))
    }

    @Synchronized
    internal fun finish(
        status: RunStatus,
        exitCode: Int? = null,
    ) {
        this.status = status
        this.exitCode = exitCode ?: this.exitCode
        finishedAt = Instant.now()
        closeStreaming(null, isThinking = false)
        live = null
        approval = null

        pendingPermission = null
        pendingQuestion = null
    }

    @Synchronized
    private fun appendDelta(
        text: String,
        isThinking: Boolean,
    ) {
        val live = entries.lastOrNull() as? ConsoleEntry.Message
        if (live != null && live.isStreaming && live.isThinking == isThinking) {
            live.text += text
        } else {
            closeStreaming(null, isThinking)
            add(ConsoleEntry.Message(nextKey, text, isThinking))
        }
    }

    @Synchronized
    private fun closeStreaming(
        finalText: String?,
        isThinking: Boolean,
    ) {
        val live = entries.lastOrNull() as? ConsoleEntry.Message
        if (live != null && live.isStreaming) {
            finalText?.let { live.text = it }
            live.isStreaming = false
            if (!live.isThinking) outputBuffer.appendLine(live.text)
            onEntrySettled?.invoke(live)
        } else if (finalText != null) {
            add(ConsoleEntry.Message(nextKey, finalText, isThinking).also { it.isStreaming = false })
            if (!isThinking) outputBuffer.appendLine(finalText)
        }
    }

    @Synchronized
    private fun attachResult(block: ContentBlock.ToolResult) {
        val call =
            entries
                .asReversed()
                .filterIsInstance<ConsoleEntry.ToolCall>()
                .firstOrNull { it.toolUseId == block.toolUseId }
                ?: return
        call.result = block.text
        call.isError = block.isError
    }

    @Synchronized
    private fun add(entry: ConsoleEntry) {
        entries.add(entry)
        if (entry !is ConsoleEntry.Message || !entry.isStreaming) onEntrySettled?.invoke(entry)
    }
}

fun NodeRun.showing(
    status: RunStatus = this.status,
    model: String? = this.model,
    sessionId: String? = this.sessionId,
    costUsd: Double? = this.costUsd,
    question: PendingQuestion? = this.pendingQuestion,
    permission: PendingPermission? = this.pendingPermission,
): NodeRun =
    apply {
        this.status = status
        this.model = model
        this.sessionId = sessionId
        this.costUsd = costUsd
        pendingQuestion = question
        pendingPermission = permission
    }
