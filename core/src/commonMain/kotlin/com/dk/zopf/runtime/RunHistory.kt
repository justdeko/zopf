package com.dk.zopf.runtime

import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.useLines

internal fun RunRecord.hasLiveOwner(): Boolean {
    val owner = pid ?: return false
    if (owner == ProcessHandle.current().pid()) return false
    val handle = ProcessHandle.of(owner).orElse(null)?.takeIf { it.isAlive } ?: return false
    val began = runCatching { Instant.parse(startedAt) }.getOrNull() ?: return true
    val started = handle.info().startInstant().orElse(null) ?: return true
    return !started.isAfter(began)
}

internal fun RunRecord.restoreAs(): Restore = if (hasLiveOwner()) Restore.LIVE else Restore.SETTLED

object RunHistory {
    const val DEFAULT_LIMIT = 100

    fun list(
        root: Path = AppPaths.runsDir,
        limit: Int = DEFAULT_LIMIT,
    ): List<WorkflowRun> =
        RunArchive
            .all(root)
            .take(limit)
            .mapNotNull { archive ->
                archive.read()?.let { record ->
                    WorkflowRun.restored(record, record.restoreAs()).also { it.archiveDir = archive.dir }
                }
            }

    fun record(run: WorkflowRun): RunRecord? = run.archiveDir?.let { RunArchive(it).read() }

    fun loadTranscript(run: WorkflowRun) {
        if (run.transcriptLoaded) return
        run.transcriptLoaded = true

        val archive = run.archiveDir?.let { RunArchive(it) } ?: return
        run.nodes.forEach { node ->
            val settled = node.status
            val file = archive.rawPath(node.nodeId)
            if (!file.exists()) return@forEach

            runCatching {
                file.useLines { lines ->
                    lines.forEach { line ->
                        when (val agent = node.agent) {
                            null -> node.consume(ShellLine(line, isError = false))
                            else -> agent.parse(line)?.let(node::consume)
                        }
                    }
                }
            }.onFailure { node.notice("Couldn't read the transcript: ${it.message}", isWarning = true) }

            node.restore(settled)
        }
    }
}
