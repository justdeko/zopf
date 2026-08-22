package com.dk.zopf.cli

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.runOrder
import com.dk.zopf.runtime.NodeExecutor
import com.dk.zopf.runtime.NodeRun
import com.dk.zopf.runtime.ProcessNodeExecutor
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.WorkflowEngine
import com.dk.zopf.runtime.WorkflowRun
import com.dk.zopf.runtime.errors
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.MAX_CONCURRENCY
import com.dk.zopf.store.SettingsStore
import com.dk.zopf.store.WorkflowStore
import com.dk.zopf.store.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.PrintStream
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class GatePolicy { APPROVE, REJECT, FAIL }

val RUN_OPTIONS =
    setOf("workspace", "repo", "on-gate", "answer", "concurrency", "model", "provider", "format", "timeout")

val RUN_SWITCHES = setOf("dry-run")

private const val STOP_GRACE_MILLIS = 10_000L

private const val MAX_TIMEOUT_SECONDS = 86_400

fun runWorkflow(
    options: Options,
    out: PrintStream,
    err: PrintStream,
    executor: NodeExecutor? = null,
    archiveRoot: Path = AppPaths.runsDir,
): Int {
    val name =
        options.positionals.firstOrNull()
            ?: throw UsageError("Which workflow? Run \"zopf list\" to see what this workspace has")
    if (options.positionals.size > 1) {
        throw UsageError("One workflow at a time. \"${options.positionals.drop(1).joinToString(" ")}\" is extra")
    }

    val workspace = locateWorkspace(options.one("workspace"))
    val store = WorkflowStore(workspace)
    val loaded =
        store.load(name)
            ?: throw UsageError("No workflow called \"$name\" in ${workspace.name} (${workspace.root})")

    val gatePolicy = options.choice("on-gate", GatePolicy.entries.toTypedArray()) ?: GatePolicy.FAIL
    val answers = options.pairs("answer")
    val workflow =
        loaded
            .withRepos(options.pairs("repo"))
            .withDefaultModel(options.one("model"))
            .withDefaultProvider(options.choice("provider", AgentProviderId.entries.toTypedArray()))
    val format = options.choice("format", Format.entries.toTypedArray()) ?: Format.TEXT

    checkAnswers(workflow, answers)

    if (options.has("dry-run")) return describeRun(workflow, workspace, out)

    val errors = workflow.issues(workspace).errors()
    if (errors.isNotEmpty()) {
        err.println("zopf: ${workflow.name} didn't start, because it wouldn't get through the run. Fix these and try again:")
        errors.forEach { err.println("  ${it.render()}") }
        return EXIT_USAGE
    }

    val settings = LiveSettings(runSettings(options))
    val screen = renderer(format, out, colour = System.console() != null)
    val decisions = Decisions(gatePolicy, answers)

    val scope = CoroutineScope(Job() + Dispatchers.Default)
    val settledOnce = Settled(screen)
    val engine =
        WorkflowEngine(
            scope = scope,
            executor = executor ?: ProcessNodeExecutor(settings = settings, permissions = null),
            archiveRoot = archiveRoot,
            settings = settings,
            onProgress = settledOnce::report,
            onWaiting = decisions::decide,
            onRaw = screen::raw,
        )
    decisions.engine = engine

    val deadline = options.int("timeout", 1..MAX_TIMEOUT_SECONDS)
    val started =
        engine.start(workspace, workflow) { node ->
            node.onEntrySettled = { entry -> screen.entry(node, entry) }
        }
    val run =
        started.getOrElse {
            err.println("zopf: ${it.message}")
            return EXIT_USAGE
        }
    screen.starting(run)

    val stopper = stopOnSignal(engine, run)
    runBlocking { awaitRun(engine, run, deadline) }
    runCatching { Runtime.getRuntime().removeShutdownHook(stopper) }
    scope.coroutineContext[Job]?.cancel()

    screen.finished(run)
    return exitCodeFor(run)
}

private fun describeRun(
    workflow: Workflow,
    workspace: Workspace,
    out: PrintStream,
): Int {
    val issues = workflow.issues(workspace)
    issues.forEach { out.println(it.render()) }
    if (issues.errors().isNotEmpty()) return EXIT_USAGE

    out.println("${workflow.name} · ${workflow.nodes.size} nodes · nothing started")
    workflow.runOrder().forEachIndexed { wave, ids ->
        out.println("  ${wave + 1}. ${ids.joinToString()}")
    }
    return EXIT_OK
}

