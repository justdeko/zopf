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
import com.dk.zopf.store.workspace.Workspace
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.theme.colors
import com.dk.zopf.ui.workspace.chooseDirectory
import com.dk.zopf.ui.workspace.chooseFile
import com.dk.zopf.util.Strings
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
    SuggestingField(
        label = Strings.Inspector.MODEL_LABEL,
        value = selected,
        suggestions = provider.modelOptions,
        onChange = onSelect,
        noneLabel = noneLabel,
        monospace = true,
        supportingText =
            supportingText
                ?: Strings.Inspector.modelHint(provider.cliValue),
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
                Icon(ZopfIcons.Link, contentDescription = Strings.Actions.CONNECT_FROM_HERE, Modifier.size(18.dp))
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
            label = Strings.Inspector.TITLE_LABEL,
            supportingText = Strings.Inspector.TITLE_HINT,
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
        DangerButton(Strings.Inspector.deleteNode(node.displayTitle), onClick = { state.removeNode(node.id) })
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
        label = { Text(Strings.Labels.ID) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        supportingText = {
            Text(if (changed) Strings.Inspector.RENAME_HINT else Strings.Inspector.idHint(id))
        },
        trailingIcon = {
            if (changed) {
                IconButton(onClick = { onRename(draft.trim()) }) {
                    Icon(ZopfIcons.Check, contentDescription = Strings.Actions.RENAME, Modifier.size(18.dp))
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
    val inherited = state.resolvedWorkflow.defaults.provider ?: state.fallbackProvider
    val can = provider.capabilities

    LaunchedEffect(node.id, workflow.repos, workflow.skills) { state.refreshLookups() }

    Gap()
    InspectorDropdown(
        label = "CLI",
        selected = node.provider,
        options = AgentProviderId.entries,
        optionLabel = { it.label },
        onSelect = { state.updateNode(node.copy(provider = it)) },
        noneLabel = Strings.Labels.defaultOf(inherited.label),
        supportingText = Strings.Inspector.PROVIDER_HINT,
    )

    Gap()
    PromptSection(state, node, workspace)

    Gap()
    RepoDropdown(state, node, onAddRepo)

    if (can.extraDirectories) {
        Gap(8)
        ToggleChipField(
            label = Strings.Inspector.ALSO_READ_LABEL,
            options = state.repoIds.filterNot { it == node.repo },
            selected = node.alsoRead,
            onChange = { state.updateNode(node.copy(alsoRead = it)) },
            emptyHint = Strings.Inspector.ALSO_READ_EMPTY,
        )
    }

    if (can.skills) {
        Gap(8)
        SkillsSection(state, node, workspace)
    }

    if (can.toolPermissions) {
        Gap(8)
        ChipListField(
            label = Strings.Inspector.ALLOWED_TOOLS_LABEL,
            values = node.allowedTools,
            onChange = { state.updateNode(node.copy(allowedTools = it)) },
            addLabel = Strings.Inspector.ALLOWED_TOOLS_ADD,
            suggestions = ToolSuggestions,
            supportingText =
                Strings.Inspector.ALLOWED_TOOLS_HINT,
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
                    ?.let { Strings.Inspector.inherited(it) }
                    ?: Strings.Labels.cliDefault(provider.cliValue),
        )
    }

    if (can.toolPermissions) {
        Gap(8)
        InspectorDropdown(
            label = Strings.Labels.PERMISSION_MODE,
            selected = node.permissionMode,
            options = PermissionMode.entries,
            optionLabel = { it.cliValue },
            onSelect = { state.updateNode(node.copy(permissionMode = it)) },
            noneLabel =
                workflow.defaults.permissionMode?.let { Strings.Inspector.workflowDefault(it.cliValue) }
                    ?: Strings.Inspector.WORKFLOW_DEFAULT,
            supportingText = Strings.Inspector.PERMISSION_MODE_HINT,
        )
    }

    if (can.sandbox) {
        Gap(8)
        InspectorDropdown(
            label = Strings.Labels.SANDBOX,
            selected = node.sandbox,
            options = Sandbox.entries,
            optionLabel = { it.cliValue },
            onSelect = { state.updateNode(node.copy(sandbox = it)) },
            noneLabel =
                workflow.defaults.sandbox?.let { Strings.Inspector.workflowDefault(it.cliValue) }
                    ?: Strings.Labels.cliDefault(provider.cliValue),
            supportingText = Strings.Inspector.sandboxHint(provider.label),
        )
    }

    if (can.outputSchema) {
        Gap()
        OutputSchemaSection(state, node)
    }

    provider.ignoredFields(node).takeIf { it.isNotEmpty() }?.let {
        Gap(8)
        Text(
            Strings.Inspector.ignoredFields(provider.label, it.joinToString(), it.size),
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
            Text(Strings.Inspector.DECLARE_OUTPUT_FIELDS)
        }
        Text(
            Strings.Inspector.noSchemaHint(node.id),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(Strings.Inspector.OUTPUT_SCHEMA)

        node.schema.forEachIndexed { index, field ->
            Gap(8)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                InspectorField(
                    modifier = Modifier.weight(1f),
                    value = field.name,
                    onValueChange = { change(node.schema.replacing(index, field.copy(name = it.trim()))) },
                    label = Strings.Inspector.FIELD_LABEL,
                    monospace = true,
                    isError = field.name.isBlank(),
                )
                Spacer(Modifier.width(8.dp))
                InspectorDropdown(
                    modifier = Modifier.width(132.dp),
                    label = Strings.Inspector.TYPE_LABEL,
                    selected = field.type,
                    options = SchemaFieldType.entries,
                    optionLabel = { it.jsonType },
                    onSelect = { change(node.schema.replacing(index, field.copy(type = it ?: field.type))) },
                )
                IconButton(onClick = { change(node.schema.filterIndexed { at, _ -> at != index }) }) {
                    Icon(ZopfIcons.Delete, contentDescription = Strings.Inspector.REMOVE_FIELD, Modifier.size(16.dp))
                }
            }
            InspectorField(
                value = field.description,
                onValueChange = { change(node.schema.replacing(index, field.copy(description = it))) },
                label = Strings.Labels.DESCRIPTION,
                supportingText = Strings.Inspector.FIELD_DESCRIPTION_HINT,
            )
            Gap(4)
            FilterChip(
                selected = !field.required,
                onClick = { change(node.schema.replacing(index, field.copy(required = !field.required))) },
                label = { Text(Strings.Labels.OPTIONAL) },
            )
        }

        Gap(8)
        TextButton(onClick = { change(node.schema + SchemaField(name = "")) }) {
            Icon(ZopfIcons.Add, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(Strings.Inspector.FIELD_LABEL)
        }

        val named = node.schema.filter { it.name.isNotBlank() }
        if (named.isNotEmpty()) {
            Gap(8)
            SectionLabel(Strings.Labels.OUTPUTS)
            Gap(4)
            Text(
                NodeRefs.reference(node.id, "result") + Strings.Inspector.RESULT_IS_WHOLE_OBJECT,
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
                append(Strings.Inspector.SCHEMA_INTRO)
                if (node.schema.any { it.isNested }) {
                    append(
                        Strings.Inspector.SCHEMA_NESTED_NOTE,
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
        isNested && description.isNotBlank() -> Strings.Inspector.nestedFieldDescription(description)
        isNested -> Strings.Inspector.NESTED_TOO_DEEP
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
    LaunchedEffect(node.id, node.promptFile) { state.refreshLookups() }

    if (node.promptFile.isBlank()) {
        PromptField(
            value = node.prompt,
            references = state.upstreamReferences(node.id),
            onChange = { state.updateNode(node.copy(prompt = it)) },
        )
        TextButton(onClick = { state.pickPromptFile(node, workspace) }) {
            Icon(ZopfIcons.Folder, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(Strings.Inspector.USE_PROMPT_FILE)
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
        label = Strings.Inspector.PROMPT_FILE_LABEL,
        monospace = true,
        supportingText = resolved?.toString()?.takeIf { it != node.promptFile },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { state.pickPromptFile(node, workspace) }) { Text(Strings.Actions.BROWSE) }
        TextButton(onClick = { state.updateNode(node.copy(promptFile = "")) }) { Text(Strings.Inspector.TYPE_IT_HERE_INSTEAD) }
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
        Strings.Inspector.PROMPT_FILE_HINT,
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
            label = Strings.Inspector.SKILLS_LABEL,
            options = state.skills.map { it.name },
            selected = node.skills,
            onChange = { state.updateNode(node.copy(skills = it)) },
            emptyHint =
                Strings.Inspector.SKILLS_EMPTY,
        )

        state.skills.filter { it.name in node.skills }.takeIf { it.isNotEmpty() }?.let { chosen ->
            Text(
                chosen.joinToString(" · ") { Strings.Inspector.skillIn(it.name, it.source) },
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(
            onClick = {
                chooseDirectory(Strings.Labels.CHOOSE_SKILL_DIRECTORY, state.startIn(node, workspace))?.let {
                    state.addSkillDirectory(it, node.id)
                }
            },
        ) {
            Icon(ZopfIcons.Folder, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(Strings.Inspector.ADD_SKILL_DIRECTORY)
        }

        Text(
            Strings.Inspector.SKILLS_HINT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun EditorState.pickPromptFile(
    node: WorkflowNode,
    workspace: Workspace?,
) {
    val picked = chooseFile(Strings.Inspector.CHOOSE_PROMPT, PromptFileExtensions, startIn(node, workspace)) ?: return
    updateNode(node.copy(promptFile = picked.toString()))
    if (picked.extension.lowercase() !in PromptFileExtensions) {
        message = Strings.Inspector.notMarkdown(picked.fileName)
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
        label = Strings.Inspector.COMMAND_LABEL,
        minLines = 2,
        supportingText = Strings.Inspector.COMMAND_HINT,
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
        label = Strings.Inspector.CONNECTOR_LABEL,
        selected = node.connector.ifBlank { null },
        options = state.connectors.map { it.name },
        optionLabel = { it },
        onSelect = { state.updateNode(node.copy(connector = it.orEmpty())) },
        noneLabel = Strings.Inspector.CONNECTOR_NONE,
        supportingText =
            connector?.manifest?.summary
                ?: Strings.Inspector.CONNECTOR_HINT,
    )

    if (state.connectors.isEmpty()) {
        Gap(8)
        Text(
            Strings.Inspector.NO_CONNECTORS_YET,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val declared = connector?.manifest?.inputs.orEmpty()

    var focusedInput by remember(node.id, node.connector) { mutableStateOf<String?>(null) }

    if (declared.isNotEmpty()) {
        Gap()
        SectionLabel(Strings.Labels.INPUTS)
        declared.forEach { input ->
            Gap(8)
            InspectorField(
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedInput = input.name },
                value = node.inputs[input.name] ?: "",
                onValueChange = {
                    val updated = if (it.isBlank()) node.inputs - input.name else node.inputs + (input.name to it)
                    state.updateNode(node.copy(inputs = updated))
                },
                label = input.name + if (input.required) Strings.Connectors.REQUIRED_MARKER else "",
                monospace = true,
                isError = input.required && input.default.isBlank() && node.inputs[input.name].isNullOrBlank(),
                supportingText =
                    buildString {
                        append(
                            input.description.ifBlank {
                                if (input.required) Strings.Inspector.INPUT_REQUIRED else Strings.Labels.OPTIONAL
                            },
                        )
                        if (input.default.isNotBlank()) append(Strings.Inspector.inputDefault(input.default))
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
            label = if (declared.isEmpty()) Strings.Labels.INPUTS else Strings.Inspector.UNDECLARED_INPUTS,
            values = extras,
            onChange = { state.updateNode(node.copy(inputs = node.inputs.filterKeys { it !in extras } + it)) },
        )
    }

    val outputs = connector?.manifest?.outputs.orEmpty()
    if (outputs.isNotEmpty()) {
        Gap()
        SectionLabel(Strings.Labels.OUTPUTS)
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
        Strings.Inspector.connectorHelp(node.id),
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
        label = Strings.Inspector.EXPRESSION_LABEL,
        minLines = 1,
        supportingText = Strings.Inspector.EXPRESSION_HINT,
    )

    Gap()
    Text(
        Strings.Inspector.BRANCH_HELP,
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
        label = Strings.Inspector.QUESTION_LABEL,
        minLines = 2,
        supportingText = Strings.Inspector.QUESTION_HINT,
    )

    Gap(8)
    ChipListField(
        label = Strings.Inspector.CHOICES_LABEL,
        values = node.choices,
        onChange = { state.updateNode(node.copy(choices = it)) },
        addLabel = Strings.Inspector.CHOICES_ADD,
        supportingText =
            Strings.Inspector.CHOICES_HINT,
    )

    Gap(8)
    InspectorField(
        value = node.default,
        onValueChange = { state.updateNode(node.copy(default = it)) },
        label = Strings.Inspector.DEFAULT_LABEL,
        supportingText =
            if (node.choices.isEmpty()) {
                Strings.Inspector.DEFAULT_FREE_TEXT_HINT
            } else {
                Strings.Inspector.DEFAULT_CHOICE_HINT
            },
    )

    Gap()
    Text(
        Strings.Inspector.INPUT_HELP,
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
        Strings.Inspector.GATE_HELP,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Gap()
    PromptField(
        value = node.prompt,
        references = state.upstreamReferences(node.id),
        onChange = { state.updateNode(node.copy(prompt = it)) },
        label = Strings.Inspector.GATE_SHOW_LABEL,
        minLines = 2,
        supportingText =
            Strings.Inspector.GATE_SHOW_HINT,
    )
}

@Composable
private fun PromptField(
    value: String,
    references: List<UpstreamNodeReferences>,
    onChange: (String) -> Unit,
    label: String = Strings.Inspector.PROMPT_LABEL,
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
            label = Strings.Inspector.RUNS_IN_LABEL,
            selected = node.repo,
            options = state.repoIds,
            optionLabel = { it },
            onSelect = { state.updateNode(node.copy(repo = it)) },
            noneLabel = default?.let { Strings.Inspector.workflowDefault(it) } ?: Strings.Inspector.WORKFLOW_DEFAULT,
            supportingText = Strings.Inspector.RUNS_IN_HINT,
        )
        TextButton(onClick = onAddRepo) {
            Icon(ZopfIcons.Add, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(Strings.Inspector.ADD_REPO_TO_WORKFLOW)
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

    SectionLabel(Strings.Inspector.CONNECTIONS)
    if (incoming.isEmpty() && outgoing.isEmpty()) {
        Text(
            Strings.Inspector.NO_CONNECTIONS,
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
                            label = { Text(Strings.Inspector.ON_FAILURE, style = MaterialTheme.typography.labelSmall) },
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
