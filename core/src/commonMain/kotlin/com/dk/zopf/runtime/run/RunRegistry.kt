package com.dk.zopf.runtime.run

import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.runtime.NodeExecutor
import com.dk.zopf.runtime.ProcessNodeExecutor
import com.dk.zopf.runtime.SessionReconciler
import com.dk.zopf.runtime.WorkflowEngine
import com.dk.zopf.runtime.agent.APPROVAL_DEADLINE_SECONDS
import com.dk.zopf.runtime.agent.PermissionBridge
import com.dk.zopf.runtime.macos.NotificationAction
import com.dk.zopf.runtime.macos.NotificationHandle
import com.dk.zopf.runtime.macos.Notifier
import com.dk.zopf.runtime.macos.SilentNotifier
import com.dk.zopf.runtime.macos.TerminalLauncher
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.workflow.WorkflowStore
import com.dk.zopf.store.workspace.Workspace
import com.dk.zopf.util.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

private const val ARCHIVE_POLL_MILLIS = 4_000L
private const val MAX_NOTIFICATION_ACTIONS = 3
private const val WAITING_DEADLINE_SECONDS = 3_600L
private const val WAITING_SOUND = "Ping"
private const val FAILURE_SOUND = "Basso"
private const val SUCCESS_SOUND = "Glass"

data class RunsState(
    val runs: List<WorkflowRun> = emptyList(),
    val selected: WorkflowRun? = null,
    val selectedNodeId: String? = null,
) {
    val shownNode: NodeRun? get() = selected?.nodeShown(selectedNodeId)
}

data class RunSnapshot(
    val run: WorkflowRun,
    val state: WorkflowRunState,
)

