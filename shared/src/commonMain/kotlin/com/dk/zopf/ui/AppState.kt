package com.dk.zopf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.capabilities
import com.dk.zopf.model.providerFor
import com.dk.zopf.runtime.NodeExecutor
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.UpdateCheck
import com.dk.zopf.runtime.agent.AgentProvider
import com.dk.zopf.runtime.agent.AgentProviders
import com.dk.zopf.runtime.agent.WorkflowAuthor
import com.dk.zopf.runtime.errors
import com.dk.zopf.runtime.exec.ConnectorScaffold
import com.dk.zopf.runtime.issues
import com.dk.zopf.runtime.macos.AppUpdate
import com.dk.zopf.runtime.macos.Browser
import com.dk.zopf.runtime.macos.Finder
import com.dk.zopf.runtime.macos.MacNotifier
import com.dk.zopf.runtime.macos.TerminalLauncher
import com.dk.zopf.runtime.macos.UpdateInstall
import com.dk.zopf.runtime.run.RunRegistry
import com.dk.zopf.runtime.run.RunStatus
import com.dk.zopf.runtime.run.WorkflowRun
import com.dk.zopf.runtime.run.WorkflowRunState
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.Log
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.SettingsStore
import com.dk.zopf.store.WindowFrame
import com.dk.zopf.store.workflow.WorkflowListing
import com.dk.zopf.store.workflow.WorkflowStore
import com.dk.zopf.store.workflow.slugify
import com.dk.zopf.store.workspace.BrokenConnector
import com.dk.zopf.store.workspace.Connector
import com.dk.zopf.store.workspace.ConnectorListing
import com.dk.zopf.store.workspace.ConnectorStore
import com.dk.zopf.store.workspace.OpenWorkspace
import com.dk.zopf.store.workspace.Workspace
import com.dk.zopf.store.workspace.WorkspaceRegistry
import com.dk.zopf.ui.editor.EditorCommands
import com.dk.zopf.ui.editor.EditorState
import com.dk.zopf.ui.workspace.chooseDirectory
import com.dk.zopf.util.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

enum class Screen(
    val label: String,
) {
    WORKFLOWS(Strings.Nav.WORKFLOWS),
    RUNS(Strings.Nav.RUNS),
    CONNECTORS(Strings.Nav.CONNECTORS),
    SETTINGS(Strings.Nav.SETTINGS),
}

enum class DialogRequest { NEW_WORKFLOW, NEW_CONNECTOR }

private const val DRAFT_ATTEMPTS = 2

