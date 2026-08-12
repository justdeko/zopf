package com.dk.zopf.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.dk.zopf.model.validate
import com.dk.zopf.model.withDefaultsFrom
import com.dk.zopf.model.withPositions
import com.dk.zopf.runtime.AgentProviders
import com.dk.zopf.store.Connector
import com.dk.zopf.store.DiscoveredSkill
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.availableSkills
import com.dk.zopf.store.isSkillDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

@Stable
class EditorState(
    initial: Workflow,
    private val workspace: Workspace?,
    private val connectorsProvider: () -> List<Connector> = { emptyList() },
    private val onRefreshConnectors: () -> Unit = {},
    val defaultProvider: AgentProviderId = AgentProviderId.CLAUDE,
    val defaultModel: String? = null,
    val workspaceDefaults: NodeDefaults = NodeDefaults(),
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

    var message by mutableStateOf<String?>(null)

    private var canvasPositions by mutableStateOf<Map<String, Position>?>(null)

    val isDirty: Boolean
        get() = workflow != saved || canvasPositions?.let { it != saved.positions() } == true

    val selectedNode: WorkflowNode? get() = selectedNodeId?.let { workflow.node(it) }

    var skills by mutableStateOf<List<DiscoveredSkill>>(emptyList())
        private set

    private var promptTexts by mutableStateOf<Map<String, String>>(emptyMap())

    init {

        refreshSkills()
        refreshPromptFiles()
    }

    val issues: List<WorkflowIssue>
        get() =
            workflow.validate(
                repoExists = { repo -> workspace?.resolvePath(repo.path)?.exists() ?: true },
                fileExists = { raw -> workspace?.resolvePath(raw)?.isRegularFile() ?: true },
                connector = { name -> connectors.firstOrNull { it.name == name }?.manifest },
                knownSkills = workspace?.let { skills.mapTo(mutableSetOf(), DiscoveredSkill::name) },
                promptText = promptTexts::get,
                defaultProvider = fallbackProvider,
                executableExists = AgentProviders::isInstalled,
            )

    val connectors: List<Connector> get() = connectorsProvider()

    fun connector(name: String): Connector? = if (name.isBlank()) null else connectors.firstOrNull { it.name == name }

    fun refreshConnectors() = onRefreshConnectors()

    fun refreshPromptFiles() {
        val ws = workspace ?: return
        promptTexts =
            workflow.nodes
                .map { it.promptFile }
                .filter { it.isNotBlank() }
                .distinct()
                .mapNotNull { raw -> runCatching { raw to ws.resolvePath(raw).readText() }.getOrNull() }
                .toMap()
    }

    fun refreshSkills() {
        skills = workspace?.let { availableSkills(it, workflow) }.orEmpty()
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
        refreshSkills()
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

        connectFrom = null
    }

    fun addNode(
        type: NodeType,
        position: Position? = null,
    ) {
        val (updated, node) = workflow.addNode(type, position)
        workflow = updated
        selectedNodeId = node.id
        connectFrom = null
    }

    fun updateNode(node: WorkflowNode) {
        workflow = workflow.replaceNode(node)
    }

    fun duplicateNode(id: String) {
        val (updated, clone) = workflow.duplicateNode(id) ?: return
        workflow = updated
        selectedNodeId = clone.id
        connectFrom = null
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
        val stale = promptTexts.filterValues { from in NodeRefs.referencedNodeIds(it) }.keys
        if (stale.isEmpty()) return
        message = "Renamed to $to. ${stale.joinToString()} still reads \${$from…}. " +
            "zopf doesn't edit prompt files, so fix that one by hand."
    }

    fun removeNode(id: String) {
        workflow = workflow.removeNode(id)
        if (selectedNodeId == id) selectedNodeId = null
        if (connectFrom == id) connectFrom = null
    }

    fun startConnecting(from: String) {
        connectFrom = if (connectFrom == from) null else from
    }

    fun cancelConnecting() {
        connectFrom = null
    }

    fun completeConnection(to: String): Boolean {
        val from = connectFrom ?: return false
        connectFrom = null
        if (from == to) return true
        workflow
            .connect(from, to)
            .onSuccess {
                workflow = it
                selectedNodeId = to
            }.onFailure { message = it.message }
        return true
    }

    fun reportCanvasPositions(positions: Map<String, Position>?) {
        canvasPositions = positions
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
    }

    fun revert() {
        workflow = saved
        selectedNodeId = null
        connectFrom = null
    }
}
