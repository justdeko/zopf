package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
        data class Streaming(
            val text: String,
            val isStreaming: Boolean,
        )

        private val _live = MutableStateFlow(Streaming(initial, isStreaming))

        val live: StateFlow<Streaming> = _live.asStateFlow()

        val text: String get() = _live.value.text

        val isStreaming: Boolean get() = _live.value.isStreaming

        internal fun append(more: String) {
            _live.update { it.copy(text = it.text + more) }
        }

        internal fun settle(finalText: String?) {
            _live.update { Streaming(finalText ?: it.text, false) }
        }
    }

    class ToolCall(
        override val key: Long,
        val toolUseId: String,
        val name: String,
        val summary: String,
        result: String? = null,
        isError: Boolean = false,
    ) : ConsoleEntry() {
        data class Outcome(
            val result: String?,
            val isError: Boolean,
        )

        private val _outcome = MutableStateFlow(Outcome(result, isError))

        val outcome: StateFlow<Outcome> = _outcome.asStateFlow()

        val result: String? get() = _outcome.value.result

        val isError: Boolean get() = _outcome.value.isError

        internal fun complete(
            result: String?,
            isError: Boolean,
        ) {
            _outcome.value = Outcome(result, isError)
        }
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
    private val _state = MutableStateFlow(NodeRunState())

    val state: StateFlow<NodeRunState> = _state.asStateFlow()

    internal var owner: WorkflowRun? = null

    @Synchronized
    internal fun update(transform: NodeRunState.() -> NodeRunState) {
        val next = _state.updateAndGet(transform)
        owner?.nodeChanged(id, next)
    }

    val status: RunStatus get() = _state.value.status

    val sessionId: String? get() = _state.value.sessionId

    val model: String? get() = _state.value.model

    val costUsd: Double? get() = _state.value.costUsd

    val tokens: Int? get() = _state.value.tokens

    val exitCode: Int? get() = _state.value.exitCode

    val finishedAt: Instant? get() = _state.value.finishedAt

    val command: List<String> get() = _state.value.command

    private val log = ArrayList<ConsoleEntry>()

    private val _transcript = MutableStateFlow(0)

    val transcript: StateFlow<Int> = _transcript.asStateFlow()

    @get:Synchronized
    val entries: List<ConsoleEntry> get() = log.toList()

    @Synchronized
    fun entryAt(index: Int): ConsoleEntry? = log.getOrNull(index)

    var onEntrySettled: ((ConsoleEntry) -> Unit)? = null

    private val outputBuffer = StringBuilder()

    private var outputFields: Map<String, String> = emptyMap()
    private val keys = AtomicLong(0)

    private val nextKey: Long get() = keys.getAndIncrement()

    internal var live: LiveProcess? = null

    internal var approval: CompletableDeferred<Boolean>? = null

    val pendingQuestion: PendingQuestion? get() = _state.value.pendingQuestion

    val pendingPermission: PendingPermission? get() = _state.value.pendingPermission

    internal val autoAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal var stopping: Boolean = false

    internal var detached: Boolean = false

    val agent: AgentProvider?
        get() =
            if (nodeType != NodeType.AGENT) null else AgentProviders.of(provider ?: AgentProviderId.CLAUDE)

    fun agentLabel(state: NodeRunState): String? =
        agent?.let { provider ->
            listOfNotNull(provider.id.label, state.model?.takeIf { provider.capabilities.modelSelection })
                .joinToString(" · ")
        }

    fun canTakeOver(state: NodeRunState): Boolean = agent?.capabilities?.resumeInTerminal == true && state.sessionId != null && cwd != null

    val canTakeOver: Boolean get() = canTakeOver(_state.value)

    val canFollowUp: Boolean get() = agent?.capabilities?.followUps == true

    fun isAwaitingApproval(state: NodeRunState): Boolean = nodeType == NodeType.GATE && state.status == RunStatus.WAITING

    val isAwaitingApproval: Boolean get() = isAwaitingApproval(_state.value)

    fun elapsed(now: Instant = Instant.now()): Duration = _state.value.elapsed(startedAt, now)

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
        if (status == RunStatus.QUEUED || status == RunStatus.STARTING) update { copy(status = RunStatus.RUNNING) }
        event.sessionId?.let { session -> update { observed(sessionId = session) } }

        when (event) {
            is AgentEvent.SystemInit -> {
                update { observed(model = event.model ?: model) }
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
                update { observed(costUsd = event.costUsd, tokens = event.tokens) }
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

                update {
                    copy(
                        status =
                            when {
                                event.isError -> RunStatus.FAILED
                                canFollowUp -> RunStatus.WAITING
                                else -> RunStatus.RUNNING
                            },
                    )
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
        if (status == RunStatus.QUEUED || status == RunStatus.STARTING) update { copy(status = RunStatus.RUNNING) }
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
        log.clear()
        _transcript.value = 0
        outputBuffer.setLength(0)
        outputFields = emptyMap()
    }

    @Synchronized
    internal fun restore(settled: RunStatus) {
        closeStreaming(null, isThinking = false)
        update { restored(settled) }
        live = null
    }

    @Synchronized
    internal fun carryOver(output: NodeOutput) {
        produce(output.result, output.extras)
        update { observed(sessionId = output.sessionId) }
        notice("Carried over from the previous attempt")
        finish(RunStatus.SUCCEEDED, output.exitCode)
    }

    @Synchronized
    internal fun beginPermission(pending: PendingPermission) {
        update { permissionAsked(pending) }
        notice("${pending.request.toolName} wants to run: ${pending.request.summary}".trimEnd(':', ' '))
    }

    @Synchronized
    internal fun endPermission(
        allowed: Boolean,
        request: PermissionRequest,
    ) {
        update { permissionAnswered() }
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
        val last = log.lastOrNull() as? ConsoleEntry.Summary ?: return false
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
        update { finished(status, exitCode, Instant.now()) }
        closeStreaming(null, isThinking = false)
        live = null
        approval = null
    }

    @Synchronized
    private fun appendDelta(
        text: String,
        isThinking: Boolean,
    ) {
        val live = log.lastOrNull() as? ConsoleEntry.Message
        if (live != null && live.isStreaming && live.isThinking == isThinking) {
            live.append(text)
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
        val live = log.lastOrNull() as? ConsoleEntry.Message
        if (live != null && live.isStreaming) {
            live.settle(finalText)
            if (!live.isThinking) outputBuffer.appendLine(live.text)
            onEntrySettled?.invoke(live)
        } else if (finalText != null) {
            add(ConsoleEntry.Message(nextKey, finalText, isThinking, isStreaming = false))
            if (!isThinking) outputBuffer.appendLine(finalText)
        }
    }

    @Synchronized
    private fun attachResult(block: ContentBlock.ToolResult) {
        val call =
            log
                .asReversed()
                .filterIsInstance<ConsoleEntry.ToolCall>()
                .firstOrNull { it.toolUseId == block.toolUseId }
                ?: return
        call.complete(block.text, block.isError)
    }

    @Synchronized
    fun add(entry: ConsoleEntry) {
        log.add(entry)
        _transcript.value = log.size
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
        val shown =
            state.value.copy(
                status = status,
                model = model,
                sessionId = sessionId,
                costUsd = costUsd,
                pendingQuestion = question,
                pendingPermission = permission,
            )
        update { shown }
    }
