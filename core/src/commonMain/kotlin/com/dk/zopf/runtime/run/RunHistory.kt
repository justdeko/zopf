package com.dk.zopf.runtime.run

import com.dk.zopf.runtime.exec.ShellLine
import com.dk.zopf.runtime.exec.Worktrees
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.useLines

internal fun RunRecord.hasLiveOwner(): Boolean {
    val owner = pid ?: return false
    if (owner == ProcessHandle.current().pid()) return false
    val handle = ProcessHandle.of(owner).orElse(null)?.takeIf { it.isAlive } ?: return false
    return startedUnder(handle)
}

internal fun RunRecord.isOursInFlight(): Boolean {
    val current = ProcessHandle.current()
    val active = runCatching { RunStatus.valueOf(status) }.getOrNull()?.isActive ?: false
    return active && pid == current.pid() && startedUnder(current)
}

private fun RunRecord.startedUnder(handle: ProcessHandle): Boolean {
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
        skipOursInFlight: Boolean = false,
    ): List<WorkflowRun> =
        RunArchive
            .all(root)
            .take(limit)
            .mapNotNull { archive ->
                archive.read()?.takeUnless { skipOursInFlight && it.isOursInFlight() }?.let { record ->
                    WorkflowRun.restored(record, record.restoreAs()).also { it.archiveDir = archive.dir }
                }
            }

    fun delete(
        dir: Path,
        worktreeRoot: Path = AppPaths.worktreesFor(dir.parent.parent),
    ): List<Path> {
        val kept = Worktrees.retire(dir.name, worktreeRoot)
        RunArchive.delete(dir)
        return kept
    }

    fun sweepWorktrees(
        archiveRoot: Path = AppPaths.runsDir,
        keepDays: Int,
        worktreeRoot: Path = AppPaths.worktreesFor(archiveRoot),
        now: Instant = Instant.now(),
    ): List<Path> {
        if (!worktreeRoot.isDirectory()) return emptyList()
        return worktreeRoot
            .listDirectoryEntries()
            .filter { it.isDirectory() && isExpired(RunArchive.find(it.name, archiveRoot)?.read(), keepDays, now) }
            .flatMap { Worktrees.retire(it.name, worktreeRoot) }
    }

    private fun isExpired(
        record: RunRecord?,
        keepDays: Int,
        now: Instant,
    ): Boolean {
        if (record == null) return true
        val active = runCatching { RunStatus.valueOf(record.status) }.getOrNull()?.isActive ?: false
        if (active && (record.hasLiveOwner() || record.isOursInFlight())) return false
        if (keepDays <= 0) return false
        val settled = runCatching { Instant.parse(record.finishedAt ?: record.startedAt) }.getOrNull() ?: return false
        return settled.plus(Duration.ofDays(keepDays.toLong())).isBefore(now)
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
