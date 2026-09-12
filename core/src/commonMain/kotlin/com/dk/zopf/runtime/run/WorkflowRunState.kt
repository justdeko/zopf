package com.dk.zopf.runtime.run

import com.dk.zopf.runtime.outcome
import com.dk.zopf.util.spend
import java.time.Duration
import java.time.Instant

data class WorkflowRunState(
    val outcome: RunStatus? = null,
    val finishedAt: Instant? = null,
    val nodes: List<NodeRun> = emptyList(),
    val nodeStates: Map<String, NodeRunState> = emptyMap(),
) {
    fun of(node: NodeRun): NodeRunState = nodeStates[node.id] ?: NodeRunState()

    val status: RunStatus
        get() =
            outcome ?: when {
                nodes.any { of(it).status == RunStatus.WAITING } -> RunStatus.WAITING
                nodes.any { of(it).status.showsProgress } -> RunStatus.RUNNING
                else -> RunStatus.STARTING
            }

    val focusNode: NodeRun?
        get() =
            nodes.firstOrNull { of(it).status == RunStatus.WAITING }
                ?: nodes.lastOrNull { of(it).status.showsProgress }
                ?: nodes.lastOrNull { of(it).status.isFinished && of(it).status != RunStatus.SKIPPED }
                ?: nodes.firstOrNull()

    val costUsd: Double?
        get() = nodes.mapNotNull { of(it).costUsd }.takeIf { it.isNotEmpty() }?.sum()

    val tokens: Int?
        get() = nodes.mapNotNull { of(it).tokens }.takeIf { it.isNotEmpty() }?.sum()

    val settledCount: Int get() = nodes.count { of(it).status.isFinished }

    val reachedCount: Int get() = nodes.count { of(it).status != RunStatus.QUEUED }

    val isActive: Boolean get() = outcome == null

    val awaitingPermission: List<NodeRun> get() = nodes.filter { of(it).isAwaitingPermission }

    val awaitingInput: List<NodeRun> get() = nodes.filter { of(it).isAwaitingInput }

    val awaitingApproval: List<NodeRun> get() = nodes.filter { it.isAwaitingApproval(of(it)) }

    fun elapsed(
        startedAt: Instant,
        now: Instant,
    ): Duration = Duration.between(startedAt, finishedAt ?: now)

    fun summary(): String =
        buildString {
            append(status.label)
            if (nodes.size > 1) append(" · $reachedCount/${nodes.size}")
            if (isActive) {
                nodes
                    .firstOrNull { of(it).status == RunStatus.WAITING || of(it).status.showsProgress }
                    ?.let { append(" · ${it.nodeTitle}") }
            }
            spend(costUsd, tokens)?.let { append(" · $it") }
        }
}