data class RunActivity(
    val total: Int = 0,
    val active: Int = 0,
    val waiting: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RunRegistry(
    private val scope: CoroutineScope,
    private val settings: LiveSettings = LiveSettings(),
    executor: NodeExecutor? = null,
    private val archiveRoot: Path,
    private val notifier: Notifier = SilentNotifier,
    private val isForeground: () -> Boolean = { false },
    private val onActivate: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(RunsState())

    val state: StateFlow<RunsState> = _state.asStateFlow()

    val runs: List<WorkflowRun> get() = _state.value.runs

    val selectedRun: WorkflowRun? get() = _state.value.selected

    val live: StateFlow<List<RunSnapshot>> =
        _state
            .flatMapLatest { open ->
                when {
                    open.runs.isEmpty() -> flowOf(emptyList())
                    else ->
                        combine(open.runs.map { it.state }) { states ->
                            open.runs.mapIndexed { index, run -> RunSnapshot(run, states[index]) }
                        }
                }
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activity: StateFlow<RunActivity> =
        live
            .map { snapshots ->
                RunActivity(
                    total = snapshots.size,
                    active = snapshots.count { it.state.status.isActive },
                    waiting = snapshots.count { it.state.status == RunStatus.WAITING },
                )
            }.stateIn(scope, SharingStarted.Eagerly, RunActivity())

    private val asked = ConcurrentHashMap<String, NotificationHandle>()

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

    val terminalApp: String get() = settings.current.terminalApp

    fun startWorkflow(
        workspace: Workspace?,
        workflow: Workflow,
    ): Result<WorkflowRun> = engine.start(workspace, workflow).onSuccess(::adopt)

    fun startNode(
        workspace: Workspace?,
        workflow: Workflow,
        node: WorkflowNode,
    ): Result<WorkflowRun> = engine.start(workspace, workflow, only = node).onSuccess(::adopt)

    fun canRetry(run: WorkflowRun): Boolean = !run.isInteractive && (run.workflow != null || run.workspaceRoot != null)

    fun retry(
        run: WorkflowRun,
        nodeId: String,
    ): Result<WorkflowRun> {
        if (run.isActive) {
            return Result.failure(IllegalStateException(Strings.RunErrors.stillRunning(run.workflowName)))
        }
        if (run.isInteractive) {
            return Result.failure(
                IllegalStateException(Strings.RunErrors.NOT_A_GRAPH),
            )
        }
        val workspace = run.workspace ?: run.workspaceRoot?.let(Workspace::open)
        val workflow =
            run.workflow
                ?: workspace?.let { WorkflowStore(it).load(run.workflowName) }
                ?: return Result.failure(
                    IllegalStateException(
                        Strings.RunErrors.workflowGone(
                            run.workflowName,
                            "${run.workspaceRoot ?: Strings.RunErrors.THE_WORKSPACE_IT_RAN_IN}",
                        ),
                    ),
                )
        if (workflow.node(nodeId) == null) {
            return Result.failure(IllegalStateException(Strings.RunErrors.nodeGone(nodeId, workflow.name)))
        }

        val point = Resume.pointFor(run, workflow, alsoRedo = setOf(nodeId))
        return engine.start(workspace, workflow, inherited = point.carried).onSuccess(::adopt)
    }

    fun stop(run: WorkflowRun) {
        if (run.isElsewhere) return
        engine.stop(run)
    }

    fun stopAll() {
        permissions.denyOutstanding()
        ours().forEach { engine.stop(it) }
    }

    fun shutdown() {
        permissions.shutdown()
        ours().forEach { engine.stop(it) }
    }

    private fun ours(): List<WorkflowRun> = runs.filter { it.isActive && !it.isElsewhere }

    fun add(run: WorkflowRun) {
        _state.update { it.copy(runs = it.runs + run) }
    }

    fun remove(run: WorkflowRun) {
        if (run.isActive) stop(run)
        _state.update { it.copy(runs = it.runs - run) }
        if (_state.value.selected == run) select(runs.firstOrNull())
    }

    fun approve(
        node: NodeRun,
        approved: Boolean,
    ) {
        settled(node)
        engine.resolveGate(node, approved)
    }

    fun answer(
        node: NodeRun,
        text: String?,
    ) {
        settled(node)
        engine.resolveInput(node, text)
    }

    val awaitingPermission: List<Pair<WorkflowRun, NodeRun>>
        get() =
            runs.flatMap { run ->
                run.state.value.awaitingPermission
                    .map { run to it }
            }

    val awaitingInput: List<Pair<WorkflowRun, NodeRun>>
        get() =
            runs.flatMap { run ->
                run.state.value.awaitingInput
                    .map { run to it }
            }

    val awaitingApproval: List<Pair<WorkflowRun, NodeRun>>
        get() =
            runs
                .filterNot { it.isElsewhere }
                .flatMap { run ->
                    run.state.value.awaitingApproval
                        .map { run to it }
                }

    fun decide(
        node: NodeRun,
        allow: Boolean,
        forRestOfRun: Boolean = false,
    ) {
        val pending = node.pendingPermission ?: return
        settled(node)
        if (allow && forRestOfRun) {
            node.autoAllowed.add(pending.request.toolName)
            node.notice(Strings.Transcript.wontAskAgain(pending.request.toolName))
        }
        pending.decide(allow)
    }

    private fun announceQuestion(node: NodeRun) {
        when {
            node.pendingQuestion != null -> announceInput(node)
            node.isAwaitingApproval -> announceGate(node)
        }
    }

    private fun announceInput(node: NodeRun) {
        val question = node.pendingQuestion ?: return
        val choices = question.choices.take(MAX_NOTIFICATION_ACTIONS)
        ask(
            node = node,
            notification =
                RunNotification(
                    runId = runIdOf(node),
                    title = Strings.Notifications.needsAnAnswer(node.nodeTitle),
                    body = question.question,
                    isFailure = false,
                    key = "node-${node.id}",
                    subtitle = workflowNameOf(node),
                    sound = WAITING_SOUND,
                    thread = runIdOf(node),
                    actions = choices.mapIndexed { index, choice -> NotificationAction("choice-$index", choice) },
                ),
        ) { answer ->
            val index = answer.removePrefix("choice-").toIntOrNull()
            if (index != null && index in choices.indices) answer(node, choices[index]) else activate(node)
        }
    }

    private fun announceGate(node: NodeRun) {
        val asking =
            node.entries
                .filterIsInstance<ConsoleEntry.Prompt>()
                .lastOrNull()
                ?.text
        ask(
            node = node,
            notification =
                RunNotification(
                    runId = runIdOf(node),
                    title = Strings.Notifications.needsApproval(node.nodeTitle),
                    body = asking ?: Strings.Notifications.GATE_FALLBACK_BODY,
                    isFailure = false,
                    key = "node-${node.id}",
                    subtitle = workflowNameOf(node),
                    sound = WAITING_SOUND,
                    thread = runIdOf(node),
                    actions =
                        listOf(
                            NotificationAction("approve", Strings.Notifications.APPROVE),
                            NotificationAction("reject", Strings.Notifications.REJECT),
                        ),
                ),
        ) { answer ->
            when (answer) {
                "approve" -> approve(node, true)
                "reject" -> approve(node, false)
                else -> activate(node)
            }
        }
    }

    private fun nodeBySession(sessionId: String): NodeRun? = runs.firstNotNullOfOrNull { run -> run.nodes.firstOrNull { it.sessionId == sessionId } }

    private fun announcePermission(node: NodeRun) {
        val request = node.pendingPermission?.request ?: return
        ask(
            node = node,
            notification =
                RunNotification(
                    runId = runIdOf(node),
                    title = Strings.Notifications.needsYou(node.nodeTitle),
                    body = Strings.Notifications.permissionBody(request.toolName, request.summary),
                    isFailure = false,
                    key = "node-${node.id}",
                    subtitle = workflowNameOf(node),
                    sound = WAITING_SOUND,
                    thread = runIdOf(node),
                    actions =
                        listOf(
                            NotificationAction("allow", Strings.Notifications.ALLOW),
                            NotificationAction("allow-run", Strings.Notifications.ALLOW_FOR_THIS_RUN),
                            NotificationAction("deny", Strings.Notifications.DENY),
                        ),
                ),
            timeoutSeconds = APPROVAL_DEADLINE_SECONDS,
        ) { answer ->
            when (answer) {
                "allow" -> decide(node, allow = true)
                "allow-run" -> decide(node, allow = true, forRestOfRun = true)
                "deny" -> decide(node, allow = false)
                else -> activate(node)
            }
        }
    }

    fun send(
        node: NodeRun,
        text: String,
    ) {
        val live = node.live ?: return
        node.echoFollowUp(text)
        node.update { copy(status = RunStatus.RUNNING) }
        live.send(text).onFailure { node.notice(Strings.Transcript.couldntSend(it.message), isWarning = true) }
    }

    fun finishInput(node: NodeRun) {
        node.notice(Strings.Transcript.ENDING_SESSION)
        node.live?.endInput()
    }

    fun takeOver(node: NodeRun): Result<Unit> {
        val provider =
            node.agent
                ?: return Result.failure(IllegalStateException(Strings.RunErrors.NO_SESSION_TO_TAKE_OVER))
        if (!provider.capabilities.resumeInTerminal) {
            return Result.failure(
                IllegalStateException(Strings.RunErrors.cannotResumeHeadless(provider.id.label)),
            )
        }
        val sessionId =
            node.sessionId
                ?: return Result.failure(IllegalStateException(Strings.RunErrors.NO_SESSION_ID_YET))
        val cwd =
            node.cwd
                ?: return Result.failure(IllegalStateException(Strings.RunErrors.NO_DIRECTORY_TO_RESUME))

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
                node.notice(Strings.Transcript.takenOver(terminal, resume))
                if (live == null) node.update { copy(status = RunStatus.DETACHED) }
            }.onFailure { node.notice(Strings.Transcript.couldntOpen(terminal, it.message), isWarning = true) }
    }

    fun select(run: WorkflowRun?) {
        _state.update { it.copy(selected = run, selectedNodeId = null) }
        run?.let(::readTranscript)
    }

    fun select(
        run: WorkflowRun,
        node: NodeRun,
    ) {
        _state.update { it.copy(selected = run, selectedNodeId = node.id) }
        readTranscript(run)
    }

    private fun readTranscript(run: WorkflowRun) {
        if (!run.fromArchive || run.transcriptLoaded) return
        scope.launch(Dispatchers.IO) { RunHistory.loadTranscript(run) }
    }

    fun watchArchive() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(ARCHIVE_POLL_MILLIS.milliseconds)
                readArchive()
            }
        }
    }

    internal fun readArchive() {
        val watched = runs.filter { it.isElsewhere }
        val known = runs.mapTo(mutableSetOf()) { it.id }
        val arrived = RunHistory.list(archiveRoot).filterNot { it.id in known }

        _state.update { it.copy(runs = arrived + it.runs) }
        arrived.filterNot { it.isActive }.forEach(::announceElsewhere)
        watched.forEach(::follow)
    }

    private fun follow(run: WorkflowRun) {
        val record = RunHistory.record(run) ?: return
        run.refreshFrom(record, record.restoreAs())
        if (run.isActive) return
        announceElsewhere(run)
        if (run == _state.value.selected) readTranscript(run)
    }

    private fun announceElsewhere(run: WorkflowRun) {
        val failed = run.status == RunStatus.FAILED
        val level = settings.current.notify
        if (!(if (failed) level.notifiesOnFailure else level.notifiesOnSuccess)) return
        if (isForeground()) return
        notifier.post(
            RunNotification(
                runId = run.id,
                title = Strings.Notifications.runSettled(run.workflowName, run.status.label),
                body = run.summary(),
                isFailure = failed,
                key = "run-${run.id}",
                sound = if (failed) FAILURE_SOUND else SUCCESS_SOUND,
                thread = run.id,
            ),
        )
    }

    fun loadHistory() {
        val known = runs.mapTo(mutableSetOf()) { it.id }
        val archived = RunHistory.list(archiveRoot).filterNot { it.id in known }
        if (archived.isNotEmpty()) _state.update { it.copy(runs = it.runs + archived) }
    }

    fun forget(run: WorkflowRun): Result<Unit> {
        if (run.isElsewhere) {
            return Result.failure(IllegalStateException(Strings.RunErrors.stillRunningElsewhere(run.workflowName)))
        }
        val dir =
            run.archiveDir
                ?: return Result.failure(IllegalStateException(Strings.RunErrors.NOT_ON_DISK_YET))
        return runCatching {
            RunArchive.delete(dir)
            remove(run)
        }
    }

    fun reconcile() {
        scope.launch(Dispatchers.IO) {
            val found = SessionReconciler.reconcile(archiveRoot)
            if (found.orphans.isNotEmpty()) _state.update { it.copy(runs = found.orphans + it.runs) }
            loadHistory()

            if (found.orphans.isEmpty()) return@launch
            if (!settings.current.notify.notifiesWhenWaiting) return@launch
            notifier.post(
                RunNotification(
                    runId = found.orphans.first().id,
                    title = Strings.Notifications.ORPHANS_TITLE,
                    body =
                        Strings.Notifications.orphansBody(found.orphans.size),
                    isFailure = false,
                    key = "orphans",
                    sound = WAITING_SOUND,
                ),
            )
        }
    }

    private fun adopt(run: WorkflowRun) {
        _state.update { it.copy(runs = listOf(run) + it.runs) }
        select(run)
    }

    private fun announce(run: WorkflowRun) {
        run.nodes.forEach { asked.remove(it.id)?.cancel() }
        val failed = run.status == RunStatus.FAILED
        val level = settings.current.notify
        if (!(if (failed) level.notifiesOnFailure else level.notifiesOnSuccess)) return
        if (isForeground()) return
        notifier.post(
            RunNotification(
                runId = run.id,
                title = Strings.Notifications.runSettled(run.workflowName, run.status.label),
                body = run.summary(),
                isFailure = failed,
                key = "run-${run.id}",
                sound = if (failed) FAILURE_SOUND else SUCCESS_SOUND,
                thread = run.id,
            ),
        )
    }

    private fun ask(
        node: NodeRun,
        notification: RunNotification,
        timeoutSeconds: Long = WAITING_DEADLINE_SECONDS,
        onAnswer: (String) -> Unit,
    ) {
        if (!settings.current.notify.notifiesWhenWaiting || isForeground()) return
        asked.remove(node.id)?.cancel()
        asked[node.id] = notifier.ask(notification, timeoutSeconds) { answer -> scope.launch { onAnswer(answer) } }
    }

    private fun settled(node: NodeRun) {
        asked.remove(node.id)?.cancel()
    }

    private fun activate(node: NodeRun) {
        onActivate(runIdOf(node))
    }

    private fun runIdOf(node: NodeRun): String = runs.firstOrNull { run -> run.nodes.any { it.id == node.id } }?.id.orEmpty()

    private fun workflowNameOf(node: NodeRun): String = runs.firstOrNull { run -> run.nodes.any { it.id == node.id } }?.workflowName.orEmpty()
}
