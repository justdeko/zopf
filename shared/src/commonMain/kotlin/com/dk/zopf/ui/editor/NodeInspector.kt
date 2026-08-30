package com.dk.zopf.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.EdgeTrigger
import com.dk.zopf.model.NodeRefs
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.PermissionMode
import com.dk.zopf.model.Sandbox
import com.dk.zopf.model.SchemaField
import com.dk.zopf.model.SchemaFieldType
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.ancestorsOf
import com.dk.zopf.model.canFail
import com.dk.zopf.model.capabilities
import com.dk.zopf.model.ignoredFields
import com.dk.zopf.model.incomingEdges
import com.dk.zopf.model.inheritedModel
import com.dk.zopf.model.label
import com.dk.zopf.model.modelOptions
import com.dk.zopf.model.outgoingEdges
import com.dk.zopf.model.outputFields
import com.dk.zopf.model.providerFor
import com.dk.zopf.store.Workspace
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.theme.colors
import com.dk.zopf.ui.workspace.chooseDirectory
import com.dk.zopf.ui.workspace.chooseFile
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

private val ToolSuggestions =
    listOf("Read", "Edit", "Write", "Grep", "Glob", "Bash", "WebFetch", "WebSearch")

@Composable
fun ModelField(
    provider: AgentProviderId,
    selected: String?,
    onSelect: (String?) -> Unit,
    noneLabel: String,
    supportingText: String? = null,
) {
    val options = provider.modelOptions
    if (options.isEmpty()) {
        InspectorField(
            value = selected.orEmpty(),
            onValueChange = { onSelect(it.trim().ifBlank { null }) },
            label = "Model",
            monospace = true,
            supportingText =
                supportingText
                    ?: "Passed to ${provider.cliValue} as --model. Blank leaves it to ${provider.cliValue}'s own setting.",
        )
        return
    }
    InspectorDropdown(
        label = "Model",
        selected = selected,
        options = options,
        optionLabel = { it },
        onSelect = onSelect,
        noneLabel = noneLabel,
        supportingText = supportingText,
    )
}

@Composable
fun NodeInspector(
    state: EditorState,
    node: WorkflowNode,
    workspace: Workspace?,
    onAddRepo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val issues = state.issuesFor(node.id)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                node.type.icon,
                contentDescription = null,
                Modifier.size(20.dp),
                tint = node.type.colors().accent,
            )
            Spacer(Modifier.width(10.dp))
            Text(node.type.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { state.startConnecting(node.id) }) {
                Icon(ZopfIcons.Link, contentDescription = "Connect from here", Modifier.size(18.dp))
            }
        }

        if (issues.isNotEmpty()) {
            Gap(8)
            NodeIssues(issues)
        }

        Gap()
        NodeIdField(
            id = node.id,
            onRename = { state.renameNode(node.id, it) },
        )

        Gap(8)
        InspectorField(
            value = node.title,
            onValueChange = { state.updateNode(node.copy(title = it)) },
            label = "Title",
            supportingText = "Shown on the card. Defaults to the id.",
        )

        Gap()
        HorizontalDivider()

        when (node.type) {
            NodeType.AGENT -> AgentFields(state, node, workspace, onAddRepo)
            NodeType.SHELL -> ShellFields(state, node, onAddRepo)
            NodeType.CONNECTOR -> ConnectorFields(state, node)
            NodeType.BRANCH -> BranchFields(state, node)
            NodeType.GATE -> GateFields(state, node)
            NodeType.INPUT -> InputFields(state, node)
        }

        Gap()
        HorizontalDivider()
        Gap(8)
        Connections(state, node)

        Gap()
        HorizontalDivider()
        Gap(4)
        DangerButton("Delete ${node.displayTitle}", onClick = { state.removeNode(node.id) })
    }
}

@Composable
private fun NodeIdField(
    id: String,
    onRename: (String) -> Unit,
) {
    var draft by remember(id) { mutableStateOf(id) }
    val changed = draft != id

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Id") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        supportingText = {
            Text(if (changed) "Press ✓ to rename. References are updated too." else "Used by \${$id.result}")
        },
        trailingIcon = {
            if (changed) {
                IconButton(onClick = { onRename(draft.trim()) }) {
                    Icon(ZopfIcons.Check, contentDescription = "Rename", Modifier.size(18.dp))
                }
            }
        },
    )
}

