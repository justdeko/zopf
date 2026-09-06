package com.dk.zopf.cli

import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.runtime.UpdateCheck
import com.dk.zopf.runtime.issues
import com.dk.zopf.runtime.money
import com.dk.zopf.runtime.updateChecksSilenced
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.BrokenWorkflow
import com.dk.zopf.store.BuildInfo
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import com.dk.zopf.store.SettingsStore
import com.dk.zopf.store.WorkflowStore
import com.dk.zopf.store.Workspace
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        out.println("  no workflows yet")
    }
    val width = listing.workflows.maxOfOrNull { it.name.length } ?: 0
    listing.workflows.forEach { workflow ->
        val detail =
            listOfNotNull(
                "${workflow.nodes.size} ${if (workflow.nodes.size == 1) "node" else "nodes"}",
                workflow.description
                    .lineSequence()
                    .firstOrNull()
                    ?.takeIf { it.isNotBlank() },
            )
        out.println("  ${workflow.name.padEnd(width)}  ${detail.joinToString(" · ")}")
    }
    listing.broken.forEach { out.println("  ${it.file.fileName}: doesn't parse. ${it.message}") }
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
                        it ?: throw UsageError("No workflow called \"$name\" in ${workspace.name} (${workspace.root})")
                    },
                    onFailure = {
                        broken += BrokenWorkflow(store.fileFor(name), it.message ?: it::class.simpleName.orEmpty())
                        null
                    },
                )
            }
        }

    var refused = broken.isNotEmpty()
    broken.forEach { out.println("${it.file.fileName}: doesn't parse. ${it.message}") }

    workflows.forEach { workflow ->
        val issues = workflow.issues(workspace)
        val stray = store.unknownKeys(workflow.name)
        issues.forEach { out.println("${workflow.name}: ${it.render()}") }
        stray.forEach {
            out.println("${workflow.name}: warning: ${it.where} sets \"${it.key}\", which zopf doesn't know, so it is ignored")
        }
        if (issues.any { it.severity == WorkflowIssue.Severity.ERROR }) refused = true
        if (issues.isEmpty() && stray.isEmpty()) out.println("${workflow.name}: ok")
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
        out.println("no runs archived yet")
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
        out.println("  ${archive.dir}")
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
        out.println("nothing to prune, keeping $keep")
        return
    }
    doomed.forEach { dir ->
        out.println("${if (dryRun) "would delete" else "deleted"} $dir")
        if (!dryRun) RunArchive.delete(dir)
    }
    out.println("${doomed.size} run(s)${if (dryRun) " would go" else " gone"}, $keep kept")
}

fun printVersion(
    out: PrintStream,
    err: PrintStream,
    check: UpdateCheck = UpdateCheck(),
    notify: Boolean = notifiable(),
) {
    out.println("zopf ${BuildInfo.version}")
    if (!notify) return
    val release = check.cached() ?: return
    err.println("zopf ${release.version} is out. ${release.url}")
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
                out.println("zopf ${release.version} is out. You have $running.")
                out.println(release.url)
            } else {
                out.println("zopf ${BuildInfo.version} is the latest release")
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
    val level = if (severity == WorkflowIssue.Severity.ERROR) "error" else "warning"
    return "$level: $message"
}

private fun RunRecord.cost(): Double? = nodes.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()

private fun String.readable(): String =
    runCatching {
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(this))
    }.getOrElse { this }
