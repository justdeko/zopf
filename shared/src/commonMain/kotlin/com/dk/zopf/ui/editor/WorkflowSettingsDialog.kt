package com.dk.zopf.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.PermissionMode
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Sandbox
import com.dk.zopf.model.addRepo
import com.dk.zopf.model.capabilities
import com.dk.zopf.model.removeRepo
import com.dk.zopf.store.workspace.Workspace
import com.dk.zopf.store.workspace.knownRepoPaths
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.workspace.chooseDirectory
import com.dk.zopf.util.Strings

@Composable
fun WorkflowSettingsDialog(
    state: EditorState,
    workspace: Workspace?,
    onDismiss: () -> Unit,
) {
    val workflow = state.workflow
    val defaultProvider = workflow.defaults.provider ?: state.defaultProvider

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.WorkflowSettings.title(workflow.name)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                InspectorField(
                    value = workflow.description,
                    onValueChange = { state.edit { w -> w.copy(description = it) } },
                    label = Strings.Labels.DESCRIPTION,
                    singleLine = false,
                    supportingText = Strings.WorkflowSettings.DESCRIPTION_HINT,
                )

                Gap()
                HorizontalDivider()
                Gap(8)
                ReposSection(state, workspace)

                Gap()
                HorizontalDivider()
                Gap(8)
                ChipListField(
                    label = Strings.WorkflowSettings.SKILL_DIRS_LABEL,
                    values = workflow.skills,
                    onChange = { skills ->
                        state.edit { it.copy(skills = skills) }
                        state.refreshLookups()
                    },
                    addLabel = Strings.WorkflowSettings.SKILL_DIR_ADD,
                    supportingText =
                        Strings.WorkflowSettings.SKILL_DIRS_HINT,
                    onBrowse = {
                        chooseDirectory(Strings.Labels.CHOOSE_SKILL_DIRECTORY, state.startIn(null, workspace))?.let {
                            state.addSkillDirectory(it, nodeId = null)
                        }
                    },
                )

                Gap()
                HorizontalDivider()
                Gap(8)
                SectionLabel(Strings.WorkflowSettings.DEFAULTS)
                Gap(4)
                InspectorDropdown(
                    label = Strings.WorkflowSettings.REPO_LABEL,
                    selected = workflow.defaults.repo,
                    options = state.repoIds,
                    optionLabel = { it },
                    onSelect = { repo -> state.edit { it.copy(defaults = it.defaults.copy(repo = repo)) } },
                    noneLabel = Strings.WorkflowSettings.REPO_NONE,
                    supportingText = Strings.WorkflowSettings.REPO_HINT,
                )
                Gap(8)
                InspectorDropdown(
                    label = "CLI",
                    selected = workflow.defaults.provider,
                    options = AgentProviderId.entries,
                    optionLabel = { it.label },
                    onSelect = { provider ->
                        state.edit { it.copy(defaults = it.defaults.copy(provider = provider)) }
                    },
                    noneLabel = Strings.Labels.defaultOf(state.defaultProvider.label),
                    supportingText = Strings.WorkflowSettings.PROVIDER_HINT,
                )
                if (defaultProvider.capabilities.modelSelection) {
                    Gap(8)
                    ModelField(
                        provider = defaultProvider,
                        selected = workflow.defaults.model,
                        onSelect = { model -> state.edit { it.copy(defaults = it.defaults.copy(model = model)) } },
                        noneLabel = Strings.Labels.cliDefault(defaultProvider.cliValue),
                        supportingText = Strings.WorkflowSettings.modelHint(defaultProvider.label),
                    )
                }
                Gap(8)
                InspectorDropdown(
                    label = Strings.Labels.PERMISSION_MODE,
                    selected = workflow.defaults.permissionMode,
                    options = PermissionMode.entries,
                    optionLabel = { it.cliValue },
                    onSelect = { mode ->
                        state.edit { it.copy(defaults = it.defaults.copy(permissionMode = mode)) }
                    },
                    noneLabel = Strings.Labels.CLI_DEFAULT,
                    supportingText = Strings.WorkflowSettings.PERMISSION_MODE_HINT,
                )
                Gap(8)
                InspectorDropdown(
                    label = Strings.Labels.SANDBOX,
                    selected = workflow.defaults.sandbox,
                    options = Sandbox.entries,
                    optionLabel = { it.cliValue },
                    onSelect = { sandbox ->
                        state.edit { it.copy(defaults = it.defaults.copy(sandbox = sandbox)) }
                    },
                    noneLabel = Strings.Labels.CLI_DEFAULT,
                    supportingText = Strings.WorkflowSettings.SANDBOX_HINT,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.Actions.DONE) } },
    )
}