private suspend fun awaitRun(
    engine: WorkflowEngine,
    run: WorkflowRun,
    deadlineSeconds: Int?,
) {
    val job = run.job ?: return
    if (deadlineSeconds == null) {
        job.join()
        return
    }
    if (withTimeoutOrNull(deadlineSeconds.seconds) { job.join() } != null) return

    run.nodes
        .filter { it.status.isActive }
        .forEach { it.notice("Out of time. The run was given ${deadlineSeconds}s.", isWarning = true) }
    engine.stop(run)
    withTimeoutOrNull(STOP_GRACE_MILLIS.milliseconds) { job.join() }
}

private class Settled(
    private val screen: RunRenderer,
) {
    private val reported = mutableSetOf<String>()

    @Synchronized
    fun report(run: WorkflowRun) {
        run.nodes.filter { it.status.isFinished && reported.add(it.id) }.forEach(screen::settled)
    }
}

private class Decisions(
    private val policy: GatePolicy,
    private val answers: Map<String, String>,
) {
    lateinit var engine: WorkflowEngine

    fun decide(node: NodeRun) {
        when (node.nodeType) {
            NodeType.GATE -> decideGate(node)
            else -> answer(node)
        }
    }

    private fun decideGate(node: NodeRun) {
        when (policy) {
            GatePolicy.APPROVE -> {
                node.notice("Approved by --on-gate approve")
                engine.resolveGate(node, approved = true)
            }

            GatePolicy.REJECT -> {
                node.notice("Rejected by --on-gate reject")
                engine.resolveGate(node, approved = false)
            }

            GatePolicy.FAIL -> {
                node.notice(
                    "${node.nodeTitle} is a gate and nobody is here to answer it. " +
                        "Pass --on-gate approve or --on-gate reject to decide up front.",
                    isWarning = true,
                )
                engine.resolveGate(node, approved = false)
            }
        }
    }

    private fun answer(node: NodeRun) {
        val question = node.pendingQuestion ?: return
        val answer = answers[node.nodeId] ?: question.default.takeIf { it.isNotBlank() }
        if (answer == null) {
            node.notice(
                "Nothing answers ${node.nodeId} and it declares no default. " +
                    "Pass --answer ${node.nodeId}=<value>.",
                isWarning = true,
            )
        }
        engine.resolveInput(node, answer)
    }
}

fun checkAnswers(
    workflow: Workflow,
    answers: Map<String, String>,
) {
    answers.forEach { (nodeId, value) ->
        val node =
            workflow.node(nodeId)
                ?: throw UsageError("--answer $nodeId=…: ${workflow.name} has no node called \"$nodeId\"")
        if (node.type != NodeType.INPUT) {
            throw UsageError("--answer $nodeId=…: \"$nodeId\" is a ${node.type.name.lowercase()} node, not an input")
        }
        if (node.choices.isNotEmpty() && value !in node.choices) {
            throw UsageError("--answer $nodeId=$value: \"$nodeId\" offers ${node.choices.joinToString()}")
        }
    }
}

fun Workflow.withRepos(overrides: Map<String, String>): Workflow {
    if (overrides.isEmpty()) return this
    val kept = repos.filterNot { it.id in overrides.keys }
    return copy(repos = kept + overrides.map { (id, path) -> RepoRef(id, path) })
}

fun Workflow.withDefaultModel(model: String?): Workflow = if (model == null) this else copy(defaults = defaults.copy(model = model))

fun Workflow.withDefaultProvider(provider: AgentProviderId?): Workflow = if (provider == null) this else copy(defaults = defaults.copy(provider = provider))

private fun runSettings(options: Options): AppSettings {
    val stored = SettingsStore().read().getOrElse { AppSettings() }
    val concurrency = options.int("concurrency", 1..MAX_CONCURRENCY) ?: stored.concurrency
    return stored.copy(concurrency = concurrency)
}

private fun stopOnSignal(
    engine: WorkflowEngine,
    run: WorkflowRun,
): Thread {
    val hook =
        Thread {
            if (!run.isActive) return@Thread
            engine.stop(run)
            runBlocking { withTimeoutOrNull(STOP_GRACE_MILLIS.milliseconds) { run.job?.join() } }
        }
    Runtime.getRuntime().addShutdownHook(hook)
    return hook
}

fun exitCodeFor(run: WorkflowRun): Int =
    when (run.status) {
        RunStatus.SUCCEEDED -> EXIT_OK
        RunStatus.STOPPED -> EXIT_STOPPED
        else -> EXIT_FAILED
    }
