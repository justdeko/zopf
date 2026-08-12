package com.dk.zopf.runtime

import com.dk.zopf.model.EdgeTrigger
import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.WORKFLOW_VERSION
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.label
import com.dk.zopf.model.withDefaultsFrom
import com.dk.zopf.store.ConnectorStore
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class WorkflowEngine(
    private val scope: CoroutineScope,
    private val executor: NodeExecutor = ProcessNodeExecutor(),
    private val archiveRoot: Path,
    private val settings: LiveSettings = LiveSettings(),
    private val onProgress: (WorkflowRun) -> Unit = {},
    private val onFinished: (WorkflowRun) -> Unit = {},
    private val onWaiting: (NodeRun) -> Unit = {},
    private val onRaw: (String, String) -> Unit = { _, _ -> },
) {
    fun start(
        workspace: Workspace?,
        workflow: Workflow,
        only: WorkflowNode? = null,
        inherited: Map<String, NodeOutput> = emptyMap(),
        onNodeReady: (NodeRun) -> Unit = {},
    ): Result<WorkflowRun> =
        startResolved(
            workspace = workspace,
            workflow = workflow.withDefaultsFrom(workspace?.config?.defaults ?: NodeDefaults()),
            only = only,
            inherited = inherited,
            onNodeReady = onNodeReady,
        )

    private fun startResolved(
        workspace: Workspace?,
        workflow: Workflow,
        only: WorkflowNode?,
        inherited: Map<String, NodeOutput>,
        onNodeReady: (NodeRun) -> Unit,
    ): Result<WorkflowRun> {
        if (workflow.isFromTheFuture) {
            return Result.failure(
                IllegalArgumentException(
                    "${workflow.name} needs workflow format v${workflow.version}. This zopf reads " +
                        "v$WORKFLOW_VERSION, so update zopf to run it",
                ),
            )
        }

        val plan = only?.let(::listOf) ?: workflow.nodes
        if (plan.isEmpty()) {
            return Result.failure(IllegalArgumentException("${workflow.name} has no nodes yet"))
        }
        if (only != null && !only.type.needsProcess()) {
            return Result.failure(
                IllegalArgumentException(
                    "${only.type.label} nodes only mean something with the rest of the graph " +
                        "around them, so run the workflow instead",
                ),
            )
        }

        val placements = plan.associate { it.id to resolveCwd(workspace, workflow, it) }
        if (only != null) {
            placements.getValue(only.id).exceptionOrNull()?.let { return Result.failure(it) }
        }

        val run =
            WorkflowRun(
                id = UUID.randomUUID().toString(),
                workflowName = workflow.name,
                workspaceRoot = workspace?.root,
                isInteractive = only != null,
            )

        run.workflow = workflow
        run.workspace = workspace
        plan.forEach { node ->
            run.nodes +=
                NodeRun(
                    id = "${run.id}:${node.id}",
                    workflowName = workflow.name,
                    nodeId = node.id,
                    nodeTitle = node.displayTitle,
                    nodeType = node.type,
                    cwd = placements.getValue(node.id).getOrNull(),
                    provider =
                        node.takeIf { it.type == NodeType.AGENT }?.let {
                            resolveProvider(it, workflow, settings.current)
                        },
                )
        }

        run.nodes.forEach(onNodeReady)
        run.nodes.forEach { node -> inherited[node.nodeId]?.let(node::carryOver) }

        val archive = RunArchive.create(run.id, RunArchive.workspaceId(workspace?.root), archiveRoot, onRaw)
        archive.write(run.record())

        run.job =
            scope.launch {
                try {
                    schedule(run, workflow, workspace, plan, placements, archive, inherited)
                } finally {
                    finalize(run, archive)
                }
            }
        return Result.success(run)
    }

    fun stop(run: WorkflowRun) {
        run.stopping = true
        run.nodes.forEach { node ->
            node.stopping = true
            node.live?.stop()
        }
        run.job?.cancel()
    }

    fun resolveGate(
        node: NodeRun,
        approved: Boolean,
    ) {
        node.approval?.complete(approved)
    }

    fun resolveInput(
        node: NodeRun,
        answer: String?,
    ) {
        node.pendingQuestion?.answer(answer)
    }

    private suspend fun schedule(
        run: WorkflowRun,
        workflow: Workflow,
        workspace: Workspace?,
        plan: List<WorkflowNode>,
        placements: Map<String, Result<Path?>>,
        archive: RunArchive,
        inherited: Map<String, NodeOutput>,
    ) {
        val outputs = RunContext(inherited)
        val branches = ConcurrentHashMap<String, Boolean>()

        plan.filter { it.type == NodeType.BRANCH }.forEach { node ->
            inherited[node.id]?.result?.toBooleanStrictOrNull()?.let { branches[node.id] = it }
        }

        val completions = Channel<Unit>(Channel.UNLIMITED)

        val permits = Semaphore(settings.current.concurrency)
        val planned = plan.mapTo(mutableSetOf()) { it.id }

        coroutineScope {
            var inFlight = 0

            while (true) {
                for (node in settle(run, workflow, plan, planned, branches)) {
                    val nodeRun = run.node(node.id) ?: continue
                    inFlight++
                    launch {
                        runNode(run, nodeRun, node, workflow, workspace, outputs, branches, placements, archive, permits)
                        onProgress(run)
                        archive.write(run.record())
                        completions.send(Unit)
                    }
                }

                if (inFlight == 0) break
                completions.receive()
                inFlight--
            }
        }

        run.nodes.filter { it.status == RunStatus.QUEUED }.forEach {
            it.notice("Never became reachable. Its dependencies are in a loop.", isWarning = true)
            it.finish(RunStatus.FAILED)
        }
    }

    private fun settle(
        run: WorkflowRun,
        workflow: Workflow,
        plan: List<WorkflowNode>,
        planned: Set<String>,
        branches: Map<String, Boolean>,
    ): List<WorkflowNode> {
        val ready = mutableListOf<WorkflowNode>()
        var changed = true
        while (changed) {
            changed = false
            for (node in plan) {
                val nodeRun = run.node(node.id) ?: continue
                if (nodeRun.status != RunStatus.QUEUED) continue

                val incoming = workflow.edges.filter { it.to == node.id && it.from in planned }
                val arrivals =
                    incoming.map { edge ->
                        val source = run.node(edge.from)
                        when {
                            source == null || source.status.isActive -> Arrival.PENDING
                            !edge.on.accepts(source.status) -> Arrival.DEAD
                            edge.condition != null && branches[edge.from] != edge.condition -> Arrival.DEAD
                            else -> Arrival.ARRIVED
                        }
                    }

                when {
                    arrivals.any { it == Arrival.PENDING } -> Unit

                    incoming.isEmpty() || arrivals.any { it == Arrival.ARRIVED } -> {
                        nodeRun.status = RunStatus.STARTING
                        ready += node
                    }

                    incoming.any {
                        it.on == EdgeTrigger.SUCCESS && run.node(it.from)?.status == RunStatus.FAILED
                    } -> {
                        nodeRun.notice("Skipped: something it depends on failed")
                        nodeRun.finish(RunStatus.SKIPPED)
                        changed = true
                    }

                    else -> {
                        nodeRun.notice("Skipped: the run took another path")
                        nodeRun.finish(RunStatus.SKIPPED)
                        changed = true
                    }
                }
            }
        }
        return ready
    }

    private enum class Arrival { PENDING, ARRIVED, DEAD }

    private fun EdgeTrigger.accepts(status: RunStatus): Boolean =
        when (this) {
            EdgeTrigger.SUCCESS -> status == RunStatus.SUCCEEDED
            EdgeTrigger.FAILURE -> status == RunStatus.FAILED
        }

    private suspend fun runNode(
        run: WorkflowRun,
        nodeRun: NodeRun,
        node: WorkflowNode,
        workflow: Workflow,
        workspace: Workspace?,
        outputs: RunContext,
        branches: MutableMap<String, Boolean>,
        placements: Map<String, Result<Path?>>,
        archive: RunArchive,
        permits: Semaphore,
    ) {
        try {
            when (node.type) {
                NodeType.GATE -> awaitGate(nodeRun, node)
                NodeType.INPUT -> awaitInput(nodeRun, node, outputs)
                NodeType.BRANCH -> evaluateBranch(nodeRun, node, outputs, branches)

                else ->
                    permits.withPermit {
                        placements.getValue(node.id).getOrThrow()
                        executor.execute(
                            NodeExecution(
                                run = nodeRun,
                                node = node,
                                workflow = workflow,
                                workspace = workspace,
                                outputs = outputs,
                                archive = archive,
                                interactive = run.isInteractive,
                            ),
                        )
                    }
            }
        } catch (cancelled: CancellationException) {
            nodeRun.live?.stop()
            if (nodeRun.status.isActive) nodeRun.finish(RunStatus.STOPPED)
            throw cancelled
        } catch (failure: Throwable) {
            nodeRun.notice(failure.message ?: failure.toString(), isWarning = true)
            nodeRun.finish(RunStatus.FAILED)
        }

        if (nodeRun.status.isActive) nodeRun.finish(RunStatus.SUCCEEDED)
        outputs.record(node.id, nodeRun.output())
    }

    private suspend fun awaitGate(
        nodeRun: NodeRun,
        node: WorkflowNode,
    ) {
        val approval = CompletableDeferred<Boolean>()
        nodeRun.approval = approval
        nodeRun.status = RunStatus.WAITING
        nodeRun.notice(node.title.ifBlank { "Waiting for you" })
        onWaiting(nodeRun)

        val approved = approval.await()
        nodeRun.produce(if (approved) "approved" else "rejected")
        nodeRun.notice(if (approved) "Approved" else "Rejected. The rest of the run is skipped.")
        nodeRun.finish(if (approved) RunStatus.SUCCEEDED else RunStatus.STOPPED)
    }

    private suspend fun awaitInput(
        nodeRun: NodeRun,
        node: WorkflowNode,
        outputs: RunContext,
    ) {
        val question = outputs.interpolate(node.prompt).also { warnUnresolved(nodeRun, it) }.text
        val pending =
            PendingQuestion(
                question = question,
                choices = node.choices,
                default = node.default,
            )
        nodeRun.pendingQuestion = pending
        nodeRun.status = RunStatus.WAITING
        nodeRun.notice(question)
        onWaiting(nodeRun)

        val answer = pending.await()

        nodeRun.pendingQuestion = null
        if (answer == null) {
            nodeRun.notice("Cancelled. The rest of the run is skipped.")
            nodeRun.finish(RunStatus.STOPPED)
            return
        }
        nodeRun.produce(answer)
        nodeRun.notice("You: $answer")
        nodeRun.finish(RunStatus.SUCCEEDED)
    }

    private fun evaluateBranch(
        nodeRun: NodeRun,
        node: WorkflowNode,
        outputs: RunContext,
        branches: MutableMap<String, Boolean>,
    ) {
        val interpolated = outputs.interpolate(node.expression).also { warnUnresolved(nodeRun, it) }
        val verdict = Branches.evaluate(interpolated.text)
        branches[node.id] = verdict.taken
        nodeRun.produce(verdict.taken.toString())
        nodeRun.notice(verdict.explanation)
        nodeRun.finish(RunStatus.SUCCEEDED)
    }

    private fun finalize(
        run: WorkflowRun,
        archive: RunArchive,
    ) {
        run.nodes.filter { it.status.isActive }.forEach {
            it.finish(if (run.stopping) RunStatus.STOPPED else RunStatus.FAILED)
        }
        run.finishedAt = Instant.now()
        run.outcome =
            when {
                run.nodes.any { it.status == RunStatus.FAILED } -> RunStatus.FAILED
                run.stopping || run.nodes.any { it.status == RunStatus.STOPPED } -> RunStatus.STOPPED
                run.nodes.any { it.status == RunStatus.DETACHED } -> RunStatus.DETACHED
                else -> RunStatus.SUCCEEDED
            }
        archive.write(run.record())
        archive.close()
        onFinished(run)
    }

    private fun resolveCwd(
        workspace: Workspace?,
        workflow: Workflow,
        node: WorkflowNode,
    ): Result<Path?> {
        if (!node.type.needsProcess()) return Result.success(null)

        if (node.type == NodeType.CONNECTOR) {
            if (node.connector.isBlank()) {
                return Result.failure(IllegalStateException("${node.displayTitle} doesn't name a connector"))
            }
            val found =
                ConnectorStore(workspace).find(node.connector)
                    ?: return Result.failure(
                        IllegalStateException(
                            "No connector called \"${node.connector}\" in " +
                                "${workspace?.name ?: "this workspace"} or ~/zopf/connectors",
                        ),
                    )
            return Result.success(found.dir)
        }

        val requested = node.repo ?: workflow.defaults.repo
        val path =
            when {
                requested != null ->
                    workspace?.resolveRepo(workflow, requested)
                        ?: return Result.failure(
                            IllegalStateException("Repo \"$requested\" isn't declared in ${workflow.name}"),
                        )

                else ->
                    workspace?.root
                        ?: return Result.failure(IllegalStateException("Open a workspace before running a node"))
            }
        if (!path.exists() || !path.isDirectory()) {
            return Result.failure(IllegalStateException("$path isn't there any more"))
        }
        return Result.success(path)
    }
}

class PendingQuestion(
    val question: String,
    val choices: List<String>,
    val default: String,
) {
    private val answer = CompletableDeferred<String?>()

    val initialAnswer: String
        get() =
            when {
                default.isNotBlank() -> default
                choices.isNotEmpty() -> choices.first()
                else -> ""
            }

    internal fun answer(text: String?) {
        answer.complete(text)
    }

    internal suspend fun await(): String? = answer.await()
}
