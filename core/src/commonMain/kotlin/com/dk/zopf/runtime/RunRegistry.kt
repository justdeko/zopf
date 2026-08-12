package com.dk.zopf.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.descendantsOf
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

@Stable
class RunRegistry(
    private val scope: CoroutineScope,
    private val settings: LiveSettings = LiveSettings(),
    executor: NodeExecutor? = null,
    private val archiveRoot: Path,
) {
    val runs = mutableStateListOf<WorkflowRun>()

    var selectedRunId by mutableStateOf<String?>(null)

    var selectedNodeId by mutableStateOf<String?>(null)
        private set

    private val _notifications = MutableSharedFlow<RunNotification>(extraBufferCapacity = 16)
    val notifications: SharedFlow<RunNotification> = _notifications

    val permissions =
        PermissionBridge(
            nodeBySession = ::nodeBySession,
            onRequest = ::announcePermission,
        )

    private val engine =
        WorkflowEngine(
            scope = scope,
            executor = executor ?: ProcessNodeExecutor(settings = settings, permissions = permissions),
            archiveRoot = archiveRoot,
            settings = settings,
            onFinished = ::announce,
            onWaiting = ::announceQuestion,
        )

    val selectedRun: WorkflowRun? get() = runs.firstOrNull { it.id == selectedRunId }

    val selectedNode: NodeRun?
        get() =
            selectedRun?.let { run ->
                run.nodes.firstOrNull { it.id == selectedNodeId } ?: run.focusNode
            }

    val terminalApp: String get() = settings.current.terminalApp

    val activeCount: Int get() = runs.count { it.isActive }

    val waitingCount: Int get() = runs.count { it.status == RunStatus.WAITING }

    fun startWorkflow(
        workspace: Workspace?,
        workflow: Workflow,
    ): Result<WorkflowRun> = engine.start(workspace, workflow).onSuccess(::adopt)

    fun startNode(
        workspace: Workspace?,
        workflow: Workflow,
        node: WorkflowNode,
    ): Result<WorkflowRun> = engine.start(workspace, workflow, only = node).onSuccess(::adopt)

    fun retry(
        run: WorkflowRun,
        nodeId: String,
    ): Result<WorkflowRun> {
        if (run.isActive) {
            return Result.failure(IllegalStateException("${run.workflowName} is still running"))
        }
        val workflow =
            run.workflow
                ?: return Result.failure(
                    IllegalStateException("This run started before zopf last quit, so there is nothing to retry from"),
                )
        if (run.isInteractive) {
            return Result.failure(
                IllegalStateException("A single node run isn't a graph. Run it again from the editor."),
            )
        }
        if (workflow.node(nodeId) == null) {
            return Result.failure(IllegalStateException("\"$nodeId\" isn't in ${workflow.name} any more"))
        }

        val redo = workflow.descendantsOf(nodeId) + nodeId
        val inherited =
            run.nodes
                .filter { it.status == RunStatus.SUCCEEDED && it.nodeId !in redo }
                .associate { it.nodeId to it.output() }

        return engine.start(run.workspace, workflow, inherited = inherited).onSuccess(::adopt)
    }

    fun stop(run: WorkflowRun) = engine.stop(run)

    fun stopAll() {
        permissions.denyOutstanding()
        runs.filter { it.isActive }.forEach { engine.stop(it) }
    }

    fun shutdown() {
        permissions.shutdown()
        runs.filter { it.isActive }.forEach { engine.stop(it) }
    }

    fun remove(run: WorkflowRun) {
        if (run.isActive) engine.stop(run)
        runs.remove(run)
        if (selectedRunId == run.id) select(runs.firstOrNull())
    }

    fun approve(
        node: NodeRun,
        approved: Boolean,
    ) = engine.resolveGate(node, approved)

    fun answer(
        node: NodeRun,
        text: String?,
    ) = engine.resolveInput(node, text)

    val awaitingPermission: List<Pair<WorkflowRun, NodeRun>>
        get() = runs.flatMap { run -> run.nodes.filter { it.isAwaitingPermission }.map { run to it } }

    val awaitingInput: List<Pair<WorkflowRun, NodeRun>>
        get() = runs.flatMap { run -> run.nodes.filter { it.isAwaitingInput }.map { run to it } }

    fun decide(
        node: NodeRun,
        allow: Boolean,
        forRestOfRun: Boolean = false,
    ) {
        val pending = node.pendingPermission ?: return
        if (allow && forRestOfRun) {
            node.autoAllowed.add(pending.request.toolName)
            node.notice("Won't ask about ${pending.request.toolName} again in this run")
        }
        pending.decide(allow)
    }

    private fun announceQuestion(node: NodeRun) {
        val question = node.pendingQuestion ?: return
        _notifications.tryEmit(
            RunNotification(
                runId = runs.firstOrNull { run -> run.nodes.any { it.id == node.id } }?.id.orEmpty(),
                title = "${node.nodeTitle} · needs an answer",
                body = question.question,
                isFailure = false,
            ),
        )
    }

    private fun nodeBySession(sessionId: String): NodeRun? = runs.firstNotNullOfOrNull { run -> run.nodes.firstOrNull { it.sessionId == sessionId } }

    private fun announcePermission(node: NodeRun) {
        val request = node.pendingPermission?.request ?: return
        _notifications.tryEmit(
            RunNotification(
                runId = runs.firstOrNull { run -> run.nodes.any { it.id == node.id } }?.id.orEmpty(),
                title = "${node.nodeTitle} · needs you",
                body = "${request.toolName}: ${request.summary}".trim().trimEnd(':'),
                isFailure = false,
            ),
        )
    }

    fun send(
        node: NodeRun,
        text: String,
    ) {
        val live = node.live ?: return
        node.echoFollowUp(text)
        node.status = RunStatus.RUNNING
        live.send(text).onFailure { node.notice("Couldn't send that: ${it.message}", isWarning = true) }
    }

    fun finishInput(node: NodeRun) {
        node.notice("Ending the session")
        node.live?.endInput()
    }

    fun takeOver(node: NodeRun): Result<Unit> {
        val provider =
            node.agent
                ?: return Result.failure(IllegalStateException("Only an agent node has a session to take over"))
        if (!provider.capabilities.resumeInTerminal) {
            return Result.failure(
                IllegalStateException("${provider.id.label} can't resume a headless session, so there is nothing to hand over"),
            )
        }
        val sessionId =
            node.sessionId
                ?: return Result.failure(IllegalStateException("This run hasn't reported a session id yet"))
        val cwd =
            node.cwd
                ?: return Result.failure(IllegalStateException("This node has no directory to resume in"))

        val terminal = terminalApp
        val live = node.live
        live?.let {
            node.detached = true
            node.stopping = true
            it.stop()
        }
        return TerminalLauncher
            .takeOver(provider, sessionId, cwd, terminal)
            .onSuccess {
                val resume = (listOf(provider.executable) + provider.terminalArgs(sessionId)).joinToString(" ")
                node.notice("Taken over in $terminal. Continue there with $resume")
                if (live == null) node.status = RunStatus.DETACHED
            }.onFailure { node.notice("Couldn't open $terminal: ${it.message}", isWarning = true) }
    }

    fun select(run: WorkflowRun?) {
        selectedRunId = run?.id

        selectedNodeId = null
        run?.let(::readTranscript)
    }

    fun select(
        run: WorkflowRun,
        node: NodeRun,
    ) {
        selectedRunId = run.id
        selectedNodeId = node.id
        readTranscript(run)
    }

    private fun readTranscript(run: WorkflowRun) {
        if (!run.fromArchive || run.transcriptLoaded) return
        scope.launch(Dispatchers.IO) { RunHistory.loadTranscript(run) }
    }

    fun loadHistory() {
        val known = runs.mapTo(mutableSetOf()) { it.id }
        val archived = RunHistory.list(archiveRoot).filterNot { it.id in known }
        if (archived.isNotEmpty()) runs.addAll(archived)
    }

    fun forget(run: WorkflowRun): Result<Unit> {
        val dir =
            run.archiveDir
                ?: return Result.failure(IllegalStateException("This run isn't on disk yet"))
        return runCatching {
            RunArchive.delete(dir)
            remove(run)
        }
    }

    fun reconcile() {
        scope.launch(Dispatchers.IO) {
            val found = SessionReconciler.reconcile(archiveRoot)
            if (found.orphans.isNotEmpty()) runs.addAll(0, found.orphans)
            loadHistory()

            if (found.orphans.isEmpty()) return@launch
            _notifications.tryEmit(
                RunNotification(
                    runId = found.orphans.first().id,
                    title = "Sessions still running",
                    body =
                        "${found.orphans.size} session(s) from a previous launch are still alive. " +
                            "Take them over in Terminal or stop them.",
                    isFailure = false,
                ),
            )
        }
    }

    private fun adopt(run: WorkflowRun) {
        runs.add(0, run)
        select(run)
    }

    private fun announce(run: WorkflowRun) {
        val failed = run.status == RunStatus.FAILED
        _notifications.tryEmit(
            RunNotification(
                runId = run.id,
                title = "${run.workflowName} · ${run.status.label.lowercase()}",
                body = run.summary(),
                isFailure = failed,
            ),
        )
    }
}
