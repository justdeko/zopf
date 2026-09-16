package com.dk.zopf.runtime.run

import com.dk.zopf.model.Workflow
import com.dk.zopf.model.descendantsOf
import com.dk.zopf.runtime.NodeOutput
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.RunArchive
import com.dk.zopf.util.Strings
import java.nio.file.Path
import kotlin.io.path.name

data class ResumePoint(
    val run: WorkflowRun,
    val carried: Map<String, NodeOutput>,
    val redo: List<String>,
)

object Resume {
    const val LATEST = "last"

    private const val SHOWN_MATCHES = 3

    fun find(
        id: String,
        root: Path = AppPaths.runsDir,
    ): Result<WorkflowRun> {
        val archives = RunArchive.all(root)
        if (archives.isEmpty()) {
            return Result.failure(IllegalStateException(Strings.RunErrors.NOTHING_ARCHIVED))
        }
        val matches = if (id == LATEST) archives.take(1) else archives.filter { it.dir.name.startsWith(id) }
        val archive =
            when {
                matches.isEmpty() ->
                    return Result.failure(
                        IllegalArgumentException(Strings.RunErrors.noArchivedRun(id)),
                    )

                matches.size > 1 ->
                    return Result.failure(
                        IllegalArgumentException(
                            Strings.RunErrors.ambiguousRunId(
                                id,
                                matches.size,
                                matches.take(SHOWN_MATCHES).joinToString { it.dir.name },
                            ),
                        ),
                    )

                else -> matches.single()
            }

        val record =
            archive.read()
                ?: return Result.failure(IllegalStateException("${archive.dir} has no run.json"))
        val run = WorkflowRun.restored(record, record.restoreAs()).also { it.archiveDir = archive.dir }
        if (run.isElsewhere) {
            return Result.failure(
                IllegalStateException(Strings.RunErrors.stillRunningSomewhereElse(run.workflowName)),
            )
        }
        return Result.success(run)
    }

    fun pointFor(
        run: WorkflowRun,
        workflow: Workflow,
        alsoRedo: Set<String> = emptySet(),
    ): ResumePoint {
        if (run.fromArchive) RunHistory.loadTranscript(run)

        val archived = run.nodes.associateBy { it.nodeId }
        val added = workflow.nodes.map { it.id }.filterNot { it in archived }
        val unfinished = run.nodes.filterNot { it.status == RunStatus.SUCCEEDED }.map { it.nodeId }
        val undecided = run.nodes.filter { !it.nodeType.needsProcess() && it.output().result.isBlank() }.map { it.nodeId }
        val redo = (added + unfinished + undecided + alsoRedo).flatMapTo(mutableSetOf()) { setOf(it) + workflow.descendantsOf(it) }

        val carried =
            workflow.nodes
                .map { it.id }
                .filterNot { it in redo }
                .mapNotNull { id -> archived[id]?.takeIf { it.status == RunStatus.SUCCEEDED }?.let { id to it.output() } }
                .toMap()

        return ResumePoint(
            run = run,
            carried = carried,
            redo = workflow.nodes.map { it.id }.filterNot { it in carried },
        )
    }
}
