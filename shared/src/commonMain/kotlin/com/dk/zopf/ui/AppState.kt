package com.dk.zopf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.providerFor
import com.dk.zopf.runtime.AgentProviders
import com.dk.zopf.runtime.Browser
import com.dk.zopf.runtime.ConnectorScaffold
import com.dk.zopf.runtime.Finder
import com.dk.zopf.runtime.MacNotifier
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.RunRegistry
import com.dk.zopf.runtime.TerminalLauncher
import com.dk.zopf.runtime.UpdateCheck
import com.dk.zopf.runtime.WorkflowRun
import com.dk.zopf.runtime.errors
import com.dk.zopf.runtime.issues
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.BrokenConnector
import com.dk.zopf.store.Connector
import com.dk.zopf.store.ConnectorListing
import com.dk.zopf.store.ConnectorStore
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.Log
import com.dk.zopf.store.OpenWorkspace
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.SettingsStore
import com.dk.zopf.store.WindowFrame
import com.dk.zopf.store.WorkflowListing
import com.dk.zopf.store.WorkflowStore
import com.dk.zopf.store.WorkspaceRegistry
import com.dk.zopf.ui.editor.EditorCommands
import com.dk.zopf.ui.editor.EditorState
import com.dk.zopf.ui.workspace.chooseDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.name

enum class Screen(
    val label: String,
) {
    WORKFLOWS("Workflows"),
    RUNS("Runs"),
    CONNECTORS("Connectors"),
    SETTINGS("Settings"),
}

enum class DialogRequest { NEW_WORKFLOW, NEW_CONNECTOR }

class AppState(
    val registry: WorkspaceRegistry = WorkspaceRegistry(),
) {
    var workspaces by mutableStateOf<List<OpenWorkspace>>(emptyList())
        private set

    var activeWorkspace by mutableStateOf<OpenWorkspace?>(null)
        private set

    var listing by mutableStateOf(WorkflowListing(emptyList(), emptyList()))
        private set

    var connectors by mutableStateOf(ConnectorListing())
        private set

    var selectedWorkflow by mutableStateOf<Workflow?>(null)
        private set

    var editing by mutableStateOf<EditorState?>(null)
        private set

    var editorCommands by mutableStateOf<EditorCommands?>(null)

    var dialogRequest by mutableStateOf<DialogRequest?>(null)

    var message by mutableStateOf<String?>(null)

    var windowFocused by mutableStateOf(true)

    var onActivateRun: (String) -> Unit = {}

    var update by mutableStateOf<Release?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsStore = SettingsStore()

    private val settingsRead = settingsStore.read()

    val settings = LiveSettings(settingsRead.getOrElse { AppSettings() }, settingsStore)

    val runs =
        RunRegistry(
            scope,
            settings,
            archiveRoot = AppPaths.runsDir,
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

    fun start() {
        Log.info("starting · ${settings.current.concurrency} at once · ${settings.current.theme.label}")
        settingsRead.exceptionOrNull()?.let { message = "${it.message}. Using the defaults." }
        registry.load()
        syncFromRegistry()

        runs.reconcile()
        runs.watchArchive()
        checkAgent()
        checkUpdate()
        housekeep()

        activeWorkspace?.workspace?.takeIf { it.config.isFromTheFuture }?.let {
            message = "${it.name} was written by a newer zopf. Some of it may not mean what it says here."
        }
    }

    private fun checkAgent() {
        val fallback =
            activeWorkspace
                ?.workspace
                ?.config
                ?.defaults
                ?.provider ?: settings.current.defaultProvider
        val wanted =
            listing.workflows
                .flatMap { workflow ->
                    workflow.nodes
                        .filter { it.type == NodeType.AGENT }
                        .map { workflow.providerFor(it, fallback) }
                }.plus(fallback)
                .distinct()

        scope.launch(Dispatchers.IO) {
            val missing = wanted.filterNot(AgentProviders::isInstalled).map(AgentProviders::of)
            if (missing.isEmpty()) return@launch
            Log.warn("${missing.joinToString { it.executable }} isn't on the login shell's PATH")
            message =
                "Can't find ${missing.joinToString(" or ") { it.executable }}. Install " +
                missing.joinToString(" or ") { it.id.label } +
                " and sign in, or the nodes that use ${if (missing.size == 1) "it" else "them"} will fail."
        }
    }

    private fun checkUpdate() {
        if (!settings.current.checkForUpdates) return
        scope.launch(Dispatchers.IO) {
            val check = UpdateCheck()
            val release = check.refresh() ?: return@launch
            update = release
            Log.info("zopf ${release.version} is out")
            if (check.announceOnce(release)) message = "zopf ${release.version} is out. Settings has the link."
        }
    }

    fun openRelease() {
        val release = update ?: return
        Browser.open(release.url).onFailure { message = it.message }
    }

    private fun housekeep() {
        scope.launch(Dispatchers.IO) {
            val keep = settings.current.keepRuns
            if (keep <= 0) return@launch
            val gone = RunArchive.prune(keep = keep)
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
            message = "Couldn't open ${dir.fileName} as a workspace"
            return
        }
        syncFromRegistry()
        message = "Opened ${added.displayName}"
    }

    fun forgetWorkspace(path: Path) {
        registry.remove(path)
        syncFromRegistry()
    }

    fun openWorkspace() {
        chooseDirectory("Open or create a zopf workspace")?.let(::addWorkspace)
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
                "${target.name} can't run yet. ${blocking.first().message}" +
                if (rest > 0) " (and ${if (rest == 1) "1 more thing" else "$rest more things"} to fix)" else ""
            return
        }
        runs
            .startWorkflow(activeWorkspace?.workspace, target)
            .onSuccess {
                showRunPanel = true
                if (editing?.workflow?.name != target.name) message = "Running ${target.name}"
            }.onFailure { message = it.message }
    }

    fun refreshConnectors() {
        connectors = connectorStore.list()
    }

    fun createConnector(
        name: String,
        shared: Boolean = false,
    ) {
        val workspace = activeWorkspace?.workspace
        if (workspace == null) {
            message = "Open a workspace first, then add a connector"
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
        TerminalLauncher
            .openIn(dir, terminal, prompt)
            .onSuccess {
                message = "Opened $name in $terminal. Refresh when you're done."
            }.onFailure { message = "Couldn't open $terminal: ${it.message}" }
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
            message = "No workflow called $name in this workspace"
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

        if (activeWorkspace?.path != was) editing = null
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
        "Deleted $gone archived runs, keeping the last $keep. See Settings › History."
    }

internal fun RunRegistry.runForEditor(workflowName: String): WorkflowRun? = selectedRun?.takeIf { it.workflowName == workflowName }
