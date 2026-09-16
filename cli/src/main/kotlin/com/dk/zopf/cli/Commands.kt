package com.dk.zopf.cli

import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.runtime.UpdateCheck
import com.dk.zopf.runtime.issues
import com.dk.zopf.runtime.updateChecksSilenced
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.BuildInfo
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import com.dk.zopf.store.SettingsStore
import com.dk.zopf.store.workflow.BrokenWorkflow
import com.dk.zopf.store.workflow.WorkflowStore
import com.dk.zopf.store.workspace.Workspace
import com.dk.zopf.util.Strings
import com.dk.zopf.util.money
import com.dk.zopf.util.stamp
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

val LIST_OPTIONS = setOf("workspace")
val VALIDATE_OPTIONS = setOf("workspace")
val RUNS_OPTIONS = setOf("last")
val PRUNE_OPTIONS = setOf("keep", "older-than")
val PRUNE_SWITCHES = setOf("dry-run")

private const val DEFAULT_RUNS = 10

private const val DEFAULT_KEEP = 200

fun listWorkflows(
    options: Options,
    out: PrintStream,
): Int {
    val workspace = locateWorkspace(options.one("workspace"))
    val listing = WorkflowStore(workspace).list()

    out.println("${workspace.name} · ${workspace.root}")
    if (listing.workflows.isEmpty() && listing.broken.isEmpty()) {
        out.println(Strings.Cli.NO_WORKFLOWS_YET)
    }
    val width = listing.workflows.maxOfOrNull { it.name.length } ?: 0
    listing.workflows.forEach { workflow ->
        val detail =
            listOfNotNull(
                Strings.Words.count(workflow.nodes.size, Strings.Words.NODE),
                workflow.description
                    .lineSequence()
                    .firstOrNull()
                    ?.takeIf { it.isNotBlank() },
            )
        out.println("  ${workflow.name.padEnd(width)}  ${detail.joinToString(" · ")}")
    }
    listing.broken.forEach { out.println("  " + Strings.Cli.brokenFile(it.file.fileName, it.message)) }
    return EXIT_OK
}

fun validateWorkflows(
    options: Options,
    out: PrintStream,
): Int {
    val workspace = locateWorkspace(options.one("workspace"))
    val store = WorkflowStore(workspace)
    val listing = store.list()

    val requested = options.positionals
    val broken = mutableListOf<BrokenWorkflow>()
    val workflows =
        if (requested.isEmpty()) {
            broken += listing.broken
            listing.workflows
        } else {
            requested.mapNotNull { name ->
                runCatching { store.load(name) }.fold(
                    onSuccess = {
                        it ?: throw UsageError(Strings.Cli.noSuchWorkflow(name, workspace.name, workspace.root))
                    },
                    onFailure = {
                        broken += BrokenWorkflow(store.fileFor(name), it.message ?: it::class.simpleName.orEmpty())
                        null
                    },
                )
            }
        }

    var refused = broken.isNotEmpty()
    workspace.configProblem?.let { out.println("${Strings.Words.WARNING}: $it") }
    broken.forEach { out.println(Strings.Cli.brokenFile(it.file.fileName, it.message)) }

    workflows.forEach { workflow ->
        val issues = workflow.issues(workspace)
        val stray = store.unknownKeys(workflow.name)
        issues.forEach { out.println(Strings.Cli.issueLine(workflow.name, it.render())) }
        stray.forEach {
            out.println(Strings.Cli.warningLine(workflow.name, Strings.Validation.unknownKey(it.where, it.key)))
        }
        if (issues.any { it.severity == WorkflowIssue.Severity.ERROR }) refused = true
        if (issues.isEmpty() && stray.isEmpty()) out.println(Strings.Cli.okLine(workflow.name))
    }
    return if (refused) EXIT_USAGE else EXIT_OK
}

fun listRuns(
    options: Options,
    out: PrintStream,
    archiveRoot: Path = AppPaths.runsDir,
) {
    val last = options.int("last", 1..1000) ?: DEFAULT_RUNS
    val records = RunArchive.all(archiveRoot).take(last).mapNotNull { archive -> archive.read()?.let { it to archive } }
    if (records.isEmpty()) {
        out.println(Strings.Cli.NO_RUNS_YET)
        return
    }
    records.forEach { (record, archive) ->
        val detail =
            listOfNotNull(
                record.status.lowercase(),
                "${record.nodes.count { it.status == "SUCCEEDED" }}/${record.nodes.size}",
                record.cost()?.let { money(it) },
            )
        out.println("${record.startedAt.readable()}  ${record.workflow} · ${detail.joinToString(" · ")}")
        out.println("  ${record.id.take(SHORT_ID)}  ${archive.dir}")
    }
}

fun pruneRuns(
    options: Options,
    out: PrintStream,
    archiveRoot: Path = AppPaths.runsDir,
) {
    val keep = options.int("keep", 0..100_000) ?: DEFAULT_KEEP
    val olderThan = options.int("older-than", 1..36_500)?.let { Duration.ofDays(it.toLong()) }
    val dryRun = options.has("dry-run")

    val doomed = RunArchive.prune(archiveRoot, keep, olderThan)
    if (doomed.isEmpty()) {
        out.println(Strings.Cli.nothingToPrune(keep))
        return
    }
    doomed.forEach { dir ->
        out.println("${if (dryRun) Strings.Cli.WOULD_DELETE else Strings.Cli.DELETED} $dir")
        if (!dryRun) RunArchive.delete(dir)
    }
    out.println(Strings.Cli.pruned(doomed.size, if (dryRun) Strings.Cli.WOULD_GO else Strings.Cli.GONE, keep))
}

fun printVersion(
    out: PrintStream,
    err: PrintStream,
    check: UpdateCheck = UpdateCheck(),
    notify: Boolean = notifiable(),
) {
    out.println(Strings.version(BuildInfo.version))
    if (!notify) return
    val release = check.cached() ?: return
    err.println(Strings.Cli.newerRelease("${release.version}"))
}

fun checkForUpdate(
    out: PrintStream,
    err: PrintStream,
    check: UpdateCheck = UpdateCheck(),
): Int =
    check.fetch().fold(
        onSuccess = { release ->
            val running = check.running
            if (running != null && release.version > running) {
                out.println(Strings.Updates.available("${release.version}", "$running"))
                out.println(Strings.Cli.readRelease(release.url))
            } else {
                out.println(Strings.Cli.latestAlready(BuildInfo.version))
            }
            EXIT_OK
        },
        onFailure = {
            err.println("zopf: couldn't ask GitHub for the latest release: ${it.message}")
            EXIT_FAILED
        },
    )

private fun notifiable(): Boolean =
    System.console() != null &&
        System.getenv("CI").isNullOrBlank() &&
        !updateChecksSilenced() &&
        SettingsStore().load().checkForUpdates

fun Workflow.issues(workspace: Workspace): List<WorkflowIssue> = issues(workspace, SettingsStore().load().defaultProvider)

internal fun WorkflowIssue.render(): String {
    val level = if (severity == WorkflowIssue.Severity.ERROR) Strings.Words.ERROR else Strings.Words.WARNING
    return "$level: $message"
}

private fun RunRecord.cost(): Double? = nodes.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()

private fun String.readable(): String = runCatching { stamp(Instant.parse(this)) }.getOrElse { this }
