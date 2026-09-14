package com.dk.zopf.cli

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.runOrder
import com.dk.zopf.runtime.NodeExecutor
import com.dk.zopf.runtime.ProcessNodeExecutor
import com.dk.zopf.runtime.WorkflowEngine
import com.dk.zopf.runtime.errors
import com.dk.zopf.runtime.run.NodeRun
import com.dk.zopf.runtime.run.Resume
import com.dk.zopf.runtime.run.ResumePoint
import com.dk.zopf.runtime.run.RunStatus
import com.dk.zopf.runtime.run.WorkflowRun
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.MAX_CONCURRENCY
import com.dk.zopf.store.SettingsStore
import com.dk.zopf.store.workflow.WorkflowStore
import com.dk.zopf.store.workspace.Workspace
import com.dk.zopf.util.Strings
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
    setOf("workspace", "repo", "on-gate", "answer", "concurrency", "model", "provider", "format", "timeout", "resume")

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
    val resumed =
        options.one("resume")?.let { id ->
            Resume.find(id, archiveRoot).getOrElse { throw UsageError(it.message.orEmpty()) }
        }
    val name =
        options.positionals.firstOrNull()
            ?: resumed?.workflowName
            ?: throw UsageError(Strings.Cli.WHICH_WORKFLOW)
    if (options.positionals.size > 1) {
        throw UsageError(Strings.Cli.oneAtATime(options.positionals.drop(1).joinToString(" ")))
    }
    if (resumed != null && name != resumed.workflowName) {
        throw UsageError(Strings.Cli.resumeMismatch(resumed.workflowName, name))
    }

    val workspace = locateWorkspace(options.one("workspace"))
    val store = WorkflowStore(workspace)
    val loaded =
        store.load(name)
            ?: throw UsageError(Strings.Cli.noSuchWorkflow(name, workspace.name, workspace.root))

    val gatePolicy = options.choice("on-gate", GatePolicy.entries.toTypedArray()) ?: GatePolicy.FAIL
    val answers = options.pairs("answer")
    val workflow =
        loaded
            .withRepos(options.pairs("repo"))
            .withDefaultModel(options.one("model"))
            .withDefaultProvider(options.choice("provider", AgentProviderId.entries.toTypedArray()))
    val format = options.choice("format", Format.entries.toTypedArray()) ?: Format.TEXT

    checkAnswers(workflow, answers)
    val point = resumed?.let { Resume.pointFor(it, workflow) }

    if (options.has("dry-run")) return describeRun(workflow, workspace, point, out)

    val errors = workflow.issues(workspace).errors()
    if (errors.isNotEmpty()) {
        err.println(Strings.Cli.wontGetThrough(workflow.name))
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
        engine.start(workspace, workflow, inherited = point?.carried.orEmpty()) { node ->
            node.onEntrySettled = { entry -> screen.entry(node, entry) }
        }
    val run =
        started.getOrElse {
            err.println(Strings.Cli.failed("${it.message}"))
            return EXIT_USAGE
        }
    screen.starting(run)
    point?.let { screen.resuming(it) }

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
    point: ResumePoint?,
    out: PrintStream,
): Int {
    val issues = workflow.issues(workspace)
    issues.forEach { out.println(it.render()) }
    if (issues.errors().isNotEmpty()) return EXIT_USAGE

    point?.let { out.println(it.plan()) }
    out.println(Strings.Cli.dryRunHeader(workflow.name, workflow.nodes.size))
    workflow.runOrder().forEachIndexed { wave, ids ->
        out.println(Strings.Cli.wave(wave, ids.joinToString()))
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
        .forEach { it.notice(Strings.Cli.outOfTime(deadlineSeconds), isWarning = true) }
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
                node.notice(Strings.Cli.approvedByPolicy())
                engine.resolveGate(node, approved = true)
            }

            GatePolicy.REJECT -> {
                node.notice(Strings.Cli.rejectedByPolicy())
                engine.resolveGate(node, approved = false)
            }

            GatePolicy.FAIL -> {
                node.notice(
                    Strings.Cli.gateNeedsPolicy(node.nodeTitle),
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
                Strings.Cli.inputNeedsAnswer(node.nodeId),
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
                ?: throw UsageError(Strings.Cli.answerUnknownNode(nodeId, workflow.name))
        if (node.type != NodeType.INPUT) {
            throw UsageError(Strings.Cli.answerWrongNodeType(nodeId, node.type.name.lowercase()))
        }
        if (node.choices.isNotEmpty() && value !in node.choices) {
            throw UsageError(Strings.Cli.answerNotAChoice(nodeId, value, node.choices.joinToString()))
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