@Composable
private fun ReposSection(
    state: EditorState,
    workspace: Workspace?,
) {
    val workflow = state.workflow
    var adding by remember { mutableStateOf(false) }

    SectionLabel(Strings.Labels.REPOS)
    if (workflow.repos.isEmpty()) {
        Text(
            Strings.WorkflowSettings.NO_REPOS_DECLARED,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    workflow.repos.forEach { repo ->
        RepoRow(
            repo = repo,
            resolved = workspace?.resolvePath(repo.path)?.toString(),
            onPathChange = { path ->
                state.edit { w -> w.copy(repos = w.repos.map { if (it.id == repo.id) it.copy(path = path) else it }) }
            },
            onRemove = { state.edit { it.removeRepo(repo.id) } },
        )
    }

    if (workspace?.selfRepo != null && workflow.repos.none { it.id == "self" }) {
        Text(
            Strings.WorkflowSettings.selfRepoAvailable(workspace.selfRepo),
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TextButton(onClick = { adding = true }) {
        Icon(ZopfIcons.Add, contentDescription = null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(Strings.WorkflowSettings.ADD_REPO)
    }

    if (adding) {
        AddRepoDialog(
            suggestions = remember(workspace) { workspace?.let { knownRepoPaths(it) }.orEmpty() },
            onDismiss = { adding = false },
            onAdd = { id, path ->
                adding = false
                state.tryEdit { it.addRepo(id, path) }
            },
        )
    }
}

@Composable
private fun RepoRow(
    repo: RepoRef,
    resolved: String?,
    onPathChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        InspectorField(
            value = repo.path,
            onValueChange = onPathChange,
            label = repo.id,
            monospace = true,
            modifier = Modifier.weight(1f),
            supportingText = resolved?.takeIf { it != repo.path },
        )
        IconButton(onClick = onRemove) {
            Icon(ZopfIcons.Delete, contentDescription = Strings.Actions.remove(repo.id), Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AddRepoDialog(
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onAdd: (id: String, path: String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.WorkflowSettings.ADD_REPO) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InspectorField(
                    value = id,
                    onValueChange = { id = it },
                    label = Strings.Labels.ID,
                    monospace = true,
                    supportingText = Strings.WorkflowSettings.REPO_ID_HINT,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    InspectorField(
                        value = path,
                        onValueChange = { path = it },
                        label = Strings.WorkflowSettings.REPO_PATH_LABEL,
                        monospace = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        chooseDirectory(Strings.WorkflowSettings.CHOOSE_REPO)?.let {
                            path = it.toString()

                            if (id.isBlank()) id = it.fileName?.toString().orEmpty()
                        }
                    }) { Text(Strings.Actions.BROWSE) }
                }
                if (suggestions.isNotEmpty()) {
                    SectionLabel(Strings.WorkflowSettings.USED_BY_OTHER_WORKFLOWS)
                    suggestions.take(6).forEach { suggestion ->
                        TextButton(onClick = { path = suggestion }) {
                            Text(
                                suggestion,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(id, path) },
                enabled = id.isNotBlank() && path.isNotBlank(),
            ) { Text(Strings.Actions.ADD) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.Actions.CANCEL) } },
    )
}

@Preview
@Composable
private fun WorkflowSettingsDialogPreview() {
    ZopfTheme {
        WorkflowSettingsDialog(
            state = PreviewFixtures.editorState(),
            workspace = PreviewFixtures.workspace(),
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun AddRepoDialogPreview() {
    ZopfTheme {
        AddRepoDialog(
            suggestions = listOf("../app", "../ios", "../infra"),
            onDismiss = {},
            onAdd = { _, _ -> },
        )
    }
}