@Composable
private fun AgentFields(
    state: EditorState,
    node: WorkflowNode,
    workspace: Workspace?,
    onAddRepo: () -> Unit,
) {
    val workflow = state.workflow
    val provider = state.resolvedWorkflow.providerFor(node, state.fallbackProvider)
    val can = provider.capabilities

    LaunchedEffect(node.id, workflow.repos, workflow.skills) { state.refreshSkills() }

    Gap()
    InspectorDropdown(
        label = "CLI",
        selected = node.provider,
        options = AgentProviderId.entries,
        optionLabel = { it.label },
        onSelect = { state.updateNode(node.copy(provider = it)) },
        noneLabel = "Default (${provider.label})",
        supportingText = "Which agent CLI runs this node. Absent everywhere means Claude Code.",
    )

    Gap()
    PromptSection(state, node, workspace)

    Gap()
    RepoDropdown(state, node, onAddRepo)

    if (can.extraDirectories) {
        Gap(8)
        ToggleChipField(
            label = "Also read",
            options = state.repoIds.filterNot { it == node.repo },
            selected = node.alsoRead,
            onChange = { state.updateNode(node.copy(alsoRead = it)) },
            emptyHint = "Add more repos to give this node extra directories to read.",
        )
    }

    if (can.skills) {
        Gap(8)
        SkillsSection(state, node, workspace)
    }

    if (can.toolPermissions) {
        Gap(8)
        ChipListField(
            label = "Allowed tools",
            values = node.allowedTools,
            onChange = { state.updateNode(node.copy(allowedTools = it)) },
            addLabel = "Tool rule",
            suggestions = ToolSuggestions,
            supportingText =
                "Passed straight to --allowedTools. Narrow a tool with a pattern, " +
                    "like Bash(<command> *).",
        )
    }

    if (can.modelSelection) {
        Gap()
        ModelField(
            provider = provider,
            selected = node.model,
            onSelect = { state.updateNode(node.copy(model = it)) },
            noneLabel =
                state.resolvedWorkflow
                    .inheritedModel(provider, state.defaultProvider, state.defaultModel)
                    ?.let { "Inherited ($it)" }
                    ?: "Whatever ${provider.cliValue} is set to",
        )
    }

    if (can.toolPermissions) {
        Gap(8)
        InspectorDropdown(
            label = "Permission mode",
            selected = node.permissionMode,
            options = PermissionMode.entries,
            optionLabel = { it.cliValue },
            onSelect = { state.updateNode(node.copy(permissionMode = it)) },
            noneLabel =
                workflow.defaults.permissionMode?.let { "Workflow default (${it.cliValue})" }
                    ?: "Workflow default",
            supportingText = "What this node may do without asking.",
        )
    }

    if (can.sandbox) {
        Gap(8)
        InspectorDropdown(
            label = "Sandbox",
            selected = node.sandbox,
            options = Sandbox.entries,
            optionLabel = { it.cliValue },
            onSelect = { state.updateNode(node.copy(sandbox = it)) },
            noneLabel =
                workflow.defaults.sandbox?.let { "Workflow default (${it.cliValue})" }
                    ?: "Whatever ${provider.cliValue} is configured for",
            supportingText =
                "Chosen before launch, since ${provider.label} has no way to ask mid-turn. " +
                    "Nothing here can be approved inline.",
        )
    }

    if (can.outputSchema) {
        Gap()
        OutputSchemaSection(state, node)
    }

    provider.ignoredFields(node).takeIf { it.isNotEmpty() }?.let {
        Gap(8)
        Text(
            "${provider.label} has no ${it.joinToString()}, so ${if (it.size == 1) "it is" else "they are"} " +
                "left out of the command. Change the CLI, or clear ${if (it.size == 1) "it" else "them"} in the YAML.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun OutputSchemaSection(
    state: EditorState,
    node: WorkflowNode,
) {
    fun change(schema: List<SchemaField>) = state.updateNode(node.copy(schema = schema))

    if (node.schema.isEmpty()) {
        TextButton(onClick = { change(listOf(SchemaField(name = ""))) }) {
            Icon(ZopfIcons.Add, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Declare output fields…")
        }
        Text(
            "With no schema, the node answers in prose and \${${node.id}.result} is that prose.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Output schema")

        node.schema.forEachIndexed { index, field ->
            Gap(8)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                InspectorField(
                    modifier = Modifier.weight(1f),
                    value = field.name,
                    onValueChange = { change(node.schema.replacing(index, field.copy(name = it.trim()))) },
                    label = "Field",
                    monospace = true,
                    isError = field.name.isBlank(),
                )
                Spacer(Modifier.width(8.dp))
                InspectorDropdown(
                    modifier = Modifier.width(132.dp),
                    label = "Type",
                    selected = field.type,
                    options = SchemaFieldType.entries,
                    optionLabel = { it.jsonType },
                    onSelect = { change(node.schema.replacing(index, field.copy(type = it ?: field.type))) },
                )
                IconButton(onClick = { change(node.schema.filterIndexed { at, _ -> at != index }) }) {
                    Icon(ZopfIcons.Delete, contentDescription = "Remove field", Modifier.size(16.dp))
                }
            }
            InspectorField(
                value = field.description,
                onValueChange = { change(node.schema.replacing(index, field.copy(description = it))) },
                label = "Description",
                supportingText = "Sent to the model as this field's description.",
            )
            Gap(4)
            FilterChip(
                selected = !field.required,
                onClick = { change(node.schema.replacing(index, field.copy(required = !field.required))) },
                label = { Text("Optional") },
            )
        }

        Gap(8)
        TextButton(onClick = { change(node.schema + SchemaField(name = "")) }) {
            Icon(ZopfIcons.Add, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Field")
        }

        val named = node.schema.filter { it.name.isNotBlank() }
        if (named.isNotEmpty()) {
            Gap(8)
            SectionLabel("Outputs")
            Gap(4)
            Text(
                NodeRefs.reference(node.id, "result") + ": the whole object, as JSON text",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            named.forEach { field ->
                Text(
                    NodeRefs.reference(node.id, field.name) + field.outputNote(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Gap(8)
        Text(
            buildString {
                append("The node fills this shape instead of writing freely.")
                if (node.schema.any { it.isNested }) {
                    append(
                        " An object or array field is up to the model: it picks the keys and the " +
                            "size, and the value arrives whole, since \${node.field} only goes one " +
                            "level deep. Pass it to a shell node running jq, or split it into " +
                            "plain fields you can compare.",
                    )
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun SchemaField.outputNote(): String =
    when {
        isNested && description.isNotBlank() -> ": $description, as JSON text"
        isNested -> ": JSON text, too deep to compare"
        description.isNotBlank() -> ": $description"
        else -> ""
    }

private fun <T> List<T>.replacing(
    index: Int,
    value: T,
): List<T> = mapIndexed { at, existing -> if (at == index) value else existing }

@Composable
private fun PromptSection(
    state: EditorState,
    node: WorkflowNode,
    workspace: Workspace?,
) {
    LaunchedEffect(node.id, node.promptFile) { state.refreshPromptFiles() }

    if (node.promptFile.isBlank()) {
        PromptField(
            value = node.prompt,
            references = state.upstreamReferences(node.id),
            onChange = { state.updateNode(node.copy(prompt = it)) },
        )
        TextButton(onClick = { state.pickPromptFile(node, workspace) }) {
            Icon(ZopfIcons.Folder, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Read the prompt from a .md file…")
        }
        return
    }

    val resolved = remember(node.promptFile, workspace) { workspace?.resolvePath(node.promptFile) }

    val preview =
        remember(node.promptFile, workspace) {
            resolved?.let { runCatching { it.readText() }.getOrNull() }
        }

    InspectorField(
        value = node.promptFile,
        onValueChange = { state.updateNode(node.copy(promptFile = it)) },
        label = "Prompt file",
        monospace = true,
        supportingText = resolved?.toString()?.takeIf { it != node.promptFile },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { state.pickPromptFile(node, workspace) }) { Text("Browse…") }
        TextButton(onClick = { state.updateNode(node.copy(promptFile = "")) }) { Text("Type it here instead") }
    }
    preview?.let {
        Text(
            it
                .lineSequence()
                .take(PromptPreviewLines)
                .joinToString("\n")
                .take(PromptPreviewChars),
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "Read when the node runs. \${node.field} inside it is interpolated the same way. The editor " +
            "can't check those references, or update them on rename.",
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SkillsSection(
    state: EditorState,
    node: WorkflowNode,
    workspace: Workspace?,
) {
    Column(Modifier.fillMaxWidth()) {
        ToggleChipField(
            label = "Skills",
            options = state.skills.map { it.name },
            selected = node.skills,
            onChange = { state.updateNode(node.copy(skills = it)) },
            emptyHint =
                "None found in this workflow's repos, the workspace, or ~/.claude/skills. " +
                    "Add a directory below.",
        )

        state.skills.filter { it.name in node.skills }.takeIf { it.isNotEmpty() }?.let { chosen ->
            Text(
                chosen.joinToString(" · ") { "${it.name} in ${it.source}" },
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(
            onClick = {
                chooseDirectory("Choose a skill directory", state.startIn(node, workspace))?.let {
                    state.addSkillDirectory(it, node.id)
                }
            },
        ) {
            Icon(ZopfIcons.Folder, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add a skill directory…")
        }

        Text(
            "A ticked skill is passed to the session and named in the prompt, so it is loaded and " +
                "asked for. Skills in this node's own .claude/skills or in ~/.claude/skills load " +
                "anyway, so they are only named.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun EditorState.pickPromptFile(
    node: WorkflowNode,
    workspace: Workspace?,
) {
    val picked = chooseFile("Choose a prompt", PromptFileExtensions, startIn(node, workspace)) ?: return
    updateNode(node.copy(promptFile = picked.toString()))
    if (picked.extension.lowercase() !in PromptFileExtensions) {
        message = "${picked.fileName} isn't markdown. Its text is sent as the prompt anyway."
    }
}

internal fun EditorState.startIn(
    node: WorkflowNode?,
    workspace: Workspace?,
): Path? {
    if (workspace == null) return null
    return workspace.resolveRepo(workflow, node?.repo)?.takeIf { it.isDirectory() }
        ?: workspace.selfRepo
        ?: workspace.root
}

private val PromptFileExtensions = setOf("md", "markdown", "txt")

private const val PromptPreviewLines = 6
private const val PromptPreviewChars = 400

@Composable
private fun ShellFields(
    state: EditorState,
    node: WorkflowNode,
    onAddRepo: () -> Unit,
) {
    Gap()
    PromptField(
        value = node.command,
        references = state.upstreamReferences(node.id),
        onChange = { state.updateNode(node.copy(command = it)) },
        label = "Command",
        minLines = 2,
        supportingText = "Run with zsh -lc, so your login PATH applies.",
    )

    Gap()
    RepoDropdown(state, node, onAddRepo)
}

@Composable
private fun ConnectorFields(
    state: EditorState,
    node: WorkflowNode,
) {
    LaunchedEffect(node.id, node.connector) { state.refreshConnectors() }

    val connector = state.connector(node.connector)
    val references = state.upstreamReferences(node.id)

    Gap()
    InspectorDropdown(
        label = "Connector",
        selected = node.connector.ifBlank { null },
        options = state.connectors.map { it.name },
        optionLabel = { it },
        onSelect = { state.updateNode(node.copy(connector = it.orEmpty())) },
        noneLabel = "Choose a connector",
        supportingText =
            connector?.manifest?.summary
                ?: "A folder under connectors/. Workspace first, then ~/.zopf/connectors.",
    )

    if (state.connectors.isEmpty()) {
        Gap(8)
        Text(
            "No connectors yet. Add one on the Connectors screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val declared = connector?.manifest?.inputs.orEmpty()

    var focusedInput by remember(node.id, node.connector) { mutableStateOf<String?>(null) }

    if (declared.isNotEmpty()) {
        Gap()
        SectionLabel("Inputs")
        declared.forEach { input ->
            Gap(8)
            InspectorField(
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedInput = input.name },
                value = node.inputs[input.name] ?: "",
                onValueChange = {
                    val updated = if (it.isBlank()) node.inputs - input.name else node.inputs + (input.name to it)
                    state.updateNode(node.copy(inputs = updated))
                },
                label = input.name + if (input.required) " *" else "",
                monospace = true,
                isError = input.required && input.default.isBlank() && node.inputs[input.name].isNullOrBlank(),
                supportingText =
                    buildString {
                        append(input.description.ifBlank { if (input.required) "Required" else "Optional" })
                        if (input.default.isNotBlank()) append(" · defaults to ${input.default}")
                    },
            )
        }
        if (references.isNotEmpty()) {
            Gap(8)
            ReferenceChips(
                groups = references,
                onInsert = { reference ->
                    val target = focusedInput ?: declared.first().name
                    val updated = (node.inputs[target] ?: "") + reference
                    state.updateNode(node.copy(inputs = node.inputs + (target to updated)))
                },
            )
        }
    }

    val extras = node.inputs.filterKeys { key -> declared.none { it.name == key } }
    if (extras.isNotEmpty() || declared.isEmpty()) {
        Gap(8)
        KeyValueField(
            label = if (declared.isEmpty()) "Inputs" else "Undeclared inputs",
            values = extras,
            onChange = { state.updateNode(node.copy(inputs = node.inputs.filterKeys { it !in extras } + it)) },
        )
    }

    val outputs = connector?.manifest?.outputs.orEmpty()
    if (outputs.isNotEmpty()) {
        Gap()
        SectionLabel("Outputs")
        Gap(4)
        outputs.forEach { output ->
            Text(
                NodeRefs.reference(node.id, output.name) +
                    output.description
                        .takeIf { it.isNotBlank() }
                        ?.let { ": $it" }
                        .orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    Gap(8)
    Text(
        "Input values interpolate \${node.field} like prompts do, and arrive as one JSON object on " +
            "the script's stdin. The reply is \${${node.id}.result}, plus one field per key of the " +
            "object.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BranchFields(
    state: EditorState,
    node: WorkflowNode,
) {
    Gap()
    PromptField(
        value = node.expression,
        references = state.upstreamReferences(node.id),
        onChange = { state.updateNode(node.copy(expression = it)) },
        label = "Expression",
        minLines = 1,
        supportingText = "Interpolated, then compared with == or !=, or read as truthy. No other syntax.",
    )

    Gap()
    Text(
        "Set each outgoing edge to the true or the false side under Connections below.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InputFields(
    state: EditorState,
    node: WorkflowNode,
) {
    Gap()
    PromptField(
        value = node.prompt,
        references = state.upstreamReferences(node.id),
        onChange = { state.updateNode(node.copy(prompt = it)) },
        label = "Question",
        minLines = 2,
        supportingText = "Interpolated, so it can quote an earlier node's output.",
    )

    Gap(8)
    ChipListField(
        label = "Choices",
        values = node.choices,
        onChange = { state.updateNode(node.copy(choices = it)) },
        addLabel = "Choice",
        supportingText =
            "Leave empty for a free-text answer. A question with choices can also be answered " +
                "from the menu bar, which has no text field.",
    )

    Gap(8)
    InspectorField(
        value = node.default,
        onValueChange = { state.updateNode(node.copy(default = it)) },
        label = "Default",
        supportingText =
            if (node.choices.isEmpty()) {
                "Prefills the answer field."
            } else {
                "Which choice starts selected."
            },
    )

    Gap()
    Text(
        "The run waits here until you answer, in the app or from the menu bar. Cancelling skips " +
            "the rest of the run, like rejecting a gate.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GateFields(
    state: EditorState,
    node: WorkflowNode,
) {
    Gap()
    Text(
        "The run waits here until you approve it, in the app or from the menu bar. The title is " +
            "the question you'll be asked.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Gap()
    PromptField(
        value = node.prompt,
        references = state.upstreamReferences(node.id),
        onChange = { state.updateNode(node.copy(prompt = it)) },
        label = "What to show",
        minLines = 2,
        supportingText =
            "Interpolated, so it can quote what you're approving. A whole result is fine: the " +
                "run shows the first lines and expands on a click.",
    )
}

@Composable
private fun PromptField(
    value: String,
    references: List<UpstreamNodeReferences>,
    onChange: (String) -> Unit,
    label: String = "Prompt",
    minLines: Int = 5,
    supportingText: String? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) {
        field = TextFieldValue(value, TextRange(value.length))
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                onChange(it.text)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            supportingText = supportingText?.let { { Text(it) } },
        )
        Gap(8)
        ReferenceChips(
            groups = references,
            onInsert = { reference ->
                val at = field.selection.end.coerceIn(0, field.text.length)
                val updated = field.text.substring(0, at) + reference + field.text.substring(at)
                field = TextFieldValue(updated, TextRange(at + reference.length))
                onChange(updated)
            },
        )
    }
}

@Composable
private fun RepoDropdown(
    state: EditorState,
    node: WorkflowNode,
    onAddRepo: () -> Unit,
) {
    val default = state.workflow.defaults.repo
    Column(Modifier.fillMaxWidth()) {
        InspectorDropdown(
            label = "Runs in",
            selected = node.repo,
            options = state.repoIds,
            optionLabel = { it },
            onSelect = { state.updateNode(node.copy(repo = it)) },
            noneLabel = default?.let { "Workflow default ($it)" } ?: "Workflow default",
            supportingText = "Becomes the process working directory.",
        )
        TextButton(onClick = onAddRepo) {
            Icon(ZopfIcons.Add, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add a repo to this workflow")
        }
    }
}

@Composable
private fun Connections(
    state: EditorState,
    node: WorkflowNode,
) {
    val workflow = state.workflow
    val incoming = workflow.incomingEdges(node.id)
    val outgoing = workflow.outgoingEdges(node.id)

    SectionLabel("Connections")
    if (incoming.isEmpty() && outgoing.isEmpty()) {
        Text(
            "Nothing connected yet. Use the link handle on the card to draw an edge.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    incoming.forEach { edge ->
        EdgeRow(
            text = "← ${workflow.node(edge.from)?.displayTitle ?: edge.from}",
            onRemove = { state.disconnect(edge.from, edge.to) },
        )
    }
    outgoing.forEach { edge ->
        EdgeRow(
            text = "→ ${workflow.node(edge.to)?.displayTitle ?: edge.to}",
            onRemove = { state.disconnect(edge.from, edge.to) },
            trailing = {
                Row {
                    if (node.type == NodeType.BRANCH) {
                        listOf(true, false).forEach { side ->
                            FilterChip(
                                selected = edge.condition == side,
                                onClick = {
                                    state.setEdgeCondition(
                                        edge.from,
                                        edge.to,
                                        if (edge.condition == side) null else side,
                                    )
                                },
                                label = { Text("$side", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }

                    if (node.type.canFail()) {
                        val onFailure = edge.on == EdgeTrigger.FAILURE
                        FilterChip(
                            selected = onFailure,
                            onClick = {
                                state.setEdgeTrigger(
                                    edge.from,
                                    edge.to,
                                    if (onFailure) EdgeTrigger.SUCCESS else EdgeTrigger.FAILURE,
                                )
                            },
                            label = { Text("on failure", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun NodeIssues(issues: List<WorkflowIssue>) {
    Column(Modifier.fillMaxWidth()) {
        issues.forEach { issue ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Icon(
                    ZopfIcons.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                    tint = issue.tint(),
                )
                Spacer(Modifier.width(8.dp))
                Text(issue.message, style = MaterialTheme.typography.labelSmall, color = issue.tint())
            }
        }
    }
}

@Composable
internal fun WorkflowIssue.tint() =
    when (severity) {
        WorkflowIssue.Severity.ERROR -> MaterialTheme.colorScheme.error
        WorkflowIssue.Severity.WARNING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun EditorState.upstreamReferences(nodeId: String): List<UpstreamNodeReferences> =
    workflow
        .ancestorsOf(nodeId)
        .mapNotNull { workflow.node(it) }
        .map { upstream -> UpstreamNodeReferences(upstream, upstream.outputFields(connector(upstream.connector)?.manifest)) }

private val InspectorPreviewWidth = 340.dp

@Composable
private fun InspectorPreviewSurface(content: @Composable () -> Unit) {
    ZopfTheme {
        Surface(Modifier.width(InspectorPreviewWidth)) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

@Preview
@Composable
private fun NodeInspectorPreview() {
    val state = PreviewFixtures.editorState()
    InspectorPreviewSurface {
        NodeInspector(
            state = state,
            node = state.workflow.node("plan")!!,
            workspace = PreviewFixtures.workspace(),
            onAddRepo = {},
        )
    }
}

@Preview
@Composable
private fun AgentFieldsPreview() {
    val state = PreviewFixtures.editorState()
    InspectorPreviewSurface {
        Column { AgentFields(state, state.workflow.node("plan")!!, PreviewFixtures.workspace(), onAddRepo = {}) }
    }
}

@Preview
@Composable
private fun ConnectorFieldsPreview() {
    val state = PreviewFixtures.editorState()
    InspectorPreviewSurface {
        Column { ConnectorFields(state, state.workflow.node("notify")!!) }
    }
}

@Preview
@Composable
private fun PromptSectionPreview() {
    val state = PreviewFixtures.editorState()
    InspectorPreviewSurface {
        Column { PromptSection(state, state.workflow.node("plan")!!, PreviewFixtures.workspace()) }
    }
}

@Preview
@Composable
private fun ConnectionsPreview() {
    val state = PreviewFixtures.editorState()
    InspectorPreviewSurface {
        Column { Connections(state, state.workflow.node("plan")!!) }
    }
}
