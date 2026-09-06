package com.dk.zopf.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.store.NodeRunRecord
import com.dk.zopf.store.RunRecord
import com.dk.zopf.store.Workspace
import kotlinx.coroutines.Job
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

enum class Restore {
    ORPHANED,
    SETTLED,
    LIVE,
}

@Stable
class WorkflowRun(
    val id: String,
    val workflowName: String,
    val workspaceRoot: Path?,
    val isInteractive: Boolean,
    val startedAt: Instant = Instant.now(),
) {
    val nodes = mutableStateListOf<NodeRun>()

    var outcome by mutableStateOf<RunStatus?>(null)
        internal set

    var finishedAt by mutableStateOf<Instant?>(null)
        internal set

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

    val status: RunStatus
        get() =
            outcome ?: when {
                nodes.any { it.status == RunStatus.WAITING } -> RunStatus.WAITING
                nodes.any { it.status.showsProgress } -> RunStatus.RUNNING
                else -> RunStatus.STARTING
            }

    val isActive: Boolean get() = outcome == null

    val isElsewhere: Boolean get() = fromArchive && isActive

    val focusNode: NodeRun?
        get() =
            nodes.firstOrNull { it.status == RunStatus.WAITING }
                ?: nodes.lastOrNull { it.status.showsProgress }
                ?: nodes.lastOrNull { it.status.isFinished && it.status != RunStatus.SKIPPED }
                ?: nodes.firstOrNull()

    fun node(nodeId: String): NodeRun? = nodes.firstOrNull { it.nodeId == nodeId }

    val costUsd: Double?
        get() = nodes.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()

    val tokens: Int?
        get() = nodes.mapNotNull { it.tokens }.takeIf { it.isNotEmpty() }?.sum()

    val settledCount: Int get() = nodes.count { it.status.isFinished }

    val reachedCount: Int get() = nodes.count { it.status != RunStatus.QUEUED }

    fun elapsed(now: Instant = Instant.now()): Duration = Duration.between(startedAt, finishedAt ?: now)

    fun summary(): String =
        buildString {
            append(status.label)
            if (nodes.size > 1) append(" · $reachedCount/${nodes.size}")
            if (isActive) {
                nodes
                    .firstOrNull { it.status == RunStatus.WAITING || it.status.showsProgress }
                    ?.let { append(" · ${it.nodeTitle}") }
            }
            spend(costUsd, tokens)?.let { append(" · $it") }
        }

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
                        status = node.status.name,
                        startedAt = node.startedAt.toString(),
                        finishedAt = node.finishedAt?.toString(),
                        cwd = node.cwd?.toString(),
                        sessionId = node.sessionId,
                        command = node.command,
                        exitCode = node.exitCode,
                        costUsd = node.costUsd,
                    )
                },
        )

    internal fun refreshFrom(
        record: RunRecord,
        restore: Restore,
    ) {
        record.nodes.forEach { archived ->
            val node = node(archived.nodeId) ?: return@forEach
            node.sessionId = archived.sessionId
            node.costUsd = archived.costUsd
            node.exitCode = archived.exitCode
            node.command = archived.command
            node.finishedAt = archived.finishedAt?.let(::parseInstant)
            node.status = statusOf(archived.status, restore)
        }
        finishedAt = record.finishedAt?.let(::parseInstant)
        outcome = statusOf(record.status, restore).takeIf { it.isFinished }
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
                run.nodes +=
                    NodeRun(
                        id = "${record.id}:${node.nodeId}",
                        workflowName = record.workflow,
                        nodeId = node.nodeId,
                        nodeTitle = node.nodeId,
                        nodeType = node.type,
                        cwd = node.cwd?.let { Paths.get(it) },
                        startedAt = parseInstant(node.startedAt),
                        provider = node.provider,
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

internal fun NodeType.needsProcess(): Boolean = this == NodeType.AGENT || this == NodeType.SHELL || this == NodeType.CONNECTOR
