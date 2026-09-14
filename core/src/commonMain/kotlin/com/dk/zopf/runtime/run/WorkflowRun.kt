package com.dk.zopf.runtime.run

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.runtime.macos.NotificationAction
import com.dk.zopf.runtime.outcome
import com.dk.zopf.store.NodeRunRecord
import com.dk.zopf.store.RunRecord
import com.dk.zopf.store.workspace.Workspace
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

enum class Restore {
    ORPHANED,
    SETTLED,
    LIVE,
}

class WorkflowRun(
    val id: String,
    val workflowName: String,
    val workspaceRoot: Path?,
    val isInteractive: Boolean,
    val startedAt: Instant = Instant.now(),
) {
    private val _state = MutableStateFlow(WorkflowRunState())

    val state: StateFlow<WorkflowRunState> = _state.asStateFlow()

    private fun update(transform: WorkflowRunState.() -> WorkflowRunState) {
        _state.update(transform)
    }

    internal fun add(node: NodeRun) {
        node.owner = this
        update { copy(nodes = nodes + node, nodeStates = nodeStates + (node.id to node.state.value)) }
    }

    internal fun nodeChanged(
        id: String,
        state: NodeRunState,
    ) {
        update { copy(nodeStates = nodeStates + (id to state)) }
    }

    internal fun settle(
        outcome: RunStatus?,
        finishedAt: Instant?,
    ) {
        update { copy(outcome = outcome, finishedAt = finishedAt) }
    }

    val nodes: List<NodeRun> get() = _state.value.nodes

    val outcome: RunStatus? get() = _state.value.outcome

    val finishedAt: Instant? get() = _state.value.finishedAt

    var job: Job? = null
        internal set

    var workflow: Workflow? = null
        internal set

    internal var workspace: Workspace? = null

    internal var stopping: Boolean = false

    var fromArchive: Boolean = false
        internal set

    var archiveDir: Path? = null
        internal set

    internal var transcriptLoaded: Boolean = false

    val status: RunStatus get() = _state.value.status

    internal fun records(): Flow<RunRecord> = state.map { record() }.distinctUntilChanged()

    val isActive: Boolean get() = _state.value.isActive

    fun isElsewhere(state: WorkflowRunState): Boolean = fromArchive && state.isActive

    val isElsewhere: Boolean get() = isElsewhere(_state.value)

    val focusNode: NodeRun? get() = _state.value.focusNode

    fun nodeShown(selectedId: String?): NodeRun? = nodes.firstOrNull { it.id == selectedId } ?: focusNode

    fun node(nodeId: String): NodeRun? = nodes.firstOrNull { it.nodeId == nodeId }

    val costUsd: Double? get() = _state.value.costUsd

    val tokens: Int? get() = _state.value.tokens

    val settledCount: Int get() = _state.value.settledCount

    val reachedCount: Int get() = _state.value.reachedCount

    fun elapsed(now: Instant = Instant.now()): Duration = _state.value.elapsed(startedAt, now)

    fun summary(): String = _state.value.summary()

    internal fun record(): RunRecord =
        RunRecord(
            id = id,
            workflow = workflowName,
            workspace = workspaceRoot?.toString(),
            startedAt = startedAt.toString(),
            finishedAt = finishedAt?.toString(),
            status = status.name,
            pid = ProcessHandle.current().pid(),
            nodes =
                nodes.map { node ->
                    NodeRunRecord(
                        nodeId = node.nodeId,
                        type = node.nodeType,
                        provider = node.provider,
                        model = node.model,
                        status = node.status.name,
                        startedAt = node.startedAt.toString(),
                        finishedAt = node.finishedAt?.toString(),
                        cwd = node.cwd?.toString(),
                        sessionId = node.sessionId,
                        command = node.command,
                        exitCode = node.exitCode,
                        costUsd = node.costUsd,
                        answer = node.decision(),
                    )
                },
        )

    internal fun refreshFrom(
        record: RunRecord,
        restore: Restore,
    ) {
        record.nodes.forEach { archived ->
            val node = node(archived.nodeId) ?: return@forEach
            archived.answer?.let(node::produce)
            node.update {
                archived(
                    status = statusOf(archived.status, restore),
                    sessionId = archived.sessionId,
                    model = archived.model,
                    costUsd = archived.costUsd,
                    exitCode = archived.exitCode,
                    command = archived.command,
                    finishedAt = archived.finishedAt?.let(::parseInstant),
                )
            }
        }
        settle(
            outcome = statusOf(record.status, restore).takeIf { it.isFinished },
            finishedAt = record.finishedAt?.let(::parseInstant),
        )
        if (!isActive && transcriptLoaded) {
            nodes.forEach { it.reset() }
            transcriptLoaded = false
        }
    }

    companion object {
        fun restored(
            record: RunRecord,
            restore: Restore,
        ): WorkflowRun {
            val run =
                WorkflowRun(
                    id = record.id,
                    workflowName = record.workflow,
                    workspaceRoot = record.workspace?.let { Paths.get(it) },
                    isInteractive = false,
                    startedAt = parseInstant(record.startedAt),
                )
            run.fromArchive = true
            record.nodes.forEach { node ->
                run.add(
                    NodeRun(
                        id = "${record.id}:${node.nodeId}",
                        workflowName = record.workflow,
                        nodeId = node.nodeId,
                        nodeTitle = node.nodeId,
                        nodeType = node.type,
                        cwd = node.cwd?.let { Paths.get(it) },
                        startedAt = parseInstant(node.startedAt),
                        provider = node.provider,
                    ),
                )
            }
            run.refreshFrom(record, restore)
            if (restore == Restore.ORPHANED) {
                run.nodes.forEach { it.notice("Started before zopf last quit, and is still running outside it.") }
            }
            return run
        }

        private fun statusOf(
            name: String,
            restore: Restore,
        ): RunStatus {
            val status = runCatching { RunStatus.valueOf(name) }.getOrDefault(RunStatus.STOPPED)
            return when {
                restore == Restore.ORPHANED -> RunStatus.DETACHED
                restore == Restore.LIVE || status.isFinished -> status
                else -> RunStatus.STOPPED
            }
        }

        private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse { Instant.now() }
    }
}

data class RunNotification(
    val runId: String,
    val title: String,
    val body: String,
    val isFailure: Boolean,
    val key: String = runId,
    val subtitle: String = "",
    val sound: String = "",
    val thread: String = "",
    val actions: List<NotificationAction> = emptyList(),
)

private fun NodeRun.decision(): String? = if (nodeType.needsProcess()) null else output().result.takeIf { it.isNotBlank() }

internal fun NodeType.needsProcess(): Boolean = this == NodeType.AGENT || this == NodeType.SHELL || this == NodeType.CONNECTOR
