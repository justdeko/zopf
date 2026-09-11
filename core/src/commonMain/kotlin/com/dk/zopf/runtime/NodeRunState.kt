package com.dk.zopf.runtime

import java.time.Duration
import java.time.Instant

data class NodeRunState(
    val status: RunStatus = RunStatus.QUEUED,
    val sessionId: String? = null,
    val model: String? = null,
    val costUsd: Double? = null,
    val tokens: Int? = null,
    val exitCode: Int? = null,
    val finishedAt: Instant? = null,
    val command: List<String> = emptyList(),
    val pendingQuestion: PendingQuestion? = null,
    val pendingPermission: PendingPermission? = null,
) {
    val isAwaitingInput: Boolean get() = pendingQuestion != null

    val isAwaitingPermission: Boolean get() = pendingPermission != null

    fun elapsed(
        startedAt: Instant,
        now: Instant,
    ): Duration = Duration.between(startedAt, finishedAt ?: now)

    fun observed(
        sessionId: String? = null,
        model: String? = null,
        costUsd: Double? = null,
        tokens: Int? = null,
    ): NodeRunState =
        copy(
            sessionId = sessionId ?: this.sessionId,
            model = model ?: this.model,
            costUsd = costUsd ?: this.costUsd,
            tokens = tokens ?: this.tokens,
        )

    fun launched(
        sessionId: String?,
        command: List<String>,
        model: String? = null,
    ): NodeRunState =
        copy(
            sessionId = sessionId ?: this.sessionId,
            command = command,
            model = model ?: this.model,
            status = RunStatus.RUNNING,
        )

    fun finished(
        status: RunStatus,
        exitCode: Int?,
        at: Instant,
    ): NodeRunState =
        copy(
            status = status,
            exitCode = exitCode ?: this.exitCode,
            finishedAt = at,
            pendingQuestion = null,
            pendingPermission = null,
        )

    fun restored(status: RunStatus): NodeRunState =
        copy(
            status = status,
            pendingQuestion = null,
            pendingPermission = null,
        )

    fun archived(
        status: RunStatus,
        sessionId: String?,
        model: String?,
        costUsd: Double?,
        exitCode: Int?,
        command: List<String>,
        finishedAt: Instant?,
    ): NodeRunState =
        copy(
            status = status,
            sessionId = sessionId,
            model = model,
            costUsd = costUsd,
            exitCode = exitCode,
            command = command,
            finishedAt = finishedAt,
        )

    fun asking(question: PendingQuestion): NodeRunState =
        copy(
            pendingQuestion = question,
            status = RunStatus.WAITING,
        )

    fun answered(): NodeRunState = copy(pendingQuestion = null)

    fun permissionAsked(permission: PendingPermission): NodeRunState =
        copy(
            pendingPermission = permission,
            status = RunStatus.WAITING,
        )

    fun permissionAnswered(): NodeRunState =
        copy(
            pendingPermission = null,
            status = if (status == RunStatus.WAITING) RunStatus.RUNNING else status,
        )
}
