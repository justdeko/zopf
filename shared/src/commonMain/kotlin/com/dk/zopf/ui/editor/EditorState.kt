package com.dk.zopf.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.EdgeTrigger
import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeRefs
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Position
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.addNode
import com.dk.zopf.model.duplicateNode
import com.dk.zopf.model.positions
import com.dk.zopf.model.removeNode
import com.dk.zopf.model.renameNode
import com.dk.zopf.model.replaceNode
import com.dk.zopf.model.withDefaultsFrom
import com.dk.zopf.model.withPositions
import com.dk.zopf.runtime.AgentProviders
import com.dk.zopf.runtime.WorkflowLookups
import com.dk.zopf.runtime.issues
import com.dk.zopf.runtime.promptFiles
import com.dk.zopf.runtime.workflowLookups
import com.dk.zopf.store.Connector
import com.dk.zopf.store.DiscoveredSkill
import com.dk.zopf.store.FileStamp
import com.dk.zopf.store.WorkflowStore
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.decodeWorkflow
import com.dk.zopf.store.encodeWorkflow
import com.dk.zopf.store.fileStamp
import com.dk.zopf.store.isSkillDir
import java.nio.file.Path
import kotlin.io.path.name

@Stable
class EditorState(
    initial: Workflow,
    private val workspace: Workspace?,
    private val connectorsProvider: () -> List<Connector> = { emptyList() },
    private val onRefreshConnectors: () -> Unit = {},
    val defaultProvider: AgentProviderId = AgentProviderId.CLAUDE,
    val defaultModel: String? = null,
    val workspaceDefaults: NodeDefaults = NodeDefaults(),
    private val executableExists: (AgentProviderId) -> Boolean = AgentProviders::isInstalled,
    private val onSave: (Workflow) -> Unit,
) {
    val resolvedWorkflow: Workflow get() = workflow.withDefaultsFrom(workspaceDefaults)

    val fallbackProvider: AgentProviderId get() = workspaceDefaults.provider ?: defaultProvider

    var workflow by mutableStateOf(initial)
        private set

    private var saved by mutableStateOf(initial)

    var selectedNodeId by mutableStateOf<String?>(null)
        private set

    var connectFrom by mutableStateOf<String?>(null)
        private set

    var connectDrag by mutableStateOf<ConnectDrag?>(null)
        private set

    var connectCandidate by mutableStateOf<String?>(null)
        private set

    val connectTarget: String? get() = connectDrag?.over ?: connectCandidate

    var message by mutableStateOf<String?>(null)

    private var canvasPositions by mutableStateOf<Map<String, Position>?>(null)

    val isDirty: Boolean
        get() = workflow != saved || canvasPositions?.let { it != saved.positions() } == true

    val selectedNode: WorkflowNode? get() = selectedNodeId?.let { workflow.node(it) }

    val skills: List<DiscoveredSkill> get() = lookups.skills.orEmpty()

    private var lookups by mutableStateOf(WorkflowLookups())

    private var promptStamps: Map<String, FileStamp?> = emptyMap()

    private val store: WorkflowStore? get() = workspace?.let(::WorkflowStore)

    private var stamp: FileStamp? = null

    var changedOnDisk by mutableStateOf<Workflow?>(null)
        private set

    init {
        refreshLookups()
        stamp = currentStamp()
    }

    private fun currentStamp(): FileStamp? = store?.let { fileStamp(it.fileFor(saved.name)) }

    private fun currentPromptStamps(): Map<String, FileStamp?> {
        val ws = workspace ?: return emptyMap()
        return workflow.promptFiles().associateWith { fileStamp(ws.resolvePath(it)) }
    }

    fun checkFileOnDisk() {
        if (currentPromptStamps() != promptStamps) refreshLookups()

        val store = store ?: return
        val current = fileStamp(store.fileFor(saved.name)) ?: return
        if (current == stamp) return
        stamp = current

        val onDisk = runCatching { store.load(saved.name) }.getOrNull() ?: return
        if (onDisk == saved) {
            changedOnDisk = null
            return
        }
        if (isDirty) changedOnDisk = onDisk else adopt(onDisk)
    }

    fun adoptChangeOnDisk() {
        adopt(changedOnDisk ?: return)
    }

    fun keepMineOverChangeOnDisk() {
        changedOnDisk = null
    }

    private fun adopt(onDisk: Workflow) {
        workflow = onDisk
        saved = onDisk
        changedOnDisk = null
        canvasPositions = null
        selectedNodeId = selectedNodeId?.takeIf { onDisk.node(it) != null }
        clearConnect()
        refreshLookups()
    }

    val issues: List<WorkflowIssue> get() = workflow.issues(lookups)

    val connectors: List<Connector> get() = connectorsProvider()

    fun connector(name: String): Connector? = if (name.isBlank()) null else connectors.firstOrNull { it.name == name }

    fun refreshConnectors() = onRefreshConnectors()

    fun refreshLookups() {
        lookups =
            workflowLookups(
                workspace = workspace,
                workflow = workflow,
                defaultProvider = fallbackProvider,
                executableExists = executableExists,
                connector = { name -> connectors.firstOrNull { it.name == name }?.manifest },
            )
        promptStamps = currentPromptStamps()
    }

    fun addSkillDirectory(
        path: Path,
        nodeId: String?,
    ): Boolean {
        if (!isSkillDir(path)) {
            message = "${path.fileName} has no SKILL.md, so the CLI won't load it as a skill"
            return false
        }
        val raw = path.toString()
        val name = path.name
        workflow =
            workflow.copy(skills = (workflow.skills + raw).distinct()).let { updated ->
                val node = nodeId?.let(updated::node) ?: return@let updated
                updated.replaceNode(node.copy(skills = (node.skills + name).distinct()))
            }
        refreshLookups()
        return true
    }

    fun issuesFor(nodeId: String): List<WorkflowIssue> = issues.filter { it.nodeId == nodeId }

    val repoIds: List<String>
        get() = workspace?.availableRepoIds(workflow) ?: workflow.repos.map { it.id }

    fun edit(block: (Workflow) -> Workflow) {
        workflow = block(workflow)
    }

    fun tryEdit(block: (Workflow) -> Result<Workflow>) {
        block(workflow)
            .onSuccess { workflow = it }
            .onFailure { message = it.message }
    }

    fun select(nodeId: String?) {
        selectedNodeId = nodeId

        clearConnect()
    }

    fun addNode(
        type: NodeType,
        position: Position? = null,
    ) {
        val (updated, node) = workflow.addNode(type, position)
        workflow = updated
        selectedNodeId = node.id
        clearConnect()
    }

    fun updateNode(node: WorkflowNode) {
        workflow = workflow.replaceNode(node)
    }

    fun duplicateNode(id: String) {
        val (updated, clone) = workflow.duplicateNode(id) ?: return
        workflow = updated
        selectedNodeId = clone.id
        clearConnect()
    }

    fun renameNode(
        from: String,
        to: String,
    ) {
        workflow
            .renameNode(from, to)
            .onSuccess {
                workflow = it
                if (selectedNodeId == from) selectedNodeId = to
                if (connectFrom == from) connectFrom = to
                warnAboutPromptFileReferences(from, to)
            }.onFailure { message = it.message }
    }

    private fun warnAboutPromptFileReferences(
        from: String,
        to: String,
    ) {
        val stale = lookups.promptTexts.filterValues { from in NodeRefs.referencedNodeIds(it) }.keys
        if (stale.isEmpty()) return
        message = "Renamed to $to. ${stale.joinToString()} still reads \${$from…}. " +
            "zopf doesn't edit prompt files, so fix that one by hand."
    }

    fun removeNode(id: String) {
        workflow = workflow.removeNode(id)
        if (selectedNodeId == id) selectedNodeId = null
        if (connectFrom == id) clearConnect()
    }

    fun startConnecting(from: String) {
        if (connectFrom == from) clearConnect() else connectFrom = from
    }

    fun cancelConnecting() {
        clearConnect()
    }

    private fun clearConnect() {
        connectFrom = null
        connectDrag = null
        connectCandidate = null
    }

    fun stepConnectCandidate(targets: List<String>) {
        if (targets.isEmpty()) {
            connectCandidate = null
            return
        }
        val at = targets.indexOf(connectCandidate)
        connectCandidate = targets[(at + 1) % targets.size]
    }

    fun completeConnectionToCandidate(): Boolean = completeConnection(connectCandidate ?: return false)

    fun beginConnectDrag(
        from: String,
        start: DpOffset,
    ) {
        connectFrom = from
        connectDrag = ConnectDrag(from, start)
    }

    fun moveConnectDrag(
        delta: DpOffset,
        over: String?,
    ) {
        connectDrag = connectDrag?.copy(delta = delta, over = over)
    }

    fun finishConnectDrag(): Boolean {
        val over = connectDrag?.over
        connectDrag = null
        if (over == null) {
            connectFrom = null
            return false
        }
        return completeConnection(over)
    }

    fun cancelConnectDrag() {
        clearConnect()
    }

    fun completeConnection(to: String): Boolean {
        val from = connectFrom ?: return false
        clearConnect()
        if (from == to) return true
        connectNodes(from, to)
        return true
    }

    fun connectNodes(
        from: String,
        to: String,
    ) {
        workflow
            .connect(from, to)
            .onSuccess {
                workflow = it
                selectedNodeId = to
            }.onFailure { message = it.message }
    }

    fun addConnectedNode(
        from: String,
        type: NodeType,
        position: Position?,
    ) {
        val (updated, node) = workflow.addNode(type, position)
        workflow = updated
        clearConnect()
        connectNodes(from, node.id)
        selectedNodeId = node.id
    }

    fun reportCanvasPositions(positions: Map<String, Position>?) {
        canvasPositions = positions
    }

    val sourceText: String
        get() = encodeWorkflow(canvasPositions?.let { workflow.withPositions(it) } ?: workflow)

    fun applySource(text: String): Result<Unit> =
        runCatching {
            val parsed = decodeWorkflow(text)
            if (parsed.name != saved.name) {
                error("Rename on the Workflows screen to move the file.")
            }
            workflow = parsed
            canvasPositions = null
            selectedNodeId = selectedNodeId?.takeIf { parsed.node(it) != null }
            clearConnect()
            refreshLookups()
        }

    fun disconnect(
        from: String,
        to: String,
    ) {
        workflow = workflow.disconnect(from, to)
    }

    fun setEdgeTrigger(
        from: String,
        to: String,
        on: EdgeTrigger,
    ) {
        workflow = workflow.setEdgeTrigger(from, to, on)
    }

    fun setEdgeCondition(
        from: String,
        to: String,
        condition: Boolean?,
    ) {
        workflow = workflow.setEdgeCondition(from, to, condition)
    }

    fun save(positions: Map<String, Position>?) {
        val toWrite = positions?.let { workflow.withPositions(it) } ?: workflow
        onSave(toWrite)
        workflow = toWrite
        saved = toWrite
        changedOnDisk = null
        stamp = currentStamp()
    }

    fun revert() {
        workflow = saved
        selectedNodeId = null
        clearConnect()
    }
}