class AppState(
    val registry: WorkspaceRegistry = WorkspaceRegistry(),
    private val archiveRoot: Path = AppPaths.runsDir,
    executor: NodeExecutor? = null,
) {
    var workspaces by mutableStateOf<List<OpenWorkspace>>(emptyList())
        private set

    var activeWorkspace by mutableStateOf<OpenWorkspace?>(null)
        private set

    var listing by mutableStateOf(WorkflowListing(emptyList(), emptyList()))
        private set

    var connectors by mutableStateOf(ConnectorListing())
        private set

    private var installedAgents by mutableStateOf<Set<AgentProviderId>?>(null)

    var selectedWorkflow by mutableStateOf<Workflow?>(null)
        private set

    var editing by mutableStateOf<EditorState?>(null)
        private set

    var editorCommands by mutableStateOf<EditorCommands?>(null)

    var drafting by mutableStateOf<String?>(null)
        private set

    var dialogRequest by mutableStateOf<DialogRequest?>(null)

    var message by mutableStateOf<String?>(null)

    var windowFocused by mutableStateOf(true)

    var onActivateRun: (String) -> Unit = {}

    var onQuit: () -> Unit = {}

    var update by mutableStateOf<Release?>(null)
        private set

    var updateInstall by mutableStateOf<UpdateInstall>(UpdateInstall.Idle)
        private set

    private val appUpdate = AppUpdate()

    private var reopenAfterUpdate = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsStore = SettingsStore()

    private val settingsRead = settingsStore.read()

    val settings = LiveSettings(settingsRead.getOrElse { AppSettings() }, settingsStore)

    val runs =
        RunRegistry(
            scope,
            settings,
            executor = executor,
            archiveRoot = archiveRoot,
            notifier = MacNotifier(),
            isForeground = { windowFocused },
            onActivate = { runId -> onActivateRun(runId) },
        )

    var showRunPanel by mutableStateOf(false)

    var screen by mutableStateOf(Screen.WORKFLOWS)

    private val store: WorkflowStore?
        get() = activeWorkspace?.workspace?.let { WorkflowStore(it) }

    private val connectorStore: ConnectorStore
        get() = ConnectorStore(activeWorkspace?.workspace)

    private fun defaultProvider(from: AppSettings = settings.current): AgentProviderId =
        activeWorkspace
            ?.workspace
            ?.config
            ?.defaults
            ?.provider ?: from.defaultProvider

    fun connectorAgentLabel(from: AppSettings): String = authoringAgent(defaultProvider(from), ::knownInstalled).id.label

    private fun knownInstalled(id: AgentProviderId): Boolean = installedAgents?.contains(id) ?: true

    private fun refreshInstalledAgents() {
        installedAgents = AgentProviderId.entries.filterTo(mutableSetOf(), AgentProviders::isInstalled)
    }

    fun start() {
        Log.info("starting · ${settings.current.concurrency} at once · ${settings.current.theme.label}")
        settingsRead.exceptionOrNull()?.let { message = Strings.Workspaces.settingsFellBack(it.message) }
        registry.load()
        syncFromRegistry()

        runs.reconcile()
        runs.watchArchive()
        scope.launch { appUpdate.state.collect { updateInstall = it } }
        checkAgent()
        checkUpdate()
        housekeep()

        activeWorkspace?.workspace?.takeIf { it.config.isFromTheFuture }?.let {
            message = Strings.Workspaces.fromTheFuture(it.name)
        }
        activeWorkspace?.workspace?.configProblem?.let { message = it }
    }

    private fun checkAgent() {
        val fallback = defaultProvider()
        val wanted =
            listing.workflows
                .flatMap { workflow ->
                    workflow.nodes
                        .filter { it.type == NodeType.AGENT }
                        .map { workflow.providerFor(it, fallback) }
                }.plus(fallback)
                .distinct()

        scope.launch(Dispatchers.IO) {
            refreshInstalledAgents()
            val missing = wanted.filterNot(::knownInstalled).map(AgentProviders::of)
            if (missing.isEmpty()) return@launch
            Log.warn("${missing.joinToString { it.executable }} isn't on the login shell's PATH")
            message =
                Strings.RunErrors.missingExecutables(
                    missing.joinToString(" or ") { it.executable },
                    missing.joinToString(" or ") { it.id.label },
                    missing.size,
                )
        }
    }

    private fun checkUpdate() {
        if (!settings.current.checkForUpdates) return
        scope.launch(Dispatchers.IO) {
            val check = UpdateCheck()
            val release = check.refresh() ?: return@launch
            update = release
            Log.info("zopf ${release.version} is out")
            if (settings.current.autoUpdate && updateBlocker == null) {
                install(release)
                return@launch
            }
            if (check.announceOnce(release)) message = Strings.Updates.isOut("${release.version}")
        }
    }

    val updateBlocker: String? get() = appUpdate.blocker()

    fun installUpdate() {
        val release = update ?: return
        if (updateInstall is UpdateInstall.Downloading || updateInstall is UpdateInstall.Verifying) return
        scope.launch(Dispatchers.IO) { install(release) }
    }

    private fun install(release: Release) {
        appUpdate
            .install(release)
            .onSuccess { message = Strings.Updates.readyToRestart("${release.version}") }
            .onFailure { message = it.message }
    }

    fun restartForUpdate() {
        reopenAfterUpdate = true
        onQuit()
    }

    fun finishUpdate() {
        if (updateInstall !is UpdateInstall.Ready) return
        appUpdate.swap(reopenAfterUpdate).onFailure { Log.warn("the update wouldn't swap in: ${it.message}") }
    }

    fun openRelease() {
        val release = update ?: return
        Browser.open(release.url).onFailure { message = it.message }
    }

    private fun housekeep() {
        scope.launch(Dispatchers.IO) {
            val keep = settings.current.keepRuns
            if (keep <= 0) return@launch
            val gone = RunArchive.prune(root = archiveRoot, keep = keep)
            if (gone.isEmpty()) return@launch

            gone.forEach { RunArchive.delete(it) }
            Log.info("pruned ${gone.size} archived run(s), keeping $keep")
            prunedRunsMessage(gone.size, keep)?.let { message = it }
        }
    }

    fun selectWorkspace(path: Path) {
        registry.setActive(path)
        syncFromRegistry()
    }

    fun addWorkspace(dir: Path) {
        val added = registry.add(dir)
        if (added == null) {
            message = Strings.Workspaces.couldntOpen("${dir.fileName}")
            return
        }
        syncFromRegistry()
        message = Strings.Workspaces.opened(added.displayName)
    }

    fun forgetWorkspace(path: Path) {
        registry.remove(path)
        syncFromRegistry()
    }

    fun openWorkspace() {
        chooseDirectory(Strings.Workspaces.CHOOSE_TITLE)?.let(::addWorkspace)
    }

    fun refreshWorkspaces() {
        registry.refresh()
        syncFromRegistry()
    }

    fun refreshWorkflows() {
        listing = store?.list() ?: WorkflowListing(emptyList(), emptyList())

        selectedWorkflow =
            selectedWorkflow?.let { selected ->
                listing.workflows.firstOrNull { it.name == selected.name }
            }
    }

    fun openEditor(workflow: Workflow) {
        val workspace = activeWorkspace?.workspace ?: return
        selectedWorkflow = workflow
        showRunPanel = runs.runForEditor(workflow.name)?.isActive == true

        refreshConnectors()
        editing =
            EditorState(
                initial = workflow,
                workspace = workspace,
                connectorsProvider = { connectors.connectors },
                onRefreshConnectors = ::refreshConnectors,
                defaultProvider = settings.current.defaultProvider,
                defaultModel = settings.current.defaultModel,
                workspaceDefaults = workspace.config.defaults,
                executableExists = ::knownInstalled,
            ) { edited ->
                WorkflowStore(workspace).save(edited)
                refreshWorkflows()
            }
    }

    fun closeEditor() {
        editing = null
        refreshWorkflows()
    }

    fun createWorkflow(
        name: String,
        template: String? = null,
    ) {
        val store = store ?: return
        store
            .create(name, template)
            .onSuccess {
                refreshWorkflows()
                selectedWorkflow = it
            }.onFailure { message = it.message }
    }

    fun describeWorkflow(
        name: String,
        description: String,
    ) {
        val workspace = activeWorkspace?.workspace
        if (workspace == null) {
            message = Strings.Workspaces.OPEN_FIRST
            return
        }
        val slug = slugify(name)
        when {
            slug.isBlank() -> message = Strings.Workflows.NAME_FIRST
            description.isBlank() -> message = Strings.Workflows.DESCRIPTION_FIRST
            WorkflowStore(workspace).fileFor(slug).exists() -> message = Strings.RunErrors.workflowExists(slug)
            drafting != null -> message = Strings.Workflows.alreadyDrafting("$drafting")
            else -> {
                drafting = slug
                scope.launch {
                    try {
                        draft(workspace, slug, description)
                    } finally {
                        drafting = null
                    }
                }
            }
        }
    }

    private suspend fun draft(
        workspace: Workspace,
        slug: String,
        description: String,
    ) {
        var prompt = WorkflowAuthor.createPrompt(slug, description, workspace)
        repeat(DRAFT_ATTEMPTS) { attempt ->
            val run =
                runs
                    .startWorkflow(workspace, WorkflowAuthor.workflowFor(slug, prompt, workspace))
                    .getOrElse {
                        message = it.message
                        return
                    }
            screen = Screen.RUNS
            run.job?.join()
            if (run.status != RunStatus.SUCCEEDED) {
                message = Strings.Workflows.draftStopped(slug)
                return
            }

            val answer =
                run.nodes
                    .firstOrNull()
                    ?.output()
                    ?.result
                    .orEmpty()
            val drafted = WorkflowAuthor.draftFrom(answer, slug)
            val problems =
                drafted.fold(
                    onSuccess = { it.issues(workspace, defaultProvider()).errors().map { issue -> issue.message } },
                    onFailure = { listOf(it.message.orEmpty()) },
                )
            if (problems.isEmpty() || attempt == DRAFT_ATTEMPTS - 1) {
                settle(workspace, slug, drafted.getOrNull(), problems)
                return
            }
            prompt = WorkflowAuthor.repairPrompt(problems, WorkflowAuthor.yamlIn(answer).orEmpty())
        }
    }

    private fun settle(
        workspace: Workspace,
        slug: String,
        drafted: Workflow?,
        problems: List<String>,
    ) {
        if (drafted == null) {
            message = Strings.Workflows.draftNotAWorkflow(slug)
            return
        }
        WorkflowStore(workspace).save(drafted)
        refreshWorkflows()
        openEditor(drafted)
        message =
            when (val first = problems.firstOrNull()) {
                null -> Strings.Workflows.drafted(slug)
                else -> Strings.Workflows.draftedWithIssue(slug, first)
            }
    }

    fun renameWorkflow(
        workflow: Workflow,
        newName: String,
    ) {
        val store = store ?: return
        store
            .rename(workflow, newName)
            .onSuccess {
                refreshWorkflows()
                selectedWorkflow = it
            }.onFailure { message = it.message }
    }

    fun deleteWorkflow(workflow: Workflow) {
        store?.delete(workflow.name)
        if (selectedWorkflow?.name == workflow.name) selectedWorkflow = null
        if (editing?.workflow?.name == workflow.name) editing = null
        refreshWorkflows()
    }

    fun runNode(node: WorkflowNode) {
        val workflow = editing?.workflow ?: selectedWorkflow ?: return
        runs
            .startNode(activeWorkspace?.workspace, workflow, node)
            .onSuccess { showRunPanel = true }
            .onFailure { message = it.message }
    }

    val runnableWorkflow: Workflow?
        get() = (editing?.workflow ?: selectedWorkflow)?.takeIf { it.nodes.isNotEmpty() }

    private fun blockers(workflow: Workflow): List<WorkflowIssue> {
        val editor = editing?.takeIf { it.workflow.name == workflow.name }
        if (editor == null) {
            return workflow.issues(activeWorkspace?.workspace, settings.current.defaultProvider).errors()
        }
        refreshConnectors()
        editor.refreshLookups()
        return editor.issues.errors()
    }

    fun runWorkflow(workflow: Workflow? = null) {
        val target = workflow ?: editing?.workflow ?: selectedWorkflow ?: return
        val blocking = blockers(target)
        if (blocking.isNotEmpty()) {
            val rest = blocking.size - 1
            message =
                Strings.Workflows.notRunnable(target.name, blocking.first().message) +
                if (rest > 0) Strings.Workflows.andMoreToFix(rest) else ""
            return
        }
        runs
            .startWorkflow(activeWorkspace?.workspace, target)
            .onSuccess {
                showRunPanel = true
                if (editing?.workflow?.name != target.name) message = Strings.Workflows.running(target.name)
            }.onFailure { message = it.message }
    }

    fun refreshConnectors() {
        connectors = connectorStore.list()
        scope.launch(Dispatchers.IO) { refreshInstalledAgents() }
    }

    fun createConnector(
        name: String,
        shared: Boolean = false,
    ) {
        val workspace = activeWorkspace?.workspace
        if (workspace == null) {
            message = Strings.Workspaces.OPEN_FIRST
            return
        }
        ConnectorStore(workspace)
            .create(name, shared)
            .onFailure { message = it.message }
            .onSuccess { dir ->
                refreshConnectors()
                handOff(dir.name, dir, ConnectorScaffold.createPrompt(dir.name))
            }
    }

    fun changeConnector(connector: Connector) {
        handOff(connector.name, connector.dir)
    }

    fun fixConnector(broken: BrokenConnector) {
        handOff(broken.name, broken.dir, ConnectorScaffold.fixPrompt(broken.name, broken.message))
    }

    private fun handOff(
        name: String,
        dir: Path,
        prompt: String? = null,
    ) {
        val terminal = runs.terminalApp
        val default = defaultProvider()
        scope.launch(Dispatchers.IO) {
            refreshInstalledAgents()
            val agent = authoringAgent(default, ::knownInstalled)
            if (!knownInstalled(agent.id)) {
                message =
                    Strings.Connectors.missingAgent(agent.executable, agent.id.label, name)
                return@launch
            }
            TerminalLauncher
                .openIn(agent, dir, terminal, prompt)
                .onSuccess {
                    message = Strings.Connectors.openedIn(name, terminal)
                }.onFailure { message = Strings.RunErrors.couldntOpenTerminal(terminal, it.message) }
        }
    }

    fun reveal(path: Path) {
        Finder.reveal(path).onFailure { message = it.message }
    }

    fun revealCurrentWorkflow() {
        val workflow = editing?.workflow ?: selectedWorkflow ?: return
        store?.fileFor(workflow.name)?.let(::reveal)
    }

    fun deleteConnector(dir: Path) {
        dir.toFile().deleteRecursively()
        refreshConnectors()
    }

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        settings.update(change).onFailure {
            message = "Couldn't save settings: ${it.message}"
        }
    }

    fun runWorkflowNamed(name: String) {
        val workflow = listing.workflows.firstOrNull { it.name == name }
        if (workflow == null) {
            message = Strings.Workflows.notFound(name)
            return
        }
        runWorkflow(workflow)
    }

    fun rememberWindow(frame: WindowFrame?) {
        frame?.let { settings.update { current -> current.copy(window = it) } }
    }

    fun shutdown() {
        runs.shutdown()
        scope.cancel()
    }

    private fun syncFromRegistry() {
        val was = activeWorkspace?.path
        workspaces = registry.workspaces.value
        activeWorkspace = registry.active.value

        if (activeWorkspace?.path != was) {
            editing = null
            activeWorkspace?.workspace?.configProblem?.let { message = it }
        }
        refreshWorkflows()
        refreshConnectors()
    }
}

internal fun prunedRunsMessage(
    gone: Int,
    keep: Int,
): String? =
    if (gone <= keep) {
        null
    } else {
        Strings.Settings.prunedRuns(gone, keep)
    }

internal fun RunRegistry.runForEditor(workflowName: String): WorkflowRun? = selectedRun?.takeIf { it.workflowName == workflowName }

internal fun WorkflowRunState?.onCanvas(panelOpen: Boolean): WorkflowRunState? = this?.takeIf { panelOpen || it.isActive }

internal fun authoringAgent(
    default: AgentProviderId,
    isInstalled: (AgentProviderId) -> Boolean = AgentProviders::isInstalled,
): AgentProvider =
    AgentProviders.all
        .filter { it.capabilities.authorsInTerminal }
        .sortedByDescending { it.id == default }
        .firstOrNull { isInstalled(it.id) }
        ?: AgentProviders.of(default.takeIf { it.capabilities.authorsInTerminal } ?: AgentProviderId.CLAUDE)
